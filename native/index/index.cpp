#include "index/index.h"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace retrieve {

using namespace format;

std::unique_ptr<Index> Index::open(const std::string& path, std::string* error) {
    auto fail = [&](const std::string& msg) -> std::unique_ptr<Index> {
        if (error) *error = msg;
        return nullptr;
    };

    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return fail("cannot open " + path + ": " + std::strerror(errno));
    struct stat st {};
    if (::fstat(fd, &st) != 0) {
        ::close(fd);
        return fail("cannot stat " + path);
    }
    const auto size = static_cast<size_t>(st.st_size);
    if (size < sizeof(FileHeader)) {
        ::close(fd);
        return fail("index file too small");
    }
    void* map = ::mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
    ::close(fd);  // the mapping stays valid after closing the descriptor
    if (map == MAP_FAILED) return fail(std::string("mmap failed: ") + std::strerror(errno));

    std::unique_ptr<Index> index(new Index());
    index->map_ = map;
    index->mapSize_ = size;
    if (!index->validate(error)) return nullptr;  // destructor unmaps
    return index;
}

Index::~Index() {
    if (map_) ::munmap(map_, mapSize_);
}

bool Index::validate(std::string* error) {
    auto fail = [&](const std::string& msg) {
        if (error) *error = "corrupt index: " + msg;
        return false;
    };
    auto* base = static_cast<const char*>(map_);
    auto* h = reinterpret_cast<const FileHeader*>(base);
    if (std::memcmp(h->magic, kMagic, sizeof kMagic) != 0) return fail("bad magic");
    if (h->version != kVersion) return fail("unsupported version " + std::to_string(h->version));
    if (h->fileSize != mapSize_) return fail("size mismatch (truncated file?)");

    // Each section must start 8-aligned, lie inside the file, and not overflow when sized.
    auto section = [&](uint64_t off, uint64_t count, uint64_t elem) {
        if (off % 8 != 0 || off > mapSize_) return false;
        if (count != 0 && elem > (mapSize_ - off) / count) return false;
        return true;
    };
    if (!section(h->offDocs, h->numDocs, sizeof(DocRecord)) ||
        !section(h->offModules, h->numModules, sizeof(ModuleRecord)) ||
        !section(h->offTerms, h->numTerms, sizeof(TermRecord)) ||
        !section(h->offPostings, h->numPostings, sizeof(PostingRecord)) ||
        !section(h->offPositions, h->numPositions, sizeof(uint32_t)) || !section(h->offStrings, h->stringsSize, 1)) {
        return fail("section out of bounds");
    }

    header_ = h;
    docs_ = reinterpret_cast<const DocRecord*>(base + h->offDocs);
    modules_ = reinterpret_cast<const ModuleRecord*>(base + h->offModules);
    terms_ = reinterpret_cast<const TermRecord*>(base + h->offTerms);
    postings_ = reinterpret_cast<const PostingRecord*>(base + h->offPostings);
    positions_ = reinterpret_cast<const uint32_t*>(base + h->offPositions);
    strings_ = base + h->offStrings;

    auto strOk = [&](uint32_t off, uint32_t len) { return static_cast<uint64_t>(off) + len <= h->stringsSize; };
    for (uint32_t i = 0; i < h->numModules; ++i) {
        if (!strOk(modules_[i].off, modules_[i].len)) return fail("module string out of bounds");
    }
    for (uint32_t i = 0; i < h->numDocs; ++i) {
        const auto& d = docs_[i];
        if (!strOk(d.keyOff, d.keyLen)) return fail("doc key out of bounds");
        if (d.module >= h->numModules) return fail("doc module out of range");
    }
    uint64_t expectedPosting = 0;
    for (uint32_t i = 0; i < h->numTerms; ++i) {
        const auto& t = terms_[i];
        if (!strOk(t.strOff, t.strLen)) return fail("term string out of bounds");
        if (t.postingsStart != expectedPosting || t.df == 0 || t.df > h->numPostings - t.postingsStart) {
            return fail("term postings out of range");
        }
        expectedPosting += t.df;
        if (i > 0 && !(str(terms_[i - 1].strOff, terms_[i - 1].strLen) < str(t.strOff, t.strLen))) {
            return fail("terms not sorted");
        }
        uint32_t prevDoc = 0;
        for (uint64_t p = t.postingsStart; p < t.postingsStart + t.df; ++p) {
            const auto& pr = postings_[p];
            if (pr.doc >= h->numDocs || (p > t.postingsStart && pr.doc <= prevDoc)) return fail("posting doc ids invalid");
            if (pr.tf == 0 || pr.positionsStart > h->numPositions || pr.tf > h->numPositions - pr.positionsStart) {
                return fail("posting positions out of range");
            }
            prevDoc = pr.doc;
        }
    }
    if (expectedPosting != h->numPostings) return fail("posting count mismatch");
    return true;
}

Index::Doc Index::doc(uint32_t id) const {
    const auto& d = docs_[id];
    return {str(d.keyOff, d.keyLen), module(d.module), d.week, d.type, d.page, d.startMs, d.length};
}

std::string_view Index::module(uint16_t id) const { return str(modules_[id].off, modules_[id].len); }

std::optional<uint16_t> Index::moduleId(std::string_view name) const {
    for (uint32_t i = 0; i < header_->numModules; ++i) {
        if (module(static_cast<uint16_t>(i)) == name) return static_cast<uint16_t>(i);
    }
    return std::nullopt;
}

Index::PostingList Index::postings(std::string_view term) const {
    const auto* end = terms_ + header_->numTerms;
    const auto* it = std::lower_bound(terms_, end, term, [this](const TermRecord& t, std::string_view key) {
        return str(t.strOff, t.strLen) < key;
    });
    if (it == end || str(it->strOff, it->strLen) != term) return {};
    return PostingList(postings_ + it->postingsStart, it->df, positions_);
}

}  // namespace retrieve

#include "index/builder.h"

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <map>
#include <unistd.h>

#include "index/format.h"
#include "index/tokenizer.h"

namespace retrieve {
namespace {

using namespace format;

struct BuildPosting {
    uint32_t doc;
    std::vector<uint32_t> positions;
};

uint64_t alignUp(uint64_t n) { return (n + 7) & ~uint64_t{7}; }

class Writer {
public:
    explicit Writer(FILE* f) : f_(f) {}
    bool write(const void* data, size_t n) {
        if (n == 0) return true;
        if (std::fwrite(data, 1, n, f_) != n) return false;
        written_ += n;
        return true;
    }
    bool padTo(uint64_t offset) {
        static const char zeros[8] = {};
        while (written_ < offset) {
            if (!write(zeros, std::min<uint64_t>(8, offset - written_))) return false;
        }
        return written_ == offset;
    }
    uint64_t written() const { return written_; }

private:
    FILE* f_;
    uint64_t written_ = 0;
};

std::string tempPathFor(const std::string& path) {
    static std::atomic<unsigned> counter{0};
    return path + ".tmp." + std::to_string(::getpid()) + "." + std::to_string(counter++);
}

}  // namespace

bool IndexBuilder::writeTo(const std::string& path, std::string* error) const {
    auto fail = [&](const std::string& msg) {
        if (error) *error = msg;
        return false;
    };

    // 1. Order docs by stable key so doc ids are deterministic.
    std::vector<const DocInput*> docs;
    docs.reserve(docs_.size());
    for (const auto& d : docs_) docs.push_back(&d);
    std::sort(docs.begin(), docs.end(), [](auto* a, auto* b) { return a->stableKey < b->stableKey; });
    for (size_t i = 1; i < docs.size(); ++i) {
        if (docs[i]->stableKey == docs[i - 1]->stableKey) return fail("duplicate stable key: " + docs[i]->stableKey);
    }

    // 2. Module table.
    std::vector<std::string> modules;
    for (auto* d : docs) modules.push_back(d->module);
    std::sort(modules.begin(), modules.end());
    modules.erase(std::unique(modules.begin(), modules.end()), modules.end());
    if (modules.size() > UINT16_MAX) return fail("too many modules");

    // 3. Tokenize and invert. std::map keeps terms sorted by bytes, as the reader's binary search needs.
    std::map<std::string, std::vector<BuildPosting>> inverted;
    std::vector<uint32_t> lengths(docs.size());
    uint64_t totalLength = 0;
    for (uint32_t id = 0; id < docs.size(); ++id) {
        auto tokens = tokenize(docs[id]->text);
        lengths[id] = static_cast<uint32_t>(tokens.size());
        totalLength += tokens.size();
        for (auto& tok : tokens) {
            auto& list = inverted[tok.term];
            if (list.empty() || list.back().doc != id) list.push_back({id, {}});
            list.back().positions.push_back(tok.position);
        }
    }

    // 4. Lay out strings: keys, then modules, then terms.
    std::string strings;
    auto addString = [&](const std::string& s) {
        auto off = static_cast<uint32_t>(strings.size());
        strings += s;
        return off;
    };

    std::vector<DocRecord> docRecords(docs.size());
    for (uint32_t id = 0; id < docs.size(); ++id) {
        const auto* d = docs[id];
        auto& r = docRecords[id];
        std::memset(&r, 0, sizeof r);
        r.keyOff = addString(d->stableKey);
        r.keyLen = static_cast<uint32_t>(d->stableKey.size());
        r.length = lengths[id];
        r.page = d->page;
        r.startMs = d->startMs;
        r.week = d->week;
        r.module = static_cast<uint16_t>(std::lower_bound(modules.begin(), modules.end(), d->module) - modules.begin());
        r.type = d->type;
    }
    std::vector<ModuleRecord> moduleRecords;
    for (const auto& m : modules) moduleRecords.push_back({addString(m), static_cast<uint32_t>(m.size())});

    std::vector<TermRecord> termRecords;
    std::vector<PostingRecord> postingRecords;
    std::vector<uint32_t> positions;
    for (const auto& [term, list] : inverted) {
        TermRecord t{};
        t.strOff = addString(term);
        t.strLen = static_cast<uint32_t>(term.size());
        t.df = static_cast<uint32_t>(list.size());
        t.postingsStart = postingRecords.size();
        termRecords.push_back(t);
        for (const auto& p : list) {
            postingRecords.push_back({p.doc, static_cast<uint32_t>(p.positions.size()), positions.size()});
            positions.insert(positions.end(), p.positions.begin(), p.positions.end());
        }
    }
    if (strings.size() > UINT32_MAX) return fail("string pool too large");

    // 5. Header with section offsets.
    FileHeader h{};
    std::memcpy(h.magic, kMagic, sizeof kMagic);
    h.version = kVersion;
    h.numDocs = static_cast<uint32_t>(docRecords.size());
    h.numTerms = static_cast<uint32_t>(termRecords.size());
    h.numModules = static_cast<uint32_t>(moduleRecords.size());
    h.numPostings = postingRecords.size();
    h.numPositions = positions.size();
    h.avgDocLength = docs.empty() ? 0.0 : static_cast<double>(totalLength) / static_cast<double>(docs.size());
    h.offDocs = alignUp(sizeof h);
    h.offModules = alignUp(h.offDocs + docRecords.size() * sizeof(DocRecord));
    h.offTerms = alignUp(h.offModules + moduleRecords.size() * sizeof(ModuleRecord));
    h.offPostings = alignUp(h.offTerms + termRecords.size() * sizeof(TermRecord));
    h.offPositions = alignUp(h.offPostings + postingRecords.size() * sizeof(PostingRecord));
    h.offStrings = alignUp(h.offPositions + positions.size() * sizeof(uint32_t));
    h.stringsSize = strings.size();
    h.fileSize = h.offStrings + strings.size();

    // 6. Write to a temp file, fsync, rename over the target.
    const std::string tmp = tempPathFor(path);
    FILE* f = std::fopen(tmp.c_str(), "wb");
    if (!f) return fail("cannot create " + tmp + ": " + std::strerror(errno));
    Writer w(f);
    bool ok = w.write(&h, sizeof h) && w.padTo(h.offDocs) &&
              w.write(docRecords.data(), docRecords.size() * sizeof(DocRecord)) && w.padTo(h.offModules) &&
              w.write(moduleRecords.data(), moduleRecords.size() * sizeof(ModuleRecord)) && w.padTo(h.offTerms) &&
              w.write(termRecords.data(), termRecords.size() * sizeof(TermRecord)) && w.padTo(h.offPostings) &&
              w.write(postingRecords.data(), postingRecords.size() * sizeof(PostingRecord)) && w.padTo(h.offPositions) &&
              w.write(positions.data(), positions.size() * sizeof(uint32_t)) && w.padTo(h.offStrings) &&
              w.write(strings.data(), strings.size()) && w.written() == h.fileSize;
    ok = ok && std::fflush(f) == 0 && ::fsync(::fileno(f)) == 0;
    const int savedErrno = errno;
    ok = (std::fclose(f) == 0) && ok;
    if (!ok) {
        std::remove(tmp.c_str());
        return fail("write failed for " + tmp + ": " + std::strerror(savedErrno));
    }
    if (std::rename(tmp.c_str(), path.c_str()) != 0) {
        const std::string msg = std::strerror(errno);
        std::remove(tmp.c_str());
        return fail("rename to " + path + " failed: " + msg);
    }
    return true;
}

}  // namespace retrieve

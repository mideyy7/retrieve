#pragma once

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <string_view>

#include "index/format.h"

namespace retrieve {

// Read-only view of an index file, memory-mapped. Opening validates every offset and count, so a
// truncated or corrupt file is rejected up front and lookups never read outside the mapping.
// Safe to share between threads: nothing is mutated after open().
class Index {
public:
    struct Doc {
        std::string_view stableKey;
        std::string_view module;
        uint16_t week;
        uint8_t type;
        int32_t page;
        int64_t startMs;
        uint32_t length;
    };

    // Positions of one term in one doc.
    struct Positions {
        const uint32_t* data;
        uint32_t size;
    };

    struct Posting {
        uint32_t doc;
        uint32_t tf;
    };

    // All docs containing one term, sorted by doc id.
    class PostingList {
    public:
        PostingList() = default;
        uint32_t size() const { return size_; }
        Posting operator[](uint32_t i) const { return {records_[i].doc, records_[i].tf}; }
        Positions positions(uint32_t i) const { return {positions_ + records_[i].positionsStart, records_[i].tf}; }

    private:
        friend class Index;
        PostingList(const format::PostingRecord* r, uint32_t n, const uint32_t* p) : records_(r), size_(n), positions_(p) {}
        const format::PostingRecord* records_ = nullptr;
        uint32_t size_ = 0;
        const uint32_t* positions_ = nullptr;
    };

    static std::unique_ptr<Index> open(const std::string& path, std::string* error);
    ~Index();
    Index(const Index&) = delete;
    Index& operator=(const Index&) = delete;

    uint32_t numDocs() const { return header_->numDocs; }
    uint32_t numTerms() const { return header_->numTerms; }
    uint32_t numModules() const { return header_->numModules; }
    double avgDocLength() const { return header_->avgDocLength; }
    uint64_t fileSize() const { return header_->fileSize; }

    Doc doc(uint32_t id) const;
    std::string_view module(uint16_t id) const;
    std::optional<uint16_t> moduleId(std::string_view name) const;

    // Postings of an exact (already tokenized) term; empty if the term is not in the index.
    PostingList postings(std::string_view term) const;

private:
    Index() = default;
    bool validate(std::string* error);
    std::string_view str(uint32_t off, uint32_t len) const { return {strings_ + off, len}; }

    void* map_ = nullptr;
    size_t mapSize_ = 0;
    const format::FileHeader* header_ = nullptr;
    const format::DocRecord* docs_ = nullptr;
    const format::ModuleRecord* modules_ = nullptr;
    const format::TermRecord* terms_ = nullptr;
    const format::PostingRecord* postings_ = nullptr;
    const uint32_t* positions_ = nullptr;
    const char* strings_ = nullptr;
};

}  // namespace retrieve

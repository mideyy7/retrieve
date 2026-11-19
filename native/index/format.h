#pragma once

// On-disk layout of a keyword index (version 1). The file is memory-mapped and read in place, so
// every record is fixed-size, little-endian and 8-byte aligned. All "start" fields are indices
// into the corresponding array, not byte offsets.
//
//   FileHeader
//   DocRecord[numDocs]          sorted by stableKey; a doc's index here is its internal doc id
//   ModuleRecord[numModules]    distinct module names, sorted
//   TermRecord[numTerms]        sorted by term bytes, for binary search
//   PostingRecord[numPostings]  grouped by term, each group sorted by doc id
//   uint32 positions[numPositions]  grouped by posting, ascending
//   char strings[stringsSize]   stable keys, module names and terms (not NUL-terminated)

#include <cstdint>

namespace retrieve::format {

inline constexpr char kMagic[8] = {'R', 'T', 'R', 'V', 'I', 'D', 'X', '1'};
inline constexpr uint32_t kVersion = 1;

struct FileHeader {
    char magic[8];
    uint32_t version;
    uint32_t numDocs;
    uint32_t numTerms;
    uint32_t numModules;
    uint64_t numPostings;
    uint64_t numPositions;
    double avgDocLength;
    uint64_t offDocs;
    uint64_t offModules;
    uint64_t offTerms;
    uint64_t offPostings;
    uint64_t offPositions;
    uint64_t offStrings;
    uint64_t stringsSize;
    uint64_t fileSize;
};
static_assert(sizeof(FileHeader) == 112);

struct DocRecord {
    uint32_t keyOff;   // stable key in strings
    uint32_t keyLen;
    uint32_t length;   // number of tokens, for BM25 length normalisation
    int32_t page;      // 1-based page, or -1 for audio
    int64_t startMs;   // start of an audio chunk, or -1 for PDFs
    uint16_t week;
    uint16_t module;   // index into ModuleRecord[]
    uint8_t type;      // DocType ordinal from the app: 0 slides, 1 pdf, 2 audio
    uint8_t pad[3];
};
static_assert(sizeof(DocRecord) == 32);

struct ModuleRecord {
    uint32_t off;
    uint32_t len;
};
static_assert(sizeof(ModuleRecord) == 8);

struct TermRecord {
    uint32_t strOff;
    uint32_t strLen;
    uint32_t df;             // number of docs containing the term = length of its posting group
    uint32_t pad;
    uint64_t postingsStart;
};
static_assert(sizeof(TermRecord) == 24);

struct PostingRecord {
    uint32_t doc;
    uint32_t tf;             // occurrences in the doc = number of positions
    uint64_t positionsStart;
};
static_assert(sizeof(PostingRecord) == 16);

}  // namespace retrieve::format

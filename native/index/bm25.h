#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

#include "index/index.h"

namespace retrieve {

struct Bm25Params {
    double k1 = 1.2;  // term-frequency saturation: higher = repeated terms keep adding more
    double b = 0.75;  // length normalisation: 0 = none, 1 = full
};

struct Hit {
    uint32_t doc;
    double score;
};

// BM25 inverse document frequency, the non-negative variant used by Lucene:
//   idf = ln(1 + (N - df + 0.5) / (df + 0.5))
// The classic Robertson form goes negative for terms in more than half the docs, which would make
// a common word *lower* a document's score. This variant keeps every matching term helpful.
double bm25Idf(uint32_t df, uint32_t numDocs);

// One term's contribution for one doc: idf * tf*(k1+1) / (tf + k1*(1 - b + b*dl/avgdl)).
double bm25TermScore(double idf, uint32_t tf, uint32_t docLength, double avgDocLength, const Bm25Params& p);

// True if `doc` contains `terms` at consecutive positions.
bool containsPhrase(const Index& index, uint32_t doc, const std::vector<std::string>& terms);

// A parsed Phase 2 query: free terms (match any) and quoted phrases (must all match).
// Phase 3 replaces this with the full query language.
struct SimpleQuery {
    std::vector<std::string> terms;
    std::vector<std::vector<std::string>> phrases;
};
SimpleQuery parseSimpleQuery(std::string_view query);

class Searcher {
public:
    explicit Searcher(const Index& index, Bm25Params params = {}) : index_(index), params_(params) {}

    // Top-k docs by BM25 over all distinct query terms (free and phrase terms). A doc is a
    // candidate if it contains every phrase and, when there are no phrases, any free term.
    // Ties are broken by ascending doc id, so results are fully deterministic.
    std::vector<Hit> search(std::string_view query, size_t k) const;
    std::vector<Hit> search(const SimpleQuery& query, size_t k) const;

private:
    const Index& index_;
    Bm25Params params_;
};

}  // namespace retrieve

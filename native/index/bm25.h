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

// Parses `query` with the query language (docs/query-language.md) and returns the top-k hits from
// the planned executor. Throws query::QueryError for invalid syntax. An empty query, or one with
// nothing searchable in it, returns no hits.
class Searcher {
public:
    explicit Searcher(const Index& index, Bm25Params params = {}) : index_(index), params_(params) {}
    std::vector<Hit> search(std::string_view query, size_t k) const;

private:
    const Index& index_;
    Bm25Params params_;
};

}  // namespace retrieve

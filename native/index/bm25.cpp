#include "index/bm25.h"

#include <algorithm>
#include <cmath>

#include "index/tokenizer.h"
#include "query/executor.h"
#include "query/parser.h"

namespace retrieve {
namespace {

// Finds `doc` in a posting list (sorted by doc id). Returns the posting's index or -1.
int64_t findDoc(const Index::PostingList& list, uint32_t doc) {
    uint32_t lo = 0, hi = list.size();
    while (lo < hi) {
        const uint32_t mid = lo + (hi - lo) / 2;
        if (list[mid].doc < doc) lo = mid + 1;
        else hi = mid;
    }
    // Both branches must be int64_t: with `? lo : -1` the result type is uint32_t, so "not found"
    // became 4294967295 and slipped past the caller's `< 0` check (caught by the phrase tests).
    return (lo < list.size() && list[lo].doc == doc) ? static_cast<int64_t>(lo) : int64_t{-1};
}

bool hasPosition(Index::Positions p, uint32_t value) { return std::binary_search(p.data, p.data + p.size, value); }

}  // namespace

double bm25Idf(uint32_t df, uint32_t numDocs) {
    return std::log(1.0 + (static_cast<double>(numDocs) - df + 0.5) / (df + 0.5));
}

double bm25TermScore(double idf, uint32_t tf, uint32_t docLength, double avgDocLength, const Bm25Params& p) {
    const double norm = avgDocLength > 0 ? 1.0 - p.b + p.b * docLength / avgDocLength : 1.0;
    return idf * (tf * (p.k1 + 1.0)) / (tf + p.k1 * norm);
}

bool containsPhrase(const Index& index, uint32_t doc, const std::vector<std::string>& terms) {
    if (terms.empty()) return true;
    std::vector<Index::Positions> pos;
    pos.reserve(terms.size());
    for (const auto& t : terms) {
        auto list = index.postings(t);
        const int64_t i = findDoc(list, doc);
        if (i < 0) return false;
        pos.push_back(list.positions(static_cast<uint32_t>(i)));
    }
    // Try every occurrence of the first term as the phrase start.
    for (uint32_t s = 0; s < pos[0].size; ++s) {
        const uint32_t start = pos[0].data[s];
        bool all = true;
        for (size_t j = 1; j < pos.size() && all; ++j) all = hasPosition(pos[j], start + static_cast<uint32_t>(j));
        if (all) return true;
    }
    return false;
}

std::vector<Hit> Searcher::search(std::string_view query, size_t k) const {
    const auto root = query::parse(query);
    if (!root) return {};
    return query::executePlanned(index_, *root, k, params_);
}

}  // namespace retrieve

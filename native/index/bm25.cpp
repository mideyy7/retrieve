#include "index/bm25.h"

#include <algorithm>
#include <cmath>
#include <unordered_set>

#include "index/tokenizer.h"

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

std::vector<std::string> termsOf(std::string_view text) {
    std::vector<std::string> out;
    for (auto& t : tokenize(text)) out.push_back(std::move(t.term));
    return out;
}

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

SimpleQuery parseSimpleQuery(std::string_view query) {
    SimpleQuery q;
    size_t i = 0;
    while (i < query.size()) {
        const size_t quote = query.find('"', i);
        const size_t close = quote == std::string_view::npos ? quote : query.find('"', quote + 1);
        if (quote == std::string_view::npos || close == std::string_view::npos) {
            // No (complete) phrase left: everything else is free terms. An unmatched quote is ignored.
            for (auto& t : termsOf(query.substr(i))) q.terms.push_back(std::move(t));
            break;
        }
        for (auto& t : termsOf(query.substr(i, quote - i))) q.terms.push_back(std::move(t));
        auto phrase = termsOf(query.substr(quote + 1, close - quote - 1));
        if (!phrase.empty()) q.phrases.push_back(std::move(phrase));
        i = close + 1;
    }
    return q;
}

std::vector<Hit> Searcher::search(std::string_view query, size_t k) const { return search(parseSimpleQuery(query), k); }

std::vector<Hit> Searcher::search(const SimpleQuery& query, size_t k) const {
    const uint32_t n = index_.numDocs();
    if (n == 0 || k == 0) return {};

    // Distinct scoring terms, in first-seen order (a repeated query term counts once).
    std::vector<std::string> scoring;
    std::unordered_set<std::string> seen;
    auto addTerm = [&](const std::string& t) {
        if (seen.insert(t).second) scoring.push_back(t);
    };
    for (const auto& t : query.terms) addTerm(t);
    for (const auto& p : query.phrases) for (const auto& t : p) addTerm(t);
    if (scoring.empty()) return {};

    // Accumulate BM25 over postings (term-at-a-time).
    std::vector<double> score(n, 0.0);
    std::vector<uint8_t> matched(n, 0);
    for (const auto& term : scoring) {
        auto list = index_.postings(term);
        if (list.size() == 0) continue;
        const double idf = bm25Idf(list.size(), n);
        for (uint32_t i = 0; i < list.size(); ++i) {
            const auto p = list[i];
            score[p.doc] += bm25TermScore(idf, p.tf, index_.doc(p.doc).length, index_.avgDocLength(), params_);
            matched[p.doc] = 1;
        }
    }

    std::vector<Hit> hits;
    for (uint32_t d = 0; d < n; ++d) {
        if (!matched[d]) continue;
        bool ok = true;
        for (const auto& phrase : query.phrases) {
            if (!containsPhrase(index_, d, phrase)) {
                ok = false;
                break;
            }
        }
        if (ok) hits.push_back({d, score[d]});
    }

    auto better = [](const Hit& a, const Hit& b) { return a.score != b.score ? a.score > b.score : a.doc < b.doc; };
    const size_t top = std::min(k, hits.size());
    std::partial_sort(hits.begin(), hits.begin() + static_cast<std::ptrdiff_t>(top), hits.end(), better);
    hits.resize(top);
    return hits;
}

}  // namespace retrieve

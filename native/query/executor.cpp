#include "query/executor.h"

#include <algorithm>
#include <cmath>
#include <iterator>
#include <optional>

namespace retrieve::query {
namespace {

using DocSet = std::vector<uint32_t>;  // sorted, unique doc ids

int64_t findDoc(const Index::PostingList& list, uint32_t doc) {
    uint32_t lo = 0, hi = list.size();
    while (lo < hi) {
        const uint32_t mid = lo + (hi - lo) / 2;
        if (list[mid].doc < doc) lo = mid + 1;
        else hi = mid;
    }
    return (lo < list.size() && list[lo].doc == doc) ? static_cast<int64_t>(lo) : int64_t{-1};
}

// ---- Filters --------------------------------------------------------------------------------

// Resolved once per query: which module ids a module filter accepts.
bool moduleMatches(std::string_view moduleName, const std::string& wanted) {
    const bool digitsOnly = !wanted.empty() && std::all_of(wanted.begin(), wanted.end(), [](char c) { return c >= '0' && c <= '9'; });
    if (digitsOnly) return moduleName.size() >= wanted.size() && moduleName.substr(moduleName.size() - wanted.size()) == wanted;
    if (moduleName.size() != wanted.size()) return false;
    for (size_t i = 0; i < wanted.size(); ++i) {
        const char a = moduleName[i];
        const char up = (a >= 'a' && a <= 'z') ? static_cast<char>(a - 'a' + 'A') : a;
        if (up != wanted[i]) return false;
    }
    return true;
}

bool filterMatches(const Index& index, const Filter& f, uint32_t doc) {
    const auto d = index.doc(doc);
    switch (f.key) {
        case Filter::Key::Module: return moduleMatches(d.module, f.module);
        case Filter::Key::Week: return d.week >= f.weekLo && d.week <= f.weekHi;
        case Filter::Key::Type: return d.type == f.type;
    }
    return false;
}

// ---- Shared scoring and ordering ------------------------------------------------------------

std::vector<Hit> topK(const Index& index, std::vector<Hit> hits, size_t k) {
    auto before = [&](const Hit& a, const Hit& b) { return resultBefore(index, a, b); };
    const size_t top = std::min(k, hits.size());
    std::partial_sort(hits.begin(), hits.begin() + static_cast<std::ptrdiff_t>(top), hits.end(), before);
    hits.resize(top);
    return hits;
}

// ---- Planned executor -----------------------------------------------------------------------

class Planner {
public:
    Planner(const Index& index) : index_(index) {}

    // Evaluates `n` restricted to `cand` (nullptr = all docs). Returns a sorted doc set.
    DocSet eval(const Node& n, const DocSet* cand) {
        switch (n.kind) {
            case NodeKind::Terms: return evalTerms(n, cand);
            case NodeKind::Filter: return evalFilter(n.filter, cand);
            case NodeKind::And: return evalAnd(n, cand);
            case NodeKind::Or: {
                DocSet out;
                for (const auto& c : n.children) out = unite(out, eval(*c, cand));
                return out;
            }
        }
        return {};
    }

    // Rough number of docs a node can match: used to order AND operands, rarest first.
    uint64_t estimate(const Node& n) const {
        const uint64_t all = index_.numDocs();
        switch (n.kind) {
            case NodeKind::Terms: {
                uint64_t best = all;
                for (const auto& t : n.terms) best = std::min<uint64_t>(best, index_.postings(t).size());
                return best;
            }
            case NodeKind::Filter: return all;
            case NodeKind::And: {
                uint64_t best = all;
                bool any = false;
                for (const auto& r : n.required) {
                    if (r->kind == NodeKind::Filter) continue;
                    best = std::min(best, estimate(*r));
                    any = true;
                }
                if (!any && shouldIsMandatory(n)) {
                    uint64_t sum = 0;
                    for (const auto& s : n.should) sum += estimate(*s);
                    best = std::min(best, sum);
                }
                return best;
            }
            case NodeKind::Or: {
                uint64_t sum = 0;
                for (const auto& c : n.children) sum += estimate(*c);
                return std::min(sum, all);
            }
        }
        return all;
    }

private:
    static DocSet intersect(const DocSet& a, const DocSet& b) {
        DocSet out;
        std::set_intersection(a.begin(), a.end(), b.begin(), b.end(), std::back_inserter(out));
        return out;
    }
    static DocSet unite(const DocSet& a, const DocSet& b) {
        DocSet out;
        std::set_union(a.begin(), a.end(), b.begin(), b.end(), std::back_inserter(out));
        return out;
    }
    static DocSet subtract(const DocSet& a, const DocSet& b) {
        DocSet out;
        std::set_difference(a.begin(), a.end(), b.begin(), b.end(), std::back_inserter(out));
        return out;
    }

    const DocSet& allDocs() {
        if (!all_) {
            all_.emplace(index_.numDocs());
            for (uint32_t i = 0; i < index_.numDocs(); ++i) (*all_)[i] = i;
        }
        return *all_;
    }

    // Docs of one posting list, restricted to cand. Walks whichever side is cheaper: probing the
    // posting list for each candidate (cand small) or scanning the list (cand large).
    DocSet postingsIn(const Index::PostingList& list, const DocSet* cand) const {
        DocSet out;
        if (!cand) {
            out.reserve(list.size());
            for (uint32_t i = 0; i < list.size(); ++i) out.push_back(list[i].doc);
            return out;
        }
        const double probeCost = static_cast<double>(cand->size()) * std::log2(static_cast<double>(list.size()) + 2.0);
        if (probeCost < static_cast<double>(list.size())) {
            for (uint32_t d : *cand) if (findDoc(list, d) >= 0) out.push_back(d);
        } else {
            size_t j = 0;
            for (uint32_t i = 0; i < list.size() && j < cand->size(); ++i) {
                const uint32_t d = list[i].doc;
                while (j < cand->size() && (*cand)[j] < d) ++j;
                if (j < cand->size() && (*cand)[j] == d) out.push_back(d);
            }
        }
        return out;
    }

    DocSet evalTerms(const Node& n, const DocSet* cand) {
        if (n.terms.size() == 1) return postingsIn(index_.postings(n.terms[0]), cand);
        // Phrase: intersect the term postings rarest first, then check positions on survivors only.
        std::vector<Index::PostingList> lists;
        for (const auto& t : n.terms) lists.push_back(index_.postings(t));
        std::vector<size_t> order(lists.size());
        for (size_t i = 0; i < order.size(); ++i) order[i] = i;
        std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) { return lists[a].size() < lists[b].size(); });
        DocSet s = postingsIn(lists[order[0]], cand);
        for (size_t i = 1; i < order.size() && !s.empty(); ++i) s = postingsIn(lists[order[i]], &s);
        DocSet out;
        for (uint32_t d : s) if (containsPhrase(index_, d, n.terms)) out.push_back(d);
        return out;
    }

    DocSet evalFilter(const Filter& f, const DocSet* cand) {
        const DocSet& base = cand ? *cand : allDocs();
        DocSet out;
        for (uint32_t d : base) if (filterMatches(index_, f, d)) out.push_back(d);
        return out;
    }

    DocSet evalAnd(const Node& n, const DocSet* cand) {
        std::optional<DocSet> c;
        if (cand) c = *cand;
        auto current = [&]() -> const DocSet* { return c ? &*c : nullptr; };

        // 1. Filters first: cheap metadata checks that shrink the candidate set for everything else.
        for (const auto& r : n.required) {
            if (r->kind == NodeKind::Filter) c = evalFilter(r->filter, current());
            if (c && c->empty()) return {};
        }
        // 2. Other required operands, most selective first, each evaluated only within survivors.
        std::vector<const Node*> rest;
        for (const auto& r : n.required) if (r->kind != NodeKind::Filter) rest.push_back(r.get());
        std::stable_sort(rest.begin(), rest.end(), [&](const Node* a, const Node* b) { return estimate(*a) < estimate(*b); });
        for (const Node* r : rest) {
            c = eval(*r, current());
            if (c->empty()) return {};
        }
        // 3. Mandatory should group: union of the should words within survivors.
        if (shouldIsMandatory(n)) {
            DocSet u;
            for (const auto& s : n.should) u = unite(u, eval(*s, current()));
            c = std::move(u);
            if (c->empty()) return {};
        }
        // 4. Nothing positive constrained the set (e.g. only NOT): start from everything allowed.
        if (!c) c = allDocs();
        // 5. NOT as set difference, evaluated only within survivors.
        for (const auto& m : n.mustNot) {
            if (c->empty()) break;
            c = subtract(*c, eval(*m, current()));
        }
        return *c;
    }

    const Index& index_;
    std::optional<DocSet> all_;
};

// ---- Naive executor -------------------------------------------------------------------------

bool naiveMatch(const Index& index, const Node& n, uint32_t doc) {
    switch (n.kind) {
        case NodeKind::Terms:
            if (n.terms.size() == 1) return findDoc(index.postings(n.terms[0]), doc) >= 0;
            return containsPhrase(index, doc, n.terms);
        case NodeKind::Filter: return filterMatches(index, n.filter, doc);
        case NodeKind::And: {
            for (const auto& r : n.required) {
                if (r->kind != NodeKind::Filter && !naiveMatch(index, *r, doc)) return false;
            }
            for (const auto& m : n.mustNot) if (naiveMatch(index, *m, doc)) return false;
            if (shouldIsMandatory(n)) {
                bool any = false;
                for (const auto& s : n.should) {
                    if (naiveMatch(index, *s, doc)) {
                        any = true;
                        break;
                    }
                }
                if (!any) return false;
            }
            for (const auto& r : n.required) {  // filters last: the naive "filter after scoring" order
                if (r->kind == NodeKind::Filter && !naiveMatch(index, *r, doc)) return false;
            }
            return true;
        }
        case NodeKind::Or:
            for (const auto& c : n.children) if (naiveMatch(index, *c, doc)) return true;
            return false;
    }
    return false;
}

}  // namespace

bool resultBefore(const Index& index, const Hit& a, const Hit& b) {
    if (a.score != b.score) return a.score > b.score;
    const auto da = index.doc(a.doc), db = index.doc(b.doc);
    if (da.module != db.module) return da.module < db.module;
    if (da.week != db.week) return da.week < db.week;
    if (da.page != db.page) return da.page < db.page;
    if (da.startMs != db.startMs) return da.startMs < db.startMs;
    return a.doc < b.doc;
}

std::vector<Hit> executePlanned(const Index& index, const Node& root, size_t k, const Bm25Params& params) {
    if (index.numDocs() == 0 || k == 0) return {};
    Planner planner(index);
    const DocSet matched = planner.eval(root, nullptr);

    // BM25 only for the surviving docs, adding terms in scoringTerms() order (same as naive).
    std::vector<double> score(matched.size(), 0.0);
    for (const auto& term : scoringTerms(root)) {
        const auto list = index.postings(term);
        if (list.size() == 0) continue;
        const double idf = bm25Idf(list.size(), index.numDocs());
        for (size_t i = 0; i < matched.size(); ++i) {
            const int64_t p = findDoc(list, matched[i]);
            if (p < 0) continue;
            const auto posting = list[static_cast<uint32_t>(p)];
            score[i] += bm25TermScore(idf, posting.tf, index.doc(posting.doc).length, index.avgDocLength(), params);
        }
    }
    std::vector<Hit> hits(matched.size());
    for (size_t i = 0; i < matched.size(); ++i) hits[i] = {matched[i], score[i]};
    return topK(index, std::move(hits), k);
}

std::vector<Hit> executeNaive(const Index& index, const Node& root, size_t k, const Bm25Params& params) {
    const uint32_t n = index.numDocs();
    if (n == 0 || k == 0) return {};

    // Score everything first (term-at-a-time over full posting lists)...
    std::vector<double> score(n, 0.0);
    for (const auto& term : scoringTerms(root)) {
        const auto list = index.postings(term);
        if (list.size() == 0) continue;
        const double idf = bm25Idf(list.size(), n);
        for (uint32_t i = 0; i < list.size(); ++i) {
            const auto p = list[i];
            score[p.doc] += bm25TermScore(idf, p.tf, index.doc(p.doc).length, index.avgDocLength(), params);
        }
    }
    // ...then test every doc against the query, left to right.
    std::vector<Hit> hits;
    for (uint32_t d = 0; d < n; ++d) {
        if (naiveMatch(index, root, d)) hits.push_back({d, score[d]});
    }
    return topK(index, std::move(hits), k);
}

}  // namespace retrieve::query

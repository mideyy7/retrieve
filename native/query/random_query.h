#pragma once

// Seeded random query generator for the planner equivalence test and `retrieve_cli equiv`.
// Produces only syntactically valid queries that exercise every construct: single words,
// phrases, all three filter kinds (including ranges and digit-suffix modules), NOT, explicit AND,
// OR and nested groups.

#include <random>
#include <string>
#include <vector>

namespace retrieve::query {

struct QueryVocab {
    std::vector<std::string> words;                     // pick from; repeats act as weights
    std::vector<std::vector<std::string>> phrases;      // word sequences that occur in the corpus
    std::vector<std::string> modules;
    uint16_t maxWeek = 12;
};

class RandomQueryGenerator {
public:
    RandomQueryGenerator(QueryVocab vocab, uint32_t seed) : v_(std::move(vocab)), rng_(seed) {}

    std::string next() { return orExpr(2); }

private:
    bool chance(double p) { return std::uniform_real_distribution<double>(0, 1)(rng_) < p; }
    template <typename T>
    const T& pick(const std::vector<T>& xs) {
        return xs[std::uniform_int_distribution<size_t>(0, xs.size() - 1)(rng_)];
    }
    int between(int lo, int hi) { return std::uniform_int_distribution<int>(lo, hi)(rng_); }

    std::string orExpr(int depth) {
        std::string s = andExpr(depth);
        while (chance(0.2)) s += " OR " + andExpr(depth);
        return s;
    }

    std::string andExpr(int depth) {
        std::string s = unary(depth);
        const int extra = between(0, 3);
        for (int i = 0; i < extra; ++i) s += (chance(0.25) ? " AND " : " ") + unary(depth);
        return s;
    }

    std::string unary(int depth) { return (chance(0.15) ? "NOT " : "") + primary(depth); }

    std::string primary(int depth) {
        const double r = std::uniform_real_distribution<double>(0, 1)(rng_);
        if (r < 0.50 || (v_.phrases.empty() && r < 0.62)) return pick(v_.words);
        if (r < 0.62) {
            const auto& p = pick(v_.phrases);
            std::string s = "\"";
            for (size_t i = 0; i < p.size(); ++i) s += (i ? " " : "") + p[i];
            return s + "\"";
        }
        if (r < 0.85 || depth == 0) return filter();
        return "(" + orExpr(depth - 1) + ")";
    }

    std::string filter() {
        switch (between(0, 2)) {
            case 0: {
                const std::string& m = pick(v_.modules);
                if (chance(0.3) && m.size() >= 5) return "module:" + m.substr(m.size() - 5);  // digit suffix form
                return "module:" + m;
            }
            case 1: {
                const int lo = between(1, v_.maxWeek);
                if (chance(0.5)) return "week:" + std::to_string(lo);
                return "week:" + std::to_string(lo) + ".." + std::to_string(between(lo, v_.maxWeek));
            }
            default: {
                static const char* types[] = {"slides", "pdf", "audio"};
                return std::string("type:") + types[between(0, 2)];
            }
        }
    }

    QueryVocab v_;
    std::mt19937 rng_;
};

}  // namespace retrieve::query

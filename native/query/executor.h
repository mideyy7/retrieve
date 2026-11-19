#pragma once

#include <cstddef>
#include <limits>
#include <vector>

#include "index/bm25.h"
#include "index/index.h"
#include "query/ast.h"

namespace retrieve::query {

inline constexpr size_t kAllResults = std::numeric_limits<size_t>::max();

// Planned execution (used by the app): filters first, required operands in ascending order of
// estimated document frequency with early exit, NOT as set difference, BM25 only for survivors.
std::vector<Hit> executePlanned(const Index& index, const Node& root, size_t k, const Bm25Params& params = {});

// Naive execution (benchmark baseline): score every doc containing any positive term, then test
// every doc against the tree left to right, with filters checked last. Same results as planned,
// bit for bit; tests check this on random queries.
std::vector<Hit> executeNaive(const Index& index, const Node& root, size_t k, const Bm25Params& params = {});

// Result order shared by both: score descending, then module, week, page, start time, doc id.
bool resultBefore(const Index& index, const Hit& a, const Hit& b);

}  // namespace retrieve::query

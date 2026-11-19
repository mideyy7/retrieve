#pragma once

#include <memory>
#include <string_view>

#include "query/ast.h"

namespace retrieve::query {

// Parses a query into an AST (docs/query-language.md). Returns nullptr for a query with nothing
// searchable in it (empty, whitespace, or only words that tokenize to nothing). Throws QueryError
// with a message and 1-based column for invalid input.
std::unique_ptr<Node> parse(std::string_view query);

}  // namespace retrieve::query

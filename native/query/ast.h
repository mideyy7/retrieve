#pragma once

// Abstract syntax tree for the query language (docs/query-language.md).

#include <cstddef>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace retrieve::query {

// Thrown by the lexer and parser. `column` is 1-based, counted in Unicode code points.
class QueryError : public std::runtime_error {
public:
    QueryError(const std::string& message, size_t column) : std::runtime_error(message), column_(column) {}
    size_t column() const { return column_; }

private:
    size_t column_;
};

struct Filter {
    enum class Key { Module, Week, Type };
    Key key = Key::Module;
    std::string module;  // upper-cased; all digits = match codes ending in these digits
    uint16_t weekLo = 0, weekHi = 0;
    uint8_t type = 0;    // 0 slides, 1 pdf, 2 audio (the app's DocType ordinal)
};

enum class NodeKind {
    Terms,   // one index term, or several that must be adjacent (a phrase)
    Filter,  // metadata condition
    And,     // required / should / mustNot operands
    Or,      // any child
};

struct Node {
    NodeKind kind;
    size_t column = 1;

    // Terms
    std::vector<std::string> terms;
    bool quoted = false;  // written as "..." (affects printing only; any multi-term node is a phrase)

    // Filter
    Filter filter;

    // And: see docs/query-language.md "Matching"
    std::vector<std::unique_ptr<Node>> required;
    std::vector<std::unique_ptr<Node>> should;
    std::vector<std::unique_ptr<Node>> mustNot;

    // Or
    std::vector<std::unique_ptr<Node>> children;

    explicit Node(NodeKind k) : kind(k) {}
};

// True if the subtree contains any term outside a NOT (used for the "should" rule).
bool hasPositiveTerms(const Node& node);

// The "should" words of an And are mandatory unless a required operand brings words of its own.
bool shouldIsMandatory(const Node& andNode);

// Canonical S-expression, used by tests and the CLI's `parse` command, e.g.
//   (and req:[module:COMP21111 "unit propagation"] should:[dpll] not:[example])
std::string toString(const Node& node);

// Distinct positive terms in a fixed depth-first order. Both executors add BM25 contributions in
// exactly this order, so their scores are bit-for-bit identical.
std::vector<std::string> scoringTerms(const Node& node);

}  // namespace retrieve::query

#pragma once

#include <string>
#include <string_view>
#include <vector>

namespace retrieve::query {

struct LexToken {
    enum class Kind { Word, Phrase, LParen, RParen, And, Or, Not, End };
    Kind kind;
    std::string text;  // raw text for Word and Phrase (without quotes)
    size_t column;     // 1-based, in code points
};

// Splits a query into tokens. Words are maximal runs of characters other than whitespace,
// parentheses and quotes. "..." and “...” delimit phrases. Upper-case AND/OR/NOT are operators.
// Throws QueryError for an unterminated phrase.
std::vector<LexToken> lex(std::string_view query);

}  // namespace retrieve::query

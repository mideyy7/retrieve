#include "query/lexer.h"

#include "query/ast.h"

namespace retrieve::query {
namespace {

// Byte length of the UTF-8 sequence starting with `b` (1 for invalid lead bytes).
size_t seqLen(unsigned char b) {
    if (b >= 0xF0 && b <= 0xF4) return 4;
    if (b >= 0xE0) return 3;
    if (b >= 0xC2 && b < 0xE0) return 2;
    return 1;
}

bool startsWith(std::string_view s, size_t i, std::string_view p) { return s.substr(i, p.size()) == p; }

constexpr std::string_view kOpenCurly = "\xE2\x80\x9C";   // “
constexpr std::string_view kCloseCurly = "\xE2\x80\x9D";  // ”

bool isSpace(unsigned char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\v'; }

}  // namespace

std::vector<LexToken> lex(std::string_view q) {
    std::vector<LexToken> out;
    size_t i = 0, column = 1;

    auto advance = [&](size_t bytes) {
        // Count code points: every byte that isn't a UTF-8 continuation byte starts one.
        for (size_t k = 0; k < bytes && i < q.size(); ++k, ++i) {
            if ((static_cast<unsigned char>(q[i]) & 0xC0) != 0x80) ++column;
        }
    };
    auto isQuote = [&](size_t at) { return q[at] == '"' || startsWith(q, at, kOpenCurly) || startsWith(q, at, kCloseCurly); };
    auto quoteLen = [&](size_t at) { return q[at] == '"' ? size_t{1} : size_t{3}; };

    while (i < q.size()) {
        const auto c = static_cast<unsigned char>(q[i]);
        if (isSpace(c)) {
            advance(1);
        } else if (c == '(') {
            out.push_back({LexToken::Kind::LParen, "(", column});
            advance(1);
        } else if (c == ')') {
            out.push_back({LexToken::Kind::RParen, ")", column});
            advance(1);
        } else if (isQuote(i)) {
            const size_t startColumn = column;
            advance(quoteLen(i));
            const size_t start = i;
            while (i < q.size() && !isQuote(i)) advance(seqLen(static_cast<unsigned char>(q[i])));
            if (i >= q.size()) throw QueryError("unterminated phrase starting at column " + std::to_string(startColumn), startColumn);
            out.push_back({LexToken::Kind::Phrase, std::string(q.substr(start, i - start)), startColumn});
            advance(quoteLen(i));
        } else {
            const size_t startColumn = column;
            const size_t start = i;
            while (i < q.size() && !isSpace(static_cast<unsigned char>(q[i])) && q[i] != '(' && q[i] != ')' && !isQuote(i)) {
                advance(seqLen(static_cast<unsigned char>(q[i])));
            }
            const std::string word(q.substr(start, i - start));
            auto kind = LexToken::Kind::Word;
            if (word == "AND") kind = LexToken::Kind::And;
            else if (word == "OR") kind = LexToken::Kind::Or;
            else if (word == "NOT") kind = LexToken::Kind::Not;
            out.push_back({kind, word, startColumn});
        }
    }
    out.push_back({LexToken::Kind::End, "", column});
    return out;
}

}  // namespace retrieve::query

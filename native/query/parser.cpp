#include "query/parser.h"

#include <algorithm>
#include <cctype>

#include "index/tokenizer.h"
#include "query/lexer.h"

namespace retrieve::query {
namespace {

using K = LexToken::Kind;

std::string col(size_t c) { return std::to_string(c); }

bool allLetters(std::string_view s) {
    return !s.empty() && std::all_of(s.begin(), s.end(), [](char ch) { return std::isalpha(static_cast<unsigned char>(ch)); });
}
bool allDigits(std::string_view s) {
    return !s.empty() && std::all_of(s.begin(), s.end(), [](char ch) { return std::isdigit(static_cast<unsigned char>(ch)); });
}
std::string lower(std::string s) {
    for (auto& ch : s) ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    return s;
}
std::string upper(std::string s) {
    for (auto& ch : s) ch = static_cast<char>(std::toupper(static_cast<unsigned char>(ch)));
    return s;
}

std::vector<std::string> termsOf(std::string_view text) {
    std::vector<std::string> out;
    for (auto& t : tokenize(text)) out.push_back(std::move(t.term));
    return out;
}

uint16_t parseWeekNumber(std::string_view s, size_t column) {
    if (!allDigits(s) || s.size() > 5) throw QueryError("week must be a number or a range like 4..6 (column " + col(column) + ")", column);
    const unsigned long v = std::stoul(std::string(s));
    if (v > 65535) throw QueryError("week must be a number or a range like 4..6 (column " + col(column) + ")", column);
    return static_cast<uint16_t>(v);
}

// One operand of an andExpr before roles are assigned.
struct Operand {
    std::unique_ptr<Node> node;
    bool negated = false;
    bool andLeft = false;   // an explicit AND directly before it
    bool andRight = false;  // an explicit AND directly after it
};

class Parser {
public:
    explicit Parser(std::vector<LexToken> tokens) : t_(std::move(tokens)) {}

    std::unique_ptr<Node> parseQuery() {
        if (peek().kind == K::End) return nullptr;
        auto node = parseOr();
        if (peek().kind == K::RParen) throw QueryError("unexpected ')' at column " + col(peek().column), peek().column);
        if (peek().kind != K::End) throw unexpected("");
        return node;
    }

private:
    const LexToken& peek() const { return t_[pos_]; }
    const LexToken& take() { return t_[pos_++]; }
    static bool startsUnary(K k) { return k == K::Word || k == K::Phrase || k == K::LParen || k == K::Not; }

    QueryError unexpected(const std::string& after) const {
        const auto& tok = peek();
        std::string found = tok.kind == K::End ? "end of query" : tok.text;
        if (!after.empty()) {
            return QueryError("expected a word, phrase, filter or '(' after " + after, tok.column);
        }
        return QueryError("expected a word, phrase, filter or '(' at column " + col(tok.column) + ", found " + found, tok.column);
    }

    std::unique_ptr<Node> parseOr() {
        if (!startsUnary(peek().kind)) throw unexpected("");
        std::vector<std::unique_ptr<Node>> children;
        if (auto first = parseAnd()) children.push_back(std::move(first));
        while (peek().kind == K::Or) {
            const size_t orColumn = take().column;
            if (!startsUnary(peek().kind)) throw unexpected("OR at column " + col(orColumn));
            if (auto next = parseAnd()) children.push_back(std::move(next));
        }
        if (children.empty()) return nullptr;
        if (children.size() == 1) return std::move(children[0]);
        auto node = std::make_unique<Node>(NodeKind::Or);
        node->column = children[0]->column;
        node->children = std::move(children);
        return node;
    }

    std::unique_ptr<Node> parseAnd() {
        std::vector<Operand> ops;
        bool pendingAnd = false;
        size_t andColumn = 0;
        while (true) {
            const auto k = peek().kind;
            if (k == K::And) {
                andColumn = take().column;
                if (ops.empty() && !pendingAnd) {
                    throw QueryError("expected a word, phrase, filter or '(' before AND at column " + col(andColumn), andColumn);
                }
                if (!startsUnary(peek().kind)) throw unexpected("AND at column " + col(andColumn));
                pendingAnd = true;
                continue;
            }
            if (!startsUnary(k)) break;
            Operand op = parseUnary();
            if (!op.node) continue;  // a word with no index terms: ignored, any pending AND carries over
            if (pendingAnd) {
                op.andLeft = true;
                if (!ops.empty()) ops.back().andRight = true;
                pendingAnd = false;
            }
            ops.push_back(std::move(op));
        }
        if (pendingAnd) throw QueryError("expected a word, phrase, filter or '(' after AND at column " + col(andColumn), andColumn);
        if (ops.empty()) return nullptr;

        auto node = std::make_unique<Node>(NodeKind::And);
        node->column = ops[0].node->column;
        for (auto& op : ops) {
            const bool singleWord = op.node->kind == NodeKind::Terms && op.node->terms.size() == 1 && !op.node->quoted;
            if (op.negated) node->mustNot.push_back(std::move(op.node));
            else if (singleWord && !op.andLeft && !op.andRight) node->should.push_back(std::move(op.node));
            else node->required.push_back(std::move(op.node));
        }
        // Simplify trivial wrappers so printed trees stay readable: (and should:[x]) -> x, (and req:[x]) -> x.
        const size_t total = node->required.size() + node->should.size() + node->mustNot.size();
        if (total == 1 && node->should.size() == 1) return std::move(node->should[0]);
        if (total == 1 && node->required.size() == 1) return std::move(node->required[0]);
        return node;
    }

    Operand parseUnary() {
        if (peek().kind == K::Not) {
            const size_t notColumn = take().column;
            if (!startsUnary(peek().kind)) throw unexpected("NOT at column " + col(notColumn));
            Operand inner = parseUnary();
            if (!inner.node) throw QueryError("expected a word, phrase, filter or '(' after NOT at column " + col(notColumn), notColumn);
            inner.negated = !inner.negated;
            return inner;
        }
        Operand op;
        op.node = parsePrimary();
        return op;
    }

    std::unique_ptr<Node> parsePrimary() {
        const LexToken tok = take();
        switch (tok.kind) {
            case K::Phrase: {
                auto terms = termsOf(tok.text);
                if (terms.empty()) {
                    const bool blank = std::all_of(tok.text.begin(), tok.text.end(), [](char ch) { return std::isspace(static_cast<unsigned char>(ch)); });
                    if (blank) throw QueryError("empty phrase at column " + col(tok.column), tok.column);
                    return nullptr;  // e.g. "—": nothing searchable
                }
                auto n = std::make_unique<Node>(NodeKind::Terms);
                n->column = tok.column;
                n->terms = std::move(terms);
                n->quoted = true;
                return n;
            }
            case K::LParen: {
                if (peek().kind == K::RParen) throw QueryError("empty group at column " + col(tok.column), tok.column);
                auto inner = parseOr();
                if (peek().kind != K::RParen) throw QueryError("expected ')' to close '(' at column " + col(tok.column), tok.column);
                take();
                if (!inner) throw QueryError("empty group at column " + col(tok.column), tok.column);
                return inner;
            }
            case K::Word:
                return parseWord(tok);
            default:
                --pos_;
                throw unexpected("");
        }
    }

    std::unique_ptr<Node> parseWord(const LexToken& tok) {
        const auto colon = tok.text.find(':');
        if (colon != std::string::npos && allLetters(std::string_view(tok.text).substr(0, colon))) {
            return parseFilter(lower(tok.text.substr(0, colon)), tok.text.substr(colon + 1), tok.column);
        }
        auto terms = termsOf(tok.text);
        if (terms.empty()) return nullptr;
        auto n = std::make_unique<Node>(NodeKind::Terms);
        n->column = tok.column;
        n->terms = std::move(terms);
        return n;
    }

    std::unique_ptr<Node> parseFilter(const std::string& key, const std::string& value, size_t column) {
        if (key != "module" && key != "week" && key != "type") {
            throw QueryError("unknown filter '" + key + "' at column " + col(column) + " (use module:, week: or type:)", column);
        }
        if (value.empty()) throw QueryError("missing value for " + key + ": at column " + col(column), column);
        auto n = std::make_unique<Node>(NodeKind::Filter);
        n->column = column;
        if (key == "module") {
            n->filter.key = Filter::Key::Module;
            n->filter.module = upper(value);
        } else if (key == "week") {
            n->filter.key = Filter::Key::Week;
            const auto dots = value.find("..");
            if (dots == std::string::npos) {
                n->filter.weekLo = n->filter.weekHi = parseWeekNumber(value, column);
            } else {
                n->filter.weekLo = parseWeekNumber(std::string_view(value).substr(0, dots), column);
                n->filter.weekHi = parseWeekNumber(std::string_view(value).substr(dots + 2), column);
                if (n->filter.weekLo > n->filter.weekHi) {
                    throw QueryError("empty week range " + value + " (column " + col(column) + ")", column);
                }
            }
        } else {
            n->filter.key = Filter::Key::Type;
            const auto v = lower(value);
            if (v == "slides") n->filter.type = 0;
            else if (v == "pdf") n->filter.type = 1;
            else if (v == "audio") n->filter.type = 2;
            else throw QueryError("unknown type '" + value + "' at column " + col(column) + " (use slides, pdf or audio)", column);
        }
        return n;
    }

    std::vector<LexToken> t_;
    size_t pos_ = 0;
};

}  // namespace

std::unique_ptr<Node> parse(std::string_view query) { return Parser(lex(query)).parseQuery(); }

}  // namespace retrieve::query

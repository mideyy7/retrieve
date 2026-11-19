#include <gtest/gtest.h>

#include "query/lexer.h"
#include "query/parser.h"

using namespace retrieve::query;

namespace {

std::string ast(std::string_view q) {
    auto n = parse(q);
    return n ? toString(*n) : "<empty>";
}

struct ErrorInfo {
    std::string message;
    size_t column;
};

ErrorInfo error(std::string_view q) {
    try {
        parse(q);
    } catch (const QueryError& e) {
        return {e.what(), e.column()};
    }
    ADD_FAILURE() << "expected a QueryError for: " << q;
    return {"", 0};
}

}  // namespace

// ---- Valid queries: the printed AST pins down precedence, roles and tokenization ---------------

TEST(Parser, SingleWord) { EXPECT_EQ(ast("Raft"), "raft"); }

TEST(Parser, JuxtaposedWordsAreShould) { EXPECT_EQ(ast("unit propagation"), "(and should:[unit propagation])"); }

TEST(Parser, QuotedPhrase) { EXPECT_EQ(ast("\"Unit Propagation\""), "\"unit propagation\""); }

TEST(Parser, CurlyQuotesDelimitPhrases) { EXPECT_EQ(ast("“stored procedure”"), "\"stored procedure\""); }

TEST(Parser, MultiTermWordIsAPhrase) {
    EXPECT_EQ(ast("RISC-V"), "\"risc v\"");
    EXPECT_EQ(ast("16:1 mux"), "(and req:[\"16 1\"] should:[mux])");
}

TEST(Parser, FiltersAreRequired) {
    EXPECT_EQ(ast("module:comp21111 week:4..6 type:SLIDES dpll"),
              "(and req:[module:COMP21111 week:4..6 type:slides] should:[dpll])");
    EXPECT_EQ(ast("Week:5"), "week:5");
}

TEST(Parser, SpecExampleQuery) {
    EXPECT_EQ(ast("module:COMP26120 week:4..6 \"amortised\" NOT tutorial"),
              "(and req:[module:COMP26120 week:4..6 \"amortised\"] not:[tutorial])");
}

TEST(Parser, ExplicitAndMakesBothSidesRequired) {
    EXPECT_EQ(ast("trigger AND update before"), "(and req:[trigger update] should:[before])");
    EXPECT_EQ(ast("a AND b AND c"), "(and req:[a b c])");
}

TEST(Parser, OrBindsLooserThanJuxtaposition) {
    EXPECT_EQ(ast("raft log OR paxos"), "(or (and should:[raft log]) paxos)");
}

TEST(Parser, ParenthesesOverridePrecedence) {
    EXPECT_EQ(ast("(raft OR paxos) consensus"), "(and req:[(or raft paxos)] should:[consensus])");
}

TEST(Parser, NotOnlyQuery) { EXPECT_EQ(ast("NOT tutorial"), "(and not:[tutorial])"); }

TEST(Parser, DoubleNegationCancels) { EXPECT_EQ(ast("NOT NOT raft"), "raft"); }

TEST(Parser, NotAppliesToGroupsAndFilters) {
    EXPECT_EQ(ast("dpll NOT (example OR exercise) NOT week:7"), "(and should:[dpll] not:[(or example exercise) week:7])");
}

TEST(Parser, LowercaseOperatorsAreWords) { EXPECT_EQ(ast("and or not"), "(and should:[and or not])"); }

TEST(Parser, ModuleDigitSuffixAndDigitsInWords) {
    EXPECT_EQ(ast("module:21111"), "module:21111");
    EXPECT_EQ(ast("COMP12111:Overview"), "\"comp12111 overview\"");  // non-letter key: just text
}

TEST(Parser, NestedGroupsAndOr) {
    EXPECT_EQ(ast("((a OR b) AND c) OR (d e)"), "(or (and req:[(or a b) c]) (and should:[d e]))");
}

TEST(Parser, WordsWithNoTermsAreIgnored) {
    EXPECT_EQ(ast("— raft …"), "raft");
    EXPECT_EQ(ast("raft AND — log"), "(and req:[raft log])");  // AND carries over the empty word
}

TEST(Parser, EmptyQueriesHaveNoTreeAndNoError) {
    EXPECT_EQ(ast(""), "<empty>");
    EXPECT_EQ(ast("   \t "), "<empty>");
    EXPECT_EQ(ast("— …"), "<empty>");
}

TEST(Parser, PossessiveAndMathLettersTokenizeLikeDocuments) {
    EXPECT_EQ(ast("Moore’s law"), "(and should:[moore law])");
    EXPECT_EQ(ast("𝑫𝑩𝑴𝑺"), "dbms");
}

// ---- Invalid queries: message and 1-based column ----------------------------------------------

TEST(ParserErrors, UnterminatedPhrase) {
    auto e = error("dpll \"unit propagation");
    EXPECT_EQ(e.message, "unterminated phrase starting at column 6");
    EXPECT_EQ(e.column, 6u);
}

TEST(ParserErrors, UnclosedParen) {
    auto e = error("(raft OR paxos");
    EXPECT_EQ(e.message, "expected ')' to close '(' at column 1");
    EXPECT_EQ(e.column, 1u);
}

TEST(ParserErrors, StrayCloseParen) {
    auto e = error("raft)");
    EXPECT_EQ(e.message, "unexpected ')' at column 5");
    EXPECT_EQ(e.column, 5u);
}

TEST(ParserErrors, DanglingAnd) {
    auto e = error("raft AND");
    EXPECT_EQ(e.message, "expected a word, phrase, filter or '(' after AND at column 6");
    EXPECT_EQ(e.column, 9u);  // points at where the operand should be
}

TEST(ParserErrors, LeadingOr) {
    auto e = error("OR raft");
    EXPECT_EQ(e.message, "expected a word, phrase, filter or '(' at column 1, found OR");
    EXPECT_EQ(e.column, 1u);
}

TEST(ParserErrors, TrailingOr) { EXPECT_EQ(error("raft OR").message, "expected a word, phrase, filter or '(' after OR at column 6"); }

TEST(ParserErrors, LoneNot) { EXPECT_EQ(error("NOT").message, "expected a word, phrase, filter or '(' after NOT at column 1"); }

TEST(ParserErrors, NotOfNothingSearchable) {
    EXPECT_EQ(error("raft NOT —").message, "expected a word, phrase, filter or '(' after NOT at column 6");
}

TEST(ParserErrors, EmptyGroup) {
    auto e = error("raft ()");
    EXPECT_EQ(e.message, "empty group at column 6");
    EXPECT_EQ(e.column, 6u);
}

TEST(ParserErrors, EmptyPhrase) { EXPECT_EQ(error("\"  \"").message, "empty phrase at column 1"); }

TEST(ParserErrors, MissingFilterValue) { EXPECT_EQ(error("dpll module:").message, "missing value for module: at column 6"); }

TEST(ParserErrors, BadWeek) {
    EXPECT_EQ(error("week:x").message, "week must be a number or a range like 4..6 (column 1)");
    EXPECT_EQ(error("week:4..").message, "week must be a number or a range like 4..6 (column 1)");
    EXPECT_EQ(error("week:99999999").message, "week must be a number or a range like 4..6 (column 1)");
}

TEST(ParserErrors, ReversedWeekRange) { EXPECT_EQ(error("week:6..4").message, "empty week range 6..4 (column 1)"); }

TEST(ParserErrors, UnknownType) {
    EXPECT_EQ(error("type:video").message, "unknown type 'video' at column 1 (use slides, pdf or audio)");
}

TEST(ParserErrors, UnknownFilterKey) {
    EXPECT_EQ(error("raft foo:bar").message, "unknown filter 'foo' at column 6 (use module:, week: or type:)");
}

TEST(ParserErrors, ColumnsCountCharactersNotBytes) {
    auto e = error("café )");  // é is 2 bytes in UTF-8 but one column
    EXPECT_EQ(e.column, 6u);
}

TEST(ParserErrors, LeadingAnd) { EXPECT_EQ(error("AND raft").message, "expected a word, phrase, filter or '(' at column 1, found AND"); }

// ---- Lexer details ----------------------------------------------------------------------------

TEST(Lexer, TokensAndColumns) {
    auto toks = lex("(a OR “b c”) NOT d:e");
    std::vector<std::pair<LexToken::Kind, size_t>> got;
    for (auto& t : toks) got.push_back({t.kind, t.column});
    using K = LexToken::Kind;
    std::vector<std::pair<K, size_t>> want = {{K::LParen, 1}, {K::Word, 2}, {K::Or, 4}, {K::Phrase, 7},
                                              {K::RParen, 12}, {K::Not, 14}, {K::Word, 18}, {K::End, 21}};
    EXPECT_EQ(got, want);
    EXPECT_EQ(toks[3].text, "b c");
}

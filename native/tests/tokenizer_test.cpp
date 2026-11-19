#include <gtest/gtest.h>

#include "index/tokenizer.h"

using retrieve::tokenize;

namespace {
std::vector<std::string> terms(std::string_view text) {
    std::vector<std::string> out;
    for (auto& t : tokenize(text)) out.push_back(t.term);
    return out;
}
std::vector<uint32_t> positions(std::string_view text) {
    std::vector<uint32_t> out;
    for (auto& t : tokenize(text)) out.push_back(t.position);
    return out;
}
using V = std::vector<std::string>;
}  // namespace

TEST(Tokenizer, LowercasesAndSplitsOnAsciiPunctuation) {
    EXPECT_EQ(terms("Raft LOG-matching: (term, index)!"), (V{"raft", "log", "matching", "term", "index"}));
}

TEST(Tokenizer, EmptyAndSeparatorOnlyInput) {
    EXPECT_TRUE(tokenize("").empty());
    EXPECT_TRUE(tokenize("  ,.;:!? \n\t").empty());
}

TEST(Tokenizer, DigitsAreWordCharacters) {
    EXPECT_EQ(terms("COMP26120 week 4, 16:1 mux"), (V{"comp26120", "week", "4", "16", "1", "mux"}));
}

TEST(Tokenizer, CurlyApostropheSplitsLikeAsciiApostrophe) {
    EXPECT_EQ(terms("Moore’s law"), terms("Moore's law"));
    EXPECT_EQ(terms("rock’n’roll"), (V{"rock", "n", "roll"}));
}

TEST(Tokenizer, PossessiveSIsDropped) {
    EXPECT_EQ(terms("Moore’s law"), (V{"moore", "law"}));
    EXPECT_EQ(terms("Dijkstra's algorithm, Ohm's."), (V{"dijkstra", "algorithm", "ohm"}));
    EXPECT_EQ(terms("it's"), (V{"it"}));
    EXPECT_EQ(terms("CHARLES'S"), (V{"charles"}));
    EXPECT_EQ(terms("'s alone"), (V{"s", "alone"}));          // no word before: an ordinary "s"
    EXPECT_EQ(terms("x'sy"), (V{"x", "sy"}));                  // "s" starts a longer word: not a possessive
    EXPECT_EQ(terms("students' work"), (V{"students", "work"}));
}

TEST(Tokenizer, PossessiveDoesNotShiftPositions) {
    auto toks = tokenize("Moore's law holds");
    ASSERT_EQ(toks.size(), 3u);
    EXPECT_EQ(toks[1].term, "law");
    EXPECT_EQ(toks[1].position, 1u);  // so the phrase "moore's law" matches "moore law" adjacency
}

TEST(Tokenizer, UnicodeDashesBulletsAndQuotesSeparate) {
    EXPECT_EQ(terms("5.1 — Constant–variables • “named” …end"), (V{"5", "1", "constant", "variables", "named", "end"}));
}

TEST(Tokenizer, LogicAndArrowSymbolsSeparate) {
    EXPECT_EQ(terms("¬p∧q→r ∨ s ≤ t ▪ x×y"), (V{"p", "q", "r", "s", "t", "x", "y"}));
}

TEST(Tokenizer, NonAsciiLettersStayInsideTerms) {
    EXPECT_EQ(terms("Gödel’s naïve café"), (V{"gödel", "naïve", "café"}));
}

TEST(Tokenizer, MathAlphanumericSymbolsFoldToAscii) {
    // Real text from the COMP23111 Transactions slides (bold italic), plus other styles.
    EXPECT_EQ(terms("𝑫𝑩𝑴𝑺 𝑩𝒖𝒇𝒇𝒆𝒓 𝑻𝟏"), (V{"dbms", "buffer", "t1"}));
    EXPECT_EQ(terms("𝐀𝐳 𝑥 𝔄 𝕫 𝟎𝟗 𝟿"), (V{"az", "x", "a", "z", "09", "9"}));
    EXPECT_EQ(terms("𝑷𝒓𝒐𝒈𝒓𝒂𝒎𝑴𝒆𝒎𝒐𝒓𝒚"), (V{"programmemory"}));
    EXPECT_EQ(terms("𝚫𝛂"), (V{"𝚫𝛂"}));  // Greek styles are left alone
}

TEST(Tokenizer, MalformedUtf8IsASeparator) {
    const std::string bad = std::string("abc") + "\xC3" + "def" + "\xFF\xFE" + "ghi";
    EXPECT_EQ(terms(bad), (V{"abc", "def", "ghi"}));
    const std::string overlong = std::string("ab") + "\xC0\xAF" + "cd";  // overlong encoding of '/'
    EXPECT_EQ(terms(overlong), (V{"ab", "cd"}));
    const std::string truncated = std::string("ab") + "\xE2\x80";  // cut off mid-character
    EXPECT_EQ(terms(truncated), (V{"ab"}));
}

TEST(Tokenizer, PositionsCountTokens) {
    EXPECT_EQ(positions("the log, the term"), (std::vector<uint32_t>{0, 1, 2, 3}));
}

TEST(Tokenizer, OverlongTermsAreDroppedButKeepTheirPosition) {
    const std::string blob(65, 'a');
    auto toks = tokenize("before " + blob + " after");
    ASSERT_EQ(toks.size(), 2u);
    EXPECT_EQ(toks[0].term, "before");
    EXPECT_EQ(toks[1].term, "after");
    EXPECT_EQ(toks[1].position, 2u);  // not 1: "before" and "after" must not look adjacent
    EXPECT_EQ(tokenize(std::string(64, 'b')).size(), 1u);  // exactly the limit is kept
}

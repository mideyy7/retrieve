#include <gtest/gtest.h>

#include <filesystem>
#include <unistd.h>

#include "index/bm25.h"
#include "index/builder.h"

using namespace retrieve;
namespace fs = std::filesystem;

// Hand-checkable corpus. Expected scores were computed independently in Python with
//   idf = ln(1 + (N - df + 0.5) / (df + 0.5)),  k1 = 1.2, b = 0.75, N = 3, avgdl = (3+5+4)/3 = 4
// e.g. "raft" in doc 2: df = 2 -> idf = ln(1.6) = 0.470003629; tf = 2, dl = 4 -> norm = 1
//      -> 0.470003629 * (2 * 2.2) / (2 + 1.2) = 0.646254990
class Bm25 : public ::testing::Test {
protected:
    void SetUp() override {
        path_ = fs::temp_directory_path() / ("retrieve_bm25_" + std::to_string(::getpid()) + ".idx");
        IndexBuilder b;
        b.add({"d0", "COMP26120", 4, 0, 1, -1, "the raft leader"});
        b.add({"d1", "COMP25111", 5, 2, -1, 30000, "log replication of the log"});
        b.add({"d2", "COMP26120", 6, 0, 1, -1, "raft log matching raft"});
        std::string err;
        ASSERT_TRUE(b.writeTo(path_.string(), &err)) << err;
        index_ = Index::open(path_.string(), &err);
        ASSERT_NE(index_, nullptr) << err;
    }
    void TearDown() override {
        index_.reset();
        fs::remove(path_);
    }
    std::vector<Hit> search(std::string_view q, size_t k = 10) { return Searcher(*index_).search(q, k); }

    fs::path path_;
    std::unique_ptr<Index> index_;
};

TEST_F(Bm25, SingleTermMatchesHandComputedScores) {
    auto hits = search("raft");
    ASSERT_EQ(hits.size(), 2u);
    EXPECT_EQ(hits[0].doc, 2u);
    EXPECT_NEAR(hits[0].score, 0.646254990213, 1e-9);
    EXPECT_EQ(hits[1].doc, 0u);
    EXPECT_NEAR(hits[1].score, 0.523548346502, 1e-9);
}

TEST_F(Bm25, HigherTermFrequencyAndShorterDocWin) {
    auto hits = search("log");
    ASSERT_EQ(hits.size(), 2u);
    EXPECT_EQ(hits[0].doc, 1u);  // tf 2 beats tf 1 despite the longer doc
    EXPECT_NEAR(hits[0].score, 0.603800282827, 1e-9);
    EXPECT_NEAR(hits[1].score, 0.470003629246, 1e-9);
}

TEST_F(Bm25, MultipleTermsSumAndMatchAny) {
    auto hits = search("raft log");
    ASSERT_EQ(hits.size(), 3u);
    EXPECT_EQ(hits[0].doc, 2u);
    EXPECT_NEAR(hits[0].score, 1.116258619459, 1e-9);
    EXPECT_EQ(hits[1].doc, 1u);
    EXPECT_EQ(hits[2].doc, 0u);
}

TEST_F(Bm25, RepeatedQueryTermCountsOnce) {
    auto once = search("raft"), twice = search("raft RAFT raft");
    ASSERT_EQ(once.size(), twice.size());
    for (size_t i = 0; i < once.size(); ++i) EXPECT_DOUBLE_EQ(once[i].score, twice[i].score);
}

TEST_F(Bm25, QueryIsTokenizedLikeDocuments) {
    auto hits = search("RAFT!!");
    ASSERT_EQ(hits.size(), 2u);
    EXPECT_EQ(hits[0].doc, 2u);
}

TEST_F(Bm25, CommonWordStillAddsPositiveScore) {
    // "the" is in 2 of 3 docs; the classic Robertson idf would be negative here.
    for (auto& h : search("the")) EXPECT_GT(h.score, 0.0);
    EXPECT_NEAR(search("the")[1].score, 0.426395045089, 1e-9);
}

TEST_F(Bm25, TopKAndDeterministicTieBreak) {
    EXPECT_EQ(search("raft log", 1).size(), 1u);
    EXPECT_TRUE(search("raft", 0).empty());
    // "matching" and "leader" each occur once in one doc of the same... different lengths; use an exact tie:
    IndexBuilder b;
    b.add({"z", "M", 1, 0, 1, -1, "same words"});
    b.add({"a", "M", 1, 0, 2, -1, "same words"});
    auto p = fs::temp_directory_path() / ("retrieve_tie_" + std::to_string(::getpid()) + ".idx");
    std::string err;
    ASSERT_TRUE(b.writeTo(p.string(), &err));
    auto idx = Index::open(p.string(), &err);
    auto hits = Searcher(*idx).search("same", 10);
    ASSERT_EQ(hits.size(), 2u);
    EXPECT_DOUBLE_EQ(hits[0].score, hits[1].score);
    EXPECT_EQ(hits[0].doc, 0u);  // equal scores -> lower doc id ("a") first
    fs::remove(p);
}

TEST_F(Bm25, NoMatchesAndEmptyQuery) {
    EXPECT_TRUE(search("paxos").empty());
    EXPECT_TRUE(search("").empty());
    EXPECT_TRUE(search("  ?! ").empty());
}

TEST_F(Bm25, PhraseRequiresAdjacentTermsInOrder) {
    EXPECT_TRUE(containsPhrase(*index_, 2, {"raft", "log"}));
    EXPECT_FALSE(containsPhrase(*index_, 2, {"log", "raft"}));       // both present, wrong order
    EXPECT_FALSE(containsPhrase(*index_, 2, {"raft", "matching"}));  // both present, not adjacent
    EXPECT_TRUE(containsPhrase(*index_, 1, {"the", "log"}));
    EXPECT_TRUE(containsPhrase(*index_, 2, {"matching", "raft"}));   // second occurrence of raft
    EXPECT_FALSE(containsPhrase(*index_, 0, {"raft", "log"}));       // doc lacks "log"
}

TEST_F(Bm25, QuotedPhraseFiltersCandidates) {
    auto hits = search("\"raft log\"");
    ASSERT_EQ(hits.size(), 1u);
    EXPECT_EQ(hits[0].doc, 2u);
    EXPECT_NEAR(hits[0].score, 1.116258619459, 1e-9);  // scored over the phrase's terms

    auto mixed = search("leader \"the log\"");  // phrase must match; "leader" only adds score
    ASSERT_EQ(mixed.size(), 1u);
    EXPECT_EQ(mixed[0].doc, 1u);

    EXPECT_TRUE(search("\"log raft\"").empty());
}

TEST_F(Bm25, ParseSimpleQuery) {
    auto q = parseSimpleQuery("Raft \"log matching\" leader \"\" \"unclosed term");
    EXPECT_EQ(q.terms, (std::vector<std::string>{"raft", "leader", "unclosed", "term"}));
    ASSERT_EQ(q.phrases.size(), 1u);
    EXPECT_EQ(q.phrases[0], (std::vector<std::string>{"log", "matching"}));
}

TEST(Bm25Formula, TermScoreSaturatesAndNormalisesLength) {
    Bm25Params p;
    const double idf = 1.0;
    EXPECT_LT(bm25TermScore(idf, 10, 4, 4.0, p), idf * (p.k1 + 1));  // bounded by idf*(k1+1)
    EXPECT_GT(bm25TermScore(idf, 2, 4, 4.0, p), bm25TermScore(idf, 1, 4, 4.0, p));
    EXPECT_GT(bm25TermScore(idf, 1, 2, 4.0, p), bm25TermScore(idf, 1, 8, 4.0, p));  // shorter doc scores higher
    Bm25Params noLength{1.2, 0.0};
    EXPECT_DOUBLE_EQ(bm25TermScore(idf, 1, 2, 4.0, noLength), bm25TermScore(idf, 1, 8, 4.0, noLength));
}

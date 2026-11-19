#include <gtest/gtest.h>

#include <filesystem>
#include <map>
#include <random>
#include <set>
#include <unistd.h>

#include "index/builder.h"
#include "query/executor.h"
#include "query/parser.h"
#include "query/random_query.h"

using namespace retrieve;
using namespace retrieve::query;
namespace fs = std::filesystem;

namespace {

fs::path tempPath(const std::string& name) {
    return fs::temp_directory_path() / ("retrieve_exec_" + name + "_" + std::to_string(::getpid()) + ".idx");
}

std::unique_ptr<Index> build(IndexBuilder& b, const std::string& name) {
    std::string err;
    EXPECT_TRUE(b.writeTo(tempPath(name).string(), &err)) << err;
    auto idx = Index::open(tempPath(name).string(), &err);
    EXPECT_NE(idx, nullptr) << err;
    return idx;
}

}  // namespace

// Small corpus with metadata, for checking each semantic rule by hand.
class Semantics : public ::testing::Test {
protected:
    void SetUp() override {
        IndexBuilder b;
        //       key   module       wk type page text
        b.add({"a1", "COMP21111", 4, 0, 1, -1, "unit propagation removes the unit"});
        b.add({"a2", "COMP21111", 4, 0, 2, -1, "unit propagation example with clauses"});
        b.add({"a3", "COMP21111", 5, 0, 1, -1, "tableau method example"});
        b.add({"a4", "COMP21111", 7, 0, 1, -1, "binary decision diagrams reduce"});
        b.add({"b1", "COMP23111", 4, 1, 1, -1, "stored procedure parameters in mysql"});
        b.add({"b2", "COMP23111", 4, 1, 2, -1, "create trigger before update on products"});
        b.add({"b3", "COMP23111", 5, 1, 3, -1, "transactions acid isolation durability"});
        b.add({"c1", "COMP22111", 1, 2, -1, 60000, "risc-v datapath and the alu unit"});
        idx_ = build(b, "semantics");
    }
    void TearDown() override { fs::remove(tempPath("semantics")); }

    // Stable keys of all results, in result order.
    std::vector<std::string> keys(std::string_view q) {
        auto root = parse(q);
        if (!root) return {};
        std::vector<std::string> out;
        for (const auto& h : executePlanned(*idx_, *root, kAllResults)) out.emplace_back(idx_->doc(h.doc).stableKey);
        return out;
    }
    using Keys = std::vector<std::string>;
    std::unique_ptr<Index> idx_;
};

TEST_F(Semantics, ShouldWordsMatchAny) { EXPECT_EQ(keys("tableau trigger"), (Keys{"a3", "b2"})); }

TEST_F(Semantics, FilterRestrictsAndShouldStaysMandatory) {
    EXPECT_EQ(keys("module:COMP21111 unit"), (Keys{"a1", "a2"}));  // c1 has "unit" but is another module
}

TEST_F(Semantics, PhraseMakesShouldOptional) {
    // a2 has the phrase but not "removes": still matches; "removes" only boosts a1.
    EXPECT_EQ(keys("\"unit propagation\" removes"), (Keys{"a1", "a2"}));
}

TEST_F(Semantics, ExplicitAndRequiresBoth) {
    EXPECT_EQ(keys("unit AND example"), (Keys{"a2"}));
    EXPECT_EQ(keys("unit AND example clauses"), (Keys{"a2"}));
}

TEST_F(Semantics, NotIsSetDifference) {
    EXPECT_EQ(keys("unit NOT example"), (Keys{"a1", "c1"}));
}

TEST_F(Semantics, NotOnlyQueryIsEverythingElseInCourseOrder) {
    // c1 ("... alu unit") is excluded too; the rest come in module, week, page order.
    EXPECT_EQ(keys("NOT unit"), (Keys{"a3", "a4", "b1", "b2", "b3"}));
}

TEST_F(Semantics, FilterOnlyQueryListsCourseOrder) {
    EXPECT_EQ(keys("module:COMP23111"), (Keys{"b1", "b2", "b3"}));
    EXPECT_EQ(keys("type:audio"), (Keys{"c1"}));
}

TEST_F(Semantics, WeekRangesAreInclusive) {
    EXPECT_EQ(keys("module:COMP21111 week:5..7"), (Keys{"a3", "a4"}));
    EXPECT_EQ(keys("week:4"), (Keys{"a1", "a2", "b1", "b2"}));
    EXPECT_EQ(keys("week:5..5"), (Keys{"a3", "b3"}));
    EXPECT_EQ(keys("week:8..52"), (Keys{}));
}

TEST_F(Semantics, ModuleFilterIsCaseInsensitiveAndAcceptsDigitSuffix) {
    EXPECT_EQ(keys("module:comp23111 week:5"), (Keys{"b3"}));
    EXPECT_EQ(keys("module:23111 week:5"), (Keys{"b3"}));
    EXPECT_EQ(keys("module:COMP99999"), (Keys{}));
    EXPECT_EQ(keys("module:111 week:1"), (Keys{"c1"}));  // suffix of every module code
}

TEST_F(Semantics, OrUnionsAndGroupsWork) {
    EXPECT_EQ(keys("(tableau OR trigger) NOT update"), (Keys{"a3"}));
    EXPECT_EQ(keys("module:COMP23111 OR tableau"), (Keys{"a3", "b1", "b2", "b3"}));
}

TEST_F(Semantics, MultiTermWordIsAPhrase) {
    EXPECT_EQ(keys("RISC-V"), (Keys{"c1"}));
    EXPECT_EQ(keys("\"propagation unit\""), (Keys{}));  // wrong order
}

TEST_F(Semantics, NegatedTermsNeverScore) {
    auto root = parse("unit NOT example");
    for (const auto& h : executePlanned(*idx_, *root, kAllResults)) {
        auto alone = executePlanned(*idx_, *parse("unit"), kAllResults);
        for (const auto& a : alone) if (a.doc == h.doc) EXPECT_DOUBLE_EQ(a.score, h.score);
    }
}

TEST_F(Semantics, TopKKeepsTheBest) {
    auto root = parse("unit");
    auto all = executePlanned(*idx_, *root, kAllResults);
    auto top1 = executePlanned(*idx_, *root, 1);
    ASSERT_EQ(top1.size(), 1u);
    EXPECT_EQ(top1[0].doc, all[0].doc);
}

// ---- Planner equivalence: planned and naive must agree exactly on random queries -------------

class Equivalence : public ::testing::Test {
protected:
    void SetUp() override {
        // Synthetic corpus with a Zipf-like vocabulary, so terms span very rare to very common.
        std::mt19937 rng(20261119);
        std::vector<std::string> vocab;
        for (int i = 0; i < 150; ++i) vocab.push_back("w" + std::to_string(i));
        std::vector<double> weights;
        for (int i = 0; i < 150; ++i) weights.push_back(1.0 / (i + 1));
        std::discrete_distribution<int> word(weights.begin(), weights.end());
        const std::vector<std::string> modules = {"COMP21111", "COMP22111", "COMP23111", "COMP12111"};

        IndexBuilder b;
        for (int d = 0; d < 400; ++d) {
            std::string text;
            const int len = std::uniform_int_distribution<int>(5, 120)(rng);
            std::vector<std::string> words;
            for (int i = 0; i < len; ++i) words.push_back(vocab[static_cast<size_t>(word(rng))]);
            for (const auto& w : words) text += w + " ";
            if (d % 7 == 0 && words.size() >= 3) phrases_.push_back({words[0], words[1], words[2]});
            if (d % 5 == 0 && words.size() >= 2) phrases_.push_back({words[0], words[1]});
            DocInput in;
            in.stableKey = "doc" + std::to_string(d);
            in.module = modules[static_cast<size_t>(d) % modules.size()];
            in.week = static_cast<uint16_t>(1 + (d * 7) % 12);
            in.type = static_cast<uint8_t>(d % 3);
            in.page = d % 3 == 2 ? -1 : d % 40 + 1;
            in.startMs = d % 3 == 2 ? (d % 50) * 30000 : -1;
            in.text = text;
            b.add(std::move(in));
        }
        idx_ = build(b, "equiv");
        for (int i = 0; i < 150; ++i) for (int r = 0; r < static_cast<int>(30.0 / (i + 1)) + 1; ++r) words_.push_back(vocab[static_cast<size_t>(i)]);
        words_.push_back("absentword");
        modules_ = modules;
    }
    void TearDown() override { fs::remove(tempPath("equiv")); }

    std::unique_ptr<Index> idx_;
    std::vector<std::string> words_;
    std::vector<std::vector<std::string>> phrases_;
    std::vector<std::string> modules_;
};

TEST_F(Equivalence, PlannedEqualsNaiveOnRandomQueries) {
    RandomQueryGenerator gen({words_, phrases_, modules_, 12}, /*seed=*/42);
    int compared = 0, nonEmpty = 0;
    std::set<std::string> distinct;
    for (int i = 0; i < 1000 && compared < 600; ++i) {
        const std::string q = gen.next();
        if (!distinct.insert(q).second) continue;
        auto root = parse(q);
        ASSERT_NE(root, nullptr) << q;
        const auto planned = executePlanned(*idx_, *root, kAllResults);
        const auto naive = executeNaive(*idx_, *root, kAllResults);
        ASSERT_EQ(planned.size(), naive.size()) << q << "\n  " << toString(*root);
        for (size_t r = 0; r < planned.size(); ++r) {
            ASSERT_EQ(planned[r].doc, naive[r].doc) << q << " rank " << r;
            ASSERT_EQ(planned[r].score, naive[r].score) << q << " rank " << r;  // bit-for-bit
        }
        // Top-k must be a prefix of the full ranking for both.
        const auto top5 = executePlanned(*idx_, *root, 5);
        for (size_t r = 0; r < top5.size(); ++r) ASSERT_EQ(top5[r].doc, planned[r].doc) << q;
        ++compared;
        nonEmpty += !planned.empty();
    }
    EXPECT_GE(compared, 500);
    EXPECT_GE(nonEmpty, compared / 3) << "random queries should mostly hit something to be a meaningful test";
}

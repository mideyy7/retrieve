#include <gtest/gtest.h>

#include <cstdio>
#include <filesystem>
#include <fstream>

#include "index/builder.h"
#include "index/index.h"

using namespace retrieve;
namespace fs = std::filesystem;

namespace {

fs::path tempIndexPath(const std::string& name) {
    return fs::temp_directory_path() / ("retrieve_test_" + name + "_" + std::to_string(::getpid()) + ".idx");
}

// Small hand-checkable corpus. Keys are deliberately added out of order.
IndexBuilder smallCorpus() {
    IndexBuilder b;
    b.add({"c:p1:0", "COMP26120", 6, 0, 1, -1, "raft log matching raft"});
    b.add({"a:p1:0", "COMP26120", 4, 0, 1, -1, "the raft leader"});
    b.add({"b:t30000", "COMP25111", 5, 2, -1, 30000, "log replication of the log"});
    return b;
}

std::unique_ptr<Index> buildAndOpen(const IndexBuilder& b, const fs::path& path) {
    std::string err;
    EXPECT_TRUE(b.writeTo(path.string(), &err)) << err;
    auto idx = Index::open(path.string(), &err);
    EXPECT_NE(idx, nullptr) << err;
    return idx;
}

}  // namespace

TEST(Index, DocsAreOrderedByStableKeyWithMetadata) {
    auto path = tempIndexPath("meta");
    auto idx = buildAndOpen(smallCorpus(), path);
    ASSERT_NE(idx, nullptr);
    ASSERT_EQ(idx->numDocs(), 3u);
    EXPECT_EQ(idx->doc(0).stableKey, "a:p1:0");
    EXPECT_EQ(idx->doc(1).stableKey, "b:t30000");
    EXPECT_EQ(idx->doc(2).stableKey, "c:p1:0");

    auto audio = idx->doc(1);
    EXPECT_EQ(audio.module, "COMP25111");
    EXPECT_EQ(audio.week, 5);
    EXPECT_EQ(audio.type, 2);
    EXPECT_EQ(audio.page, -1);
    EXPECT_EQ(audio.startMs, 30000);
    EXPECT_EQ(idx->doc(2).page, 1);
    EXPECT_EQ(idx->numModules(), 2u);
    EXPECT_EQ(idx->moduleId("COMP26120"), std::optional<uint16_t>(1));
    EXPECT_EQ(idx->moduleId("COMP99999"), std::nullopt);
    fs::remove(path);
}

TEST(Index, LengthsAndAverageLength) {
    auto path = tempIndexPath("len");
    auto idx = buildAndOpen(smallCorpus(), path);
    ASSERT_NE(idx, nullptr);
    EXPECT_EQ(idx->doc(0).length, 3u);  // the raft leader
    EXPECT_EQ(idx->doc(1).length, 5u);  // log replication of the log
    EXPECT_EQ(idx->doc(2).length, 4u);  // raft log matching raft
    EXPECT_DOUBLE_EQ(idx->avgDocLength(), 4.0);
    fs::remove(path);
}

TEST(Index, PostingsHaveDocFrequencyTermFrequencyAndPositions) {
    auto path = tempIndexPath("post");
    auto idx = buildAndOpen(smallCorpus(), path);
    ASSERT_NE(idx, nullptr);

    auto raft = idx->postings("raft");
    ASSERT_EQ(raft.size(), 2u);           // df = 2
    EXPECT_EQ(raft[0].doc, 0u);
    EXPECT_EQ(raft[0].tf, 1u);
    EXPECT_EQ(raft[1].doc, 2u);
    EXPECT_EQ(raft[1].tf, 2u);
    auto pos = raft.positions(1);
    ASSERT_EQ(pos.size, 2u);
    EXPECT_EQ(pos.data[0], 0u);
    EXPECT_EQ(pos.data[1], 3u);

    auto log = idx->postings("log");
    ASSERT_EQ(log.size(), 2u);
    EXPECT_EQ(log[0].doc, 1u);
    EXPECT_EQ(log[0].tf, 2u);
    EXPECT_EQ(log.positions(0).data[1], 4u);

    EXPECT_EQ(idx->postings("missing").size(), 0u);
    EXPECT_EQ(idx->postings("Raft").size(), 0u);  // terms are stored lowercased; callers tokenize first
    fs::remove(path);
}

TEST(Index, SameChunksInAnyOrderGiveByteIdenticalFiles) {
    IndexBuilder forward, reverse;
    std::vector<DocInput> docs = {
        {"k1", "M", 1, 0, 1, -1, "alpha beta"}, {"k2", "M", 1, 0, 2, -1, "beta gamma"}, {"k3", "N", 2, 1, 3, -1, "gamma"}};
    for (auto& d : docs) forward.add(d);
    for (auto it = docs.rbegin(); it != docs.rend(); ++it) reverse.add(*it);
    auto p1 = tempIndexPath("fwd"), p2 = tempIndexPath("rev");
    std::string err;
    ASSERT_TRUE(forward.writeTo(p1.string(), &err)) << err;
    ASSERT_TRUE(reverse.writeTo(p2.string(), &err)) << err;
    std::ifstream a(p1, std::ios::binary), b(p2, std::ios::binary);
    std::string sa((std::istreambuf_iterator<char>(a)), {}), sb((std::istreambuf_iterator<char>(b)), {});
    EXPECT_EQ(sa, sb);
    fs::remove(p1);
    fs::remove(p2);
}

TEST(Index, EmptyIndexIsValid) {
    auto path = tempIndexPath("empty");
    auto idx = buildAndOpen(IndexBuilder{}, path);
    ASSERT_NE(idx, nullptr);
    EXPECT_EQ(idx->numDocs(), 0u);
    EXPECT_EQ(idx->postings("anything").size(), 0u);
    fs::remove(path);
}

TEST(Index, DuplicateStableKeysAreRejected) {
    IndexBuilder b;
    b.add({"same", "M", 1, 0, 1, -1, "one"});
    b.add({"same", "M", 1, 0, 2, -1, "two"});
    std::string err;
    EXPECT_FALSE(b.writeTo(tempIndexPath("dup").string(), &err));
    EXPECT_NE(err.find("duplicate"), std::string::npos);
}

TEST(Index, RewriteReplacesFileAtomicallyAndLeavesNoTempFiles) {
    auto path = tempIndexPath("atomic");
    auto dir = path.parent_path();
    std::string err;
    ASSERT_TRUE(smallCorpus().writeTo(path.string(), &err)) << err;
    IndexBuilder bigger = smallCorpus();
    bigger.add({"d:p1:0", "COMP26120", 7, 0, 1, -1, "extra"});
    ASSERT_TRUE(bigger.writeTo(path.string(), &err)) << err;
    auto idx = Index::open(path.string(), &err);
    ASSERT_NE(idx, nullptr) << err;
    EXPECT_EQ(idx->numDocs(), 4u);
    for (auto& e : fs::directory_iterator(dir)) {
        EXPECT_EQ(e.path().string().find(path.filename().string() + ".tmp."), std::string::npos) << e.path();
    }
    fs::remove(path);
}

TEST(Index, MissingFileIsAnError) {
    std::string err;
    EXPECT_EQ(Index::open("/nonexistent/dir/x.idx", &err), nullptr);
    EXPECT_NE(err.find("cannot open"), std::string::npos);
}

class CorruptIndex : public ::testing::Test {
protected:
    void SetUp() override {
        path_ = tempIndexPath("corrupt");
        std::string err;
        ASSERT_TRUE(smallCorpus().writeTo(path_.string(), &err)) << err;
        std::ifstream in(path_, std::ios::binary);
        bytes_.assign(std::istreambuf_iterator<char>(in), {});
    }
    void TearDown() override { fs::remove(path_); }

    std::string openModified() {
        std::ofstream(path_, std::ios::binary | std::ios::trunc).write(bytes_.data(), static_cast<std::streamsize>(bytes_.size()));
        std::string err;
        EXPECT_EQ(Index::open(path_.string(), &err), nullptr);
        return err;
    }
    template <typename T>
    void poke(size_t offset, T value) { std::memcpy(bytes_.data() + offset, &value, sizeof value); }
    const format::FileHeader& header() { return *reinterpret_cast<const format::FileHeader*>(bytes_.data()); }

    fs::path path_;
    std::string bytes_;
};

TEST_F(CorruptIndex, BadMagic) {
    bytes_[0] = 'X';
    EXPECT_NE(openModified().find("bad magic"), std::string::npos);
}

TEST_F(CorruptIndex, Truncated) {
    bytes_.resize(bytes_.size() - 10);
    EXPECT_NE(openModified().find("truncated"), std::string::npos);
}

TEST_F(CorruptIndex, TooSmallForHeader) {
    bytes_.resize(20);
    EXPECT_NE(openModified().find("too small"), std::string::npos);
}

TEST_F(CorruptIndex, PostingPointsPastLastDoc) {
    poke<uint32_t>(header().offPostings, 999);  // first posting's doc id
    EXPECT_NE(openModified().find("doc ids"), std::string::npos);
}

TEST_F(CorruptIndex, HugeSectionCountDoesNotOverflow) {
    poke<uint64_t>(offsetof(format::FileHeader, numPositions), UINT64_MAX / 2);
    EXPECT_NE(openModified().find("out of bounds"), std::string::npos);
}

TEST_F(CorruptIndex, TermStringOutsidePool) {
    poke<uint32_t>(header().offTerms, 0xFFFFFF00u);  // first term's strOff
    EXPECT_NE(openModified().find("term string"), std::string::npos);
}

// retrieve_cli: the keyword engine on the desktop, built from the same sources as the app.
//
//   retrieve_cli build  <chunks.json> <out.idx>      build an index from an app export
//   retrieve_cli search <index.idx> <query> [-k N]   top-N hits as JSON lines
//   retrieve_cli stats  <index.idx>                  index statistics as JSON
//
// ml/eval.py calls `search` so BM25 recall is measured on the real engine (plan problem 5), and
// the device-vs-CLI check compares its output with the app's.

#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>

#include <nlohmann/json.hpp>

#include "index/bm25.h"
#include "index/builder.h"
#include "index/index.h"

using json = nlohmann::json;
using namespace retrieve;

namespace {

int usage() {
    std::fprintf(stderr,
                 "usage:\n"
                 "  retrieve_cli build  <chunks.json> <out.idx>\n"
                 "  retrieve_cli search <index.idx> <query> [-k N]\n"
                 "  retrieve_cli stats  <index.idx>\n");
    return 2;
}

uint8_t typeOrdinal(const std::string& type) {
    if (type == "SLIDES") return 0;
    if (type == "PDF") return 1;
    if (type == "AUDIO") return 2;
    throw std::runtime_error("unknown chunk type: " + type);
}

int cmdBuild(const std::string& chunksPath, const std::string& outPath) {
    const auto t0 = std::chrono::steady_clock::now();
    std::ifstream in(chunksPath);
    if (!in) {
        std::fprintf(stderr, "cannot read %s\n", chunksPath.c_str());
        return 1;
    }
    const json doc = json::parse(in);
    IndexBuilder builder;
    for (const auto& c : doc.at("chunks")) {
        DocInput d;
        d.stableKey = c.at("stable_key").get<std::string>();
        d.module = c.at("module").get<std::string>();
        d.week = c.at("week").get<uint16_t>();
        d.type = typeOrdinal(c.at("type").get<std::string>());
        d.page = c.at("page").is_null() ? -1 : c.at("page").get<int32_t>();
        d.startMs = c.at("start_ms").is_null() ? -1 : c.at("start_ms").get<int64_t>();
        d.text = c.at("text").get<std::string>();
        builder.add(std::move(d));
    }
    std::string err;
    if (!builder.writeTo(outPath, &err)) {
        std::fprintf(stderr, "build failed: %s\n", err.c_str());
        return 1;
    }
    const auto ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    auto idx = Index::open(outPath, &err);
    if (!idx) {
        std::fprintf(stderr, "built index does not open: %s\n", err.c_str());
        return 1;
    }
    std::cout << json{{"chunks", idx->numDocs()}, {"terms", idx->numTerms()}, {"modules", idx->numModules()},
                      {"file_bytes", idx->fileSize()}, {"build_ms", ms}}
                     .dump()
              << "\n";
    return 0;
}

std::unique_ptr<Index> openOrDie(const std::string& path) {
    std::string err;
    auto idx = Index::open(path, &err);
    if (!idx) std::fprintf(stderr, "%s\n", err.c_str());
    return idx;
}

int cmdSearch(const std::string& indexPath, const std::string& query, size_t k) {
    auto idx = openOrDie(indexPath);
    if (!idx) return 1;
    const auto hits = Searcher(*idx).search(query, k);
    for (size_t r = 0; r < hits.size(); ++r) {
        const auto d = idx->doc(hits[r].doc);
        std::cout << json{{"rank", r + 1},
                          {"stable_key", std::string(d.stableKey)},
                          {"score", hits[r].score},
                          {"module", std::string(d.module)},
                          {"week", d.week},
                          {"page", d.page < 0 ? json(nullptr) : json(d.page)},
                          {"start_ms", d.startMs < 0 ? json(nullptr) : json(d.startMs)}}
                         .dump()
                  << "\n";
    }
    return 0;
}

int cmdStats(const std::string& indexPath) {
    auto idx = openOrDie(indexPath);
    if (!idx) return 1;
    json modules = json::array();
    for (uint32_t m = 0; m < idx->numModules(); ++m) modules.push_back(std::string(idx->module(static_cast<uint16_t>(m))));
    std::cout << json{{"chunks", idx->numDocs()}, {"terms", idx->numTerms()}, {"avg_chunk_tokens", idx->avgDocLength()},
                      {"file_bytes", idx->fileSize()}, {"modules", modules}}
                     .dump()
              << "\n";
    return 0;
}

}  // namespace

int main(int argc, char** argv) {
    try {
        if (argc >= 4 && std::strcmp(argv[1], "build") == 0) return cmdBuild(argv[2], argv[3]);
        if (argc >= 4 && std::strcmp(argv[1], "search") == 0) {
            size_t k = 10;
            for (int i = 4; i + 1 < argc; ++i) {
                if (std::strcmp(argv[i], "-k") == 0) k = std::stoul(argv[i + 1]);
            }
            return cmdSearch(argv[2], argv[3], k);
        }
        if (argc >= 3 && std::strcmp(argv[1], "stats") == 0) return cmdStats(argv[2]);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
    return usage();
}

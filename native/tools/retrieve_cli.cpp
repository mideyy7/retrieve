// retrieve_cli: the keyword engine on the desktop, built from the same sources as the app.
//
//   retrieve_cli build  <chunks.json> <out.idx>      build an index from an app export
//   retrieve_cli search <index.idx> <query> [-k N]   top-N hits as JSON lines
//   retrieve_cli stats  <index.idx>                  index statistics as JSON
//   retrieve_cli parse  <query>                      print the query's AST, or its error
//   retrieve_cli equiv  <chunks.json> <index.idx> [--random N] [--seed S]
//                                                    planned == naive on random queries over the real vocabulary
//   retrieve_cli bench  <index.idx> <queries.txt> [--runs R] [--csv out.csv]
//                                                    time planned vs naive per query (results checked equal first)
//
// ml/eval.py calls `search` so BM25 recall is measured on the real engine (plan problem 5), and
// the device-vs-CLI check compares its output with the app's.

#include <algorithm>
#include <chrono>
#include <random>
#include <set>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <string>

#include <nlohmann/json.hpp>

#include "index/bm25.h"
#include "index/builder.h"
#include "index/index.h"
#include "index/tokenizer.h"
#include "query/executor.h"
#include "query/parser.h"
#include "query/random_query.h"

using json = nlohmann::json;
using namespace retrieve;

namespace {

int usage() {
    std::fprintf(stderr,
                 "usage:\n"
                 "  retrieve_cli build  <chunks.json> <out.idx>\n"
                 "  retrieve_cli search <index.idx> <query> [-k N]\n"
                 "  retrieve_cli stats  <index.idx>\n"
                 "  retrieve_cli parse  <query>\n"
                 "  retrieve_cli equiv  <chunks.json> <index.idx> [--random N] [--seed S]\n"
                 "  retrieve_cli bench  <index.idx> <queries.txt> [--runs R] [--csv out.csv]\n");
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
    std::vector<Hit> hits;
    try {
        hits = Searcher(*idx).search(query, k);
    } catch (const query::QueryError& e) {
        std::cerr << json{{"error", e.what()}, {"column", e.column()}}.dump() << "\n";
        return 3;
    }
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


int cmdParse(const std::string& q) {
    try {
        auto root = query::parse(q);
        std::cout << (root ? query::toString(*root) : std::string("<empty>")) << "\n";
        return 0;
    } catch (const query::QueryError& e) {
        std::cout << "error: " << e.what() << "\n";
        return 3;
    }
}

std::vector<std::string> readLines(const std::string& path) {
    std::ifstream in(path);
    if (!in) throw std::runtime_error("cannot read " + path);
    std::vector<std::string> out;
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (line.empty() || line[0] == '#') continue;
        out.push_back(line);
    }
    return out;
}

bool sameResults(const std::vector<Hit>& a, const std::vector<Hit>& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (a[i].doc != b[i].doc || a[i].score != b[i].score) return false;
    }
    return true;
}

// Planned vs naive on random queries built from the real corpus: words weighted by frequency,
// phrases taken from real consecutive words, and the corpus's own module codes.
int cmdEquiv(const std::string& chunksPath, const std::string& indexPath, int n, uint32_t seed) {
    auto idx = openOrDie(indexPath);
    if (!idx) return 1;
    std::ifstream in(chunksPath);
    const json doc = json::parse(in);
    query::QueryVocab vocab;
    std::set<std::string> modules;
    std::mt19937 rng(seed);
    for (const auto& c : doc.at("chunks")) {
        modules.insert(c.at("module").get<std::string>());
        auto toks = tokenize(c.at("text").get<std::string>());
        for (size_t i = 0; i < toks.size(); i += 7) vocab.words.push_back(toks[i].term);  // sample, keeps frequency weighting
        if (toks.size() >= 3 && rng() % 3 == 0) {
            const size_t at = rng() % (toks.size() - 2);
            const size_t len = 2 + rng() % 2;
            std::vector<std::string> p;
            for (size_t j = 0; j < len; ++j) p.push_back(toks[at + j].term);
            vocab.phrases.push_back(std::move(p));
        }
    }
    vocab.modules.assign(modules.begin(), modules.end());
    vocab.words.push_back("zzzabsent");
    query::RandomQueryGenerator gen(vocab, seed);

    std::set<std::string> seen;
    int compared = 0, nonEmpty = 0, mismatches = 0;
    for (int attempts = 0; compared < n && attempts < n * 4; ++attempts) {
        const std::string q = gen.next();
        if (!seen.insert(q).second) continue;
        auto root = query::parse(q);
        const auto planned = query::executePlanned(*idx, *root, query::kAllResults);
        const auto naive = query::executeNaive(*idx, *root, query::kAllResults);
        if (!sameResults(planned, naive)) {
            ++mismatches;
            std::cerr << "MISMATCH: " << q << "\n";
        }
        ++compared;
        nonEmpty += !planned.empty();
    }
    std::cout << json{{"queries", compared}, {"non_empty", nonEmpty}, {"mismatches", mismatches}, {"seed", seed}}.dump() << "\n";
    return mismatches == 0 ? 0 : 1;
}

std::string queryKind(const std::string& q) {
    const bool filter = q.find("module:") != std::string::npos || q.find("week:") != std::string::npos ||
                        q.find("type:") != std::string::npos;
    const bool boolean = q.find(" AND ") != std::string::npos || q.find(" OR ") != std::string::npos ||
                         q.find("NOT ") != std::string::npos || q.find('(') != std::string::npos;
    if (filter) return "filter";
    if (boolean) return "boolean";
    if (q.find('"') != std::string::npos) return "phrase";
    return "plain";
}

double medianUs(std::vector<double>& xs) {
    std::sort(xs.begin(), xs.end());
    return xs[xs.size() / 2];
}

// Times planned vs naive top-10 for each query. Results are checked identical first, so the
// comparison is between two implementations of the same answer.
int cmdBench(const std::string& indexPath, const std::string& queriesPath, int runs, const std::string& csvPath) {
    auto idx = openOrDie(indexPath);
    if (!idx) return 1;
    const auto queries = readLines(queriesPath);
    std::ofstream csv;
    if (!csvPath.empty()) {
        csv.open(csvPath);
        csv << "query,kind,results,planned_us,naive_us\n";
    }
    using clock = std::chrono::steady_clock;
    auto timeOnce = [&](auto&& fn) {
        const auto t0 = clock::now();
        auto r = fn();
        const auto t1 = clock::now();
        volatile size_t sink = r.size();  // keep the call from being optimised away
        (void)sink;
        return std::chrono::duration<double, std::micro>(t1 - t0).count();
    };
    int errors = 0, mismatches = 0, timed = 0;
    for (const auto& q : queries) {
        std::unique_ptr<query::Node> root;
        try {
            root = query::parse(q);
        } catch (const query::QueryError& e) {
            std::cerr << "skipping (syntax error: " << e.what() << "): " << q << "\n";
            ++errors;
            continue;
        }
        if (!root) continue;
        const auto all = query::executePlanned(*idx, *root, query::kAllResults);
        if (!sameResults(all, query::executeNaive(*idx, *root, query::kAllResults))) {
            std::cerr << "MISMATCH: " << q << "\n";
            ++mismatches;
            continue;
        }
        auto planned = [&] { return query::executePlanned(*idx, *root, 10); };
        auto naive = [&] { return query::executeNaive(*idx, *root, 10); };
        for (int w = 0; w < 3; ++w) {
            timeOnce(planned);
            timeOnce(naive);
        }
        std::vector<double> tp, tn;
        for (int r = 0; r < runs; ++r) {  // alternate so drift affects both equally
            tp.push_back(timeOnce(planned));
            tn.push_back(timeOnce(naive));
        }
        const double mp = medianUs(tp), mn = medianUs(tn);
        ++timed;
        if (csv) {
            std::string quoted = "\"";
            for (char ch : q) quoted += (ch == '"' ? std::string("\"\"") : std::string(1, ch));
            csv << quoted << "\"," << queryKind(q) << "," << all.size() << "," << mp << "," << mn << "\n";
        }
    }
    std::cout << json{{"queries", timed}, {"syntax_errors", errors}, {"mismatches", mismatches}, {"runs", runs}}.dump() << "\n";
    return mismatches == 0 ? 0 : 1;
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
        if (argc >= 3 && std::strcmp(argv[1], "parse") == 0) return cmdParse(argv[2]);
        auto flag = [&](const char* name, const char* fallback) -> std::string {
            for (int i = 1; i + 1 < argc; ++i) if (std::strcmp(argv[i], name) == 0) return argv[i + 1];
            return fallback;
        };
        if (argc >= 4 && std::strcmp(argv[1], "equiv") == 0) {
            return cmdEquiv(argv[2], argv[3], std::stoi(flag("--random", "500")), static_cast<uint32_t>(std::stoul(flag("--seed", "1"))));
        }
        if (argc >= 4 && std::strcmp(argv[1], "bench") == 0) {
            return cmdBench(argv[2], argv[3], std::stoi(flag("--runs", "50")), flag("--csv", ""));
        }
    } catch (const std::exception& e) {
        std::fprintf(stderr, "error: %s\n", e.what());
        return 1;
    }
    return usage();
}

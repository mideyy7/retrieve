#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace retrieve {

// One searchable chunk as handed over by the app (or by retrieve_cli from chunks.json).
struct DocInput {
    std::string stableKey;
    std::string module;
    uint16_t week = 0;
    uint8_t type = 0;
    int32_t page = -1;
    int64_t startMs = -1;
    std::string text;
};

// Builds an index file from chunks. Doc ids are assigned in stableKey order, not insertion order,
// so the same set of chunks always produces the same file, whether it comes from the app's
// database or from chunks.json. That is what makes on-device results comparable with the CLI.
class IndexBuilder {
public:
    void add(DocInput doc) { docs_.push_back(std::move(doc)); }
    size_t size() const { return docs_.size(); }

    // Writes the index atomically: to a temporary file in the same directory, fsync, then rename
    // over `path`. A reader therefore sees either the old index or the new one, never a partial
    // file. Returns false and sets `error` on failure (duplicate stable keys, I/O errors).
    bool writeTo(const std::string& path, std::string* error) const;

private:
    std::vector<DocInput> docs_;
};

}  // namespace retrieve

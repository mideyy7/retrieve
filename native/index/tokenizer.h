#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace retrieve {

struct Token {
    std::string term;
    uint32_t position;  // index of the token in the text, used for phrase matching
};

// Splits UTF-8 text into lowercase terms. The same function is used for documents and queries,
// so whatever it does to one it does to the other.
//
// Rules:
//  - A term is a maximal run of "word" code points: ASCII letters and digits, plus any non-ASCII
//    code point that is not punctuation or a symbol (so "naïve" or "Gödel" stay one term).
//  - ASCII is lowercased. Non-ASCII letters are kept as they are (lecture text is English, and
//    full Unicode case folding would need ICU, which is too big for this app).
//  - Separators include all ASCII punctuation and the Unicode punctuation and symbol blocks that
//    slides actually use: curly quotes, dashes, bullets, arrows and logic/math operators
//    (¬ ∧ ∨ → ↔ ≤). Without this, "Moore’s" (curly apostrophe) would never match "Moore's".
//  - A possessive "'s" or "’s" directly after a word is dropped: "Moore's" -> "moore". Otherwise
//    a stray "s" term would match every possessive ("Ohm’s law" for a query about Moore's law).
//  - No stemming and no stopword removal. Exact terms keep phrase queries and BM25 predictable;
//    BM25's IDF already makes common words cheap, and semantic search (Phase 5) handles
//    morphological variants.
//  - Terms longer than kMaxTermBytes are dropped (they are base64 blobs or extraction garbage),
//    but still consume a position so they can't make two words look adjacent.
//  - Malformed UTF-8 bytes are treated as separators.
inline constexpr size_t kMaxTermBytes = 64;

std::vector<Token> tokenize(std::string_view text);

}  // namespace retrieve

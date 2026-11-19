#include "index/tokenizer.h"

namespace retrieve {
namespace {

// Decodes one UTF-8 code point starting at text[i]. Returns its length in bytes (>= 1) and
// writes the code point, or 0xFFFFFFFF for a malformed sequence (which is then skipped as one byte).
size_t decode(std::string_view text, size_t i, uint32_t& cp) {
    const auto b0 = static_cast<unsigned char>(text[i]);
    auto cont = [&](size_t k) -> int {
        if (i + k >= text.size()) return -1;
        const auto b = static_cast<unsigned char>(text[i + k]);
        return (b & 0xC0) == 0x80 ? (b & 0x3F) : -1;
    };
    if (b0 < 0x80) {
        cp = b0;
        return 1;
    }
    if ((b0 & 0xE0) == 0xC0 && b0 >= 0xC2) {
        int c1 = cont(1);
        if (c1 >= 0) {
            cp = ((b0 & 0x1Fu) << 6) | static_cast<uint32_t>(c1);
            return 2;
        }
    } else if ((b0 & 0xF0) == 0xE0) {
        int c1 = cont(1), c2 = cont(2);
        if (c1 >= 0 && c2 >= 0) {
            cp = ((b0 & 0x0Fu) << 12) | (static_cast<uint32_t>(c1) << 6) | static_cast<uint32_t>(c2);
            if (cp >= 0x800 && (cp < 0xD800 || cp > 0xDFFF)) return 3;
        }
    } else if ((b0 & 0xF8) == 0xF0 && b0 <= 0xF4) {
        int c1 = cont(1), c2 = cont(2), c3 = cont(3);
        if (c1 >= 0 && c2 >= 0 && c3 >= 0) {
            cp = ((b0 & 0x07u) << 18) | (static_cast<uint32_t>(c1) << 12) | (static_cast<uint32_t>(c2) << 6) |
                 static_cast<uint32_t>(c3);
            if (cp >= 0x10000 && cp <= 0x10FFFF) return 4;
        }
    }
    cp = 0xFFFFFFFF;
    return 1;
}

bool isWordCodePoint(uint32_t cp) {
    if (cp < 0x80) return (cp >= '0' && cp <= '9') || (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z');
    if (cp == 0xFFFFFFFF) return false;
    if (cp <= 0xBF) return false;                    // Latin-1 punctuation and symbols (incl. ¬ « » ·)
    if (cp == 0xD7 || cp == 0xF7) return false;      // × ÷
    if (cp >= 0x2000 && cp <= 0x206F) return false;  // general punctuation: ‘ ’ “ ” – — • …
    if (cp >= 0x2190 && cp <= 0x22FF) return false;  // arrows and mathematical operators: → ↔ ∧ ∨ ≤
    if (cp >= 0x2500 && cp <= 0x25FF) return false;  // box drawing and geometric shapes: ▪ ■ ►
    if (cp >= 0xE000 && cp <= 0xF8FF) return false;  // private use area (icon fonts in slides)
    if (cp == 0xFEFF) return false;                  // byte order mark
    return true;
}

}  // namespace

std::vector<Token> tokenize(std::string_view text) {
    std::vector<Token> tokens;
    std::string current;
    uint32_t position = 0;

    auto flush = [&] {
        if (current.empty()) return;
        if (current.size() <= kMaxTermBytes) tokens.push_back({std::move(current), position});
        ++position;  // dropped over-long terms still occupy a position
        current.clear();
    };

    for (size_t i = 0; i < text.size();) {
        uint32_t cp;
        const size_t len = decode(text, i, cp);
        if (isWordCodePoint(cp)) {
            if (cp < 0x80) {
                current.push_back(static_cast<char>(cp >= 'A' && cp <= 'Z' ? cp + ('a' - 'A') : cp));
            } else {
                current.append(text.substr(i, len));
            }
        } else {
            flush();
        }
        i += len;
    }
    flush();
    return tokens;
}

}  // namespace retrieve

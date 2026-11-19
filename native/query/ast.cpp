#include "query/ast.h"

#include <unordered_set>

namespace retrieve::query {

bool hasPositiveTerms(const Node& n) {
    switch (n.kind) {
        case NodeKind::Terms: return !n.terms.empty();
        case NodeKind::Filter: return false;
        case NodeKind::And:
            for (const auto& c : n.required) if (hasPositiveTerms(*c)) return true;
            for (const auto& c : n.should) if (hasPositiveTerms(*c)) return true;
            return false;
        case NodeKind::Or:
            for (const auto& c : n.children) if (hasPositiveTerms(*c)) return true;
            return false;
    }
    return false;
}

bool shouldIsMandatory(const Node& n) {
    if (n.should.empty()) return false;
    for (const auto& r : n.required) if (hasPositiveTerms(*r)) return false;
    return true;
}

namespace {

std::string filterString(const Filter& f) {
    switch (f.key) {
        case Filter::Key::Module: return "module:" + f.module;
        case Filter::Key::Week:
            return f.weekLo == f.weekHi ? "week:" + std::to_string(f.weekLo)
                                        : "week:" + std::to_string(f.weekLo) + ".." + std::to_string(f.weekHi);
        case Filter::Key::Type: {
            static const char* names[] = {"slides", "pdf", "audio"};
            return std::string("type:") + (f.type < 3 ? names[f.type] : "?");
        }
    }
    return "?";
}

void list(std::string& out, const char* label, const std::vector<std::unique_ptr<Node>>& nodes) {
    if (nodes.empty()) return;
    out += ' ';
    out += label;
    out += ":[";
    for (size_t i = 0; i < nodes.size(); ++i) {
        if (i) out += ' ';
        out += toString(*nodes[i]);
    }
    out += ']';
}

void collect(const Node& n, std::vector<std::string>& out, std::unordered_set<std::string>& seen) {
    switch (n.kind) {
        case NodeKind::Terms:
            for (const auto& t : n.terms) if (seen.insert(t).second) out.push_back(t);
            break;
        case NodeKind::Filter: break;
        case NodeKind::And:
            for (const auto& c : n.required) collect(*c, out, seen);
            for (const auto& c : n.should) collect(*c, out, seen);
            break;  // mustNot subtrees never contribute to scores
        case NodeKind::Or:
            for (const auto& c : n.children) collect(*c, out, seen);
            break;
    }
}

}  // namespace

std::string toString(const Node& n) {
    switch (n.kind) {
        case NodeKind::Terms: {
            if (n.terms.size() == 1 && !n.quoted) return n.terms[0];
            std::string s = "\"";
            for (size_t i = 0; i < n.terms.size(); ++i) s += (i ? " " : "") + n.terms[i];
            return s + "\"";
        }
        case NodeKind::Filter: return filterString(n.filter);
        case NodeKind::And: {
            std::string s = "(and";
            list(s, "req", n.required);
            list(s, "should", n.should);
            list(s, "not", n.mustNot);
            return s + ")";
        }
        case NodeKind::Or: {
            std::string s = "(or";
            for (const auto& c : n.children) s += " " + toString(*c);
            return s + ")";
        }
    }
    return "?";
}

std::vector<std::string> scoringTerms(const Node& node) {
    std::vector<std::string> out;
    std::unordered_set<std::string> seen;
    collect(node, out, seen);
    return out;
}

}  // namespace retrieve::query

// The two Boost.StringAlgorithms calls camodocal makes: a case-insensitive compare.
#pragma once
#include <algorithm>
#include <cctype>
#include <string>

namespace boost {

inline bool iequals(const std::string &a, const std::string &b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); i++) {
        const unsigned char ca = static_cast<unsigned char>(a[i]);
        const unsigned char cb = static_cast<unsigned char>(b[i]);
        if (std::tolower(ca) != std::tolower(cb)) return false;
    }
    return true;
}

}  // namespace boost

// Only camodocal's Scaramuzza (OCAM) camera needs this; phones get a pinhole or fisheye model, but
// the file is part of camera_model and stays in the build, so the cast is provided.
#pragma once
#include <sstream>
#include <stdexcept>
#include <string>

namespace boost {

template <typename Target>
inline Target lexical_cast(const std::string &text) {
    std::istringstream in(text);
    Target value;
    if (!(in >> value)) throw std::bad_cast();
    return value;
}

}  // namespace boost

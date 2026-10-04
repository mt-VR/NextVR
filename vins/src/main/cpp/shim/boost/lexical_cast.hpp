// Only camodocal's Scaramuzza (OCAM) camera needs this; phones get a pinhole or fisheye model, but
// the file is part of camera_model and stays in the build, so the cast is provided.
//
// boost::lexical_cast<T>(u) is "print u, read a T" with an exception when the text does not fit,
// which is what both directions in camodocal amount to: reading a parameter as a double, and
// building the name of a parameter out of an index.
#pragma once
#include <sstream>
#include <stdexcept>
#include <string>

namespace boost {

template <typename Target, typename Source>
inline Target lexical_cast(const Source &source) {
    std::ostringstream text;
    text << source;
    std::istringstream in(text.str());
    Target value;
    if (!(in >> value)) throw std::bad_cast();
    return value;
}

template <typename Target>
inline Target lexical_cast(const std::string &text) {
    std::istringstream in(text);
    Target value;
    if (!(in >> value)) throw std::bad_cast();
    return value;
}

}  // namespace boost

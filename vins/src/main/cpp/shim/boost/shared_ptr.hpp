// camodocal's headers say boost::shared_ptr; std::shared_ptr is the same object these days, and
// pulling all of Boost into an Android APK for a typedef is not worth 108 MB of headers.
#pragma once
#include <memory>

namespace boost {
using std::shared_ptr;
using std::weak_ptr;
inline void throw_exception(std::exception const &e) { throw e; }
}  // namespace boost

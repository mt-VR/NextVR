// The one glibc-ism the vendored sources use that Android's C library does not have.
//
// Upstream's Utility::createDirectoryIfNotExists() takes dirname() of a path, which modifies its
// argument, so it copies the path first with strdupa() — strdup on the stack, no allocation to free.
// bionic has strdup but not strdupa, and the upstream header is not edited for it, so the macro is
// handed to every translation unit of this module with -include (see ../CMakeLists.txt).
#pragma once

#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#ifndef strdupa
#define strdupa(s)                                                              \
    (__extension__({                                                            \
        size_t vins_strdupa_size = strlen(s) + 1;                               \
        char *vins_strdupa_copy = (char *)alloca(vins_strdupa_size);           \
        memcpy(vins_strdupa_copy, (s), vins_strdupa_size);                      \
        vins_strdupa_copy;                                                      \
    }))
#endif

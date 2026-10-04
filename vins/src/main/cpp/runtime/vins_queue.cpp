#include "runtime/vins_queue.h"

namespace vins {

Buffers &buffers() {
    static Buffers instance;
    return instance;
}

}  // namespace vins

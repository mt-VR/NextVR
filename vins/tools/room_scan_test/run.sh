#!/usr/bin/env bash
# Builds and runs the room scan's geometry test on the host.
#
# runtime/vins_room.cpp is the only file in this module with no Ceres, OpenCV or ROS in it, which is
# what makes this possible: the floor fit and the room signature can be checked here, on a machine
# with no phone attached, instead of only on the headset.
#
# Eigen is needed, and nothing else. It is found in this order:
#   1. $EIGEN_INCLUDE_DIR, if you have one
#   2. the copy the Android build already fetched into vins/.cxx/.../_deps/eigen-src
#   3. whatever the compiler finds on its own
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
vins="$(cd "$here/../.." && pwd)"

eigen="${EIGEN_INCLUDE_DIR:-}"
if [ -z "$eigen" ]; then
    # The Gradle/CMake fetch checked this into .cxx, but the tree it lands in is fingerprinted per
    # variant/build/ABI, so its depth under .cxx is not fixed - find it rather than guess the path,
    # and require the licence file so we know it really is an Eigen source tree.
    while IFS= read -r candidate; do
        if [ -f "$candidate/COPYING.GPL" ] || [ -f "$candidate/COPYing.gPL" ]; then
            eigen="$candidate"
            break
        fi
    done < <(find "$vins/.cxx" -type d -path "*/_deps/eigen-src" 2>/dev/null)
fi

out="${TMPDIR:-/tmp}/room_scan_test"
mkdir -p "$(dirname "$out")"

args=(-std=c++14 -O2 -Wall -I "$vins/src/main/cpp" -I "$vins/src/main/cpp/shim")
if [ -n "$eigen" ]; then
    echo "Eigen from $eigen"
    args+=(-I "$eigen")
else
    echo "Eigen: using the compiler's default include path"
fi

cxx="${CXX:-g++}"
"$cxx" "${args[@]}" "$here/room_scan_test.cpp" "$vins/src/main/cpp/runtime/vins_room.cpp" -o "$out"
"$out"

#!/usr/bin/env bash
# Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT
# Cross-compile the owned native terminal library for every supported target and reject
# platform-specific APIs in the portable sources. Build products stay under target/.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v zig >/dev/null 2>&1; then
    echo 'verify_portability: zig is required (G-5)' >&2
    exit 1
fi

readonly SOURCE='src/main/native/terminal.c'
readonly CACHE='target/portability'
mkdir -p "$CACHE"

compile_target() {
    local target=$1 extension=$2 link_mode=$3
    echo "verify_portability: compiling $target"
    zig cc -target "$target" -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -fPIC -pthread \
        "$link_mode" "$SOURCE" -o "$CACHE/libbraxton-$target.$extension"
}

compile_target x86_64-linux-gnu so -shared
compile_target aarch64-linux-gnu so -shared
compile_target aarch64-macos dylib -dynamiclib
compile_target x86_64-macos dylib -dynamiclib

if grep -rnE '/proc/|epoll|inotify|__linux__|signalfd|eventfd|TIOCSTI' \
        src/main/java/io src/main/native/terminal.c; then
    echo 'verify_portability: Linux-only API found in portable sources' >&2
    exit 1
fi

echo 'verify_portability: all targets compiled; no forbidden platform APIs found'

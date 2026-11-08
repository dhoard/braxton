#!/usr/bin/env sh
# Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT
set -eu
source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
output_dir=${1:?usage: build.sh output-directory version}
version=${2:?usage: build.sh output-directory version}
case $(uname -s) in
    Linux) platform=linux; extension=so; shared=-shared ;;
    Darwin) platform=macos; extension=dylib; shared=-dynamiclib ;;
    *) echo 'Braxton native builds require Linux or macOS' >&2; exit 1 ;;
esac
case $(uname -m) in
    x86_64|amd64) arch=x86_64 ;;
    arm64|aarch64) arch=aarch64 ;;
    *) echo 'Braxton native builds require x86_64 or ARM64' >&2; exit 1 ;;
esac
if [ "$platform" = macos ] && [ "$arch" != aarch64 ]; then
    echo 'Braxton supports macOS ARM64 only' >&2; exit 1
fi
destination="$output_dir/META-INF/braxton/native/$platform-$arch"
mkdir -p "$destination"
"${CC:-cc}" -std=c11 -O2 -Wall -Wextra -Werror -Wpedantic -fPIC "$shared" \
    "$source_dir/terminal.c" -o "$destination/libbraxton.$extension"

printf '%s\n' "$version" > "$destination/version.txt"

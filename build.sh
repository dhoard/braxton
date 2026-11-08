#!/usr/bin/env bash
# Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT
# Maven builds the host native terminal library during generate-resources.
set -euo pipefail
cd "$(dirname "$0")"
if [[ $# -eq 0 ]]; then
    set -- clean install
fi
exec ./mvnw -B -ntp "$@"

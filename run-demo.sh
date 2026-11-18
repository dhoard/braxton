#!/usr/bin/env bash
#
# Build the packaged Braxton jar and run the integrated parity showcase:
#   examples.parity.Showcase
#
# The showcase is the phase-24 acceptance entry point: one catalog over every completed parity
# phase, plus the composed task workspace. Each entry mounts that phase's own demo component.
#
# Usage:
#   ./run-demo.sh                    # ./mvnw clean package, then start the showcase
#   ./run-demo.sh --skip-build       # skip packaging and start the existing jar
#
# Keys: arrow keys move, enter opens a scene, ctrl+c returns to the catalog, q quits.
#
# The runtime uses Braxton's bundled POSIX native library through FFM. The markdown scene renders
# through the CommonMark parser, which the jar does not shade, so the library's own runtime
# dependencies are copied into target/showcase-lib and put beside the jar.

set -euo pipefail

cd "$(dirname "$0")"

build=1
if [ "${1:-}" = "--skip-build" ]; then
    build=0
    shift
fi

if [ "$build" -eq 1 ]; then
    ./mvnw -B -q clean package
fi

# Use the packaged artifact rather than target/classes. Exclude auxiliary jars
# if the build is later configured to produce sources or javadoc archives.
jar=$(ls -1 target/braxton-*.jar 2>/dev/null \
    | grep -Ev -- '-(sources|javadoc)\.jar$' \
    | head -n 1 || true)
if [ -z "${jar}" ]; then
    echo "run-demo.sh: no packaged jar in target/; run ./mvnw clean package" >&2
    exit 1
fi

./mvnw -B -q dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory=target/showcase-lib

exec java --enable-native-access=ALL-UNNAMED \
    -cp "${jar}:target/showcase-lib/*" examples.parity.Showcase "$@"

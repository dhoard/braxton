#!/usr/bin/env bash
#
# Build the packaged Braxton jar and run the AI Harness:
#   examples.harness.ai.AiHarness
#
# Usage:
#   ./run-ai-harness.sh            # ./mvnw clean package, then start the harness
#   ./run-ai-harness.sh --no-build # skip packaging and start the existing jar
#
# The runtime uses JLine 4.4.8 FFM. The harness additionally uses optional
# Gson and its annotation dependency; all runtime jars are copied into
# target/showcase-lib so the packaged Braxton jar still contains no
# third-party code.

set -euo pipefail

cd "$(dirname "$0")"

build=1
if [ "${1:-}" = "--no-build" ]; then
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
    echo "run-ai-harness.sh: no packaged jar in target/; run ./mvnw clean package" >&2
    exit 1
fi

./mvnw -B -q dependency:copy-dependencies \
    -DincludeScope=runtime \
    -DoutputDirectory=target/showcase-lib

exec java --enable-native-access=ALL-UNNAMED \
    -cp "${jar}:target/showcase-lib/*" examples.harness.ai.AiHarness "$@"

#!/usr/bin/env bash
#
# Braxton build entry point and quality gate.
#
# Installs the vendored, patched JLine artifacts that Braxton depends on, then
# runs the Maven wrapper with the arguments you pass through. With no arguments
# it runs the quality gate, "clean install".
#
#   ./build.sh                             # quality gate: clean install
#   ./build.sh clean install -DskipTests   # Maven goals and -D properties
#   ./build.sh clean                       # clean Braxton and vendored JLine output
#   ./build.sh install                     # rebuild Braxton, reuse current JLine
#   ./build.sh package                     # forward "package" to Maven
#   ./build.sh -DskipTests                 # no goal given: clean install
#   ./build.sh --help
#
# "clean" has the same reach as ./mvnw clean plus the vendored JLine tree: it
# deletes target/ for Braxton and for vendor/jline3, so the next build compiles
# both from scratch. Installed artifacts stay in the local repository, exactly as
# ./mvnw clean leaves Braxton's installed artifact alone.
#
# Vendored JLine version contract: the Braxton version in pom.xml (`${revision}`,
# for example 0.2.0-SNAPSHOT) builds JLine at its numeric core plus
# "-jline-braxton" (0.2.0-jline-braxton), and Braxton resolves exactly that
# version. The script refuses to build when pom.xml `<jline.version>` or
# vendor/jline3/.mvn/maven.config disagree with that derivation, so the vendored
# JLine version cannot drift from the Braxton version.
#
# Requires bash and JDK 25+; Unix only. See README.md for the Windows build.

set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly project_dir
readonly jline_dir="vendor/jline3"
readonly jline_qualifier="jline-braxton"
# Modules Braxton resolves plus the reactor modules they need (-pl ... -am).
readonly jline_modules=(native terminal terminal-ffm)

die() {
    printf 'build.sh: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage: ./build.sh [--help] [Maven arguments...]

Installs the vendored JLine artifacts at the version derived from the Braxton
version, then runs ./mvnw with the given arguments. When no Maven goal is
given, the quality gate goals "clean install" are used.

Maven goals and options are forwarded unchanged, so -D properties, profiles and
plugin goals all work:

  ./build.sh                              # clean install (quality gate)
  ./build.sh verify -DskipTests
  ./build.sh clean                        # clean Braxton and vendor/jline3 output
  ./build.sh install                      # reuse the installed vendored JLine
  ./build.sh spotless:apply
  ./build.sh clean install -Dmaven.repo.local=/tmp/repo

-Dmaven.repo.local, -DskipTests, -Dmaven.test.skip and -s/--settings are also
applied to the vendored JLine build.

The vendored JLine build is skipped when the artifacts for the derived version
are installed and newer than the vendored sources. A clean goal removes
vendor/jline3 target directories as well, so the next build rebuilds them.

Exit status is Maven's exit status; 1 means a build.sh precondition failed.
This script needs a POSIX shell environment; on Windows use Maven directly.
EOF
}

# Implementation version of a Braxton/Maven version: the numeric core.
base_version_of() {
    local version=${1%%-*}
    [[ $version =~ ^[0-9]+(\.[0-9]+)*$ ]] || return 1
    printf '%s\n' "$version"
}

# Reads the first "<name>value</name>" element from a pom.xml.
xml_value() {
    sed -n "s:.*<$2>\([^<]*\)</$2>.*:\1:p" "$1" | head -n 1
}

# Local repository Maven will use for both builds, so the installed JLine
# artifacts can be checked. An unrecognized repository layout only ever causes
# an unnecessary rebuild, never a missing one.
local_repository() {
    local arg i override="" settings_file=""
    for ((i = 0; i < ${#maven_args[@]}; i++)); do
        arg=${maven_args[i]}
        case $arg in
            -Dmaven.repo.local=*) override=${arg#-Dmaven.repo.local=} ;;
            -s | --settings) settings_file=${maven_args[i + 1]:-} ;;
        esac
    done
    if [[ -n $override ]]; then
        printf '%s\n' "$override"
        return
    fi
    settings_file=${settings_file:-${MAVEN_USER_HOME:-$HOME/.m2}/settings.xml}
    local configured=""
    if [[ -f $settings_file ]]; then
        configured=$(sed -n 's:.*<localRepository>\([^<]*\)</localRepository>.*:\1:p' "$settings_file" | head -n 1)
    fi
    printf '%s\n' "${configured:-${MAVEN_USER_HOME:-$HOME/.m2}/repository}"
}

# Removes Braxton's and the vendored JLine build output, like ./mvnw clean but
# covering vendor/jline3 as well. Installed artifacts are left in the local
# repository, matching ./mvnw clean's treatment of Braxton's installed artifact.
clean_vendored_jline() {
    printf 'build.sh: cleaning vendored JLine build output\n'
    "$project_dir/mvnw" -B -ntp \
        -f "$jline_dir/pom.xml" \
        -Dnisse.jgit.dynamicVersion="$jline_version" \
        clean ||
        die "vendored JLine clean failed"
}

# Builds and installs the vendored JLine modules unless the derived version is
# already installed from the current vendored sources.
install_vendored_jline() {
    local repository artifact jar reference requires_build=0 artifacts=(
        "org/jline/jline-native/$jline_version/jline-native-$jline_version.jar"
        "org/jline/jline-terminal/$jline_version/jline-terminal-$jline_version.jar"
        "org/jline/jline-terminal-ffm/$jline_version/jline-terminal-ffm-$jline_version.jar"
    )

    repository=$(local_repository)
    for artifact in "${artifacts[@]}"; do
        [[ -f $repository/$artifact ]] || requires_build=1
    done

    # The vendored reactor output is the reference for source staleness, so a
    # clean that removed vendor/jline3 target directories also forces a rebuild.
    reference="$jline_dir/terminal-ffm/target/jline-terminal-ffm-$jline_version.jar"
    [[ -f $reference ]] || requires_build=1

    if ((requires_build == 0)); then
        if [[ -n $(find "${jline_modules[@]/#/$jline_dir/}" "$jline_dir/pom.xml" \
            -type f \( -name '*.java' -o -name 'pom.xml' \) -newer "$reference" -print -quit 2>/dev/null) ]]; then
            requires_build=1
        fi
    fi

    if ((requires_build == 0)); then
        printf 'build.sh: vendored JLine %s is up to date\n' "$jline_version"
        return
    fi

    printf 'build.sh: building vendored JLine %s\n' "$jline_version"
    # JLine's suite reads TERM/COLORTERM from the environment (max_colors), so
    # pin them to keep the vendored build independent of the developer's
    # terminal; COLORTERM=truecolor otherwise fails ColorPaletteTest.
    env -u COLORTERM -u TERM_PROGRAM TERM=xterm-256color \
        "$project_dir/mvnw" -B -ntp \
        -f "$jline_dir/pom.xml" \
        -pl terminal-ffm -am \
        -Dnisse.jgit.dynamicVersion="$jline_version" \
        -Djava.awt.headless=true \
        ${vendored_args[@]+"${vendored_args[@]}"} \
        clean install ||
        die "vendored JLine build failed"

    for module in "${jline_modules[@]}"; do
        jar="$jline_dir/$module/target/jline-$module-$jline_version.jar"
        [[ -f $jar ]] || die "vendored JLine build did not produce $jar"
    done
}

declare -a maven_args=() vendored_args=()
has_goal=0
clean_requested=0
needs_jline=0
expect_value=0
pending_option=""

# Separate Maven arguments from the ones the vendored JLine build also needs.
for arg in "$@"; do
    if ((expect_value)); then
        maven_args+=("$arg")
        case $pending_option in
            -s | --settings) vendored_args+=("$arg") ;;
        esac
        expect_value=0
        continue
    fi
    case $arg in
        -h | --help)
            usage
            exit 0
            ;;
        # Options whose value is the next argument; never a Maven goal.
        -pl | --projects | -P | --activate-profiles | -f | --file | -rf | --resume-from | -T | --threads | \
            -s | --settings | -gs | --global-settings | -t | --toolchains | -gt | --global-toolchains | \
            -l | --log-file | -b | --builder)
            maven_args+=("$arg")
            expect_value=1
            pending_option=$arg
            ;;
        -Dmaven.repo.local=* | -DskipTests* | -Dmaven.test.skip*)
            maven_args+=("$arg")
            vendored_args+=("$arg")
            ;;
        -*)
            maven_args+=("$arg")
            ;;
        *)
            maven_args+=("$arg")
            has_goal=1
            # clean alone does not need the vendored JLine artifacts.
            case $arg in
                clean | clean:clean) clean_requested=1 ;;
                *) needs_jline=1 ;;
            esac
            ;;
    esac
done

if ((expect_value)); then
    die "option $pending_option requires a value"
fi

cd "$project_dir"

[[ -f pom.xml ]] || die "pom.xml is missing from $project_dir"
[[ -x ./mvnw ]] || die "./mvnw is missing or not executable"
[[ -d $jline_dir ]] || die "$jline_dir is missing; restore the vendored JLine tree described in README.md"
[[ -f $jline_dir/pom.xml ]] || die "$jline_dir/pom.xml is missing; restore the vendored JLine tree described in README.md"

revision=$(xml_value pom.xml revision)
[[ -n $revision ]] || die 'pom.xml has no <revision> property; cannot derive the vendored JLine version'
base_version=$(base_version_of "$revision") ||
    die "pom.xml <revision> '$revision' does not start with a numeric version; cannot derive the vendored JLine version"
jline_version="$base_version-$jline_qualifier"

pom_jline_version=$(xml_value pom.xml jline.version)
[[ $pom_jline_version == "$jline_version" ]] ||
    die "Braxton version '$revision' requires vendored JLine '$jline_version', but pom.xml pins <jline.version>$pom_jline_version</jline.version>; update <jline.version> in pom.xml and -Dnisse.jgit.dynamicVersion in $jline_dir/.mvn/maven.config"

[[ -f $jline_dir/.mvn/maven.config ]] || die "$jline_dir/.mvn/maven.config is missing; it must pin -Dnisse.jgit.dynamicVersion=$jline_version"
vendored_pin=$(sed -n 's:^-Dnisse\.jgit\.dynamicVersion=::p' "$jline_dir/.mvn/maven.config" | tail -n 1)
[[ $vendored_pin == "$jline_version" ]] ||
    die "$jline_dir/.mvn/maven.config pins '$vendored_pin', but Braxton version '$revision' requires vendored JLine '$jline_version'; update it"

if ((has_goal == 0)); then
    maven_args+=(clean install)
    clean_requested=1
    needs_jline=1
fi

if ((clean_requested)); then
    clean_vendored_jline
fi

if ((needs_jline)); then
    install_vendored_jline
fi

printf 'build.sh: ./mvnw -B -ntp %s\n' "${maven_args[*]}"
exec ./mvnw -B -ntp "${maven_args[@]}"

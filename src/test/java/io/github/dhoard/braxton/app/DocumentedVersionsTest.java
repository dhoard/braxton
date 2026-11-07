/*
 * Copyright (c) 2026-present Doug Hoard
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.dhoard.braxton.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The version identifiers the README states are a published build contract, so they are pinned here
 * to the version the build actually derives.
 *
 * <p>{@code build.sh} checks that {@code pom.xml} {@code <jline.version>} and the vendored
 * {@code -Dnisse.jgit.dynamicVersion} pin agree with the version derived from
 * {@code pom.xml <revision>}. It cannot check the prose, and the README is where a contributor
 * learns which locally installed JLine artifacts {@code ./mvnw} needs and where a consumer reads
 * which release this repository ships. This test reads the same three sources the gate reads -
 * {@code pom.xml} and {@code README.md} - and fails when the documented identifiers drift from the
 * derived one, the way {@link DocumentedDefaultsTest} fails when a documented default drifts from
 * the shipped constant.</p>
 */
class DocumentedVersionsTest {

    /** A version of the locally built JLine as the prose names it: {@code 0.1.0-jline-braxton}. */
    private static final Pattern DOCUMENTED_JLINE_VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+)+-jline-braxton");

    /**
     * A release version in prose. Three components are required so the sentence "releases follow
     * semantic versioning once 1.0 is reached" is prose about the major line, not a claim about
     * this release.
     */
    private static final Pattern DOCUMENTED_RELEASE_VERSION =
            Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.]+)?");

    private static final Pattern POM_REVISION = Pattern.compile("<revision>([^<]*)</revision>");
    private static final Pattern POM_JLINE_VERSION = Pattern.compile("<jline\\.version>([^<]*)</jline\\.version>");

    /** The heading of the section that states the release this repository ships. */
    private static final String VERSIONING_HEADING = "## Project status and versioning";

    /**
     * The heading that names the release a documented source break ships in, as the compatibility
     * prose states it: {@code ### Source migration to 0.1.0}. A two-component release reference
     * escapes {@link #DOCUMENTED_RELEASE_VERSION}, which needs three components, so it is matched
     * here instead.
     */
    private static final Pattern MIGRATION_HEADING = Pattern.compile("### Source migration to ([0-9]+(?:\\.[0-9]+)*)");

    @Test
    void theDocumentedJLineVersionIsTheOneTheBuildDerives() throws IOException {
        String expected = derivedJLineVersion(pomValue(POM_REVISION));
        List<String> documented = matches(Files.readString(Path.of("README.md")), DOCUMENTED_JLINE_VERSION);
        assertThat(documented)
                .as("the README names the locally built JLine version")
                .isNotEmpty();
        assertThat(documented).as("every JLine version the README states").containsOnly(expected);
        assertThat(pomValue(POM_JLINE_VERSION))
                .as("the JLine version pom.xml resolves")
                .isEqualTo(expected);
    }

    @Test
    void theDocumentedReleaseVersionIsTheOneTheBuildProduces() throws IOException {
        String revision = pomValue(POM_REVISION);
        String section = versioningSection(Files.readString(Path.of("README.md")));
        assertThat(section)
                .as("%s states the shipped version", VERSIONING_HEADING)
                .contains(revision);
        List<String> documented = matches(section, DOCUMENTED_RELEASE_VERSION);
        assertThat(documented)
                .as("the versioning section names a release version")
                .isNotEmpty();
        assertThat(documented).as("every release version the README states").containsOnly(revision);
    }

    /**
     * The section that tells a consumer how to migrate to the shipped contract has to name the
     * release that contract shipped in. The break it describes is the current source, so a heading
     * naming any other release documents a break the build does not produce, and a reader of the
     * published artifact cannot tell which release they are actually migrating into.
     */
    @Test
    void theDocumentedMigrationReleaseIsTheOneTheBuildProduces() throws IOException {
        String revision = pomValue(POM_REVISION);
        String readme = Files.readString(Path.of("README.md"));
        Matcher heading = MIGRATION_HEADING.matcher(readme);
        assertThat(heading.find())
                .as("the README keeps a source-migration heading that names a release")
                .isTrue();
        assertThat(heading.group(1))
                .as("the release the README says the source break ships in")
                .isEqualTo(revision);
        List<String> documented = matches(migrationSection(readme, heading), DOCUMENTED_RELEASE_VERSION);
        assertThat(documented)
                .as("every release version the migration section states")
                .containsOnly(revision);
    }

    /**
     * The JLine version {@code build.sh} derives for a revision: the numeric core with any
     * qualifier dropped, plus the {@code -jline-braxton} qualifier. Mirrors
     * {@code base_version_of} in that script.
     */
    private static String derivedJLineVersion(String revision) {
        String core = revision.split("-")[0];
        assertThat(core).as("pom.xml <revision> starts with a numeric version").matches("\\d+(\\.\\d+)*");
        return core + "-jline-braxton";
    }

    private static String pomValue(Pattern element) throws IOException {
        Matcher matcher = element.matcher(Files.readString(Path.of("pom.xml")));
        assertThat(matcher.find()).as("pom.xml declares %s", element.pattern()).isTrue();
        return matcher.group(1);
    }

    /** The migration section, from its heading to the next heading of any level. */
    private static String migrationSection(String readme, Matcher heading) {
        int start = heading.start();
        int end = readme.indexOf("\n#", start + heading.group().length());
        return end < 0 ? readme.substring(start) : readme.substring(start, end);
    }

    /** The README section that states the release, up to the next heading. */
    private static String versioningSection(String readme) {
        int start = readme.indexOf(VERSIONING_HEADING);
        assertThat(start)
                .as("the README keeps its %s section", VERSIONING_HEADING)
                .isNotNegative();
        int end = readme.indexOf("\n## ", start + VERSIONING_HEADING.length());
        return end < 0 ? readme.substring(start) : readme.substring(start, end);
    }

    private static List<String> matches(String text, Pattern pattern) {
        List<String> found = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}

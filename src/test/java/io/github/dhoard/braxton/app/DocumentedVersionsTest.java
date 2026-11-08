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

/** Published release identifiers must agree with the artifact version. */
class DocumentedVersionsTest {

    /**
     * A release version in prose. Three components are required so the sentence "releases follow
     * semantic versioning once 1.0 is reached" is prose about the major line, not a claim about
     * this release.
     */
    private static final Pattern DOCUMENTED_RELEASE_VERSION =
            Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.]+)?");

    private static final Pattern POM_REVISION = Pattern.compile("<revision>([^<]*)</revision>");

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
    void theDocumentedNativeBuildMatchesTheShippedContract() throws IOException {
        String readme = Files.readString(Path.of("README.md"));
        assertThat(readme).contains("C compiler", "macOS ARM64", "Linux", "--enable-native-access");
        assertThat(Files.readString(Path.of("pom.xml"))).contains("build-native-terminal");
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

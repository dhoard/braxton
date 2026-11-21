/*
 * Copyright (c) 2026-present Douglas Hoard
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Records reviewed direct package dependencies and rejects upward imports from the public kernel.
 * The edge ledger includes legitimate shared value dependencies; it is not a list of exceptions.
 * Nested classes and static members are resolved to their declaring package.
 */
class LayeringContractTest {

    private static final Path CORE = Path.of("src/main/java/io/github/dhoard/braxton");
    private static final String POLICY_RESOURCE = "/layering-policy.txt";
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT =
            Pattern.compile("^import\\s+(?:static\\s+)?(io\\.github\\.dhoard\\.braxton\\.[\\w.]+);", Pattern.MULTILINE);
    private static final Pattern OWNER = Pattern.compile("WP-\\d+\\.\\d+");

    @Test
    void sourceImportsMatchTheReviewedPolicy() throws IOException {
        assertThat(CORE).as("core source root").isDirectory();
        Map<String, String> baseline = readEdges(POLICY_RESOURCE);
        assertThat(actualEdges())
                .as("new or resolved package edges; update %s", POLICY_RESOURCE)
                .isEqualTo(new TreeSet<>(baseline.keySet()));
    }

    @Test
    void everyRecordedEdgeNamesAnOwningWorkPackage() throws IOException {
        Map<String, String> baseline = readEdges(POLICY_RESOURCE);
        for (Map.Entry<String, String> entry : baseline.entrySet()) {
            assertThat(OWNER.matcher(entry.getValue()).matches())
                    .as("owner of %s", entry.getKey())
                    .isTrue();
            assertThat(entry.getKey()).as("baseline edge").contains(" -> ").doesNotContain("#");
        }
    }

    private static TreeSet<String> actualEdges() throws IOException {
        TreeSet<String> edges = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(CORE)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(path);
                Matcher packageMatcher = PACKAGE.matcher(source);
                assertThat(packageMatcher.find())
                        .as("package declaration in %s", path)
                        .isTrue();
                String from = packageMatcher.group(1);
                Matcher importMatcher = IMPORT.matcher(source);
                while (importMatcher.find()) {
                    String to = importMatcher.group(1);
                    String targetPackage = importedPackage(to);
                    if (!from.equals(targetPackage)) {
                        edges.add(from + " -> " + targetPackage);
                    }
                }
            }
        }
        return edges;
    }

    private static String importedPackage(String symbol) {
        String[] parts = symbol.split("\\.");
        int type = 0;
        while (type < parts.length && !Character.isUpperCase(parts[type].charAt(0))) type++;
        return String.join(".", java.util.Arrays.copyOf(parts, type));
    }

    @Test
    void publicKernelDoesNotDependOnApplicationsOrWidgets() throws IOException {
        String prefix = "io.github.dhoard.braxton.";
        for (String edge : actualEdges()) {
            String[] ends = edge.split(" -> ");
            String from = ends[0].substring(prefix.length());
            String to = ends[1].substring(prefix.length());
            if (java.util.Set.of("terminal", "render", "layout", "style", "ui", "input")
                    .contains(from)) {
                assertThat(to)
                        .as("upward dependency: %s", edge)
                        .isNotIn("app", "mvu", "effect", "widget", "line", "image", "testing");
            }
            if (from.equals("internal.terminal")) {
                assertThat(to)
                        .as("native transport cannot depend on application configuration: %s", edge)
                        .isNotIn("app", "mvu", "effect", "widget", "ui");
            }
        }
    }

    @Test
    void noMigrationExceptionsRemain() throws IOException {
        assertThat(readEdges("/layering-baseline.txt")).isEmpty();
    }

    private static Map<String, String> readEdges(String resource) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream stream = LayeringContractTest.class.getResourceAsStream(resource)) {
            assertThat(stream).as("missing edge resource %s", resource).isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int comment = trimmed.indexOf('#');
                assertThat(comment).as("owner comment in %s", trimmed).isPositive();
                entries.put(
                        trimmed.substring(0, comment).trim(),
                        trimmed.substring(comment + 1).trim());
            }
        }
        return entries;
    }
}

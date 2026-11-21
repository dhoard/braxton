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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces the production size ceiling and an empty migration exception list.
 */
class SourceSizeContractTest {

    private static final int MAX_LINES = 1200;
    private static final Path MAIN = Path.of("src/main/java");
    private static final String BASELINE_RESOURCE = "/source-size-baseline.txt";

    @Test
    void everyProductionSourceFitsTheCeiling() throws IOException {
        assertThat(MAIN).as("main source root").isDirectory();
        assertThat(actualOversizedSources())
                .as("production sources over %s lines", MAX_LINES)
                .isEmpty();
    }

    @Test
    void noMigrationExceptionsRemain() throws IOException {
        assertThat(baseline()).isEmpty();
    }

    private static TreeSet<String> actualOversizedSources() throws IOException {
        TreeSet<String> result = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(MAIN)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList()) {
                if (Files.readAllLines(path).size() > MAX_LINES) {
                    result.add(MAIN.relativize(path).toString().replace('\\', '/'));
                }
            }
        }
        return result;
    }

    private static Map<String, String> baseline() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream stream = SourceSizeContractTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            assertThat(stream)
                    .as("missing baseline resource %s", BASELINE_RESOURCE)
                    .isNotNull();
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

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps actual Java documentation comments in the project's multiline block format.
 */
class JavadocContractTest {
    @Test
    void sourcesAndDeveloperProbesUseBlockJavadocs() throws IOException {
        List<String> failures = new ArrayList<>();
        for (Path root : List.of(Path.of("src"), Path.of("tools"))) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".java"))
                        .sorted()
                        .toList()) {
                    for (int offset : invalidComments(Files.readString(path))) {
                        failures.add(path + ":"
                                + (Files.readString(path)
                                                .substring(0, offset)
                                                .lines()
                                                .count()
                                        + 1));
                    }
                }
            }
        }
        assertThat(failures).as("Javadocs requiring a multiline block").isEmpty();
    }

    @Test
    void ignoresCommentMarkersInsideLiteralsAndOrdinaryComments() {
        String source = "String s = \"/** example */\";\n"
                + "// /** ordinary line comment */\n"
                + "/* ordinary /** block */\n"
                + "String block = \"\"\"\n/** literal */\n\"\"\";\n"
                + "/**\n * Valid documentation.\n */\n";
        assertThat(invalidComments(source)).isEmpty();
        assertThat(invalidComments(source + "/** Inline documentation. */")).containsExactly(source.length());
        assertThat(invalidComments("/**\n Missing asterisk.\n */")).containsExactly(0);
        assertThat(invalidComments("/** Text on opening line.\n */")).containsExactly(0);
        assertThat(invalidComments("/**\n * Text on closing line. */")).containsExactly(0);
    }

    private static List<Integer> invalidComments(String source) {
        List<Integer> invalid = new ArrayList<>();
        for (int i = 0; i < source.length(); ) {
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i + 2);
                i = end < 0 ? source.length() : end + 1;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) {
                    invalid.add(i);
                    break;
                }
                if (source.startsWith("/**", i)) {
                    String[] lines = source.substring(i, end + 2).split("\n", -1);
                    boolean valid = lines.length >= 3
                            && lines[0].strip().equals("/**")
                            && lines[lines.length - 1].strip().equals("*/");
                    for (int line = 1; line < lines.length - 1; line++) {
                        String content = lines[line].stripLeading();
                        valid &= content.equals("*") || content.startsWith("* ");
                    }
                    if (!valid) invalid.add(i);
                }
                i = end + 2;
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i);
                boolean textBlock = source.startsWith("\"\"\"", i);
                i += textBlock ? 3 : 1;
                while (i < source.length()) {
                    if (source.charAt(i) == '\\') {
                        i = Math.min(source.length(), i + 2);
                    } else if (textBlock && source.startsWith("\"\"\"", i)) {
                        i += 3;
                        break;
                    } else if (!textBlock && source.charAt(i++) == quote) {
                        break;
                    } else if (textBlock) {
                        i++;
                    }
                }
            } else {
                i++;
            }
        }
        return invalid;
    }
}

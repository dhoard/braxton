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

package io.github.dhoard.braxton.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * A FIGlet font that is not a FIGlet font, and the numbers a header is allowed to claim.
 *
 * <p>A font arrives as text a FIGlet header describes, and the header decides how the rest of the
 * stream is read: the glyph height, the number of comment lines to skip, the character that marks the
 * end of a line. Those numbers come from the file. A header claiming a glyph two billion rows tall, or
 * a comment count that runs past the end of the stream, is the difference between an
 * {@link IOException} the application can report and a runtime that allocates a two-billion element
 * list, or reads off the end of an array, on the way to loading a logo.</p>
 *
 * <p>So the header numbers are enumerated over the values that break index arithmetic, and the streams
 * they describe are random. The contract is narrow: {@link FigletFont#parse} either reports a
 * {@link IOException} or returns a font that renders the exact number of rows it promised, for any
 * text, without ever failing.</p>
 */
class FigletFontFuzzTest {

    private static final String[] HEIGHTS = {
        "1", "2", "3", "8", "95", "0", "-1", "2147483647", "-2147483648", "99999999999", "abc", "", "00001"
    };

    private static final String[] COMMENT_COUNTS = {
        "0", "1", "2", "5", "2147483647", "-2147483648", "99999999999", "x", ""
    };

    private static final String[] ART = {"@", "_", "/\\", "|", "", "@@", "  ", "\u4E2D", "\uD83D\uDE00", "#"};

    /**
     * Single characters a header may name as its hard blank. Never empty, never astral: the header is
     * one UTF-16 char wide, and a surrogate pair in it arrives at the parser as a replacement
     * character.
     */
    private static final char[] BLANKS = {'@', '_', '#', '*', '$', '+'};

    private static final String[] TEXTS = {
        "",
        "a",
        "abc",
        "hello world",
        "\n",
        "\n\n",
        "a\nb",
        "\u4E2D\u6587",
        "\uD83D\uDE00",
        "\u0000\u0001",
        "~",
        " ",
        "a".repeat(60)
    };

    @Test
    void noHeaderNumberCrashesTheParser() {
        for (String height : HEIGHTS) {
            for (String comments : COMMENT_COUNTS) {
                for (int lines = 0; lines <= 24; lines++) {
                    String font = header('@', height, comments) + body(lines);
                    parseAndReport(
                            font, "header [%s] with %d body lines".formatted(header('@', height, comments), lines));
                }
            }
        }
    }

    @Test
    void anArbitraryStreamIsRejectedOrRendersEveryRowItPromised() {
        Random random = new Random(11_235_813L);
        for (int round = 0; round < 4_000; round++) {
            StringBuilder font = new StringBuilder();
            font.append(randomHeader(random));
            for (int line = 1 + random.nextInt(20); line > 0; line--) {
                font.append('\n');
                for (int cells = random.nextInt(6); cells > 0; cells--) {
                    font.append(ART[random.nextInt(ART.length)]);
                }
            }
            parseAndReport(font.toString(), "stream %s".formatted(show(font.toString())));
        }
    }

    /** A complete font whose glyph art is random still answers for every text it is handed. */
    @Test
    void aRandomlyFilledFontRendersEveryRowItPromised() {
        Random random = new Random(71_406L);
        for (int round = 0; round < 400; round++) {
            int height = 1 + random.nextInt(3);
            int comments = random.nextInt(3);
            char blank = BLANKS[random.nextInt(BLANKS.length)];
            StringBuilder font = new StringBuilder(header(blank, String.valueOf(height), String.valueOf(comments)));
            for (int line = 0; line < comments + 95 * height; line++) {
                font.append('\n');
                for (int cells = random.nextInt(4); cells > 0; cells--) {
                    font.append(ART[random.nextInt(ART.length)]);
                }
            }
            // The last line has to be terminated, or it is not a line at all: a stream ending in an
            // empty glyph row carries one fewer line than it looks like it does.
            font.append('\n');
            FigletFont parsed = parseAndRequire(font.toString(), height, blank);
            for (String text : TEXTS) {
                assertThat(parsed.render(text))
                        .as("random font of height %d rendering [%s]", height, show(text))
                        .hasSize(height * (1 + countOf(text, '\n')));
            }
        }
    }

    private static FigletFont parseAndRequire(String font, int height, char blank) {
        try {
            FigletFont parsed =
                    FigletFont.parse(new ByteArrayInputStream(font.getBytes(StandardCharsets.UTF_8)), "fuzz");
            assertThat(parsed.height())
                    .as("height of a font whose header said %d", height)
                    .isEqualTo(height);
            assertThat(parsed.hardBlank()).isEqualTo(blank);
            return parsed;
        } catch (IOException unexpected) {
            throw new AssertionError(
                    "a complete " + height + "-row font was refused: " + unexpected.getMessage(), unexpected);
        }
    }

    /** A structurally valid font still has to answer for every text it is handed. */
    @Test
    void aWellFormedFontRendersTheRowsItPromisedForAnyText() throws IOException {
        int height = 3;
        StringBuilder font = new StringBuilder(header('@', "3", "0"));
        for (char code = 32; code <= 126; code++) {
            for (int row = 0; row < height; row++) {
                font.append('\n').append(code).append("-").append(row).append("@#");
            }
        }
        FigletFont parsed =
                FigletFont.parse(new ByteArrayInputStream(font.toString().getBytes(StandardCharsets.UTF_8)), "fuzz");
        assertThat(parsed.height()).isEqualTo(height);
        for (String text : TEXTS) {
            List<String> rows = parsed.render(text);
            int expectedRows = height * (1 + countOf(text, '\n'));
            assertThat(rows)
                    .as("rendering [%s] with a %d-row font", show(text), height)
                    .hasSize(expectedRows);
            for (String row : rows) {
                assertThat(row.contains(parsed.hardBlank() + ""))
                        .as("a rendered row still holds the hard blank of [%s]: [%s]", show(text), show(row))
                        .isFalse();
            }
        }
        assertThat(parsed.render(null)).hasSize(height);
    }

    /** Runs the parser and holds the one contract: an IOException, or a font that renders. */
    private static void parseAndReport(String font, String where) {
        try {
            FigletFont parsed =
                    FigletFont.parse(new ByteArrayInputStream(font.getBytes(StandardCharsets.UTF_8)), "fuzz");
            assertThat(parsed.height()).isPositive();
            for (String text : TEXTS) {
                List<String> rows = parsed.render(text);
                assertThat(rows)
                        .as(
                                "%s: rendering [%s] gave %d rows for a %d-row font",
                                where, show(text), rows.size(), parsed.height())
                        .hasSize(parsed.height() * (1 + countOf(text, '\n')));
            }
        } catch (IOException reported) {
            // The only acceptable answer to a font that is not a font.
        } catch (RuntimeException crash) {
            throw new AssertionError(
                    where + " failed as " + crash.getClass().getSimpleName() + ": " + crash.getMessage(), crash);
        }
    }

    private static String header(char hardBlank, String height, String comments) {
        return "flf2a" + hardBlank + " " + height + " 1 10 0 " + comments;
    }

    private static String randomHeader(Random random) {
        char blank = BLANKS[random.nextInt(BLANKS.length)];
        return "flf2a" + blank + " " + HEIGHTS[random.nextInt(HEIGHTS.length)] + " 1 10 0 "
                + COMMENT_COUNTS[random.nextInt(COMMENT_COUNTS.length)];
    }

    private static String body(int lines) {
        StringBuilder text = new StringBuilder();
        for (int line = 0; line < lines; line++) {
            text.append('\n').append("@");
        }
        return text.toString();
    }

    private static int countOf(String value, char target) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < Math.min(value.length(), 80); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append(value.length() > 80 ? "...\"" : "\"").toString();
    }
}

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

package io.github.dhoard.braxton.internal.clipboard;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.style.TextMeasure;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Clipboard text a hostile or merely unlucky source supplied, and a size limit it has to fit.
 *
 * <p>An OSC 52 sequence is the one place Braxton asks the terminal to touch something outside the
 * session - the user's clipboard. The text written there is whatever the application was holding: a
 * pasted transcript, a diff, a value typed into a field. And the selection field comes from
 * configuration. Both end up inside an escape sequence, so the property that matters is that the
 * sequence stays one sequence: exactly one introducer, a payload that is pure base64, and a selection
 * that is one of the names the protocol defines. Anything else is a second escape sequence riding into
 * the terminal inside what was meant to be content.</p>
 *
 * <p>The size limit is the other half. It is counted in UTF-8 bytes and applied on a grapheme-cluster
 * boundary, because a payload that stops in the middle of a ZWJ family emoji or a flag hands the
 * clipboard a half-formed character.</p>
 */
class Osc52FuzzTest {

    /** Content with its own escape bytes, control bytes, astral pairs, joiners and selectors. */
    private static final String[] TEXT_ATOMS = {
        "a",
        "z",
        " ",
        "\n",
        "\t",
        "\u001B[1;31m",
        "\u0007",
        "\u0000",
        "\u001B]52;c\u0007",
        "\u4E2D",
        "\uD83D\uDE00\uFE0F",
        "\uD83D\uDC69\u200D\uD83D\uDCBB",
        "\uD83C\uDDFA\uD83C\uDDF8",
        "e\u0301",
        "\uFE0F",
    };

    /** Selection values including the strings that would open a second sequence if trusted. */
    private static final String[] SELECTIONS = {
        null, "", "c", "p", "s", "0", "7", "8", "9", ";", ";p", "c;echo", "\u001B]52;", "cc", "C", "  "
    };

    /** ESC ] 52 ; selection ; base64 BEL - nothing before, between, or after. */
    private static final java.util.regex.Pattern FRAMED =
            java.util.regex.Pattern.compile("\u001B\\]52;([cps0-7]);([A-Za-z0-9+/=]*)\u0007");

    private static final Random RANDOM = new Random(31_415_926L);

    @Test
    void theSequenceStaysOneSequenceForAnyTextAndAnySelection() {
        for (int round = 0; round < 12_000; round++) {
            String text = text(RANDOM, 1 + RANDOM.nextInt(6));
            String selection = SELECTIONS[RANDOM.nextInt(SELECTIONS.length)];
            int maxBytes = RANDOM.nextInt(120) - 6;
            Osc52.Encoded encoded = Osc52.encode(text, selection, maxBytes);

            String where = "selection %s limit %d text %s".formatted(show(selection), maxBytes, show(text));
            // One regex pins the framing, the whitelisted selection, and a payload that is pure
            // base64: a selection or text that carried a separator or an escape byte cannot match.
            java.util.regex.Matcher framed = FRAMED.matcher(encoded.sequence());
            if (!framed.matches()) {
                throw new AssertionError(
                        where + ": sequence is not a single OSC 52 sequence: " + show(encoded.sequence()));
            }
            assertThat(framed.group(1))
                    .as("%s: selection reached the sequence as %s", where, show(framed.group(1)))
                    .isIn("c", "p", "s", "0", "1", "2", "3", "4", "5", "6", "7");
            String payload = framed.group(2);
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(payload);
            } catch (IllegalArgumentException notBase64) {
                throw new AssertionError(where + ": payload is not base64: " + show(payload), notBase64);
            }
            assertThat(new String(decoded, StandardCharsets.UTF_8))
                    .as("%s: decoded payload is not the carried text", where)
                    .isEqualTo(encoded.text());
            assertThat(encoded.byteLength())
                    .as("%s: reported byte length", where)
                    .isEqualTo(decoded.length);
            assertThat(encoded.byteLength())
                    .as("%s: payload exceeds the %d-byte limit", where, Math.max(0, maxBytes))
                    .isLessThanOrEqualTo(Math.max(0, maxBytes));
            assertThat(text)
                    .as("%s: carried text is not a prefix of the input", where)
                    .startsWith(encoded.text());
            assertThat(encoded.truncated())
                    .as("%s: truncation flag disagrees with the carried text", where)
                    .isEqualTo(encoded.text().length() < text.length());
        }
    }

    /** A payload cut by the byte limit stops between clusters, never inside one. */
    @Test
    void aTruncatedPayloadEndsOnAClusterBoundary() {
        for (int round = 0; round < 6_000; round++) {
            String text = text(RANDOM, 1 + RANDOM.nextInt(8));
            int limit = RANDOM.nextInt(40);
            String carried = Osc52.encode(text, limit).text();
            String where = "text %s limit %d carried %s".formatted(show(text), limit, show(carried));

            List<String> whole = TextMeasure.graphemes(text);
            List<String> kept = TextMeasure.graphemes(carried);
            // A limit smaller than the first cluster carries nothing at all, which is a clean
            // answer rather than a half-formed character.
            assertThat(whole.subList(0, kept.size()))
                    .as("%s: truncation cut through a cluster", where)
                    .isEqualTo(kept);
            for (String cluster : kept) {
                assertThat(TextMeasure.graphemes(cluster))
                        .as("%s: cluster [%s] survived as several", where, show(cluster))
                        .isEqualTo(List.of(cluster));
            }
            for (int i = 0; i < carried.length(); i++) {
                boolean danglingHigh = Character.isHighSurrogate(carried.charAt(i)) && i + 1 >= carried.length();
                boolean orphanLow = Character.isLowSurrogate(carried.charAt(i)) && i == 0;
                assertThat(danglingHigh || orphanLow)
                        .as("%s: carried text holds half of a surrogate pair", where)
                        .isFalse();
            }
            // A wider limit can only ever keep more of the same text.
            if (limit + 1 <= 40) {
                assertThat(Osc52.encode(text, limit + 1).text())
                        .as("%s with one more byte", where)
                        .startsWith(carried);
            }
        }
    }

    /** The no-limit form: an empty or null draft writes nothing rather than clearing the clipboard. */
    @Test
    void anEmptyDraftNeverEmitsAClearingSequence() {
        assertThat(Osc52.encode(null)).isEmpty();
        assertThat(Osc52.encode("")).isEmpty();
        for (int round = 0; round < 2_000; round++) {
            String text = text(RANDOM, 1 + RANDOM.nextInt(4));
            String sequence = Osc52.encode(text);

            java.util.regex.Matcher framed = FRAMED.matcher(sequence);
            assertThat(framed.matches()).as("framing of %s", show(text)).isTrue();
            assertThat(new String(Base64.getDecoder().decode(framed.group(2)), StandardCharsets.UTF_8))
                    .as("round trip of %s", show(text))
                    .isEqualTo(text);
        }
    }

    private static String text(Random random, int atoms) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < atoms; i++) {
            parts.add(TEXT_ATOMS[random.nextInt(TEXT_ATOMS.length)]);
        }
        return String.join("", parts);
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
        if (value == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append('"').toString();
    }
}

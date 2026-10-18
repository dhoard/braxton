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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.TextMeasure;

/** OSC 52 selection whitelisting, payload encoding, and cluster-safe truncation. */
class Osc52Test {

    private static final String FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
    private static final String FLAG = "\uD83C\uDDFA\uD83C\uDDF8";
    private static final String COMBINING = "e\u0301";

    private static String payloadOf(String sequence) {
        int selection = sequence.indexOf(';');
        int start = sequence.indexOf(';', selection + 1) + 1;
        return new String(Base64.getDecoder().decode(
                sequence.substring(start, sequence.length() - 1)), StandardCharsets.UTF_8);
    }

    @Test
    void everyDocumentedSelectionIsWrittenVerbatim() {
        for (String selection : List.of("c", "p", "s", "0", "1", "2", "3", "4", "5", "6", "7")) {
            Osc52.Encoded encoded = Osc52.encode("hi", selection, Integer.MAX_VALUE);
            assertThat(encoded.sequence()).as("selection %s", selection)
                    .isEqualTo("\u001B]52;" + selection + ";" + Base64.getEncoder()
                            .encodeToString("hi".getBytes(StandardCharsets.UTF_8)) + "\u0007");
            assertThat(payloadOf(encoded.sequence())).isEqualTo("hi");
            assertThat(encoded.truncated()).isFalse();
            assertThat(encoded.byteLength()).isEqualTo(2);
        }
    }

    @Test
    void anUnknownOrInjectedSelectionFallsBackToTheSystemClipboard() {
        for (String selection : List.of("", "x", "8", "9", "C", "P", "cc", "cp", "c;c",
                "c\u0007", "\u001B]52;", " c", "c ")) {
            Osc52.Encoded encoded = Osc52.encode("hi", selection, Integer.MAX_VALUE);
            assertThat(encoded.sequence()).as("selection %s", selection)
                    .isEqualTo(Osc52.encode("hi", "c", Integer.MAX_VALUE).sequence())
                    .startsWith("\u001B]52;c;");
        }
        assertThat(Osc52.encode("hi", null, Integer.MAX_VALUE).sequence())
                .isEqualTo(Osc52.encode("hi", "c", Integer.MAX_VALUE).sequence());
    }

    @Test
    void truncationCutsOnClusterBoundariesAndNeverExceedsTheLimit() {
        String[] texts = {FAMILY, FLAG, COMBINING, "a" + COMBINING, "plain ascii text",
            FAMILY + FLAG + COMBINING + "\u6F22\u672C", "\uD83D\uDE00"};
        for (String text : texts) {
            List<String> prefixes = new ArrayList<>();
            StringBuilder prefix = new StringBuilder();
            for (String cluster : TextMeasure.graphemes(text)) {
                prefixes.add(prefix.toString());
                prefix.append(cluster);
            }
            prefixes.add(prefix.toString());
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            for (int max = 0; max <= bytes + 1; max++) {
                String limited = Osc52.limitUtf8(text, max);
                assertThat(limited).as("%s at %d bytes", text, max).isIn(prefixes);
                assertThat(limited.getBytes(StandardCharsets.UTF_8).length)
                        .as("%s at %d bytes", text, max).isLessThanOrEqualTo(max);
                assertThat(Osc52.limitUtf8(limited, max)).as("%s is stable", text)
                        .isEqualTo(limited);
            }
            assertThat(Osc52.limitUtf8(text, Integer.MAX_VALUE)).isEqualTo(text);
        }
    }

    @Test
    void theFirstClusterThatDoesNotFitEndsThePayload() {
        // The limit cuts, it does not skip ahead: a small cluster after an oversized one does not
        // jump the gap, so the payload stays a prefix of the text.
        assertThat(Osc52.limitUtf8(FAMILY + "a", 4)).isEmpty();
        assertThat(Osc52.limitUtf8("a" + FAMILY, 4)).isEqualTo("a");
        assertThat(Osc52.limitUtf8("ab" + FLAG, 6)).isEqualTo("ab");
        assertThat(Osc52.limitUtf8(COMBINING, 1)).isEmpty();
        assertThat(Osc52.limitUtf8(COMBINING, 2)).isEmpty();
        assertThat(Osc52.limitUtf8(COMBINING, 3)).isEqualTo(COMBINING);
        assertThat(Osc52.limitUtf8(COMBINING + "a", 3)).isEqualTo(COMBINING);
        assertThat(Osc52.limitUtf8(COMBINING + "a", 4)).isEqualTo(COMBINING + "a");
        assertThat(Osc52.limitUtf8("x", 0)).isEmpty();
        assertThat(Osc52.limitUtf8("", 4)).isEmpty();
    }

    @Test
    void aLimitThatRemovesEverythingStillBuildsASequenceAndTheServiceMustRefuseIt() {
        // OSC 52 has no acknowledgement and an empty payload clears the clipboard, so the encoder
        // reports the empty text honestly and the caller (ClipboardService.copy) refuses to write.
        Osc52.Encoded encoded = Osc52.encode("\u6F22", "c", 1);
        assertThat(encoded.text()).isEmpty();
        assertThat(encoded.byteLength()).isZero();
        assertThat(encoded.truncated()).isTrue();
        assertThat(encoded.sequence()).isEqualTo("\u001B]52;c;\u0007");
        assertThat(Osc52.encode("\u6F22", "c", -5).sequence()).isEqualTo("\u001B]52;c;\u0007");
    }

    @Test
    void theUnboundedEncoderWritesNothingForNothing() {
        assertThat(Osc52.encode(null)).isEmpty();
        assertThat(Osc52.encode("")).isEmpty();
        assertThat(Osc52.encode("hi")).isEqualTo(Osc52.encode("hi", "c", Integer.MAX_VALUE).sequence());
    }

    @Test
    void randomTextTruncatesToAWholeClusterUnderEveryLimit() {
        String[] pieces = {"a", "bc", FAMILY, FLAG, COMBINING, "\u6F22", "\n", " "};
        Random random = new Random(53L);
        for (int iteration = 0; iteration < 3_000; iteration++) {
            StringBuilder text = new StringBuilder();
            int count = random.nextInt(5);
            for (int i = 0; i < count; i++) {
                text.append(pieces[random.nextInt(pieces.length)]);
            }
            String source = text.toString();
            int max = random.nextInt(24);
            String limited = Osc52.limitUtf8(source, max);
            assertThat(source).as("iteration %d", iteration).startsWith(limited);
            assertThat(limited.getBytes(StandardCharsets.UTF_8).length)
                    .as("iteration %d at %d bytes", iteration, max).isLessThanOrEqualTo(max);
            assertThat(TextMeasure.graphemes(limited)).as("iteration %d", iteration)
                    .isSubsetOf(TextMeasure.graphemes(source));
            // A larger budget never removes content.
            String wider = Osc52.limitUtf8(source, max + 1);
            assertThat(wider).as("iteration %d grows monotonically", iteration)
                    .startsWith(limited);
        }
    }
}
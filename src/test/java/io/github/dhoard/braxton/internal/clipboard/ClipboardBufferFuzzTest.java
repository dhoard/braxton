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

import io.github.dhoard.braxton.internal.text.Graphemes;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A clipboard bound that has to land between characters.
 *
 * <p>The stored value is text the application pastes back, so cutting it is a content decision, not a
 * memory decision: a cut inside a cluster leaves a joiner separated from the pictographs it joined, or a
 * mark attached to whatever the user pastes next. The value the terminal gives back has to be whole
 * clusters, and a bound too small for the first cluster has to store nothing rather than half of it.</p>
 *
 * <p>The interesting part is that the implementation does not segment the whole string to find that
 * boundary. It segments a window, because segmenting is the expensive half and a 200 KB paste should
 * cost the same as a 200 byte one. A cluster has no length limit, so a window that ends inside the
 * cluster that straddles the bound cannot tell where the cluster ends, and the code has to notice that
 * and fall back instead of guessing. The oracle here is the obvious slow answer - segment everything,
 * keep whole clusters while they fit - so every disagreement is the fast path giving a different
 * clipboard than the slow one would.</p>
 */
@Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
class ClipboardBufferFuzzTest {

    private static final String[] FRAGMENTS = {
        "a",
        "ab",
        "hello world",
        "\u0301",
        "\u0301\u0323",
        "\u200D",
        "\uFE0F",
        "\uFE0E",
        "\u20DD",
        " ",
        "\n",
        "\t",
        "\u0000",
        "\u0007",
        "\u001B",
        "\u007F",
        "\u4E2D",
        "\u00E9",
        "\uD83D\uDE00",
        "\uD83D\uDE00\u200D\uD83D\uDE00",
        "\uD83D\uDE00\uFE0F",
        "\uD83C\uDFFB",
        "\uD83C\uDDE6\uD83C\uDDFA",
        "\uD83D",
        "\uDE00",
        "\uD83D\uDE00\u0301\u0302\u0303",
        "\u2764\uFE0F\u200D\uD83D\uDD25",
    };

    private static final int[] BOUNDS = {
        Integer.MIN_VALUE, -1, 0, 1, 2, 3, 4, 5, 7, 16, 32, 63, 64, 65, 66, 100, 127, 128, 129, 4096
    };

    @Test
    void everyTruncationLandsOnAClusterBoundaryAndMatchesSegmentingTheWholeValue() {
        Random random = new Random(1_618_033L);
        for (int round = 0; round < 5_000; round++) {
            String value = text(random);
            int bound = BOUNDS[random.nextInt(BOUNDS.length)];
            ClipboardBuffer buffer = new ClipboardBuffer(bound);

            String stored = buffer.set(value);
            String where = "bound %d value %s".formatted(bound, show(value));

            assertThat(stored)
                    .as("%s: stored value is not what get() reports", where)
                    .isEqualTo(buffer.get());
            assertThat(buffer.length())
                    .as("%s: length() disagrees with the stored value", where)
                    .isEqualTo(stored.length());
            assertThat(buffer.isEmpty())
                    .as("%s: isEmpty() disagrees with the stored value", where)
                    .isEqualTo(stored.isEmpty());
            assertThat(value)
                    .as("%s: the stored value %s is not a prefix of what was set", where, show(stored))
                    .startsWith(stored);
            assertThat(stored.length())
                    .as("%s: stored %d characters past a bound of %d", where, stored.length(), Math.max(1, bound))
                    .isLessThanOrEqualTo(Math.max(1, bound));
            if (value.length() > Math.max(1, bound)) {
                assertThat(stored)
                        .as("%s: a truncated value ends in half of a character: %s", where, show(stored))
                        .doesNotEndWith("\uD83D");
            }
            assertThat(stored)
                    .as("%s: truncated value differs from keeping whole clusters while they fit", where)
                    .isEqualTo(storedFor(value, bound));
            // Segmentation of the stored value has to agree with segmentation of the source: a cut
            // that falls between characters would show up as a different first cluster.
            List<String> keptClusters = clusters(stored);
            List<String> sourceClusters = clusters(value);
            assertThat(sourceClusters.size())
                    .as("%s: the stored value holds more clusters than the source", where)
                    .isGreaterThanOrEqualTo(keptClusters.size());
            assertThat(sourceClusters.subList(0, keptClusters.size()))
                    .as("%s: the stored value does not begin with whole clusters of the source", where)
                    .isEqualTo(keptClusters);
            // Storing the stored value again must be a no-op: the value it returns already fits.
            assertThat(buffer.set(stored))
                    .as("%s: setting the stored value again changed it", where)
                    .isEqualTo(stored);
            buffer.clear();
            assertThat(buffer.get()).as("%s: clear() left text behind", where).isEmpty();
        }
    }

    /** Bounds that cannot hold the first cluster, whatever that cluster is. */
    @Test
    void aBoundSmallerThanTheFirstClusterStoresNothingInsteadOfHalfACluster() {
        String[] first = {
            "\uD83D\uDE00",
            "\uD83D\uDE00\u200D\uD83D\uDE00",
            "\uD83D\uDE00\uFE0F\u200D\u27A1\uFE0F",
            "a".repeat(300),
            "\u4E2D" + "\u0301".repeat(300),
            "\uD83D" + "\u0301".repeat(300),
        };
        for (String value : first) {
            for (int bound : new int[] {Integer.MIN_VALUE, -7, 0, 1, 2, 3, 4, 8, 16, 31, 63, 64, 100}) {
                ClipboardBuffer buffer = new ClipboardBuffer(bound);
                String stored = buffer.set(value + "tail");
                String where = "bound %d first cluster %s".formatted(bound, show(value));
                assertThat(stored)
                        .as("%s: stored %s, which is not whole clusters of the value", where, show(stored))
                        .isEqualTo(storedFor(value + "tail", bound));
                assertThat(stored.length())
                        .as("%s: stored value is past the bound", where)
                        .isLessThanOrEqualTo(Math.max(1, bound));
                if (storedFor(value, bound).isEmpty()) {
                    assertThat(stored)
                            .as("%s: the first cluster does not fit, so nothing may be stored", where)
                            .isEmpty();
                }
            }
        }
    }

    /**
     * The window is the whole point of the implementation, so a value far larger than the bound has to
     * give the same answer as a small one - and must not cost the size of the value.
     */
    @Test
    void aPasteFarLargerThanTheBoundIsTruncatedLikeASmallOne() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            huge.append("\uD83D\uDE00\u200D\uD83D\uDE00\uFE0f\u0301abc\u4E2d");
        }
        String value = huge.toString();
        for (int bound : new int[] {1, 5, 64, 129, 500}) {
            ClipboardBuffer buffer = new ClipboardBuffer(bound);
            String stored = buffer.set(value);
            assertThat(stored)
                    .as(
                            "bound %d: a %d character value truncated differently than the whole-value answer",
                            bound, value.length())
                    .isEqualTo(storedFor(value, bound));
            assertThat(stored.length()).as("bound %d: past the bound", bound).isLessThanOrEqualTo(bound);
        }
        // A cluster longer than the segmenting window: the fast path cannot see where it ends and has
        // to fall back rather than cut inside it.
        String longCluster = "x" + "\u0301".repeat(500) + "y";
        for (int bound : new int[] {1, 8, 64, 300, 501, 502}) {
            ClipboardBuffer buffer = new ClipboardBuffer(bound);
            assertThat(buffer.set(longCluster))
                    .as("bound %d: a 502 character cluster was cut inside itself", bound)
                    .isEqualTo(storedFor(longCluster, bound));
            assertThat(buffer.set("ab" + longCluster))
                    .as("bound %d: a long cluster behind two ordinary characters was cut inside itself", bound)
                    .isEqualTo(storedFor("ab" + longCluster, bound));
        }
    }

    /**
     * What the bound allows: verbatim when the value fits (a malformed value that fits is kept as it
     * arrived), and otherwise the whole clusters that fit with any unpaired high surrogate the cut
     * leaves at the end removed, since half a surrogate is not a character anything can render.
     */
    private static String storedFor(String value, int maxChars) {
        int bound = Math.max(1, maxChars);
        if (value.length() <= bound) {
            return value;
        }
        String kept = wholeClusters(value, bound);
        int end = kept.length();
        while (end > 0 && Character.isHighSurrogate(kept.charAt(end - 1))) {
            end--;
        }
        return kept.substring(0, end);
    }

    /** The slow, obvious answer: segment everything, keep whole clusters while they still fit. */
    private static String wholeClusters(String value, int bound) {
        StringBuilder kept = new StringBuilder();
        for (String cluster : Graphemes.iterate(value)) {
            if (kept.length() + cluster.length() > bound) {
                break;
            }
            kept.append(cluster);
        }
        return kept.toString();
    }

    private static List<String> clusters(String value) {
        List<String> all = new ArrayList<>();
        for (String cluster : Graphemes.iterate(value)) {
            all.add(cluster);
        }
        return all;
    }

    private static String text(Random random) {
        StringBuilder text = new StringBuilder();
        for (int count = 1 + random.nextInt(10); count > 0; count--) {
            text.append(FRAGMENTS[random.nextInt(FRAGMENTS.length)]);
        }
        return text.toString();
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < Math.min(value.length(), 40); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append(value.length() > 40 ? "...\"" : "\"").toString();
    }
}

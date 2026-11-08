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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A clock that arrives out of order, in the future, or wrapped around.
 *
 * <p>Frames are pure functions of a monotonic clock, which means the arithmetic has to survive the
 * values a clock actually produces: a start time recorded after the first tick, a clock read at the
 * end of its range, a widget that has been running for years. A spinner that computes its frame as
 * {@code (elapsed / step) % frames} indexes an array with a negative number the moment elapsed goes
 * negative, and a shimmer that re-renders its text can quietly change the text.</p>
 *
 * <p>The frame table is read from the animation itself at the four documented quarter-second offsets,
 * and the expected frame for a time difference comes from exact arithmetic rather than the
 * implementation's own. For differences large enough to overflow the clock arithmetic the frame is
 * arbitrary by construction, so those cases only have to stay in range; for everything else the frame
 * is checked exactly, and a label that has been up for less than one step has to be on its first
 * frame.</p>
 */
@Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
class AnimationFuzzTest {

    private static final BigInteger MILLIS = BigInteger.valueOf(1_000_000L);
    private static final BigInteger STEP = BigInteger.valueOf(250L);

    /** Hostile label text: joins, marks, controls, astral text and half a surrogate pair. */
    private static final String[] TEXTS = {
        "",
        "a",
        "abc",
        "Hello, world",
        "\u00E9\u0301",
        "\u0301",
        "\u200D",
        "\uFE0F",
        "\uFE0E",
        "\uD83D\uDE00",
        "\uD83D\uDE00\u200D\uD83D\uDE00\uFE0F",
        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
        "\u4E2D\u6587",
        "\n",
        "a\nb",
        "\t",
        "\u0000",
        "\u0007\u001B[31m",
        "\uD83D",
        "\uDE00",
        "x".repeat(400),
        "\u25C7\u25C8\u25C6",
        "   ",
    };

    private static final long[] TIMES = {
        0L,
        1L,
        -1L,
        250_000_000L,
        500_000_000L,
        750_000_000L,
        1_000_000_000L,
        Long.MAX_VALUE,
        Long.MIN_VALUE,
        Long.MAX_VALUE - 1L,
        Long.MIN_VALUE + 1L,
    };

    @Test
    void theSpinnerNeverLeavesItsFrameTableForAnyPairOfClockReadings() {
        String[] table = frameTable();
        Random random = new Random(1_234_567L);
        List<long[]> pairs = new ArrayList<>();
        for (long t : TIMES) {
            for (long s : TIMES) {
                pairs.add(new long[] {t, s});
            }
        }
        for (int round = 0; round < 6_000; round++) {
            long now = random.nextLong();
            long started = random.nextLong();
            if (round % 3 == 0) {
                now = TIMES[random.nextInt(TIMES.length)];
            }
            if (round % 5 == 0) {
                started = TIMES[random.nextInt(TIMES.length)];
            }
            pairs.add(new long[] {now, started});
        }
        for (long[] pair : pairs) {
            long now = pair[0];
            long started = pair[1];
            StyledText frame = Animation.spinner(now, started, Style.NONE);
            String glyph = frame.plainText();
            String where = "now %d started %d".formatted(now, started);
            assertThat(glyph)
                    .as("%s: the spinner painted %s, which is not one of its frames", where, show(glyph))
                    .isIn((Object[]) table);
            assertThat(Animation.spinnerFrames())
                    .as("%s: the frame count disagrees with the frames the spinner paints", where)
                    .isPositive();
        }
    }

    /**
     * Where the frame comes from, as long as the clock difference fits in a clock. The expected frame
     * is computed with exact arithmetic on the difference; once the difference itself overflows the
     * long the result is meaningless, so those cases are covered by the range check above instead.
     */
    @Test
    void theSpinnerFrameIsTheStepOfTheElapsedTimeWrappedOverTheCycle() {
        String[] table = frameTable();
        Random random = new Random(7_654_321L);
        BigInteger limit = BigInteger.valueOf(Long.MAX_VALUE);
        for (int round = 0; round < 4_000; round++) {
            long started = random.nextInt(round % 4 == 0 ? 100 : 1_000_000) - 500_000L;
            long now = started + (random.nextLong(40_000L) - 10_000L) * 1_000_000L;
            BigInteger delta = BigInteger.valueOf(now).subtract(BigInteger.valueOf(started));
            if (delta.abs().compareTo(limit) >= 0) {
                continue;
            }
            BigInteger millis = delta.max(BigInteger.ZERO).divide(MILLIS);
            int expectedFrame =
                    millis.divide(STEP).mod(BigInteger.valueOf(table.length)).intValue();
            String glyph = Animation.spinner(now, started, Style.NONE).plainText();
            assertThat(glyph)
                    .as(
                            "now %d started %d: expected frame %d (%s)",
                            now, started, expectedFrame, show(table[expectedFrame]))
                    .isEqualTo(table[expectedFrame]);
        }
        // A label younger than one step has not moved, whatever its start time was.
        for (long started : TIMES) {
            for (long ageNanos : new long[] {0L, 1L, 249_999_999L}) {
                BigInteger delta = BigInteger.valueOf(started).add(BigInteger.valueOf(ageNanos));
                if (delta.compareTo(limit) > 0) {
                    continue;
                }
                assertThat(Animation.spinner(started + ageNanos, started, Style.NONE)
                                .plainText())
                        .as("started %d after %d ns: the spinner moved before its first step", started, ageNanos)
                        .isEqualTo(table[0]);
            }
        }
    }

    /** Walking the clock forward sweeps the frame table in cycle order, never skipping a frame. */
    @Test
    void aFullCycleVisitsEveryFrameInOrder() {
        String[] table = frameTable();
        int frames = Animation.spinnerFrames();
        long started = 5_000_000_000L;
        for (int lap = 0; lap < 3; lap++) {
            for (int frame = 0; frame < frames; frame++) {
                long now = started + (lap * frames + frame) * 250_000_000L;
                assertThat(Animation.spinner(now, started, Style.NONE).plainText())
                        .as("lap %d frame %d", lap, frame)
                        .isEqualTo(table[frame]);
            }
        }
        assertThat(table)
                .as("the frame table does not have spinnerFrames() entries")
                .hasSize(frames);
    }

    /** The shimmer keeps its text and its style, whatever the clock and whatever the label. */
    @Test
    void theShimmerNeverChangesItsTextOrInventsAColor() {
        Random random = new Random(8_765_432L);
        Color base = new Color(10, 20, 30);
        Color highlight = new Color(200, 210, 220);
        Set<Color> allowed = Set.of(base, highlight);
        for (int round = 0; round < 6_000; round++) {
            String text = TEXTS[random.nextInt(TEXTS.length)];
            if (random.nextBoolean()) {
                text = text + TEXTS[random.nextInt(TEXTS.length)];
            }
            long now = random.nextLong(2_000_000_000L) * (random.nextBoolean() ? 1 : -1);
            if (round % 7 == 0) {
                now = TIMES[random.nextInt(TIMES.length)];
            }
            StyledText shimmered = Animation.shimmer(text, now, base, highlight);
            String where = "text %s now %d".formatted(show(text), now);

            // Painting must not rewrite the label: what a shimmered label says is what it was given,
            // once the terminal boundary has dropped what it cannot carry.
            assertThat(shimmered.plainText())
                    .as("%s: the shimmer changed the text it was given", where)
                    .isEqualTo(StyledText.plain(text).plainText());
            assertThat(shimmered.graphemeCount())
                    .as("%s: the shimmer changed the number of characters on screen", where)
                    .isEqualTo(TextMeasure.graphemes(shimmered.plainText()).size());

            StringBuilder painted = new StringBuilder();
            Set<Integer> highlighted = new HashSet<>();
            int clusters = 0;
            List<Boolean> lit = new ArrayList<>();
            for (StyledText.Span span : shimmered.spans()) {
                painted.append(span.text());
                Color foreground = span.style().foreground();
                assertThat(foreground)
                        .as(
                                "%s: span %s carries the colour %s, which was never offered",
                                where, show(span.text()), foreground)
                        .isIn(allowed);
                int spanClusters = TextMeasure.graphemes(span.text()).size();
                for (int at = 0; at < spanClusters; at++) {
                    lit.add(foreground.equals(highlight));
                }
                clusters += spanClusters;
            }
            for (int at = 0; at < lit.size(); at++) {
                if (lit.get(at)) {
                    highlighted.add(at);
                }
            }
            // The spans are the label: reading them in order has to say exactly what the label says.
            assertThat(painted.toString())
                    .as("%s: the styled spans do not add up to the label", where)
                    .isEqualTo(shimmered.plainText());
            if (!shimmered.plainText().isEmpty()) {
                assertThat(clusters)
                        .as(
                                "%s: the spans hold %d characters, the label shows %d",
                                where, clusters, shimmered.graphemeCount())
                        .isEqualTo(shimmered.graphemeCount());
                // A band of four travels across the label: it never lights more than four characters
                // at once, and it is allowed to be off the end of the label between passes.
                assertThat(highlighted.size())
                        .as("%s: %d of %d characters are highlighted at once", where, highlighted.size(), clusters)
                        .isBetween(0, Math.min(4, clusters));
            }
        }
    }

    /**
     * The highlight is a band that crosses the label: over one full pass every character has to be lit
     * at some point. A band that stops one place short, or a cycle that comes round before the band
     * has left the label, leaves a character permanently dark - which on a status line reads as a
     * frozen widget.
     */
    @Test
    void everyCharacterIsLitOnceAcrossOnePassOfTheHighlight() {
        Color base = new Color(1, 2, 3);
        Color highlight = new Color(250, 250, 250);
        for (String given : new String[] {"abcdefghijkl", "a\nb\tc", "\uD83D\uDE00\u4E2Dabc\u0301", "ab"}) {
            String paintedText = StyledText.plain(given).plainText();
            int clusters = TextMeasure.graphemes(paintedText).size();
            if (clusters == 0) {
                continue;
            }
            Map<Integer, Integer> litAt = new java.util.HashMap<>();
            for (int position = 0; position < clusters + 12; position++) {
                StyledText shimmered = Animation.shimmer(given, position * 70_000_000L, base, highlight);
                List<Boolean> flags = new ArrayList<>();
                for (StyledText.Span span : shimmered.spans()) {
                    for (int at = 0; at < TextMeasure.graphemes(span.text()).size(); at++) {
                        flags.add(span.style().foreground().equals(highlight));
                    }
                }
                assertThat(flags.size())
                        .as(
                                "text %s at position %d: %d spans for %d characters",
                                show(given), position, flags.size(), clusters)
                        .isEqualTo(clusters);
                int lit = (int) flags.stream().filter(Boolean::booleanValue).count();
                assertThat(lit)
                        .as("text %s at position %d: %d characters lit at once", show(given), position, lit)
                        .isLessThanOrEqualTo(Math.min(4, clusters));
                for (int at = 0; at < clusters; at++) {
                    if (flags.get(at)) {
                        litAt.merge(at, 1, Integer::sum);
                    }
                }
            }
            for (int at = 0; at < clusters; at++) {
                assertThat(litAt.getOrDefault(at, 0))
                        .as("text %s: character %d of %d is never lit during a pass", show(given), at, clusters)
                        .isPositive();
            }
        }
    }

    /** Missing arguments are a missing label, not a crash on the next tick. */
    @Test
    void aShimmerWithoutTextOrColourStillRenders() {
        for (String text : new String[] {null, ""}) {
            for (Color base : new Color[] {null, Color.BLACK}) {
                for (Color highlight : new Color[] {null, Color.WHITE}) {
                    for (long now : TIMES) {
                        StyledText shimmered = Animation.shimmer(text, now, base, highlight);
                        assertThat(shimmered.isEmpty())
                                .as("text %s base %s highlight %s at %d", text, base, highlight, now)
                                .isTrue();
                    }
                }
            }
        }
        for (long now : TIMES) {
            for (String text : TEXTS) {
                assertThat(Animation.shimmer(text, now, null, null).plainText())
                        .as("text %s at %d without colours", show(text), now)
                        .isEqualTo(StyledText.plain(text).plainText());
                assertThat(Animation.shimmer(text, now, Color.BLACK, null).plainText())
                        .as("text %s at %d without a highlight", show(text), now)
                        .isEqualTo(StyledText.plain(text).plainText());
            }
        }
    }

    /** The spinner's own frames, read at the documented quarter-second offsets. */
    private static String[] frameTable() {
        int frames = Animation.spinnerFrames();
        String[] table = new String[frames];
        for (int frame = 0; frame < frames; frame++) {
            table[frame] = Animation.spinner(10_000_000_000L + frame * 250_000_000L, 10_000_000_000L, Style.NONE)
                    .plainText();
        }
        return table;
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < Math.min(value.length(), 32); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append(value.length() > 32 ? "...\"" : "\"").toString();
    }
}

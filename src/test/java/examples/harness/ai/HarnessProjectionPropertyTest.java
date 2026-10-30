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

package examples.harness.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.widget.Editor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The visible transcript window and its scrollbar thumb hold for any content and viewport. */
class HarnessProjectionPropertyTest {

    private static final String TRACK = "\u2502";
    private static final String THUMB = "\u2503";

    private static Harness.Model randomState(Random random) {
        List<HarnessEntry> entries = new ArrayList<>();
        int count = random.nextInt(30);
        for (int i = 0; i < count; i++) {
            entries.add(switch (random.nextInt(5)) {
                case 0 -> new HarnessEntry.User("user " + i);
                case 1 -> new HarnessEntry.Assistant("assistant " + i);
                case 2 -> new HarnessEntry.Reasoning("thinking " + i);
                case 3 -> new HarnessEntry.Notice("notice " + i);
                default -> new HarnessEntry.Tool("tool " + i, "out\nput " + i,
                        random.nextBoolean(),
                        random.nextBoolean() ? HarnessEntry.ToolStatus.SUCCESS
                                : HarnessEntry.ToolStatus.PENDING);
            });
        }
        int rows = 1 + random.nextInt(24);
        int cols = 1 + random.nextInt(60);
        return Harness.Model.initial(new Size(rows, cols), "~/braxton", List.of())
                .withEntries(entries)
                .withStatus(Session.Status.READY)
                .withViewport(new Size(rows, cols))
                .withDraft(Editor.Model.of("d".repeat(random.nextInt(6))));
    }

    private static List<String> thumbRows(Harness.Model state, int width, int height) {
        List<String> rows = new ArrayList<>();
        for (StyledText row :
                HarnessProjection.window(
                        (state).session(),
                        width,
                        HarnessProjection.allocate(height, state.editorRows()).transcript())) {
            String plain = row.plainText();
            rows.add(plain.endsWith(THUMB) ? THUMB : plain.endsWith(TRACK) ? TRACK : "");
        }
        return rows;
    }

    @Test
    void everyWindowRowIsExactlyTheViewportWidth() {
        Random random = new Random(41L);
        for (int iteration = 0; iteration < 400; iteration++) {
            Harness.Model state = randomState(random);
            int width = state.viewport().cols();
            int height = state.viewport().rows();
            int rows = HarnessProjection.allocate(height, state.editorRows()).transcript();
            List<StyledText> window =
                    HarnessProjection.window(
                            (state).session(),
                            width,
                            HarnessProjection.allocate(height, state.editorRows()).transcript());
            assertThat(window).as("iteration %d size", iteration).hasSize(Math.max(0, rows));
            for (int row = 0; row < window.size(); row++) {
                assertThat(TextMeasure.columns(window.get(row).plainText()))
                        .as("iteration %d row %d of %d", iteration, row, width)
                        .isEqualTo(width);
            }
        }
    }

    @Test
    void theThumbIsOneContiguousRunThatReachesBothEndsOfItsTrack() {
        Random random = new Random(67L);
        int overflowing = 0;
        for (int iteration = 0; iteration < 400; iteration++) {
            Harness.Model state = randomState(random);
            int width = state.viewport().cols();
            int height = state.viewport().rows();
            int max = HarnessProjection.maxScroll(state);
            if (max <= 0) {
                assertThat(thumbRows(state, width, height)).as("iteration %d", iteration)
                        .noneMatch(TRACK::equals);
                continue;
            }
            overflowing++;
            List<String> bottom = thumbRows(state.withScroll(0), width, height);
            assertThat(bottom.get(bottom.size() - 1)).as("iteration %d bottom", iteration)
                    .isEqualTo(THUMB);
            List<String> top = thumbRows(state.withScroll(max), width, height);
            assertThat(top.get(0)).as("iteration %d top", iteration).isEqualTo(THUMB);

            int scroll = random.nextInt(max + 1);
            List<String> rows = thumbRows(state.withScroll(scroll), width, height);
            int first = rows.indexOf(THUMB);
            int last = rows.lastIndexOf(THUMB);
            assertThat(first).as("iteration %d scroll %d thumb", iteration, scroll)
                    .isNotNegative().isLessThan(rows.size());
            for (int row = first; row <= last; row++) {
                assertThat(rows.get(row)).as("iteration %d row %d contiguous", iteration, row)
                        .isEqualTo(THUMB);
            }
            assertThat(rows.subList(0, first)).as("iteration %d above", iteration)
                    .allMatch(TRACK::equals);
            assertThat(rows.subList(last + 1, rows.size())).as("iteration %d below", iteration)
                    .allMatch(TRACK::equals);
        }
        assertThat(overflowing).as("the run must exercise overflowing states").isPositive();
    }

    @Test
    void scrollingPastTheEndIsTheSameAsTheLastPage() {
        Random random = new Random(89L);
        for (int iteration = 0; iteration < 400; iteration++) {
            Harness.Model state = randomState(random);
            int width = state.viewport().cols();
            int height = state.viewport().rows();
            int max = HarnessProjection.maxScroll(state);
            assertThat(HarnessProjection.maxScroll(state.withScroll(max + 100 + random.nextInt(50))))
                    .as("iteration %d max is scroll independent", iteration).isEqualTo(max);
            List<StyledText> clamped =
                    HarnessProjection.window(
                            (state.withScroll(max + 1_000)).session(),
                            width,
                            HarnessProjection.allocate(height, state.editorRows()).transcript());
            assertThat(clamped)
                    .as("iteration %d clamped window", iteration)
                    .isEqualTo(
                            HarnessProjection.window(
                                    (state.withScroll(max)).session(),
                                    width,
                                    HarnessProjection.allocate(height, state.editorRows())
                                            .transcript()));
            assertThat(HarnessProjection.visiblePlainText(state.withScroll(max + 1_000)))
                    .as("iteration %d clamped copy", iteration)
                    .doesNotContain(TRACK).doesNotContain(THUMB);
        }
    }
}
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

/** The frame, its transcript window, and its scroll bound hold for any content and viewport. */
class HarnessProjectionPropertyTest {

    private static Harness.Model randomState(Random random) {
        List<HarnessEntry> entries = new ArrayList<>();
        int count = random.nextInt(30);
        for (int i = 0; i < count; i++) {
            entries.add(switch (random.nextInt(5)) {
                case 0 -> new HarnessEntry.User("user " + i);
                case 1 -> new HarnessEntry.Assistant("assistant " + i);
                case 2 -> new HarnessEntry.Reasoning("thinking " + i);
                case 3 -> new HarnessEntry.Notice("notice " + i);
                default -> new HarnessEntry.Tool("$ tool " + i, "out\nput " + i,
                        random.nextBoolean(),
                        random.nextBoolean() ? HarnessEntry.ToolStatus.SUCCESS
                                : HarnessEntry.ToolStatus.PENDING);
            });
        }
        List<String> pending = new ArrayList<>();
        int queued = random.nextInt(4) == 0 ? 1 + random.nextInt(8) : 0;
        for (int i = 0; i < queued; i++) {
            pending.add("queued prompt " + i);
        }
        int rows = 1 + random.nextInt(24);
        int cols = 1 + random.nextInt(60);
        return Harness.Model.initial(new Size(rows, cols), "~/braxton", List.of())
                .withEntries(entries)
                .withPending(pending)
                .withStatus(Session.Status.READY)
                .withViewport(new Size(rows, cols))
                .withDraft(Editor.Model.of("d".repeat(random.nextInt(6))));
    }

    @Test
    void everyFrameIsExactlyItsViewportSize() {
        Random random = new Random(41L);
        for (int iteration = 0; iteration < 400; iteration++) {
            Harness.Model state = randomState(random);
            int width = state.viewport().cols();
            int height = state.viewport().rows();
            HarnessProjection.Layout layout = HarnessProjection.layout(state);
            assertThat(layout.rows()).as("iteration %d layout", iteration).isEqualTo(height);
            List<String> lines = HarnessView.renderLines(state);
            assertThat(lines).as("iteration %d rows", iteration).hasSize(height);
            for (int row = 0; row < lines.size(); row++) {
                assertThat(TextMeasure.columns(lines.get(row)))
                        .as("iteration %d row %d of %d", iteration, row, width)
                        .isLessThanOrEqualTo(width);
            }
        }
    }

    @Test
    void everyWindowRowIsExactlyTheViewportWidth() {
        Random random = new Random(53L);
        for (int iteration = 0; iteration < 400; iteration++) {
            Harness.Model state = randomState(random);
            int width = state.viewport().cols();
            int rows = HarnessProjection.layout(state).body();
            List<StyledText> window = HarnessProjection.window(state.session(), width, rows);
            assertThat(window).as("iteration %d size", iteration).hasSize(rows);
            for (int row = 0; row < window.size(); row++) {
                assertThat(TextMeasure.columns(window.get(row).plainText()))
                        .as("iteration %d row %d of %d", iteration, row, width)
                        .isEqualTo(width);
            }
        }
    }

    @Test
    void theQueuedBandNeverDisplacesTheFrame() {
        Random random = new Random(61L);
        int bounded = 0;
        for (int iteration = 0; iteration < 400; iteration++) {
            Harness.Model state = randomState(random);
            int width = state.viewport().cols();
            HarnessProjection.Layout layout = HarnessProjection.layout(state);
            List<StyledText> band =
                    HarnessProjection.queuedBand(state, width, layout.queued());
            assertThat(band.size())
                    .as("iteration %d band", iteration)
                    .isLessThanOrEqualTo(layout.queued());
            if (!state.pending().isEmpty() && layout.queued() > 0) {
                bounded++;
                assertThat(band).as("iteration %d names the queued prompts", iteration).isNotEmpty();
            }
            assertThat(layout.rows()).as("iteration %d layout", iteration)
                    .isEqualTo(state.viewport().rows());
        }
        assertThat(bounded).as("the band must be exercised").isPositive();
    }

    @Test
    void scrollingPastTheEndIsTheSameAsTheLastPage() {
        Random random = new Random(89L);
        for (int iteration = 0; iteration < 400; iteration++) {
            Harness.Model state = randomState(random);
            int width = state.viewport().cols();
            int max = HarnessProjection.maxScroll(state);
            assertThat(HarnessProjection.maxScroll(state.withScroll(max + 100 + random.nextInt(50))))
                    .as("iteration %d max is scroll independent", iteration).isEqualTo(max);
            Harness.Model clamped = state.withScroll(max + 1_000);
            assertThat(HarnessProjection.visiblePlainText(clamped))
                    .as("iteration %d clamped copy", iteration)
                    .isEqualTo(HarnessProjection.visiblePlainText(state.withScroll(max)));
            assertThat(HarnessProjection.maxScroll(state.withScroll(0))).isEqualTo(max);
        }
    }
}

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
import io.github.dhoard.braxton.testing.AllocationProbe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class HarnessProjectionCostTest {

    @Test
    void offscreenTranscriptRowsAreNotStyledAndPadded() {
        List<HarnessEntry> entries = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            entries.add(new HarnessEntry.Assistant("word ".repeat(200)));
        }
        Harness.Model state =
                Harness.Model.initial(new Size(24, 80), "probe", List.of()).withEntries(entries);
        assertThat(
                        AllocationProbe.bytes(
                                () ->
                                        HarnessProjection.window(
                                                (state).session(),
                                                80,
                                                HarnessProjection.allocate(24, 1).transcript())))
                .isLessThan(3_000_000);
    }

    @Test
    void unscrolledAndScrolledWindowsRetainTheFullProjectionContent() {
        Harness.Model base =
                Harness.Model.initial(new Size(24, 80), "probe", List.of())
                        .withEntries(
                                List.of(
                                        new HarnessEntry.User("界🙂 ".repeat(20)),
                                        new HarnessEntry.Assistant("word ".repeat(100)),
                                        new HarnessEntry.Reasoning("thinking\nmore"),
                                        new HarnessEntry.Notice("notice"),
                                        new HarnessEntry.Failure("failed"),
                                        new HarnessEntry.Tool(
                                                "pending",
                                                "",
                                                false,
                                                HarnessEntry.ToolStatus.PENDING),
                                        new HarnessEntry.Tool(
                                                "success",
                                                "out\n".repeat(10),
                                                true,
                                                HarnessEntry.ToolStatus.SUCCESS),
                                        new HarnessEntry.Tool(
                                                "failed",
                                                "error\n",
                                                false,
                                                HarnessEntry.ToolStatus.ERROR)));
        for (int width : new int[]{1, 2, 8, 80}) {
            for (int height : new int[]{1, 8, 24}) {
                for (int scroll : new int[]{0, 1, 10, 1000}) {
                    Harness.Model state =
                            base.withViewport(new Size(height, width)).withScroll(scroll);
                    List<StyledText> full = HarnessProjection.allRows(state, width);
                    int rows = HarnessProjection.allocate(height, 1).transcript();
                    int end = full.size() - Math.min(scroll, Math.max(0, full.size() - rows));
                    int start = Math.max(0, end - rows);
                    String expected = rows == 0 ? "" : String.join("\n", full.subList(start, end).stream()
                            .map(text -> text.plainText().stripTrailing()).toList()).stripTrailing();
                    assertThat(HarnessProjection.visiblePlainText(state)).isEqualTo(expected);
                    assertThat(
                                    HarnessProjection.window(
                                            (state).session(),
                                            width,
                                            HarnessProjection.allocate(height, 1).transcript()))
                            .hasSize(rows);
                }
            }
        }
    }
}

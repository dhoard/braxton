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

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.widget.EditorState;

/** The production view paints exactly the viewport height and is deterministic. */
class HarnessViewDeterminismTest {

    @Test
    void everyViewportRendersExactlyItsHeightTwiceTheSame() {
        for (int rows : new int[] {4, 8, 20, 40}) {
            for (int cols : new int[] {10, 40, 120}) {
                HarnessState state = new HarnessState(HarnessState.Status.READY, List.of(),
                        HarnessTranscript.Profile.DEFAULT, HarnessTranscript.Usage.EMPTY, 1_000,
                        List.of(), 0, EditorState.of("draft"), 0, List.of(), null, 0L, 0L, -1L, 0L,
                        0L, new Size(rows, cols), "", "~/project");
                List<String> first = HarnessView.renderLines(state);
                List<String> second = HarnessView.renderLines(state);
                assertThat(first).as("%dx%d", rows, cols).hasSize(rows).isEqualTo(second);
                assertThat(first).allSatisfy(line ->
                        assertThat(io.github.dhoard.braxton.style.TextMeasure.columns(line))
                                .isLessThanOrEqualTo(cols));
            }
        }
    }
}

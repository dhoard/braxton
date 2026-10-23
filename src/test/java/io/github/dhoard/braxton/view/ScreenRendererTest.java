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

package io.github.dhoard.braxton.view;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;

/** Headless rendering measures with the same policy it lays out with. */
class ScreenRendererTest {

    @Test
    void columnsUsesTheConfiguredWidthPolicy() {
        assertThat(new ScreenRenderer(WidthPolicy.ONE_COLUMN).columns("\u65E5\u672C")).isEqualTo(2);
        assertThat(new ScreenRenderer(WidthPolicy.PINNED).columns("\u65E5\u672C")).isEqualTo(4);
    }

    @Test
    void measurementAgreesWithLayoutUnderTheConfiguredPolicy() {
        ScreenRenderer oneColumn = new ScreenRenderer(WidthPolicy.ONE_COLUMN);
        String text = "\u65E5\u672C";
        assertThat(oneColumn.plainText(Views.text(text), new Viewport(1, 2))).isEqualTo(text);
        assertThat(oneColumn.columns(text)).isEqualTo(2);
    }

    @Test
    void theHeadlessEntryPointsDescribeOneScreen() {
        // ScreenRenderer documents four views of the same layout: the screen, one string per row
        // with trailing blanks trimmed, the rows joined with newlines, and the policy-aware column
        // count. They must agree for every viewport.
        ScreenRenderer renderer = new ScreenRenderer();
        String text = "a b\n\u65E5\u672C c";
        io.github.dhoard.braxton.view.View<String> view = io.github.dhoard.braxton.view.Views.column(
                io.github.dhoard.braxton.view.Views.padding(1,
                        io.github.dhoard.braxton.view.Views.text(text)));
        for (io.github.dhoard.braxton.layout.Viewport viewport : java.util.List.of(
                new io.github.dhoard.braxton.layout.Viewport(1, 1),
                new io.github.dhoard.braxton.layout.Viewport(3, 5),
                new io.github.dhoard.braxton.layout.Viewport(6, 20))) {
            io.github.dhoard.braxton.view.RenderedScreen screen = renderer.screen(view, viewport);
            assertThat(screen.rows()).as("rows").isEqualTo(viewport.rows());
            assertThat(screen.cols()).as("cols").isEqualTo(viewport.cols());
            java.util.List<String> lines = renderer.plainLines(view, viewport);
            assertThat(lines).hasSize(viewport.rows());
            assertThat(lines).isEqualTo(screen.plainLines());
            assertThat(renderer.plainText(view, viewport)).isEqualTo(String.join("\n", lines));
            assertThat(lines).allSatisfy(line -> {
                assertThat(TextMeasure.columns(line)).as("line [%s]", line)
                        .isLessThanOrEqualTo(viewport.cols());
                assertThat(line).as("trailing blanks are trimmed").isEqualTo(line.stripTrailing());
            });
        }
        io.github.dhoard.braxton.view.View<String> spaced = io.github.dhoard.braxton.view.Views.text(
                "a  b");
        assertThat(renderer.plainLines(spaced,
                new io.github.dhoard.braxton.layout.Viewport(1, 6)))
                .as("interior blanks are kept as columns").containsExactly("a  b");
    }
}

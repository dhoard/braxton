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

import java.util.List;

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
                // Only U+0020 is padding, so only U+0020 is trimmed from the end of a row.
                assertThat(line).as("trailing blanks are trimmed").doesNotEndWith(" ");
            });
        }
        io.github.dhoard.braxton.view.View<String> spaced = io.github.dhoard.braxton.view.Views.text(
                "a  b");
        assertThat(renderer.plainLines(spaced,
                new io.github.dhoard.braxton.layout.Viewport(1, 6)))
                .as("interior blanks are kept as columns").containsExactly("a  b");
    }

    @Test
    void bothPlainViewsKeepAPaintedUnicodeSpaceAtTheRowEnd() {
        // Every padding path pads with U+0020, so a row-final Unicode space other than a blank is a
        // painted cell: the cell view and both plain-text views of one screen must report it.
        ScreenRenderer renderer = new ScreenRenderer();
        View<String> view = Views.column(
                Views.text("first"),
                Views.text("\u3000"),
                Views.text("last"));
        Viewport viewport = new Viewport(3, 8);
        List<String> lines = renderer.plainLines(view, viewport);
        RenderedScreen screen = renderer.screen(view, viewport);
        assertThat(lines).containsExactly("first", "\u3000", "last");
        assertThat(screen.plainLines()).as("one screen, two views").isEqualTo(lines);
        assertThat(screen.plainText()).as("rows joined with newlines")
                .isEqualTo(String.join("\n", lines));
        assertThat(screen.cell(1, 0).text()).isEqualTo("\u3000");
        assertThat(screen.cell(1, 0).columns()).isEqualTo(2);
        assertThat(screen.isContinuation(1, 1)).isTrue();
    }

    @Test
    void thePlainViewsTrimBlankPaddingAfterAPaintedUnicodeSpace() {
        // Blank padding after a painted EM SPACE is still trimmed, while the EM SPACE itself stays.
        ScreenRenderer renderer = new ScreenRenderer();
        View<String> view = Views.text("Total\u2003");
        Viewport viewport = new Viewport(1, 8);
        List<String> lines = renderer.plainLines(view, viewport);
        RenderedScreen screen = renderer.screen(view, viewport);
        assertThat(lines).containsExactly("Total\u2003");
        assertThat(screen.plainLines()).as("one screen, two views").isEqualTo(lines);
        assertThat(screen.plainText()).isEqualTo("Total\u2003");
        assertThat(screen.cell(0, 5).text()).isEqualTo("\u2003");
        assertThat(screen.cell(0, 6).text()).isEmpty();
    }
}

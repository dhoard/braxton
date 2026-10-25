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

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Style;

import org.junit.jupiter.api.Test;

class RenderedScreenTest {
    @Test
    void snapshotsKeepActualWideCellsAndCannotBeMutated() {
        var snapshot = new ScreenRenderer().screen(Views.text("界x"), new Viewport(1, 4));
        assertThat(snapshot.cell(0, 0).columns()).isEqualTo(2);
        assertThat(snapshot.cell(0, 1).continuation()).isTrue();
        assertThat(snapshot.cell(0, 1).columns()).isZero();
        assertThat(snapshot.cell(0, 2).text()).isEqualTo("x");
        assertThat(snapshot.plainText()).isEqualTo("界x");
        assertThatThrownBy(() -> snapshot.cells().getFirst().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.cells().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotCopiesEachInputCollection() {
        var row = new java.util.ArrayList<RenderedScreen.Cell>();
        row.add(new RenderedScreen.Cell("x", Style.NONE, null, 1, false));
        var regions = new java.util.ArrayList<RenderedScreen.Region>();
        var snapshot = new RenderedScreen(1, 1, java.util.List.of(row), regions);
        row.clear();
        regions.clear();
        assertThat(snapshot.plainText()).isEqualTo("x");
    }

    @Test
    void plainViewsTrimOnlyBlankPaddingAndKeepPaintedUnicodeSpaces() {
        // U+0020 is the only character any padding path paints, so it is the only character the
        // plain-text views may drop from the end of a row; a row-final Unicode space is painted
        // content and must survive, exactly as Screen.plainLine reports it.
        var painted = new java.util.ArrayList<RenderedScreen.Cell>();
        painted.add(new RenderedScreen.Cell("a", Style.NONE, null, 1, false));
        painted.add(new RenderedScreen.Cell("\u3000", Style.NONE, null, 2, false));
        painted.add(new RenderedScreen.Cell("", Style.NONE, null, 0, true));
        var padded = new java.util.ArrayList<RenderedScreen.Cell>();
        padded.add(new RenderedScreen.Cell("x", Style.NONE, null, 1, false));
        padded.add(new RenderedScreen.Cell(" ", Style.NONE, null, 1, false));
        padded.add(new RenderedScreen.Cell(" ", Style.NONE, null, 1, false));
        var snapshot = new RenderedScreen(2, 3, java.util.List.of(painted, padded),
                java.util.List.of());
        assertThat(snapshot.plainLine(0)).isEqualTo("a\u3000");
        assertThat(snapshot.plainLine(1)).as("blank padding is still trimmed").isEqualTo("x");
        assertThat(snapshot.plainLines()).containsExactly("a\u3000", "x");
        assertThat(snapshot.plainText()).isEqualTo("a\u3000\nx");
    }
}

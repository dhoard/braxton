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

package io.github.dhoard.braxton.benchmark;

import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Box;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.Table;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared view-tree fixtures. The shape mirrors what an application actually rebuilds per frame: a
 * title, a bordered status box, a table of transcript rows, a focused editor and a growing filler.
 */
final class BenchViews {

    private BenchViews() {
    }

    /**
     * A transcript-like tree with {@code rows} table rows.
     *
     * @param rows table rows to include; each row is three cells
     * @param draft the editor draft
     */
    static View<Object> transcript(int rows, String draft) {
        Table table = Table.of();
        for (int i = 0; i < rows; i++) {
            table.row(StyledText.plain("turn " + i), StyledText.plain(BenchCorpus.build("ascii", 24)),
                    StyledText.plain("ok"));
        }
        List<View<Object>> items = new ArrayList<>();
        items.add(Views.text("Braxton benchmark"));
        items.add(Box.of("status: idle").title("status").view(40));
        items.add(table.view());
        items.add(
                new Editor(Editor.Config.defaults())
                        .view(
                                (Editor.Model.of(draft)).withFocus(true),
                                new io.github.dhoard.braxton.mvu.ViewContext(
                                        io.github.dhoard.braxton.layout.Viewport.EMPTY))
                        .map(action -> action));
        items.add(Views.grow(Views.blank()));
        return Views.column(items);
    }

    /** Parses the {@code rowsCols} {@code @Param} shape, for example {@code 40x120}. */
    static Viewport viewport(String spec) {
        int at = spec.indexOf('x');
        if (at <= 0) {
            throw new IllegalArgumentException("expected <rows>x<cols>, got: " + spec);
        }
        return new Viewport(Integer.parseInt(spec.substring(0, at)),
                Integer.parseInt(spec.substring(at + 1)));
    }
}

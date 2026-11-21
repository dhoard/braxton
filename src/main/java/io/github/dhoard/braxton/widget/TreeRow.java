/*
 * Copyright (c) 2026-present Douglas Hoard
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

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.util.Objects;

/**
 * One row of a {@link Tree}: the disclosure marker and the label, painted in the live theme.
 *
 * <p>It is a leaf so the row is genuinely themed: the element engine hands a {@link ThemedWidget}
 * the theme of the frame it is painting, so a live theme switch repaints every row without the
 * tree's model changing. A row paints its own background, because a selection is a whole row and not
 * only the cells its label happens to occupy.</p>
 *
 * @param <Msg> the message type of the tree the row is placed in
 */
final class TreeRow<Msg> implements ThemedWidget<Tree.Row, Msg> {

    private final Tree.Appearance appearance;

    /**
     * A painter for one tree's rows.
     *
     * @param appearance the marker glyphs and the indent; required
     */
    TreeRow(Tree.Appearance appearance) {
        this.appearance = Objects.requireNonNull(appearance, "appearance");
    }

    /**
     * One row: as wide as the marker and label need, inside what is offered.
     */
    @Override
    public Size measure(Size max, Tree.Row row) {
        if (row == null || max.rows() <= 0 || max.cols() <= 0) {
            return new Size(0, 0);
        }
        int wanted = row.labelColumn(appearance) + TextMeasure.columns(label(row));
        return new Size(Math.min(1, max.rows()), Math.min(max.cols(), wanted));
    }

    /**
     * The whole width it is offered: a row is one line of the pane, so a selection covers the row
     * rather than the cells its label happens to use.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Tree.Row row) {
        return new Rect(
                available.row(),
                available.col(),
                Math.min(available.rows(), Math.max(0, measured.rows())),
                available.cols());
    }

    /**
     * Paints the selected row's background, then the marker and the label in the theme's styles.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Tree.Row row, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (row == null || bounds.isEmpty()) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        Style text = resolved.style(ThemeToken.TEXT);
        Style marker = resolved.style(ThemeToken.MUTED);
        if (row.selected()) {
            surface.fill(bounds, resolved.style(ThemeToken.SELECTED));
            if (row.focused()) {
                // Focus is painted on the row's content rather than on the selection's background:
                // the theme's focus style is a foreground style, and filling the row with it would
                // color the cells the label does not cover.
                Style focus = resolved.style(ThemeToken.FOCUS);
                text = text.merge(focus);
                marker = marker.merge(focus);
            }
        }
        surface.write(bounds, line(row, marker, text), WidthPolicy.PINNED);
    }

    /**
     * The marker cell, its separating column, and the label, each in its own style.
     */
    private StyledText line(Tree.Row row, Style markerStyle, Style labelStyle) {
        String indent = " ".repeat(Math.max(0, row.depth()) * appearance.indent());
        String glyph = row.expanded()
                ? appearance.expanded()
                : row.node().expandable() ? appearance.collapsed() : appearance.leaf();
        StyledText prefix = StyledText.of(indent + glyph + " ", markerStyle);
        return prefix.append(RowText.styled(label(row), labelStyle));
    }

    /**
     * A node's label flattened to the single row a tree paints it on.
     */
    private static StyledText label(Tree.Row row) {
        return RowText.singleRow(row.node().label());
    }
}

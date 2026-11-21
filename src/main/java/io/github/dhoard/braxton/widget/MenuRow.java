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
 * One row of a {@link Menu}: an item with its right-aligned hint, or a separator.
 *
 * <p>It is a leaf so the row is genuinely themed: the element engine hands a {@link ThemedWidget} the
 * theme of the frame it is painting, so a live theme switch repaints every row without the menu's
 * model changing. The highlighted row paints its own background, because a highlight is a whole row
 * and not only the cells its label happens to occupy.</p>
 *
 * @param <Msg> the message type of the menu the row is placed in
 */
final class MenuRow<Msg> implements ThemedWidget<Menu.Row, Msg> {

    /**
     * One row: as wide as the label and hint need, inside what is offered.
     */
    @Override
    public Size measure(Size max, Menu.Row row) {
        if (row == null || max.rows() <= 0 || max.cols() <= 0) {
            return new Size(0, 0);
        }
        int wanted = columns(row);
        return new Size(1, Math.min(max.cols(), wanted));
    }

    /**
     * The whole width it is offered: a row is one line of the menu, so a highlight covers the row
     * rather than the cells its label happens to use, and a hint can be pushed to the right edge.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Menu.Row row) {
        return new Rect(available.row(), available.col(), Math.min(available.rows(), 1), available.cols());
    }

    /**
     * Paints the highlight, then the label and the hint in the theme's styles.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Menu.Row row, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (row == null || bounds.isEmpty() || bounds.rows() <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        if (row.entry() instanceof Menu.Separator) {
            surface.write(
                    bounds,
                    StyledText.of(Menu.SEPARATOR.repeat(bounds.cols()), resolved.style(ThemeToken.BORDER)),
                    WidthPolicy.PINNED);
            return;
        }
        Menu.Item item = (Menu.Item) row.entry();
        Style label = item.enabled() ? resolved.style(ThemeToken.TEXT) : resolved.style(ThemeToken.DISABLED);
        Style hint = item.enabled() ? resolved.style(ThemeToken.MUTED) : resolved.style(ThemeToken.DISABLED);
        if (row.highlighted()) {
            surface.fill(bounds, resolved.style(ThemeToken.SELECTED));
            if (row.focused()) {
                // Focus colors the row's content rather than its selection's background: the theme's
                // focus style is a foreground style, and filling a row with it would color the cells
                // the label does not cover.
                Style focus = resolved.style(ThemeToken.FOCUS);
                label = label.merge(focus);
                hint = hint.merge(focus);
            }
        }
        StyledText text = RowText.singleRow(item.label());
        surface.write(bounds, RowText.styled(text, label), WidthPolicy.PINNED);
        StyledText right = RowText.singleRow(item.hint());
        int hintColumns = right.isEmpty() ? 0 : TextMeasure.columns(right.plainText());
        int labelColumns = text.columns();
        // The hint keeps one separating column from the label; a row too narrow for both keeps the
        // label, because a clipped hint says less than a clipped label.
        if (hintColumns > 0 && labelColumns + 1 + hintColumns <= bounds.cols()) {
            surface.writeLine(
                    bounds.row(),
                    bounds.col() + bounds.cols() - hintColumns,
                    hintColumns,
                    RowText.styled(right, hint),
                    WidthPolicy.PINNED);
        }
    }

    /**
     * The columns a row asks for: its label, one separator, and its hint.
     */
    private static int columns(Menu.Row row) {
        if (row.entry() instanceof Menu.Separator) {
            return 1;
        }
        Menu.Item item = (Menu.Item) row.entry();
        int label = RowText.singleRow(item.label()).columns();
        StyledText hint = RowText.singleRow(item.hint());
        return hint.isEmpty() ? label : label + 1 + TextMeasure.columns(hint.plainText());
    }
}

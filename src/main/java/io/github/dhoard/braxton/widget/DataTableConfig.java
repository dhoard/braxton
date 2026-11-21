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

import static io.github.dhoard.braxton.widget.DataTable.DEFAULT_HEIGHT;
import static io.github.dhoard.braxton.widget.DataTable.DEFAULT_WIDTH;

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.widget.DataTable.Bar;
import io.github.dhoard.braxton.widget.DataTable.Mode;
import io.github.dhoard.braxton.widget.DataTable.Theme;

/**
 * Immutable table definition.
 *
 * <p>It holds what does not change while the table runs: the content it starts with, whether a
 * header is painted, how cells are painted, the fallback geometry and the rows and columns the
 * application paints outside the table.</p>
 *
 * @param content the initial columns and rows
 * @param mode the selection mode the state starts in
 * @param header whether a header line is painted
 * @param theme how header cells, rows and cells are painted
 * @param activeStyle the style merged over the selection while the table is focused
 * @param inactiveStyle the style merged over the selection while it is not
 * @param height the rows used before the terminal's own geometry arrives
 * @param width the cells used before the terminal's own geometry arrives
 * @param reservedRows the rows the application paints outside the table
 * @param reservedColumns the columns the application paints outside the table
 * @param scrollbar the scrollbar the table paints on its right edge; never null
 */
public record DataTableConfig(
        DataTableContent content,
        Mode mode,
        boolean header,
        Theme theme,
        Style activeStyle,
        Style inactiveStyle,
        int height,
        int width,
        int reservedRows,
        int reservedColumns,
        Bar scrollbar) {

    /**
     * Keeps the definition paintable.
     */
    public DataTableConfig {
        content = content == null ? DataTableContent.empty() : content;
        mode = mode == null ? Mode.ROW : mode;
        theme = theme == null ? Theme.plain() : theme;
        activeStyle = activeStyle == null ? Style.NONE : activeStyle;
        inactiveStyle = inactiveStyle == null ? Style.NONE : inactiveStyle;
        height = Math.max(0, height);
        width = Math.max(0, width);
        reservedRows = Math.max(0, reservedRows);
        reservedColumns = Math.max(0, reservedColumns);
        scrollbar = scrollbar == null ? Bar.none() : scrollbar;
    }

    /**
     * The same definition with no scrollbar.
     *
     * @param content the initial columns and rows
     * @param mode the selection mode
     * @param header whether a header line is painted
     * @param theme how header cells, rows and cells are painted
     * @param activeStyle the style merged over the selection while focused
     * @param inactiveStyle the style merged over the selection while not focused
     * @param height the fallback height
     * @param width the fallback width
     * @param reservedRows the rows painted outside the table
     * @param reservedColumns the columns painted outside the table
     */
    public DataTableConfig(
            DataTableContent content,
            Mode mode,
            boolean header,
            Theme theme,
            Style activeStyle,
            Style inactiveStyle,
            int height,
            int width,
            int reservedRows,
            int reservedColumns) {
        this(
                content,
                mode,
                header,
                theme,
                activeStyle,
                inactiveStyle,
                height,
                width,
                reservedRows,
                reservedColumns,
                Bar.none());
    }

    /**
     * The same definition with a different scrollbar.
     *
     * @param value the scrollbar to paint; null is {@link Bar#none()}
     * @return the new definition
     */
    public DataTableConfig withScrollbar(Bar value) {
        return new DataTableConfig(
                content,
                mode,
                header,
                theme,
                activeStyle,
                inactiveStyle,
                height,
                width,
                reservedRows,
                reservedColumns,
                value);
    }

    /**
     * A row-selecting table with a header, an inverting selection and no content.
     *
     * @return the default definition
     */
    public static DataTableConfig defaults() {
        return new DataTableConfig(
                DataTableContent.empty(),
                Mode.ROW,
                true,
                Theme.plain(),
                Style.NONE.withInvert(),
                Style.NONE,
                DEFAULT_HEIGHT,
                DEFAULT_WIDTH,
                0,
                0);
    }

    /**
     * The same definition over different content.
     *
     * @param value the content
     * @return the new definition
     */
    public DataTableConfig withContent(DataTableContent value) {
        return new DataTableConfig(
                value,
                mode,
                header,
                theme,
                activeStyle,
                inactiveStyle,
                height,
                width,
                reservedRows,
                reservedColumns,
                scrollbar);
    }

    /**
     * The same definition starting in a different selection mode.
     *
     * @param value the mode
     * @return the new definition
     */
    public DataTableConfig withMode(Mode value) {
        return new DataTableConfig(
                content,
                value,
                header,
                theme,
                activeStyle,
                inactiveStyle,
                height,
                width,
                reservedRows,
                reservedColumns,
                scrollbar);
    }

    /**
     * The same definition with or without a header.
     *
     * @param value whether a header is painted
     * @return the new definition
     */
    public DataTableConfig withHeader(boolean value) {
        return new DataTableConfig(
                content,
                mode,
                value,
                theme,
                activeStyle,
                inactiveStyle,
                height,
                width,
                reservedRows,
                reservedColumns,
                scrollbar);
    }

    /**
     * The same definition with a different theme.
     *
     * @param value the theme; null is {@link Theme#plain()}
     * @return the new definition
     */
    public DataTableConfig withTheme(Theme value) {
        return new DataTableConfig(
                content,
                mode,
                header,
                value,
                activeStyle,
                inactiveStyle,
                height,
                width,
                reservedRows,
                reservedColumns,
                scrollbar);
    }

    /**
     * The same definition with different selection styles.
     *
     * @param active the style merged over a focused table's selection
     * @param inactive the style merged over an unfocused table's selection
     * @return the new definition
     */
    public DataTableConfig withSelectionStyles(Style active, Style inactive) {
        return new DataTableConfig(
                content, mode, header, theme, active, inactive, height, width, reservedRows, reservedColumns);
    }

    /**
     * The same definition with a different fallback height.
     *
     * @param value the fallback rows
     * @return the new definition
     */
    public DataTableConfig withHeight(int value) {
        return new DataTableConfig(
                content,
                mode,
                header,
                theme,
                activeStyle,
                inactiveStyle,
                value,
                width,
                reservedRows,
                reservedColumns,
                scrollbar);
    }

    /**
     * The same definition with a different fallback width.
     *
     * @param value the fallback cells
     * @return the new definition
     */
    public DataTableConfig withWidth(int value) {
        return new DataTableConfig(
                content,
                mode,
                header,
                theme,
                activeStyle,
                inactiveStyle,
                height,
                value,
                reservedRows,
                reservedColumns,
                scrollbar);
    }

    /**
     * The same definition with a different number of reserved rows.
     *
     * @param value the rows the application paints outside the table
     * @return the new definition
     */
    public DataTableConfig withReservedRows(int value) {
        return new DataTableConfig(
                content, mode, header, theme, activeStyle, inactiveStyle, height, width, value, reservedColumns);
    }

    /**
     * The same definition with a different number of reserved columns.
     *
     * @param value the columns the application paints outside the table
     * @return the new definition
     */
    public DataTableConfig withReservedColumns(int value) {
        return new DataTableConfig(
                content,
                mode,
                header,
                theme,
                activeStyle,
                inactiveStyle,
                height,
                width,
                reservedRows,
                value,
                scrollbar);
    }
}

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

import static io.github.dhoard.braxton.widget.RichList.MARKED;
import static io.github.dhoard.braxton.widget.RichList.MARKER;
import static io.github.dhoard.braxton.widget.RichList.MARKER_GAP;

import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.widget.RichList.Highlight;
import io.github.dhoard.braxton.widget.RichList.Item;
import io.github.dhoard.braxton.widget.RichList.Renderer;
import io.github.dhoard.braxton.widget.RichList.Row;
import java.util.ArrayList;
import java.util.List;

/**
 * The item renderer a list uses unless it is given another: a marker, a title, and a description
 * under it.
 *
 * <p>The marker names the selected item and is painted whether or not the list has focus, the
 * rule {@link Selector} follows. A highlighted part of the title carries the match style as well
 * as the title style, so a theme only has to say what a match looks like. The description is
 * optional and its rows are bounded, which is what lets an item be taller than one row without
 * being unbounded.</p>
 */
public final class RichListDefaultRenderer implements Renderer {

    /**
     * The item styles a {@link RichListDefaultRenderer} paints with.
     *
     * @param title the title of an unselected item
     * @param description the description of an unselected item
     * @param selectedTitle the title of the selected item
     * @param selectedDescription the description of the selected item
     * @param match the additional style of the part of a title the query matched
     */
    public record Styles(Style title, Style description, Style selectedTitle, Style selectedDescription, Style match) {

        /**
         * Substitutes no styling for a missing style.
         */
        public Styles {
            title = title == null ? Style.NONE : title;
            description = description == null ? Style.NONE : description;
            selectedTitle = selectedTitle == null ? Style.NONE : selectedTitle;
            selectedDescription = selectedDescription == null ? Style.NONE : selectedDescription;
            match = match == null ? Style.NONE : match;
        }

        /**
         * A plain title, a plain description, a bold selected title and an underlined match.
         *
         * @return the default styling
         */
        public static Styles defaults() {
            return new Styles(Style.NONE, Style.NONE, Style.NONE.withBold(), Style.NONE, Style.NONE.withUnderline());
        }

        /**
         * The same styling with a different title style.
         *
         * @param value the new style; null is no styling
         * @return the new styling
         */
        public Styles withTitle(Style value) {
            return new Styles(value, description, selectedTitle, selectedDescription, match);
        }

        /**
         * The same styling with a different description style.
         *
         * @param value the new style; null is no styling
         * @return the new styling
         */
        public Styles withDescription(Style value) {
            return new Styles(title, value, selectedTitle, selectedDescription, match);
        }

        /**
         * The same styling with a different selected-title style.
         *
         * @param value the new style; null is no styling
         * @return the new styling
         */
        public Styles withSelectedTitle(Style value) {
            return new Styles(title, description, value, selectedDescription, match);
        }

        /**
         * The same styling with a different selected-description style.
         *
         * @param value the new style; null is no styling
         * @return the new styling
         */
        public Styles withSelectedDescription(Style value) {
            return new Styles(title, description, selectedTitle, value, match);
        }

        /**
         * The same styling with a different match style.
         *
         * @param value the new style; null is no styling
         * @return the new styling
         */
        public Styles withMatch(Style value) {
            return new Styles(title, description, selectedTitle, selectedDescription, value);
        }
    }

    private final Styles styles;
    private final boolean showDescription;
    private final int maxDescriptionRows;

    /**
     * A renderer from its policy.
     *
     * @param styles the item styles; null is {@link Styles#defaults()}
     * @param showDescription whether a description is painted under the title
     * @param maxDescriptionRows the most description rows an item may occupy, breaks included;
     *     never negative
     */
    public RichListDefaultRenderer(Styles styles, boolean showDescription, int maxDescriptionRows) {
        if (maxDescriptionRows < 0) {
            throw new IllegalArgumentException("maxDescriptionRows must not be negative");
        }
        this.styles = styles == null ? Styles.defaults() : styles;
        this.showDescription = showDescription;
        this.maxDescriptionRows = showDescription ? maxDescriptionRows : 0;
    }

    /**
     * The default renderer: a title and one description row.
     *
     * @return the default renderer
     */
    public static RichListDefaultRenderer defaults() {
        return new RichListDefaultRenderer(Styles.defaults(), true, 1);
    }

    /**
     * The same policy with different item styles.
     *
     * @param value the new styles; null is the default styling
     * @return the new renderer
     */
    public RichListDefaultRenderer withStyles(Styles value) {
        return new RichListDefaultRenderer(value, showDescription, maxDescriptionRows);
    }

    /**
     * The same policy with a different description policy.
     *
     * @param show whether a description is painted
     * @param rows the most description rows an item may occupy; never negative
     * @return the new renderer
     */
    public RichListDefaultRenderer withDescription(boolean show, int rows) {
        return new RichListDefaultRenderer(styles, show, rows);
    }

    /**
     * One row for the title, then as many description rows as the description has, up to the
     * configured bound. An item's height therefore follows its own content.
     */
    @Override
    public int height(Item item) {
        return 1 + Math.min(maxDescriptionRows, descriptionLines(item));
    }

    @Override
    public List<StyledText> render(Row row) {
        Item item = row.item();
        Style titleStyle = row.selected() ? styles.selectedTitle() : styles.title();
        StyledText body = row.highlights().isEmpty()
                ? StyledText.of(item.title(), titleStyle)
                : source(item.title(), row.highlights(), titleStyle, styles.match());
        List<StyledText> rows = new ArrayList<>(1 + maxDescriptionRows);
        String marker = row.selected() ? MARKER : row.marked() ? MARKED : MARKER_GAP;
        rows.add(StyledText.concat(StyledText.of(marker, titleStyle), body));
        if (maxDescriptionRows > 0) {
            Style descriptionStyle = row.selected() ? styles.selectedDescription() : styles.description();
            List<String> lines = lines(item.description());
            for (int index = 0; index < Math.min(maxDescriptionRows, lines.size()); index++) {
                rows.add(StyledText.of(MARKER_GAP + lines.get(index), descriptionStyle));
            }
        }
        return rows;
    }

    /**
     * One title with the matched ranges in the match style: the title is split at the highlight
     * boundaries, which are cluster boundaries, so no styled run splits a grapheme.
     */
    private static StyledText source(String text, List<Highlight> highlights, Style title, Style match) {
        Style matched = title.merge(match);
        StyledText styled = StyledText.empty();
        int offset = 0;
        for (Highlight highlight : highlights) {
            int start = Math.max(offset, Math.min(highlight.start(), text.length()));
            int end = Math.max(start, Math.min(highlight.end(), text.length()));
            if (start > offset) {
                styled = styled.append(StyledText.of(text.substring(offset, start), title));
            }
            if (end > start) {
                styled = styled.append(StyledText.of(text.substring(start, end), matched));
            }
            offset = end;
        }
        if (offset < text.length()) {
            styled = styled.append(StyledText.of(text.substring(offset), title));
        }
        return styled;
    }

    /**
     * How many rows an item's description occupies.
     */
    private static int descriptionLines(Item item) {
        return item.description().isEmpty() ? 0 : lines(item.description()).size();
    }

    /**
     * The logical lines of a value, with the platform's own breaks normalized first.
     */
    private static List<String> lines(String value) {
        return List.of(LineBreaks.normalize(value).split("\n", -1));
    }
}

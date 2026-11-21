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

package io.github.dhoard.braxton.line;

import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.terminal.TerminalEvent;
import io.github.dhoard.braxton.terminal.TerminalException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A list a prompt puts in front of the user: a question row and the options it offers.
 *
 * <p>It is the one implementation behind {@link SelectPrompt} and {@link MultiSelectPrompt}, because
 * the two differ in exactly two things - what {@code space} means and what the prompt answers - while
 * the window arithmetic, the movement, the markers and the painting are the same. A list is a value:
 * every gesture answers a new list, and a gesture that cannot move answers the list it was called on,
 * so a prompt that repaints only when the value changed costs nothing for a key that changes
 * nothing.</p>
 *
 * <p>The options are prepared once, in the canonical constructor: each is flattened to one row, so a
 * label can bring neither a line break nor a control character into a cell, and a blank label is
 * dropped because a row that says nothing cannot be chosen. The window shows at most {@code rows}
 * options and always holds the highlight, so the list scrolls by the least it can rather than
 * jumping a page whenever the highlight reaches an edge. The question row says which gestures the
 * prompt answers, because a list prompt's keys are not otherwise discoverable.</p>
 *
 * <p>Rows are painted the way the widget family paints them: the highlighted row is filled with the
 * theme's selection across its whole width and its content takes the theme's focus style, a marked
 * option's box is accented, and an unmarked one's is muted. A marker is deliberate in both lists -
 * a monochrome terminal shows a style as nothing, and a highlight that cannot say which row it is on
 * is not a highlight.</p>
 */
final class PromptList {

    /**
     * The single-choice hint painted after the question.
     */
    private static final String SINGLE_HINT = "(enter chooses, esc cancels)";

    /**
     * The multiple-choice hint painted after the question.
     */
    private static final String MULTIPLE_HINT = "(space marks, enter chooses, esc cancels)";

    /**
     * The box a marked option carries.
     */
    private static final String MARKED = "[\u2713]";

    /**
     * The box an unmarked option carries.
     */
    private static final String UNMARKED = "[ ]";

    /**
     * The marker the highlighted option carries in a single-choice list.
     */
    private static final String ARROW = "\u276F";

    private final String question;
    private final List<String> options;
    private final Set<Integer> marked;
    private final int rows;
    private final int selected;
    private final int offset;
    private final boolean multiple;

    private PromptList(
            String question,
            List<String> options,
            Set<Integer> marked,
            int rows,
            int selected,
            int offset,
            boolean multiple) {
        this.question = LineText.singleRow(question);
        this.options = List.copyOf(options);
        this.rows = Math.max(1, rows);
        this.multiple = multiple;
        int size = this.options.size();
        int shownRows = Math.min(this.rows, size);
        int highlight = size == 0 ? 0 : Math.max(0, Math.min(selected, size - 1));
        // The window holds the highlight and stays inside the list: the least offset that satisfies
        // both is what a reader sees as "the list scrolled one row".
        int lowest = Math.max(0, highlight - shownRows + 1);
        int highest = Math.max(0, Math.min(size - shownRows, highlight));
        int shown = Math.max(lowest, Math.min(offset, highest));
        Set<Integer> kept = new LinkedHashSet<>();
        for (int index : marked) {
            if (index >= 0 && index < size) {
                kept.add(index);
            }
        }
        this.selected = highlight;
        this.offset = shown;
        this.marked = Set.copyOf(kept);
    }

    /**
     * The list as it opens: nothing marked, the first option highlighted.
     *
     * @param question the question painted above the options; null is none
     * @param options the labels to offer; null or an all-blank list offers nothing
     * @param rows the option rows the list shows at once; a value below one shows one row
     * @param multiple whether the list marks several options instead of choosing one
     * @return the list
     */
    static PromptList of(String question, List<String> options, int rows, boolean multiple) {
        return new PromptList(question, prepare(options), Set.of(), rows, 0, 0, multiple);
    }

    /**
     * The question painted above the options.
     */
    String question() {
        return question;
    }

    /**
     * The options as they are shown, in order.
     */
    List<String> options() {
        return options;
    }

    /**
     * The rows the list shows at once.
     */
    int rows() {
        return rows;
    }

    /**
     * The rows the list shows: its options, up to the window's height.
     */
    int visible() {
        return Math.min(rows, options.size());
    }

    /**
     * The rows the whole prompt needs: the question, then the options it shows.
     */
    int height() {
        return 1 + visible();
    }

    /**
     * The index of the highlighted option.
     */
    int selected() {
        return selected;
    }

    /**
     * The index of the first shown option.
     */
    int offset() {
        return offset;
    }

    /**
     * Whether an option is marked.
     *
     * @param index the option's index
     * @return true when it is marked
     */
    boolean marked(int index) {
        return marked.contains(index);
    }

    /**
     * The highlighted option, which is what a single-choice list answers with.
     */
    String selectedOption() {
        return options.get(selected);
    }

    /**
     * The marked options in the order the list holds them, which is what a multiple-choice list answers.
     */
    List<String> markedOptions() {
        List<String> chosen = new ArrayList<>(marked.size());
        for (int index = 0; index < options.size(); index++) {
            if (marked.contains(index)) {
                chosen.add(options.get(index));
            }
        }
        return List.copyOf(chosen);
    }

    /**
     * The list one gesture leaves behind.
     *
     * <p>A movement clamps at both ends, so a key that cannot move answers the list it was called on
     * and the prompt repaints nothing. {@link PromptKeys.Command#TOGGLE} is ignored by a
     * single-choice list, whose key map never sends one.</p>
     *
     * @param command the gesture
     * @return the next list, or this one when nothing changed
     */
    PromptList moved(PromptKeys.Command command) {
        if (command == null || options.isEmpty()) {
            return this;
        }
        return switch (command) {
            case UP -> withSelected(selected - 1);
            case DOWN -> withSelected(selected + 1);
            case FIRST -> withSelected(0);
            case LAST -> withSelected(options.size() - 1);
            case PAGE_UP -> withSelected(selected - visible());
            case PAGE_DOWN -> withSelected(selected + visible());
            case TOGGLE -> toggle();
            case CHOOSE, CANCEL -> this;
        };
    }

    /**
     * Paints the question row and the options the window shows.
     *
     * @param surface the surface to paint on; required
     * @param cols the columns a row may use
     * @param theme the theme the rows' styles come from; null is the built-in dark theme
     * @param policy how a cluster nobody has assigned a width is measured; null is the pinned policy
     */
    void paint(Surface surface, int cols, Theme theme, WidthPolicy policy) {
        Objects.requireNonNull(surface, "surface");
        if (cols <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        WidthPolicy measured = policy == null ? WidthPolicy.PINNED : policy;
        surface.write(new Rect(0, 0, 1, cols), heading(resolved), measured);
        Style focus = resolved.style(ThemeToken.FOCUS);
        Style label = resolved.style(ThemeToken.TEXT);
        int markerColumns = markerColumns(measured);
        int start = markerColumns + 1;
        for (int index = offset; index < offset + visible(); index++) {
            int row = 1 + index - offset;
            boolean highlighted = index == selected;
            if (highlighted) {
                surface.fill(new Rect(row, 0, 1, cols), resolved.style(ThemeToken.SELECTED));
            }
            Style marker = resolved.style(marked(index) ? ThemeToken.ACCENT : ThemeToken.MUTED);
            Style rowLabel = label;
            if (highlighted) {
                // Focus colors the row's content rather than its selection's background: the
                // theme's focus style is a foreground style, and filling with it would color the
                // cells the label does not cover.
                marker = marker.merge(focus);
                rowLabel = label.merge(focus);
            }
            surface.write(
                    new Rect(row, 0, 1, Math.min(markerColumns, cols)),
                    StyledText.of(marker(index, highlighted), marker),
                    measured);
            if (start < cols) {
                surface.write(
                        new Rect(row, start, 1, cols - start), StyledText.of(options.get(index), rowLabel), measured);
            }
        }
    }

    /**
     * Runs the prompt until it is answered.
     *
     * <p>The region opens where the terminal's cursor is and closes either answer: a choice leaves
     * the final frame on screen, the rule {@link LineReader} follows for an accepted line, and an
     * abandoned prompt erases it. A list with nothing to offer asks nothing and answers nothing.</p>
     *
     * @param start the list to open
     * @param reader the reader that supplies the session, the theme and the measurement
     * @return the list as it stood when it was answered, or empty when it was abandoned
     * @throws TerminalException when the session cannot be read from or written to
     */
    static Optional<PromptList> ask(PromptList start, LineReader reader) throws TerminalException {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(reader, "reader");
        if (start.options.isEmpty()) {
            return Optional.empty();
        }
        KeyMap<PromptKeys.Command> keys = start.multiple ? PromptKeys.multiple() : PromptKeys.single();
        InlineRegion region = InlineRegion.open(reader.session(), reader.profile());
        PromptList current = start;
        present(region, current, reader);
        while (true) {
            Optional<TerminalEvent> event = reader.session().read();
            if (event.isEmpty()) {
                region.leave(false);
                return Optional.empty();
            }
            // A session reports exactly two kinds of event, so this is exhaustive by construction.
            switch (event.get()) {
                case TerminalEvent.Resize resize -> {
                    region.resize(resize.size().cols());
                    present(region, current, reader);
                }
                case TerminalEvent.Input input -> {
                    Optional<PromptKeys.Command> found = PromptKeys.command(keys, input.value());
                    if (found.isEmpty()) {
                        continue;
                    }
                    switch (found.get()) {
                        case CANCEL -> {
                            region.leave(false);
                            return Optional.empty();
                        }
                        case CHOOSE -> {
                            region.leave(true);
                            return Optional.of(current);
                        }
                        default -> {
                            PromptList next = current.moved(found.get());
                            if (next != current) {
                                current = next;
                                present(region, current, reader);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Paints the current list into a frame of the region's width.
     */
    private static void present(InlineRegion region, PromptList list, LineReader reader) throws TerminalException {
        int cols = region.columns();
        Screen screen = new Screen(list.height(), cols);
        list.paint(screen, cols, reader.theme(), reader.policy());
        region.present(screen, Optional.empty());
    }

    /**
     * The question and the hint its list answers, on one row.
     */
    private StyledText heading(Theme theme) {
        String hint = multiple ? MULTIPLE_HINT : SINGLE_HINT;
        StyledText head = question.isEmpty()
                ? StyledText.of(hint, theme.style(ThemeToken.MUTED))
                : StyledText.of(question, theme.style(ThemeToken.ACCENT))
                        .append("  ")
                        .append(StyledText.of(hint, theme.style(ThemeToken.MUTED)));
        return head;
    }

    /**
     * The marker an option carries: its box, or the arrow the highlight uses in a single-choice list.
     */
    private String marker(int index, boolean highlighted) {
        if (!multiple) {
            return highlighted ? ARROW : " ";
        }
        return marked(index) ? MARKED : UNMARKED;
    }

    /**
     * The columns the marker field reserves, so every label starts in the same one.
     */
    private int markerColumns(WidthPolicy policy) {
        if (!multiple) {
            return Math.max(1, TextMeasure.columns(ARROW, policy));
        }
        return Math.max(TextMeasure.columns(MARKED, policy), TextMeasure.columns(UNMARKED, policy));
    }

    /**
     * The same list with another highlight, keeping the window it already shows where it can.
     */
    private PromptList withSelected(int value) {
        int clamped = Math.max(0, Math.min(value, options.size() - 1));
        return clamped == selected ? this : new PromptList(question, options, marked, rows, clamped, offset, multiple);
    }

    /**
     * The same list with the highlighted option's mark flipped.
     */
    private PromptList toggle() {
        if (!multiple) {
            return this;
        }
        Set<Integer> flipped = new LinkedHashSet<>(marked);
        if (!flipped.remove(selected)) {
            flipped.add(selected);
        }
        return new PromptList(question, options, flipped, rows, selected, offset, multiple);
    }

    /**
     * Every option flattened to one row, with the blank ones dropped.
     *
     * <p>A prompt's value prepares its options here, so what it reports it offers is what it really
     * shows: one row per label, and no row that says nothing.</p>
     *
     * @param options the labels to prepare; null or an all-blank list offers nothing
     * @return the prepared options
     */
    static List<String> prepare(List<String> options) {
        if (options == null || options.isEmpty()) {
            return List.of();
        }
        List<String> prepared = new ArrayList<>(options.size());
        for (String option : options) {
            String row = LineText.singleRow(option);
            if (!row.isBlank()) {
                prepared.add(row);
            }
        }
        return List.copyOf(prepared);
    }
}

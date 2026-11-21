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

import static io.github.dhoard.braxton.widget.TextInput.CURSOR;
import static io.github.dhoard.braxton.widget.TextInput.UNBOUNDED;

import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.internal.text.Graphemes;
import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.widget.TextInput.Appearance;
import io.github.dhoard.braxton.widget.TextInput.Model;
import io.github.dhoard.braxton.widget.TextInput.Msg;
import java.util.List;
import java.util.Optional;

/**
 * Single-line cell projection and rendering.
 */
final class TextInputRendering {

    /**
     * How a row charges its clusters: a masked row charges one cell, a hidden one charges none.
     */
    static VisualRows.Cells paintCells(TextInputConfig opts) {
        return switch (opts.masking()) {
            case NONE -> VisualRows.Cells.NATURAL;
            case PASSWORD -> VisualRows.Cells.MASKED;
            case HIDDEN -> VisualRows.Cells.HIDDEN;
        };
    }

    /**
     * The prompt as the layout paints it, with its tabs expanded to the same four-column stops.
     */
    static String paintedPrompt(Appearance look) {
        return TabStops.expand(StyledText.plain(look.prompt())).plainText();
    }

    /**
     * The cell the prompt leaves open to the draft's first cluster.
     *
     * <p>A prompt ending in a base character and one ending in a tab's space open different cells:
     * the text view continues a stop's space only with the clusters that claim no column of their
     * own, while an ordinary last cluster takes a bare mark into its own cell. The projection the
     * caret is placed from reads the same cell the painting path does.</p>
     */
    static VisualRows.Open promptOpen(Appearance look) {
        List<String> clusters = TextMeasure.graphemes(paintedPrompt(look));
        if (clusters.isEmpty()) {
            return VisualRows.CLOSED;
        }
        return new VisualRows.Open(
                clusters.get(clusters.size() - 1), look.prompt().endsWith("\t"));
    }

    /**
     * The columns the field paints in.
     *
     * <p>The declared width is what the row scrolls for; the space the view is actually given wins
     * when it is narrower, so a resized terminal scrolls the draft rather than letting the layout
     * wrap it into a second row. A width of zero means the caller knows of none, so the field paints
     * whatever it holds.</p>
     */
    static int width(TextInputConfig opts, int allocatedCols) {
        int declared = opts.appearance().width();
        return declared > 0 ? (allocatedCols > 0 ? Math.min(declared, allocatedCols) : declared) : allocatedCols;
    }

    /**
     * Projects the one row the field paints into the window it shows of it.
     */
    static Painted painted(Model state, TextInputConfig config, int allocatedCols) {
        TextInputConfig opts = config == null ? TextInputConfig.defaults() : config;
        Appearance look = opts.appearance();
        String display = state.draft().displayText(editorConfig(opts));
        boolean blockPainted = state.focused() && look.cursor().caret() == Editor.Caret.PAINTED;
        int bounded = width(opts, allocatedCols);
        int available = bounded > 0 ? bounded : UNBOUNDED;
        VisualRows.CaretMark mark = !state.focused()
                ? VisualRows.CaretMark.NONE
                : blockPainted ? VisualRows.CaretMark.BLOCK : VisualRows.CaretMark.DECLARED;
        // A masked row paints one glyph per cluster and a hidden row paints none, so the caret's
        // offset in the painted text is not the draft's: the block has to be placed where the glyph
        // it covers was drawn.
        int caret = displayCaret(state, opts);
        VisualRows.Window window = VisualRows.window(
                display,
                TextMeasure.columns(paintedPrompt(look)),
                available,
                paintCells(opts),
                promptOpen(look),
                caret,
                mark);
        // Completion is off for a masked draft, so this is a second guard rather than the only one:
        // the suffix is the candidate's own text, and the mask is what keeps that text off screen.
        String suffix =
                opts.masking() == EditorConfig.Masking.NONE ? TextInputEditing.suggestedSuffix(state, opts) : "";
        return new Painted(
                display,
                suffix,
                blockPainted,
                available,
                caret,
                window.start(),
                window.end(),
                window.scroll(),
                window.caretColumn());
    }

    /**
     * The caret's offset in the text the row paints.
     *
     * <p>A visible draft paints its own text, so the offsets are the same. A masked draft paints one
     * glyph per cluster, so the caret's offset is the number of clusters before it; a hidden draft
     * paints nothing, so the caret's offset is the start of the row.</p>
     */
    static int displayCaret(Model state, TextInputConfig opts) {
        if (opts.masking() == EditorConfig.Masking.NONE) {
            return state.caret();
        }
        if (opts.masking() == EditorConfig.Masking.HIDDEN) {
            return 0;
        }
        int offset = 0;
        int position = 0;
        for (String cluster : Graphemes.iterate(state.text())) {
            if (position == state.caret()) {
                break;
            }
            position += cluster.length();
            offset++;
        }
        return offset;
    }

    /**
     * The message a pointer report produces, or empty when the field paints nothing to point at.
     *
     * <p>A press puts the caret where the pointer was, a second press selects the word there, and
     * motion with the left button held extends the selection. The cell is mapped through the window
     * the field painted - its prompt, and whatever was scrolled off - so a click on a scrolled or
     * masked field still lands on the cluster it covered.</p>
     *
     * <p>A hidden field paints no glyphs at all, so there is no cluster under any cell: a click on
     * one places no caret and reports nothing.</p>
     */
    static Optional<Msg> pointerAction(Model state, TextInputConfig config, Size region, PointerEvent event) {
        boolean dragging = event.isMotion() && event.button() == PointerButton.LEFT;
        if ((!event.isPress() && !dragging) || config.masking() == EditorConfig.Masking.HIDDEN) {
            return Optional.empty();
        }
        int offset = offsetAt(state, config, region, event);
        if (dragging) {
            return Optional.of(new Msg.ExtendSelection(offset));
        }
        return Optional.of(event.clickCount() > 1 ? new Msg.SelectWord(offset) : new Msg.PlaceCaret(offset));
    }

    /**
     * The draft offset one cell of the painted row falls on.
     *
     * <p>The cell is counted in the display text - the masked glyphs, if any - and the index it
     * reaches is then converted back to a draft offset, so a masked field's caret lands where the
     * user clicked instead of one glyph per mask too far along.</p>
     */
    static int offsetAt(Model state, TextInputConfig config, Size region, PointerEvent event) {
        TextInputConfig opts = config == null ? TextInputConfig.defaults() : config;
        Painted frame = painted(state, opts, region.cols());
        Appearance look = opts.appearance();
        int prompt = TextMeasure.columns(paintedPrompt(look));
        // A click on the prompt, or in the cells scrolled off the row, is the row's first painted
        // cluster: the field scrolls rather than wraps, so there is nothing before it to land on.
        int column = Math.max(0, event.column() - prompt) + frame.scroll();
        int display = VisualRows.indexAt(
                frame.display(), 0, frame.display().length(), column, prompt, paintCells(opts), promptOpen(look));
        return draftOffset(state, opts, display);
    }

    /**
     * The draft offset a painted display index names in a masked or plain row.
     */
    static int draftOffset(Model state, TextInputConfig opts, int displayIndex) {
        if (opts.masking() == EditorConfig.Masking.NONE) {
            return Math.max(0, Math.min(displayIndex, state.text().length()));
        }
        int position = 0;
        for (int index = 0; index < displayIndex; index++) {
            if (position >= state.text().length()) {
                break;
            }
            position = TextMeasure.boundaryAfter(state.text(), position);
        }
        return position;
    }

    /**
     * Paints one projected field: its prompt, the visible part of its row, and the ghost suffix.
     */
    static StyledText render(Painted frame, Model state, TextInputConfig config) {
        TextInputConfig opts = config == null ? TextInputConfig.defaults() : config;
        Appearance look = opts.appearance();
        StyledText out = StyledText.empty();
        if (!look.prompt().isEmpty()) {
            out = out.append(StyledText.of(look.prompt(), look.promptStyle()));
        }
        Run run = new Run(look.textStyle());
        if (state.isEmpty() && !look.placeholder().isEmpty()) {
            placeholder(run, frame, look);
            return out.append(run.flush());
        }
        String text = frame.display();
        VisualRows.Cells cells = paintCells(opts);
        int prefix = TextMeasure.columns(paintedPrompt(look));
        int scroll = frame.scroll();
        int caret = frame.caret();
        int selectionStart = state.hasSelection() ? state.draft().selectionStart() : -1;
        int selectionEnd = state.hasSelection() ? state.draft().selectionEnd() : -1;
        VisualRows.Open open = promptOpen(look);
        int used = 0;
        int position = frame.start();
        // The window ends before the first cluster that would not fit whole, so every cluster it
        // names is painted in full; only the caret cell can overrun the last cells of the field.
        for (String cluster : Graphemes.iterate(text.substring(frame.start(), frame.end()))) {
            int absolute = prefix + scroll + used;
            boolean atCaret = frame.blockPainted() && position == caret;
            int cost = atCaret
                    ? VisualRows.blockCells(cluster, absolute, cells)
                    : VisualRows.cells(cluster, absolute, cells, open);
            Style style = selectionStart >= 0 && position >= selectionStart && position < selectionEnd
                    ? look.selectionStyle()
                    : look.textStyle();
            int room = frame.available() - used;
            if (cost > room) {
                // Only the caret's own cell can overrun the field's last cells: every cluster the
                // window names fits. Paint the cells that are left and stop.
                if (atCaret) {
                    run.append(CURSOR.repeat(Math.max(0, room)), style);
                }
                used += Math.max(0, room);
                break;
            }
            if (atCaret) {
                run.append(CURSOR.repeat(cost), style);
            } else if ("\t".equals(cluster) && cells == VisualRows.Cells.NATURAL) {
                // A tab is painted as the spaces of its stop, so a scrolled row paints the cells the
                // projection counted: the stop was counted from the row's own start, which the
                // layout cannot see once the beginning has been scrolled off.
                run.append(" ".repeat(cost), style);
            } else {
                run.append(cluster, style);
            }
            used += cost;
            open = atCaret ? VisualRows.CLOSED : VisualRows.openBehind(cluster, cells);
            position += cluster.length();
        }
        if (frame.blockPainted() && caret == text.length() && used < frame.available()) {
            run.append(CURSOR, look.textStyle());
            used++;
        }
        if (!frame.suffix().isEmpty()) {
            run.append(TextMeasure.truncate(frame.suffix(), frame.available() - used), look.suggestionStyle());
        }
        return out.append(run.flush());
    }

    /**
     * Paints the placeholder of an empty draft, with the caret block on its first cell.
     *
     * <p>The placeholder stands in for a draft that has none: it occupies no offset, is never
     * submitted and is never copied.</p>
     */
    static void placeholder(Run run, Painted frame, Appearance look) {
        String placeholder = look.placeholder();
        Style style = look.placeholderStyle();
        int remaining = frame.available();
        if (remaining <= 0) {
            return;
        }
        if (!frame.blockPainted()) {
            run.append(TextMeasure.truncate(placeholder, remaining), style);
            return;
        }
        int first = Math.max(1, TextMeasure.boundaryAfter(placeholder, 0));
        String head = placeholder.substring(0, Math.min(first, placeholder.length()));
        int painted = Math.min(Math.max(1, TextMeasure.columns(head)), remaining);
        run.append(CURSOR.repeat(painted), style);
        // The block covers the placeholder's first cluster; the rest of it follows.
        String tail = TextMeasure.truncate(placeholder.substring(head.length()), remaining - painted);
        run.append(tail, style);
    }

    /**
     * One painted run: the text and style still being accumulated.
     *
     * <p>Flushing on a style change keeps a styled row to the runs it really needs, and the cell
     * arithmetic is kept beside it because a tab is painted as spaces, whose length is no longer the
     * glyph the row was counted from.</p>
     */
    static final class Run {

        private final StringBuilder text = new StringBuilder();
        private StyledText out = StyledText.empty();
        private Style style;

        Run(Style initial) {
            this.style = initial == null ? Style.NONE : initial;
        }

        void append(String value, Style valueStyle) {
            if (value == null || value.isEmpty()) {
                return;
            }
            Style next = valueStyle == null ? Style.NONE : valueStyle;
            if (!next.equals(style) && !text.isEmpty()) {
                out = out.append(StyledText.of(text.toString(), style));
                text.setLength(0);
            }
            style = next;
            text.append(value);
        }

        StyledText flush() {
            return out.append(StyledText.of(text.toString(), style));
        }
    }

    /**
     * One painted field: the slice of its row that is visible, and what dressed it.
     *
     * <p>The projection the slice came from is flattened into offsets here rather than kept as the
     * value it was returned in, so nothing below this widget's public surface leaks an internal
     * type through an accessor.</p>
     *
     * @param display the text the row paints
     * @param suffix the ghost suffix of the selected candidate, or empty
     * @param blockPainted whether the caret is part of the painted text
     * @param available the cells the field's draft may occupy
     * @param caret the caret's offset in {@code display}, which a mask moves
     * @param start the first display offset the window paints
     * @param end the offset just past the last one it paints
     * @param scroll the columns scrolled off the row's left edge
     * @param caretColumn the caret's cell, counted from the row's own first column
     */
    record Painted(
            String display,
            String suffix,
            boolean blockPainted,
            int available,
            int caret,
            int start,
            int end,
            int scroll,
            int caretColumn) {}

    /**
     * The editor policy this field's policy derives.
     */
    static EditorConfig editorConfig(TextInputConfig opts) {
        EditorConfig base = EditorConfig.defaults()
                .withVisibleLines(1)
                .withMasking(opts.masking())
                .withCopyEnabled(opts.copyEnabled())
                .withKeys(opts.copyKey(), opts.pasteKey());
        return opts.maxLength().isPresent()
                ? base.withMaxLength(opts.maxLength().getAsInt())
                : base;
    }

    /**
     * Places the declared cursor from the same projection used to paint the field.
     */
    static io.github.dhoard.braxton.ui.Cursor cursor(
            io.github.dhoard.braxton.layout.Rect bounds, Model model, TextInputConfig config) {
        if (!model.focused() || config.appearance().cursor().caret() == Editor.Caret.PAINTED) {
            return null;
        }
        Painted frame = TextInputRendering.painted(model, config, bounds.cols());
        Appearance look = config.appearance();
        int column = TextMeasure.columns(TextInputRendering.paintedPrompt(look)) + frame.caretColumn() - frame.scroll();
        return new Cursor(
                Cursor.Shape.valueOf(look.cursor().shape().name()),
                Cursor.Mode.valueOf(look.cursor().mode().name()),
                Cursor.Strategy.NATIVE,
                null,
                bounds.row(),
                bounds.col() + Math.max(0, column));
    }
}

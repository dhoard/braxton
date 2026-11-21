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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import examples.parity.EditorDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.terminal.TimedReader;
import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorConfig;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 04 acceptance: complete multiline editing - word and document motions, selection
 * extension, wrapped visual rows, the textarea frame and two independent mounted editors.
 *
 * <p>Every assertion is against public behavior. The rows are read back through the real layout
 * path where the cell they land in matters, because a row the editor wraps and a row the layout
 * wraps have to be the same row.</p>
 */
class ParityEditorTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static EditorConfig wrapped(int cols) {
        return EditorConfig.defaults().withWrapWidth(cols).withVisibleLines(4);
    }

    /**
     * The rows the editor paints, as text.
     */
    private static List<String> rows(Editor.Model state, EditorConfig options) {
        return Editor.render(state, options, true).lines().stream()
                .map(StyledText::plainText)
                .toList();
    }

    /**
     * The rows a real layout paints for the editor at one viewport, which is what a user sees.
     */
    private static List<String> painted(Editor.Model state, EditorConfig options, boolean focused, int cols) {
        return ElementScreen.render(
                        new Editor(options)
                                .view((state).withFocus(focused), new ViewContext(new Viewport(24, cols)))
                                .map(action -> action),
                        24,
                        cols)
                .lines();
    }

    private static Editor editor() {
        return new Editor(EditorConfig.defaults());
    }

    private static Editor.Model after(Editor.Model state, Editor.Msg message) {
        return editor().update(state, message).model();
    }

    private static Editor.Model after(Editor.Model state, EditorConfig options, Editor.Msg message) {
        return new Editor(options).update(state, message).model();
    }

    // ── R-01: the editing and navigation policy ─────────────────────────────

    @Test
    void theDefaultsKeepEnterAsSubmitAndCtrlJAsTheLineBreak() {
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.ENTER), EditorConfig.defaults()))
                .contains(new Editor.Msg.Submit());
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("j", Modifiers.CTRL)), EditorConfig.defaults()))
                .contains(new Editor.Msg.Newline());
        assertThat(after(Editor.Model.of("ab"), new Editor.Msg.Newline()).text())
                .isEqualTo("ab\n");
    }

    @Test
    void theTextareaPresetMakesEnterBreakALineAndSubmitsSeparately() {
        Editor textarea = Editor.textarea(EditorConfig.defaults());
        assertThat(textarea.keys().actionFor(Key.ENTER).map(action -> action.message()))
                .contains(new Editor.Msg.Newline());
        assertThat(textarea.keys().actionFor(Key.parse("ctrl+j")).map(action -> action.message()))
                .contains(new Editor.Msg.Newline());
        assertThat(textarea.keys().actionFor(Key.parse("ctrl+enter")).map(action -> action.message()))
                .contains(new Editor.Msg.Submit());
        assertThat(textarea.keys().actionFor(Key.parse("alt+enter")).map(action -> action.message()))
                .contains(new Editor.Msg.Submit());
        assertThat(textarea.update(Editor.Model.of("ab"), new Editor.Msg.Newline())
                        .model()
                        .text())
                .isEqualTo("ab\n");
        // The preset still submits, and the submit carries the draft's text.
        Update<Editor.Model, Editor.Msg, Editor.Output> submitted =
                textarea.update(Editor.Model.of("ab"), new Editor.Msg.Submit());
        assertThat(submitted.outputs()).containsExactly(new Editor.Output.Submit("ab"));
    }

    /**
     * The expanded help of one key map: one line per group, holding every action in the map.
     */
    private static List<String> help(io.github.dhoard.braxton.input.KeyMap<Editor.Msg> keys) {
        return ElementScreen.render(Help.element(keys, Help.Config.defaults(), true, 400), 12, 400).lines().stream()
                .filter(line -> !line.isBlank())
                .toList();
    }

    @Test
    void theTwoPresetsAreOneMapAndOnlyEnterDiffers() {
        List<String> submit = help(Editor.defaultKeys(EditorConfig.defaults()));
        List<String> textarea = help(Editor.textareaKeys(EditorConfig.defaults()));
        assertThat(submit).anySatisfy(line -> assertThat(line).contains("enter").contains("submit"));
        assertThat(textarea)
                .anySatisfy(line -> assertThat(line).contains("enter").contains("new line"));
        assertThat(textarea)
                .anySatisfy(line -> assertThat(line).contains("ctrl+enter").contains("submit"));
        // No action of either map is missing from the other: the preset changes gestures, not actions.
        assertThat(Editor.textareaKeys(EditorConfig.defaults()).actions().stream()
                        .map(action -> action.id())
                        .toList())
                .containsExactlyElementsOf(Editor.defaultKeys(EditorConfig.defaults()).actions().stream()
                        .map(action -> action.id())
                        .toList());
    }

    @Test
    void aWordIsARunOfLettersDigitsUnderscoresBlanksOrPunctuation() {
        String text = "foo bar_baz42! qux\nnext";
        Editor.Model start = new Editor.Model(text, 0, 0, 0);
        assertThat(after(start, new Editor.Msg.MoveWordRight()).caret()).isEqualTo(3);
        assertThat(after(after(start, new Editor.Msg.MoveWordRight()), new Editor.Msg.MoveWordRight())
                        .caret())
                .isEqualTo(13);
        assertThat(after(
                                after(after(start, new Editor.Msg.MoveWordRight()), new Editor.Msg.MoveWordRight()),
                                new Editor.Msg.MoveWordRight())
                        .caret())
                .isEqualTo(14);
        // Leftward from the end: the last word, then the blank, then the punctuation run.
        Editor.Model end = new Editor.Model(text, text.length(), text.length(), 0);
        assertThat(after(end, new Editor.Msg.MoveWordLeft()).caret()).isEqualTo(19);
        assertThat(after(new Editor.Model(text, 14, 14, 0), new Editor.Msg.MoveWordLeft())
                        .caret())
                .isEqualTo(13);
        assertThat(after(new Editor.Model(text, 13, 13, 0), new Editor.Msg.MoveWordLeft())
                        .caret())
                .as("the run of letters, digits and underscores before the punctuation")
                .isEqualTo(4);
    }

    @Test
    void wordMotionStaysOnGraphemeBoundariesAndCrossesLines() {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        String text = family + " two\nthree";
        Editor.Model state = new Editor.Model(text, 0, 0, 0);
        assertThat(after(state, new Editor.Msg.MoveWordRight()).caret())
                .as("the joined emoji is one cluster, so a word move cannot stop inside it")
                .isEqualTo(family.length());
        assertThat(after(new Editor.Model(text, family.length(), family.length(), 0), new Editor.Msg.MoveWordRight())
                        .caret())
                .isEqualTo(family.length() + 4);
        assertThat(after(
                                new Editor.Model(text, family.length() + 4, family.length() + 4, 0),
                                new Editor.Msg.MoveWordRight())
                        .caret())
                .as("a word move crosses the line break as it crosses a space")
                .isEqualTo(text.length());
    }

    @Test
    void deletingByWordTakesTheRunAndTheBlanksBehindIt() {
        Editor.Model state = new Editor.Model("foo bar_baz", 11, 11, 0);
        assertThat(after(state, new Editor.Msg.DeleteWordBackward()).text()).isEqualTo("foo ");
        assertThat(after(state, new Editor.Msg.DeleteWordForward()).text()).isEqualTo("foo bar_baz");
        Editor.Model head = new Editor.Model("foo bar", 0, 0, 0);
        assertThat(after(head, new Editor.Msg.DeleteWordForward()).text()).isEqualTo(" bar");
        // A selection is what a word deletion removes first.
        Editor.Model selected = new Editor.Model("foo bar", 6, 4, 0);
        assertThat(after(selected, new Editor.Msg.DeleteWordBackward()).text()).isEqualTo("foo r");
    }

    @Test
    void theDocumentEdgesAreTheirOwnGestures() {
        Editor.Model state = new Editor.Model("one\ntwo\nthree", 5, 5, 0);
        assertThat(after(state, new Editor.Msg.MoveDocumentStart()).caret()).isEqualTo(0);
        assertThat(after(state, new Editor.Msg.MoveDocumentEnd()).caret()).isEqualTo(13);
        assertThat(Editor.defaultKeys(EditorConfig.defaults())
                        .actionFor(Key.parse("ctrl+home"))
                        .map(action -> action.message()))
                .contains(new Editor.Msg.MoveDocumentStart());
        assertThat(Editor.defaultKeys(EditorConfig.defaults())
                        .actionFor(Key.parse("ctrl+end"))
                        .map(action -> action.message()))
                .contains(new Editor.Msg.MoveDocumentEnd());
    }

    @Test
    void anExtensionGrowsAndShrinksTheSelectionFromItsAnchor() {
        Editor.Model state = new Editor.Model("hello world", 0, 0, 0);
        Editor.Model selected = after(state, new Editor.Msg.Extend(new Editor.Msg.MoveWordRight()));
        assertThat(selected.anchor()).isEqualTo(0);
        assertThat(selected.caret()).isEqualTo(5);
        assertThat(selected.selection()).isEqualTo("hello");
        // The same gesture again extends the same way; the opposite one shrinks it back.
        assertThat(after(selected, new Editor.Msg.Extend(new Editor.Msg.MoveLeft()))
                        .caret())
                .isEqualTo(4);
        assertThat(after(selected, new Editor.Msg.Extend(new Editor.Msg.MoveHome()))
                        .caret())
                .isEqualTo(0);
        assertThat(after(
                                after(state, new Editor.Msg.Extend(new Editor.Msg.MoveWordRight())),
                                new Editor.Msg.MoveRight())
                        .hasSelection())
                .as("a plain move collapses the selection")
                .isFalse();
    }

    @Test
    void anExtensionRejectsAnActionThatWouldEditTheText() {
        assertThatThrownBy(() -> new Editor.Msg.Extend(new Editor.Msg.Insert("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("navigation");
        assertThatThrownBy(() -> new Editor.Msg.Extend(new Editor.Msg.Backspace()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Editor.Msg.Extend(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new Editor.Msg.Extend(new Editor.Msg.MoveUp()))
                .as("a navigation action is accepted")
                .isEqualTo(new Editor.Msg.Extend(new Editor.Msg.MoveUp()));
    }

    @Test
    void extensionGesturesReachTheirActions() {
        var keys = Editor.defaultKeys(EditorConfig.defaults());
        assertThat(keys.actionFor(Key.parse("shift+left")).map(action -> action.message()))
                .contains(new Editor.Msg.Extend(new Editor.Msg.MoveLeft()));
        assertThat(keys.actionFor(Key.parse("ctrl+shift+right")).map(action -> action.message()))
                .contains(new Editor.Msg.Extend(new Editor.Msg.MoveWordRight()));
        assertThat(keys.actionFor(Key.parse("ctrl+shift+home")).map(action -> action.message()))
                .contains(new Editor.Msg.Extend(new Editor.Msg.MoveDocumentStart()));
        assertThat(keys.actionFor(Key.parse("ctrl+backspace")).map(action -> action.message()))
                .contains(new Editor.Msg.DeleteWordBackward());
        assertThat(keys.actionFor(Key.parse("ctrl+left")).map(action -> action.message()))
                .contains(new Editor.Msg.MoveWordLeft());
    }

    @Test
    void typingOverASelectionReplacesItAndKeepsTheDraftPrepared() {
        Editor.Model state = new Editor.Model("abcdef", 6, 0, 0);
        Editor.Model typed = after(state, new Editor.Msg.Insert("X"));
        assertThat(typed.text()).isEqualTo("X");
        assertThat(typed.caret()).isEqualTo(1);
        // A seeded carriage return is prepared where the draft is entered, and a word move over
        // the prepared draft never sees it.
        Editor.Model crlf = Editor.Model.of("one\r\ntwo\rthree");
        assertThat(crlf.text()).isEqualTo("one\ntwo\nthree");
        assertThat(after(new Editor.Model(crlf.text(), 0, 0, 0), new Editor.Msg.MoveDocumentEnd())
                        .caret())
                .isEqualTo(crlf.text().length());
    }

    // ── R-02: visual rows ──────────────────────────────────────────────────

    @Test
    void aWrappedRowBreaksAtThePaintedWidthWithoutTouchingTheDraft() {
        Editor.Model state = Editor.Model.of("the quick brown fox");
        EditorConfig options = wrapped(10).withVisibleLines(4);
        assertThat(rows(state, options)).containsExactly("the quick ", "brown fox" + Editor.CURSOR);
        assertThat(state.text()).as("wrapping is derived, never inserted").isEqualTo("the quick brown fox");
    }

    @Test
    void aCaretOnASoftBreakIsDrawnOnTheRowItOpensAndNowhereElse() {
        // Offset 10 is the end of the first row and the start of the second: the block opens the
        // row it starts, so exactly one block is painted.
        Editor.Model state = new Editor.Model("the quick brown fox", 10, 10, 0);
        List<String> painted = rows(state, wrapped(10));
        assertThat(painted.get(0)).isEqualTo("the quick ");
        assertThat(painted.get(1)).startsWith(Editor.CURSOR).endsWith("fox");
        assertThat(String.join("", painted)).containsOnlyOnce(Editor.CURSOR);
    }

    @Test
    void aVerticalMoveCrossesASoftBreakBeforeItLeavesTheLine() {
        Editor.Model state = new Editor.Model("the quick brown fox", 4, 4, 0);
        EditorConfig options = wrapped(10);
        Editor.Model down = after(state, options, new Editor.Msg.MoveDown());
        assertThat(down.caret())
                .as("the soft break is one row down, before the line is left")
                .isEqualTo(14);
        assertThat(down.preferredColumn()).isEqualTo(4);
        assertThat(after(down, options, new Editor.Msg.MoveUp()).caret()).isEqualTo(4);
        // The last row of the last line has no row below it, so the move keeps the caret where it
        // is rather than inventing a row the draft does not have.
        Editor.Model deep = new Editor.Model("the quick brown fox", 14, 14, 0);
        assertThat(after(deep, options, new Editor.Msg.MoveDown()).caret()).isEqualTo(14);
    }

    @Test
    void aVerticalMoveKeepsThePaintedColumnThroughRowsOfDifferentWidths() {
        String text = "0123456789\nab\n0123456789";
        EditorConfig options = wrapped(10);
        Editor.Model top = new Editor.Model(text, 8, 8, 0);
        Editor.Model middle = after(top, options, new Editor.Msg.MoveDown());
        assertThat(middle.caret()).as("a short row clamps the caret").isEqualTo(13);
        assertThat(middle.preferredColumn()).isEqualTo(8);
        Editor.Model bottom = after(middle, options, new Editor.Msg.MoveDown());
        assertThat(bottom.caret()).as("the preferred column comes back").isEqualTo(22);
    }

    @Test
    void aTabAndAWideGlyphKeepTheCaretOnTheCellTheyPaint() {
        EditorConfig options = wrapped(8);
        Editor.Model tab = new Editor.Model("ab\tcd", 0, 0, 0);
        assertThat(rows(tab, options).get(0)).isEqualTo(Editor.CURSOR + "b\tcd");
        // The row's tab stop is measured from the row's own columns, so the block on the 'c' sits
        // where the tab put it - which is what the layout paints for the tab the editor emitted.
        Editor.Model onC = new Editor.Model("ab\tcd", 3, 3, 0);
        assertThat(painted(onC, options, true, 8).get(0).stripTrailing()).isEqualTo("ab  " + Editor.CURSOR + "d");
        Editor.Model wide = new Editor.Model("a\u754cb", 1, 1, 0);
        assertThat(Editor.render(wide, options, true).plainText())
                .as("the block covers both cells of the two-column glyph")
                .isEqualTo("a" + Editor.CURSOR + Editor.CURSOR + "b");
        // A wide glyph that would straddle the edge starts the next row rather than being split.
        Editor.Model edge = new Editor.Model("abcdefg\u754c", 0, 0, 0);
        assertThat(rows(edge, options)).hasSize(2);
        assertThat(rows(edge, options).get(1)).startsWith("\u754c");
    }

    @Test
    void theCaretStaysVisibleAtZeroOneAndNormalWidths() {
        String text = "alpha beta\ngamma delta epsilon";
        Editor.Model state = new Editor.Model(text, text.length(), text.length(), 0);
        for (int cols : new int[] {0, 1, 2, 5, 12, 80}) {
            EditorConfig options = cols == 0
                    ? EditorConfig.defaults().withVisibleLines(3)
                    : wrapped(cols).withVisibleLines(3);
            List<String> painted = painted(state, options, true, cols);
            if (cols == 0) {
                assertThat(painted)
                        .as("a zero-column view paints no cell")
                        .allSatisfy(row -> assertThat(row).isEmpty());
                continue;
            }
            assertThat(String.join("", painted))
                    .as("%d columns keep the caret in the painted rows", cols)
                    .contains(Editor.CURSOR);
        }
    }

    @Test
    void aZeroColumnRowStillNamesTheCaretRow() {
        Editor.Model state = new Editor.Model("abc\ndef", 5, 5, 0);
        EditorConfig options = wrapped(1).withVisibleLines(2);
        assertThat(Editor.visibleLineCount(state, options, 1)).isEqualTo(2);
        assertThat(String.join("", painted(state, options, true, 1)))
                .as("a one-column row holds one cell, and the caret still gets one")
                .contains(Editor.CURSOR);
    }

    @Test
    void theAllocatedWidthWinsWhenItIsNarrowerThanTheDeclaredOne() {
        Editor.Model state = Editor.Model.of("alpha beta gamma");
        EditorConfig declared = wrapped(40).withVisibleLines(3);
        assertThat(rows(state, declared)).hasSize(1);
        // The declared width is what movement uses; painting is bounded by what the view is given,
        // so a narrower pane wraps its rows instead of overflowing them.
        assertThat(painted(state, declared, true, 6).stream().allMatch(row -> row.length() <= 6))
                .isTrue();
    }

    @Test
    void aComputedLeafIsMeasuredAtWhatItPaints() {
        // A leaf that renders for the columns it is given is the element answer to a size-aware
        // subtree: it measures what its renderer produces and paints the same value.
        Element<String> view = Elements.text("xxxxx");
        assertThat(io.github.dhoard.braxton.ui.ElementTree.measure(view, new Size(Integer.MAX_VALUE, 5))
                        .cols())
                .isEqualTo(5);
        assertThat(ElementScreen.render(view, 2, 5).lines().get(0)).isEqualTo("xxxxx");
        // The renderer is asked again for a different geometry, so the same leaf paints both.
        assertThat(ElementScreen.render(view, 2, 3).lines().get(0)).isEqualTo("xxx");
    }

    @Test
    void routingSeesThroughASizeAwareSubtree() {
        EditorConfig options = wrapped(10);
        Element<Editor.Msg> field = new Editor(options).view(new Editor.Model("abc", 3, 3, 0), CONTEXT);
        assertThat(InteractionRouter.message(field, InputEvent.KeyInput.of(Key.of("x"), "x")))
                .as("a focused element inside a size-aware subtree still receives the text")
                .contains(new Editor.Msg.Insert("x"));
    }

    @Test
    void mappingAnElementKeepsItsContent() {
        Element<String> view = Elements.text("ab");
        Element<Integer> mapped = view.map(String::length);
        assertThat(mapped.children()).isEmpty();
        assertThat(ElementScreen.render(mapped, 1, 4).lines().get(0)).isEqualTo("ab");
    }

    @Test
    void aDeclaredCaretSitsOnTheCellTheBlockWouldCover() {
        Editor.Model state = new Editor.Model("the quick brown fox", 14, 14, 0);
        EditorConfig native_ = wrapped(10)
                .withVisibleLines(4)
                .withCursor(Editor.CursorPolicy.nativeCursor(
                        io.github.dhoard.braxton.ui.Cursor.Shape.BLOCK,
                        io.github.dhoard.braxton.ui.Cursor.Mode.STEADY));
        var view = new Editor(native_).view(state, CONTEXT).map(action -> action);
        var caret = ElementScreen.render(view, 6, 10).cursor();
        assertThat(caret).as("a native caret is resolved to a screen cell").isNotNull();
        // Row 1 is the wrapped row, and the column is the 'r' of "brown" the block was drawn on.
        assertThat(caret.row()).isEqualTo(1);
        assertThat(ElementScreen.render(view, 6, 10).lines().get(1)).startsWith("brow");
        assertThat(caret.column())
                .as("four cells into the wrapped row, where the block on the 'n' is drawn")
                .isEqualTo(4);
    }

    @Test
    void theVisualRowProjectionAgreesWithTheRowsItBreaks() {
        String text = "alpha beta gamma delta epsilon";
        List<VisualRows.Row> projected =
                VisualRows.project(text, 0, text.length(), 0, 10, VisualRows.Cells.NATURAL, VisualRows.CLOSED, -1);
        StringBuilder rebuilt = new StringBuilder();
        for (VisualRows.Row row : projected) {
            rebuilt.append(text, row.start(), row.end());
            assertThat(row.columns()).isLessThanOrEqualTo(10);
        }
        assertThat(rebuilt.toString()).isEqualTo(text);
        assertThat(projected.get(projected.size() - 1).end()).isEqualTo(text.length());
        // An empty line is one row that shows nothing, never no rows at all.
        assertThat(VisualRows.project("", 0, 0, 0, 10, VisualRows.Cells.NATURAL, VisualRows.CLOSED, -1))
                .containsExactly(new VisualRows.Row(0, 0, 0));
    }

    // ── R-03: presentation and height ──────────────────────────────────────

    @Test
    void aPlaceholderIsPaintedOnlyForAnEmptyDraftAndIsNeverContent() {
        EditorConfig options = EditorConfig.defaults()
                .withWrapWidth(20)
                .withPresentation(Editor.Presentation.plain().withPlaceholder("Once upon a time", Style.NONE));
        Editor.Model empty = Editor.Model.empty();
        assertThat(Editor.render(empty, options, false).plainText()).isEqualTo("Once upon a time");
        assertThat(Editor.render(empty, options, true).plainText())
                .as("the block covers the placeholder's first cluster, as it covers a draft's")
                .isEqualTo(Editor.CURSOR + "nce upon a time");
        Editor.Model typed = Editor.Model.of("x");
        assertThat(Editor.render(typed, options, false).plainText()).isEqualTo("x");
        // The placeholder is not part of the draft, so nothing semantic carries it.
        assertThat(empty.text()).isEmpty();
        assertThat(Editor.copyableText(empty, options)).contains("");
        assertThat(new Editor(options).update(empty, new Editor.Msg.Submit()).outputs())
                .containsExactly(new Editor.Output.Submit(""));
    }

    @Test
    void lineNumbersNameLogicalLinesAndWrappedRowsContinueUnderThem() {
        EditorConfig options = wrapped(10)
                .withVisibleLines(4)
                .withPresentation(Editor.Presentation.plain().withLineNumbers(true, Style.NONE));
        Editor.Model state = Editor.Model.of("alpha beta gamma\ndelta");
        assertThat(Editor.render(state, options, false).plainText().lines().toList())
                .as("the gutter takes its columns first, so the draft wraps inside what is left")
                .containsExactly("1 alpha be", "  ta gamma", "2 delta");
    }

    @Test
    void theGutterWidensWithTheLineCountAndStaysNarrowWhenTheEditorIsNarrow() {
        EditorConfig numbers = EditorConfig.defaults()
                .withPresentation(Editor.Presentation.plain().withLineNumbers(true, Style.NONE));
        Editor.Model ten = Editor.Model.of("1\n2\n3\n4\n5\n6\n7\n8\n9\n10");
        assertThat(Editor.render(ten, numbers, false)
                        .plainText()
                        .lines()
                        .toList()
                        .get(5))
                .as("a ten-line draft reserves two digits, and the window ends at the caret's line")
                .startsWith("10 ");
        // A gutter and a prompt wider than the view still produce rows that fit it.
        EditorConfig narrow =
                numbers.withPrompt(">> ", Style.NONE).withWrapWidth(4).withVisibleLines(2);
        Editor.Model state = new Editor.Model("abcdef", 6, 6, 0);
        assertThat(painted(state, narrow, true, 3).stream().allMatch(row -> row.length() <= 3))
                .isTrue();
        assertThat(Editor.visibleLineCount(state, narrow, 3)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void theCursorLineStylesTheCaretRowOnlyWhileFocused() {
        Style line = Style.NONE.withUnderline();
        EditorConfig options =
                wrapped(20).withPresentation(Editor.Presentation.plain().withCursorLine(line));
        Editor.Model state = new Editor.Model("one\ntwo", 5, 5, 0);
        StyledText focused = Editor.render(state, options, true);
        assertThat(focused.spans().stream()
                        .filter(span -> span.style().underline())
                        .count())
                .as("only one row is styled")
                .isEqualTo(1);
        assertThat(focused.spans().stream()
                        .filter(span -> span.style().underline())
                        .map(span -> span.text().replace(Editor.CURSOR, ""))
                        .reduce("", String::concat))
                .as("the caret's row, filled to the row's width")
                .startsWith("to")
                .endsWith(" ");
        assertThat(Editor.render(state, options, false).spans().stream()
                        .noneMatch(span -> span.style().underline()))
                .isTrue();
    }

    @Test
    void aDynamicHeightFollowsTheContentBetweenItsBounds() {
        EditorConfig options = EditorConfig.defaults()
                .withWrapWidth(10)
                .withPresentation(Editor.Presentation.plain().withDynamicHeight(true, 2, 4));
        assertThat(Editor.visibleLineCount(Editor.Model.empty(), options, 10))
                .as("the minimum")
                .isEqualTo(2);
        assertThat(Editor.visibleLineCount(Editor.Model.of("one"), options, 10)).isEqualTo(2);
        assertThat(Editor.visibleLineCount(Editor.Model.of("one\ntwo\nthree"), options, 10))
                .isEqualTo(3);
        assertThat(Editor.visibleLineCount(Editor.Model.of("1\n2\n3\n4\n5\n6"), options, 10))
                .as("the maximum")
                .isEqualTo(4);
        // The rows it reports are the rows it paints.
        Editor.Model longDraft = Editor.Model.of("1\n2\n3\n4\n5\n6");
        assertThat(Editor.render(longDraft, options, true).lines()).hasSize(4);
    }

    @Test
    void aDynamicHeightCountsNoFurtherThanTheContentRowLimit() {
        EditorConfig options = EditorConfig.defaults()
                .withPresentation(Editor.Presentation.plain()
                        .withDynamicHeight(true, 1, 50)
                        .withMaxContentLines(3));
        Editor.Model many = Editor.Model.of("1\n2\n3\n4\n5\n6\n7\n8\n9");
        assertThat(Editor.visibleLineCount(many, options, 10))
                .as("the content row limit bounds a height that would otherwise follow every line")
                .isEqualTo(3);
    }

    @Test
    void theFramePolicyRejectsAHeightRangeItCannotPaint() {
        assertThatThrownBy(() -> new Editor.Presentation("", Style.NONE, false, Style.NONE, Style.NONE, true, 0, 4, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minLines");
        assertThatThrownBy(() -> new Editor.Presentation("", Style.NONE, false, Style.NONE, Style.NONE, true, 4, 3, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLines");
        assertThatThrownBy(() -> new Editor.Presentation("", Style.NONE, false, Style.NONE, Style.NONE, true, 1, 4, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxContentLines");
        assertThatThrownBy(() -> EditorConfig.defaults().withWrapWidth(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Editor.Presentation.plain()
                        .withPlaceholder("a\nb", Style.NONE)
                        .placeholder())
                .as("a placeholder is one row, so a break in it becomes a space")
                .isEqualTo("a b");
    }

    @Test
    void anEmptyPasswordAndAHiddenDraftStillWrapAndKeepTheirCaret() {
        for (EditorConfig.Masking masking :
                new EditorConfig.Masking[] {EditorConfig.Masking.PASSWORD, EditorConfig.Masking.HIDDEN}) {
            EditorConfig options = wrapped(6)
                    .withVisibleLines(3)
                    .withMasking(masking)
                    .withPresentation(Editor.Presentation.plain().withLineNumbers(true, Style.NONE));
            Editor.Model state = new Editor.Model("secret text", 11, 11, 0);
            List<String> painted = painted(state, options, true, 6);
            assertThat(painted).isNotEmpty();
            assertThat(String.join("", painted))
                    .as("%s keeps its caret", masking)
                    .contains(Editor.CURSOR);
            assertThat(painted.stream().allMatch(row -> row.length() <= 6)).isTrue();
            // A masked draft never copies, whatever the width.
            assertThat(Editor.copyableText(state, options)).isEmpty();
        }
    }

    @Test
    void aTrailingNewlineIsARowOfItsOwn() {
        Editor.Model state = Editor.Model.of("one\n");
        assertThat(rows(state, wrapped(10))).containsExactly("one", Editor.CURSOR);
        assertThat(rows(Editor.Model.of("one\n\ntwo"), wrapped(10)))
                .as("an empty line is a row that shows nothing, and the caret ends the last one")
                .containsExactly("one", "", "two" + Editor.CURSOR);
    }

    @Test
    void countingTheRowsOfALargeDraftIsBoundedByTheHeight() {
        String draft = "0123456789\n".repeat(20_000);
        EditorConfig capped = EditorConfig.defaults()
                .withWrapWidth(6)
                .withPresentation(Editor.Presentation.plain()
                        .withDynamicHeight(true, 1, 5)
                        .withMaxContentLines(4));
        // Counting stops at the content row limit, so the measurement is bounded rather than
        // proportional to the draft.
        long started = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            assertThat(Editor.visibleLineCount(Editor.Model.of(draft), capped, 6))
                    .isEqualTo(4);
        }
        assertThat((System.nanoTime() - started) / 1_000_000L)
                .as("two hundred row counts over a twenty-thousand-line draft")
                .isLessThan(4_000L);
    }

    // ── R-04: two editors, focus and resize ─────────────────────────────────

    private static ComponentDriver<EditorDemo.Model, EditorDemo.Msg, Never> demo() {
        return ComponentDriver.initialize(new EditorDemo.Scene(), new InitContext(null, null));
    }

    private static List<String> lines(ComponentDriver<EditorDemo.Model, EditorDemo.Msg, ?> driver, int cols) {
        return ElementScreen.render(driver.view(new ViewContext(new Viewport(24, cols))), 24, cols)
                .lines();
    }

    private static void press(ComponentDriver<EditorDemo.Model, EditorDemo.Msg, Never> driver, String bytes, int cols) {
        List<InputEvent> events;
        try {
            events = decodeAll(bytes);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        for (InputEvent event : events) {
            io.github.dhoard.braxton.testing.Routing.route(
                            driver.component(), driver.model(), new ViewContext(new Viewport(24, cols)), event)
                    .forEach(message -> deliver(driver, message));
        }
    }

    /**
     * Feeds the decoder one byte per read, so a partial sequence is really partial.
     */
    private static final class ByteAtATime extends java.io.InputStream {

        private final byte[] bytes;
        private int index;

        private ByteAtATime(String text) {
            this.bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            return index < bytes.length ? bytes[index++] & 0xFF : -1;
        }
    }

    private static List<InputEvent> decodeAll(String text) throws IOException {
        TimedReader reader = io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(text));
        InputDecoder decoder = new InputDecoder(reader);
        List<InputEvent> events = new ArrayList<>();
        Optional<InputEvent> event;
        while ((event = decoder.read()).isPresent()) {
            events.add(event.get());
        }
        return events;
    }

    private static void deliver(
            ComponentDriver<EditorDemo.Model, EditorDemo.Msg, Never> driver, EditorDemo.Msg message) {
        ArrayDeque<EditorDemo.Msg> pending = new ArrayDeque<>();
        pending.add(message);
        while (!pending.isEmpty()) {
            inspect(driver.send(pending.removeFirst()).command(), pending);
        }
    }

    private static void inspect(Command<EditorDemo.Msg> command, ArrayDeque<EditorDemo.Msg> pending) {
        switch (command) {
            case Command.None<EditorDemo.Msg> none -> {}
            case Command.Batch<EditorDemo.Msg> batch -> batch.commands().forEach(item -> inspect(item, pending));
            case Command.Dispatch<EditorDemo.Msg> dispatch -> pending.add(dispatch.message());
            case Command.Scoped<EditorDemo.Msg> scoped -> inspect(scoped.command(), pending);
            default -> {}
        }
    }

    @Test
    void theDemoKeepsTwoIndependentDraftsAndTypesOnlyIntoTheFocusedPane() {
        var driver = demo();
        assertThat(driver.model().left().text()).isEqualTo(EditorDemo.LEFT_TEXT);
        assertThat(driver.model().right().text()).isEqualTo(EditorDemo.RIGHT_TEXT);
        press(driver, "Z", 80);
        assertThat(driver.model().left().text()).endsWith("Z");
        assertThat(driver.model().right().text()).isEqualTo(EditorDemo.RIGHT_TEXT);
        press(driver, "\t", 80);
        assertThat(driver.model().leftFocused()).isFalse();
        press(driver, "Q", 80);
        assertThat(driver.model().right().text()).endsWith("Q");
        assertThat(driver.model().left().text()).endsWith("Z");
        assertThat(lines(driver, 80)).anySatisfy(line -> assertThat(line).contains("focus=right"));
    }

    @Test
    void theDemoTogglesEveryEditingModeIncludingAfterAResize() {
        var driver = demo();
        press(driver, "\u001bt", 80);
        assertThat(driver.model().dynamicHeight()).isTrue();
        press(driver, "\u001bl", 80);
        assertThat(driver.model().lineNumbers()).isTrue();
        press(driver, "\u001bm", 80);
        assertThat(driver.model().caret()).isEqualTo(Editor.Caret.NATIVE);
        press(driver, "\u001bp", 80);
        assertThat(driver.model().placeholder()).isTrue();

        List<String> wide = lines(driver, 80);
        assertThat(wide).anySatisfy(line -> assertThat(line).contains("lines=on height=dynamic cursor=native"));
        assertThat(wide).anySatisfy(line -> assertThat(line).contains("wrap=38"));
        // A narrower terminal re-wraps the same scene and reports the new width.
        List<String> narrow = lines(driver, 40);
        assertThat(narrow).anySatisfy(line -> assertThat(line).contains("wrap=18"));
        assertThat(narrow.stream().allMatch(row -> row.length() <= 40)).isTrue();
        assertThat(driver.model().left().caret())
                .as("a resize repaints the same draft without editing it")
                .isEqualTo(EditorDemo.LEFT_TEXT.length());
    }

    @Test
    void theDemoRoutesAnEditorsOutputUpwardWithoutChangingItsDraft() {
        var driver = demo();
        press(driver, "\u001b\r", 80);
        assertThat(driver.model().last()).isEqualTo("left-submit:" + EditorDemo.LEFT_TEXT.length());
        assertThat(driver.model().left().text()).isEqualTo(EditorDemo.LEFT_TEXT);
        press(driver, "\u001b", 80);
        assertThat(driver.latest().command()).isInstanceOf(Command.Quit.class);
    }

    // ── preserved contracts ────────────────────────────────────────────────

    @Test
    void renderingIsPureAndAMessageThatChangesNothingKeepsItsReference() {
        EditorConfig options = wrapped(10).withVisibleLines(3);
        Editor.Model state = new Editor.Model("alpha beta", 3, 3, 0);
        StyledText first = Editor.render(state, options, true);
        assertThat(Editor.render(state, options, true)).isEqualTo(first);
        assertThat(Editor.render(state, options, true)).isEqualTo(first);
        assertThat(new Editor(options).update(state, new Editor.Msg.MoveLeft()).model())
                .as("the same position again is a no-op")
                .isNotSameAs(state);
        Editor.Model start = new Editor.Model("alpha beta", 0, 0, 0);
        assertThat(new Editor(options).update(start, new Editor.Msg.MoveLeft()).model())
                .isSameAs(start);
    }

    @Test
    void anEditorThatDeclaresNoWidthPaintsTheRowsItAlwaysDid() {
        Editor.Model state = new Editor.Model("one\ntwo\nthree", 5, 5, 0);
        EditorConfig legacy = EditorConfig.defaults().withVisibleLines(2).withPrompt("> ", Style.NONE);
        assertThat(Editor.render(state, legacy, false).plainText()).isEqualTo("> one\n  two");
        assertThat(Editor.visibleLineCount(state, legacy))
                .isEqualTo(Editor.render(state, legacy, false).lines().size());
    }

    @Test
    void aFrameWithoutAHeightStillResolvesThePanesFocusTarget() {
        EditorConfig options = wrapped(10);
        Element<Editor.Msg> view = new Editor(options)
                .view(new Editor.Model("alpha beta", 3, 3, 0), CONTEXT)
                .map(action -> action);
        assertThat(ElementScreen.render(view, 0, 0).lines()).isEmpty();
        assertThat(InteractionRouter.focusables(
                        io.github.dhoard.braxton.ui.ElementTree.layout(view, new Rect(0, 0, 4, 10))))
                .hasSize(1);
    }
}

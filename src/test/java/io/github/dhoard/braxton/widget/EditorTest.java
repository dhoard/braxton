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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;

/** Editor behaviour is a pure reducer; side effects are intents. */
class EditorTest {

    private static final String FAMILY = new String(new int[] {
            0x1F469, 0x200D, 0x1F469, 0x200D, 0x1F467}, 0, 5);
    private static final EditorOptions OPTIONS = EditorOptions.defaults()
            .withMaxLength(10).withVisibleLines(3).withPrompt("> ", io.github.dhoard.braxton.style.Style.NONE);

    private static EditorState update(EditorState state, EditorAction action) {
        return Editor.update(state, OPTIONS, action).state();
    }

    /** The first row a viewport paints for {@code state}, through the real layout path. */
    private static String painted(EditorState state, EditorOptions options, boolean focused) {
        return paintedLines(state, options, focused).get(0);
    }

    /** Every row a viewport paints for {@code state}, through the real layout path. */
    private static java.util.List<String> paintedLines(EditorState state, EditorOptions options,
            boolean focused) {
        return new io.github.dhoard.braxton.view.ScreenRenderer().plainLines(
                Editor.view(io.github.dhoard.braxton.view.ElementId.of("composer"), state, options,
                        focused, (EditorAction action) -> action),
                new io.github.dhoard.braxton.layout.Viewport(2, 24));
    }

    @Test
    void insertionHappensAtTheCaretAndMovesIt() {
        EditorState state = EditorState.of("ab");
        state = Editor.update(state, OPTIONS, new EditorAction.MoveLeft()).state();
        state = update(state, new EditorAction.Insert("X"));
        assertThat(state.text()).isEqualTo("aXb");
        assertThat(state.caret()).isEqualTo(2);
        assertThat(state.anchor()).isEqualTo(2);
        assertThat(state.hasSelection()).isFalse();
    }

    @Test
    void insertionBeforeACombiningMarkMovesPastTheJoinedGrapheme() {
        EditorOptions options = EditorOptions.defaults();
        EditorState state = EditorState.of("\u0301").withCaret(0);
        state = Editor.update(state, options, new EditorAction.Insert("a")).state();
        assertThat(state.text()).isEqualTo("a\u0301");
        assertThat(state.caret()).isEqualTo(2);
        assertThat(state.anchor()).isEqualTo(2);
        assertThat(state.hasSelection()).isFalse();

        state = Editor.update(state, options, new EditorAction.Insert("b")).state();
        assertThat(state.text()).isEqualTo("a\u0301b");
        assertThat(state.caret()).isEqualTo(3);
        assertThat(state.anchor()).isEqualTo(3);
    }

    @Test
    void insertionBeforeAnEmojiMovesPastTheJoinedZwjGraphemeOnly() {
        EditorOptions options = EditorOptions.defaults();
        EditorState state = EditorState.of("\uD83D\uDCBBx").withCaret(0);
        state = Editor.update(state, options,
                new EditorAction.Insert("\uD83D\uDC69\u200D")).state();
        assertThat(state.text()).isEqualTo("\uD83D\uDC69\u200D\uD83D\uDCBBx");
        assertThat(state.caret()).isEqualTo(5);
        assertThat(state.anchor()).isEqualTo(5);
        assertThat(state.hasSelection()).isFalse();

        state = Editor.update(state, options, new EditorAction.Insert("b")).state();
        assertThat(state.text()).isEqualTo("\uD83D\uDC69\u200D\uD83D\uDCBBbx");
        assertThat(state.caret()).isEqualTo(6);
        assertThat(state.anchor()).isEqualTo(6);
    }

    @Test
    void backspaceThatJoinsGraphemesLeavesTheCaretAfterTheJoinedCluster() {
        EditorOptions options = EditorOptions.defaults();
        EditorState state = EditorState.of("note\n\u0301x");
        state = Editor.update(state, options, new EditorAction.MoveHome()).state();
        assertThat(state.caret()).isEqualTo(5);

        state = Editor.update(state, options, new EditorAction.Backspace()).state();
        assertThat(state.text()).isEqualTo("note\u0301x");
        assertThat(state.caret()).isEqualTo(5);
        assertThat(state.anchor()).isEqualTo(5);
        assertThat(state.hasSelection()).isFalse();
        // The block cursor covers the text after the joined cluster, not the surviving base.
        assertThat(painted(state, options, true)).isEqualTo("note\u0301" + Editor.CURSOR);

        state = Editor.update(state, options, new EditorAction.Insert("Z")).state();
        assertThat(state.text()).isEqualTo("note\u0301Zx");
        assertThat(state.caret()).isEqualTo(6);
    }

    @Test
    void deleteAndSelectionDeleteThatJoinGraphemesLeaveTheCaretAfterTheJoinedCluster() {
        EditorOptions options = EditorOptions.defaults();

        EditorState deleted = new EditorState("note\n\u0301x", 4, 4, 0);
        deleted = Editor.update(deleted, options, new EditorAction.Delete()).state();
        assertThat(deleted.text()).isEqualTo("note\u0301x");
        assertThat(deleted.caret()).isEqualTo(5);
        assertThat(deleted.anchor()).isEqualTo(5);

        EditorState selected = new EditorState("note\n\u0301x", 4, 5, 0);
        selected = Editor.update(selected, options, new EditorAction.Delete()).state();
        assertThat(selected.text()).isEqualTo("note\u0301x");
        assertThat(selected.caret()).isEqualTo(5);
        assertThat(selected.anchor()).isEqualTo(5);

        EditorState backspaced = new EditorState("note\n\u0301x", 5, 4, 0);
        backspaced = Editor.update(backspaced, options, new EditorAction.Backspace()).state();
        assertThat(backspaced.text()).isEqualTo("note\u0301x");
        assertThat(backspaced.caret()).isEqualTo(5);
        assertThat(backspaced.anchor()).isEqualTo(5);
    }

    @Test
    void backspaceOverATabSeparatingABaseAndAMarkKeepsTheJoinedCluster() {
        EditorOptions options = EditorOptions.defaults();
        EditorState state = new EditorState("a\t\u0301b", 2, 2, 0);

        state = Editor.update(state, options, new EditorAction.Backspace()).state();
        assertThat(state.text()).isEqualTo("a\u0301b");
        assertThat(state.caret()).isEqualTo(2);
        assertThat(state.anchor()).isEqualTo(2);

        state = Editor.update(state, options, new EditorAction.Insert("Z")).state();
        assertThat(state.text()).isEqualTo("a\u0301Zb");
        assertThat(state.caret()).isEqualTo(3);
    }

    @Test
    void deletionThatRemovesANewlineWithoutAJoinKeepsTheCaretAtTheDeletionPoint() {
        EditorOptions options = EditorOptions.defaults();
        EditorState state = new EditorState("ab\ncd", 3, 3, 0);

        state = Editor.update(state, options, new EditorAction.Backspace()).state();
        assertThat(state.text()).isEqualTo("abcd");
        assertThat(state.caret()).isEqualTo(2);

        state = new EditorState("ab\ncd", 2, 2, 0);
        state = Editor.update(state, options, new EditorAction.Delete()).state();
        assertThat(state.text()).isEqualTo("abcd");
        assertThat(state.caret()).isEqualTo(2);
    }

    @Test
    void backspaceRemovesAWholeGrapheme() {
        EditorState state = EditorState.of("a" + FAMILY);
        assertThat(update(state, new EditorAction.Backspace()).text()).isEqualTo("a");
        assertThat(update(EditorState.empty(), new EditorAction.Backspace()).text()).isEmpty();
    }

    @Test
    void deleteRemovesTheNextGraphemeAndStopsAtTheEnd() {
        EditorState state = EditorState.of("ab");
        state = new EditorState("ab", 0, 0, 0);
        assertThat(update(state, new EditorAction.Delete()).text()).isEqualTo("b");
        EditorState end = EditorState.of("ab");
        assertThat(update(end, new EditorAction.Delete()).text()).isEqualTo("ab");
    }

    @Test
    void typingReplacesTheSelection() {
        EditorState state = EditorState.of("hello").withSelection(0, 5);
        assertThat(update(state, new EditorAction.Insert("X")).text()).isEqualTo("X");
    }

    @Test
    void selectionAllAndCopyIntentUseTheSelectionOrTheDraft() {
        EditorState state = EditorState.of("hello");
        EditorResult all = Editor.update(state, OPTIONS, new EditorAction.SelectAll());
        assertThat(all.state().selection()).isEqualTo("hello");
        EditorIntent.Copy copy = (EditorIntent.Copy) Editor.update(all.state(), OPTIONS,
                new EditorAction.Copy()).intent();
        assertThat(copy.text()).isEqualTo("hello");
        EditorIntent.Copy whole = (EditorIntent.Copy) Editor.update(state, OPTIONS,
                new EditorAction.Copy()).intent();
        assertThat(whole.text()).isEqualTo("hello");
    }

    @Test
    void passwordEditorsDoNotCopyAndRenderMasks() {
        EditorOptions password = OPTIONS.withMasking(EditorOptions.Masking.PASSWORD);
        EditorState state = EditorState.of("secret");
        EditorIntent.Copy copy = (EditorIntent.Copy) Editor.update(state, password,
                new EditorAction.Copy()).intent();
        assertThat(copy.text()).isEmpty();
        assertThat(Editor.render(state, password, true).plainText())
                .doesNotContain("secret").contains("\u2022");
    }

    @Test
    void maskedDisplayTextKeepsTheDraftLineStructure() {
        EditorOptions password = OPTIONS.withMasking(EditorOptions.Masking.PASSWORD)
                .withPrompt("", io.github.dhoard.braxton.style.Style.NONE);
        EditorState state = EditorState.of("ab\nc");
        assertThat(state.displayText(password))
                .isEqualTo(Editor.render(state, password, false).plainText())
                .isEqualTo("\u2022\u2022\n\u2022");
    }

    @Test
    void hiddenEditorsRenderNoContent() {
        EditorOptions hidden = OPTIONS.withMasking(EditorOptions.Masking.HIDDEN);
        assertThat(Editor.render(EditorState.of("secret"), hidden, false).plainText())
                .doesNotContain("secret");
    }

    @Test
    void aMaskedCaretNeverRevealsTheCharacterUnderIt() {
        EditorOptions masked = OPTIONS.withMasking(EditorOptions.Masking.PASSWORD);
        for (int caret = 0; caret <= 3; caret++) {
            String rendered = Editor.render(new EditorState("abc", caret, caret, 0), masked, true)
                    .plainText();
            assertThat(rendered).as("caret %d", caret)
                    .doesNotContain("a").doesNotContain("b").doesNotContain("c");
            assertThat(rendered.chars().filter(c -> c == 0x2022).count())
                    .as("masks at caret %d", caret)
                    .isEqualTo(caret < 3 ? 2 : 3);
        }
    }

    @Test
    void lengthLimitTruncatesAtAGraphemeBoundary() {
        EditorOptions limit = OPTIONS.withMaxLength(2);
        EditorState state = Editor.update(EditorState.empty(), limit,
                new EditorAction.Insert("\uD83D\uDE00\uD83D\uDE00")).state();
        assertThat(state.text()).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void pasteRespectsTheLengthLimitAndKeepsGraphemesWhole() {
        EditorOptions limit = OPTIONS.withMaxLength(4);
        EditorState state = Editor.update(EditorState.empty(), limit,
                new EditorAction.Paste("\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D")).state();
        assertThat(state.text()).isEqualTo("\uD83D\uDC4D\uD83D\uDC4D");
    }

    @Test
    void theLimitGovernsInputAndNeverTruncatesExistingText() {
        EditorOptions limit = OPTIONS.withMaxLength(3);
        EditorState state = EditorState.of("abcdef");
        EditorState after = Editor.update(state, limit, new EditorAction.Insert("x")).state();
        assertThat(after.text()).isEqualTo("abcdef");
    }

    @Test
    void unlimitedLengthAcceptsLongInput() {
        EditorOptions unlimited = EditorOptions.defaults().withUnlimitedLength();
        String longText = "x".repeat(5_000);
        assertThat(Editor.update(EditorState.empty(), unlimited,
                new EditorAction.Insert(longText)).state().text()).hasSize(5_000);
    }

    @Test
    void multilineDraftsReportVisibleLineCount() {
        EditorState state = EditorState.of("1\n2\n3\n4\n5");
        assertThat(state.lineCount()).isEqualTo(5);
        assertThat(Editor.visibleLineCount(state, OPTIONS)).isEqualTo(3);
        assertThat(Editor.visibleLineCount(state, EditorOptions.defaults())).isEqualTo(5);
    }

    @Test
    void homeAndEndMoveWithinTheCurrentLine() {
        EditorState state = EditorState.of("one\ntwo");
        assertThat(update(state, new EditorAction.MoveHome()).caret()).isEqualTo(4);
        EditorState start = EditorState.of("one\ntwo");
        assertThat(update(new EditorState(start.text(), 5, 5, 0), new EditorAction.MoveEnd()).caret())
                .isEqualTo(7);
    }

    @Test
    void verticalMovementKeepsThePreferredColumnWithinBounds() {
        EditorState state = EditorState.of("abcd\nx");
        state = new EditorState(state.text(), 3, 3, 0);
        EditorState down = update(state, new EditorAction.MoveDown());
        assertThat(down.caret()).isEqualTo("abcd\nx".length());
        // Painted column 5: the draft's "abc" after the two-column prompt.
        assertThat(down.preferredColumn()).isEqualTo(5);
        assertThat(update(down, new EditorAction.MoveUp()).caret()).isEqualTo(3);
    }

    @Test
    void verticalMovementKeepsTheVisualColumnAcrossWideGlyphs() {
        String text = "a\u65E5b\nabcdef";
        // After "a\u65E5" the caret is at grapheme 2 but visual column 3, painted at column 5.
        EditorState state = new EditorState(text, 2, 2, 0);
        EditorState down = update(state, new EditorAction.MoveDown());
        assertThat(text.substring(4, down.caret())).isEqualTo("abc");
        assertThat(down.preferredColumn()).isEqualTo(5);
        assertThat(update(down, new EditorAction.MoveUp()).caret()).isEqualTo(2);
    }

    @Test
    void verticalMovementCountsATabAsTheColumnsTheTextViewPaints() {
        String text = "a\tb\nabcdef";
        // The row is painted after the two-column prompt, which expands the tab to the next
        // four-column stop from there: one cell, so the caret before "b" sits at painted column 4
        // and stays two columns into the target line. Counting the tab from column 0 would put it
        // at column 4 of the draft and land the caret four columns in.
        EditorState state = new EditorState(text, 2, 2, 0);
        EditorState down = update(state, new EditorAction.MoveDown());
        assertThat(down.caret()).isEqualTo(text.indexOf("abcdef") + 2);
        assertThat(down.preferredColumn()).isEqualTo(4);
        assertThat(update(down, new EditorAction.MoveUp()).caret()).isEqualTo(2);
    }

    @Test
    void verticalMovementCountsATabFromThePaintedPromptColumn() {
        // The prompt prefixes every visible draft line, so a tab in the draft reaches its stop from
        // the prompt's width. Measuring it from column 0 targets a different character and slides
        // the caret left by the prompt's width modulo the tab stop.
        EditorOptions options = EditorOptions.defaults().withPrompt("> ", Style.NONE)
                .withVisibleLines(2);
        String text = "ab\tc\n0123456789";
        EditorState state = new EditorState(text, 3, 3, 0);
        assertThat(paintedLines(state, options, true))
                .containsExactly("> ab    " + Editor.CURSOR, "  0123456789");

        EditorState down = Editor.update(state, options, new EditorAction.MoveDown()).state();
        assertThat(paintedLines(down, options, true))
                .containsExactly("> ab    c", "  012345" + Editor.CURSOR + "789");
        // Six columns into the target line: the painted column 8 less the prompt's two columns.
        assertThat(down.caret()).isEqualTo(state.lineStart(1) + 6);
        assertThat(down.preferredColumn()).isEqualTo(8);
        assertThat(Editor.update(down, options, new EditorAction.MoveUp()).state().caret())
                .isEqualTo(3);
    }

    @Test
    void verticalMovementSeeksATabOnTheTargetLineFromThePaintedPromptColumn() {
        // The same gap in the opposite direction: seeking into a target line that contains a tab has
        // to count it from the prompt, or the caret lands short of the painted column.
        EditorOptions options = EditorOptions.defaults().withPrompt("> ", Style.NONE)
                .withVisibleLines(2);
        String text = "abcdef\n0\tabc";
        EditorState state = new EditorState(text, 4, 4, 0);
        assertThat(paintedLines(state, options, true))
                .containsExactly("> abcd" + Editor.CURSOR + "f", "  0 abc");

        EditorState down = Editor.update(state, options, new EditorAction.MoveDown()).state();
        assertThat(paintedLines(down, options, true))
                .containsExactly("> abcdef", "  0 ab" + Editor.CURSOR);
        // Four columns into the target line: the tab there occupies the single cell left before
        // the painted column 6, so the caret lands on "c" rather than on the tab after "0 ".
        assertThat(down.caret()).isEqualTo(state.lineStart(1) + 4);
        assertThat(down.preferredColumn()).isEqualTo(6);
        assertThat(Editor.update(down, options, new EditorAction.MoveUp()).state().caret())
                .isEqualTo(4);
    }

    @Test
    void verticalMovementChargesABaselessClusterToTheCellTheRowPaintsItIn() {
        // A draft cluster with no base character of its own is painted inside the cell the row drew
        // before it - the prompt's trailing space, or the last space of the blank stand-in a
        // continuation line carries - exactly as LayoutEngine continues a cell across a span
        // boundary. Charging it a cell of its own (or none, for a lone selector) draws the block in
        // one column and lands the caret a cluster away from it on every vertical move.
        EditorOptions options = EditorOptions.defaults().withPrompt("> ", Style.NONE)
                .withVisibleLines(2);
        EditorState state = new EditorState("\u0301abcdef\nabcdefgh", 7, 7, 0);
        assertThat(paintedLines(state, options, true))
                .containsExactly("> \u0301abcdef" + Editor.CURSOR, "  abcdefgh");

        EditorState down = Editor.update(state, options, new EditorAction.MoveDown()).state();
        // The mark shares the prompt's second cell, so the row drew the block in column 8.
        assertThat(down.preferredColumn()).isEqualTo(8);
        assertThat(down.caret()).isEqualTo(state.lineStart(1) + 6);
        assertThat(paintedLines(down, options, true))
                .containsExactly("> \u0301abcdef", "  abcdef" + Editor.CURSOR + "h");
        // The way back returns to the offset that cell was drawn for.
        assertThat(Editor.update(down, options, new EditorAction.MoveUp()).state().caret())
                .isEqualTo(7);

        // Seeking into a line that opens with a mark charges the joined cell there too: the target
        // row paints its first draft cluster inside the stand-in's last cell.
        String target = "abcdef\n\u0301abcdefgh";
        EditorState source = new EditorState(target, 6, 6, 0);
        EditorState moved = Editor.update(source, options, new EditorAction.MoveDown()).state();
        assertThat(moved.preferredColumn()).isEqualTo(8);
        assertThat(moved.caret()).isEqualTo(source.lineStart(1) + 7);
        assertThat(paintedLines(moved, options, true))
                .containsExactly("> abcdef", "  \u0301abcdef" + Editor.CURSOR + "h");

        // The opposite sign: a lone selector claims no cell of its own but widens the one it joins,
        // so the block is drawn in column 9 while an arithmetic that charged it nothing aimed at 8.
        EditorState selector = new EditorState("\uFE0Fabcdef\nabcdefgh", 7, 7, 0);
        assertThat(paintedLines(selector, options, true))
                .containsExactly("> \uFE0Fabcdef" + Editor.CURSOR, "  abcdefgh");
        EditorState selectorDown = Editor.update(selector, options,
                new EditorAction.MoveDown()).state();
        assertThat(selectorDown.preferredColumn()).isEqualTo(9);
        assertThat(selectorDown.caret()).isEqualTo(selector.lineStart(1) + 7);
        assertThat(paintedLines(selectorDown, options, true))
                .containsExactly("> \uFE0Fabcdef", "  abcdefg" + Editor.CURSOR);
    }

    @Test
    void aBaselessClusterAtTheHeadOfALineAgreesWithThePaintWhereItKeepsItsOwnCell() {
        EditorState mark = new EditorState("\u0301abcdef\nabcdefgh", 7, 7, 0);
        // With no prompt the row gives the mark its own cell - the dotted circle the text view
        // paints in place of the base it lacks - so the arithmetic and the paint already agree.
        EditorOptions plain = EditorOptions.defaults().withVisibleLines(2);
        assertThat(paintedLines(mark, plain, true))
                .containsExactly("\u25CC\u0301abcdef" + Editor.CURSOR, "abcdefgh");
        EditorState plainDown = Editor.update(mark, plain, new EditorAction.MoveDown()).state();
        assertThat(plainDown.preferredColumn()).isEqualTo(7);
        assertThat(plainDown.caret()).isEqualTo(mark.lineStart(1) + 7);

        // A masked row draws one mask glyph per cluster, and that glyph is a base of its own: no
        // join happens, so one cell per cluster is still the column the row paints.
        EditorOptions password = EditorOptions.defaults().withPrompt("> ", Style.NONE)
                .withVisibleLines(2).withMasking(EditorOptions.Masking.PASSWORD);
        assertThat(paintedLines(mark, password, true).get(0))
                .isEqualTo("> \u2022\u2022\u2022\u2022\u2022\u2022\u2022" + Editor.CURSOR);
        EditorState maskedDown = Editor.update(mark, password,
                new EditorAction.MoveDown()).state();
        assertThat(maskedDown.preferredColumn()).isEqualTo(9);
        assertThat(maskedDown.caret()).isEqualTo(mark.lineStart(1) + 7);

        // A prompt that ends with a tab reaches its last cell as the space of a stop, and the text
        // view continues a stop's space only with the clusters that claim no column of their own.
        // The mark therefore keeps the column it measures there, dotted circle in front of it.
        EditorOptions tab = EditorOptions.defaults().withPrompt(">\t", Style.NONE)
                .withVisibleLines(2);
        EditorState stop = new EditorState("\u0301abcdef\nabcdefgh", 7, 7, 0);
        assertThat(paintedLines(stop, tab, true))
                .containsExactly(">   \u25CC\u0301abcdef" + Editor.CURSOR, "    abcdefgh");
        EditorState stopDown = Editor.update(stop, tab, new EditorAction.MoveDown()).state();
        assertThat(stopDown.preferredColumn()).isEqualTo(11);
        assertThat(stopDown.caret()).isEqualTo(stop.lineStart(1) + 7);
        assertThat(paintedLines(stopDown, tab, true))
                .containsExactly(">   \u25CC\u0301abcdef", "    abcdefg" + Editor.CURSOR);
        // The selector that claims no column does join that space, and is charged the column it
        // gives the cell it joins.
        EditorState selectorStop = new EditorState("\uFE0Fabcdef\nabcdefgh", 7, 7, 0);
        assertThat(paintedLines(selectorStop, tab, true))
                .containsExactly(">   \uFE0Fabcdef" + Editor.CURSOR, "    abcdefgh");
        assertThat(Editor.update(selectorStop, tab, new EditorAction.MoveDown()).state()
                .preferredColumn()).isEqualTo(11);
    }

    @Test
    void verticalMovementStopsBeforeAWideGlyphItCannotFitInto() {
        String text = "abcdef\na\u65E5b";
        // Painted column 4 falls inside the wide glyph on the shorter line.
        EditorState state = new EditorState(text, 2, 2, 0);
        EditorState down = update(state, new EditorAction.MoveDown());
        assertThat(down.caret()).isEqualTo(text.indexOf('\u65E5'));
    }

    @Test
    void verticalMovementRestoresThePreferredColumnAfterAShortLine() {
        String text = "abcdef\nx\nabcdef";
        EditorState state = new EditorState(text, 6, 6, 0);
        EditorState first = update(state, new EditorAction.MoveDown());
        // Painted column 8: the draft's "abcdef" after the two-column prompt.
        assertThat(first.preferredColumn()).isEqualTo(8);
        EditorState second = update(first, new EditorAction.MoveDown());
        assertThat(second.caret()).isEqualTo(text.length());
        assertThat(second.preferredColumn()).isEqualTo(8);
    }

    @Test
    void submitAndPasteRequestsAreIntentsNotMutations() {
        EditorState state = EditorState.of("hi");
        EditorResult submit = Editor.update(state, OPTIONS, new EditorAction.Submit());
        assertThat(submit.intent()).isEqualTo(new EditorIntent.Submit("hi"));
        assertThat(submit.state()).isEqualTo(state);
        assertThat(Editor.update(state, OPTIONS, new EditorAction.PasteRequest()).intent())
                .isEqualTo(new EditorIntent.PasteRequest());
    }

    @Test
    void pasteIsSanitizedAndInsertedAtomically() {
        EditorState state = EditorState.of("x");
        EditorState pasted = update(state, new EditorAction.Paste("  a  \r\n\tb  \r\n\r\n"));
        assertThat(pasted.text()).isEqualTo("x  a\n\tb");
    }

    @Test
    void aggressivePasteTrimsAndCollapsesBlankLines() {
        EditorOptions aggressive = OPTIONS.withPaste(io.github.dhoard.braxton.clipboard.PasteMode.AGGRESSIVE, true);
        EditorState pasted = Editor.update(EditorState.empty(), aggressive,
                new EditorAction.Paste("  a  \n\n\n  b  ")).state();
        assertThat(pasted.text()).isEqualTo("a\n\nb");
    }

    @Test
    void controlJInsertsOneNewlineAndRendersOneCaret() {
        EditorState after = update(EditorState.of("ab"), new EditorAction.Newline());
        assertThat(after.text()).isEqualTo("ab\n");
        assertThat(after.caret()).isEqualTo(3);
        String rendered = Editor.render(after, OPTIONS, true).plainText();
        assertThat(rendered.chars().filter(c -> c == Editor.CURSOR.charAt(0)).count())
                .isEqualTo(1);
    }

    @Test
    void aMaskedDraftNeverCopiesEvenWhenCopyingIsEnabled() {
        EditorOptions masked = OPTIONS.withMasking(EditorOptions.Masking.PASSWORD)
                .withCopyEnabled(true);
        assertThat(masked.canCopy()).isFalse();
        assertThat(Editor.copyableText(EditorState.of("secret"), masked)).isEmpty();
        EditorIntent.Copy copy = (EditorIntent.Copy) Editor.update(EditorState.of("secret"),
                masked, new EditorAction.Copy()).intent();
        assertThat(copy.text()).isEmpty();

        assertThat(OPTIONS.canCopy()).isTrue();
        assertThat(OPTIONS.withCopyEnabled(false).canCopy()).isFalse();
    }

    @Test
    void copyingADisabledVisibleDraftReturnsAnEmptyIntent() {
        // copyEnabled=false opts a visible field out of copying entirely, so the Copy intent carries
        // no text even though the draft is not masked.
        EditorResult result = Editor.update(EditorState.of("secret"), OPTIONS.withCopyEnabled(false),
                new EditorAction.Copy());
        assertThat(result.intent()).isInstanceOfSatisfying(EditorIntent.Copy.class,
                copy -> assertThat(copy.text()).isEmpty());
        assertThat(Editor.copyableText(EditorState.of("secret"), OPTIONS.withCopyEnabled(false)))
                .isEmpty();
    }

    @Test
    void renderingShowsThePromptSelectionAndBlockCursor() {
        EditorState state = EditorState.of("ab").withSelection(0, 1);
        StyledText rendered = Editor.render(state, OPTIONS, true);
        assertThat(rendered.plainText()).contains("> ");
        assertThat(rendered.plainText()).contains(Editor.CURSOR);
        assertThat(rendered.spans()).anySatisfy(span ->
                assertThat(span.style().invert()).isTrue());
    }

    @Test
    void thePromptIsDrawnOnceAtTheStartOfTheVisibleWindow() {
        // The prompt marks the input area, not the caret: drawing it on the caret's line as well
        // puts a second prompt (and a second caret-like marker next to the block cursor) on an
        // added line, which is the reported double caret in the AI Harness composer.
        EditorState state = new EditorState("one\ntwo\nthree", 13, 13, 0);
        assertThat(Editor.render(state, OPTIONS, true).plainText())
                .isEqualTo("> one\n  two\n  three" + Editor.CURSOR);
    }

    @Test
    void theBlockCursorCoversAWideGraphemeWithoutMovingTheRestOfTheLine() {
        // The block cursor covers the grapheme under it. A two-column grapheme occupies two cells,
        // so one block leaves the line a column short and slides everything after the caret left.
        EditorState wide = EditorState.of("a\u6F22b").withCaret(1);
        assertThat(Editor.render(wide, EditorOptions.defaults(), false).plainText())
                .isEqualTo("a\u6F22b");
        assertThat(Editor.render(wide, EditorOptions.defaults(), true).plainText())
                .isEqualTo("a" + Editor.CURSOR.repeat(2) + "b");

        EditorState family = EditorState.of(FAMILY + "x").withCaret(0);
        assertThat(Editor.render(family, EditorOptions.defaults(), false).plainText())
                .isEqualTo(FAMILY + "x");
        assertThat(Editor.render(family, EditorOptions.defaults(), true).plainText())
                .isEqualTo(Editor.CURSOR.repeat(2) + "x");
    }

    @Test
    void theBlockCursorOnATabCoversTheColumnsTheTextViewPaints() {
        // A tab paints the gap to the next four-column stop, exactly as the caret's own navigation
        // counts it. A one-cell block over it leaves the line short and slides everything after the
        // caret left, the same way a one-cell block over a wide grapheme would.
        EditorState onTab = EditorState.of("a\tb").withCaret(1);
        assertThat(painted(onTab, EditorOptions.defaults(), false)).isEqualTo("a   b");
        assertThat(painted(onTab, EditorOptions.defaults(), true))
                .isEqualTo("a" + Editor.CURSOR.repeat(3) + "b");
        // The prompt is painted on the same line, so a tab after it reaches the next stop from
        // there: the block covers the one cell that is left, not the three it covers at column 0.
        EditorOptions prompted = EditorOptions.defaults().withPrompt("> ", Style.NONE);
        assertThat(painted(onTab, prompted, false)).isEqualTo("> a b");
        assertThat(painted(onTab, prompted, true)).isEqualTo("> a" + Editor.CURSOR + "b");
    }

    @Test
    void unfocusedEditorsDoNotDrawACursor() {
        assertThat(Editor.render(EditorState.of("ab"), OPTIONS, false).plainText())
                .doesNotContain(Editor.CURSOR);
    }

    @Test
    void inputMappingIsPureAndIgnoresModifiedPrintableKeys() {
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("x"), "x"), OPTIONS))
                .contains(new EditorAction.Insert("x"));
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.SPACE, " "), OPTIONS))
                .contains(new EditorAction.Insert(" "));
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.ENTER), OPTIONS))
                .contains(new EditorAction.Submit());
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("j", Modifiers.CTRL)), OPTIONS))
                .contains(new EditorAction.Newline());
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("c", Modifiers.ALT)), OPTIONS))
                .contains(new EditorAction.Copy());
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("v", Modifiers.CTRL)), OPTIONS))
                .contains(new EditorAction.PasteRequest());
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("c", Modifiers.CTRL), "c"),
                OPTIONS)).isEmpty();
    }

    @Test
    void aConfiguredPrintableCopyOrPasteKeyWinsOverInsertingItsText() {
        // shift+c and shift+v carry text, but a binding the caller configured must still fire.
        EditorOptions options = EditorOptions.defaults()
                .withKeys(Key.of("c", Modifiers.SHIFT), Key.of("v", Modifiers.SHIFT));
        assertThat(Editor.actionFor(new InputEvent.KeyInput(Key.of("c", Modifiers.SHIFT), "C", 0L),
                options)).contains(new EditorAction.Copy());
        assertThat(Editor.actionFor(new InputEvent.KeyInput(Key.of("v", Modifiers.SHIFT), "V", 0L),
                options)).contains(new EditorAction.PasteRequest());
        // With the default bindings the same gesture stays printable text.
        assertThat(Editor.actionFor(new InputEvent.KeyInput(Key.of("c", Modifiers.SHIFT), "C", 0L),
                EditorOptions.defaults())).contains(new EditorAction.Insert("C"));
    }

    @Test
    void anInsertedCarriageReturnBecomesTheEditorsLineBreak() {
        // The editor's line model is LF (lineAt/lineEnd, the decoder's newline and the paste
        // sanitizer all agree), so a raw CR must not sit in the draft as an invisible character
        // that shifts offsets without breaking a line.
        EditorState state = Editor.update(EditorState.empty(), OPTIONS,
                new EditorAction.Insert("a\r\nb\rc")).state();
        assertThat(state.text()).isEqualTo("a\nb\nc");
        assertThat(state.lineCount()).isEqualTo(3);
        assertThat(state.caret()).isEqualTo(state.text().length());
        assertThat(state.displayText(OPTIONS)).doesNotContain("\r");
    }

    @Test
    void aDraftSeededWithLineEndingsBecomesTheEditorsOwnDraft() {
        // The draft lives in the application's model, so it arrives from a file, a fixture, or a
        // restored session with whatever break that platform writes. The reducer normalizes what it
        // inserts; the state's entry points have to prepare what they are given, or the draft holds
        // a character the row paints as no cell at all while the caret's column arithmetic counts
        // it as one.
        for (String raw : java.util.List.of("a\r\nb", "a\rb", "a\nb")) {
            String label = raw.replace("\r", "\\r").replace("\n", "\\n");
            EditorState seeded = EditorState.of(raw);
            assertThat(seeded.text()).as("draft of %s", label).isEqualTo("a\nb");
            assertThat(seeded.text()).as("no carriage return in %s", label).doesNotContain("\r");
            assertThat(seeded.lineCount()).as("lines of %s", label).isEqualTo(2);
            // The offsets the caller passed are its own coordinates: the end of the text it gave.
            assertThat(seeded.caret()).as("caret of %s", label)
                    .isEqualTo(seeded.text().length());
            assertThat(seeded.anchor()).as("anchor of %s", label)
                    .isEqualTo(seeded.text().length());
            assertThat(EditorState.empty().withText(raw).text()).as("withText of %s", label)
                    .isEqualTo("a\nb");
        }
        // A CR/LF pair is one break, so it costs one unit: an offset the caller counted past the
        // break names the same character of the prepared draft it named in the text it supplied.
        EditorState seeded = new EditorState("ab\r\ncd", 4, 0, 0);
        assertThat(seeded.text()).isEqualTo("ab\ncd");
        assertThat(seeded.caret()).isEqualTo(3);
        assertThat(seeded.caretLine()).contains("cd");
        assertThat(new EditorState("ab\r\ncd", 6, 6, 0).caret()).isEqualTo(5);
        // An offset between the CR and the LF of one pair names no grapheme boundary - the pair is
        // one cluster - so it lands at the start of the break that replaces the pair.
        assertThat(new EditorState("ab\r\ncd", 3, 3, 0).caret()).isEqualTo(2);
    }

    @Test
    void aDraftSeededWithACarriageReturnDrawsTheCaretInTheColumnTheRowPaints() {
        // preferredColumn and the caret's column are documented as the columns the row draws before
        // the caret. An unprepared CR is measured as a column and painted as no cell, which leaves
        // the block a column right of the text it sits in and lets two offsets draw it in the same
        // place. Preparing the draft is what makes the two agree.
        EditorState seeded = new EditorState("a\rb\nxyz", 2, 2, 0);
        assertThat(seeded).isEqualTo(new EditorState("a\nb\nxyz", 2, 2, 0));
        assertThat(paintedLines(seeded, OPTIONS, true))
                .isEqualTo(paintedLines(new EditorState("a\nb\nxyz", 2, 2, 0), OPTIONS, true));
        assertThat(Editor.lines(seeded)).containsExactly("a", "b", "xyz");
        // Stepping over the break moves the block: no offset hides in a column the row never paints.
        assertThat(paintedLines(new EditorState("a\rb\nxyz", 1, 1, 0), OPTIONS, true).get(0))
                .isEqualTo("> a" + Editor.CURSOR);
        assertThat(paintedLines(new EditorState("a\rb\nxyz", 2, 2, 0), OPTIONS, true).get(1))
                .isEqualTo("  " + Editor.CURSOR);
        // The column a vertical move returns to is the column the row paints the block in.
        EditorState up = Editor.update(seeded, OPTIONS, new EditorAction.MoveUp()).state();
        assertThat(up.preferredColumn()).isEqualTo(2);
        assertThat(paintedLines(up, OPTIONS, true).get(0)).isEqualTo("> " + Editor.CURSOR);
    }

    @Test
    void equivalentBreakFormsInsertIdenticallyUnderALengthLimit() {
        // Conversion happens before truncation and offset math, so LF, CRLF, and lone CR from the
        // same logical value leave the same text, caret, anchor, and line count. A length limit
        // that falls inside the break sequence is where the order is observable.
        EditorOptions limited = EditorOptions.defaults().withMaxLength(4);
        EditorState base = new EditorState("ab", 1, 1, 0);
        EditorState lf = Editor.update(base, limited, new EditorAction.Insert("x\nyz")).state();
        for (String raw : java.util.List.of("x\r\nyz", "x\ryz")) {
            EditorState inserted = Editor.update(base, limited, new EditorAction.Insert(raw)).state();
            assertThat(inserted.text()).as("text for %s", raw.replace("\r", "\\r"))
                    .isEqualTo(lf.text());
            assertThat(inserted.caret()).isEqualTo(lf.caret());
            assertThat(inserted.anchor()).isEqualTo(lf.anchor());
            assertThat(inserted.lineCount()).isEqualTo(lf.lineCount());
        }
        assertThat(lf.text()).isEqualTo("ax\nb");
        assertThat(lf.caret()).isEqualTo(3);
    }

    @Test
    void aPromptWithATabKeepsContinuationLinesAligned() {
        // The prompt is painted by the text view, which expands its tab to the next four-column
        // stop, so the blank stand-in on later lines must be the painted width.
        EditorOptions options = EditorOptions.defaults().withPrompt(">\t",
                io.github.dhoard.braxton.style.Style.NONE);
        EditorState state = new EditorState("a\nb", 0, 0, 0);
        java.util.List<String> lines = new io.github.dhoard.braxton.view.ScreenRenderer().plainLines(
                Editor.view(io.github.dhoard.braxton.view.ElementId.of("composer"), state, options,
                        false, (EditorAction action) -> action),
                new io.github.dhoard.braxton.layout.Viewport(6, 12));
        assertThat(lines.get(0)).isEqualTo(">   a");
        assertThat(lines.get(1)).isEqualTo("    b");
    }

    @Test
    void aPromptLineBreakBecomesASpaceSoItCannotSplitTheInputLine() {
        // The prompt prefixes every visible input line, so it must stay on one line: a break inside
        // it paints a line the window (Editor.visibleLineCount) never budgeted and pushes the draft
        // after it off the editor's rows. CR/LF, CR and LF all become one space, exactly like the
        // other one-line decorations (Box.title, ProgressBar icons, Badge segments).
        EditorOptions options = EditorOptions.defaults().withPrompt("a\nb", Style.NONE);
        assertThat(options.prompt()).isEqualTo("a b");
        assertThat(EditorOptions.defaults().withPrompt("a\r\nb", Style.NONE).prompt())
                .isEqualTo("a b");
        assertThat(EditorOptions.defaults().withPrompt("a\rb", Style.NONE).prompt())
                .isEqualTo("a b");
        EditorState state = new EditorState("x\ny", 0, 0, 0);
        assertThat(Editor.render(state, options, true).lines())
                .hasSize(Editor.visibleLineCount(state, options));
        java.util.List<String> lines = new io.github.dhoard.braxton.view.ScreenRenderer().plainLines(
                Editor.view(io.github.dhoard.braxton.view.ElementId.of("composer"), state, options,
                        false, (EditorAction action) -> action),
                new io.github.dhoard.braxton.layout.Viewport(2, 20));
        assertThat(lines).containsExactly("a bx", "   y");
    }

    @Test
    void editorViewDeclaresFocusAndHandlesPaste() {
        View<String> view = Editor.view(io.github.dhoard.braxton.view.ElementId.of("editor"), EditorState.empty(),
                OPTIONS, true, action -> action.getClass().getSimpleName());
        assertThat(view).isInstanceOf(View.Focused.class);
        assertThat(((View.Focused<String>) view).id()).isEqualTo(io.github.dhoard.braxton.view.ElementId.of("editor"));
        assertThat(((View.Focused<String>) view).input()
                .handle(new InputEvent.PasteInput("hello")))
                .contains("Paste");
    }

    @Test
    void withStylesAssignsEachStyleToItsOwnRole() {
        Style text = Style.NONE.withBold();
        Style prompt = Style.NONE.withItalic();
        Style selection = Style.NONE.withUnderline();
        EditorOptions options = EditorOptions.defaults().withStyles(text, prompt, selection);
        assertThat(options.textStyle()).isEqualTo(text);
        assertThat(options.promptStyle()).isEqualTo(prompt);
        assertThat(options.selectionStyle()).isEqualTo(selection);
        EditorState state = EditorState.of("ab");
        assertThat(Editor.render(state, options, true).spans())
                .as("the draft uses the text style while the cursor rule is selection styling")
                .anySatisfy(span -> assertThat(span.style()).isEqualTo(text));
    }

    @Test
    void textWithersAcceptNullLikeTheConstructorAndFactoryDo() {
        EditorState state = EditorState.of("abc");
        EditorState cleared = state.withText(null);
        assertThat(cleared.text()).isEmpty();
        assertThat(cleared.caret()).isZero();
        assertThat(EditorState.of(null).text()).isEmpty();
        assertThat(new EditorState(null, 3, 1, 0).text()).isEmpty();
    }

    @Test
    void outOfRangePositionsAreClampedToGraphemeBoundaries() {
        EditorState state = new EditorState("a" + FAMILY, 99, -3, -1);
        assertThat(state.caret()).isEqualTo(("a" + FAMILY).length());
        assertThat(state.anchor()).isZero();
        assertThat(state.preferredColumn()).isZero();
    }
}

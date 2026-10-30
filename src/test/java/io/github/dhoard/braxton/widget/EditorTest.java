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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;

import org.junit.jupiter.api.Test;

/** Editor behaviour is a pure reducer; side effects are intents. */
class EditorTest {

    private static final String FAMILY = new String(new int[] {
            0x1F469, 0x200D, 0x1F469, 0x200D, 0x1F467}, 0, 5);
    private static final Editor.Config OPTIONS =
            Editor.Config.defaults()
                    .withMaxLength(10)
                    .withVisibleLines(3)
                    .withPrompt("> ", io.github.dhoard.braxton.style.Style.NONE);

    private static Editor.Model update(Editor.Model state, Editor.Msg action) {
        return new Editor(OPTIONS).update(state, action).model();
    }

    /** The first row a viewport paints for {@code state}, through the real layout path. */
    private static String painted(Editor.Model state, Editor.Config options, boolean focused) {
        return paintedLines(state, options, focused).get(0);
    }

    /** Every row a viewport paints for {@code state}, through the real layout path. */
    private static java.util.List<String> paintedLines(
            Editor.Model state, Editor.Config options, boolean focused) {
        return new io.github.dhoard.braxton.view.ScreenRenderer()
                .plainLines(
                        new Editor(options)
                                .view(
                                        (state).withFocus(focused),
                                        new io.github.dhoard.braxton.mvu.ViewContext(
                                                io.github.dhoard.braxton.layout.Viewport.EMPTY))
                                .map((Editor.Msg action) -> action),
                        new io.github.dhoard.braxton.layout.Viewport(2, 24));
    }

    @Test
    void aMessageThatChangesNothingReturnsTheSameModelReference() {
        var editor = new Editor(OPTIONS);
        var model = editor.init(new io.github.dhoard.braxton.mvu.InitContext(null, null)).model();
        // The caret is already at the start, so this action changes nothing at all.
        var result = editor.update(model, new Editor.Msg.MoveLeft());
        assertThat(result.model()).isSameAs(model);
        assertThat(model.withFocus(model.focused())).isSameAs(model);
        assertThat(model.withFocus(!model.focused())).isNotSameAs(model);
    }

    @Test
    void insertionHappensAtTheCaretAndMovesIt() {
        Editor.Model state = Editor.Model.of("ab");
        state = new Editor(OPTIONS).update(state, new Editor.Msg.MoveLeft()).model();
        state = update(state, new Editor.Msg.Insert("X"));
        assertThat(state.text()).isEqualTo("aXb");
        assertThat(state.caret()).isEqualTo(2);
        assertThat(state.anchor()).isEqualTo(2);
        assertThat(state.hasSelection()).isFalse();
    }

    @Test
    void insertionBeforeACombiningMarkMovesPastTheJoinedGrapheme() {
        Editor.Config options = Editor.Config.defaults();
        Editor.Model state = Editor.Model.of("\u0301").withCaret(0);
        state = new Editor(options).update(state, new Editor.Msg.Insert("a")).model();
        assertThat(state.text()).isEqualTo("a\u0301");
        assertThat(state.caret()).isEqualTo(2);
        assertThat(state.anchor()).isEqualTo(2);
        assertThat(state.hasSelection()).isFalse();

        state = new Editor(options).update(state, new Editor.Msg.Insert("b")).model();
        assertThat(state.text()).isEqualTo("a\u0301b");
        assertThat(state.caret()).isEqualTo(3);
        assertThat(state.anchor()).isEqualTo(3);
    }

    @Test
    void insertionBeforeAnEmojiMovesPastTheJoinedZwjGraphemeOnly() {
        Editor.Config options = Editor.Config.defaults();
        Editor.Model state = Editor.Model.of("\uD83D\uDCBBx").withCaret(0);
        state =
                new Editor(options)
                        .update(state, new Editor.Msg.Insert("\uD83D\uDC69\u200D"))
                        .model();
        assertThat(state.text()).isEqualTo("\uD83D\uDC69\u200D\uD83D\uDCBBx");
        assertThat(state.caret()).isEqualTo(5);
        assertThat(state.anchor()).isEqualTo(5);
        assertThat(state.hasSelection()).isFalse();

        state = new Editor(options).update(state, new Editor.Msg.Insert("b")).model();
        assertThat(state.text()).isEqualTo("\uD83D\uDC69\u200D\uD83D\uDCBBbx");
        assertThat(state.caret()).isEqualTo(6);
        assertThat(state.anchor()).isEqualTo(6);
    }

    @Test
    void backspaceThatJoinsGraphemesLeavesTheCaretAfterTheJoinedCluster() {
        Editor.Config options = Editor.Config.defaults();
        Editor.Model state = Editor.Model.of("note\n\u0301x");
        state = new Editor(options).update(state, new Editor.Msg.MoveHome()).model();
        assertThat(state.caret()).isEqualTo(5);

        state = new Editor(options).update(state, new Editor.Msg.Backspace()).model();
        assertThat(state.text()).isEqualTo("note\u0301x");
        assertThat(state.caret()).isEqualTo(5);
        assertThat(state.anchor()).isEqualTo(5);
        assertThat(state.hasSelection()).isFalse();
        // The block cursor covers the text after the joined cluster, not the surviving base.
        assertThat(painted(state, options, true)).isEqualTo("note\u0301" + Editor.CURSOR);

        state = new Editor(options).update(state, new Editor.Msg.Insert("Z")).model();
        assertThat(state.text()).isEqualTo("note\u0301Zx");
        assertThat(state.caret()).isEqualTo(6);
    }

    @Test
    void deleteAndSelectionDeleteThatJoinGraphemesLeaveTheCaretAfterTheJoinedCluster() {
        Editor.Config options = Editor.Config.defaults();

        Editor.Model deleted = new Editor.Model("note\n\u0301x", 4, 4, 0);
        deleted = new Editor(options).update(deleted, new Editor.Msg.Delete()).model();
        assertThat(deleted.text()).isEqualTo("note\u0301x");
        assertThat(deleted.caret()).isEqualTo(5);
        assertThat(deleted.anchor()).isEqualTo(5);

        Editor.Model selected = new Editor.Model("note\n\u0301x", 4, 5, 0);
        selected = new Editor(options).update(selected, new Editor.Msg.Delete()).model();
        assertThat(selected.text()).isEqualTo("note\u0301x");
        assertThat(selected.caret()).isEqualTo(5);
        assertThat(selected.anchor()).isEqualTo(5);

        Editor.Model backspaced = new Editor.Model("note\n\u0301x", 5, 4, 0);
        backspaced = new Editor(options).update(backspaced, new Editor.Msg.Backspace()).model();
        assertThat(backspaced.text()).isEqualTo("note\u0301x");
        assertThat(backspaced.caret()).isEqualTo(5);
        assertThat(backspaced.anchor()).isEqualTo(5);
    }

    @Test
    void backspaceOverATabSeparatingABaseAndAMarkKeepsTheJoinedCluster() {
        Editor.Config options = Editor.Config.defaults();
        Editor.Model state = new Editor.Model("a\t\u0301b", 2, 2, 0);

        state = new Editor(options).update(state, new Editor.Msg.Backspace()).model();
        assertThat(state.text()).isEqualTo("a\u0301b");
        assertThat(state.caret()).isEqualTo(2);
        assertThat(state.anchor()).isEqualTo(2);

        state = new Editor(options).update(state, new Editor.Msg.Insert("Z")).model();
        assertThat(state.text()).isEqualTo("a\u0301Zb");
        assertThat(state.caret()).isEqualTo(3);
    }

    @Test
    void deletionThatRemovesANewlineWithoutAJoinKeepsTheCaretAtTheDeletionPoint() {
        Editor.Config options = Editor.Config.defaults();
        Editor.Model state = new Editor.Model("ab\ncd", 3, 3, 0);

        state = new Editor(options).update(state, new Editor.Msg.Backspace()).model();
        assertThat(state.text()).isEqualTo("abcd");
        assertThat(state.caret()).isEqualTo(2);

        state = new Editor.Model("ab\ncd", 2, 2, 0);
        state = new Editor(options).update(state, new Editor.Msg.Delete()).model();
        assertThat(state.text()).isEqualTo("abcd");
        assertThat(state.caret()).isEqualTo(2);
    }

    @Test
    void backspaceRemovesAWholeGrapheme() {
        Editor.Model state = Editor.Model.of("a" + FAMILY);
        assertThat(update(state, new Editor.Msg.Backspace()).text()).isEqualTo("a");
        assertThat(update(Editor.Model.empty(), new Editor.Msg.Backspace()).text()).isEmpty();
    }

    @Test
    void deleteRemovesTheNextGraphemeAndStopsAtTheEnd() {
        Editor.Model state = Editor.Model.of("ab");
        state = new Editor.Model("ab", 0, 0, 0);
        assertThat(update(state, new Editor.Msg.Delete()).text()).isEqualTo("b");
        Editor.Model end = Editor.Model.of("ab");
        assertThat(update(end, new Editor.Msg.Delete()).text()).isEqualTo("ab");
    }

    @Test
    void typingReplacesTheSelection() {
        Editor.Model state = Editor.Model.of("hello").withSelection(0, 5);
        assertThat(update(state, new Editor.Msg.Insert("X")).text()).isEqualTo("X");
    }

    @Test
    void selectionAllAndCopyIntentUseTheSelectionOrTheDraft() {
        Editor.Model state = Editor.Model.of("hello");
        io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> all =
                new Editor(OPTIONS).update(state, new Editor.Msg.SelectAll());
        assertThat(all.model().selection()).isEqualTo("hello");
        Editor.Output.Copy copy =
                (Editor.Output.Copy)
                        new Editor(OPTIONS)
                                .update(all.model(), new Editor.Msg.Copy())
                                .outputs()
                                .getFirst();
        assertThat(copy.text()).isEqualTo("hello");
        Editor.Output.Copy whole =
                (Editor.Output.Copy)
                        new Editor(OPTIONS)
                                .update(state, new Editor.Msg.Copy())
                                .outputs()
                                .getFirst();
        assertThat(whole.text()).isEqualTo("hello");
    }

    @Test
    void passwordEditorsDoNotCopyAndRenderMasks() {
        Editor.Config password = OPTIONS.withMasking(Editor.Config.Masking.PASSWORD);
        Editor.Model state = Editor.Model.of("secret");
        Editor.Output.Copy copy =
                (Editor.Output.Copy)
                        new Editor(password)
                                .update(state, new Editor.Msg.Copy())
                                .outputs()
                                .getFirst();
        assertThat(copy.text()).isEmpty();
        assertThat(Editor.render(state, password, true).plainText())
                .doesNotContain("secret").contains("\u2022");
    }

    @Test
    void maskedDisplayTextKeepsTheDraftLineStructure() {
        Editor.Config password =
                OPTIONS.withMasking(Editor.Config.Masking.PASSWORD)
                        .withPrompt("", io.github.dhoard.braxton.style.Style.NONE);
        Editor.Model state = Editor.Model.of("ab\nc");
        assertThat(state.displayText(password))
                .isEqualTo(Editor.render(state, password, false).plainText())
                .isEqualTo("\u2022\u2022\n\u2022");
    }

    @Test
    void hiddenEditorsRenderNoContent() {
        Editor.Config hidden = OPTIONS.withMasking(Editor.Config.Masking.HIDDEN);
        assertThat(Editor.render(Editor.Model.of("secret"), hidden, false).plainText())
                .doesNotContain("secret");
    }

    @Test
    void aMaskedCaretNeverRevealsTheCharacterUnderIt() {
        Editor.Config masked = OPTIONS.withMasking(Editor.Config.Masking.PASSWORD);
        for (int caret = 0; caret <= 3; caret++) {
            String rendered =
                    Editor.render(new Editor.Model("abc", caret, caret, 0), masked, true)
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
        Editor.Config limit = OPTIONS.withMaxLength(2);
        Editor.Model state =
                new Editor(limit)
                        .update(
                                Editor.Model.empty(),
                                new Editor.Msg.Insert("\uD83D\uDE00\uD83D\uDE00"))
                        .model();
        assertThat(state.text()).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void pasteRespectsTheLengthLimitAndKeepsGraphemesWhole() {
        Editor.Config limit = OPTIONS.withMaxLength(4);
        Editor.Model state =
                new Editor(limit)
                        .update(
                                Editor.Model.empty(),
                                new Editor.Msg.Paste("\uD83D\uDC4D\uD83D\uDC4D\uD83D\uDC4D"))
                        .model();
        assertThat(state.text()).isEqualTo("\uD83D\uDC4D\uD83D\uDC4D");
    }

    @Test
    void theLimitGovernsInputAndNeverTruncatesExistingText() {
        Editor.Config limit = OPTIONS.withMaxLength(3);
        Editor.Model state = Editor.Model.of("abcdef");
        Editor.Model after = new Editor(limit).update(state, new Editor.Msg.Insert("x")).model();
        assertThat(after.text()).isEqualTo("abcdef");
    }

    @Test
    void unlimitedLengthAcceptsLongInput() {
        Editor.Config unlimited = Editor.Config.defaults().withUnlimitedLength();
        String longText = "x".repeat(5_000);
        assertThat(
                        new Editor(unlimited)
                                .update(Editor.Model.empty(), new Editor.Msg.Insert(longText))
                                .model()
                                .text())
                .hasSize(5_000);
    }

    @Test
    void multilineDraftsReportVisibleLineCount() {
        Editor.Model state = Editor.Model.of("1\n2\n3\n4\n5");
        assertThat(state.lineCount()).isEqualTo(5);
        assertThat(Editor.visibleLineCount(state, OPTIONS)).isEqualTo(3);
        assertThat(Editor.visibleLineCount(state, Editor.Config.defaults())).isEqualTo(5);
    }

    @Test
    void homeAndEndMoveWithinTheCurrentLine() {
        Editor.Model state = Editor.Model.of("one\ntwo");
        assertThat(update(state, new Editor.Msg.MoveHome()).caret()).isEqualTo(4);
        Editor.Model start = Editor.Model.of("one\ntwo");
        assertThat(
                        update(new Editor.Model(start.text(), 5, 5, 0), new Editor.Msg.MoveEnd())
                                .caret())
                .isEqualTo(7);
    }

    @Test
    void verticalMovementKeepsThePreferredColumnWithinBounds() {
        Editor.Model state = Editor.Model.of("abcd\nx");
        state = new Editor.Model(state.text(), 3, 3, 0);
        Editor.Model down = update(state, new Editor.Msg.MoveDown());
        assertThat(down.caret()).isEqualTo("abcd\nx".length());
        // Painted column 5: the draft's "abc" after the two-column prompt.
        assertThat(down.preferredColumn()).isEqualTo(5);
        assertThat(update(down, new Editor.Msg.MoveUp()).caret()).isEqualTo(3);
    }

    @Test
    void verticalMovementKeepsTheVisualColumnAcrossWideGlyphs() {
        String text = "a\u65E5b\nabcdef";
        // After "a\u65E5" the caret is at grapheme 2 but visual column 3, painted at column 5.
        Editor.Model state = new Editor.Model(text, 2, 2, 0);
        Editor.Model down = update(state, new Editor.Msg.MoveDown());
        assertThat(text.substring(4, down.caret())).isEqualTo("abc");
        assertThat(down.preferredColumn()).isEqualTo(5);
        assertThat(update(down, new Editor.Msg.MoveUp()).caret()).isEqualTo(2);
    }

    @Test
    void verticalMovementCountsATabAsTheColumnsTheTextViewPaints() {
        String text = "a\tb\nabcdef";
        // The row is painted after the two-column prompt, which expands the tab to the next
        // four-column stop from there: one cell, so the caret before "b" sits at painted column 4
        // and stays two columns into the target line. Counting the tab from column 0 would put it
        // at column 4 of the draft and land the caret four columns in.
        Editor.Model state = new Editor.Model(text, 2, 2, 0);
        Editor.Model down = update(state, new Editor.Msg.MoveDown());
        assertThat(down.caret()).isEqualTo(text.indexOf("abcdef") + 2);
        assertThat(down.preferredColumn()).isEqualTo(4);
        assertThat(update(down, new Editor.Msg.MoveUp()).caret()).isEqualTo(2);
    }

    @Test
    void verticalMovementCountsATabFromThePaintedPromptColumn() {
        // The prompt prefixes every visible draft line, so a tab in the draft reaches its stop from
        // the prompt's width. Measuring it from column 0 targets a different character and slides
        // the caret left by the prompt's width modulo the tab stop.
        Editor.Config options =
                Editor.Config.defaults().withPrompt("> ", Style.NONE).withVisibleLines(2);
        String text = "ab\tc\n0123456789";
        Editor.Model state = new Editor.Model(text, 3, 3, 0);
        assertThat(paintedLines(state, options, true))
                .containsExactly("> ab    " + Editor.CURSOR, "  0123456789");

        Editor.Model down = new Editor(options).update(state, new Editor.Msg.MoveDown()).model();
        assertThat(paintedLines(down, options, true))
                .containsExactly("> ab    c", "  012345" + Editor.CURSOR + "789");
        // Six columns into the target line: the painted column 8 less the prompt's two columns.
        assertThat(down.caret()).isEqualTo(state.lineStart(1) + 6);
        assertThat(down.preferredColumn()).isEqualTo(8);
        assertThat(new Editor(options).update(down, new Editor.Msg.MoveUp()).model().caret())
                .isEqualTo(3);
    }

    @Test
    void verticalMovementSeeksATabOnTheTargetLineFromThePaintedPromptColumn() {
        // The same gap in the opposite direction: seeking into a target line that contains a tab
        // has to count it from the prompt, or the caret lands short of the painted column.
        Editor.Config options =
                Editor.Config.defaults().withPrompt("> ", Style.NONE).withVisibleLines(2);
        String text = "abcdef\n0\tabc";
        Editor.Model state = new Editor.Model(text, 4, 4, 0);
        assertThat(paintedLines(state, options, true))
                .containsExactly("> abcd" + Editor.CURSOR + "f", "  0 abc");

        Editor.Model down = new Editor(options).update(state, new Editor.Msg.MoveDown()).model();
        assertThat(paintedLines(down, options, true))
                .containsExactly("> abcdef", "  0 ab" + Editor.CURSOR);
        // Four columns into the target line: the tab there occupies the single cell left before
        // the painted column 6, so the caret lands on "c" rather than on the tab after "0 ".
        assertThat(down.caret()).isEqualTo(state.lineStart(1) + 4);
        assertThat(down.preferredColumn()).isEqualTo(6);
        assertThat(new Editor(options).update(down, new Editor.Msg.MoveUp()).model().caret())
                .isEqualTo(4);
    }

    @Test
    void verticalMovementChargesABaselessClusterToTheCellTheRowPaintsItIn() {
        // A draft cluster with no base character of its own is painted inside the cell the row drew
        // before it - the prompt's trailing space, or the last space of the blank stand-in a
        // continuation line carries - exactly as LayoutEngine continues a cell across a span
        // boundary. Charging it a cell of its own (or none, for a lone selector) draws the block in
        // one column and lands the caret a cluster away from it on every vertical move.
        Editor.Config options =
                Editor.Config.defaults().withPrompt("> ", Style.NONE).withVisibleLines(2);
        Editor.Model state = new Editor.Model("\u0301abcdef\nabcdefgh", 7, 7, 0);
        assertThat(paintedLines(state, options, true))
                .containsExactly("> \u0301abcdef" + Editor.CURSOR, "  abcdefgh");

        Editor.Model down = new Editor(options).update(state, new Editor.Msg.MoveDown()).model();
        // The mark shares the prompt's second cell, so the row drew the block in column 8.
        assertThat(down.preferredColumn()).isEqualTo(8);
        assertThat(down.caret()).isEqualTo(state.lineStart(1) + 6);
        assertThat(paintedLines(down, options, true))
                .containsExactly("> \u0301abcdef", "  abcdef" + Editor.CURSOR + "h");
        // The way back returns to the offset that cell was drawn for.
        assertThat(new Editor(options).update(down, new Editor.Msg.MoveUp()).model().caret())
                .isEqualTo(7);

        // Seeking into a line that opens with a mark charges the joined cell there too: the target
        // row paints its first draft cluster inside the stand-in's last cell.
        String target = "abcdef\n\u0301abcdefgh";
        Editor.Model source = new Editor.Model(target, 6, 6, 0);
        Editor.Model moved = new Editor(options).update(source, new Editor.Msg.MoveDown()).model();
        assertThat(moved.preferredColumn()).isEqualTo(8);
        assertThat(moved.caret()).isEqualTo(source.lineStart(1) + 7);
        assertThat(paintedLines(moved, options, true))
                .containsExactly("> abcdef", "  \u0301abcdef" + Editor.CURSOR + "h");

        // The opposite sign: a lone selector claims no cell of its own but widens the one it joins,
        // so the block is drawn in column 9 while an arithmetic that charged it nothing aimed at 8.
        Editor.Model selector = new Editor.Model("\uFE0Fabcdef\nabcdefgh", 7, 7, 0);
        assertThat(paintedLines(selector, options, true))
                .containsExactly("> \uFE0Fabcdef" + Editor.CURSOR, "  abcdefgh");
        Editor.Model selectorDown =
                new Editor(options).update(selector, new Editor.Msg.MoveDown()).model();
        assertThat(selectorDown.preferredColumn()).isEqualTo(9);
        assertThat(selectorDown.caret()).isEqualTo(selector.lineStart(1) + 7);
        assertThat(paintedLines(selectorDown, options, true))
                .containsExactly("> \uFE0Fabcdef", "  abcdefg" + Editor.CURSOR);
    }

    @Test
    void verticalMovementChargesASelectorAfterADraftTabToTheCellTheStopEndsWith() {
        // A tab in the draft reaches its stop as spaces, and the row's own plain text re-segments
        // that last space with the clusters behind the tab: LayoutEngine reserves the column a
        // widening selector adds together with the stop, and LayoutEngineTest's
        // aSelectorAfterATabStopKeepsTheColumnsTheRowMeasures pins that paint as a two-column cell.
        // The caret's arithmetic has to charge that column too, or the block is drawn in column 9
        // while the move aims at 8 - and, being sticky, keeps aiming there for the rest of the run.
        Editor.Config options = Editor.Config.defaults().withVisibleLines(2);
        String text = "\t\uFE0Fdone\nabcdefghij";
        Editor.Model state = new Editor.Model(text, 6, 6, 0);
        assertThat(paintedLines(state, options, true))
                .containsExactly("    \uFE0Fdone" + Editor.CURSOR, "abcdefghij");

        Editor.Model down = new Editor(options).update(state, new Editor.Msg.MoveDown()).model();
        assertThat(down.preferredColumn()).isEqualTo(9);
        assertThat(down.caret()).isEqualTo(state.lineStart(1) + 9);
        assertThat(paintedLines(down, options, true))
                .containsExactly("    \uFE0Fdone", "abcdefghi" + Editor.CURSOR);
        // The way back returns to the cell the stop ends with.
        assertThat(new Editor(options).update(down, new Editor.Msg.MoveUp()).model().caret())
                .isEqualTo(6);

        // A second move of the same run stays in that column instead of stepping one left.
        Editor.Model three = new Editor.Model("\t\uFE0Fdone\nabcdefghij\nklmnopqrst", 6, 6, 0);
        Editor.Model second =
                new Editor(options)
                        .update(
                                new Editor(options)
                                        .update(three, new Editor.Msg.MoveDown())
                                        .model(),
                                new Editor.Msg.MoveDown())
                        .model();
        assertThat(second.preferredColumn()).isEqualTo(9);
        assertThat(second.caret()).isEqualTo(three.lineStart(2) + 9);
        assertThat(paintedLines(second, options, true))
                .containsExactly("abcdefghij", "klmnopqrs" + Editor.CURSOR);

        // The same rule sizes the block that covers a later tab: the stop behind the widened cell
        // has the three columns the row was measured at, so the text after it keeps its columns
        // instead of being slid one right.
        Editor.Model onStop = new Editor.Model("\t\uFE0F\tzz", 2, 2, 0);
        assertThat(paintedLines(onStop, options, true).get(0))
                .isEqualTo("    \uFE0F" + Editor.CURSOR + Editor.CURSOR + Editor.CURSOR + "zz");

        // The same stop behind a prompt: every continuation line is indented by the prompt's width,
        // and the selector still widens the cell the stop ends with.
        Editor.Config prompt =
                Editor.Config.defaults().withPrompt("> ", Style.NONE).withVisibleLines(2);
        Editor.Model seeded = new Editor.Model("a\t\uFE0Fbc\nabcdefghij", 5, 5, 0);
        assertThat(paintedLines(seeded, prompt, true))
                .containsExactly("> a \uFE0Fbc" + Editor.CURSOR, "  abcdefghij");
        Editor.Model moved = new Editor(prompt).update(seeded, new Editor.Msg.MoveDown()).model();
        assertThat(moved.preferredColumn()).isEqualTo(7);
        assertThat(moved.caret()).isEqualTo(seeded.lineStart(1) + 5);
        assertThat(paintedLines(moved, prompt, true))
                .containsExactly("> a \uFE0Fbc", "  abcde" + Editor.CURSOR + "ghij");
    }

    @Test
    void verticalMovementSeeksIntoALineWhoseTabIsFollowedByASelector() {
        // The column is charged on the line the caret seeks into as well. Aiming short of the
        // column the row reaches draws the block one column right of where the move aimed, on the
        // cell of the text that paid for the selector's column.
        Editor.Config options = Editor.Config.defaults().withVisibleLines(2);
        String text = "abcdefghij\nx\t\uFE0Fyz0123456789";
        Editor.Model state = new Editor.Model(text, 8, 8, 0);
        assertThat(paintedLines(state, options, true))
                .containsExactly("abcdefgh" + Editor.CURSOR + "j", "x   \uFE0Fyz0123456789");

        Editor.Model down = new Editor(options).update(state, new Editor.Msg.MoveDown()).model();
        assertThat(down.preferredColumn()).isEqualTo(8);
        assertThat(down.caret()).isEqualTo(state.lineStart(1) + 6);
        assertThat(paintedLines(down, options, true))
                .containsExactly("abcdefghij", "x   \uFE0Fyz0" + Editor.CURSOR + "23456789");
        assertThat(new Editor(options).update(down, new Editor.Msg.MoveUp()).model().caret())
                .isEqualTo(8);
    }

    @Test
    void aClusterBehindADraftTabThatPaintsItsOwnCellKeepsIt() {
        // The stop's last space is open only to the clusters that claim no column of their own,
        // which is what LayoutEngine reads ahead and reserves with the stop. A bare combining mark
        // keeps the column it measures, the dotted circle standing in for its base; a cluster with
        // a base of its own takes a cell of its own; and a control between the tab and the selector
        // breaks the chain the cell continues, so the selector joins nothing and claims no
        // column.
        Editor.Config options = Editor.Config.defaults().withVisibleLines(2);
        Editor.Model mark = new Editor.Model("\t\u0301done\nabcdefghij", 6, 6, 0);
        assertThat(paintedLines(mark, options, true))
                .containsExactly("    \u25CC\u0301done" + Editor.CURSOR, "abcdefghij");
        assertThat(
                        new Editor(options)
                                .update(mark, new Editor.Msg.MoveDown())
                                .model()
                                .preferredColumn())
                .isEqualTo(9);

        Editor.Model base = new Editor.Model("\tX\uFE0Fdone\nabcdefghij", 7, 7, 0);
        assertThat(paintedLines(base, options, true))
                .containsExactly("    X\uFE0Fdone" + Editor.CURSOR, "abcdefghij");
        assertThat(
                        new Editor(options)
                                .update(base, new Editor.Msg.MoveDown())
                                .model()
                                .preferredColumn())
                .isEqualTo(10);

        Editor.Model control = new Editor.Model("\t\u200B\uFE0Fdone\nabcdefghij", 7, 7, 0);
        assertThat(paintedLines(control, options, true))
                .containsExactly("    \u200B\uFE0Fdone" + Editor.CURSOR, "abcdefghij");
        assertThat(
                        new Editor(options)
                                .update(control, new Editor.Msg.MoveDown())
                                .model()
                                .preferredColumn())
                .isEqualTo(8);

        // A second tab is charged its own stop, measured from where the widened cell before it
        // ends; it does not continue that cell.
        Editor.Model stops = new Editor.Model("\t\t\uFE0Fx\nabcdefghij", 4, 4, 0);
        assertThat(paintedLines(stops, options, true))
                .containsExactly("        \uFE0Fx" + Editor.CURSOR, "abcdefghij");
        assertThat(
                        new Editor(options)
                                .update(stops, new Editor.Msg.MoveDown())
                                .model()
                                .preferredColumn())
                .isEqualTo(10);

        // A masked row draws one mask glyph for the tab instead of its spaces, and that glyph is a
        // base of its own, so the row stays one cell per cluster.
        Editor.Config password =
                Editor.Config.defaults()
                        .withVisibleLines(2)
                        .withMasking(Editor.Config.Masking.PASSWORD);
        Editor.Model masked = new Editor.Model("\t\uFE0Fdone\nabcdefghij", 6, 6, 0);
        assertThat(paintedLines(masked, password, true))
                .containsExactly("\u2022\u2022\u2022\u2022\u2022\u2022" + Editor.CURSOR,
                        "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022");
        Editor.Model maskedDown =
                new Editor(password).update(masked, new Editor.Msg.MoveDown()).model();
        assertThat(maskedDown.preferredColumn()).isEqualTo(6);
        assertThat(maskedDown.caret()).isEqualTo(masked.lineStart(1) + 6);
    }

    @Test
    void aBaselessClusterAtTheHeadOfALineAgreesWithThePaintWhereItKeepsItsOwnCell() {
        Editor.Model mark = new Editor.Model("\u0301abcdef\nabcdefgh", 7, 7, 0);
        // With no prompt the row gives the mark its own cell - the dotted circle the text view
        // paints in place of the base it lacks - so the arithmetic and the paint already agree.
        Editor.Config plain = Editor.Config.defaults().withVisibleLines(2);
        assertThat(paintedLines(mark, plain, true))
                .containsExactly("\u25CC\u0301abcdef" + Editor.CURSOR, "abcdefgh");
        Editor.Model plainDown = new Editor(plain).update(mark, new Editor.Msg.MoveDown()).model();
        assertThat(plainDown.preferredColumn()).isEqualTo(7);
        assertThat(plainDown.caret()).isEqualTo(mark.lineStart(1) + 7);

        // A masked row draws one mask glyph per cluster, and that glyph is a base of its own: no
        // join happens, so one cell per cluster is still the column the row paints.
        Editor.Config password =
                Editor.Config.defaults()
                        .withPrompt("> ", Style.NONE)
                        .withVisibleLines(2)
                        .withMasking(Editor.Config.Masking.PASSWORD);
        assertThat(paintedLines(mark, password, true).get(0))
                .isEqualTo("> \u2022\u2022\u2022\u2022\u2022\u2022\u2022" + Editor.CURSOR);
        Editor.Model maskedDown =
                new Editor(password).update(mark, new Editor.Msg.MoveDown()).model();
        assertThat(maskedDown.preferredColumn()).isEqualTo(9);
        assertThat(maskedDown.caret()).isEqualTo(mark.lineStart(1) + 7);

        // A prompt that ends with a tab reaches its last cell as the space of a stop, and the text
        // view continues a stop's space only with the clusters that claim no column of their own.
        // The mark therefore keeps the column it measures there, dotted circle in front of it.
        Editor.Config tab =
                Editor.Config.defaults().withPrompt(">\t", Style.NONE).withVisibleLines(2);
        Editor.Model stop = new Editor.Model("\u0301abcdef\nabcdefgh", 7, 7, 0);
        assertThat(paintedLines(stop, tab, true))
                .containsExactly(">   \u25CC\u0301abcdef" + Editor.CURSOR, "    abcdefgh");
        Editor.Model stopDown = new Editor(tab).update(stop, new Editor.Msg.MoveDown()).model();
        assertThat(stopDown.preferredColumn()).isEqualTo(11);
        assertThat(stopDown.caret()).isEqualTo(stop.lineStart(1) + 7);
        assertThat(paintedLines(stopDown, tab, true))
                .containsExactly(">   \u25CC\u0301abcdef", "    abcdefg" + Editor.CURSOR);
        // The selector that claims no column does join that space, and is charged the column it
        // gives the cell it joins.
        Editor.Model selectorStop = new Editor.Model("\uFE0Fabcdef\nabcdefgh", 7, 7, 0);
        assertThat(paintedLines(selectorStop, tab, true))
                .containsExactly(">   \uFE0Fabcdef" + Editor.CURSOR, "    abcdefgh");
        assertThat(
                        new Editor(tab)
                                .update(selectorStop, new Editor.Msg.MoveDown())
                                .model()
                                .preferredColumn())
                .isEqualTo(11);
    }

    @Test
    void verticalMovementStopsBeforeAWideGlyphItCannotFitInto() {
        String text = "abcdef\na\u65E5b";
        // Painted column 4 falls inside the wide glyph on the shorter line.
        Editor.Model state = new Editor.Model(text, 2, 2, 0);
        Editor.Model down = update(state, new Editor.Msg.MoveDown());
        assertThat(down.caret()).isEqualTo(text.indexOf('\u65E5'));
    }

    @Test
    void verticalMovementRestoresThePreferredColumnAfterAShortLine() {
        String text = "abcdef\nx\nabcdef";
        Editor.Model state = new Editor.Model(text, 6, 6, 0);
        Editor.Model first = update(state, new Editor.Msg.MoveDown());
        // Painted column 8: the draft's "abcdef" after the two-column prompt.
        assertThat(first.preferredColumn()).isEqualTo(8);
        Editor.Model second = update(first, new Editor.Msg.MoveDown());
        assertThat(second.caret()).isEqualTo(text.length());
        assertThat(second.preferredColumn()).isEqualTo(8);
    }

    @Test
    void submitAndPasteRequestsAreIntentsNotMutations() {
        Editor.Model state = Editor.Model.of("hi");
        io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> submit =
                new Editor(OPTIONS).update(state, new Editor.Msg.Submit());
        assertThat(submit.outputs().getFirst()).isEqualTo(new Editor.Output.Submit("hi"));
        assertThat(submit.model()).isEqualTo(state);
        assertThat(
                        new Editor(OPTIONS)
                                .update(state, new Editor.Msg.PasteRequest())
                                .outputs()
                                .getFirst())
                .isEqualTo(new Editor.Output.PasteRequest());
    }

    @Test
    void pasteIsSanitizedAndInsertedAtomically() {
        Editor.Model state = Editor.Model.of("x");
        Editor.Model pasted = update(state, new Editor.Msg.Paste("  a  \r\n\tb  \r\n\r\n"));
        assertThat(pasted.text()).isEqualTo("x  a\n\tb");
    }

    @Test
    void aggressivePasteTrimsAndCollapsesBlankLines() {
        Editor.Config aggressive =
                OPTIONS.withPaste(io.github.dhoard.braxton.clipboard.PasteMode.AGGRESSIVE, true);
        Editor.Model pasted =
                new Editor(aggressive)
                        .update(Editor.Model.empty(), new Editor.Msg.Paste("  a  \n\n\n  b  "))
                        .model();
        assertThat(pasted.text()).isEqualTo("a\n\nb");
    }

    @Test
    void controlJInsertsOneNewlineAndRendersOneCaret() {
        Editor.Model after = update(Editor.Model.of("ab"), new Editor.Msg.Newline());
        assertThat(after.text()).isEqualTo("ab\n");
        assertThat(after.caret()).isEqualTo(3);
        String rendered = Editor.render(after, OPTIONS, true).plainText();
        assertThat(rendered.chars().filter(c -> c == Editor.CURSOR.charAt(0)).count())
                .isEqualTo(1);
    }

    @Test
    void aMaskedDraftNeverCopiesEvenWhenCopyingIsEnabled() {
        Editor.Config masked =
                OPTIONS.withMasking(Editor.Config.Masking.PASSWORD).withCopyEnabled(true);
        assertThat(masked.canCopy()).isFalse();
        assertThat(Editor.copyableText(Editor.Model.of("secret"), masked)).isEmpty();
        Editor.Output.Copy copy =
                (Editor.Output.Copy)
                        new Editor(masked)
                                .update(Editor.Model.of("secret"), new Editor.Msg.Copy())
                                .outputs()
                                .getFirst();
        assertThat(copy.text()).isEmpty();

        assertThat(OPTIONS.canCopy()).isTrue();
        assertThat(OPTIONS.withCopyEnabled(false).canCopy()).isFalse();
    }

    @Test
    void copyingADisabledVisibleDraftReturnsAnEmptyIntent() {
        // copyEnabled=false opts a visible field out of copying entirely, so the Copy intent
        // carries no text even though the draft is not masked.
        io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> result =
                new Editor(OPTIONS.withCopyEnabled(false))
                        .update(Editor.Model.of("secret"), new Editor.Msg.Copy());
        assertThat(result.outputs().getFirst())
                .isInstanceOfSatisfying(
                        Editor.Output.Copy.class, copy -> assertThat(copy.text()).isEmpty());
        assertThat(Editor.copyableText(Editor.Model.of("secret"), OPTIONS.withCopyEnabled(false)))
                .isEmpty();
    }

    @Test
    void renderingShowsThePromptSelectionAndBlockCursor() {
        Editor.Model state = Editor.Model.of("ab").withSelection(0, 1);
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
        Editor.Model state = new Editor.Model("one\ntwo\nthree", 13, 13, 0);
        assertThat(Editor.render(state, OPTIONS, true).plainText())
                .isEqualTo("> one\n  two\n  three" + Editor.CURSOR);
    }

    @Test
    void theBlockCursorCoversAWideGraphemeWithoutMovingTheRestOfTheLine() {
        // The block cursor covers the grapheme under it. A two-column grapheme occupies two cells,
        // so one block leaves the line a column short and slides everything after the caret left.
        Editor.Model wide = Editor.Model.of("a\u6F22b").withCaret(1);
        assertThat(Editor.render(wide, Editor.Config.defaults(), false).plainText())
                .isEqualTo("a\u6F22b");
        assertThat(Editor.render(wide, Editor.Config.defaults(), true).plainText())
                .isEqualTo("a" + Editor.CURSOR.repeat(2) + "b");

        Editor.Model family = Editor.Model.of(FAMILY + "x").withCaret(0);
        assertThat(Editor.render(family, Editor.Config.defaults(), false).plainText())
                .isEqualTo(FAMILY + "x");
        assertThat(Editor.render(family, Editor.Config.defaults(), true).plainText())
                .isEqualTo(Editor.CURSOR.repeat(2) + "x");
    }

    @Test
    void theBlockCursorOnATabCoversTheColumnsTheTextViewPaints() {
        // A tab paints the gap to the next four-column stop, exactly as the caret's own navigation
        // counts it. A one-cell block over it leaves the line short and slides everything after the
        // caret left, the same way a one-cell block over a wide grapheme would.
        Editor.Model onTab = Editor.Model.of("a\tb").withCaret(1);
        assertThat(painted(onTab, Editor.Config.defaults(), false)).isEqualTo("a   b");
        assertThat(painted(onTab, Editor.Config.defaults(), true))
                .isEqualTo("a" + Editor.CURSOR.repeat(3) + "b");
        // The prompt is painted on the same line, so a tab after it reaches the next stop from
        // there: the block covers the one cell that is left, not the three it covers at column 0.
        Editor.Config prompted = Editor.Config.defaults().withPrompt("> ", Style.NONE);
        assertThat(painted(onTab, prompted, false)).isEqualTo("> a b");
        assertThat(painted(onTab, prompted, true)).isEqualTo("> a" + Editor.CURSOR + "b");
    }

    @Test
    void unfocusedEditorsDoNotDrawACursor() {
        assertThat(Editor.render(Editor.Model.of("ab"), OPTIONS, false).plainText())
                .doesNotContain(Editor.CURSOR);
    }

    @Test
    void inputMappingIsPureAndIgnoresModifiedPrintableKeys() {
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("x"), "x"), OPTIONS))
                .contains(new Editor.Msg.Insert("x"));
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.SPACE, " "), OPTIONS))
                .contains(new Editor.Msg.Insert(" "));
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.ENTER), OPTIONS))
                .contains(new Editor.Msg.Submit());
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("j", Modifiers.CTRL)), OPTIONS))
                .contains(new Editor.Msg.Newline());
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("c", Modifiers.ALT)), OPTIONS))
                .contains(new Editor.Msg.Copy());
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("v", Modifiers.CTRL)), OPTIONS))
                .contains(new Editor.Msg.PasteRequest());
        assertThat(Editor.actionFor(InputEvent.KeyInput.of(Key.of("c", Modifiers.CTRL), "c"),
                OPTIONS)).isEmpty();
    }

    @Test
    void aConfiguredPrintableCopyOrPasteKeyWinsOverInsertingItsText() {
        // shift+c and shift+v carry text, but a binding the caller configured must still fire.
        Editor.Config options =
                Editor.Config.defaults()
                        .withKeys(Key.of("c", Modifiers.SHIFT), Key.of("v", Modifiers.SHIFT));
        assertThat(
                        Editor.actionFor(
                                new InputEvent.KeyInput(Key.of("c", Modifiers.SHIFT), "C", 0L),
                                options))
                .contains(new Editor.Msg.Copy());
        assertThat(
                        Editor.actionFor(
                                new InputEvent.KeyInput(Key.of("v", Modifiers.SHIFT), "V", 0L),
                                options))
                .contains(new Editor.Msg.PasteRequest());
        // With the default bindings the same gesture stays printable text.
        assertThat(
                        Editor.actionFor(
                                new InputEvent.KeyInput(Key.of("c", Modifiers.SHIFT), "C", 0L),
                                Editor.Config.defaults()))
                .contains(new Editor.Msg.Insert("C"));
    }

    @Test
    void anInsertedCarriageReturnBecomesTheEditorsLineBreak() {
        // The editor's line model is LF (lineAt/lineEnd, the decoder's newline and the paste
        // sanitizer all agree), so a raw CR must not sit in the draft as an invisible character
        // that shifts offsets without breaking a line.
        Editor.Model state =
                new Editor(OPTIONS)
                        .update(Editor.Model.empty(), new Editor.Msg.Insert("a\r\nb\rc"))
                        .model();
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
            Editor.Model seeded = Editor.Model.of(raw);
            assertThat(seeded.text()).as("draft of %s", label).isEqualTo("a\nb");
            assertThat(seeded.text()).as("no carriage return in %s", label).doesNotContain("\r");
            assertThat(seeded.lineCount()).as("lines of %s", label).isEqualTo(2);
            // The offsets the caller passed are its own coordinates: the end of the text it gave.
            assertThat(seeded.caret()).as("caret of %s", label)
                    .isEqualTo(seeded.text().length());
            assertThat(seeded.anchor()).as("anchor of %s", label)
                    .isEqualTo(seeded.text().length());
            assertThat(Editor.Model.empty().withText(raw).text())
                    .as("withText of %s", label)
                    .isEqualTo("a\nb");
        }
        // A CR/LF pair is one break, so it costs one unit: an offset the caller counted past the
        // break names the same character of the prepared draft it named in the text it supplied.
        Editor.Model seeded = new Editor.Model("ab\r\ncd", 4, 0, 0);
        assertThat(seeded.text()).isEqualTo("ab\ncd");
        assertThat(seeded.caret()).isEqualTo(3);
        assertThat(seeded.caretLine()).contains("cd");
        assertThat(new Editor.Model("ab\r\ncd", 6, 6, 0).caret()).isEqualTo(5);
        // An offset between the CR and the LF of one pair names no grapheme boundary - the pair is
        // one cluster - so it lands at the start of the break that replaces the pair.
        assertThat(new Editor.Model("ab\r\ncd", 3, 3, 0).caret()).isEqualTo(2);
    }

    @Test
    void aDraftSeededWithACarriageReturnDrawsTheCaretInTheColumnTheRowPaints() {
        // preferredColumn and the caret's column are documented as the columns the row draws before
        // the caret. An unprepared CR is measured as a column and painted as no cell, which leaves
        // the block a column right of the text it sits in and lets two offsets draw it in the same
        // place. Preparing the draft is what makes the two agree.
        Editor.Model seeded = new Editor.Model("a\rb\nxyz", 2, 2, 0);
        assertThat(seeded).isEqualTo(new Editor.Model("a\nb\nxyz", 2, 2, 0));
        assertThat(paintedLines(seeded, OPTIONS, true))
                .isEqualTo(paintedLines(new Editor.Model("a\nb\nxyz", 2, 2, 0), OPTIONS, true));
        assertThat(Editor.lines(seeded)).containsExactly("a", "b", "xyz");
        // Stepping over the break moves the block: no offset hides in a column the row never
        // paints.
        assertThat(paintedLines(new Editor.Model("a\rb\nxyz", 1, 1, 0), OPTIONS, true).get(0))
                .isEqualTo("> a" + Editor.CURSOR);
        assertThat(paintedLines(new Editor.Model("a\rb\nxyz", 2, 2, 0), OPTIONS, true).get(1))
                .isEqualTo("  " + Editor.CURSOR);
        // The column a vertical move returns to is the column the row paints the block in.
        Editor.Model up = new Editor(OPTIONS).update(seeded, new Editor.Msg.MoveUp()).model();
        assertThat(up.preferredColumn()).isEqualTo(2);
        assertThat(paintedLines(up, OPTIONS, true).get(0)).isEqualTo("> " + Editor.CURSOR);
    }

    @Test
    void equivalentBreakFormsInsertIdenticallyUnderALengthLimit() {
        // Conversion happens before truncation and offset math, so LF, CRLF, and lone CR from the
        // same logical value leave the same text, caret, anchor, and line count. A length limit
        // that falls inside the break sequence is where the order is observable.
        Editor.Config limited = Editor.Config.defaults().withMaxLength(4);
        Editor.Model base = new Editor.Model("ab", 1, 1, 0);
        Editor.Model lf = new Editor(limited).update(base, new Editor.Msg.Insert("x\nyz")).model();
        for (String raw : java.util.List.of("x\r\nyz", "x\ryz")) {
            Editor.Model inserted =
                    new Editor(limited).update(base, new Editor.Msg.Insert(raw)).model();
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
        Editor.Config options =
                Editor.Config.defaults()
                        .withPrompt(">\t", io.github.dhoard.braxton.style.Style.NONE);
        Editor.Model state = new Editor.Model("a\nb", 0, 0, 0);
        java.util.List<String> lines =
                new io.github.dhoard.braxton.view.ScreenRenderer()
                        .plainLines(
                                new Editor(options)
                                        .view(
                                                (state).withFocus(false),
                                                new io.github.dhoard.braxton.mvu.ViewContext(
                                                        io.github.dhoard.braxton.layout.Viewport
                                                                .EMPTY))
                                        .map((Editor.Msg action) -> action),
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
        Editor.Config options = Editor.Config.defaults().withPrompt("a\nb", Style.NONE);
        assertThat(options.prompt()).isEqualTo("a b");
        assertThat(Editor.Config.defaults().withPrompt("a\r\nb", Style.NONE).prompt())
                .isEqualTo("a b");
        assertThat(Editor.Config.defaults().withPrompt("a\rb", Style.NONE).prompt())
                .isEqualTo("a b");
        Editor.Model state = new Editor.Model("x\ny", 0, 0, 0);
        assertThat(Editor.render(state, options, true).lines())
                .hasSize(Editor.visibleLineCount(state, options));
        java.util.List<String> lines =
                new io.github.dhoard.braxton.view.ScreenRenderer()
                        .plainLines(
                                new Editor(options)
                                        .view(
                                                (state).withFocus(false),
                                                new io.github.dhoard.braxton.mvu.ViewContext(
                                                        io.github.dhoard.braxton.layout.Viewport
                                                                .EMPTY))
                                        .map((Editor.Msg action) -> action),
                                new io.github.dhoard.braxton.layout.Viewport(2, 20));
        assertThat(lines).containsExactly("a bx", "   y");
    }

    @Test
    void editorViewDeclaresFocusAndHandlesPaste() {
        View<String> view =
                new Editor(OPTIONS)
                        .view(
                                (Editor.Model.empty()).withFocus(true),
                                new io.github.dhoard.braxton.mvu.ViewContext(
                                        io.github.dhoard.braxton.layout.Viewport.EMPTY))
                        .map(action -> action.getClass().getSimpleName());
        assertThat(view).isInstanceOf(View.Focused.class);
        assertThat(((View.Focused<String>) view).id())
                .isEqualTo(io.github.dhoard.braxton.view.ElementId.of("input"));
        assertThat(((View.Focused<String>) view).input()
                .handle(new InputEvent.PasteInput("hello")))
                .contains("Paste");
    }

    @Test
    void withStylesAssignsEachStyleToItsOwnRole() {
        Style text = Style.NONE.withBold();
        Style prompt = Style.NONE.withItalic();
        Style selection = Style.NONE.withUnderline();
        Editor.Config options = Editor.Config.defaults().withStyles(text, prompt, selection);
        assertThat(options.textStyle()).isEqualTo(text);
        assertThat(options.promptStyle()).isEqualTo(prompt);
        assertThat(options.selectionStyle()).isEqualTo(selection);
        Editor.Model state = Editor.Model.of("ab");
        assertThat(Editor.render(state, options, true).spans())
                .as("the draft uses the text style while the cursor rule is selection styling")
                .anySatisfy(span -> assertThat(span.style()).isEqualTo(text));
    }

    @Test
    void textWithersAcceptNullLikeTheConstructorAndFactoryDo() {
        Editor.Model state = Editor.Model.of("abc");
        Editor.Model cleared = state.withText(null);
        assertThat(cleared.text()).isEmpty();
        assertThat(cleared.caret()).isZero();
        assertThat(Editor.Model.of(null).text()).isEmpty();
        assertThat(new Editor.Model(null, 3, 1, 0).text()).isEmpty();
    }

    @Test
    void outOfRangePositionsAreClampedToGraphemeBoundaries() {
        Editor.Model state = new Editor.Model("a" + FAMILY, 99, -3, -1);
        assertThat(state.caret()).isEqualTo(("a" + FAMILY).length());
        assertThat(state.anchor()).isZero();
        assertThat(state.preferredColumn()).isZero();
    }
}

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

    @Test
    void insertionHappensAtTheCaretAndMovesIt() {
        EditorState state = EditorState.of("ab");
        state = Editor.update(state, OPTIONS, new EditorAction.MoveLeft()).state();
        state = update(state, new EditorAction.Insert("X"));
        assertThat(state.text()).isEqualTo("aXb");
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
        assertThat(down.preferredColumn()).isEqualTo(3);
        assertThat(update(down, new EditorAction.MoveUp()).caret()).isEqualTo(3);
    }

    @Test
    void verticalMovementKeepsTheVisualColumnAcrossWideGlyphs() {
        String text = "a\u65E5b\nabcdef";
        // After "a\u65E5" the caret is at grapheme 2 but visual column 3.
        EditorState state = new EditorState(text, 2, 2, 0);
        EditorState down = update(state, new EditorAction.MoveDown());
        assertThat(text.substring(4, down.caret())).isEqualTo("abc");
        assertThat(down.preferredColumn()).isEqualTo(3);
        assertThat(update(down, new EditorAction.MoveUp()).caret()).isEqualTo(2);
    }

    @Test
    void verticalMovementCountsATabAsTheColumnsTheTextViewPaints() {
        String text = "a\tb\nabcdef";
        // The draft is painted by the text view, which expands the tab to the next four-column
        // stop, so the caret before "b" sits at visual column 4 rather than 2.
        EditorState state = new EditorState(text, 2, 2, 0);
        EditorState down = update(state, new EditorAction.MoveDown());
        assertThat(down.caret()).isEqualTo(text.indexOf("abcd") + 4);
        assertThat(down.preferredColumn()).isEqualTo(4);
        assertThat(update(down, new EditorAction.MoveUp()).caret()).isEqualTo(2);
    }

    @Test
    void verticalMovementStopsBeforeAWideGlyphItCannotFitInto() {
        String text = "abcdef\na\u65E5b";
        // Visual column 2 falls inside the wide glyph on the shorter line.
        EditorState state = new EditorState(text, 2, 2, 0);
        EditorState down = update(state, new EditorAction.MoveDown());
        assertThat(down.caret()).isEqualTo(text.indexOf('\u65E5'));
    }

    @Test
    void verticalMovementRestoresThePreferredColumnAfterAShortLine() {
        String text = "abcdef\nx\nabcdef";
        EditorState state = new EditorState(text, 6, 6, 0);
        EditorState first = update(state, new EditorAction.MoveDown());
        assertThat(first.preferredColumn()).isEqualTo(6);
        EditorState second = update(first, new EditorAction.MoveDown());
        assertThat(second.caret()).isEqualTo(text.length());
        assertThat(second.preferredColumn()).isEqualTo(6);
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
    void outOfRangePositionsAreClampedToGraphemeBoundaries() {
        EditorState state = new EditorState("a" + FAMILY, 99, -3, -1);
        assertThat(state.caret()).isEqualTo(("a" + FAMILY).length());
        assertThat(state.anchor()).isZero();
        assertThat(state.preferredColumn()).isZero();
    }
}

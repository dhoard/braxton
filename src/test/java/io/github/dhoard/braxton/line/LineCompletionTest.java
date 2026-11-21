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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.style.TextMeasure;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Completion: the token a candidate replaces, the menu in front of it, and how the menu ends.
 */
class LineCompletionTest {

    private static final Completer CHECKOUT = (text, caret) -> List.of("checkout", "cherry-pick");

    private static final Completer NOTHING = (text, caret) -> List.of();

    @Test
    void oneCandidateIsInsertedInPlaceOfTheToken() {
        LineEditor editor = LineEditor.emacs().withText("git che").withCompleter((text, caret) -> List.of("checkout"));

        LineEditor.Result result = press(editor, "tab");

        assertThat(result.editor().text()).isEqualTo("git checkout");
        assertThat(result.editor().caret()).isEqualTo(12);
        assertThat(result.editor().completion())
                .as("one candidate needs no menu")
                .isEmpty();
        assertThat(result.outputs()).as("completion never ends a line").isEmpty();
    }

    @Test
    void severalCandidatesOpenTheMenuAndLeaveTheLineAsTheUserTypedIt() {
        LineEditor editor = LineEditor.emacs().withText("git che").withCompleter(CHECKOUT);

        LineEditor completed = press(editor, "tab").editor();

        assertThat(completed.text()).isEqualTo("git che");
        assertThat(completed.caret()).isEqualTo(7);
        assertThat(completed.completion()).contains(new Completion(4, "che", List.of("checkout", "cherry-pick"), 0));
        assertThat(completed.completion().orElseThrow().candidate()).isEqualTo("checkout");
    }

    @Test
    void theHighlightStepsAndWrapsBothWays() {
        LineEditor menu = press(LineEditor.emacs().withText("git che").withCompleter(CHECKOUT), "tab")
                .editor();

        LineEditor second = menu.update(new LineEditor.Msg.CompleteNext()).editor();
        assertThat(second.completion().orElseThrow().selected()).isEqualTo(1);

        LineEditor wrapped = second.update(new LineEditor.Msg.CompleteNext()).editor();
        assertThat(wrapped.completion().orElseThrow().selected())
                .as("the highlight wraps past the last candidate")
                .isZero();

        LineEditor back = menu.update(new LineEditor.Msg.CompletePrevious()).editor();
        assertThat(back.completion().orElseThrow().selected())
                .as("the highlight wraps before the first candidate")
                .isEqualTo(1);

        assertThat(menu.text()).isEqualTo("git che");
    }

    @Test
    void acceptingACandidateReplacesTheTokenAndEndsTheMenu() {
        LineEditor menu = press(LineEditor.emacs().withText("git che").withCompleter(CHECKOUT), "tab")
                .editor();

        LineEditor accepted = menu.update(new LineEditor.Msg.CompleteAccept()).editor();

        assertThat(accepted.text()).isEqualTo("git checkout");
        assertThat(accepted.caret()).isEqualTo(12);
        assertThat(accepted.completion()).isEmpty();
        assertThat(accepted.state()).isEqualTo(LineEditor.State.INSERT);

        LineEditor second = menu.update(new LineEditor.Msg.CompleteNext())
                .editor()
                .update(new LineEditor.Msg.CompleteAccept())
                .editor();
        assertThat(second.text()).isEqualTo("git cherry-pick");
        assertThat(second.caret()).isEqualTo(15);
    }

    @Test
    void cancelLeavesTheLineAsTheUserTypedIt() {
        LineEditor menu = press(LineEditor.emacs().withText("git che").withCompleter(CHECKOUT), "tab")
                .editor();

        LineEditor canceled = menu.update(new LineEditor.Msg.CompleteCancel()).editor();

        assertThat(canceled.text()).isEqualTo("git che");
        assertThat(canceled.caret()).isEqualTo(7);
        assertThat(canceled.completion()).isEmpty();
        assertThat(menu.update(new LineEditor.Msg.CompleteCancel()).outputs())
                .as("dismissing candidates is not a line gesture")
                .isEmpty();
    }

    @Test
    void theTokenIsTheRunOfNonBlankClustersBeforeTheCaret() {
        // Punctuation stays in the token, so a path or a flag is completed whole.
        LineEditor path =
                LineEditor.emacs().withText("cat src/fo").withCompleter((text, caret) -> List.of("src/foo.txt"));
        assertThat(press(path, "tab").editor().text()).isEqualTo("cat src/foo.txt");

        LineEditor arguments = LineEditor.emacs().withText("make -j").withCompleter((text, caret) -> List.of("-j4"));
        assertThat(press(arguments, "tab").editor().text()).isEqualTo("make -j4");

        // A caret before a token completes the empty token it sits in.
        LineEditor blank = LineEditor.emacs().withText("a b").withCompleter((text, caret) -> List.of("beta"));
        LineEditor replaced = press(blank.withCaret(0), "tab").editor();
        assertThat(replaced.text()).isEqualTo("betaa b");
        assertThat(replaced.caret()).isEqualTo(4);
    }

    @Test
    void aCompleterThatOffersNothingLeavesTheEditorAlone() {
        LineEditor noCompleter = LineEditor.emacs().withText("git che");
        assertThat(press(noCompleter, "tab").editor()).isSameAs(noCompleter);

        LineEditor empty = noCompleter.withCompleter(NOTHING);
        assertThat(press(empty, "tab").editor()).isSameAs(empty);

        LineEditor missing = noCompleter.withCompleter((text, caret) -> null);
        assertThat(press(missing, "tab").editor()).isSameAs(missing);

        LineEditor blanks = noCompleter.withCompleter((text, caret) -> Arrays.asList(" ", "\n", null));
        assertThat(press(blanks, "tab").editor()).isSameAs(blanks);
    }

    @Test
    void aCandidateIsPreparedToOneRowAndABlankOneIsDropped() {
        LineEditor editor =
                LineEditor.emacs().withText("x").withCompleter((text, caret) -> Arrays.asList(" ", "x\ny", "x z"));

        LineEditor completed = press(editor, "tab").editor();

        assertThat(completed.completion().orElseThrow().candidates()).containsExactly("x y", "x z");
        assertThat(completed
                        .completion()
                        .orElseThrow()
                        .candidates()
                        .get(0)
                        .codePoints()
                        .anyMatch(Character::isISOControl))
                .isFalse();
    }

    @Test
    void aCandidateThatMatchesTheTokenChangesNothing() {
        LineEditor editor = LineEditor.emacs().withText("git").withCompleter((text, caret) -> List.of("git"));

        assertThat(press(editor, "tab").editor()).isSameAs(editor);
    }

    @Test
    void aMaskedEditorNeverCompletesSoTheMenuCannotShowWhatTheMaskHides() {
        LineEditor masked = LineEditor.emacs()
                .withText("secret")
                .withCompleter((text, caret) -> List.of("secretarial"))
                .withMasked(true);
        assertThat(masked.masked()).isTrue();
        assertThat(press(masked, "tab").editor()).isSameAs(masked);

        LineEditor menu = press(LineEditor.emacs().withText("git che").withCompleter(CHECKOUT), "tab")
                .editor();
        assertThat(menu.withMasked(true).completion())
                .as("turning the mask on ends a menu that was showing")
                .isEmpty();
        assertThat(menu.withMasked(false)).isSameAs(menu);
    }

    @Test
    void anEditorWithNoCompleterAndVIsNormalModeDeclineTheGesture() {
        LineEditor plain = LineEditor.emacs().withText("git che");
        assertThat(press(plain, "tab").editor()).isSameAs(plain);
        assertThat(plain.completer()).isEmpty();

        LineEditor normal = LineEditor.of(Mode.VI, LineEditor.State.NORMAL)
                .withText("git che")
                .withCompleter(CHECKOUT);
        assertThat(press(normal, "tab").editor())
                .as("a candidate is an insertion, so normal mode does not offer one")
                .isSameAs(normal);

        LineEditor inserting = press(LineEditor.vi().withText("git che").withCompleter(CHECKOUT), "tab")
                .editor();
        assertThat(inserting.completion()).isPresent();
    }

    @Test
    void anyOtherGestureEndsTheMenuAndIsThenApplied() {
        LineEditor menu = press(LineEditor.emacs().withText("git che").withCompleter(CHECKOUT), "tab")
                .editor();

        LineEditor typed = typed(menu, "x");
        assertThat(typed.completion()).isEmpty();
        assertThat(typed.text()).isEqualTo("git chex");
        assertThat(typed.caret()).isEqualTo(8);

        LineEditor moved = press(menu, "left").editor();
        assertThat(moved.completion()).isEmpty();
        assertThat(moved.caret()).isEqualTo(6);
        assertThat(moved.text()).isEqualTo("git che");

        LineEditor erased = press(menu, "backspace").editor();
        assertThat(erased.completion()).isEmpty();
        assertThat(erased.text()).isEqualTo("git ch");
    }

    @Test
    void aPasteEndsTheMenuAndIsInserted() {
        LineEditor menu = press(LineEditor.emacs().withText("gi").withCompleter(CHECKOUT), "tab")
                .editor();

        LineEditor pasted = menu.handle(new InputEvent.PasteInput("x\ny")).editor();

        assertThat(pasted.completion()).isEmpty();
        assertThat(pasted.text()).isEqualTo("gix y");
    }

    @Test
    void submittingWhileTheMenuShowsAcceptsTheLineAndDropsTheMenu() {
        LineEditor menu = press(LineEditor.emacs().withText("git che").withCompleter(CHECKOUT), "tab")
                .editor();

        LineEditor.Result result = menu.update(new LineEditor.Msg.Submit());

        assertThat(result.outputs()).containsExactly(new LineEditor.Output.Accepted("git che"));
        assertThat(result.editor().completion()).isEmpty();
    }

    @Test
    void tabOpensTheMenuAndThenStepsItAndEnterAcceptsTheCandidateNotTheLine() {
        LineEditor editor = LineEditor.emacs().withText("git che").withCompleter(CHECKOUT);

        LineEditor menu = press(editor, "tab").editor();
        assertThat(menu.completion().orElseThrow().selected()).isZero();

        LineEditor stepped = press(menu, "tab").editor();
        assertThat(stepped.completion().orElseThrow().selected()).isEqualTo(1);

        LineEditor.Result accepted = press(stepped, "enter");
        assertThat(accepted.outputs())
                .as("the first enter chooses the candidate")
                .isEmpty();
        assertThat(accepted.editor().text()).isEqualTo("git cherry-pick");

        assertThat(press(accepted.editor(), "enter").outputs())
                .as("the next enter accepts the line")
                .containsExactly(new LineEditor.Output.Accepted("git cherry-pick"));

        assertThat(press(press(press(editor, "tab").editor(), "up").editor(), "enter")
                        .editor()
                        .text())
                .as("up steps back from the first candidate")
                .isEqualTo("git cherry-pick");
    }

    @Test
    void theKeyMapIsTheMenusWhileItShows() {
        LineEditor editor = LineEditor.emacs().withText("git che").withCompleter(CHECKOUT);
        assertThat(editor.keyMap()).isSameAs(LineKeys.emacs());
        assertThat(LineEditor.keys(Mode.EMACS, LineEditor.State.INSERT).actionFor(Key.TAB))
                .as("the help map names the completion gesture")
                .isPresent();
        assertThat(LineEditor.keys(Mode.VI, LineEditor.State.INSERT).actionFor(Key.TAB))
                .as("Vi completes while inserting")
                .isPresent();
        assertThat(LineEditor.keys(Mode.VI, LineEditor.State.NORMAL).actionFor(Key.TAB))
                .as("Vi's normal mode does not")
                .isEmpty();

        LineEditor menu = press(editor, "tab").editor();
        assertThat(menu.keyMap()).isSameAs(LineKeys.completion());

        LineEditor canceled = press(menu, "escape").editor();
        assertThat(canceled.completion()).isEmpty();
        assertThat(canceled.keyMap()).isSameAs(LineKeys.emacs());
    }

    @Test
    void aSearchGestureEndsTheMenuAndStartsTheSearch() {
        LineEditor menu = press(LineEditor.emacs().withText("git che").withCompleter(CHECKOUT), "tab")
                .editor();

        LineEditor searching = press(menu, "ctrl+r").editor();

        assertThat(searching.completion()).isEmpty();
        assertThat(searching.search()).isPresent();
        assertThat(searching.keyMap()).isSameAs(LineKeys.search());
    }

    @Test
    void aCompletionIsAValueThatNormalizesItsInput() {
        Completion menu = new Completion(-4, "a\nb", Arrays.asList(null, " ", "one", "", "two"), 9);

        assertThat(menu.start()).isZero();
        assertThat(menu.token()).isEqualTo("a b");
        assertThat(menu.candidates()).containsExactly("one", "two");
        assertThat(menu.selected()).isEqualTo(1);
        assertThat(menu.candidate()).isEqualTo("two");
        assertThat(menu.end()).isEqualTo(3);
        assertThat(menu.next().selected()).isZero();
        assertThat(menu.previous().selected()).isZero();

        Completion one = new Completion(0, "x", List.of("x"), 0);
        assertThat(one.next()).isSameAs(one);
        assertThat(one.previous()).isSameAs(one);

        assertThat(Completion.of(0, "x", List.of())).isEmpty();
        assertThat(Completion.of(0, "x", null)).isEmpty();
        assertThat(Completion.of(0, "x", List.of("y"))).contains(new Completion(0, "x", List.of("y"), 0));
        assertThatThrownBy(() -> new Completion(0, "x", List.of(""), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSeededRunOfGesturesKeepsTheEditorAndItsMenuConsistent() {
        Random random = new Random(5_303);
        LineEditor editor = freshEditor();
        LineEditor.Msg[] gestures = {
            new LineEditor.Msg.Complete(),
            new LineEditor.Msg.CompleteNext(),
            new LineEditor.Msg.CompletePrevious(),
            new LineEditor.Msg.CompleteAccept(),
            new LineEditor.Msg.CompleteCancel(),
            new LineEditor.Msg.Insert("x"),
            new LineEditor.Msg.EraseBack(),
            new LineEditor.Msg.Move(Motion.BACK),
            new LineEditor.Msg.Move(Motion.LINE_START),
            new LineEditor.Msg.Move(Motion.LINE_END),
            new LineEditor.Msg.SearchBackward(),
            new LineEditor.Msg.Cancel(),
            new LineEditor.Msg.Submit()
        };
        for (int step = 0; step < 300; step++) {
            LineEditor.Result result = editor.update(gestures[random.nextInt(gestures.length)]);
            editor = result.editor();
            assertThat(editor.text().indexOf('\n'))
                    .as("one row at step %d", step)
                    .isNegative();
            assertThat(editor.text().codePoints().anyMatch(Character::isISOControl))
                    .as("no control character at step %d", step)
                    .isFalse();
            assertThat(TextMeasure.isBoundary(editor.text(), editor.caret()))
                    .as("caret on a cluster boundary at step %d", step)
                    .isTrue();
            Completion menu = editor.completion().orElse(null);
            if (menu != null) {
                assertThat(menu.candidates())
                        .as("a menu offers something at step %d", step)
                        .isNotEmpty();
                assertThat(menu.selected()).isBetween(0, menu.candidates().size() - 1);
                assertThat(menu.end())
                        .as("a menu names text the line still holds at step %d", step)
                        .isLessThanOrEqualTo(editor.text().length())
                        .isLessThanOrEqualTo(editor.caret());
                assertThat(editor.text().substring(menu.start(), menu.end()))
                        .as("a menu records the token it completes at step %d", step)
                        .isEqualTo(menu.token());
            }
            if (!result.outputs().isEmpty()) {
                // The line ended, so the run starts again on a fresh editor.
                editor = freshEditor();
            }
        }
    }

    private static LineEditor freshEditor() {
        return LineEditor.emacs()
                .withText("git ")
                .withCompleter((text, caret) -> List.of("checkout", "cherry-pick", "commit"));
    }

    private static LineEditor.Result press(LineEditor editor, String spec) {
        return editor.handle(InputEvent.KeyInput.of(Key.parse(spec)));
    }

    private static LineEditor typed(LineEditor editor, String text) {
        LineEditor current = editor;
        for (int index = 0; index < text.length(); index++) {
            String character = text.substring(index, index + 1);
            current = current.handle(InputEvent.KeyInput.of(Key.of(character), character))
                    .editor();
        }
        return current;
    }
}

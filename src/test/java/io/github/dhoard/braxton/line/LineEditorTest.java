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
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.TextMeasure;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The line editor's modes, edits, kills and outputs, with no terminal anywhere.
 */
class LineEditorTest {

    @Test
    void typingInsertsAtTheCaretAndTheArrowsMoveIt() {
        LineEditor editor = typed(LineEditor.emacs(), "abc");
        assertThat(editor.text()).isEqualTo("abc");
        assertThat(editor.caret()).isEqualTo(3);

        editor = press(editor, "left").editor();
        assertThat(editor.caret()).isEqualTo(2);

        editor = typed(editor, "X");
        assertThat(editor.text()).isEqualTo("abXc");
        assertThat(editor.caret()).isEqualTo(3);
    }

    @Test
    void theEmacsChordsMoveKillAndYank() {
        LineEditor editor = LineEditor.emacs().withText("hello world");

        LineEditor.Result killed = press(editor, "ctrl+w");
        assertThat(killed.editor().text()).isEqualTo("hello ");
        assertThat(killed.editor().kill()).isEqualTo("world");
        assertThat(killed.editor().caret()).isEqualTo(6);

        LineEditor yanked = press(killed.editor(), "ctrl+y").editor();
        assertThat(yanked.text()).isEqualTo("hello world");
        assertThat(yanked.caret()).isEqualTo(11);

        LineEditor start = press(yanked, "ctrl+a").editor();
        assertThat(start.caret()).isZero();

        LineEditor.Result toEnd = press(start, "ctrl+k");
        assertThat(toEnd.editor().text()).isEmpty();
        assertThat(toEnd.editor().kill()).isEqualTo("hello world");

        LineEditor words = press(press(editor, "alt+b").editor(), "alt+f").editor();
        assertThat(words.caret()).isEqualTo(11);
        assertThat(press(editor, "alt+b").editor().caret()).isEqualTo(6);
    }

    @Test
    void oneEraseRemovesOneGraphemeClusterNotOneChar() {
        // "ae" followed by a combining acute accent: one cluster, two chars.
        LineEditor editor = LineEditor.emacs().withText("ae\u0301");
        assertThat(editor.caret()).isEqualTo(3);

        LineEditor after = press(editor, "backspace").editor();
        assertThat(after.text()).isEqualTo("a");
        assertThat(after.caret()).isEqualTo(1);

        LineEditor forward = LineEditor.emacs().withText("abc").withCaret(1);
        assertThat(press(forward, "delete").editor().text()).isEqualTo("ac");
    }

    @Test
    void ctrlDDeletesForwardUnlessTheLineIsEmpty() {
        LineEditor editor = LineEditor.emacs().withText("ab").withCaret(0);

        LineEditor.Result deleted = press(editor, "ctrl+d");
        assertThat(deleted.editor().text()).isEqualTo("b");
        assertThat(deleted.outputs()).isEmpty();

        LineEditor.Result empty = press(deleted.editor().withText(""), "ctrl+d");
        assertThat(empty.outputs()).containsExactly(new LineEditor.Output.Eof());
    }

    @Test
    void enterAcceptsCtrlCCancelsAndTheEndOfTheStreamIsAnOutput() {
        LineEditor editor = typed(LineEditor.emacs(), "ready");

        LineEditor.Result accepted = press(editor, "enter");
        assertThat(accepted.outputs()).containsExactly(new LineEditor.Output.Accepted("ready"));

        assertThat(press(editor, "ctrl+c").outputs()).containsExactly(new LineEditor.Output.Canceled());

        // The LF terminal's Enter is ctrl+j, which the decoder cannot tell from a typed newline.
        assertThat(press(editor, "ctrl+j").outputs()).containsExactly(new LineEditor.Output.Accepted("ready"));
    }

    @Test
    void transposeSwapsTheClusterAtTheCaretWithTheOneBefore() {
        LineEditor atTheEnd = press(LineEditor.emacs().withText("ab"), "ctrl+t").editor();
        assertThat(atTheEnd.text()).isEqualTo("ba");
        assertThat(atTheEnd.caret()).isEqualTo(2);

        LineEditor inside =
                press(LineEditor.emacs().withText("abc").withCaret(1), "ctrl+t").editor();
        assertThat(inside.text()).isEqualTo("bac");
        assertThat(inside.caret()).isEqualTo(2);

        LineEditor oneCluster = LineEditor.emacs().withText("a");
        assertThat(press(oneCluster, "ctrl+t").editor()).isSameAs(oneCluster);
    }

    @Test
    void aPasteIsOneInsertionOfOneRowAndNeverASubmittedLine() {
        LineEditor.Result pasted = LineEditor.emacs().handle(new InputEvent.PasteInput("one\r\ntwo\tthree\u0007"));

        assertThat(pasted.outputs()).isEmpty();
        assertThat(pasted.editor().text()).isEqualTo("one two three");
        assertThat(pasted.editor().caret()).isEqualTo(13);
    }

    @Test
    void textAndCaretAreNormalizedOnTheWayIn() {
        assertThat(LineEditor.emacs().withText("a\r\nb").text()).isEqualTo("a b");
        assertThat(LineEditor.emacs().withText("a\u0000b").text()).isEqualTo("a b");
        assertThat(LineEditor.emacs().withText("abc").withCaret(99).caret()).isEqualTo(3);
        assertThat(LineEditor.emacs().withText("abc").withCaret(-4).caret()).isZero();
        assertThat(LineEditor.emacs().withText("xe\u0301y").withCaret(2).caret())
                .isEqualTo(1);
    }

    @Test
    void aGestureThatChangesNothingReturnsTheIncomingEditor() {
        LineEditor empty = LineEditor.emacs();
        assertThat(empty.update(new LineEditor.Msg.EraseBack()).editor()).isSameAs(empty);
        assertThat(empty.update(new LineEditor.Msg.EraseForward()).editor()).isSameAs(empty);
        assertThat(empty.update(new LineEditor.Msg.Yank()).editor()).isSameAs(empty);
        assertThat(empty.update(new LineEditor.Msg.Move(Motion.BACK)).editor()).isSameAs(empty);
        assertThat(empty.handle(null).editor()).isSameAs(empty);
        assertThat(empty.handle(new InputEvent.ResizeInput(new Size(10, 40))).editor())
                .isSameAs(empty);
        assertThat(empty.handle(new InputEvent.KeyInput(Key.of("a"), "a", KeyEventType.RELEASE, 0))
                        .editor())
                .isSameAs(empty);

        LineEditor text = LineEditor.emacs().withText("abc");
        assertThat(text.withText("abc")).isSameAs(text);
        assertThat(text.withCaret(3)).isSameAs(text);
        assertThat(text.withMode(Mode.EMACS)).isSameAs(text);
    }

    @Test
    void viStartsInsertingAndEscapeStepsBackOneCluster() {
        LineEditor editor = typed(LineEditor.vi(), "abc");
        assertThat(editor.state()).isEqualTo(LineEditor.State.INSERT);

        LineEditor normal = press(editor, "escape").editor();
        assertThat(normal.state()).isEqualTo(LineEditor.State.NORMAL);
        assertThat(normal.caret()).isEqualTo(2);

        LineEditor atTheStart = press(LineEditor.vi(), "escape").editor();
        assertThat(atTheStart.caret()).isZero();
        assertThat(press(atTheStart, "escape").editor()).isSameAs(atTheStart);
    }

    @Test
    void viNormalModeMovesEditsAndInsertsWhereItIsTold() {
        LineEditor normal = letter(normal(LineEditor.vi().withText("one two three")), "0");

        assertThat(letter(normal, "w").caret()).isEqualTo(4);
        assertThat(letter(normal, "e").caret()).isEqualTo(3);
        assertThat(letter(normal, "$").caret()).isEqualTo(13);
        assertThat(letter(letter(normal, "$"), "0").caret()).isZero();
        assertThat(letter(letter(normal, "$"), "b").caret()).isEqualTo(8);

        LineEditor erased = letter(normal, "x");
        assertThat(erased.text()).isEqualTo("ne two three");
        assertThat(erased.kill()).isEqualTo("o");

        LineEditor toTheEnd = letter(normal, "D");
        assertThat(toTheEnd.text()).isEmpty();
        assertThat(toTheEnd.kill()).isEqualTo("one two three");

        LineEditor appended = letter(letter(normal, "A"), "!");
        assertThat(appended.text()).isEqualTo("one two three!");
        assertThat(appended.state()).isEqualTo(LineEditor.State.INSERT);

        LineEditor after = letter(letter(normal, "a"), "!");
        assertThat(after.text()).isEqualTo("o!ne two three");

        LineEditor before = letter(letter(letter(normal, "$"), "i"), "!");
        assertThat(before.text()).isEqualTo("one two three!");
    }

    @Test
    void viOperatorsWaitForAMotion() {
        LineEditor normal = letter(normal(LineEditor.vi().withText("one two three")), "0");

        LineEditor armed = letter(normal, "d");
        assertThat(armed.pending()).contains(LineEditor.Operator.DELETE);
        assertThat(armed.text()).isEqualTo("one two three");

        LineEditor deleted = letter(armed, "w");
        assertThat(deleted.pending()).isEmpty();
        assertThat(deleted.text()).isEqualTo("two three");
        assertThat(deleted.kill()).isEqualTo("one ");
        assertThat(deleted.caret()).isZero();
        assertThat(deleted.state()).isEqualTo(LineEditor.State.NORMAL);

        // cw changes the word it is in, not the blanks after it.
        LineEditor changed = letter(letter(normal, "c"), "w");
        assertThat(changed.text()).isEqualTo(" two three");
        assertThat(changed.kill()).isEqualTo("one");
        assertThat(changed.state()).isEqualTo(LineEditor.State.INSERT);

        // A backward motion removes the range behind the caret and leaves the caret at its start.
        LineEditor atTheEnd = normal(LineEditor.vi().withText("one two")).withCaret(7);
        assertThat(atTheEnd.caret()).isEqualTo(7);
        LineEditor removedBack = letter(letter(atTheEnd, "d"), "b");
        assertThat(removedBack.text()).isEqualTo("one ");
        assertThat(removedBack.kill()).isEqualTo("two");
        assertThat(removedBack.caret()).isEqualTo(4);
    }

    @Test
    void anOperatorPressedTwiceTakesTheWholeLine() {
        LineEditor deleted = letter(letter(normal(LineEditor.vi().withText("abc")), "d"), "d");
        assertThat(deleted.text()).isEmpty();
        assertThat(deleted.kill()).isEqualTo("abc");
        assertThat(deleted.state()).isEqualTo(LineEditor.State.NORMAL);

        LineEditor changed = letter(letter(normal(LineEditor.vi().withText("abc")), "c"), "c");
        assertThat(changed.text()).isEmpty();
        assertThat(changed.kill()).isEqualTo("abc");
        assertThat(changed.state()).isEqualTo(LineEditor.State.INSERT);
    }

    @Test
    void escapeDropsAHalfTypedOperatorAndAnUnboundLetterDeclines() {
        LineEditor armed = letter(normal(LineEditor.vi().withText("abc")), "d");
        LineEditor dropped = press(armed, "escape").editor();
        assertThat(dropped.pending()).isEmpty();
        assertThat(dropped.text()).isEqualTo("abc");

        LineEditor normal = normal(LineEditor.vi().withText("abc"));
        assertThat(typed(normal, "Qz").text()).isEqualTo("abc");
        assertThat(normal.handle(InputEvent.KeyInput.of(Key.of("w", Modifiers.ALT), "w"))
                        .editor())
                .isSameAs(normal);
    }

    @Test
    void viPastesTheKillBufferInEitherState() {
        LineEditor deleted = letter(letter(normal(LineEditor.vi().withText("abc")), "0"), "D");
        assertThat(deleted.text()).isEmpty();

        LineEditor pasted = letter(deleted, "p");
        assertThat(pasted.text()).isEqualTo("abc");
        assertThat(pasted.caret()).isEqualTo(3);
        assertThat(pasted.state()).isEqualTo(LineEditor.State.NORMAL);

        LineEditor twice = letter(pasted, "P");
        assertThat(twice.text()).isEqualTo("abcabc");
    }

    @Test
    void theMapsNameTheGesturesEachModeAnswers() {
        LineEditor emacs = LineEditor.emacs();
        assertThat(emacs.keyMap().actionFor(Key.parse("ctrl+k")).orElseThrow().message())
                .isEqualTo(new LineEditor.Msg.Remove(Motion.LINE_END));
        assertThat(LineEditor.keys(Mode.EMACS, LineEditor.State.NORMAL))
                .as("Emacs is always inserting, whatever state is asked about")
                .isSameAs(LineEditor.keys(Mode.EMACS, LineEditor.State.INSERT));

        LineEditor normal = normal(LineEditor.vi());
        assertThat(normal.keyMap().actionFor(Key.parse("escape")).orElseThrow().message())
                .isEqualTo(new LineEditor.Msg.Normal());
        assertThat(LineEditor.keys(Mode.VI, LineEditor.State.INSERT))
                .isNotSameAs(LineEditor.keys(Mode.VI, LineEditor.State.NORMAL));

        // Vi's letters are the typed character, so the normal map deliberately names none of them.
        assertThat(normal.keyMap().actionFor(Key.of("d"))).isEmpty();
    }

    @Test
    void switchingModeKeepsTheLineAndDropsTheOperator() {
        LineEditor armed = letter(normal(LineEditor.vi().withText("abc")), "d");
        LineEditor emacs = armed.withMode(Mode.EMACS);

        assertThat(emacs.text()).isEqualTo("abc");
        assertThat(emacs.state()).isEqualTo(LineEditor.State.INSERT);
        assertThat(emacs.pending()).isEmpty();
        assertThat(emacs.withMode(null).mode()).isEqualTo(Mode.EMACS);
    }

    @Test
    void viChangeCommandsReplaceWhatTheyCoverAndStartInserting() {
        LineEditor normal = letter(normal(LineEditor.vi().withText("abc")), "0");

        LineEditor toTheEnd = letter(normal, "C");
        assertThat(toTheEnd.text()).isEmpty();
        assertThat(toTheEnd.kill()).isEqualTo("abc");
        assertThat(toTheEnd.state()).isEqualTo(LineEditor.State.INSERT);

        LineEditor oneCluster = letter(normal.withCaret(1), "s");
        assertThat(oneCluster.text()).isEqualTo("ac");
        assertThat(oneCluster.kill()).isEqualTo("b");
        assertThat(oneCluster.state()).isEqualTo(LineEditor.State.INSERT);
    }

    @Test
    void wordMotionsSkipTheBlanksBetweenWords() {
        LineEditor editor = LineEditor.emacs().withText("   ab   ");

        assertThat(press(editor, "alt+b").editor().caret()).isEqualTo(3);
        assertThat(editor.withCaret(0)
                        .update(new LineEditor.Msg.Move(Motion.WORD_FORWARD))
                        .editor()
                        .caret())
                .isEqualTo(3);
        assertThat(editor.withCaret(5)
                        .update(new LineEditor.Msg.Move(Motion.WORD_END))
                        .editor()
                        .caret())
                .isEqualTo(8);
        // Blanks alone have no word to find in either direction.
        assertThat(LineEditor.emacs()
                        .withText("   ")
                        .update(new LineEditor.Msg.Move(Motion.WORD_BACK))
                        .editor()
                        .caret())
                .isZero();
    }

    @Test
    void aCaretOrTextSetFromOutsideDropsTheArmedOperator() {
        LineEditor armed = letter(normal(LineEditor.vi().withText("abc")), "d");

        assertThat(armed.withCaret(0).pending()).isEmpty();
        assertThat(armed.withText("abc").pending()).isEmpty();
        assertThat(armed.withText("abc").caret()).isEqualTo(3);
    }

    @Test
    void anUnboundLetterAndAnEmptyInsertChangeNothingButAbandonTheOperator() {
        LineEditor armed = letter(normal(LineEditor.vi().withText("abc")), "d");
        LineEditor declined = typed(armed, "z");
        assertThat(declined.text()).isEqualTo("abc");
        assertThat(declined.pending()).isEmpty();

        LineEditor emacs = LineEditor.emacs().withText("abc");
        assertThat(emacs.update(new LineEditor.Msg.Insert("")).editor()).isSameAs(emacs);
        assertThat(emacs.update(new LineEditor.Msg.Insert(null)).editor()).isSameAs(emacs);
    }

    @Test
    void theGesturesTheOtherModeOwnsAreDeclined() {
        LineEditor emacs = LineEditor.emacs().withText("abc");
        assertThat(emacs.update(new LineEditor.Msg.Normal()).editor()).isSameAs(emacs);
        assertThat(emacs.update(new LineEditor.Msg.StartInsert(LineEditor.Place.HERE))
                        .editor())
                .isSameAs(emacs);
        assertThat(emacs.update(new LineEditor.Msg.Change(Motion.FORWARD)).editor())
                .isSameAs(emacs);
        assertThat(emacs.update(new LineEditor.Msg.Operate(LineEditor.Operator.DELETE))
                        .editor())
                .isSameAs(emacs);
        assertThat(emacs.update(new LineEditor.Msg.Move(null)).editor()).isSameAs(emacs);
        assertThat(emacs.update(new LineEditor.Msg.Remove(null)).editor()).isSameAs(emacs);
        assertThat(emacs.update(new LineEditor.Msg.Change(null)).editor()).isSameAs(emacs);
        assertThat(emacs.update(new LineEditor.Msg.Operate(null)).editor()).isSameAs(emacs);
        assertThat(emacs.update(new LineEditor.Msg.StartInsert(null)).editor()).isSameAs(emacs);
        assertThat(emacs.update(null).editor()).isSameAs(emacs);
        assertThat(emacs.handle(new InputEvent.PasteInput(null)).editor()).isSameAs(emacs);

        LineEditor normal = normal(LineEditor.vi().withText("abc"));
        assertThat(normal.update(new LineEditor.Msg.EndOfInput()).editor()).isSameAs(normal);
        assertThat(normal.update(new LineEditor.Msg.Insert("x")).editor()).isSameAs(normal);
    }

    @Test
    void aChangeOrDeleteOverAnEmptyRangeConsumesTheOperator() {
        LineEditor normal = letter(letter(normal(LineEditor.vi().withText("abc")), "0"), "c");
        LineEditor changed = letter(normal, "0");
        assertThat(changed.text()).isEqualTo("abc");
        assertThat(changed.pending()).isEmpty();
        assertThat(changed.state()).isEqualTo(LineEditor.State.INSERT);

        LineEditor empty = normal(LineEditor.vi());
        LineEditor cleared = letter(letter(empty, "d"), "d");
        assertThat(cleared.text()).isEmpty();
        assertThat(cleared.pending()).isEmpty();
        assertThat(cleared.state()).isEqualTo(LineEditor.State.NORMAL);

        LineEditor secondOperator = letter(letter(normal(LineEditor.vi().withText("abc")), "d"), "c");
        assertThat(secondOperator.text()).isEqualTo("abc");
        assertThat(secondOperator.pending()).contains(LineEditor.Operator.DELETE);
    }

    @Test
    void aPublicValueRejectsWhatItCannotRepresent() {
        assertThatThrownBy(() -> new LineEditor.Output.Accepted(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LineEditor.Result(null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theCaretAndTextSettersAnswerEveryCaseAndKeepTheOperatorHonest() {
        LineEditor emacs = LineEditor.emacs().withText("abc");
        assertThat(emacs.withText("abcd").caret()).isEqualTo(4);
        assertThat(emacs.withCaret(1).caret()).isEqualTo(1);

        LineEditor armed = letter(normal(LineEditor.vi().withText("abc")), "d");
        assertThat(armed.withMode(Mode.VI).state()).isEqualTo(LineEditor.State.INSERT);
        assertThat(armed.update(new LineEditor.Msg.Remove(Motion.FORWARD))
                        .editor()
                        .pending())
                .isEmpty();

        LineEditor startOfLine = letter(letter(normal(LineEditor.vi().withText("\te\u0301x")), "$"), "I");
        assertThat(startOfLine.caret()).isZero();
        assertThat(startOfLine.state()).isEqualTo(LineEditor.State.INSERT);

        assertThat(emacs.update(new LineEditor.Msg.Transpose()).editor()).isNotNull();
        assertThat(LineEditor.emacs()
                        .update(new LineEditor.Msg.Transpose())
                        .editor()
                        .text())
                .isEmpty();
        assertThat(emacs.withCaret(0)
                        .update(new LineEditor.Msg.Transpose())
                        .editor()
                        .caret())
                .isZero();
    }

    @Test
    void aControlChordIsNotAViLetterCommand() {
        LineEditor normal = normal(LineEditor.vi().withText("abc"));
        assertThat(normal.handle(InputEvent.KeyInput.of(Key.of("w", Modifiers.CTRL), "w"))
                        .editor())
                .isSameAs(normal);
        assertThat(LineKeys.viCommand(null)).isNull();
        assertThat(LineKeys.viCommand("z")).isNull();
    }

    @Test
    void factoriesSubstituteTheirDefaults() {
        assertThat(LineEditor.of(null).mode()).isEqualTo(Mode.EMACS);
        assertThat(LineEditor.of(null, LineEditor.State.NORMAL).state()).isEqualTo(LineEditor.State.INSERT);
        assertThat(LineEditor.of(Mode.VI, null).state()).isEqualTo(LineEditor.State.INSERT);
        assertThat(LineEditor.of(Mode.VI, LineEditor.State.NORMAL).state()).isEqualTo(LineEditor.State.NORMAL);
        assertThat(LineEditor.emacs().withMode(null).mode()).isEqualTo(Mode.EMACS);
        assertThat(LineEditor.emacs().withText(null).text()).isEmpty();
        assertThat(LineEditor.keys(null, null)).isSameAs(LineEditor.keys(Mode.EMACS, LineEditor.State.INSERT));
        LineEditor.Result result = new LineEditor.Result(LineEditor.emacs(), null);
        assertThat(result.outputs()).isEmpty();
        assertThat(LineEditor.Result.of(LineEditor.emacs()).editor().text()).isEmpty();
    }

    @Test
    void aSeededGestureLoopKeepsTheModelConsistent() {
        Random random = new Random(20261120L);
        String[] specs = {
            "left",
            "right",
            "home",
            "end",
            "backspace",
            "delete",
            "ctrl+a",
            "ctrl+e",
            "ctrl+k",
            "ctrl+u",
            "ctrl+w",
            "ctrl+y",
            "ctrl+t",
            "alt+b",
            "alt+f",
            "alt+d",
            "escape",
            "enter",
            "ctrl+c",
            "ctrl+r",
            "ctrl+s",
            "ctrl+g"
        };
        List<String> letters = List.of(
                "a",
                "b",
                " ",
                "0",
                "$",
                "w",
                "b",
                "e",
                "x",
                "X",
                "D",
                "C",
                "d",
                "c",
                "i",
                "a",
                "I",
                "A",
                "p",
                "P",
                "s",
                "\u00e9",
                "\u6f22",
                "\ud83d\ude00");
        LineEditor editor = LineEditor.vi();
        for (int step = 0; step < 800; step++) {
            LineEditor.Result result = switch (random.nextInt(4)) {
                case 0 -> editor.handle(new InputEvent.PasteInput(random.nextBoolean() ? "one\ntwo" : "a\u001bb"));
                case 1 -> press(editor, specs[random.nextInt(specs.length)]);
                case 2 -> {
                    String character = letters.get(random.nextInt(letters.size()));
                    Key gesture = character.isBlank() ? Key.SPACE : Key.of(character);
                    yield editor.handle(InputEvent.KeyInput.of(gesture, character));
                }
                default -> {
                    char typedChar = (char) (random.nextInt(26) + 'a');
                    yield editor.handle(new InputEvent.KeyInput(
                            Key.of(String.valueOf(typedChar)),
                            String.valueOf(typedChar),
                            KeyEventType.values()[random.nextInt(KeyEventType.values().length)],
                            0L));
                }
            };
            editor = result.editor();
            if (random.nextInt(50) == 0) {
                editor = editor.withMode(random.nextBoolean() ? Mode.EMACS : Mode.VI);
            }
            assertThat(editor.caret())
                    .as("caret at step %d", step)
                    .isBetween(0, editor.text().length());
            assertThat(TextMeasure.isBoundary(editor.text(), editor.caret()))
                    .as("caret on a cluster boundary at step %d", step)
                    .isTrue();
            assertThat(editor.text().codePoints().anyMatch(Character::isISOControl))
                    .as("no control character at step %d", step)
                    .isFalse();
            assertThat(editor.text().indexOf('\n'))
                    .as("one row at step %d", step)
                    .isNegative();
        }
    }

    private static LineEditor.Result press(LineEditor editor, String spec) {
        return editor.handle(InputEvent.KeyInput.of(Key.parse(spec)));
    }

    private static LineEditor typed(LineEditor editor, String text) {
        LineEditor current = editor;
        for (int index = 0; index < text.length(); index++) {
            current = letter(current, text.substring(index, index + 1));
        }
        return current;
    }

    private static LineEditor letter(LineEditor editor, String character) {
        Key gesture = character.isBlank() ? Key.SPACE : Key.of(character);
        return editor.handle(InputEvent.KeyInput.of(gesture, character)).editor();
    }

    private static LineEditor normal(LineEditor editor) {
        return press(editor, "escape").editor();
    }
}

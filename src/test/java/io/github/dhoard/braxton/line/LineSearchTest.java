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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.style.TextMeasure;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The incremental history search: its gestures, what the row shows, and how a search ends.
 */
class LineSearchTest {

    @TempDir
    Path home;

    @Test
    void ctrlRShowsTheNewestLineAndRepeatingItWalksOlder() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("one", "two", "three"));

        LineEditor.Result started = press(editor, "ctrl+r");
        assertThat(started.editor().text()).isEqualTo("three");
        assertThat(started.editor().search()).contains(new LineEditor.Search(LineEditor.Direction.BACKWARD, "", false));
        assertThat(started.outputs()).as("a search never ends a line").isEmpty();

        LineEditor older = press(started.editor(), "ctrl+r").editor();
        assertThat(older.text()).isEqualTo("two");

        LineEditor oldest = press(older, "ctrl+r").editor();
        assertThat(oldest.text()).isEqualTo("one");

        LineEditor past = press(oldest, "ctrl+r").editor();
        assertThat(past.text()).as("there is nothing older than the oldest").isEqualTo("one");
        assertThat(past.search().orElseThrow().failed()).isTrue();
    }

    @Test
    void typedTextNarrowsTheSearchAndTheCaretSitsOnTheMatch() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("run tests", "make test"));

        LineEditor narrowed = typed(press(editor, "ctrl+r").editor(), "test");

        assertThat(narrowed.text()).isEqualTo("make test");
        assertThat(narrowed.caret()).isEqualTo(5);
        assertThat(narrowed.search().orElseThrow().query()).isEqualTo("test");
        assertThat(narrowed.search().orElseThrow().failed()).isFalse();
    }

    @Test
    void ctrlSWalksTowardsNewerEntries() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("one", "two", "three"));
        LineEditor oldest = press(
                        press(press(editor, "ctrl+r").editor(), "ctrl+r").editor(), "ctrl+r")
                .editor();
        assertThat(oldest.text()).isEqualTo("one");

        LineEditor newer = press(oldest, "ctrl+s").editor();

        assertThat(newer.text()).isEqualTo("two");
        assertThat(newer.search().orElseThrow().direction()).isEqualTo(LineEditor.Direction.FORWARD);
    }

    @Test
    void aSearchThatMatchesNothingKeepsTheLineAndSaysSo() throws IOException {
        LineEditor empty = LineEditor.emacs().withText("draft").withHistory(History.of(List.of()));

        LineEditor unmatched = typed(press(empty, "ctrl+r").editor(), "q");

        assertThat(unmatched.text())
                .as("nothing ever matched, so the draft the search started from stays put")
                .isEqualTo("draft");
        assertThat(unmatched.search().orElseThrow().failed()).isTrue();
        assertThat(unmatched.search().orElseThrow().query()).isEqualTo("q");

        // A step after a failure fails again.
        assertThat(press(unmatched, "ctrl+r").editor().search().orElseThrow().failed())
                .isTrue();

        // A query that stops matching keeps the last match it had; one backspace matches again.
        LineEditor editor = LineEditor.emacs().withText("draft").withHistory(history("alpha", "zulu"));
        LineEditor stale = typed(press(editor, "ctrl+r").editor(), "zz");
        assertThat(stale.text()).isEqualTo("zulu");
        assertThat(stale.search().orElseThrow().failed()).isTrue();
        LineEditor recovered = press(stale, "backspace").editor();
        assertThat(recovered.text()).isEqualTo("zulu");
        assertThat(recovered.search().orElseThrow().failed()).isFalse();
    }

    @Test
    void aFailedStepOverAMatchKeepsShowingIt() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("one", "two"));

        LineEditor shown = typed(press(editor, "ctrl+r").editor(), "tw");
        assertThat(shown.text()).isEqualTo("two");

        LineEditor failed = press(shown, "ctrl+r").editor();

        assertThat(failed.text()).as("the last match stays on the row").isEqualTo("two");
        assertThat(failed.search().orElseThrow().failed()).isTrue();
    }

    @Test
    void enterEndsTheSearchOnTheFoundLineAndTheNextEnterAcceptsIt() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("one", "two"));

        LineEditor.Result ended = press(typed(press(editor, "ctrl+r").editor(), "two"), "enter");

        assertThat(ended.outputs())
                .as("a search gesture never accepts the line")
                .isEmpty();
        assertThat(ended.editor().search()).isEmpty();
        assertThat(ended.editor().text()).isEqualTo("two");
        assertThat(press(ended.editor(), "enter").outputs()).containsExactly(new LineEditor.Output.Accepted("two"));
    }

    @Test
    void escapeAndCtrlGRestoreTheLineTheSearchStartedFrom() throws IOException {
        LineEditor editor = LineEditor.emacs().withText("draft").withHistory(history("one", "two"));
        LineEditor searching = typed(press(editor, "ctrl+r").editor(), "tw");
        assertThat(searching.text()).isEqualTo("two");

        LineEditor escaped = press(searching, "escape").editor();
        assertThat(escaped.search()).isEmpty();
        assertThat(escaped.text()).isEqualTo("draft");
        assertThat(escaped.caret()).isEqualTo(5);

        LineEditor aborted = press(searching, "ctrl+g").editor();
        assertThat(aborted.text()).isEqualTo("draft");
        assertThat(aborted.search()).isEmpty();
    }

    @Test
    void backspaceTakesOneGraphemeClusterOffTheQuery() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("one", "two"));
        LineEditor searching = typed(press(editor, "ctrl+r").editor(), "tw");

        assertThat(press(searching, "backspace").editor().search().orElseThrow().query())
                .isEqualTo("t");

        LineEditor accented = typed(press(editor, "ctrl+r").editor(), "e\u0301");
        assertThat(accented.search().orElseThrow().query()).isEqualTo("e\u0301");
        assertThat(press(accented, "backspace").editor().search().orElseThrow().query())
                .isEmpty();

        LineEditor empty = press(editor, "ctrl+r").editor();
        assertThat(press(empty, "backspace").editor())
                .as("an empty query has nothing to erase")
                .isSameAs(empty);
    }

    @Test
    void aGestureTheSearchDoesNotAnswerEndsItOnTheFoundLineAndThenApplies() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("run tests"));
        LineEditor searching = typed(press(editor, "ctrl+r").editor(), "tests");
        assertThat(searching.caret()).isEqualTo(4);

        LineEditor home = press(searching, "home").editor();
        assertThat(home.search()).isEmpty();
        assertThat(home.text()).isEqualTo("run tests");
        assertThat(home.caret()).as("the gesture applied to the line it found").isZero();

        LineEditor inserted = searching.update(new LineEditor.Msg.Insert("!")).editor();
        assertThat(inserted.search()).isEmpty();
        assertThat(inserted.text())
                .as("the gesture applied where the search left the caret")
                .isEqualTo("run !tests");
    }

    @Test
    void aPasteDuringASearchNarrowsTheQuery() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("one", "two"));

        LineEditor.Result pasted = press(editor, "ctrl+r").editor().handle(new InputEvent.PasteInput("wor\r\nd"));

        assertThat(pasted.editor().search().orElseThrow().query()).isEqualTo("wor d");
        assertThat(pasted.outputs()).isEmpty();
    }

    @Test
    void viNormalModeAnswersSlashAndQuestion() throws IOException {
        LineEditor vi = LineEditor.of(Mode.VI, LineEditor.State.NORMAL).withHistory(history("one", "two"));

        LineEditor backward = letter(vi, "/");
        assertThat(backward.search().orElseThrow().direction()).isEqualTo(LineEditor.Direction.BACKWARD);
        assertThat(backward.text()).isEqualTo("two");

        LineEditor forward = letter(vi, "?");
        assertThat(forward.search().orElseThrow().direction()).isEqualTo(LineEditor.Direction.FORWARD);
        assertThat(forward.text())
                .as("a forward walk starts at the oldest line")
                .isEqualTo("one");

        // Slash is text while the editor is inserting, and while a search owns the keyboard.
        assertThat(typed(LineEditor.vi(), "/").text()).isEqualTo("/");
        assertThat(typed(letter(vi, "/"), "?").search().orElseThrow().query()).isEqualTo("?");

        LineEditor accepted = press(typed(letter(vi, "/"), "on"), "enter").editor();
        assertThat(accepted.text()).isEqualTo("one");
        assertThat(accepted.state())
                .as("the search leaves the state it started in")
                .isEqualTo(LineEditor.State.NORMAL);
    }

    @Test
    void theSearchMapIsTheKeyMapWhileASearchRuns() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("one"));
        LineEditor searching = press(editor, "ctrl+r").editor();

        assertThat(searching
                        .keyMap()
                        .actionFor(Key.parse("ctrl+s"))
                        .orElseThrow()
                        .message())
                .isEqualTo(new LineEditor.Msg.SearchForward());
        assertThat(searching
                        .keyMap()
                        .actionFor(Key.parse("ctrl+g"))
                        .orElseThrow()
                        .message())
                .isEqualTo(new LineEditor.Msg.SearchCancel());
        assertThat(searching.keyMap()).isNotSameAs(editor.keyMap());
        assertThat(editor.keyMap().actionFor(Key.parse("ctrl+r")).orElseThrow().message())
                .isEqualTo(new LineEditor.Msg.SearchBackward());
    }

    @Test
    void aNewHistoryEndsTheSearchAndTheSameOneKeepsIt() throws IOException {
        LineEditor editor = LineEditor.emacs().withHistory(history("one"));
        LineEditor searching = press(editor, "ctrl+r").editor();

        assertThat(searching.withHistory(editor.history())).isSameAs(searching);

        LineEditor replaced = searching.withHistory(null);
        assertThat(replaced.search()).isEmpty();
        assertThat(replaced.history().isEmpty()).isTrue();
        assertThat(replaced.withHistory(null))
                .as("the same session history again")
                .isSameAs(replaced);
    }

    @Test
    void aSearchWithNoHistoryFailsAndSearchMessagesAloneChangeNothing() {
        LineEditor editor = LineEditor.emacs().withText("abc");

        LineEditor failed = press(editor, "ctrl+r").editor();
        assertThat(failed.text()).isEqualTo("abc");
        assertThat(failed.search().orElseThrow().failed()).isTrue();

        assertThat(editor.update(new LineEditor.Msg.SearchAccept()).editor()).isSameAs(editor);
        assertThat(editor.update(new LineEditor.Msg.SearchErase()).editor()).isSameAs(editor);
        assertThat(editor.update(new LineEditor.Msg.SearchCancel()).editor()).isSameAs(editor);
        assertThat(editor.update(new LineEditor.Msg.SearchInsert("")).editor()).isSameAs(editor);
        assertThat(editor.update(new LineEditor.Msg.SearchInsert(null)).editor())
                .isSameAs(editor);

        LineEditor started = editor.update(new LineEditor.Msg.SearchInsert("x")).editor();
        assertThat(started.search().orElseThrow().query()).isEqualTo("x");
        assertThat(started.search().orElseThrow().failed()).isTrue();
        assertThat(started.text()).isEqualTo("abc");
    }

    @Test
    void theSearchValueSubstitutesItsDefaults() {
        assertThat(new LineEditor.Search(null, null, false))
                .isEqualTo(new LineEditor.Search(LineEditor.Direction.BACKWARD, "", false));
        assertThat(new LineEditor.Search(LineEditor.Direction.FORWARD, "a\r\nb", true).query())
                .isEqualTo("a b");
        assertThat(LineEditor.emacs().history().isEmpty()).isTrue();
        assertThat(LineEditor.emacs().search()).isEmpty();
    }

    @Test
    void aSeededSearchLoopKeepsTheModelConsistent() throws IOException {
        List<String> entries = List.of("one two", "h\u00e9llo", "\u6f22\u5b57", "a\u0301bc", "run tests");
        List<String> letters = List.of("o", "t", "e", "s", " ", "\u00e9", "\u6f22", "\ud83d\ude00", "\u0301");
        String[] gestures = {"ctrl+r", "ctrl+s", "backspace", "enter", "escape", "ctrl+g", "left", "right", "home"};
        Random random = new Random(20261120L);
        LineEditor editor = LineEditor.emacs()
                .withHistory(history(entries.toArray(String[]::new)))
                .withText("draft line");

        for (int step = 0; step < 900; step++) {
            LineEditor next = switch (random.nextInt(4)) {
                case 0 ->
                    press(editor, gestures[random.nextInt(gestures.length)]).editor();
                case 1 ->
                    editor.handle(new InputEvent.PasteInput(random.nextBoolean() ? "o\nt" : "te"))
                            .editor();
                case 2 -> {
                    String typed = letters.get(random.nextInt(letters.size()));
                    yield editor.handle(new InputEvent.KeyInput(
                                    Key.of(typed.isBlank() ? "space" : typed),
                                    typed,
                                    KeyEventType.values()[random.nextInt(KeyEventType.values().length)],
                                    0L))
                            .editor();
                }
                default -> {
                    String typed = letters.get(random.nextInt(letters.size()));
                    yield editor.handle(InputEvent.KeyInput.of(Key.of("x"), typed))
                            .editor();
                }
            };
            editor = next;
            assertThat(editor.caret())
                    .as("caret at step %d", step)
                    .isBetween(0, editor.text().length());
            assertThat(TextMeasure.isBoundary(editor.text(), editor.caret()))
                    .as("caret on a cluster boundary at step %d", step)
                    .isTrue();
            assertThat(editor.text().codePoints().anyMatch(Character::isISOControl))
                    .as("no control character at step %d", step)
                    .isFalse();
            LineEditor.Search search = editor.search().orElse(null);
            if (search != null && !search.failed()) {
                assertThat(editor.history().entries())
                        .as("a matching search shows an entry, at step %d", step)
                        .contains(editor.text());
                assertThat(search.query().indexOf('\n'))
                        .as("one row at step %d", step)
                        .isNegative();
                assertThat(search.query().codePoints().anyMatch(Character::isISOControl))
                        .as("no control character in the query at step %d", step)
                        .isFalse();
            }
        }
    }

    private History history(String... lines) throws IOException {
        Path file = home.resolve("history");
        History history = History.load(file);
        for (String line : lines) {
            history = history.recorded(line);
        }
        return history;
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

    private static LineEditor letter(LineEditor editor, String character) {
        Key gesture = character.isBlank() ? Key.SPACE : Key.of(character);
        return editor.handle(InputEvent.KeyInput.of(gesture, character)).editor();
    }
}

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

import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.line.LineEditor.Msg;

/**
 * The one definition of what a line editor's keys mean in each mode and state.
 *
 * <p>Each map is the set of gestures that are key identities. Vi's letter commands are the one part
 * of its normal mode that cannot be a {@link KeyAction}: the input decoder reports a typed
 * upper-case letter as the lower-case {@link io.github.dhoard.braxton.input.Key} carrying the typed
 * letter as its text, so {@code D} and {@code d} arrive as the same gesture and only the text tells
 * them apart. {@link #viCommand(String)} is therefore the command table, matched against the typed
 * character, and {@link LineEditor#handle} reads it only when no binding matched and no control or
 * alt modifier was held.</p>
 *
 * <p>Both modes keep the LF terminal's Enter, which the decoder can only report as {@code ctrl+j},
 * as a second alias of the submit action: that is the same rule the README's limitations state for
 * the rest of the library, and a line editor that could not accept a line from such a terminal would
 * be unusable rather than merely limited.</p>
 */
final class LineKeys {

    private static final KeyMap<LineEditor.Msg> EMACS = buildEmacs();
    private static final KeyMap<LineEditor.Msg> VI_INSERT = buildViInsert();
    private static final KeyMap<LineEditor.Msg> VI_NORMAL = buildViNormal();
    private static final KeyMap<LineEditor.Msg> SEARCH = buildSearch();
    private static final KeyMap<LineEditor.Msg> COMPLETION = buildCompletion();

    private LineKeys() {}

    /**
     * The Emacs map, built once.
     */
    static KeyMap<LineEditor.Msg> emacs() {
        return EMACS;
    }

    /**
     * The Vi inserting map, built once.
     */
    static KeyMap<LineEditor.Msg> viInsert() {
        return VI_INSERT;
    }

    /**
     * The Vi normal map, built once.
     */
    static KeyMap<LineEditor.Msg> viNormal() {
        return VI_NORMAL;
    }

    /**
     * The map a running search answers with, whichever mode started it, built once.
     *
     * <p>A search owns the keyboard while it runs, so both of its walk directions are here whatever
     * mode started it: a Vi user who searched with {@code /} can still walk the other way with
     * {@code ctrl+s}, and an Emacs search that overshot comes back with {@code ctrl+r}. Enter ends
     * the search without accepting the line - the next Enter accepts it - because a search gesture
     * must not run a command nobody has read in full yet.</p>
     */
    static KeyMap<LineEditor.Msg> search() {
        return SEARCH;
    }

    /**
     * The map a completion menu answers with, built once.
     *
     * <p>A menu owns the keyboard while it shows: {@code tab} and {@code down} step the highlight
     * forward, {@code up} and {@code shift+tab} step it back, {@code enter} accepts the highlighted
     * candidate without accepting the line - the next {@code enter} accepts the line, the rule a
     * search follows too - and {@code escape} ends the menu and leaves the line as it is. Every
     * other key ends the menu and is then applied by the mode's own map.</p>
     */
    static KeyMap<LineEditor.Msg> completion() {
        return COMPLETION;
    }

    /**
     * The gesture a Vi normal-mode command letter names.
     *
     * <p>Matched against the typed text, case included, so {@code x} and {@code X} are different
     * commands. A text that names no command answers null, which a caller must treat as "declined"
     * rather than as "insert this": normal mode types nothing.</p>
     *
     * @param text the typed character; null is declined
     * @return the gesture, or null when the letter is not a command
     */
    static LineEditor.Msg viCommand(String text) {
        if (text == null) {
            return null;
        }
        return switch (text) {
            case "h" -> new LineEditor.Msg.Move(Motion.BACK);
            case "l", " " -> new LineEditor.Msg.Move(Motion.FORWARD);
            case "w" -> new LineEditor.Msg.Move(Motion.WORD_FORWARD);
            case "b" -> new LineEditor.Msg.Move(Motion.WORD_BACK);
            case "e" -> new LineEditor.Msg.Move(Motion.WORD_END);
            case "0" -> new LineEditor.Msg.Move(Motion.LINE_START);
            case "$" -> new LineEditor.Msg.Move(Motion.LINE_END);
            case "x" -> new LineEditor.Msg.Remove(Motion.FORWARD);
            case "X" -> new LineEditor.Msg.Remove(Motion.BACK);
            case "D" -> new LineEditor.Msg.Remove(Motion.LINE_END);
            case "C" -> new LineEditor.Msg.Change(Motion.LINE_END);
            case "s" -> new LineEditor.Msg.Change(Motion.FORWARD);
            case "i" -> new LineEditor.Msg.StartInsert(LineEditor.Place.HERE);
            case "a" -> new LineEditor.Msg.StartInsert(LineEditor.Place.AFTER);
            case "I" -> new LineEditor.Msg.StartInsert(LineEditor.Place.LINE_START);
            case "A" -> new LineEditor.Msg.StartInsert(LineEditor.Place.LINE_END);
            case "d" -> new LineEditor.Msg.Operate(LineEditor.Operator.DELETE);
            case "c" -> new LineEditor.Msg.Operate(LineEditor.Operator.CHANGE);
            case "p", "P" -> new LineEditor.Msg.Yank();
            case "/" -> new LineEditor.Msg.SearchBackward();
            case "?" -> new LineEditor.Msg.SearchForward();
            default -> null;
        };
    }

    private static KeyMap<LineEditor.Msg> buildEmacs() {
        return KeyMap.of(
                KeyMap.group(
                        "Cursor",
                        KeyAction.of(
                                "start",
                                new LineEditor.Msg.Move(Motion.LINE_START),
                                "",
                                "start of line",
                                "ctrl+a",
                                "home"),
                        KeyAction.of(
                                "end", new LineEditor.Msg.Move(Motion.LINE_END), "", "end of line", "ctrl+e", "end"),
                        KeyAction.of(
                                "back",
                                new LineEditor.Msg.Move(Motion.BACK),
                                "",
                                "back one character",
                                "ctrl+b",
                                "left"),
                        KeyAction.of(
                                "forward",
                                new LineEditor.Msg.Move(Motion.FORWARD),
                                "",
                                "forward one character",
                                "ctrl+f",
                                "right"),
                        KeyAction.of(
                                "word-back", new LineEditor.Msg.Move(Motion.WORD_BACK), "", "back one word", "alt+b"),
                        KeyAction.of(
                                "word-forward",
                                new LineEditor.Msg.Move(Motion.WORD_FORWARD),
                                "",
                                "forward one word",
                                "alt+f")),
                KeyMap.group(
                        "Editing",
                        KeyAction.of(
                                "erase-back",
                                new LineEditor.Msg.EraseBack(),
                                "",
                                "erase the character before",
                                "backspace"),
                        KeyAction.of(
                                "erase-forward",
                                new LineEditor.Msg.EraseForward(),
                                "",
                                "erase the character at the caret",
                                "delete"),
                        KeyAction.of(
                                "kill-to-end",
                                new LineEditor.Msg.Remove(Motion.LINE_END),
                                "",
                                "kill to the end",
                                "ctrl+k"),
                        KeyAction.of(
                                "kill-to-start",
                                new LineEditor.Msg.Remove(Motion.LINE_START),
                                "",
                                "kill to the start",
                                "ctrl+u"),
                        KeyAction.of(
                                "kill-word-back",
                                new LineEditor.Msg.Remove(Motion.WORD_BACK),
                                "",
                                "kill the word before",
                                "ctrl+w"),
                        KeyAction.of(
                                "kill-word-forward",
                                new LineEditor.Msg.Remove(Motion.WORD_FORWARD),
                                "",
                                "kill the word after",
                                "alt+d"),
                        KeyAction.of("yank", new LineEditor.Msg.Yank(), "", "yank the killed text", "ctrl+y"),
                        KeyAction.of(
                                "transpose", new LineEditor.Msg.Transpose(), "", "transpose characters", "ctrl+t")),
                KeyMap.group(
                        "Completion",
                        KeyAction.of("complete", new LineEditor.Msg.Complete(), "", "complete the word", "tab")),
                KeyMap.group(
                        "Search",
                        KeyAction.of(
                                "search-backward",
                                new LineEditor.Msg.SearchBackward(),
                                "",
                                "search the history for older lines",
                                "ctrl+r"),
                        KeyAction.of(
                                "search-forward",
                                new LineEditor.Msg.SearchForward(),
                                "",
                                "search the history for newer lines",
                                "ctrl+s")),
                KeyMap.group(
                        "Session",
                        KeyAction.of("submit", new LineEditor.Msg.Submit(), "", "accept the line", "enter", "ctrl+j"),
                        KeyAction.of("cancel", new LineEditor.Msg.Cancel(), "", "abandon the line", "ctrl+c"),
                        KeyAction.of(
                                "eof", new LineEditor.Msg.EndOfInput(), "", "end input on an empty line", "ctrl+d")));
    }

    private static KeyMap<LineEditor.Msg> buildViInsert() {
        return KeyMap.of(
                KeyMap.group(
                        "Cursor",
                        KeyAction.of("back", new LineEditor.Msg.Move(Motion.BACK), "", "back one character", "left"),
                        KeyAction.of(
                                "forward",
                                new LineEditor.Msg.Move(Motion.FORWARD),
                                "",
                                "forward one character",
                                "right"),
                        KeyAction.of("start", new LineEditor.Msg.Move(Motion.LINE_START), "", "start of line", "home"),
                        KeyAction.of("end", new LineEditor.Msg.Move(Motion.LINE_END), "", "end of line", "end")),
                KeyMap.group(
                        "Editing",
                        KeyAction.of(
                                "erase-back",
                                new LineEditor.Msg.EraseBack(),
                                "",
                                "erase the character before",
                                "backspace"),
                        KeyAction.of(
                                "erase-forward",
                                new LineEditor.Msg.EraseForward(),
                                "",
                                "erase the character at the caret",
                                "delete")),
                KeyMap.group(
                        "Completion",
                        KeyAction.of("complete", new LineEditor.Msg.Complete(), "", "complete the word", "tab")),
                KeyMap.group(
                        "Mode", KeyAction.of("normal", new LineEditor.Msg.Normal(), "", "leave insert mode", "escape")),
                KeyMap.group(
                        "Session",
                        KeyAction.of("submit", new LineEditor.Msg.Submit(), "", "accept the line", "enter", "ctrl+j"),
                        KeyAction.of("cancel", new LineEditor.Msg.Cancel(), "", "abandon the line", "ctrl+c"),
                        KeyAction.of(
                                "eof", new LineEditor.Msg.EndOfInput(), "", "end input on an empty line", "ctrl+d")));
    }

    private static KeyMap<LineEditor.Msg> buildSearch() {
        return KeyMap.of(
                KeyMap.group(
                        "Search",
                        KeyAction.of(
                                "search-backward",
                                new LineEditor.Msg.SearchBackward(),
                                "",
                                "search for an older match",
                                "ctrl+r"),
                        KeyAction.of(
                                "search-forward",
                                new LineEditor.Msg.SearchForward(),
                                "",
                                "search for a newer match",
                                "ctrl+s"),
                        KeyAction.of(
                                "search-erase",
                                new LineEditor.Msg.SearchErase(),
                                "",
                                "take a character back off the search",
                                "backspace"),
                        KeyAction.of(
                                "search-accept",
                                new LineEditor.Msg.SearchAccept(),
                                "",
                                "end the search on the line it found",
                                "enter",
                                "ctrl+j"),
                        KeyAction.of(
                                "search-cancel",
                                new LineEditor.Msg.SearchCancel(),
                                "",
                                "abandon the search",
                                "escape",
                                "ctrl+g")),
                KeyMap.group(
                        "Session",
                        KeyAction.of("cancel", new LineEditor.Msg.Cancel(), "", "abandon the line", "ctrl+c")));
    }

    private static KeyMap<LineEditor.Msg> buildCompletion() {
        return KeyMap.of(
                KeyMap.group(
                        "Completion",
                        KeyAction.of(
                                "complete-next",
                                new LineEditor.Msg.CompleteNext(),
                                "",
                                "highlight the next candidate",
                                "tab",
                                "down"),
                        KeyAction.of(
                                "complete-previous",
                                new LineEditor.Msg.CompletePrevious(),
                                "",
                                "highlight the previous candidate",
                                "shift+tab",
                                "up"),
                        KeyAction.of(
                                "complete-accept",
                                new LineEditor.Msg.CompleteAccept(),
                                "",
                                "insert the highlighted candidate",
                                "enter",
                                "ctrl+j"),
                        KeyAction.of(
                                "complete-cancel",
                                new LineEditor.Msg.CompleteCancel(),
                                "",
                                "dismiss the candidates",
                                "escape")),
                KeyMap.group(
                        "Session",
                        KeyAction.of("cancel", new LineEditor.Msg.Cancel(), "", "abandon the line", "ctrl+c")));
    }

    private static KeyMap<LineEditor.Msg> buildViNormal() {
        return KeyMap.of(
                KeyMap.group(
                        "Cursor",
                        KeyAction.of(
                                "back",
                                new LineEditor.Msg.Move(Motion.BACK),
                                "",
                                "back one character",
                                "left",
                                "backspace"),
                        KeyAction.of(
                                "forward",
                                new LineEditor.Msg.Move(Motion.FORWARD),
                                "",
                                "forward one character",
                                "right"),
                        KeyAction.of("start", new LineEditor.Msg.Move(Motion.LINE_START), "", "start of line", "home"),
                        KeyAction.of("end", new LineEditor.Msg.Move(Motion.LINE_END), "", "end of line", "end")),
                KeyMap.group(
                        "Editing",
                        KeyAction.of(
                                "erase-forward",
                                new LineEditor.Msg.Remove(Motion.FORWARD),
                                "",
                                "delete the character at the caret",
                                "delete")),
                KeyMap.group(
                        "Mode",
                        KeyAction.of("normal", new LineEditor.Msg.Normal(), "", "drop a half-typed command", "escape")),
                KeyMap.group(
                        "Session",
                        KeyAction.of("submit", new LineEditor.Msg.Submit(), "", "accept the line", "enter", "ctrl+j"),
                        KeyAction.of("cancel", new LineEditor.Msg.Cancel(), "", "abandon the line", "ctrl+c")));
    }

    /**
     * Whether a gesture is one the running search answers itself.
     */
    static boolean searchGesture(Msg message) {
        return switch (message) {
            case Msg.SearchBackward ignored -> true;
            case Msg.SearchForward ignored -> true;
            case Msg.SearchInsert ignored -> true;
            case Msg.SearchErase ignored -> true;
            case Msg.SearchAccept ignored -> true;
            case Msg.SearchCancel ignored -> true;
            default -> false;
        };
    }

    /**
     * Whether a gesture is one the completion menu answers itself.
     */
    static boolean completionGesture(Msg message) {
        return switch (message) {
            case Msg.Complete ignored -> true;
            case Msg.CompleteNext ignored -> true;
            case Msg.CompletePrevious ignored -> true;
            case Msg.CompleteAccept ignored -> true;
            case Msg.CompleteCancel ignored -> true;
            default -> false;
        };
    }
}

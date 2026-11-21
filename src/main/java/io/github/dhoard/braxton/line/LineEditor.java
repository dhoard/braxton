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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.Modifiers;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An immutable, grapheme-safe single-line editor with Emacs and Vi bindings.
 *
 * <p>{@link #handle(InputEvent)} and {@link #update(Msg)} are pure transitions returning the next
 * editor and typed outputs. A no-op preserves identity. {@link LineReader} drives terminal input;
 * {@link LineRenderer} paints the same model an MVU application can use.</p>
 *
 * <p>Offsets are UTF-16 positions snapped to cluster boundaries. {@link #withText(String)} and
 * {@link Msg.Insert} flatten line breaks and replace controls with spaces. Paste is one sanitized
 * insertion and cannot submit the line.</p>
 *
 * <p>Emacs mode inserts text and supports motion, kill, and yank chords. Vi starts in insert mode;
 * Escape enters normal mode and steps back one cluster. Normal-mode letter commands use the typed
 * character, including case, and ignore control/alt chords. Operators {@code d} and {@code c} wait
 * for a motion; repeating an operator takes the whole line. Escape and non-motion gestures discard
 * a pending operator. Other keys use the mode's {@link KeyMap}.</p>
 *
 * <p>Explicit kills replace {@link #kill()}; single-cluster erases leave it unchanged. {@link
 * Msg.Yank} inserts at the caret, as do Vi's {@code p} and {@code P}; there is no accumulating kill
 * ring.</p>
 *
 * <p>History search matches case-sensitive substrings. An empty query matches every entry.
 * Backward/forward gestures walk older/newer matches; failed steps retain the last match and set
 * {@link Search#failed()}. Accept keeps the match; cancel restores the starting line. Other
 * gestures accept the match before applying. Search clears pending Vi operators, and empty history
 * produces a failed search.</p>
 *
 * <p>{@link Completer} replaces the non-blank token ending at the caret. One candidate inserts
 * immediately; several open a {@link Completion} menu whose next/previous gestures move the
 * highlight. Accept replaces the token; cancel preserves the draft. Other gestures dismiss the menu
 * before applying. Completion is disabled without a provider, in Vi normal mode, and for masked
 * input.</p>
 *
 * <p>{@link Highlighter} supplies styles per cluster without changing text or geometry. Masking
 * paints one glyph per real cluster, retains the real draft and caret offsets, and disables
 * completion and highlighting to avoid revealing hidden text.</p>
 */
public final class LineEditor {

    /**
     * Whether the editor is taking typed text or answering keys as commands.
     */
    public enum State {

        /**
         * Typed text is inserted at the caret.
         */
        INSERT,

        /**
         * Keys are commands; only Vi has this state.
         */
        NORMAL
    }

    /**
     * A Vi operator that waits for a motion to say what it applies to.
     */
    public enum Operator {

        /**
         * Remove the range the motion covers.
         */
        DELETE,

        /**
         * Remove the range the motion covers and start inserting at its start.
         */
        CHANGE
    }

    /**
     * Where {@link Msg.StartInsert} puts the caret before it starts inserting.
     */
    public enum Place {

        /**
         * At the caret, which is Vi's {@code i}.
         */
        HERE,

        /**
         * Just past the cluster at the caret, which is Vi's {@code a}.
         */
        AFTER,

        /**
         * At the start of the line, which is Vi's {@code I}.
         */
        LINE_START,

        /**
         * Just past the last cluster, which is Vi's {@code A}.
         */
        LINE_END
    }

    /**
     * One gesture the editor applies.
     *
     * <p>A message is what a key means once its mode has read it, so an application can drive the
     * editor from its own model as well as from {@link #handle(InputEvent)}.</p>
     */
    public sealed interface Msg {

        /**
         * Inserts typed or pasted text at the caret.
         *
         * @param text the text to insert; line breaks and control characters are flattened
         */
        record Insert(String text) implements Msg {}

        /**
         * Removes the cluster before the caret, which is backspace.
         */
        record EraseBack() implements Msg {}

        /**
         * Removes the cluster at the caret, which is forward delete.
         */
        record EraseForward() implements Msg {}

        /**
         * Moves the caret.
         *
         * @param motion the motion to make
         */
        record Move(Motion motion) implements Msg {}

        /**
         * Removes the range a motion covers and keeps it as the kill buffer.
         *
         * @param motion the motion that measures the range
         */
        record Remove(Motion motion) implements Msg {}

        /**
         * Removes the range a motion covers, keeps it, and starts inserting at its start.
         *
         * @param motion the motion that measures the range
         */
        record Change(Motion motion) implements Msg {}

        /**
         * Arms a Vi operator, or takes the whole line when the same operator is already armed.
         *
         * @param operator the operator to arm
         */
        record Operate(Operator operator) implements Msg {}

        /**
         * Moves the caret to a place and starts inserting there.
         *
         * @param place where the caret goes
         */
        record StartInsert(Place place) implements Msg {}

        /**
         * Leaves insert mode, or drops a half-typed operator in normal mode.
         */
        record Normal() implements Msg {}

        /**
         * Inserts the kill buffer at the caret.
         */
        record Yank() implements Msg {}

        /**
         * Swaps the cluster at the caret with the one before it.
         */
        record Transpose() implements Msg {}

        /**
         * Accepts the line.
         */
        record Submit() implements Msg {}

        /**
         * Abandons the line.
         */
        record Cancel() implements Msg {}

        /**
         * Ends input: end of input on an empty line, forward delete on any other.
         */
        record EndOfInput() implements Msg {}

        /**
         * Starts an incremental search over the history, or steps it toward older entries.
         */
        record SearchBackward() implements Msg {}

        /**
         * Starts an incremental search over the history, or steps it toward newer entries.
         */
        record SearchForward() implements Msg {}

        /**
         * Narrows the running search with more of the text being looked for.
         *
         * @param text the text to add to the query; line breaks and control characters are flattened
         */
        record SearchInsert(String text) implements Msg {}

        /**
         * Takes one character back off the query and searches again.
         */
        record SearchErase() implements Msg {}

        /**
         * Ends the search on the line it found, which becomes the line being edited.
         */
        record SearchAccept() implements Msg {}

        /**
         * Ends the search and restores the line and caret it started from.
         */
        record SearchCancel() implements Msg {}

        /**
         * Asks the completer for candidates for the token that ends at the caret.
         */
        record Complete() implements Msg {}

        /**
         * Moves the completion menu's highlight to the next candidate, wrapping at its end.
         */
        record CompleteNext() implements Msg {}

        /**
         * Moves the completion menu's highlight to the previous candidate, wrapping at its start.
         */
        record CompletePrevious() implements Msg {}

        /**
         * Replaces the token with the highlighted candidate and ends the menu.
         */
        record CompleteAccept() implements Msg {}

        /**
         * Ends the completion menu and leaves the line as the user typed it.
         */
        record CompleteCancel() implements Msg {}
    }

    /**
     * What the host has to do about a message.
     */
    public sealed interface Output {

        /**
         * The line was accepted.
         *
         * @param text the accepted one-row text
         */
        record Accepted(String text) implements Output {

            /**
             * Creates a {@code Accepted} value.
             *
             * @param text the accepted one-row text
             */
            public Accepted {
                if (text == null) {
                    throw new IllegalArgumentException("accepted text is required");
                }
            }
        }

        /**
         * The line was abandoned; the editor's text is still readable but must not be used.
         */
        record Canceled() implements Output {}

        /**
         * Input ended before a line was accepted.
         */
        record Eof() implements Output {}
    }

    /**
     * The editor a message left behind, and anything the host has to do about it.
     *
     * @param editor the editor to use from now on
     * @param outputs what the message asked the host to do, in order
     */
    public record Result(LineEditor editor, List<Output> outputs) {

        /**
         * Rejects a missing editor and copies the outputs.
         */
        public Result {
            Objects.requireNonNull(editor, "editor");
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }

        /**
         * A result that asks the host for nothing.
         *
         * @param editor the editor the message left behind
         * @return the editor with no typed outputs
         */
        public static Result of(LineEditor editor) {
            return new Result(editor, List.of());
        }
    }

    /**
     * The way an incremental search walks the history.
     *
     * <p>The name is the direction through the list, so a backward search looks for lines older than
     * the one it is showing and a forward search for newer ones.</p>
     */
    public enum Direction {

        /**
         * Toward older entries: what {@code ctrl+r} and Vi's {@code /} look for.
         */
        BACKWARD,

        /**
         * Toward newer entries: what {@code ctrl+s} and Vi's {@code ?} look for.
         */
        FORWARD
    }

    /**
     * An incremental search over the history that is running.
     *
     * <p>A search is state rather than a message: the editor that holds it is showing the entry the
     * query last matched, and this says what was typed and which way the walk is going so the next
     * gesture continues from where the previous one stopped. While a search runs, {@link #text()} is
     * the matched entry, {@link #caret()} is where the query matched it, and {@link #keyMap()} is the
     * search map.</p>
     *
     * @param direction the way the walk moves through the history
     * @param query the text typed into the search; never null, and one row
     * @param failed whether the last step found nothing, which a host paints rather than hides
     */
    public record Search(Direction direction, String query, boolean failed) {

        /**
         * Rejects nothing: a missing direction is {@link Direction#BACKWARD} and missing text is
         * empty, so a host reading a search never has to guard against one.
         */
        public Search {
            direction = direction == null ? Direction.BACKWARD : direction;
            query = LineText.singleRow(query);
        }
    }

    /**
     * What an editor with no history of its own searches.
     */
    private static final History NO_HISTORY = History.inMemory();

    private final Mode mode;
    private final State state;
    private final String text;
    private final int caret;
    private final String kill;
    private final Operator pending;
    private final History history;
    private final LineSearch.Run running;
    private final Completion completion;
    private final Completer completer;
    private final Highlighter highlighter;
    private final boolean masked;

    private LineEditor(
            Mode mode,
            State state,
            String text,
            int caret,
            String kill,
            Operator pending,
            History history,
            LineSearch.Run running,
            Completion completion,
            Completer completer,
            Highlighter highlighter,
            boolean masked) {
        this.mode = mode;
        this.state = mode == Mode.EMACS ? State.INSERT : state;
        this.text = text;
        this.caret = LineText.boundaryBefore(text, Math.max(0, Math.min(caret, text.length())));
        this.kill = kill;
        this.pending = pending;
        this.history = history;
        this.running = running;
        this.completion = completion;
        this.completer = completer;
        this.highlighter = highlighter;
        this.masked = masked;
    }

    /**
     * An empty Emacs editor.
     *
     * @return the editor
     */
    public static LineEditor emacs() {
        return of(Mode.EMACS, State.INSERT);
    }

    /**
     * An empty Vi editor, inserting.
     *
     * @return the editor
     */
    public static LineEditor vi() {
        return of(Mode.VI, State.INSERT);
    }

    /**
     * An empty editor in a mode.
     *
     * @param mode the mode; null is {@link Mode#EMACS}
     * @return the editor
     */
    public static LineEditor of(Mode mode) {
        return of(mode, State.INSERT);
    }

    /**
     * An empty editor in a mode and state.
     *
     * <p>Useful to start where a session left off: Emacs is always inserting, so a state asked for
     * there is {@link State#INSERT}, and a Vi editor may start in either state.</p>
     *
     * @param mode the mode; null is {@link Mode#EMACS}
     * @param state the state; null is {@link State#INSERT}
     * @return the editor
     */
    public static LineEditor of(Mode mode, State state) {
        Mode wantedMode = mode == null ? Mode.EMACS : mode;
        State wantedState = wantedMode == Mode.EMACS || state == null ? State.INSERT : state;
        return new LineEditor(wantedMode, wantedState, "", 0, "", null, NO_HISTORY, null, null, null, null, false);
    }

    /**
     * The configured mode; never null.
     *
     * @return the configured mode; never null
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Whether typed text is inserted or keys are commands.
     *
     * @return whether typed text is inserted or keys are commands
     */
    public State state() {
        return state;
    }

    /**
     * The one row of text; never null, and the real text even when the editor paints a mask.
     *
     * @return the one row of text; never null, and the real text even when the editor paints a mask
     */
    public String text() {
        return text;
    }

    /**
     * The caret's position between clusters, from zero to the text's length.
     *
     * @return the caret's position between clusters, from zero to the text's length
     */
    public int caret() {
        return caret;
    }

    /**
     * The text the last explicit removal took, or empty when nothing was removed.
     *
     * @return the text the last explicit removal took, or empty when nothing was removed
     */
    public String kill() {
        return kill;
    }

    /**
     * The Vi operator waiting for a motion, or empty when no operator is armed.
     *
     * @return the Vi operator waiting for a motion, or empty when no operator is armed
     */
    public Optional<Operator> pending() {
        return Optional.ofNullable(pending);
    }

    /**
     * The lines a reverse search walks; never null, and empty when the editor was given none.
     *
     * @return the lines a reverse search walks; never null, and empty when the editor was given none
     */
    public History history() {
        return history;
    }

    /**
     * The incremental search running over the history, or empty when none is running.
     *
     * @return the incremental search running over the history, or empty when none is running
     */
    public Optional<Search> search() {
        return running == null ? Optional.empty() : Optional.of(running.search());
    }

    /**
     * The completion menu the editor is showing, or empty when none is showing.
     *
     * @return the completion menu the editor is showing, or empty when none is showing
     */
    public Optional<Completion> completion() {
        return Optional.ofNullable(completion);
    }

    /**
     * The candidate source completion asks, or empty when the editor was given none.
     *
     * @return the candidate source completion asks, or empty when the editor was given none
     */
    public Optional<Completer> completer() {
        return Optional.ofNullable(completer);
    }

    /**
     * The highlighter the renderer paints the row with, or empty when the editor has none.
     *
     * @return the highlighter the renderer paints the row with, or empty when the editor has none
     */
    public Optional<Highlighter> highlighter() {
        return Optional.ofNullable(highlighter);
    }

    /**
     * Whether the row paints a mask glyph per cluster instead of the text.
     *
     * @return {@code true} if the row paints a mask glyph per cluster instead of the text; otherwise {@code false}
     */
    public boolean masked() {
        return masked;
    }

    /**
     * The same editor over another history.
     *
     * <p>A different history ends any running search; the same history preserves this instance.</p>
     *
     * @param value the history; null is a session-only history with no entries
     * @return this instance when nothing changed
     */
    public LineEditor withHistory(History value) {
        History wanted = value == null ? NO_HISTORY : value;
        if (wanted == history) {
            return this;
        }
        return new LineEditor(
                mode, state, text, caret, kill, pending, wanted, null, completion, completer, highlighter, masked);
    }

    /**
     * The same editor with a candidate source.
     *
     * <p>The editor keeps the completer so no message has to carry it. A different source ends a
     * menu that is showing: its candidates came from the source no longer being asked.</p>
     *
     * @param value the completer; null is no completion
     * @return this instance when nothing changed
     */
    public LineEditor withCompleter(Completer value) {
        if (value == completer) {
            return this;
        }
        return new LineEditor(
                mode, state, text, caret, kill, pending, history, running, null, value, highlighter, masked);
    }

    /**
     * The same editor with a highlighter the renderer paints with.
     *
     * <p>A highlighter is presentation only: it changes neither the line, the caret nor a menu.</p>
     *
     * @param value the highlighter; null is no highlighting
     * @return this instance when nothing changed
     */
    public LineEditor withHighlighter(Highlighter value) {
        if (value == highlighter) {
            return this;
        }
        return new LineEditor(
                mode, state, text, caret, kill, pending, history, running, completion, completer, value, masked);
    }

    /**
     * The same editor painting a mask glyph per cluster.
     *
     * <p>The mask is presentation: the line, the caret and every edit stay on the real text, so
     * {@link #text()} answers what the user typed. Masking ends a menu, which would list it.</p>
     *
     * @param value whether to mask the row
     * @return this instance when nothing changed
     */
    public LineEditor withMasked(boolean value) {
        if (value == masked) {
            return this;
        }
        return new LineEditor(
                mode, state, text, caret, kill, pending, history, running, null, completer, highlighter, value);
    }

    /**
     * The same text in another mode, inserting and with no operator armed.
     *
     * <p>Switching modes keeps the text, the caret and the kill buffer: changing bindings is not
     * abandoning the line.</p>
     *
     * @param value the mode; null is {@link Mode#EMACS}
     * @return this instance when nothing changed
     */
    public LineEditor withMode(Mode value) {
        Mode wanted = value == null ? Mode.EMACS : value;
        if (wanted == mode && state == State.INSERT && pending == null && running == null && completion == null) {
            return this;
        }
        return new LineEditor(
                wanted, State.INSERT, text, caret, kill, null, history, null, null, completer, highlighter, masked);
    }

    /**
     * The same editor showing other text, with the caret at its end.
     *
     * @param value the text; line breaks and control characters are flattened
     * @return this instance when nothing changed
     */
    public LineEditor withText(String value) {
        String wanted = LineText.singleRow(value);
        int end = wanted.length();
        if (wanted.equals(text) && end == caret && pending == null && running == null && completion == null) {
            return this;
        }
        return new LineEditor(
                mode, state, wanted, end, kill, null, history, null, null, completer, highlighter, masked);
    }

    /**
     * The same editor with the caret at a position, clamped and moved to a cluster boundary.
     *
     * <p>An application moving the caret is not a motion, so an armed Vi operator is dropped: it was
     * armed over a caret that no longer exists.</p>
     *
     * @param value the wanted position
     * @return this instance when nothing changed
     */
    public LineEditor withCaret(int value) {
        int wanted = LineText.boundaryBefore(text, Math.max(0, Math.min(value, text.length())));
        if (wanted == caret && pending == null && running == null && completion == null) {
            return this;
        }
        return new LineEditor(
                mode, state, text, wanted, kill, null, history, null, null, completer, highlighter, masked);
    }

    /**
     * The active key map, including search or completion ownership when either is open.
     *
     * <p>Vi letter commands depend on typed case and are handled separately by {@link #handle(InputEvent)}.</p>
     *
     * @return the map used by key routing and help
     */
    public KeyMap<Msg> keyMap() {
        if (running != null) {
            return LineKeys.search();
        }
        return completion == null ? keys(mode, state) : LineKeys.completion();
    }

    /**
     * The key map one mode and state answer with, for a caller that renders help before it starts.
     *
     * @param mode the mode; null is {@link Mode#EMACS}
     * @param state the state; null is {@link State#INSERT}
     * @return the map
     */
    public static KeyMap<Msg> keys(Mode mode, State state) {
        Mode wantedMode = mode == null ? Mode.EMACS : mode;
        State wantedState = state == null ? State.INSERT : state;
        if (wantedMode == Mode.EMACS) {
            return LineKeys.emacs();
        }
        return wantedState == State.NORMAL ? LineKeys.viNormal() : LineKeys.viInsert();
    }

    /**
     * Applies normalized input, preserving identity for unhandled events and key releases.
     *
     * <p>Paste inserts atomically; printable unbound keys insert in insert mode or name Vi commands in
     * normal mode. Search handles text first; search and completion apply their own keys, then end before
     * an unrelated gesture is applied to the draft.</p>
     *
     * @param event the event; null changes nothing
     * @return the editor transition and outputs
     */
    public Result handle(InputEvent event) {
        if (event == null) {
            return unchanged();
        }
        if (event instanceof InputEvent.PasteInput paste) {
            String pasted = LineText.pasted(paste.text());
            return running == null ? update(new Msg.Insert(pasted)) : update(new Msg.SearchInsert(pasted));
        }
        if (!(event instanceof InputEvent.KeyInput key) || !key.activates()) {
            return unchanged();
        }
        if (running != null) {
            Optional<KeyAction<Msg>> found = keyMap().actionFor(key.key());
            if (found.isPresent()) {
                return update(found.get().message());
            }
            if (!key.text().isEmpty()) {
                return update(new Msg.SearchInsert(key.text()));
            }
            // A gesture the search does not answer ends it on the line it found, then applies.
            return update(new Msg.SearchAccept()).editor().handle(event);
        }
        if (completion != null) {
            Optional<KeyAction<Msg>> found = keyMap().actionFor(key.key());
            if (found.isPresent()) {
                return update(found.get().message());
            }
            // A gesture the menu does not answer ends it and is applied to the line it left.
            return update(new Msg.CompleteCancel()).editor().handle(event);
        }
        Optional<KeyAction<Msg>> action = keyMap().actionFor(key.key());
        if (action.isPresent()) {
            return update(action.get().message());
        }
        if (key.text().isEmpty()) {
            return unchanged();
        }
        if (state == State.INSERT) {
            return update(new Msg.Insert(key.text()));
        }
        Modifiers modifiers = key.key().modifiers();
        if (modifiers.ctrl() || modifiers.alt()) {
            return unchanged();
        }
        Msg command = LineKeys.viCommand(key.text());
        if (command == null) {
            // An unbound letter is declined, and it also abandons an operator it cannot apply.
            return pending == null ? unchanged() : Result.of(edit(state, text, caret, kill, null));
        }
        return update(command);
    }

    /**
     * Applies one gesture.
     *
     * <p>While a search is running, a gesture that is not one of the search's own ends the search on
     * the line it found and is then applied to that line, which is the rule {@link #handle(InputEvent)}
     * gives a key the search does not answer. A completion menu follows the same rule.</p>
     *
     * @param message the gesture; null changes nothing
     * @return the editor and outputs the gesture produced, which is this editor unchanged when the
     *     gesture is a no-op
     */
    public Result update(Msg message) {
        if (message == null) {
            return unchanged();
        }
        if (running != null && !LineKeys.searchGesture(message)) {
            return endedSearch().update(message);
        }
        if (completion != null && !LineKeys.completionGesture(message)) {
            return endedMenu().update(message);
        }
        return switch (message) {
            case Msg.Insert insert -> insert(insert.text());
            case Msg.EraseBack ignored -> eraseBack();
            case Msg.EraseForward ignored -> eraseForward();
            case Msg.Move move -> move(move.motion());
            case Msg.Remove remove -> remove(remove.motion());
            case Msg.Change change -> change(change.motion());
            case Msg.Operate operate -> operate(operate.operator());
            case Msg.StartInsert start -> startInsert(start.place());
            case Msg.Normal ignored -> normal();
            case Msg.Yank ignored -> yank();
            case Msg.Transpose ignored -> transpose();
            case Msg.Submit ignored -> new Result(this, List.of(new Output.Accepted(text)));
            case Msg.Cancel ignored -> new Result(this, List.of(new Output.Canceled()));
            case Msg.EndOfInput ignored -> endOfInput();
            case Msg.SearchBackward ignored -> find(Direction.BACKWARD);
            case Msg.SearchForward ignored -> find(Direction.FORWARD);
            case Msg.SearchInsert insert -> searchInsert(insert.text());
            case Msg.SearchErase ignored -> searchErase();
            case Msg.SearchAccept ignored -> searchAccept();
            case Msg.SearchCancel ignored -> searchCancel();
            case Msg.Complete ignored -> complete();
            case Msg.CompleteNext ignored -> stepCompletion(true);
            case Msg.CompletePrevious ignored -> stepCompletion(false);
            case Msg.CompleteAccept ignored -> completeAccept();
            case Msg.CompleteCancel ignored -> completeCancel();
        };
    }

    /**
     * The same editor with no search running.
     */
    private LineEditor endedSearch() {
        return new LineEditor(
                mode, state, text, caret, kill, pending, history, null, completion, completer, highlighter, masked);
    }

    /**
     * The same editor with no completion menu showing.
     */
    private LineEditor endedMenu() {
        return new LineEditor(
                mode, state, text, caret, kill, pending, history, running, null, completer, highlighter, masked);
    }

    /**
     * The same settings with new editing state.
     *
     * <p>An edit ends a search and a menu, which the message dispatch has already done by the time an
     * edit runs: a gesture neither answers ends it before the gesture applies.</p>
     */
    private LineEditor edit(State nextState, String nextText, int nextCaret, String nextKill, Operator nextPending) {
        return new LineEditor(
                mode,
                nextState,
                nextText,
                nextCaret,
                nextKill,
                nextPending,
                history,
                null,
                null,
                completer,
                highlighter,
                masked);
    }

    /**
     * The same editor showing the line a search step found.
     */
    private LineEditor shown(LineSearch.Step step) {
        return new LineEditor(
                mode,
                state,
                step.text(),
                step.caret(),
                kill,
                null,
                history,
                step.run(),
                null,
                completer,
                highlighter,
                masked);
    }

    /**
     * The same editor showing a completion menu.
     */
    private LineEditor completed(Completion menu) {
        if (menu.equals(completion)) {
            return this;
        }
        return new LineEditor(
                mode, state, text, caret, kill, pending, history, null, menu, completer, highlighter, masked);
    }

    /**
     * Starts or steps history search, clearing pending operators through the resulting search state.
     */
    private Result find(Direction direction) {
        return Result.of(shown(LineSearch.find(history, text, caret, running, direction)));
    }

    /**
     * Adds typed text to the query and searches again from the start of the walk.
     */
    private Result searchInsert(String raw) {
        String added = LineText.singleRow(raw);
        return added.isEmpty()
                ? unchanged()
                : Result.of(shown(LineSearch.insert(history, text, caret, running, added)));
    }

    /**
     * Takes one cluster back off the query and searches again, or changes nothing when it is empty.
     */
    private Result searchErase() {
        if (running == null || running.search().query().isEmpty()) {
            return unchanged();
        }
        return Result.of(shown(LineSearch.erase(history, text, caret, running)));
    }

    /**
     * Ends the search on the line it is showing, which is then the line being edited.
     */
    private Result searchAccept() {
        return running == null ? unchanged() : Result.of(endedSearch());
    }

    /**
     * Ends the search and restores the line and caret it started from.
     */
    private Result searchCancel() {
        return running == null ? unchanged() : Result.of(edit(state, running.line(), running.caret(), kill, null));
    }

    /**
     * Completes the token ending at the caret: one candidate replaces it, several open a menu.
     * An absent provider, masked or normal-mode input, or an empty offer leaves the editor unchanged.
     */
    private Result complete() {
        if (completer == null || masked || state != State.INSERT) {
            return unchanged();
        }
        int start = LineText.tokenStart(text, caret);
        Optional<Completion> menu =
                Completion.of(start, text.substring(start, caret), completer.candidates(text, caret));
        if (menu.isEmpty()) {
            return unchanged();
        }
        Completion offered = menu.get();
        if (offered.candidates().size() == 1) {
            return Result.of(replaced(offered.start(), offered.end(), offered.candidate()));
        }
        return Result.of(completed(offered));
    }

    /**
     * Steps the menu's highlight, wrapping at either end.
     */
    private Result stepCompletion(boolean forward) {
        if (completion == null) {
            return unchanged();
        }
        return Result.of(completed(forward ? completion.next() : completion.previous()));
    }

    /**
     * Replaces the token with the highlighted candidate.
     */
    private Result completeAccept() {
        if (completion == null) {
            return unchanged();
        }
        return Result.of(
                replaced(completion.start(), Math.min(completion.end(), text.length()), completion.candidate()));
    }

    /**
     * Ends the menu and leaves the line as the user typed it.
     */
    private Result completeCancel() {
        return completion == null ? unchanged() : Result.of(endedMenu());
    }

    /**
     * The same editor with one range swapped for a candidate.
     */
    private LineEditor replaced(int start, int end, String candidate) {
        String next = text.substring(0, start) + candidate + text.substring(end);
        int nextCaret = start + candidate.length();
        return next.equals(text) && nextCaret == caret ? this : edit(state, next, nextCaret, kill, null);
    }

    private Result unchanged() {
        return Result.of(this);
    }

    private Result insert(String raw) {
        if (state != State.INSERT) {
            return unchanged();
        }
        String value = LineText.singleRow(raw);
        if (value.isEmpty()) {
            return unchanged();
        }
        String next = text.substring(0, caret) + value + text.substring(caret);
        return Result.of(edit(state, next, caret + value.length(), kill, null));
    }

    private Result eraseBack() {
        if (caret == 0) {
            return unchanged();
        }
        int start = LineText.previous(text, caret);
        String next = text.substring(0, start) + text.substring(caret);
        return Result.of(edit(state, next, start, kill, null));
    }

    private Result eraseForward() {
        if (caret >= text.length()) {
            return unchanged();
        }
        int end = LineText.next(text, caret);
        String next = text.substring(0, caret) + text.substring(end);
        return Result.of(edit(state, next, caret, kill, null));
    }

    private Result move(Motion motion) {
        if (motion == null) {
            return unchanged();
        }
        if (pending != null) {
            return resolve(pending, motion);
        }
        int target = target(motion);
        if (target == caret) {
            return unchanged();
        }
        return Result.of(edit(state, text, target, kill, null));
    }

    private Result remove(Motion motion) {
        if (motion == null) {
            return unchanged();
        }
        int target = target(motion);
        int start = Math.min(caret, target);
        int end = Math.max(caret, target);
        if (start == end) {
            return pending == null ? unchanged() : Result.of(edit(state, text, caret, kill, null));
        }
        return Result.of(cut(start, end, state));
    }

    private Result change(Motion motion) {
        if (motion == null || state != State.NORMAL) {
            return unchanged();
        }
        int target = target(motion);
        int start = Math.min(caret, target);
        int end = Math.max(caret, target);
        return Result.of(cut(start, end, State.INSERT));
    }

    private Result operate(Operator operator) {
        if (operator == null || state != State.NORMAL) {
            return unchanged();
        }
        State next = operator == Operator.CHANGE ? State.INSERT : state;
        if (pending == operator) {
            // The same operator pressed twice takes the whole line: Vi's dd and cc.
            return text.isEmpty()
                    ? Result.of(edit(next, text, caret, kill, null))
                    : Result.of(cut(0, text.length(), next));
        }
        if (pending != null) {
            return unchanged();
        }
        return Result.of(edit(state, text, caret, kill, operator));
    }

    private Result startInsert(Place place) {
        if (place == null || mode != Mode.VI) {
            return unchanged();
        }
        int target = switch (place) {
            case HERE -> caret;
            case AFTER -> LineText.next(text, caret);
            case LINE_START -> 0;
            case LINE_END -> text.length();
        };
        if (state == State.INSERT && target == caret && pending == null) {
            return unchanged();
        }
        return Result.of(edit(State.INSERT, text, target, kill, null));
    }

    private Result normal() {
        if (mode != Mode.VI) {
            return unchanged();
        }
        if (pending != null) {
            return Result.of(edit(state, text, caret, kill, null));
        }
        if (state == State.NORMAL) {
            return unchanged();
        }
        // Vi leaves insert mode on the cluster it inserted last, not after it.
        return Result.of(edit(State.NORMAL, text, LineText.previous(text, caret), kill, null));
    }

    private Result yank() {
        if (kill.isEmpty()) {
            return unchanged();
        }
        String next = text.substring(0, caret) + kill + text.substring(caret);
        return Result.of(edit(state, next, caret + kill.length(), kill, null));
    }

    private Result transpose() {
        if (text.isEmpty() || caret == 0) {
            return unchanged();
        }
        if (caret >= text.length()) {
            int last = LineText.previous(text, text.length());
            int before = LineText.previous(text, last);
            if (last == before) {
                return unchanged();
            }
            String next = text.substring(0, before) + text.substring(last) + text.substring(before, last);
            return Result.of(edit(state, next, next.length(), kill, null));
        }
        int before = LineText.previous(text, caret);
        int after = LineText.next(text, caret);
        String next = text.substring(0, before)
                + text.substring(caret, after)
                + text.substring(before, caret)
                + text.substring(after);
        return Result.of(edit(state, next, after, kill, null));
    }

    private Result endOfInput() {
        if (text.isEmpty()) {
            return new Result(this, List.of(new Output.Eof()));
        }
        return state == State.INSERT ? eraseForward() : unchanged();
    }

    private Result resolve(Operator operator, Motion motion) {
        // Vi's cw changes the word it is in, not the blanks after it, so a change over a forward
        // word motion stops at the word's end where a delete would continue to the next word.
        Motion applied = operator == Operator.CHANGE && motion == Motion.WORD_FORWARD ? Motion.WORD_END : motion;
        int target = target(applied);
        int start = Math.min(caret, target);
        int end = Math.max(caret, target);
        State next = operator == Operator.CHANGE ? State.INSERT : state;
        return Result.of(start == end ? edit(next, text, caret, kill, null) : cut(start, end, next));
    }

    /**
     * Removes one range, keeps it as the kill buffer, and puts the caret at its start.
     */
    private LineEditor cut(int start, int end, State next) {
        String removed = text.substring(start, end);
        String nextText = text.substring(0, start) + text.substring(end);
        return edit(next, nextText, start, removed, null);
    }

    private int target(Motion motion) {
        int length = text.length();
        return switch (motion) {
            case BACK -> LineText.previous(text, caret);
            case FORWARD -> LineText.next(text, caret);
            case WORD_BACK -> LineText.wordBack(text, caret);
            case WORD_FORWARD -> LineText.wordForward(text, caret);
            case WORD_END -> LineText.wordEnd(text, caret);
            case LINE_START -> 0;
            case LINE_END -> length;
        };
    }
}

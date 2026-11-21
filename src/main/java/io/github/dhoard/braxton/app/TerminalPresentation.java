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

package io.github.dhoard.braxton.app;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.TextMeasure;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Immutable terminal presentation intent: the session-wide state a frame asks the terminal for.
 *
 * <p>A frame draws cells, and this value describes everything about the terminal that is not a cell:
 * which screen the cells are drawn on, whether the pointer and paste reports and focus changes are
 * asked for, the window title, the terminal's own default colors, a taskbar progress value and a
 * program-status record. Like every other view value it is data - building one, comparing one and
 * testing one touch no terminal. The root component returns it from {@code presentation(model)}, so
 * the runtime asks for it once per painted frame and writes only the sequences that change what it
 * previously applied.</p>
 *
 * <p>The intent belongs to the root component, which is the only writer of session-wide state: it is
 * read from the model the frame was painted from, so two components cannot declare two different
 * presentations and make the session depend on paint order. A component that composes a child
 * decides what the frame presents, exactly as it decides what the frame contains.</p>
 *
 * <p>Every optional field is genuinely optional and means "the session decides": a frame that names no
 * screen, pointer level, paste mode or focus reporting keeps the {@link io.github.dhoard.braxton.app.TerminalOptions} value the session opened with, so an application that
 * never declares a presentation keeps exactly the behavior it had before this existed. The metadata
 * fields - title, default colors, progress and status - have no session default, so leaving one out
 * means the session presents none: a title is popped, a color default is reset, a progress value is
 * removed and a status record is cleared.</p>
 *
 * <p>The protocol values are the documented ones: {@code OSC 9;4} for progress and {@code OSC 7501}
 * for program status, as specified by the xterm control-sequence reference and the Program Status
 * Protocol. Text that reaches the terminal is bounded and control characters are removed, so a title
 * or a status message can never end the string and inject another command.</p>
 *
 * @param alternateScreen whether the alternate screen is enabled
 * @param background the terminal background color, or null to retain the default
 * @param bracketedPaste whether bracketed paste is enabled
 * @param cursorColor the terminal cursor color, or null to retain the default
 * @param focusReporting whether terminal focus notifications are requested
 * @param foreground the terminal foreground color, or null to retain the default
 * @param mouseMode the requested mouse reporting mode
 * @param progress the terminal taskbar progress state
 * @param status the process status or current application state
 * @param title the terminal window title
 */
public record TerminalPresentation(
        Boolean alternateScreen,
        MouseMode mouseMode,
        Boolean bracketedPaste,
        Boolean focusReporting,
        String title,
        Color foreground,
        Color background,
        Color cursorColor,
        Progress progress,
        Status status) {

    /**
     * The largest title this library writes, in UTF-8 bytes.
     */
    public static final int MAX_TITLE_BYTES = 4096;

    /**
     * No intent at all: every field left to the session.
     */
    public static final TerminalPresentation EMPTY =
            new TerminalPresentation(null, null, null, null, null, null, null, null, null, null);

    /**
     * Removes control characters from the title and bounds it.
     *
     * <p>A control character in a title is not text: an {@code ESC} would end this library's sequence
     * and start whatever the string went on to describe, so every C0, DEL and C1 character is removed
     * rather than escaped and the title is truncated on a cluster boundary to
     * {@value #MAX_TITLE_BYTES} UTF-8 bytes.</p>
     */
    public TerminalPresentation {
        title = blankToNull(bounded(title, MAX_TITLE_BYTES));
    }

    /**
     * A presentation with a title.
     *
     * @param value the window title; null for no title from this session
     * @return the presentation
     */
    public static TerminalPresentation title(String value) {
        return EMPTY.withTitle(value);
    }

    /**
     * The same intent with another alternate-screen request.
     *
     * @param value true to draw on the alternate screen, false to draw on the plain one, or null to
     *     keep the session's own choice
     * @return the presentation
     */
    public TerminalPresentation withAlternateScreen(Boolean value) {
        return new TerminalPresentation(
                value,
                mouseMode,
                bracketedPaste,
                focusReporting,
                title,
                foreground,
                background,
                cursorColor,
                progress,
                status);
    }

    /**
     * The same intent with another pointer reporting level.
     *
     * @param value the level to ask for, or null to keep the session's own choice
     * @return the presentation
     */
    public TerminalPresentation withMouseMode(MouseMode value) {
        return new TerminalPresentation(
                alternateScreen,
                value,
                bracketedPaste,
                focusReporting,
                title,
                foreground,
                background,
                cursorColor,
                progress,
                status);
    }

    /**
     * The same intent with another bracketed-paste request.
     *
     * @param value whether to bracket paste, or null to keep the session's own choice
     * @return the presentation
     */
    public TerminalPresentation withBracketedPaste(Boolean value) {
        return new TerminalPresentation(
                alternateScreen,
                mouseMode,
                value,
                focusReporting,
                title,
                foreground,
                background,
                cursorColor,
                progress,
                status);
    }

    /**
     * The same intent with another terminal-focus-reporting request.
     *
     * @param value whether to report focus changes, or null to keep the session's own choice
     * @return the presentation
     */
    public TerminalPresentation withFocusReporting(Boolean value) {
        return new TerminalPresentation(
                alternateScreen,
                mouseMode,
                bracketedPaste,
                value,
                title,
                foreground,
                background,
                cursorColor,
                progress,
                status);
    }

    /**
     * The same intent with another window title.
     *
     * @param value the title; null or empty for no title from this session
     * @return the presentation
     */
    public TerminalPresentation withTitle(String value) {
        if (value != null && value.isEmpty()) {
            value = null;
        }
        return new TerminalPresentation(
                alternateScreen,
                mouseMode,
                bracketedPaste,
                focusReporting,
                value,
                foreground,
                background,
                cursorColor,
                progress,
                status);
    }

    /**
     * The same intent with another default text color.
     *
     * @param value the color the terminal should use for text, or null for the terminal's own
     * @return the presentation
     */
    public TerminalPresentation withForeground(Color value) {
        return new TerminalPresentation(
                alternateScreen,
                mouseMode,
                bracketedPaste,
                focusReporting,
                title,
                value,
                background,
                cursorColor,
                progress,
                status);
    }

    /**
     * The same intent with another default background color.
     *
     * @param value the color the terminal should use behind text, or null for the terminal's own
     * @return the presentation
     */
    public TerminalPresentation withBackground(Color value) {
        return new TerminalPresentation(
                alternateScreen,
                mouseMode,
                bracketedPaste,
                focusReporting,
                title,
                foreground,
                value,
                cursorColor,
                progress,
                status);
    }

    /**
     * The same intent with another default cursor color.
     *
     * @param value the color the terminal should draw its cursor in, or null for the terminal's own
     * @return the presentation
     */
    public TerminalPresentation withCursorColor(Color value) {
        return new TerminalPresentation(
                alternateScreen,
                mouseMode,
                bracketedPaste,
                focusReporting,
                title,
                foreground,
                background,
                value,
                progress,
                status);
    }

    /**
     * The same intent with another taskbar progress value.
     *
     * @param value the progress, or null for none
     * @return the presentation
     */
    public TerminalPresentation withProgress(Progress value) {
        return new TerminalPresentation(
                alternateScreen,
                mouseMode,
                bracketedPaste,
                focusReporting,
                title,
                foreground,
                background,
                cursorColor,
                value,
                status);
    }

    /**
     * The same intent with another program-status record.
     *
     * @param value the record to report, or null for none
     * @return the presentation
     */
    public TerminalPresentation withStatus(Status value) {
        return new TerminalPresentation(
                alternateScreen,
                mouseMode,
                bracketedPaste,
                focusReporting,
                title,
                foreground,
                background,
                cursorColor,
                progress,
                value);
    }

    /**
     * Text with its control characters removed, bounded to a byte budget on a cluster boundary.
     *
     * <p>Only a window of the source is segmented: the answer depends on the text up to the cluster
     * that straddles the budget, so a value far larger than the budget costs no more than the budget
     * does. The window is measured in UTF-16 units and is four times the byte budget, which no
     * encoding reaches - a unit produces at most three UTF-8 bytes and a surrogate pair produces four
     * for two units - so the cluster that straddles the budget is always inside it.</p>
     */
    private static String bounded(String text, int maxBytes) {
        if (text == null || text.isEmpty()) {
            // Null is "no title", which the caller distinguishes from an empty string.
            return text;
        }
        String value = text;
        long window = 4L * maxBytes;
        if (value.length() > window) {
            value = value.substring(0, TextMeasure.boundaryBefore(value, (int) window));
        }
        StringBuilder bounded = new StringBuilder(Math.min(value.length(), maxBytes));
        int bytes = 0;
        for (String cluster : TextMeasure.graphemes(value)) {
            String printable = withoutControls(cluster);
            if (printable.isEmpty()) {
                continue;
            }
            int size = printable.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            bounded.append(printable);
            bytes += size;
        }
        return bounded.toString();
    }

    /**
     * One cluster without its C0, DEL and C1 characters.
     */
    private static String withoutControls(String cluster) {
        StringBuilder clean = null;
        for (int index = 0; index < cluster.length(); index++) {
            char value = cluster.charAt(index);
            if (Character.isISOControl(value)) {
                if (clean == null) {
                    clean = new StringBuilder(cluster.length());
                    clean.append(cluster, 0, index);
                }
            } else if (clean != null) {
                clean.append(value);
            }
        }
        return clean == null ? cluster : clean.toString();
    }

    /**
     * A taskbar progress value: what a supporting terminal shows beside its window or tab.
     *
     * <p>{@code OSC 9;4} carries a state and, for the states that have one, a percentage.
     * {@link State#NONE} removes the indication, {@link State#INDETERMINATE} shows activity without a
     * fraction, and {@link State#PAUSED} and {@link State#ERROR} mark a run that stopped. A terminal
     * that does not implement the sequence ignores it, so declaring progress costs the frame nothing
     * on a terminal that cannot show it.</p>
     *
     * @param state the state the terminal should show; never null
     * @param value the percentage, 0..100; ignored for {@link State#NONE} and
     *     {@link State#INDETERMINATE}
     */
    public record Progress(State state, int value) {

        /**
         * What a supporting terminal shows for a run.
         */
        public enum State {
            /**
             * No indication at all.
             */
            NONE,
            /**
             * Work in progress with a known fraction.
             */
            NORMAL,
            /**
             * A run that failed.
             */
            ERROR,
            /**
             * Work in progress with no fraction to show.
             */
            INDETERMINATE,
            /**
             * A run the user paused.
             */
            PAUSED
        }

        /**
         * Rejects a missing or out-of-range value and normalizes the states that carry none.
         */
        public Progress {
            if (state == null) {
                throw new IllegalArgumentException("progress state is required");
            }
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException("progress value must be 0..100, was " + value);
            }
            if (state == State.NONE || state == State.INDETERMINATE) {
                value = 0;
            }
        }

        /**
         * No indication.
         *
         * @return the progress value
         */
        public static Progress none() {
            return new Progress(State.NONE, 0);
        }

        /**
         * Work in progress with a known fraction.
         *
         * @param value the percentage, 0..100
         * @return the progress value
         */
        public static Progress normal(int value) {
            return new Progress(State.NORMAL, value);
        }

        /**
         * A run that failed.
         *
         * @param value the percentage it reached, 0..100
         * @return the progress value
         */
        public static Progress error(int value) {
            return new Progress(State.ERROR, value);
        }

        /**
         * Work in progress with no fraction to show.
         *
         * @return the progress value
         */
        public static Progress indeterminate() {
            return new Progress(State.INDETERMINATE, 0);
        }

        /**
         * A run the user paused.
         *
         * @param value the percentage it stopped at, 0..100
         * @return the progress value
         */
        public static Progress paused(int value) {
            return new Progress(State.PAUSED, value);
        }

        /**
         * The same progress with another percentage.
         *
         * @param value the percentage, 0..100
         * @return the progress value
         */
        public Progress withValue(int value) {
            return new Progress(state, value);
        }

        /**
         * The same percentage in another state.
         *
         * <p>The percentage carries over, so a run that pauses at 40% resumes from the value it
         * reported; a state that shows no fraction normalizes it to zero.</p>
         *
         * @param value the state; required
         * @return the progress value
         */
        public Progress withState(State value) {
            return new Progress(value, this.value);
        }
    }

    /**
     * A program-status record: what this program is doing, for a terminal that shows it.
     *
     * <p>{@code OSC 7501} keeps one record per id in the terminal, so a program that reports its own
     * state uses the root record ({@code id} null) and one that reports several uses an id per
     * record. A report replaces the record completely: a key left out is a key the record no longer
     * has, which is why a program that wants its {@code app} or {@code title} to stay includes it in
     * every report.</p>
     *
     * <p>The terminal keeps the lifetime rules, and this library follows them: a {@link State#DONE}
     * or {@link State#ERROR} record is retained when the session ends, so the user finds the result,
     * while {@link State#WORKING}, {@link State#BLOCKED} and {@link State#IDLE} are cleared. The
     * record is the frame's, not the session's, so a frame that stops declaring a status clears the
     * record it declared.</p>
     *
     * @param state what the program is doing; never null
     * @param id the record to address, or null for the terminal's root record
     * @param kind why the program is blocked, or null; ignored unless {@code state} is
     *     {@link State#BLOCKED}
     * @param app the stable program identifier, or null; never a label
     * @param title a short label for the record, or null
     * @param message one human-readable line, or null
     * @param progress the percentage, or null for a program that is busy but has none; ignored
     *     unless {@code state} is {@link State#WORKING} or {@link State#BLOCKED}
     */
    public record Status(
            State state, String id, Kind kind, String app, String title, String message, Integer progress) {

        /**
         * The largest record id, in characters, as the protocol bounds it.
         */
        public static final int MAX_ID_LENGTH = 128;

        /**
         * The most characters one id segment may hold.
         */
        public static final int MAX_ID_SEGMENT = 32;

        /**
         * The deepest id the protocol defines, counted in segments.
         */
        public static final int MAX_ID_DEPTH = 8;

        /**
         * The largest program identifier, in characters.
         */
        public static final int MAX_APP_LENGTH = 32;

        /**
         * The largest record label the protocol accepts, in UTF-8 bytes.
         */
        public static final int MAX_STATUS_TITLE_BYTES = 192;

        /**
         * The largest human-readable message the protocol accepts, in UTF-8 bytes.
         */
        public static final int MAX_MESSAGE_BYTES = 2048;

        /**
         * What a program is doing, as the protocol names it.
         */
        public enum State {
            /**
             * At rest, waiting for the user's next instruction.
             */
            IDLE,
            /**
             * Running; may carry progress.
             */
            WORKING,
            /**
             * Cannot continue until the user does something; {@link Kind} says what.
             */
            BLOCKED,
            /**
             * Finished a piece of work the user has not looked at yet.
             */
            DONE,
            /**
             * Failed and stopped.
             */
            ERROR,
            /**
             * Not a state: removes the addressed record and every record beneath it.
             */
            CLEAR;

            /**
             * The name the protocol writes for this state.
             *
             * @return the lower-case state name
             */
            public String wireName() {
                return name().toLowerCase(Locale.ROOT);
            }
        }

        /**
         * Why a program is waiting for the user.
         */
        public enum Kind {
            /**
             * Approval to do something.
             */
            PERMISSION,
            /**
             * The user must type an answer.
             */
            QUESTION,
            /**
             * A login, token, or other credential.
             */
            AUTH;

            /**
             * The name the protocol writes for this kind.
             *
             * @return the lower-case kind name
             */
            public String wireName() {
                return name().toLowerCase(Locale.ROOT);
            }
        }

        /**
         * Validates the protocol's field grammars and drops the keys the state does not carry.
         */
        public Status {
            if (state == null) {
                throw new IllegalArgumentException("status state is required");
            }
            id = id == null || id.isEmpty() ? null : requireId(id);
            app = app == null || app.isEmpty() ? null : requireApp(app);
            if (state == State.CLEAR) {
                kind = null;
                app = null;
                title = null;
                message = null;
                progress = null;
            } else {
                if (state != State.BLOCKED) {
                    kind = null;
                }
                if (state != State.WORKING && state != State.BLOCKED) {
                    progress = null;
                } else if (progress != null && (progress < 0 || progress > 100)) {
                    throw new IllegalArgumentException("status progress must be 0..100, was " + progress);
                }
            }
            title = blankToNull(bounded(title, MAX_STATUS_TITLE_BYTES));
            message = blankToNull(bounded(message, MAX_MESSAGE_BYTES));
        }

        /**
         * A record in one state, with nothing else.
         *
         * @param value the state; required
         * @return the record
         */
        public static Status of(State value) {
            return new Status(value, null, null, null, null, null, null);
        }

        /**
         * Work in progress, with a message.
         *
         * @param app the program identifier, or null
         * @param message the human-readable line, or null
         * @return the record
         */
        public static Status working(String app, String message) {
            return new Status(State.WORKING, null, null, app, null, message, null);
        }

        /**
         * Waiting for the user, with a reason and a message.
         *
         * @param kind why the program is waiting; required
         * @param app the program identifier, or null
         * @param message the human-readable line, or null
         * @return the record
         */
        public static Status blocked(Kind kind, String app, String message) {
            if (kind == null) {
                throw new IllegalArgumentException("a blocked record needs a kind");
            }
            return new Status(State.BLOCKED, null, kind, app, null, message, null);
        }

        /**
         * The same record with another state.
         *
         * @param value the state; required
         * @return the record
         */
        public Status withState(State value) {
            return new Status(value, id, kind, app, title, message, progress);
        }

        /**
         * The same record addressed to another id.
         *
         * @param value the record id, or null for the terminal's root record
         * @return the record
         */
        public Status withId(String value) {
            return new Status(state, value, kind, app, title, message, progress);
        }

        /**
         * The same record with another program identifier.
         *
         * @param value the identifier, or null
         * @return the record
         */
        public Status withApp(String value) {
            return new Status(state, id, kind, value, title, message, progress);
        }

        /**
         * The same record with another label.
         *
         * @param value the label, or null
         * @return the record
         */
        public Status withTitle(String value) {
            return new Status(state, id, kind, app, value, message, progress);
        }

        /**
         * The same record with another message.
         *
         * @param value the message, or null
         * @return the record
         */
        public Status withMessage(String value) {
            return new Status(state, id, kind, app, title, value, progress);
        }

        /**
         * The same record with another progress value.
         *
         * @param value the percentage 0..100, or null for none
         * @return the record
         */
        public Status withProgress(Integer value) {
            return new Status(state, id, kind, app, title, message, value);
        }

        /**
         * Whether this record survives the end of the session.
         *
         * <p>The protocol keeps a finished result and drops a run in progress, so a terminal that
         * implements it shows what the user has to look at and nothing stale.</p>
         *
         * @return true for {@link State#DONE} and {@link State#ERROR}
         */
        public boolean retained() {
            return state == State.DONE || state == State.ERROR;
        }
    }

    /**
     * The record id, as the protocol's {@code segment("/" segment)*} grammar.
     *
     * @param value the id to check
     * @return the id
     * @throws IllegalArgumentException when the id is not in the documented grammar
     */
    private static String requireId(String value) {
        if (value.length() > Status.MAX_ID_LENGTH) {
            throw new IllegalArgumentException("status id must be at most " + Status.MAX_ID_LENGTH + " characters");
        }
        String[] segments = value.split("/", -1);
        if (segments.length > Status.MAX_ID_DEPTH) {
            throw new IllegalArgumentException("status id must have at most " + Status.MAX_ID_DEPTH + " segments");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.length() > Status.MAX_ID_SEGMENT || !identifier(segment)) {
                throw new IllegalArgumentException("status id segment must be 1.." + Status.MAX_ID_SEGMENT
                        + " of [A-Za-z0-9_.+-], was " + segment);
            }
        }
        return value;
    }

    /**
     * The program identifier, as the protocol's {@code [A-Za-z0-9_.+-]{1,32}} grammar.
     *
     * @param value the identifier to check
     * @return the identifier
     * @throws IllegalArgumentException when the identifier is not in the documented grammar
     */
    private static String requireApp(String value) {
        if (value.length() > Status.MAX_APP_LENGTH || !identifier(value)) {
            throw new IllegalArgumentException(
                    "status app must be 1.." + Status.MAX_APP_LENGTH + " of [A-Za-z0-9_.+-], was " + value);
        }
        return value;
    }

    /**
     * Whether every character of {@code value} is one the protocol's identifier set allows.
     */
    private static boolean identifier(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '.'
                    || character == '+'
                    || character == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /**
     * An empty text field, which the protocol expresses by leaving the key out.
     */
    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}

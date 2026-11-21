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

import io.github.dhoard.braxton.clipboard.ClipboardPath;
import io.github.dhoard.braxton.clipboard.ClipboardSelection;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Colors;
import java.util.Locale;
import java.util.Set;

/**
 * One bounded question to the attached terminal, and its typed answer.
 *
 * <p>A query is a value, not an effect: nothing is written when it is built, and the answer arrives
 * as a message from {@link io.github.dhoard.braxton.effect.Command#query}. Every query is bounded - a
 * one-second reply deadline, at most sixteen outstanding queries, and at most one request per kind on
 * the wire at a time - so a terminal that never answers costs a session a {@link Reply.Timeout} and
 * never a stall. A terminal reply carries no request identity, which is why same-kind requests are
 * serialized and why a reply nobody is waiting for is discarded instead of being guessed at.</p>
 *
 * <p>Only the queries defined here are ever sent. Nothing is asked at startup: an application that
 * wants terminal facts asks for them, and a terminal that does not implement a query answers
 * {@link Reply.Unsupported} or not at all.</p>
 *
 * <p>Specification sources: the
 * <a href="https://invisible-island.net/xterm/ctlseqs/ctlseqs.html">xterm control sequences</a>
 * reference, consulted 2026-11-16, for DSR, DECRQM, XTVERSION, XTGETTCAP and the OSC color
 * commands, and the X11 {@code XParseColor} specification for the {@code rgb:} form a terminal
 * answers a color query with.</p>
 */
public sealed interface TerminalQuery {

    /**
     * The stable identity of the reply this query asks for.
     *
     * <p>Two queries with the same key are indistinguishable on the wire: a terminal's answer names
     * the mode or capability it is about only when the query itself did, and the two forms that carry
     * no identity at all - the cursor position and the terminal version - can only be matched by
     * being the only one outstanding. Same-key requests are therefore serialized.</p>
     *
     * @return the key, never empty
     */
    String kind();

    /**
     * Where the cursor is, as the terminal reports it.
     *
     * <p>DSR {@code CSI 6 n} answers {@code CSI row ; column R}. The reported cell is the terminal's,
     * which is the last cell a frame wrote rather than the application's caret: a session that wants
     * to restore the cursor asks immediately after a frame and gets that frame's resting cell.</p>
     *
     * @see Reply.CursorPosition
     */
    record CursorPosition() implements TerminalQuery {
        @Override
        public String kind() {
            return "cursor-position";
        }
    }

    /**
     * Which terminal is attached, by name and version.
     *
     * <p>XTVERSION {@code CSI > 0 q} answers with a device control string
     * {@code DCS > | name version ST}, for example {@code tmux 3.4} or {@code xterm(390)}. A terminal
     * that does not implement it either answers nothing or reports its own name for the generic
     * case.</p>
     *
     * @see Reply.Identity
     */
    record Identity() implements TerminalQuery {
        @Override
        public String kind() {
            return "identity";
        }
    }

    /**
     * Whether a terminal mode is set, reset, or not implemented.
     *
     * <p>DECRQM {@code CSI ? mode $ p} answers {@code CSI ? mode ; value $ y}. The report is the only
     * honest way to learn that a terminal accepted a mode - synchronized output, bracketed paste,
     * alternative screen - instead of ignoring it.</p>
     *
     * @param mode the DEC private mode number
     * @see Reply.Mode
     * @see <a href="https://vt100.net/docs/vt510-rm/DECRQM.html">DECRQM</a>
     */
    record Mode(int mode) implements TerminalQuery {

        /**
         * Rejects a mode number outside the range a DEC private mode can have.
         */
        public Mode {
            if (mode < 1 || mode > 65_535) {
                throw new IllegalArgumentException("mode must be 1..65535, was " + mode);
            }
        }

        @Override
        public String kind() {
            return "mode:" + mode;
        }
    }

    /**
     * A termcap/terminfo capability, as the terminal itself describes it.
     *
     * <p>XTGETTCAP {@code DCS + q hex-name ST} answers {@code DCS 1 + r hex-name=hex-value ST} for a
     * capability the terminal has and {@code DCS 0 + r hex-name ST} for one it does not. Only the
     * capabilities this library documents are requestable, so a reply can never be interpreted for a
     * name whose meaning was not decided here.</p>
     *
     * @param name the capability name, in its canonical spelling
     * @see Reply.Capability
     */
    record Termcap(String name) implements TerminalQuery {

        /**
         * The capabilities a session may ask about.
         *
         * <p>{@code RGB} and {@code Tc} both mean "this terminal displays 24-bit color", and either
         * one upgrades a narrower color profile. {@code TN} names the terminal, {@code Co} reports
         * how many colors it has, and {@code Ms} reports whether it writes the system clipboard.</p>
         */
        public static final Set<String> ALLOWED = Set.of("RGB", "Tc", "TN", "Co", "Ms");

        /**
         * Rejects a capability this library does not document.
         */
        public Termcap {
            String canonical = name == null ? "" : name.strip();
            if (!ALLOWED.contains(canonical)) {
                throw new IllegalArgumentException("capability must be one of " + ALLOWED + ", was " + name);
            }
            name = canonical;
        }

        @Override
        public String kind() {
            return "termcap:" + name;
        }
    }

    /**
     * A color the terminal currently uses.
     *
     * <p>OSC 10, 11 and 12 answer with an X11 color specification, usually
     * {@code rgb:rrrr/gggg/bbbb}; each 1- to 4-digit channel is scaled to 0..255. A terminal that does
     * not implement the query answers {@code ?}, which is reported as
     * {@link Reply.Unsupported} rather than as a color.</p>
     *
     * @param target which color to ask about
     * @see Reply.ColorValue
     */
    record ColorReport(Target target) implements TerminalQuery {

        /**
         * Rejects a missing target.
         */
        public ColorReport {
            if (target == null) {
                throw new IllegalArgumentException("color target is required");
            }
        }

        @Override
        public String kind() {
            return "color:" + target.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The color profile the session is presenting with.
     *
     * <p>A session fact rather than a terminal question: the answer comes from the runtime with no
     * escape sequence written, so it is returned immediately and never times out. It is how an
     * application learns the profile that was negotiated for it, or that a capability reply upgraded
     * it.</p>
     *
     * @see Reply.Profile
     */
    record Profile() implements TerminalQuery {
        @Override
        public String kind() {
            return "profile";
        }
    }

    /**
     * The text the terminal holds for one clipboard selection.
     *
     * <p>OSC 52 {@code ESC ] 52 ; <selection> ; ? BEL} asks, and the terminal answers with the same
     * command carrying a base64 payload. This is the one query whose reply is the user's own data
     * rather than a fact about the terminal, so its payload is decoded strictly and bounded before it
     * can become a message.</p>
     *
     * <p>Two selections are independent on the wire - a reply names the selection it answers for - so
     * a system read and a primary read may be outstanding together and are still attributed
     * exactly. Two reads of the <em>same</em> selection are serialized like any other same-kind
     * query.</p>
     *
     * @param selection the selection to read; required
     * @param path the transport the request must use; {@link ClipboardPath#OSC52_DIRECT} when omitted
     * @see Reply.Clipboard
     * @see Reply.Oversize
     */
    record ClipboardRead(ClipboardSelection selection, ClipboardPath path) implements TerminalQuery {

        /**
         * Rejects a missing selection.
         */
        public ClipboardRead {
            if (selection == null) {
                throw new IllegalArgumentException("selection is required");
            }
            path = path == null ? ClipboardPath.OSC52_DIRECT : path;
        }

        /**
         * A read of {@code selection} written straight to the terminal.
         *
         * @param selection the selection to read
         */
        public ClipboardRead(ClipboardSelection selection) {
            this(selection, ClipboardPath.OSC52_DIRECT);
        }

        @Override
        public String kind() {
            // The selection is the reply's own identity, so it is what has to be serialized.
            return "clipboard:" + selection.code();
        }
    }

    /**
     * Whether the terminal keeps program-status records.
     *
     * <p>OSC 7501 feature detection: {@code OSC 7501 ; ? ST} is answered by
     * {@code OSC 7501 ; ? [:states=...][:kinds=...] ST}. The lists are what the terminal says it
     * accepts; a reply that leaves one out means exactly the protocol's fixed default set, and a
     * terminal that implements nothing ignores the question and is reported as a timeout. Sending a
     * status report without asking first is allowed by the protocol, so this is the honest way to
     * learn whether the terminal will show one.</p>
     *
     * @see Reply.ProgramStatus
     * @see <a href="https://www.superlogical.com/rex/docs/build/program-status">Program Status
     *     Protocol</a>
     */
    record ProgramStatus() implements TerminalQuery {
        @Override
        public String kind() {
            return "program-status";
        }
    }

    /**
     * Which of the terminal's three configurable colors a query asks about.
     */
    enum Target {

        /**
         * The default text color, OSC 10.
         */
        FOREGROUND,

        /**
         * The window background, OSC 11. The usual source of a dark/light theme decision.
         */
        BACKGROUND,

        /**
         * The terminal's cursor color, OSC 12.
         */
        CURSOR
    }

    /**
     * What a terminal said about one mode.
     *
     * <p>The four DECRPM values, plus the answer that is not a value: mode 0 means the terminal does
     * not recognize the mode at all.</p>
     */
    enum Setting {

        /**
         * The mode is set.
         */
        SET,

        /**
         * The mode is reset.
         */
        RESET,

        /**
         * The mode is set and cannot be reset.
         */
        PERMANENTLY_SET,

        /**
         * The mode is reset and cannot be set.
         */
        PERMANENTLY_RESET,

        /**
         * The terminal does not implement the mode.
         */
        NOT_RECOGNIZED;

        /**
         * The setting a DECRPM value names.
         *
         * @param value the reported value, 0..4
         * @return the setting
         * @throws IllegalArgumentException for a value outside the documented range
         */
        public static Setting of(int value) {
            return switch (value) {
                case 1 -> SET;
                case 2 -> RESET;
                case 3 -> PERMANENTLY_SET;
                case 4 -> PERMANENTLY_RESET;
                case 0 -> NOT_RECOGNIZED;
                default -> throw new IllegalArgumentException("no mode setting for value " + value);
            };
        }

        /**
         * Whether the mode is in force, whether or not it can be changed.
         *
         * @return true for {@link #SET} and {@link #PERMANENTLY_SET}
         */
        public boolean enabled() {
            return this == SET || this == PERMANENTLY_SET;
        }
    }

    /**
     * A terminal's answer to one query.
     *
     * <p>The five success forms carry the decoded fact; the four failure forms carry the query that
     * failed, so one mapper can serve several queries. A failure is information, not an error: an
     * application that needs a fact it could not get decides what to render instead, and the demo's
     * status rows show the difference between "not supported" and "no answer".</p>
     */
    sealed interface Reply {

        /**
         * The cursor position, zero-based.
         *
         * @param row the reported row, counted from zero
         * @param column the reported column, counted from zero
         */
        record CursorPosition(int row, int column) implements Reply {

            /**
             * Rejects a negative cell.
             */
            public CursorPosition {
                if (row < 0 || column < 0) {
                    throw new IllegalArgumentException("cursor cell must not be negative");
                }
            }
        }

        /**
         * The terminal's name and version, as it describes itself.
         *
         * @param name the reported text, for example {@code xterm(390)}
         */
        record Identity(String name) implements Reply {

            /**
             * Treats a missing name as empty.
             */
            public Identity {
                name = name == null ? "" : name;
            }
        }

        /**
         * What the terminal said about one mode.
         *
         * @param mode the mode that was asked about
         * @param setting the reported setting
         */
        record Mode(int mode, Setting setting) implements Reply {

            /**
             * Rejects a missing setting.
             */
            public Mode {
                if (setting == null) {
                    throw new IllegalArgumentException("setting is required");
                }
            }
        }

        /**
         * A capability the terminal reports.
         *
         * @param name the capability's canonical name
         * @param value the value it reported, in its own encoding
         */
        record Capability(String name, String value) implements Reply {

            /**
             * Treats a missing value as empty.
             */
            public Capability {
                name = name == null ? "" : name;
                value = value == null ? "" : value;
            }
        }

        /**
         * A color the terminal reports.
         *
         * @param target the color that was asked about
         * @param color the normalized color
         */
        record ColorValue(Target target, Color color) implements Reply {

            /**
             * Rejects a missing target or color.
             */
            public ColorValue {
                if (target == null) {
                    throw new IllegalArgumentException("color target is required");
                }
                if (color == null) {
                    throw new IllegalArgumentException("color is required");
                }
            }

            /**
             * Whether this color reads as a dark background.
             *
             * <p>The library's one classification rule, so a theme decision here and a theme decision
             * anywhere else cannot disagree.</p>
             *
             * @return true when {@link Colors#isDark(Color)} says so
             */
            public boolean dark() {
                return Colors.isDark(color);
            }
        }

        /**
         * The text the terminal holds for one clipboard selection.
         *
         * @param selection the selection the terminal answered for
         * @param text the decoded text, empty when the selection holds none
         * @param byteLength the UTF-8 length of {@code text}
         */
        record Clipboard(ClipboardSelection selection, String text, int byteLength) implements Reply {

            /**
             * Rejects a missing selection and normalizes the reported values.
             */
            public Clipboard {
                if (selection == null) {
                    throw new IllegalArgumentException("selection is required");
                }
                text = text == null ? "" : text;
                byteLength = Math.max(0, byteLength);
            }
        }

        /**
         * What a terminal's program-status feature detection said it accepts.
         *
         * <p>The protocol fixes the answer's shape: a terminal that keeps records answers
         * {@code OSC 7501 ; ?}, and may name the states and kinds it accepts. A list it leaves out is
         * exactly {@link #DEFAULT_STATES} or {@link #DEFAULT_KINDS}, which are the sets every revision
         * of the protocol has had. Names a later revision adds are passed through as they arrived, so
         * an application can render what the terminal supports without this library having to learn
         * the name first.</p>
         *
         * @param states the state names the terminal accepts
         * @param kinds the blocked-kind names the terminal accepts
         */
        record ProgramStatus(Set<String> states, Set<String> kinds) implements Reply {

            /**
             * The states a terminal supports when its reply does not list them.
             */
            public static final Set<String> DEFAULT_STATES = Set.of("idle", "working", "done", "blocked", "error");

            /**
             * The kinds a terminal supports when its reply does not list them.
             */
            public static final Set<String> DEFAULT_KINDS = Set.of("permission", "question", "auth");

            /**
             * Rejects missing lists and makes them immutable.
             */
            public ProgramStatus {
                states = states == null ? DEFAULT_STATES : Set.copyOf(states);
                kinds = kinds == null ? DEFAULT_KINDS : Set.copyOf(kinds);
            }

            /**
             * Whether the terminal accepts one state.
             *
             * @param state the state name, as the protocol spells it
             * @return true when the answer's state list holds it
             */
            public boolean supportsState(String state) {
                return state != null && states.contains(state);
            }

            /**
             * Whether the terminal accepts one blocked kind.
             *
             * @param kind the kind name, as the protocol spells it
             * @return true when the answer's kind list holds it
             */
            public boolean supportsKind(String kind) {
                return kind != null && kinds.contains(kind);
            }
        }

        /**
         * The terminal answered with more data than the session accepts.
         *
         * <p>The bound is the session's, not the terminal's: a clipboard read refuses a payload larger
         * than {@link io.github.dhoard.braxton.clipboard.ClipboardConfig#maxReadPayloadBytes()} rather
         * than handing the application a truncated value. The framing buffer that receives the reply
         * is bounded by the same limit, so a payload beyond it cannot be delivered as text either.</p>
         *
         * @param query the query whose answer was too large
         * @param limit the bound the answer exceeded
         */
        record Oversize(TerminalQuery query, int limit) implements Reply {

            /**
             * Rejects a missing query and a negative limit.
             */
            public Oversize {
                if (query == null) {
                    throw new IllegalArgumentException("query is required");
                }
                limit = Math.max(0, limit);
            }
        }

        /**
         * The color profile the session is presenting with.
         *
         * @param profile the resolved profile, never {@link ColorProfile#AUTO}
         * @param explicit whether the session was configured with this profile rather than
         *     negotiating it
         */
        record Profile(ColorProfile profile, boolean explicit) implements Reply {
            /**
             * Rejects the unresolved profile.
             */
            public Profile {
                if (profile == null || profile == ColorProfile.AUTO) {
                    throw new IllegalArgumentException("a resolved color profile is required");
                }
            }
        }

        /**
         * The terminal does not implement the query.
         *
         * @param query the query that was refused
         */
        record Unsupported(TerminalQuery query) implements Reply {

            /**
             * Rejects a missing query.
             */
            public Unsupported {
                if (query == null) {
                    throw new IllegalArgumentException("query is required");
                }
            }
        }

        /**
         * The terminal did not answer within the reply deadline.
         *
         * <p>A terminal that ignores a query and one that is slow look the same from here, and the
         * session reports what it knows: no answer arrived.</p>
         *
         * @param query the query that was not answered
         */
        record Timeout(TerminalQuery query) implements Reply {

            /**
             * Rejects a missing query.
             */
            public Timeout {
                if (query == null) {
                    throw new IllegalArgumentException("query is required");
                }
            }
        }

        /**
         * The terminal answered, but the answer could not be believed.
         *
         * @param query the query that was answered
         * @param detail what was wrong with the answer
         */
        record Malformed(TerminalQuery query, String detail) implements Reply {

            /**
             * Rejects a missing query.
             */
            public Malformed {
                if (query == null) {
                    throw new IllegalArgumentException("query is required");
                }
                detail = detail == null ? "" : detail;
            }
        }

        /**
         * The runtime declined to ask: too many queries are already outstanding.
         *
         * <p>The bound is what keeps a component that asks in a loop from writing to the terminal
         * without limit. Nothing was sent, so nothing can answer later.</p>
         *
         * @param query the query that was not sent
         * @param outstanding how many queries were already waiting for a reply
         */
        record Rejected(TerminalQuery query, int outstanding) implements Reply {

            /**
             * Rejects a missing query and an empty bound.
             */
            public Rejected {
                if (query == null) {
                    throw new IllegalArgumentException("query is required");
                }
                if (outstanding < 1) {
                    throw new IllegalArgumentException("outstanding must be positive, was " + outstanding);
                }
            }
        }
    }
}

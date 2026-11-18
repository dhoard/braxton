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

package io.github.dhoard.braxton.internal.input;

import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.InputOptions;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.input.KeyboardEnhancements;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.input.TerminalReply;
import io.github.dhoard.braxton.internal.clipboard.Osc52;
import io.github.dhoard.braxton.internal.terminal.TimedReader;
import java.io.IOException;
import java.util.Optional;

/**
 * Decodes timed terminal characters into normalized {@link InputEvent} values.
 *
 * <p>The decoder owns no application state and dispatches nothing: it reads one event at a time so
 * the runtime can route it against the latest committed view. Escape sequences are bounded by a
 * short timeout, CSI sequences by a byte cap, and scalar assembly preserves supplementary characters across split timed reads.
 * An ESC byte inside an in-progress sequence cancels that sequence and starts the next keypress, so
 * a sequence a terminal leaves open never contributes a keystroke of its own. The timed reader owns UTF-8
 * decoding and Braxton owns event normalization.</p>
 *
 * <p>Legacy input is decoded exactly as before. In addition, the decoder understands the reports an
 * enhanced-keyboard terminal sends once an application has asked for them: the Kitty keyboard
 * protocol's {@code CSI ... u} key form with its modifiers, event type and associated text, the same
 * modifiers and event type on the legacy functional-key forms, the terminal's answer to the flag
 * query, and the focus-in/focus-out notifications of xterm focus reporting. Every numeric field is
 * validated and the sequence length is already bounded, so a malformed or truncated report decodes
 * to no event rather than to an invented keystroke.</p>
 *
 * <p>Replies to terminal queries are framed here and interpreted by the runtime, because the same
 * bytes can mean different things: {@code CSI 1;2 R} is both the shape of one modified function key
 * and the shape of a cursor position report. A reply is emitted as a
 * {@link InputEvent.TerminalReplyInput} and consumed by whichever query is waiting for it.</p>
 */
public final class InputDecoder {

    private final ScalarInput in;
    private final long escapeTimeoutNanos;
    private final int maxCsiCharacters;
    private final int maxPasteBytes;
    private final long pasteIdleTimeoutMillis;
    private final int maxClipboardReplyCharacters;
    private long escapeDeadline;

    public InputDecoder(TimedReader in) {
        this(in, InputOptions.DEFAULT);
    }

    public InputDecoder(TimedReader in, InputOptions options) {
        this(in, options, () -> false);
    }

    public InputDecoder(TimedReader in, InputOptions options, java.util.function.BooleanSupplier cancelled) {
        this(in, options, cancelled, MAX_CLIPBOARD_REPLY_CHARACTERS);
    }

    /**
     * Creates a decoder with an explicit OSC 52 framing bound.
     *
     * <p>The clipboard bound is separate from {@link #MAX_REPLY_CHARACTERS} because a clipboard reply
     * carries the user's own text, which is longer than any fact about the terminal. The runtime
     * passes the bound derived from the session's read limit, so a reply that could decode within
     * that limit is never dropped before it can be checked.</p>
     *
     * @param in the timed reader
     * @param options the input limits
     * @param cancelled whether the session is shutting down
     * @param maxClipboardReplyCharacters the largest OSC 52 reply data kept, in characters
     */
    public InputDecoder(
            TimedReader in,
            InputOptions options,
            java.util.function.BooleanSupplier cancelled,
            int maxClipboardReplyCharacters) {
        this.in = new ScalarInput(in, Math.max(1, options.cancellationInterval().toMillis()), cancelled);
        this.escapeTimeoutNanos = options.escapeTimeout().toNanos();
        this.maxCsiCharacters = options.maxCsiCharacters();
        this.maxPasteBytes = options.maxPasteBytes();
        this.pasteIdleTimeoutMillis = Math.max(1, options.pasteIdleTimeout().toMillis());
        this.maxClipboardReplyCharacters = Math.max(1, maxClipboardReplyCharacters);
    }

    /**
     * Reads one event.
     *
     * <p>Sequences and scalars that map to nothing (a malformed report, unknown escapes, a control
     * character outside ASCII) are skipped inside this call: returning empty is reserved for real
     * end-of-input, so an ignored report can never be mistaken for the end of the session.</p>
     *
     * @return the event, or empty at end-of-input or when the session closed the stream
     */
    public Optional<InputEvent> read() throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) {
                return Optional.empty();
            }
            escapeDeadline = System.nanoTime() + escapeTimeoutNanos;
            InputEvent event = first == 0x1B ? readEscape().orElse(null) : decodeScalar(first);
            if (event != null) {
                return Optional.of(event);
            }
        }
    }

    private InputEvent decodeScalar(int value) throws IOException {
        if (value < 0x20 || value == 0x7F) {
            return InputEvent.KeyInput.of(keyForControlByte(value));
        }
        if (value < 0x80) {
            char c = (char) value;
            return InputEvent.KeyInput.of(keyForText(String.valueOf(c)), String.valueOf(c));
        }
        String text = new String(Character.toChars(value));
        // A C1 control (U+0080-U+009F) arrives as a two-byte UTF-8 scalar, so it reaches here as
        // printable-looking text. It is no more printable than a C0 control: it names no gesture
        // and
        // must not be carried as text, exactly as the Alt-modified path already decides for a C1
        // scalar. A key named after a control character is rejected outright, which would end the
        // run.
        if (text.isEmpty() || !isPrintable(text)) {
            return null;
        }
        return InputEvent.KeyInput.of(keyForText(text), text);
    }

    /**
     * The key a control byte names on its own: every C0 control and DEL names a gesture here.
     * ESC plus one of them is that gesture with ALT (see {@link #readEscape()}), so the pair and the
     * bare byte agree on the key and only differ by the modifier. A control byte is never used as a
     * key name or text, because {@link Key} rejects control characters in a name.
     *
     * @param value a C0 control byte or DEL
     */
    private static Key keyForControlByte(int value) {
        if (value == 0x0D) {
            return Key.ENTER;
        }
        if (value == 0x0A) {
            return Key.of("j", Modifiers.CTRL);
        }
        if (value == 0x09) {
            return Key.TAB;
        }
        if (value == 0x7F || value == 0x08) {
            return Key.BACKSPACE;
        }
        if (value == 0x00) {
            return Key.of("space", Modifiers.CTRL);
        }
        if (value == 0x1C) {
            return Key.of("\\", Modifiers.CTRL);
        }
        if (value == 0x1D) {
            return Key.of("]", Modifiers.CTRL);
        }
        if (value == 0x1E) {
            return Key.of("^", Modifiers.CTRL);
        }
        if (value == 0x1F) {
            return Key.of("_", Modifiers.CTRL);
        }
        // Remaining C0 controls are Ctrl+letter.
        char letter = (char) ('a' + value - 1);
        return Key.of(String.valueOf(letter), Modifiers.CTRL);
    }

    private Optional<InputEvent> readEscape() throws IOException {
        int second = readWithTimeout();
        if (second < 0) {
            return Optional.of(InputEvent.KeyInput.of(Key.ESCAPE));
        }
        if (second == '[') {
            String csi = readCsiSequence();
            return Optional.ofNullable(decodeCsi(csi));
        }
        if (second == 'O') {
            int third = readWithTimeout();
            if (third < 0) {
                return Optional.empty();
            }
            if (third == 0x1B) {
                // The next keypress starts here, exactly as inside a CSI body: the half-built SS3
                // introducer is abandoned and the escape byte is given back, so the sequence that
                // follows keeps its own key instead of having its bytes read as ordinary text.
                in.unread(third);
                return Optional.empty();
            }
            return Optional.ofNullable(decodeSs3((char) third));
        }
        if (second == 0x1B) {
            // A second escape starts the next keypress rather than modifying this one: two Escape
            // presses are two gestures, which the "Escape Escape" cancel relies on. Give the byte
            // back so the following read() reports it.
            in.unread(second);
            return Optional.of(InputEvent.KeyInput.of(Key.ESCAPE));
        }
        if (second == ']') {
            return readOperatingSystem();
        }
        if (second == 'P') {
            return readDeviceControl();
        }
        if (second < 0x20 || second == 0x7F) {
            // ESC plus a control byte is how a terminal reports the Alt-modified key: ESC DEL is
            // Alt+Backspace, and an xterm-family terminal with metaSendsEscape reports Alt+Enter
            // and
            // Alt+Tab as ESC CR and ESC TAB. Every control byte names a gesture of its own, so the
            // pair keeps that gesture and adds ALT to the modifiers the byte already names - ESC
            // ETX
            // is Alt plus the Ctrl+C gesture, and stays distinct from the ESC c pair that reports a
            // plain Alt+C. Consuming the pair as a bare Escape instead loses the keystroke the user
            // pressed and delivers one they never pressed, and Escape and Ctrl+C are two-press
            // gestures in real applications.
            Key control = keyForControlByte(second);
            return Optional.of(
                    InputEvent.KeyInput.of(control.with(control.modifiers().withAlt(true))));
        }
        if (second >= 0x80) {
            // Alt plus a printable character outside ASCII: the following bytes belong to the same
            // UTF-8 scalar, so decode them together instead of naming the lead byte's Latin-1
            // character and leaving a stray continuation byte behind.
            String text = new String(Character.toChars(second));
            // A C1 control (encoded as a multibyte scalar) is not printable either, and unlike a C0
            // control it names no key of its own, so ALT plus one is reported as the escape it
            // started with.
            if (text.isEmpty() || !isPrintable(text)) {
                return Optional.of(InputEvent.KeyInput.of(Key.ESCAPE));
            }
            return Optional.of(InputEvent.KeyInput.of(keyForText(text).with(Modifiers.ALT), text));
        }
        char c = (char) second;
        return Optional.of(
                InputEvent.KeyInput.of(keyForText(String.valueOf(c)).with(Modifiers.ALT), String.valueOf(c)));
    }

    /** Whether every code point of {@code text} is printable, so it can be a key name and text. */
    private static boolean isPrintable(String text) {
        return text.codePoints().noneMatch(code -> code < 0x20 || code == 0x7F || (code >= 0x80 && code <= 0x9F));
    }

    private String readCsiSequence() throws IOException {
        StringBuilder sequence = new StringBuilder();
        long deadline = escapeDeadline;
        boolean overflow = false;
        while (true) {
            int next = readWithDeadline(deadline);
            // End of input, or the escape timeout: the partial sequence is abandoned rather than
            // waited for. Waiting for a final byte cannot be unbounded, and the byte that arrives
            // after the timeout is just as likely to be the next key the user pressed - Meta+[ on
            // its own followed by a typed letter is exactly these bytes - so the keystroke that
            // follows the gap keeps its own meaning.
            if (next < 0) return "";
            if (next == 0x1B) {
                // An escape byte cancels the sequence in progress and starts the next keypress, the
                // same way a second ESC ends the first gesture above. A terminal reports Meta+[ as
                // "ESC [", so the next keystroke - and every key repeat while Meta+[ is held -
                // arrives while this CSI is still open. Appending the byte would end the sequence at
                // the next sequence's '[', and that sequence's parameters and final byte would then
                // be decoded as ordinary text: the key the user pressed is lost and a keystroke they
                // never pressed is inserted, which for a bracketed paste also destroys its marker.
                // Give the escape byte back so the following read() decodes the real sequence.
                in.unread(next);
                return "";
            }
            // Past the cap the sequence is not stored, but its bytes are still consumed up to the
            // final byte: stopping there would deliver the tail (parameters and final byte) to the
            // application as ordinary text.
            if (sequence.length() < maxCsiCharacters) {
                sequence.append((char) next);
            } else {
                overflow = true;
            }
            if (next >= 0x40 && next <= 0x7E) {
                break;
            }
        }
        return overflow ? "" : sequence.toString();
    }

    private InputEvent decodeCsi(String sequence) throws IOException {
        if (sequence.isEmpty()) {
            return null;
        }
        if (PasteDecoder.START_BODY.equals(sequence)) {
            PasteDecoder.Result paste = PasteDecoder.read(in, maxPasteBytes, pasteIdleTimeoutMillis);
            return new InputEvent.PasteInput(paste.text());
        }
        if (PasteDecoder.END_BODY.equals(sequence)) {
            return null;
        }
        InputEvent reply = decodeCsiReply(sequence);
        if (reply != null) {
            return reply;
        }
        char lead = sequence.charAt(0);
        if (lead == '?') {
            return decodeEnhancementReport(sequence);
        }
        if (lead == '<') {
            return decodeSgrMouse(sequence);
        }
        InputEvent exact = decodeExactCsi(sequence);
        // The exact forms carry no parameters, which is what a legacy terminal sends. Anything else
        // is a parameterised form: the same keys with modifiers and an optional event type.
        return exact == null ? decodeParameterizedCsi(sequence) : exact;
    }

    /**
     * The two control-sequence replies a terminal can send: a mode report and a cursor position.
     *
     * <p>Both are framed, not interpreted. {@code CSI 1;2 R} is also the shape of a modified
     * function key, so deciding what the bytes mean here would decide it without knowing what the
     * session asked; the runtime answers that, and discards a reply nobody is waiting for.</p>
     */
    private static InputEvent decodeCsiReply(String sequence) {
        int length = sequence.length();
        char last = sequence.charAt(length - 1);
        if (last == 'y') {
            // DECRPM: CSI ? Ps ; Pm $ y, the answer to CSI ? Ps $ p.
            if (length >= 5 && sequence.charAt(0) == '?' && sequence.charAt(length - 2) == '$') {
                return reply(new TerminalReply.Control(sequence.substring(0, length - 1), 'y'));
            }
            return null;
        }
        if (last == 'R') {
            // DSR: CSI Pl ; Pc R, the answer to CSI 6 n. Two plain decimal fields, one-based.
            int separator = sequence.indexOf(';');
            if (separator > 0 && separator == sequence.lastIndexOf(';') && separator < length - 2) {
                String row = sequence.substring(0, separator);
                String column = sequence.substring(separator + 1, length - 1);
                if (isDecimal(row) && isDecimal(column)) {
                    return reply(new TerminalReply.Control(row + ";" + column, 'R'));
                }
            }
            return null;
        }
        return null;
    }

    /** Whether a field is a nonempty run of ASCII digits, the only shape a reply parameter has. */
    private static boolean isDecimal(String field) {
        for (int index = 0; index < field.length(); index++) {
            char digit = field.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return !field.isEmpty();
    }

    private static InputEvent reply(TerminalReply reply) {
        return new InputEvent.TerminalReplyInput(reply);
    }

    /**
     * An operating system command: {@code ESC ] <code> ; <data>} ended by {@code BEL} or {@code ST}.
     *
     * <p>{@code ESC ]} is also how a terminal reports Alt+] with {@code metaSendsEscape}, so a byte
     * that cannot begin an OSC leaves the pair as the keystroke it was. The code has to be numeric
     * and separated from the data, which no typed sequence is.</p>
     */
    private Optional<InputEvent> readOperatingSystem() throws IOException {
        int first = readWithTimeout();
        if (first < '0' || first > '9') {
            if (first >= 0) {
                in.unread(first);
            }
            return Optional.of(altKey(']'));
        }
        int code = first - '0';
        while (true) {
            int next = readByteWithFreshDeadline();
            if (next >= '0' && next <= '9' && code <= MAX_OSC_CODE) {
                code = code * 10 + next - '0';
                continue;
            }
            if (next == ';') {
                // A clipboard reply is bounded by the session's read limit rather than by the reply
                // bound a terminal fact needs, because it carries the user's own text.
                int limit = code == Osc52.CODE ? maxClipboardReplyCharacters : MAX_REPLY_CHARACTERS;
                StringData data = readStringData(limit);
                if (data == null) {
                    return Optional.empty();
                }
                if (data.overflowed()) {
                    // Only a clipboard reply is reported as over-long: it is the one string reply
                    // whose consumer needs to tell "too large" from "never answered". Every other
                    // command's over-long data is consumed and dropped, as it always was.
                    return code == Osc52.CODE
                            ? Optional.of(reply(new TerminalReply.Overlong(code, data.head(), limit)))
                            : Optional.empty();
                }
                return Optional.of(reply(new TerminalReply.OperatingSystem(code, data.text())));
            }
            if (next == 0x18 || next == 0x1A) {
                // A cancelled command carries no value, whatever it had sent so far.
                return Optional.empty();
            }
            if (next == 0x07) {
                // An OSC with a code and no data at all, which is how a terminal says "no value".
                return Optional.of(reply(new TerminalReply.OperatingSystem(code, "")));
            }
            if (next == 0x1B) {
                return consumeStringTerminator()
                        ? Optional.of(reply(new TerminalReply.OperatingSystem(code, "")))
                        : Optional.<InputEvent>empty();
            }
            // Anything else is not an OSC: consume through its terminator so the payload cannot
            // arrive as ordinary text, and report nothing.
            readStringData(MAX_REPLY_CHARACTERS);
            return Optional.empty();
        }
    }

    /**
     * A device control string: {@code ESC P <header> <data> ST}.
     *
     * <p>The three replies the session can ask for share this framing: the terminal name and version,
     * a termcap capability, and the tertiary attributes. {@code ESC P} is also Alt+Shift+P, so a byte
     * that cannot begin a DCS header keeps the pair as that keystroke.</p>
     */
    private Optional<InputEvent> readDeviceControl() throws IOException {
        StringBuilder header = new StringBuilder();
        int first = readWithTimeout();
        if (first < 0x20 || first > 0x3F) {
            if (first >= 0) {
                in.unread(first);
            }
            return Optional.of(altKey('P'));
        }
        boolean overlong = false;
        int next = first;
        while (next < 0x40) {
            // Parameter and intermediate bytes come first; the first final byte ends the header.
            if (header.length() < MAX_DCS_HEADER) {
                header.append((char) next);
            } else {
                overlong = true;
            }
            next = readByteWithFreshDeadline();
            if (next < 0) {
                return Optional.empty();
            }
            if (next < 0x20 || next > 0x7E) {
                // A byte the framing does not allow ends the string as far as this decoder is
                // concerned; consume the rest so it cannot be delivered as text.
                readStringData(MAX_REPLY_CHARACTERS);
                return Optional.empty();
            }
        }
        if (overlong) {
            readStringData(MAX_REPLY_CHARACTERS);
            return Optional.empty();
        }
        header.append((char) next);
        StringData data = readStringData(MAX_REPLY_CHARACTERS);
        return data == null || data.overflowed()
                ? Optional.<InputEvent>empty()
                : Optional.of(reply(new TerminalReply.DeviceControl(header.toString(), data.text())));
    }

    /** The key a lone {@code ESC} plus one printable byte names with ALT, as it always has. */
    private static InputEvent altKey(char value) {
        String text = String.valueOf(value);
        return InputEvent.KeyInput.of(keyForText(text).with(Modifiers.ALT), text);
    }

    /**
     * The data of a string sequence, or null when it was cancelled or never terminated.
     *
     * <p>Every byte gets the escape timeout afresh: a reply is one write from the terminal, but a
     * long one may still arrive in pieces, and a reply that stops half-way must not hold the reader
     * open. {@code CAN} and {@code SUB} cancel the string, exactly as they cancel a control
     * sequence, and an {@code ESC} that does not begin {@code ST} cancels it too - the byte is given
     * back so the keystroke after it keeps its own meaning.</p>
     *
     * <p>Data past {@code maxCharacters} is not kept, but the sequence is still consumed through its
     * terminator and the overflow is reported with the frame: a payload the session will not use must
     * never be guessed at, and must never arrive as typed text either. The head of the data is kept
     * so a frame that carries its own identity can still be attributed.</p>
     */
    private StringData readStringData(int maxCharacters) throws IOException {
        StringBuilder data = new StringBuilder();
        boolean overflow = false;
        while (true) {
            int next = readByteWithFreshDeadline();
            if (next < 0) {
                return null;
            }
            if (next == 0x1B) {
                int terminator = readByteWithFreshDeadline();
                if (terminator < 0) {
                    return null;
                }
                if (terminator == '\\') {
                    return new StringData(data.toString(), overflow);
                }
                in.unread(terminator);
                return null;
            }
            if (next == 0x07) {
                return new StringData(data.toString(), overflow);
            }
            if (next == 0x18 || next == 0x1A) {
                return null;
            }
            if (data.length() < maxCharacters) {
                data.append((char) next);
            } else {
                overflow = true;
            }
        }
    }

    /**
     * The data of one string sequence, and whether more arrived than was kept.
     *
     * @param text the data that was kept
     * @param overflowed whether data past the bound was dropped
     */
    private record StringData(String text, boolean overflowed) {

        /**
         * Treats missing data as none.
         */
        StringData {
            text = text == null ? "" : text;
        }

        /**
         * The first characters of the data, which is what attributes an over-long frame.
         *
         * @return at most {@link #HEAD_CHARACTERS} characters of the data
         */
        String head() {
            return text.length() <= HEAD_CHARACTERS ? text : text.substring(0, HEAD_CHARACTERS);
        }
    }

    /**
     * How much of an over-long payload is kept so the frame can be attributed.
     *
     * <p>The longest identity an OSC reply carries in its data is a selection field of one character
     * and its separator; a few characters are kept so a frame that names itself is still recognizable
     * without retaining any of a payload the session refused.</p>
     */
    private static final int HEAD_CHARACTERS = 8;

    /**
     * Consumes the {@code \\} of an {@code ST} whose {@code ESC} has already been read.
     *
     * <p>An {@code ESC} inside a string that does not begin {@code ST} cancels the string; the byte
     * that followed goes back so the keystroke it starts keeps its own meaning.</p>
     */
    private boolean consumeStringTerminator() throws IOException {
        int terminator = readByteWithFreshDeadline();
        if (terminator < 0) {
            return false;
        }
        if (terminator == '\\') {
            return true;
        }
        in.unread(terminator);
        return false;
    }

    /**
     * Reads one byte with the escape timeout counted from now, for a sequence whose length is not
     * bounded by a handful of bytes.
     */
    private int readByteWithFreshDeadline() throws IOException {
        return in.readUntil(System.nanoTime() + escapeTimeoutNanos);
    }

    /**
     * The largest reply payload this decoder keeps, in characters.
     *
     * <p>A reply is a terminal's answer, not user input, and the longest one the allowlisted queries
     * can produce is a termcap value of a few hundred bytes. The cap is what keeps a terminal that
     * starts a string and never ends it from growing the buffer: past it the sequence is consumed and
     * dropped.</p>
     */
    public static final int MAX_REPLY_CHARACTERS = 4_096;

    /**
     * The largest OSC 52 reply data this decoder keeps, in characters, by default.
     *
     * <p>A clipboard reply carries the user's own text, so it is bounded by the clipboard read limit
     * rather than by {@link #MAX_REPLY_CHARACTERS}. The value is the encoded bound of
     * {@link ClipboardConfig#DEFAULT_MAX_READ_PAYLOAD_BYTES}, including the selection field; a runtime
     * configured with another limit passes its own bound instead.</p>
     */
    public static final int MAX_CLIPBOARD_REPLY_CHARACTERS =
            ClipboardConfig.defaults().maxReadEncodedCharacters();

    /** The largest device-control header kept, in characters; every documented one is four bytes. */
    private static final int MAX_DCS_HEADER = 16;

    /** The largest OSC command number accepted; the allowlisted ones are two digits. */
    private static final int MAX_OSC_CODE = 99_999;

    /** The parameterless CSI forms: cursor keys, editing keys, function keys, mouse and focus. */
    private InputEvent decodeExactCsi(String sequence) throws IOException {
        return switch (sequence) {
            case "A" -> InputEvent.KeyInput.of(Key.UP);
            case "B" -> InputEvent.KeyInput.of(Key.DOWN);
            case "C" -> InputEvent.KeyInput.of(Key.RIGHT);
            case "D" -> InputEvent.KeyInput.of(Key.LEFT);
            case "H" -> InputEvent.KeyInput.of(Key.HOME);
            case "F" -> InputEvent.KeyInput.of(Key.END);
            case "Q" -> InputEvent.KeyInput.of(Key.of("f2"));
            case "Z" -> InputEvent.KeyInput.of(Key.of("tab", Modifiers.SHIFT));
            case "1~", "7~" -> InputEvent.KeyInput.of(Key.HOME);
            case "4~", "8~" -> InputEvent.KeyInput.of(Key.END);
            case "2~" -> InputEvent.KeyInput.of(Key.of("insert"));
            case "3~" -> InputEvent.KeyInput.of(Key.DELETE);
            case "5~" -> InputEvent.KeyInput.of(Key.PAGE_UP);
            case "6~" -> InputEvent.KeyInput.of(Key.PAGE_DOWN);
            case "11~" -> InputEvent.KeyInput.of(Key.of("f1"));
            case "12~" -> InputEvent.KeyInput.of(Key.of("f2"));
            case "13~" -> InputEvent.KeyInput.of(Key.of("f3"));
            case "14~" -> InputEvent.KeyInput.of(Key.of("f4"));
            case "15~" -> InputEvent.KeyInput.of(Key.of("f5"));
            case "17~" -> InputEvent.KeyInput.of(Key.of("f6"));
            case "18~" -> InputEvent.KeyInput.of(Key.of("f7"));
            case "19~" -> InputEvent.KeyInput.of(Key.of("f8"));
            case "20~" -> InputEvent.KeyInput.of(Key.of("f9"));
            case "21~" -> InputEvent.KeyInput.of(Key.of("f10"));
            case "23~" -> InputEvent.KeyInput.of(Key.of("f11"));
            case "24~" -> InputEvent.KeyInput.of(Key.of("f12"));
            case "M" -> decodeX10Mouse();
            // xterm focus reporting: ESC [ I is a gained window focus, ESC [ O a lost one. Neither
            // is a keystroke, so neither can trigger a binding or type anything.
            case "I" -> new InputEvent.FocusInput(true);
            case "O" -> new InputEvent.FocusInput(false);
            default -> null;
        };
    }

    /**
     * The terminal's answer to the enhanced-keyboard flag query: {@code CSI ? flags u}.
     *
     * <p>An omitted flag value is the protocol's zero, and a non-numeric field is malformed rather
     * than zero: reporting a wrong set of enabled flags is worse than reporting nothing.</p>
     */
    private static InputEvent decodeEnhancementReport(String sequence) {
        if (sequence.charAt(sequence.length() - 1) != 'u') {
            return null;
        }
        int flags = number(sequence.substring(1, sequence.length() - 1), 0);
        if (flags < 0) {
            return null;
        }
        return new InputEvent.KeyboardEnhancementsInput(KeyboardEnhancements.of(flags));
    }

    /**
     * {@code CSI number ; modifiers : event-type [~ABCDEFHPQS]}.
     *
     * <p>A terminal that reports event types or modifiers but has not been asked for the full CSI
     * {@code u} form keeps encoding the functional keys this way, so the modifiers and event type
     * are attached to the key the number already names. Without this, a release of a cursor key and
     * a Ctrl+Up would both be decoded as nothing at all.</p>
     */
    private static InputEvent decodeParameterizedCsi(String sequence) {
        char last = sequence.charAt(sequence.length() - 1);
        String body = sequence.substring(0, sequence.length() - 1);
        if (last == 'u') {
            return decodeKittyKey(body);
        }
        String[] fields = body.split(";", -1);
        if (fields.length != 2) {
            return null;
        }
        Key key = parameterizedKey(last, fields[0]);
        if (key == null) {
            return null;
        }
        String[] parts = fields[1].split(":", -1);
        if (parts.length > 2) {
            return null;
        }
        int modifier = number(parts[0], 1);
        if (modifier < 1) {
            return null;
        }
        KeyEventType type = KeyEventType.PRESS;
        if (parts.length == 2) {
            type = KeyEventType.fromCode(number(parts[1], 1));
            if (type == null) {
                return null;
            }
        }
        Modifiers modifiers = modifiersFor(modifier - 1);
        if (modifiers == null) {
            return null;
        }
        // The functional keys never produce text, so a release carries none either.
        return InputEvent.KeyInput.of(key.with(modifiers), "", type);
    }

    /** The key a parameterised functional form names, or null when the form names none we report. */
    private static Key parameterizedKey(char last, String number) {
        if (last == '~') {
            return switch (number) {
                case "2" -> Key.of("insert");
                case "3" -> Key.DELETE;
                case "5" -> Key.PAGE_UP;
                case "6" -> Key.PAGE_DOWN;
                case "7" -> Key.HOME;
                case "8" -> Key.END;
                case "11" -> Key.of("f1");
                case "12" -> Key.of("f2");
                case "13" -> Key.of("f3");
                case "14" -> Key.of("f4");
                case "15" -> Key.of("f5");
                case "17" -> Key.of("f6");
                case "18" -> Key.of("f7");
                case "19" -> Key.of("f8");
                case "20" -> Key.of("f9");
                case "21" -> Key.of("f10");
                case "23" -> Key.of("f11");
                case "24" -> Key.of("f12");
                default -> null;
            };
        }
        // The letter forms always carry the fixed number 1; anything else is not that form.
        if (!"1".equals(number)) {
            return null;
        }
        return switch (last) {
            case 'A' -> Key.UP;
            case 'B' -> Key.DOWN;
            case 'C' -> Key.RIGHT;
            case 'D' -> Key.LEFT;
            case 'H' -> Key.HOME;
            case 'F' -> Key.END;
            case 'P' -> Key.of("f1");
            case 'Q' -> Key.of("f2");
            case 'S' -> Key.of("f4");
            default -> null;
        };
    }

    /**
     * The Kitty keyboard protocol key form:
     * {@code unicode-key-code : shifted : base ; modifiers : event-type ; text-as-codepoints}.
     */
    private static InputEvent decodeKittyKey(String body) {
        String[] groups = body.split(";", -1);
        if (groups.length > 3) {
            return null;
        }
        String[] codes = groups[0].split(":", -1);
        if (codes.length > 3) {
            return null;
        }
        int code = number(codes[0], -1);
        if (code < 0) {
            return null;
        }
        int shifted = codes.length > 1 ? number(codes[1], 0) : 0;
        if (shifted < 0) {
            return null;
        }
        int modifier = 1;
        KeyEventType type = KeyEventType.PRESS;
        if (groups.length > 1 && !groups[1].isEmpty()) {
            String[] parts = groups[1].split(":", -1);
            if (parts.length > 2) {
                return null;
            }
            modifier = number(parts[0], 1);
            if (modifier < 1) {
                return null;
            }
            if (parts.length == 2) {
                type = KeyEventType.fromCode(number(parts[1], 1));
                if (type == null) {
                    return null;
                }
            }
        }
        Modifiers modifiers = modifiersFor(modifier - 1);
        if (modifiers == null) {
            return null;
        }
        String reported = null;
        if (groups.length > 2) {
            reported = reportedText(groups[2]);
            if (reported == null) {
                return null;
            }
        }
        return kittyKey(code, shifted, modifiers, type, reported);
    }

    /** One enhanced key event from its decoded fields. */
    private static InputEvent kittyKey(int code, int shifted, Modifiers modifiers, KeyEventType type, String reported) {
        Key named = functionalKey(code);
        String text = reported;
        if (text == null) {
            // Without a reported text the terminal expects the application to derive one, and it
            // only exists for a printable key under the modifiers a typed character carries.
            text = "";
            if (named == null && printable(code) && plainModifiers(modifiers)) {
                int chosen = shifted != 0 && printable(shifted) ? shifted : code;
                text = new String(Character.toChars(chosen));
            }
        }
        if (named != null) {
            return InputEvent.KeyInput.of(named.with(modifiers), text, type);
        }
        if (isPrivateUse(code)) {
            // A functional key whose name this project does not define. Naming it after its
            // private-use code point would advertise a gesture no binding can ever mean.
            return null;
        }
        if (code == 0) {
            // The protocol's "pure text event": no key is associated with the text.
            return text.isEmpty()
                    ? null
                    : InputEvent.KeyInput.of(keyForText(text).with(modifiers), text, type);
        }
        if (!printable(code)) {
            return null;
        }
        return InputEvent.KeyInput.of(
                keyForText(new String(Character.toChars(code))).with(modifiers), text, type);
    }

    /** The key a Kitty keyboard protocol number names, or null when the number names a text key. */
    private static Key functionalKey(int code) {
        return switch (code) {
            case 27, 57344 -> Key.ESCAPE;
            case 13, 57345 -> Key.ENTER;
            case 9, 57346 -> Key.TAB;
            case 127, 57347 -> Key.BACKSPACE;
            case 57348 -> Key.of("insert");
            case 57349 -> Key.DELETE;
            case 57350 -> Key.LEFT;
            case 57351 -> Key.RIGHT;
            case 57352 -> Key.UP;
            case 57353 -> Key.DOWN;
            case 57354 -> Key.PAGE_UP;
            case 57355 -> Key.PAGE_DOWN;
            case 57356 -> Key.HOME;
            case 57357 -> Key.END;
            default -> (code >= 57364 && code <= 57398) ? Key.of("f" + (code - 57363)) : null;
        };
    }

    /** Whether a code point is in the Private Use Area the protocol reserves for functional keys. */
    private static boolean isPrivateUse(int code) {
        return code >= 0xE000 && code <= 0xF8FF;
    }

    /** Whether a code point can be a key name and text, by the rule {@link #isPrintable} applies. */
    private static boolean printable(int code) {
        return code >= 0x20
                && code <= Character.MAX_CODE_POINT
                && code != 0x7F
                && !(code >= 0x80 && code <= 0x9F)
                && !(code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE);
    }

    /** The modifier states a printable key carries its text under: none, or shift alone. */
    private static boolean plainModifiers(Modifiers modifiers) {
        return modifiers.none() || (modifiers.shift() && !modifiers.ctrl() && !modifiers.alt());
    }

    /**
     * The modifiers a protocol bit set names, or null when it names one this project cannot carry.
     *
     * <p>Shift, Alt and Ctrl are {@link Modifiers}. Super, Hyper and Meta are not, and are not
     * silently folded into Alt: delivering a Super chord as a plain key would run a binding or
     * insert text for a gesture the user aimed at the terminal. Such an event is dropped instead.
     * Caps Lock and Num Lock are ignored, exactly as the protocol says they are for text keys.</p>
     */
    private static Modifiers modifiersFor(int bits) {
        if ((bits & (SUPER | HYPER | META)) != 0) {
            return null;
        }
        return new Modifiers((bits & CTRL) != 0, (bits & ALT) != 0, (bits & SHIFT) != 0);
    }

    private static final int SHIFT = 1;
    private static final int ALT = 2;
    private static final int CTRL = 4;
    private static final int SUPER = 8;
    private static final int HYPER = 16;
    private static final int META = 32;

    /**
     * The associated text a colon-separated code point list names, or null when it is malformed or
     * not printable.
     *
     * <p>The protocol forbids control codes in associated text. A report that contains one is
     * dropped rather than repaired, for the same reason a stray C1 control is: a keystroke the user
     * never typed must not reach routing.</p>
     */
    private static String reportedText(String field) {
        if (field.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String part : field.split(":", -1)) {
            int code = number(part, -1);
            if (code < 0 || !printable(code)) {
                return null;
            }
            text.appendCodePoint(code);
        }
        return text.toString();
    }

    /** A nonnegative decimal field, or {@code fallback} when the field is empty, or -1 when malformed. */
    private static int number(String field, int fallback) {
        if (field.isEmpty()) {
            return fallback;
        }
        int value = 0;
        for (int i = 0; i < field.length(); i++) {
            char digit = field.charAt(i);
            if (digit < '0' || digit > '9') {
                return -1;
            }
            // Bound the value before it can overflow, so an absurd parameter is malformed rather
            // than a wrapped number that happens to name a key.
            value = value * 10 + (digit - '0');
            if (value > MAX_PARAMETER) {
                return -1;
            }
        }
        return value;
    }

    /** The largest decimal parameter accepted: every key code and text code point fits in it. */
    private static final int MAX_PARAMETER = Character.MAX_CODE_POINT;

    private InputEvent decodeSs3(char value) {
        return switch (value) {
            case 'A' -> InputEvent.KeyInput.of(Key.UP);
            case 'B' -> InputEvent.KeyInput.of(Key.DOWN);
            case 'C' -> InputEvent.KeyInput.of(Key.RIGHT);
            case 'D' -> InputEvent.KeyInput.of(Key.LEFT);
            case 'H' -> InputEvent.KeyInput.of(Key.HOME);
            case 'F' -> InputEvent.KeyInput.of(Key.END);
            case 'P' -> InputEvent.KeyInput.of(Key.of("f1"));
            case 'Q' -> InputEvent.KeyInput.of(Key.of("f2"));
            case 'R' -> InputEvent.KeyInput.of(Key.of("f3"));
            case 'S' -> InputEvent.KeyInput.of(Key.of("f4"));
            default -> null;
        };
    }

    /**
     * {@code ESC [ < b ; x ; y M} (press, motion, wheel) or {@code ESC [ < b ; x ; y m} (release).
     *
     * <p>The SGR form is the only one Braxton asks for: it names the released button and can address
     * a column past the legacy byte form's limit. Coordinates are reported one-based and are
     * normalized to the zero-based cells every layer above the decoder counts in.</p>
     */
    private InputEvent decodeSgrMouse(String sequence) {
        char last = sequence.charAt(sequence.length() - 1);
        if (last != 'M' && last != 'm') {
            return null;
        }
        String[] fields = sequence.substring(1, sequence.length() - 1).split(";", -1);
        if (fields.length != 3) {
            return null;
        }
        for (String field : fields) {
            if (field.isEmpty()) {
                return null;
            }
        }
        int code = number(fields[0], -1);
        int column = number(fields[1], -1);
        int row = number(fields[2], -1);
        // A report about cell zero does not exist: the terminal counts from one, so a zero is a
        // malformed report rather than the first cell.
        if (code < 0 || column <= 0 || row <= 0) {
            return null;
        }
        return pointerReport(code, column - 1, row - 1, last == 'm');
    }

    /**
     * {@code ESC [ M} followed by three bytes, the legacy report every terminal can send.
     *
     * <p>Each byte carries its value plus {@code 32}, and the form has two limits the SGR form does
     * not: it never names the released button - a release is button {@code 3} whatever was held -
     * and it cannot address a column past 223. The action, the button and the coordinates are
     * therefore what the bytes can actually say, never a guess at what they might have meant.</p>
     */
    private InputEvent decodeX10Mouse() throws IOException {
        int encodedButton = readWithTimeout();
        int column = readWithTimeout();
        int row = readWithTimeout();
        if (encodedButton < 0 || column < 0 || row < 0) {
            return null;
        }
        int code = encodedButton - X10_OFFSET;
        if (code < 0 || column < X10_OFFSET || row < X10_OFFSET) {
            return null;
        }
        return pointerReport(code, column - X10_OFFSET, row - X10_OFFSET, namesNoButton(code));
    }

    /** Whether a legacy report's code is its form's release: button three with nothing else set. */
    private static boolean namesNoButton(int code) {
        int base = code & ~(MOUSE_SHIFT | MOUSE_ALT | MOUSE_CTRL);
        return (base & MOTION) == 0 && (base & WHEEL) == 0 && (base & BUTTON_MASK) == BUTTON_MASK;
    }

    /** The value one legacy report byte adds to the number it carries. */
    private static final int X10_OFFSET = 32;

    /**
     * One pointer report from its decoded fields.
     *
     * <p>The protocol's code is a bit set: two bits of button, one each for shift, alt and ctrl, one
     * for motion, one for the wheel, and one that selects the four extra buttons this project does
     * not model. A report that sets the extra-button bit is dropped rather than delivered as one of
     * the three buttons it has, for the same reason a Super or Hyper chord is dropped on the
     * keyboard path: a gesture the user aimed elsewhere must not arrive as one they did not make.</p>
     */
    private static InputEvent pointerReport(int code, int column, int row, boolean release) {
        if (code > MAX_MOUSE_CODE) {
            return null;
        }
        int modifiers = code & (MOUSE_SHIFT | MOUSE_ALT | MOUSE_CTRL);
        int base = code & ~(MOUSE_SHIFT | MOUSE_ALT | MOUSE_CTRL);
        if ((base & EXTRA_BUTTONS) != 0) {
            return null;
        }
        Modifiers held = new Modifiers(
                (modifiers & MOUSE_CTRL) != 0, (modifiers & MOUSE_ALT) != 0, (modifiers & MOUSE_SHIFT) != 0);
        int button = base & BUTTON_MASK;
        if ((base & WHEEL) != 0) {
            // A wheel turns and reports; it is never pressed or released, so a release that names a
            // wheel direction is malformed.
            if (release) {
                return null;
            }
            return new InputEvent.PointerInput(PointerEvent.wheel(wheel(button), held, row, column));
        }
        if ((base & MOTION) != 0) {
            return release
                    ? null
                    : new InputEvent.PointerInput(
                            PointerEvent.motion(pressed(button, PointerButton.NONE), held, row, column));
        }
        if (button == BUTTON_MASK) {
            // The legacy form says "button three" for a release. It is the only way that form can
            // report one, and it cannot say which button came up: the release is real, the button
            // is not named.
            return release
                    ? new InputEvent.PointerInput(PointerEvent.release(PointerButton.NONE, held, row, column))
                    : null;
        }
        PointerButton named = pressed(button, PointerButton.NONE);
        return release
                ? new InputEvent.PointerInput(PointerEvent.release(named, held, row, column))
                : new InputEvent.PointerInput(PointerEvent.press(named, held, row, column));
    }

    /** The button one wheel report names. */
    private static PointerButton wheel(int button) {
        return switch (button) {
            case 0 -> PointerButton.WHEEL_UP;
            case 1 -> PointerButton.WHEEL_DOWN;
            case 2 -> PointerButton.WHEEL_LEFT;
            default -> PointerButton.WHEEL_RIGHT;
        };
    }

    /** The button two button bits name, or {@code none} for the value that names no button. */
    private static PointerButton pressed(int bits, PointerButton none) {
        return switch (bits) {
            case 0 -> PointerButton.LEFT;
            case 1 -> PointerButton.MIDDLE;
            case 2 -> PointerButton.RIGHT;
            default -> none;
        };
    }

    /** Shift, as the pointer protocol's code spells it. */
    private static final int MOUSE_SHIFT = 4;

    /** Alt (the protocol's meta), as the pointer protocol's code spells it. */
    private static final int MOUSE_ALT = 8;

    /** Ctrl, as the pointer protocol's code spells it. */
    private static final int MOUSE_CTRL = 16;

    /** The largest code a pointer report can carry: its fields are one byte of flags. */
    private static final int MAX_MOUSE_CODE = 255;

    /** The two bits that name a button. */
    private static final int BUTTON_MASK = 3;

    /** The bit a report sets for motion. */
    private static final int MOTION = 32;

    /** The bit a report sets for a wheel gesture instead of a button. */
    private static final int WHEEL = 64;

    /** The bit a report sets for one of the four buttons this project does not model. */
    private static final int EXTRA_BUTTONS = 128;

    /**
     * A stable key name for printable text. Whitespace is named {@code space} with the actual text
     * preserved, so typing a plain space (or an ideographic space) never produces a blank identity.
     */
    static Key keyForText(String text) {
        if (text.codePoints().allMatch(Character::isWhitespace)) {
            return Key.SPACE;
        }
        return Key.of(text.toLowerCase(java.util.Locale.ROOT));
    }

    private int readWithTimeout() throws IOException {
        return readWithDeadline(escapeDeadline);
    }

    private int readWithDeadline(long deadlineNanos) throws IOException {
        return in.readUntil(deadlineNanos);
    }
}

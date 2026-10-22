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

package io.github.dhoard.braxton.internal.input;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Optional;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;

/**
 * Decodes terminal bytes into normalized {@link InputEvent} values.
 *
 * <p>The decoder owns no application state and dispatches nothing: it reads one event at a time so
 * the runtime can route it against the latest committed view. Escape sequences are bounded by a
 * short timeout, CSI sequences by a byte cap, and printable input is decoded as UTF-8 so multibyte
 * characters and split reads behave correctly.</p>
 */
public final class InputDecoder {

    public static final int DEFAULT_ESCAPE_TIMEOUT_MILLIS = 30;
    public static final int MAX_CSI_LENGTH = 64;

    private final PushbackInputStream in;
    private final int escapeTimeoutMillis;
    private final int maxPasteBytes;
    private final long pasteIdleTimeoutMillis;

    public InputDecoder(InputStream in) {
        this(in, DEFAULT_ESCAPE_TIMEOUT_MILLIS, PasteDecoder.DEFAULT_MAX_BYTES,
                PasteDecoder.DEFAULT_IDLE_TIMEOUT_MILLIS);
    }

    public InputDecoder(InputStream in, int escapeTimeoutMillis, int maxPasteBytes,
            long pasteIdleTimeoutMillis) {
        this.in = new PushbackInputStream(in, 1);
        this.escapeTimeoutMillis = Math.max(1, escapeTimeoutMillis);
        this.maxPasteBytes = Math.max(0, maxPasteBytes);
        this.pasteIdleTimeoutMillis = Math.max(1L, pasteIdleTimeoutMillis);
    }

    /**
     * Reads one event.
     *
     * <p>Sequences and scalars that map to nothing (most mouse reports, unknown escapes, a control
     * character outside ASCII) are skipped inside this call: returning empty is reserved for real
     * end-of-input, so an ignored click can never be mistaken for the end of the session.</p>
     *
     * @return the event, or empty at end-of-input or when the session closed the stream
     */
    public Optional<InputEvent> read() throws IOException {
        while (true) {
            int first = in.read();
            if (first < 0) {
                return Optional.empty();
            }
            InputEvent event = first == 0x1B
                    ? readEscape().orElse(null)
                    : decodeSingleByte(first);
            if (event != null) {
                return Optional.of(event);
            }
        }
    }

    private InputEvent decodeSingleByte(int value) throws IOException {
        if (value < 0x20 || value == 0x7F) {
            return InputEvent.KeyInput.of(keyForControlByte(value));
        }
        if (value < 0x80) {
            char c = (char) value;
            return InputEvent.KeyInput.of(keyForText(String.valueOf(c)), String.valueOf(c));
        }
        String text = decodeUtf8(value);
        // A C1 control (U+0080-U+009F) arrives as a two-byte UTF-8 scalar, so it reaches here as
        // printable-looking text. It is no more printable than a C0 control: it names no gesture and
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

    /**
     * Reassembles a multi-byte UTF-8 scalar from the already-read lead byte. A byte that cannot
     * start a scalar (stray continuation, overlong lead, or beyond U+10FFFF) is dropped without
     * consuming following bytes, and a sequence truncated by a non-continuation byte gives that
     * byte back, so the next character is still decoded. A complete sequence that is itself
     * ill-formed (an overlong form, a surrogate, or past U+10FFFF) is dropped as well: a string
     * decoder would report it as U+FFFD, and that replacement character would reach the application
     * as printable text it never typed.
     */
    private String decodeUtf8(int lead) throws IOException {
        int expected;
        if (lead >= 0xC2 && lead <= 0xDF) {
            expected = 2;
        } else if (lead >= 0xE0 && lead <= 0xEF) {
            expected = 3;
        } else if (lead >= 0xF0 && lead <= 0xF4) {
            expected = 4;
        } else {
            return "";
        }
        byte[] bytes = new byte[expected];
        bytes[0] = (byte) lead;
        for (int i = 1; i < expected; i++) {
            int next = in.read();
            if (next < 0) {
                return "";
            }
            if ((next & 0xC0) != 0x80) {
                // The sequence is truncated: give the byte back so the character it starts is
                // still decoded instead of being swallowed with the broken sequence.
                in.unread(next);
                return "";
            }
            bytes[i] = (byte) next;
        }
        int codePoint = codePoint(bytes, expected);
        return codePoint < 0 ? "" : new String(Character.toChars(codePoint));
    }

    /**
     * Code point of one complete UTF-8 sequence, or -1 when the sequence is overlong, a surrogate, or
     * beyond U+10FFFF. Only the payload bits are read: the lead byte's range was already checked.
     */
    private static int codePoint(byte[] bytes, int length) {
        int codePoint = switch (length) {
            case 2 -> bytes[0] & 0x1F;
            case 3 -> bytes[0] & 0x0F;
            default -> bytes[0] & 0x07;
        };
        for (int i = 1; i < length; i++) {
            codePoint = (codePoint << 6) | (bytes[i] & 0x3F);
        }
        boolean overlong = switch (length) {
            case 2 -> codePoint < 0x80;
            case 3 -> codePoint < 0x800;
            default -> codePoint < 0x10000;
        };
        boolean surrogate = codePoint >= 0xD800 && codePoint <= 0xDFFF;
        return overlong || surrogate || codePoint > 0x10FFFF ? -1 : codePoint;
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
                return Optional.of(InputEvent.KeyInput.of(Key.ESCAPE));
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
        if (second < 0x20 || second == 0x7F) {
            // ESC plus a control byte is how a terminal reports the Alt-modified key: ESC DEL is
            // Alt+Backspace, and an xterm-family terminal with metaSendsEscape reports Alt+Enter and
            // Alt+Tab as ESC CR and ESC TAB. Every control byte names a gesture of its own, so the
            // pair keeps that gesture and adds ALT to the modifiers the byte already names - ESC ETX
            // is Alt plus the Ctrl+C gesture, and stays distinct from the ESC c pair that reports a
            // plain Alt+C. Consuming the pair as a bare Escape instead loses the keystroke the user
            // pressed and delivers one they never pressed, and Escape and Ctrl+C are two-press
            // gestures in real applications.
            Key control = keyForControlByte(second);
            return Optional.of(InputEvent.KeyInput.of(
                    control.with(control.modifiers().withAlt(true))));
        }
        if (second >= 0x80) {
            // Alt plus a printable character outside ASCII: the following bytes belong to the same
            // UTF-8 scalar, so decode them together instead of naming the lead byte's Latin-1
            // character and leaving a stray continuation byte behind.
            String text = decodeUtf8(second);
            // A C1 control (encoded as a multibyte scalar) is not printable either, and unlike a C0
            // control it names no key of its own, so ALT plus one is reported as the escape it
            // started with.
            if (text.isEmpty() || !isPrintable(text)) {
                return Optional.of(InputEvent.KeyInput.of(Key.ESCAPE));
            }
            return Optional.of(InputEvent.KeyInput.of(
                    keyForText(text).with(Modifiers.ALT), text));
        }
        char c = (char) second;
        return Optional.of(InputEvent.KeyInput.of(
                keyForText(String.valueOf(c)).with(Modifiers.ALT), String.valueOf(c)));
    }

    /** Whether every code point of {@code text} is printable, so it can be a key name and text. */
    private static boolean isPrintable(String text) {
        return text.codePoints().noneMatch(code -> code < 0x20 || code == 0x7F
                || (code >= 0x80 && code <= 0x9F));
    }

    private String readCsiSequence() throws IOException {
        StringBuilder sequence = new StringBuilder();
        long deadline = System.nanoTime() + escapeTimeoutMillis * 1_000_000L;
        while (true) {
            int next = readWithDeadline(deadline);
            if (next < 0) {
                break;
            }
            // Past the cap the sequence is not stored, but its bytes are still consumed up to the
            // final byte: stopping there would deliver the tail (parameters and final byte) to the
            // application as ordinary text.
            if (sequence.length() < MAX_CSI_LENGTH) {
                sequence.append((char) next);
            }
            if (next >= 0x40 && next <= 0x7E) {
                break;
            }
            deadline = System.nanoTime() + escapeTimeoutMillis * 1_000_000L;
        }
        return sequence.toString();
    }

    private InputEvent decodeCsi(String sequence) throws IOException {
        if (sequence.isEmpty()) {
            return InputEvent.KeyInput.of(Key.ESCAPE);
        }
        if (PasteDecoder.START_BODY.equals(sequence)) {
            PasteDecoder.Result paste = PasteDecoder.read(in, maxPasteBytes, pasteIdleTimeoutMillis);
            return new InputEvent.PasteInput(paste.text());
        }
        if (PasteDecoder.END_BODY.equals(sequence)) {
            return null;
        }
        if (sequence.charAt(0) == '<') {
            return decodeSgrMouse(sequence);
        }
        return switch (sequence) {
            case "A" -> InputEvent.KeyInput.of(Key.UP);
            case "B" -> InputEvent.KeyInput.of(Key.DOWN);
            case "C" -> InputEvent.KeyInput.of(Key.RIGHT);
            case "D" -> InputEvent.KeyInput.of(Key.LEFT);
            case "H" -> InputEvent.KeyInput.of(Key.HOME);
            case "F" -> InputEvent.KeyInput.of(Key.END);
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
            default -> null;
        };
    }

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

    /** {@code ESC [ < b ; x ; y M}. Only wheel gestures are mapped; other mouse events are ignored. */
    private InputEvent decodeSgrMouse(String sequence) {
        if (sequence.length() < 3) {
            return null;
        }
        String body = sequence.substring(1, sequence.length() - 1);
        int separator = body.indexOf(';');
        String codeText = separator < 0 ? body : body.substring(0, separator);
        int code;
        try {
            code = Integer.parseInt(codeText);
        } catch (NumberFormatException malformed) {
            return null;
        }
        int button = code & ~(4 | 8 | 16);
        if (button == 64) {
            return InputEvent.KeyInput.of(Key.WHEEL_UP);
        }
        if (button == 65) {
            return InputEvent.KeyInput.of(Key.WHEEL_DOWN);
        }
        return null;
    }

    /** {@code ESC [ M} followed by three bytes. Only wheel gestures are mapped. */
    private InputEvent decodeX10Mouse() throws IOException {
        int encodedButton = readWithTimeout();
        int column = readWithTimeout();
        int row = readWithTimeout();
        if (encodedButton < 0 || column < 0 || row < 0) {
            return null;
        }
        int button = (encodedButton - 32) & ~(4 | 8 | 16);
        if (button == 64) {
            return InputEvent.KeyInput.of(Key.WHEEL_UP);
        }
        if (button == 65) {
            return InputEvent.KeyInput.of(Key.WHEEL_DOWN);
        }
        return null;
    }

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
        return readWithDeadline(System.nanoTime() + escapeTimeoutMillis * 1_000_000L);
    }

    private int readWithDeadline(long deadlineNanos) throws IOException {
        while (true) {
            if (in.available() > 0) {
                return in.read();
            }
            if (System.nanoTime() - deadlineNanos >= 0L) {
                return -1;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
    }
}

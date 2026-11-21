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

package io.github.dhoard.braxton.input;

import io.github.dhoard.braxton.layout.Size;

/**
 * Normalized input delivered to routing.
 *
 * <p>Printable keys carry their text once; a consumed shortcut never also inserts that text. Paste
 * is one atomic operation and is never decomposed into key events. Control sequences, timing, and
 * UTF-8 decoding belong to the terminal/decoder boundary.</p>
 *
 * <p>Terminal facts that are not keystrokes arrive as their own values: a terminal focus change
 * ({@link FocusInput}), the enhanced-keyboard flags the terminal reported
 * ({@link KeyboardEnhancementsInput}), and a reply to a query ({@link TerminalReplyInput}). None is
 * a shortcut, and none changes which element of the view tree owns focus. A reply is delivered to
 * the session's query bookkeeping rather than routed, because it answers a question the application
 * asked and is never a gesture the user made.</p>
 */
public sealed interface InputEvent {

    /**
     * A key gesture. {@code text} is non-empty only for printable input.
     *
     * <p>A gesture no enhanced terminal distinguishes reports {@link KeyEventType#PRESS}. A terminal
     * that negotiated {@link KeyboardEnhancements#reportEventTypes()} also reports repeats and
     * releases, and a release keeps the same key, modifiers and associated text as the press it
     * ends. Only {@link #activates()} events run bindings or insert text.</p>
     *
     * @param key the normalized key identity
     * @param text the printable text this gesture carries, or empty
     * @param type the kind of key event
     * @param capturedAtNanos the runtime capture time, or zero when unstamped
     */
    record KeyInput(Key key, String text, KeyEventType type, long capturedAtNanos) implements InputEvent {

        /**
         * Creates a {@code KeyInput} value.
         *
         * @param key the normalized key identity
         * @param text the printable text this gesture carries, or empty
         * @param type the kind of key event
         * @param capturedAtNanos the runtime capture time, or zero when unstamped
         */
        public KeyInput {
            if (key == null) {
                throw new IllegalArgumentException("key is required");
            }
            text = text == null ? "" : text;
            type = type == null ? KeyEventType.PRESS : type;
            capturedAtNanos = Math.max(0L, capturedAtNanos);
        }

        /**
         * A press, which is every gesture an unenhanced terminal can report.
         *
         * @param key the normalized key identity
         * @param text the printable text this gesture carries, or empty
         * @param capturedAtNanos the runtime capture time, or zero when unstamped
         */
        public KeyInput(Key key, String text, long capturedAtNanos) {
            this(key, text, KeyEventType.PRESS, capturedAtNanos);
        }

        /**
         * Creates a key event with the supplied modifier and event metadata.
         *
         * @param key the normalized key identity
         * @return the normalized key event
         */
        public static KeyInput of(Key key) {
            return new KeyInput(key, "", KeyEventType.PRESS, 0L);
        }

        /**
         * Creates a key event with the supplied modifier and event metadata.
         *
         * @param key the normalized key identity
         * @param text the printable text this gesture carries, or empty
         * @return the normalized key event
         */
        public static KeyInput of(Key key, String text) {
            return new KeyInput(key, text, KeyEventType.PRESS, 0L);
        }

        /**
         * Creates a key event with the supplied modifier and event metadata.
         *
         * @param key the normalized key identity
         * @param text the printable text this gesture carries, or empty
         * @param capturedAtNanos the runtime capture time, or zero when unstamped
         * @return the normalized key event
         */
        public static KeyInput of(Key key, String text, long capturedAtNanos) {
            return new KeyInput(key, text, KeyEventType.PRESS, capturedAtNanos);
        }

        /**
         * Creates a key event with the supplied modifier and event metadata.
         *
         * @param key the normalized key identity
         * @param text the printable text this gesture carries, or empty
         * @param type the kind of key event
         * @return the normalized key event
         */
        public static KeyInput of(Key key, String text, KeyEventType type) {
            return new KeyInput(key, text, type, 0L);
        }

        /**
         * The same gesture with a runtime capture timestamp.
         *
         * @param nanos the monotonic capture timestamp in nanoseconds
         * @return the same gesture with a runtime capture timestamp
         */
        public KeyInput stamped(long nanos) {
            return new KeyInput(key, text, type, nanos);
        }

        /**
         * The same gesture reported as another kind of key event.
         *
         * @param value the kind to report
         * @return this instance when the kind did not change
         */
        public KeyInput withType(KeyEventType value) {
            KeyEventType reported = value == null ? KeyEventType.PRESS : value;
            return reported == type ? this : new KeyInput(key, text, reported, capturedAtNanos);
        }

        /**
         * Whether this event runs bindings and may insert text: a press or a repeat.
         *
         * <p>A release reports the same key, modifiers and text the press carried, so routing has
         * to decide by kind rather than by content. It never activates.</p>
         *
         * @return true for a press or a repeat, false for a release
         */
        public boolean activates() {
            return type != KeyEventType.RELEASE;
        }
    }

    /**
     * One complete bracketed-paste payload (unsanitized; sanitizing is the application's policy).
     *
     * @param text the complete paste payload, unsanitized
     */
    record PasteInput(String text) implements InputEvent {

        /**
         * Creates a {@code PasteInput} value.
         *
         * @param text the complete paste payload, unsanitized
         */
        public PasteInput {
            text = text == null ? "" : text;
        }
    }

    /**
     * A pointer report: a press, a release, motion, or a wheel gesture at one cell.
     *
     * <p>Pointer input is not a keystroke and never runs a key binding. It is delivered to the
     * widget painted under the reported cell, translated into that widget's own coordinates, so
     * where the pointer is decides what receives it - see
     * {@link io.github.dhoard.braxton.ui.Widget#onPointer}. A gesture that no widget takes - a
     * wheel turn over a blank area, for instance - falls back to the wheel gesture it used to be,
     * so an application that only binds wheel keys keeps working.</p>
     *
     * @param pointer the normalized report
     */
    record PointerInput(PointerEvent pointer) implements InputEvent {

        /**
         * Creates a {@code PointerInput} value.
         *
         * @param pointer the normalized report
         */
        public PointerInput {
            if (pointer == null) {
                throw new IllegalArgumentException("pointer is required");
            }
        }
    }

    /**
     * The terminal changed size.
     *
     * @param size the measured terminal size
     */
    record ResizeInput(Size size) implements InputEvent {

        /**
         * Creates a {@code ResizeInput} value.
         *
         * @param size the measured terminal size
         */
        public ResizeInput {
            if (size == null) {
                throw new IllegalArgumentException("size is required");
            }
        }
    }

    /**
     * The terminal window gained or lost focus.
     *
     * <p>This is a fact about the terminal, not about the view tree: it never changes which element
     * owns focus, and an application that does not subscribe can ignore it. A terminal never asked
     * to report focus
     * ({@link io.github.dhoard.braxton.app.TerminalOptions#withFocusReporting(boolean)}) sends no such
     * event.</p>
     *
     * @param gained true when the terminal window gained focus, false when it lost it
     */
    record FocusInput(boolean gained) implements InputEvent {}
    /**
     * The terminal answered which enhanced-keyboard flags it has enabled.
     *
     * <p>The reply is the terminal's own statement, so it is the only authority for whether an
     * enhancement took effect: a terminal that is silent about the protocol reports nothing at all.
     * </p>
     *
     * @param flags the reported flags
     */
    record KeyboardEnhancementsInput(KeyboardEnhancements flags) implements InputEvent {

        /**
         * Creates a {@code KeyboardEnhancementsInput} value.
         *
         * @param flags the reported flags
         */
        public KeyboardEnhancementsInput {
            if (flags == null) {
                throw new IllegalArgumentException("flags is required");
            }
        }
    }

    /**
     * The terminal answered a query.
     *
     * <p>The reply is framed but not interpreted: what it means depends on the query the session is
     * waiting for. The runtime delivers it to the query that asked and discards it when nothing did,
     * so a reply can never reach a binding or insert text. An application that wants a terminal fact
     * asks for it with {@link io.github.dhoard.braxton.effect.Command#query} instead of watching
     * input.</p>
     *
     * @param reply the framed reply
     */
    record TerminalReplyInput(TerminalReply reply) implements InputEvent {

        /**
         * Creates a {@code TerminalReplyInput} value.
         *
         * @param reply the framed reply
         */
        public TerminalReplyInput {
            if (reply == null) {
                throw new IllegalArgumentException("reply is required");
            }
        }
    }
}

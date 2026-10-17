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

package io.github.dhoard.braxton.input;

import io.github.dhoard.braxton.layout.Size;

/**
 * Normalized input delivered to routing.
 *
 * <p>Printable keys carry their text once; a consumed shortcut never also inserts that text. Paste
 * is one atomic operation and is never decomposed into key events. Control sequences, timing, and
 * UTF-8 decoding belong to the terminal/decoder boundary.</p>
 */
public sealed interface InputEvent {

    /** A key gesture. {@code text} is non-empty only for printable input. */
    record KeyInput(Key key, String text, long capturedAtNanos) implements InputEvent {

        public KeyInput {
            if (key == null) {
                throw new IllegalArgumentException("key is required");
            }
            text = text == null ? "" : text;
            capturedAtNanos = Math.max(0L, capturedAtNanos);
        }

        public static KeyInput of(Key key) {
            return new KeyInput(key, "", 0L);
        }

        public static KeyInput of(Key key, String text) {
            return new KeyInput(key, text, 0L);
        }

        public static KeyInput of(Key key, String text, long capturedAtNanos) {
            return new KeyInput(key, text, capturedAtNanos);
        }

        /** The same gesture with a runtime capture timestamp. */
        public KeyInput stamped(long nanos) {
            return new KeyInput(key, text, nanos);
        }
    }

    /** One complete bracketed-paste payload (unsanitized; sanitizing is the application's policy). */
    record PasteInput(String text) implements InputEvent {

        public PasteInput {
            text = text == null ? "" : text;
        }
    }

    /** The terminal changed size. */
    record ResizeInput(Size size) implements InputEvent {

        public ResizeInput {
            if (size == null) {
                throw new IllegalArgumentException("size is required");
            }
        }
    }
}

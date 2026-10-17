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

package io.github.dhoard.braxton.widget;

/** One editing gesture. Values are produced by the editor view and handled by the editor reducer. */
public sealed interface EditorAction {

    /** Inserts printable text at the caret, replacing the selection. */
    record Insert(String text) implements EditorAction {

        public Insert {
            text = text == null ? "" : text;
        }
    }

    /** Inserts one complete paste payload, sanitized according to the editor options. */
    record Paste(String text) implements EditorAction {

        public Paste {
            text = text == null ? "" : text;
        }
    }

    record Backspace() implements EditorAction {
    }

    record Delete() implements EditorAction {
    }

    record MoveLeft() implements EditorAction {
    }

    record MoveRight() implements EditorAction {
    }

    record MoveUp() implements EditorAction {
    }

    record MoveDown() implements EditorAction {
    }

    record MoveHome() implements EditorAction {
    }

    record MoveEnd() implements EditorAction {
    }

    record SelectAll() implements EditorAction {
    }

    record Newline() implements EditorAction {
    }

    /** Requests submission; the application decides what submission means. */
    record Submit() implements EditorAction {
    }

    /** Copies the selection, or the whole draft when nothing is selected. */
    record Copy() implements EditorAction {
    }

    /** Requests the internal clipboard buffer; the application answers with {@link Paste}. */
    record PasteRequest() implements EditorAction {
    }
}

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

package io.github.dhoard.braxton.clipboard;

/**
 * The observable result of asking the terminal for its clipboard.
 *
 * <p>A read is a question, so it always has an answer: the text the terminal reported, or the reason
 * there is none. The failures are deliberately distinct. A terminal that never answers looks nothing
 * like a clipboard that is empty, and a reply this library cannot believe is reported as invalid
 * rather than being decoded into text the user never copied - the whole reason the payload is
 * checked byte for byte.</p>
 *
 * <p>{@link #text()} is empty for every outcome that is not {@link Text}, so a caller that only wants
 * the text can read it and then check {@link #success()} to know whether to use it. A read never
 * writes the in-process buffer: pasting what was read is the application's decision, so an
 * application that wants both issues its own copy command.</p>
 */
public sealed interface ClipboardReadResult {

    /** The selection that was asked about. */
    ClipboardSelection selection();

    /** The transport the request was written on, or {@link ClipboardPath#NONE} when nothing was. */
    ClipboardPath path();

    /**
     * The reported text.
     *
     * @return the text for {@link Text}, and an empty string for every other outcome
     */
    default String text() {
        return "";
    }

    /**
     * Whether the terminal reported text.
     *
     * @return true for {@link Text}, including a clipboard the terminal reported as empty
     */
    default boolean success() {
        return false;
    }

    /**
     * The text the terminal reported.
     *
     * @param selection the selection that was asked about
     * @param path the transport the request was written on
     * @param text the reported text, which is empty when the selection is empty
     * @param byteLength the UTF-8 length of {@code text}
     */
    record Text(ClipboardSelection selection, ClipboardPath path, String text, int byteLength)
            implements ClipboardReadResult {

        /**
         * Rejects a missing selection and normalizes the reported values.
         */
        public Text {
            selection = require(selection);
            path = path == null ? ClipboardPath.OSC52_DIRECT : path;
            text = text == null ? "" : text;
            byteLength = Math.max(0, byteLength);
        }

        @Override
        public boolean success() {
            return true;
        }
    }

    /**
     * The terminal declined to report the selection.
     *
     * <p>This is the answer a terminal gives when it understands the question and cannot answer it,
     * which is different from one that ignores it.</p>
     *
     * @param selection the selection that was asked about
     * @param path the transport the request was written on
     */
    record Refused(ClipboardSelection selection, ClipboardPath path) implements ClipboardReadResult {

        /**
         * Rejects a missing selection.
         */
        public Refused {
            selection = require(selection);
            path = path == null ? ClipboardPath.OSC52_DIRECT : path;
        }
    }

    /**
     * No answer arrived within the reply deadline.
     *
     * <p>A terminal that ignores the request and one that is slow look the same from here. Nothing is
     * guessed at: the clipboard may hold text, and this result says only that the session did not
     * learn what it is.</p>
     *
     * @param selection the selection that was asked about
     * @param path the transport the request was written on
     */
    record Timeout(ClipboardSelection selection, ClipboardPath path) implements ClipboardReadResult {

        /**
         * Rejects a missing selection.
         */
        public Timeout {
            selection = require(selection);
            path = path == null ? ClipboardPath.OSC52_DIRECT : path;
        }
    }

    /**
     * The terminal answered, but the answer could not be believed.
     *
     * <p>The payload was not strict base64, did not decode to UTF-8, named another selection, or was
     * framed in a way OSC 52 does not define. None of those bytes reach the application as text: a
     * malformed reply must never be pasted into an editor.</p>
     *
     * @param selection the selection that was asked about
     * @param path the transport the request was written on
     * @param detail what was wrong with the answer
     */
    record Invalid(ClipboardSelection selection, ClipboardPath path, String detail) implements ClipboardReadResult {

        /**
         * Rejects a missing selection and treats a missing detail as empty.
         */
        public Invalid {
            selection = require(selection);
            path = path == null ? ClipboardPath.OSC52_DIRECT : path;
            detail = detail == null ? "" : detail;
        }
    }

    /**
     * The reported text was larger than the session's read limit.
     *
     * <p>Truncating a clipboard read would hand the application a value the user does not have, so
     * the payload is refused instead. The limit is
     * {@link ClipboardConfig#maxReadPayloadBytes()}, and the same bound is applied to the framing
     * buffer that receives the reply.</p>
     *
     * @param selection the selection that was asked about
     * @param path the transport the request was written on
     * @param limitBytes the decoded limit that was exceeded
     */
    record Oversize(ClipboardSelection selection, ClipboardPath path, int limitBytes) implements ClipboardReadResult {

        /**
         * Rejects a missing selection and a negative limit.
         */
        public Oversize {
            selection = require(selection);
            path = path == null ? ClipboardPath.OSC52_DIRECT : path;
            limitBytes = Math.max(0, limitBytes);
        }
    }

    /**
     * The session cannot ask the terminal for its clipboard, so nothing was written.
     *
     * <p>OSC 52 emission is off, the configured strategy never writes an escape sequence, the terminal
     * is one that cannot interpret them, or the session has no terminal at all. The reason names
     * which, because "the terminal cannot tell me" and "this build does not ask" are different
     * problems for whoever is reading the status row.</p>
     *
     * @param selection the selection that was asked about
     * @param reason why no request was written
     */
    record Disabled(ClipboardSelection selection, String reason) implements ClipboardReadResult {

        /**
         * Rejects a missing selection and treats a missing reason as empty.
         */
        public Disabled {
            selection = require(selection);
            reason = reason == null ? "" : reason;
        }

        @Override
        public ClipboardPath path() {
            return ClipboardPath.NONE;
        }
    }

    /**
     * The session declined to ask: too many terminal queries were already outstanding.
     *
     * <p>Nothing was sent, so nothing can answer later. The bound is what keeps a component that asks
     * in a loop from writing to the terminal without limit.</p>
     *
     * @param selection the selection that was asked about
     * @param path the transport the request would have used
     * @param outstanding how many queries were already waiting for a reply
     */
    record Rejected(ClipboardSelection selection, ClipboardPath path, int outstanding) implements ClipboardReadResult {

        /**
         * Rejects a missing selection and an empty bound.
         */
        public Rejected {
            selection = require(selection);
            path = path == null ? ClipboardPath.OSC52_DIRECT : path;
            if (outstanding < 1) {
                throw new IllegalArgumentException("outstanding must be positive, was " + outstanding);
            }
        }
    }

    private static ClipboardSelection require(ClipboardSelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("selection is required");
        }
        return selection;
    }
}

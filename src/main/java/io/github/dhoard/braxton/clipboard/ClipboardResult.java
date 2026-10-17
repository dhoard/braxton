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

package io.github.dhoard.braxton.clipboard;

/**
 * The observable result of a clipboard copy.
 *
 * <p>{@code systemWriteRequested} means an escape sequence was written. It is <strong>not</strong>
 * evidence that the terminal or tmux accepted the text: OSC 52 has no acknowledgement. The
 * in-process buffer remains the dependable destination.</p>
 */
public record ClipboardResult(
        String text,
        int byteLength,
        boolean truncated,
        boolean storedInternally,
        boolean systemWriteRequested,
        boolean deduplicated,
        ClipboardPath path) {

    public ClipboardResult {
        text = text == null ? "" : text;
        byteLength = Math.max(0, byteLength);
        path = path == null ? ClipboardPath.NONE : path;
    }

    public static ClipboardResult empty() {
        return new ClipboardResult("", 0, false, false, false, false, ClipboardPath.NONE);
    }

    /** Whether the in-process buffer is the only reliable destination for this copy. */
    public boolean internalOnly() {
        return path == ClipboardPath.INTERNAL_ONLY || path == ClipboardPath.NONE;
    }
}

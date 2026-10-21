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

package examples.harness.ai;

import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.widget.EditorAction;

/** Application-owned messages. A sealed hierarchy keeps dispatch exhaustive and typed. */
public sealed interface HarnessMsg {

    /** The classpath transcript finished loading. */
    public record TranscriptLoaded(HarnessTranscript transcript) implements HarnessMsg {
    }

    /** Loading failed; the deterministic fallback remains available. */
    public record TranscriptFailed(Throwable failure) implements HarnessMsg {
    }

    /** One editor gesture, mapped from the composer view. */
    public record Editor(EditorAction action) implements HarnessMsg {
    }

    /** A submission request (used by the editor intent, not by any widget callback). */
    public record Submit(String text) implements HarnessMsg {
        public Submit {
            text = text == null ? "" : text;
        }
    }

    /** Periodic time update; carries the runtime's monotonic clock. */
    public record Tick(long nowNanos) implements HarnessMsg {
    }

    /** Terminal geometry changed. */
    public record Resized(Size size) implements HarnessMsg {
    }

    public record ScrollUp() implements HarnessMsg {
    }

    public record ScrollDown() implements HarnessMsg {
    }

    public record PageUp() implements HarnessMsg {
    }

    public record PageDown() implements HarnessMsg {
    }

    public record ScrollBottom() implements HarnessMsg {
    }

    /** One Escape press, with its capture time so the window never depends on a tick. */
    public record Escape(long atNanos) implements HarnessMsg {
    }

    /** One Ctrl+C press with its capture time; two within two seconds exit. */
    public record QuitRequest(long atNanos) implements HarnessMsg {
    }

    /** A scheduled mock response block; only the matching generation and ordinal is accepted. */
    public record ResponseBlock(long generation, int ordinal) implements HarnessMsg {
    }

    /** The clipboard reported what it actually stored and wrote. */
    public record Copied(ClipboardResult result) implements HarnessMsg {
    }

    /** Copy the visible transcript as plain text (Alt+T). */
    public record CopyVisible() implements HarnessMsg {
    }

    /** Copy the most recent assistant reply (Alt+L). */
    public record CopyLastReply() implements HarnessMsg {
    }

    /** Tab asks for the composer to be focused. */
    public record FocusComposer() implements HarnessMsg {
    }

    /**
     * A key the composer did not consume. Routing changes state only through messages, so this is
     * what lets any other key cancel a pending time-based confirmation.
     */
    public record UnhandledKey() implements HarnessMsg {
    }

    /** A non-fatal notice, such as an overload rejection. */
    public record Notice(String text) implements HarnessMsg {
    }

    /** Request application exit. */
    public record Quit() implements HarnessMsg {
    }
}

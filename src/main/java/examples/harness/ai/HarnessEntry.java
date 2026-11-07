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

/**
 * One visible block in the AI Harness transcript.
 *
 * <p>The model stores only presentation-ready data. Tool calls arrive pre-formatted from the
 * transcript reader so the view does not need to parse JSON.</p>
 */
public sealed interface HarnessEntry {

    /**
     * When this entry entered the session, in Unix milliseconds, or zero when it carries no time.
     * The header renders it when it is positive; a fixture record with no recorded time therefore
     * shows its label alone rather than an invented time.
     */
    long epochMillis();

    /** Returns this entry with the supplied time, or with no time when it is not positive. */
    default HarnessEntry at(long millis) {
        long time = Math.max(0L, millis);
        return switch (this) {
            case User user -> new User(user.text(), time);
            case Assistant assistant -> new Assistant(assistant.text(), time);
            case Reasoning reasoning -> new Reasoning(reasoning.text(), time);
            case Tool tool -> new Tool(tool.call(), tool.result(), tool.truncated(), tool.status(), time);
            case Notice notice -> new Notice(notice.text(), time);
            case Failure failure -> new Failure(failure.text(), time);
        };
    }

    /** A prompt the user submitted. */
    public record User(String text, long epochMillis) implements HarnessEntry {
        public User(String text) {
            this(text, 0L);
        }

        public User {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /** Assistant prose. */
    public record Assistant(String text, long epochMillis) implements HarnessEntry {
        public Assistant(String text) {
            this(text, 0L);
        }

        public Assistant {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /** Provider reasoning, rendered in the thinking style. */
    public record Reasoning(String text, long epochMillis) implements HarnessEntry {
        public Reasoning(String text) {
            this(text, 0L);
        }

        public Reasoning {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /** A completed or pending tool call with a bounded preview of its result. */
    public record Tool(
            String call, String result, boolean truncated, ToolStatus status, long epochMillis)
            implements HarnessEntry {

        public Tool(String call, String result, boolean truncated, ToolStatus status) {
            this(call, result, truncated, status, 0L);
        }

        public Tool {
            call = call == null ? "" : call;
            result = result == null ? "" : result;
            status = status == null ? ToolStatus.SUCCESS : status;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /** A system notice, such as the "mock data" reminder or a fixture warning. */
    public record Notice(String text, long epochMillis) implements HarnessEntry {
        public Notice(String text) {
            this(text, 0L);
        }

        public Notice {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /** A failed turn or a transcript-loading problem. */
    public record Failure(String text, long epochMillis) implements HarnessEntry {
        public Failure(String text) {
            this(text, 0L);
        }

        public Failure {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /** Visual state of a tool call. */
    public enum ToolStatus {
        PENDING,
        SUCCESS,
        ERROR
    }
}

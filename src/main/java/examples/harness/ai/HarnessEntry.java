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
     *
     * @return when this entry entered the session, in Unix milliseconds, or zero when it carries no time
     */
    long epochMillis();

    /**
     * Returns this entry with the supplied time, or with no time when it is not positive.
     *
     * @param millis the timestamp in milliseconds
     * @return this entry with the supplied time, or with no time when it is not positive
     */
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

    /**
     * A prompt the user submitted.
     *
     * @param text the prompt text
     * @param epochMillis when the message entered the session, or zero
     */
    public record User(String text, long epochMillis) implements HarnessEntry {
        /**
         * Creates a {@code User} value.
         *
         * @param text the prompt text
         */
        public User(String text) {
            this(text, 0L);
        }

        /**
         * Creates a {@code User} value.
         *
         * @param text the prompt text
         * @param epochMillis when the message entered the session, or zero
         */
        public User {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /**
     * Assistant prose.
     *
     * @param text the reply text
     * @param epochMillis when the message entered the session, or zero
     */
    public record Assistant(String text, long epochMillis) implements HarnessEntry {
        /**
         * Creates a {@code Assistant} value.
         *
         * @param text the reply text
         */
        public Assistant(String text) {
            this(text, 0L);
        }

        /**
         * Creates a {@code Assistant} value.
         *
         * @param text the reply text
         * @param epochMillis when the message entered the session, or zero
         */
        public Assistant {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /**
     * Provider reasoning, rendered in the thinking style.
     *
     * @param text the reasoning text
     * @param epochMillis when the message entered the session, or zero
     */
    public record Reasoning(String text, long epochMillis) implements HarnessEntry {
        /**
         * Creates a {@code Reasoning} value.
         *
         * @param text the reasoning text
         */
        public Reasoning(String text) {
            this(text, 0L);
        }

        /**
         * Creates a {@code Reasoning} value.
         *
         * @param text the reasoning text
         * @param epochMillis when the message entered the session, or zero
         */
        public Reasoning {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /**
     * A completed or pending tool call with a bounded preview of its result.
     *
     * @param call the tool name and arguments as one display line
     * @param result the preview of the tool's output
     * @param truncated whether the preview was cut short
     * @param status whether the call completed, failed, or is still pending
     * @param epochMillis when the message entered the session, or zero
     */
    public record Tool(String call, String result, boolean truncated, ToolStatus status, long epochMillis)
            implements HarnessEntry {

        /**
         * Creates a {@code Tool} value.
         *
         * @param call the tool name and arguments as one display line
         * @param result the preview of the tool's output
         * @param truncated whether the preview was cut short
         * @param status whether the call completed, failed, or is still pending
         */
        public Tool(String call, String result, boolean truncated, ToolStatus status) {
            this(call, result, truncated, status, 0L);
        }

        /**
         * Creates a {@code Tool} value.
         *
         * @param call the tool name and arguments as one display line
         * @param result the preview of the tool's output
         * @param truncated whether the preview was cut short
         * @param status whether the call completed, failed, or is still pending
         * @param epochMillis when the message entered the session, or zero
         */
        public Tool {
            call = call == null ? "" : call;
            result = result == null ? "" : result;
            status = status == null ? ToolStatus.SUCCESS : status;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /**
     * A system notice, such as the "mock data" reminder or a fixture warning.
     *
     * @param text the notice text
     * @param epochMillis when the message entered the session, or zero
     */
    public record Notice(String text, long epochMillis) implements HarnessEntry {
        /**
         * Creates a {@code Notice} value.
         *
         * @param text the notice text
         */
        public Notice(String text) {
            this(text, 0L);
        }

        /**
         * Creates a {@code Notice} value.
         *
         * @param text the notice text
         * @param epochMillis when the message entered the session, or zero
         */
        public Notice {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /**
     * A failed turn or a transcript-loading problem.
     *
     * @param text the diagnostic text
     * @param epochMillis when the message entered the session, or zero
     */
    public record Failure(String text, long epochMillis) implements HarnessEntry {
        /**
         * Creates a {@code Failure} value.
         *
         * @param text the diagnostic text
         */
        public Failure(String text) {
            this(text, 0L);
        }

        /**
         * Creates a {@code Failure} value.
         *
         * @param text the diagnostic text
         * @param epochMillis when the message entered the session, or zero
         */
        public Failure {
            text = text == null ? "" : text;
            epochMillis = Math.max(0L, epochMillis);
        }
    }

    /**
     * Visual state of a tool call.
     */
    public enum ToolStatus {
        /**
         * The tool result has not yet arrived.
         */
        PENDING,
        /**
         * The tool completed successfully.
         */
        SUCCESS,
        /**
         * The tool completed with an error.
         */
        ERROR
    }
}

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

    /** A prompt the user submitted. */
    public record User(String text) implements HarnessEntry {
        public User {
            text = text == null ? "" : text;
        }
    }

    /** Assistant prose. */
    public record Assistant(String text) implements HarnessEntry {
        public Assistant {
            text = text == null ? "" : text;
        }
    }

    /** Provider reasoning, rendered in the thinking style. */
    public record Reasoning(String text) implements HarnessEntry {
        public Reasoning {
            text = text == null ? "" : text;
        }
    }

    /** A completed or pending tool call with a bounded preview of its result. */
    public record Tool(String call, String result, boolean truncated, ToolStatus status)
            implements HarnessEntry {

        public Tool {
            call = call == null ? "" : call;
            result = result == null ? "" : result;
            status = status == null ? ToolStatus.SUCCESS : status;
        }
    }

    /** A system notice, such as the "mock data" reminder or a fixture warning. */
    public record Notice(String text) implements HarnessEntry {
        public Notice {
            text = text == null ? "" : text;
        }
    }

    /** A failed turn or a transcript-loading problem. */
    public record Failure(String text) implements HarnessEntry {
        public Failure {
            text = text == null ? "" : text;
        }
    }

    /** Visual state of a tool call. */
    public enum ToolStatus {
        PENDING,
        SUCCESS,
        ERROR
    }
}

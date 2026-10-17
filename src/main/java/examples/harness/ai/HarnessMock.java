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

import java.util.List;

/**
 * Deterministic mock responder.
 *
 * <p>Pure selection: the replay position lives in {@link HarnessState}, not in this class. Once the
 * bundled turns are exhausted, the fallback builds a fixed response from the submitted text. No
 * model, filesystem, network, or command execution happens here.</p>
 */
final class HarnessMock {

    private HarnessMock() {
    }

    /** The next turn for {@code prompt}: the recorded turn at the replay index, or a fallback. */
    static HarnessTranscript.HarnessTurn turnFor(HarnessState state, String prompt) {
        List<HarnessTranscript.HarnessTurn> turns = state.turns();
        int index = state.replayIndex();
        if (index >= 0 && index < turns.size()) {
            return turns.get(index);
        }
        return fallback(prompt, state.profile());
    }

    static HarnessTranscript.HarnessTurn fallback(String prompt,
            HarnessTranscript.Profile profile) {
        String safePrompt = HarnessRender.sanitize(prompt).strip();
        String quoted = "\"" + HarnessRender.truncate(safePrompt, 80) + "\"";
        String reasoning = "No model is attached. I am rendering a deterministic mock response for "
                + quoted + ".";
        String result = """
                Program          holds immutable state and pure update/view
                Editor           owns the draft and emits submit/copy/paste intents
                View             describes layout; layout paints cells
                TerminalSession  owns raw mode and cancellable input
                """;
        HarnessEntry.Tool tool = new HarnessEntry.Tool(
                "read src/main/java/examples/harness/ai/HarnessProgram.java",
                result, false, HarnessEntry.ToolStatus.SUCCESS);
        String assistant = switch (Math.floorMod(safePrompt.hashCode(), 3)) {
            case 0 -> "You said: " + quoted + "\n\n"
                    + "This is mock data, not a model response. The showcase is one MVU program: "
                    + "no widget owns application state and no network call was made.";
            case 1 -> "Mock response for " + quoted + ".\n\n"
                    + "The harness is deterministic on purpose: the same prompt always produces "
                    + "this same transcript block, so the layout is easy to inspect.";
            default -> "Acknowledged: " + quoted + ".\n\n"
                    + "Try another prompt, scroll the transcript with Up/Down, or press Ctrl+C "
                    + "to exit. The example transcript is loaded from the classpath.";
        };
        long input = 800L + safePrompt.length() / 4L;
        long output = 80L + assistant.length() / 4L;
        HarnessTranscript.Usage usage = new HarnessTranscript.Usage(
                input, output, 120, input, input + output, 64.0);
        return new HarnessTranscript.HarnessTurn("",
                List.of(new HarnessEntry.Reasoning(reasoning), tool,
                        new HarnessEntry.Assistant(assistant)),
                usage, profile);
    }
}

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
 * The parsed example transcript and the display metadata read from its records.
 *
 * <p>A turn keeps its example user text separate from its response entries. The program seeds the
 * first turn and, when the user submits later prompts, replays a later turn's response entries under
 * the user's actual text.</p>
 */
public record HarnessTranscript(
        List<HarnessTurn> turns,
        Profile profile,
        Usage usage,
        int contextWindow,
        List<String> warnings) {

    public HarnessTranscript {
        turns = List.copyOf(turns);
        warnings = List.copyOf(warnings);
        profile = profile == null ? Profile.DEFAULT : profile;
        usage = usage == null ? Usage.EMPTY : usage;
        contextWindow = contextWindow <= 0 ? Profile.DEFAULT_CONTEXT_WINDOW : contextWindow;
    }

    /** One example turn: the prompt it was recorded under and the response blocks. */
    public record HarnessTurn(
            String userText,
            List<HarnessEntry> responses,
            Usage usage,
            Profile profile) {

        public HarnessTurn {
            userText = userText == null ? "" : userText;
            responses = List.copyOf(responses);
            usage = usage == null ? Usage.EMPTY : usage;
        }
    }

    /** Provider/model identity shown in the footer and used by mock fallbacks. */
    public record Profile(String provider, String model, String reasoning) {

        public static final int DEFAULT_CONTEXT_WINDOW = 1_048_576;
        public static final Profile DEFAULT =
                new Profile("amanda", "qwen3.8-flash-next", "high");

        public Profile {
            provider = blankToDefault(provider, "amanda");
            model = blankToDefault(model, "mock-harness");
            reasoning = reasoning == null ? "" : reasoning;
        }

        /** The footer's provider/model line, including the reasoning suffix when present. */
        public String label() {
            return provider + " | " + model
                    + (reasoning.isBlank() ? "" : " \u2022 " + reasoning);
        }

        private static String blankToDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    /** Token and rate observations used by the footer. */
    public record Usage(
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            long uncachedInputTokens,
            long contextTokens,
            Double generationRate) {

        public static final Usage EMPTY = new Usage(0, 0, 0, 0, 0, null);

        public Usage {
            inputTokens = Math.max(0, inputTokens);
            outputTokens = Math.max(0, outputTokens);
            cachedInputTokens = Math.max(0, cachedInputTokens);
            uncachedInputTokens = Math.max(0, uncachedInputTokens);
            contextTokens = Math.max(0, contextTokens);
            if (generationRate != null && (generationRate < 0 || !Double.isFinite(generationRate))) {
                generationRate = null;
            }
        }
    }

    public static HarnessTranscript empty(List<String> warnings) {
        return new HarnessTranscript(List.of(), Profile.DEFAULT, Usage.EMPTY,
                Profile.DEFAULT_CONTEXT_WINDOW, warnings);
    }
}

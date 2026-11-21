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

import java.util.List;

/**
 * The parsed example transcript and the display metadata read from its records.
 *
 * <p>A turn keeps its example user text separate from its response entries. The program seeds the
 * first turn and, when the user submits later prompts, replays a later turn's response entries under
 * the user's actual text.</p>
 *
 * @param turns the parsed turns, in recorded order
 * @param profile the provider/model identity shown in the footer
 * @param usage the token and rate observations shown in the footer
 * @param contextWindow the context window used for the context gauge
 * @param warnings reader diagnostics, one per skipped or malformed record
 */
public record HarnessTranscript(
        List<HarnessTurn> turns, Profile profile, Usage usage, int contextWindow, List<String> warnings) {

    /**
     * Creates a {@code HarnessTranscript} value.
     *
     * @param turns the parsed turns, in recorded order
     * @param profile the provider/model identity shown in the footer
     * @param usage the token and rate observations shown in the footer
     * @param contextWindow the context window used for the context gauge
     * @param warnings reader diagnostics, one per skipped or malformed record
     */
    public HarnessTranscript {
        turns = List.copyOf(turns);
        warnings = List.copyOf(warnings);
        profile = profile == null ? Profile.DEFAULT : profile;
        usage = usage == null ? Usage.EMPTY : usage;
        contextWindow = contextWindow <= 0 ? Profile.DEFAULT_CONTEXT_WINDOW : contextWindow;
    }

    /**
     * One example turn: the prompt it was recorded under and the response blocks.
     *
     * @param userText the recorded prompt, or empty
     * @param responses the response blocks to replay, in order
     * @param usage the token observations for this turn
     * @param profile the provider/model identity reported for this turn
     */
    public record HarnessTurn(String userText, List<HarnessEntry> responses, Usage usage, Profile profile) {

        /**
         * Creates a {@code HarnessTurn} value.
         *
         * @param userText the recorded prompt, or empty
         * @param responses the response blocks to replay, in order
         * @param usage the token observations for this turn
         * @param profile the provider/model identity reported for this turn
         */
        public HarnessTurn {
            userText = userText == null ? "" : userText;
            responses = List.copyOf(responses);
            usage = usage == null ? Usage.EMPTY : usage;
        }
    }

    /**
     * The session the bundled example transcript was recorded from, shown at the right edge of the
     * frame's identity line. The fixture is a slice of that session, so the identifier is data about
     * the example, not a live session this process opened.
     */
    public static final String EXAMPLE_SESSION_ID = "01a14c5c-c2b7-756e-9291-62d0bed736a4";

    /**
     * The example's own version, shown beside its name in the frame's identity line. It versions
     * the showcase, not the library the showcase is built on.
     */
    public static final String EXAMPLE_VERSION = "1.2.3";

    /**
     * Provider/model identity shown in the footer and used by mock fallbacks.
     *
     * @param provider the provider name; blank becomes {@code local}
     * @param model the model name; blank becomes the example default
     * @param reasoning the reasoning effort suffix, or empty
     */
    public record Profile(String provider, String model, String reasoning) {

        /**
         * The context window assumed when the transcript names none: 1,048,576 tokens.
         */
        public static final int DEFAULT_CONTEXT_WINDOW = 1_048_576;

        /**
         * The profile an unnamed transcript is shown under: the local Qwen model at high reasoning
         * effort.
         */
        public static final Profile DEFAULT = new Profile("local", "qwen3.8-flash-next", "high");

        /**
         * Creates a {@code Profile} value.
         *
         * @param provider the provider name; blank becomes {@code local}
         * @param model the model name; blank becomes the example default
         * @param reasoning the reasoning effort suffix, or empty
         */
        public Profile {
            provider = blankToDefault(provider, "local");
            model = blankToDefault(model, "mock-harness");
            reasoning = reasoning == null ? "" : reasoning;
        }

        /**
         * The footer's provider/model line, including the reasoning suffix when present.
         *
         * @return the footer's provider/model line, including the reasoning suffix when present
         */
        public String label() {
            return provider + " | " + model + (reasoning.isBlank() ? "" : " \u2022 " + reasoning);
        }

        private static String blankToDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    /**
     * Token and rate observations used by the footer.
     *
     * @param inputTokens input tokens reported for the session
     * @param outputTokens output tokens reported for the session
     * @param cachedInputTokens the part of the input that was cached
     * @param uncachedInputTokens the part of the input that was not cached
     * @param contextTokens tokens currently occupying the context window
     * @param generationRate tokens per second, or null when unreported
     */
    public record Usage(
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            long uncachedInputTokens,
            long contextTokens,
            Double generationRate) {

        /**
         * A usage record with no tokens spent and no measured rate.
         */
        public static final Usage EMPTY = new Usage(0, 0, 0, 0, 0, null);

        /**
         * Creates a {@code Usage} value.
         *
         * @param inputTokens input tokens reported for the session
         * @param outputTokens output tokens reported for the session
         * @param cachedInputTokens the part of the input that was cached
         * @param uncachedInputTokens the part of the input that was not cached
         * @param contextTokens tokens currently occupying the context window
         * @param generationRate tokens per second, or null when unreported
         */
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

    /**
     * A transcript with no turns and the default display metadata.
     *
     * @param warnings reader diagnostics to carry
     * @return an empty transcript
     */
    public static HarnessTranscript empty(List<String> warnings) {
        return new HarnessTranscript(List.of(), Profile.DEFAULT, Usage.EMPTY, Profile.DEFAULT_CONTEXT_WINDOW, warnings);
    }
}

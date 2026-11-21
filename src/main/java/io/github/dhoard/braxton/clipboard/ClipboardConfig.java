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
 * Immutable clipboard configuration.
 *
 * <p>Configuration can be supplied programmatically through the builder or, for applications that
 * prefer deployment-time settings, through these system properties:</p>
 *
 * <ul>
 *   <li>{@code braxton.clipboard.enabled} — {@code false} disables OSC 52 emission</li>
 *   <li>{@code braxton.clipboard.maxPayloadBytes} — UTF-8 byte limit for one OSC 52 write</li>
 *   <li>{@code braxton.clipboard.maxInternalChars} — in-process clipboard buffer limit</li>
 *   <li>{@code braxton.clipboard.readEnabled} — {@code false} refuses to ask the terminal for
 *       its clipboard</li>
 *   <li>{@code braxton.clipboard.maxReadPayloadBytes} — decoded byte limit for one OSC 52 read</li>
 *   <li>{@code braxton.clipboard.tmux} — {@code auto}, {@code direct}, {@code tmux_native},
 *       {@code tmux_passthrough}, or {@code off}</li>
 * </ul>
 */
public final class ClipboardConfig {

    /**
     * Default maximum UTF-8 payload for a single OSC 52 write (64 KiB).
     */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 65_536;

    /**
     * Default bound for the in-process clipboard buffer (1 Mi chars).
     */
    public static final int DEFAULT_MAX_INTERNAL_CHARS = 1_048_576;

    /**
     * Default maximum decoded payload for a single OSC 52 read (1 MiB).
     */
    public static final int DEFAULT_MAX_READ_PAYLOAD_BYTES = 1_048_576;

    /**
     * The default tmux OSC 52 forwarding policy.
     */
    public static final TmuxStrategy DEFAULT_TMUX_STRATEGY = TmuxStrategy.AUTO;

    private final boolean osc52Enabled;
    private final int maxPayloadBytes;
    private final int maxInternalChars;
    private final boolean terminalReadsEnabled;
    private final int maxReadPayloadBytes;
    private final TmuxStrategy tmuxStrategy;

    private ClipboardConfig(Builder builder) {
        this.osc52Enabled = builder.osc52Enabled;
        this.maxPayloadBytes = Math.max(1, builder.maxPayloadBytes);
        this.maxInternalChars = Math.max(1, builder.maxInternalChars);
        this.terminalReadsEnabled = builder.terminalReadsEnabled;
        this.maxReadPayloadBytes = Math.max(1, builder.maxReadPayloadBytes);
        this.tmuxStrategy = builder.tmuxStrategy == null ? DEFAULT_TMUX_STRATEGY : builder.tmuxStrategy;
    }

    /**
     * Returns the default configuration.
     *
     * @return the default configuration
     */
    public static ClipboardConfig defaults() {
        return builder().build();
    }

    /**
     * Returns a new builder seeded with the defaults.
     *
     * @return this builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder seeded with this configuration.
     *
     * @return this builder
     */
    public Builder toBuilder() {
        return new Builder()
                .osc52Enabled(osc52Enabled)
                .maxPayloadBytes(maxPayloadBytes)
                .maxInternalChars(maxInternalChars)
                .terminalReadsEnabled(terminalReadsEnabled)
                .maxReadPayloadBytes(maxReadPayloadBytes)
                .tmuxStrategy(tmuxStrategy);
    }

    /**
     * Reads configuration from system properties. Missing or invalid values fall back to the
     * defaults instead of failing application startup.
     *
     * @return the configuration resolved from system properties
     */
    public static ClipboardConfig fromSystemProperties() {
        Builder builder = builder();
        builder.osc52Enabled(booleanProperty("braxton.clipboard.enabled", true));
        builder.maxPayloadBytes(integerProperty("braxton.clipboard.maxPayloadBytes", DEFAULT_MAX_PAYLOAD_BYTES));
        builder.maxInternalChars(integerProperty("braxton.clipboard.maxInternalChars", DEFAULT_MAX_INTERNAL_CHARS));
        builder.terminalReadsEnabled(booleanProperty("braxton.clipboard.readEnabled", true));
        builder.maxReadPayloadBytes(
                integerProperty("braxton.clipboard.maxReadPayloadBytes", DEFAULT_MAX_READ_PAYLOAD_BYTES));
        builder.tmuxStrategy(TmuxStrategy.parse(property("braxton.clipboard.tmux"), DEFAULT_TMUX_STRATEGY));
        return builder.build();
    }

    /**
     * Whether OSC 52 writes may be emitted. Internal storage is unaffected.
     *
     * @return {@code true} if oSC 52 writes may be emitted; otherwise {@code false}
     */
    public boolean osc52Enabled() {
        return osc52Enabled;
    }

    /**
     * Maximum UTF-8 bytes encoded into one OSC 52 payload.
     *
     * @return maximum UTF-8 bytes encoded into one OSC 52 payload
     */
    public int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    /**
     * Maximum characters retained by the in-process clipboard buffer.
     *
     * @return maximum characters retained by the in-process clipboard buffer
     */
    public int maxInternalChars() {
        return maxInternalChars;
    }

    /**
     * Whether the session may ask the terminal for its clipboard.
     *
     * <p>Asking is already opt-in because it takes an explicit read command; this switch exists for a
     * deployment that wants the question never asked at all. Turning it off does not affect copying,
     * which keeps the in-process buffer as its dependable destination.</p>
     *
     * @return {@code true} if the session may ask the terminal for its clipboard; otherwise {@code false}
     */
    public boolean terminalReadsEnabled() {
        return terminalReadsEnabled;
    }

    /**
     * Maximum decoded UTF-8 bytes accepted from one OSC 52 read.
     *
     * @return maximum decoded UTF-8 bytes accepted from one OSC 52 read
     */
    public int maxReadPayloadBytes() {
        return maxReadPayloadBytes;
    }

    /**
     * The largest reply that can encode {@link #maxReadPayloadBytes()} bytes.
     *
     * <p>Base64 encodes three bytes as four characters, so a decoded bound has an exact encoded
     * bound: four characters per whole or partial group of three. The selection field and its
     * separator are added on top, because the reply carries them inside the same string. The input
     * decoder uses this as its framing bound for OSC 52, so a reply that could decode within the
     * limit is never dropped before it can be checked.</p>
     *
     * @return the maximum encoded clipboard reply length in characters
     */
    public int maxReadEncodedCharacters() {
        // Computed as a long and saturated: a caller may configure an absurd byte limit, and a bound
        // that wrapped negative would turn every reply into an oversize one instead of failing the
        // comparison it exists for.
        long encoded = 2L + 4L * ((maxReadPayloadBytes + 2L) / 3L);
        return (int) Math.min(Integer.MAX_VALUE, encoded);
    }

    /**
     * Transport strategy for OSC 52 writes.
     *
     * @return transport strategy for OSC 52 writes
     */
    public TmuxStrategy tmuxStrategy() {
        return tmuxStrategy;
    }

    private static String property(String name) {
        try {
            return System.getProperty(name);
        } catch (SecurityException denied) {
            return null;
        }
    }

    private static boolean booleanProperty(String name, boolean fallback) {
        String value = property(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.strip();
        if ("true".equalsIgnoreCase(normalized)) {
            return true;
        }
        if ("false".equalsIgnoreCase(normalized)) {
            return false;
        }
        return fallback;
    }

    private static int integerProperty(String name, int fallback) {
        String value = property(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.strip()));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /**
     * Fluent builder for {@link ClipboardConfig}.
     */
    public static final class Builder {
        private boolean osc52Enabled = true;
        private int maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES;
        private int maxInternalChars = DEFAULT_MAX_INTERNAL_CHARS;
        private boolean terminalReadsEnabled = true;
        private int maxReadPayloadBytes = DEFAULT_MAX_READ_PAYLOAD_BYTES;
        private TmuxStrategy tmuxStrategy = DEFAULT_TMUX_STRATEGY;

        private Builder() {}

        /**
         * Sets this builder's osc52 enabled.
         *
         * @param enabled whether the feature is enabled
         * @return this builder
         */
        public Builder osc52Enabled(boolean enabled) {
            this.osc52Enabled = enabled;
            return this;
        }

        /**
         * Sets this builder's max payload bytes.
         *
         * @param bytes the byte limit
         * @return this builder
         */
        public Builder maxPayloadBytes(int bytes) {
            this.maxPayloadBytes = Math.max(1, bytes);
            return this;
        }

        /**
         * Sets this builder's max internal chars.
         *
         * @param chars the character limit
         * @return this builder
         */
        public Builder maxInternalChars(int chars) {
            this.maxInternalChars = Math.max(1, chars);
            return this;
        }

        /**
         * Sets this builder's terminal reads enabled.
         *
         * @param enabled whether the feature is enabled
         * @return this builder
         */
        public Builder terminalReadsEnabled(boolean enabled) {
            this.terminalReadsEnabled = enabled;
            return this;
        }

        /**
         * Sets this builder's max read payload bytes.
         *
         * @param bytes the byte limit
         * @return this builder
         */
        public Builder maxReadPayloadBytes(int bytes) {
            this.maxReadPayloadBytes = Math.max(1, bytes);
            return this;
        }

        /**
         * Sets this builder's tmux strategy.
         *
         * @param strategy the transport policy
         * @return this builder
         */
        public Builder tmuxStrategy(TmuxStrategy strategy) {
            this.tmuxStrategy = strategy == null ? DEFAULT_TMUX_STRATEGY : strategy;
            return this;
        }

        /**
         * Builds the immutable clipboard policy, validating its bounds.
         *
         * @return the immutable clipboard policy, validating its bounds
         */
        public ClipboardConfig build() {
            return new ClipboardConfig(this);
        }
    }
}

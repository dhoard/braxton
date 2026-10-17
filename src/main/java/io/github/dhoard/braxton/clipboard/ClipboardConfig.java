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
 * Immutable clipboard configuration.
 *
 * <p>Configuration can be supplied programmatically through the builder or, for applications that
 * prefer deployment-time settings, through these system properties:</p>
 *
 * <ul>
 *   <li>{@code braxton.clipboard.enabled} — {@code false} disables OSC 52 emission</li>
 *   <li>{@code braxton.clipboard.maxPayloadBytes} — UTF-8 byte limit for one OSC 52 write</li>
 *   <li>{@code braxton.clipboard.maxInternalChars} — in-process clipboard buffer limit</li>
 *   <li>{@code braxton.clipboard.tmux} — {@code auto}, {@code direct}, {@code tmux_native},
 *       {@code tmux_passthrough}, or {@code off}</li>
 * </ul>
 */
public final class ClipboardConfig {

    /** Default maximum UTF-8 payload for a single OSC 52 write (64 KiB). */
    public static final int DEFAULT_MAX_PAYLOAD_BYTES = 65_536;

    /** Default bound for the in-process clipboard buffer (1 Mi chars). */
    public static final int DEFAULT_MAX_INTERNAL_CHARS = 1_048_576;

    public static final TmuxStrategy DEFAULT_TMUX_STRATEGY = TmuxStrategy.AUTO;

    private final boolean osc52Enabled;
    private final int maxPayloadBytes;
    private final int maxInternalChars;
    private final TmuxStrategy tmuxStrategy;

    private ClipboardConfig(Builder builder) {
        this.osc52Enabled = builder.osc52Enabled;
        this.maxPayloadBytes = Math.max(1, builder.maxPayloadBytes);
        this.maxInternalChars = Math.max(1, builder.maxInternalChars);
        this.tmuxStrategy = builder.tmuxStrategy == null
                ? DEFAULT_TMUX_STRATEGY : builder.tmuxStrategy;
    }

    /** Returns the default configuration. */
    public static ClipboardConfig defaults() {
        return builder().build();
    }

    /** Returns a new builder seeded with the defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder seeded with this configuration. */
    public Builder toBuilder() {
        return new Builder()
                .osc52Enabled(osc52Enabled)
                .maxPayloadBytes(maxPayloadBytes)
                .maxInternalChars(maxInternalChars)
                .tmuxStrategy(tmuxStrategy);
    }

    /**
     * Reads configuration from system properties. Missing or invalid values fall back to the
     * defaults instead of failing application startup.
     */
    public static ClipboardConfig fromSystemProperties() {
        Builder builder = builder();
        builder.osc52Enabled(booleanProperty("braxton.clipboard.enabled", true));
        builder.maxPayloadBytes(integerProperty(
                "braxton.clipboard.maxPayloadBytes", DEFAULT_MAX_PAYLOAD_BYTES));
        builder.maxInternalChars(integerProperty(
                "braxton.clipboard.maxInternalChars", DEFAULT_MAX_INTERNAL_CHARS));
        builder.tmuxStrategy(TmuxStrategy.parse(
                property("braxton.clipboard.tmux"), DEFAULT_TMUX_STRATEGY));
        return builder.build();
    }

    /** Whether OSC 52 writes may be emitted. Internal storage is unaffected. */
    public boolean osc52Enabled() {
        return osc52Enabled;
    }

    /** Maximum UTF-8 bytes encoded into one OSC 52 payload. */
    public int maxPayloadBytes() {
        return maxPayloadBytes;
    }

    /** Maximum characters retained by the in-process clipboard buffer. */
    public int maxInternalChars() {
        return maxInternalChars;
    }

    /** Transport strategy for OSC 52 writes. */
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

    /** Fluent builder for {@link ClipboardConfig}. */
    public static final class Builder {
        private boolean osc52Enabled = true;
        private int maxPayloadBytes = DEFAULT_MAX_PAYLOAD_BYTES;
        private int maxInternalChars = DEFAULT_MAX_INTERNAL_CHARS;
        private TmuxStrategy tmuxStrategy = DEFAULT_TMUX_STRATEGY;

        private Builder() {
        }

        public Builder osc52Enabled(boolean enabled) {
            this.osc52Enabled = enabled;
            return this;
        }

        public Builder maxPayloadBytes(int bytes) {
            this.maxPayloadBytes = Math.max(1, bytes);
            return this;
        }

        public Builder maxInternalChars(int chars) {
            this.maxInternalChars = Math.max(1, chars);
            return this;
        }

        public Builder tmuxStrategy(TmuxStrategy strategy) {
            this.tmuxStrategy = strategy == null ? DEFAULT_TMUX_STRATEGY : strategy;
            return this;
        }

        public ClipboardConfig build() {
            return new ClipboardConfig(this);
        }
    }
}

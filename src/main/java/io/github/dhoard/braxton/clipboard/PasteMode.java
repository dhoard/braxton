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
 * How pasted text is normalized before it reaches an editor.
 *
 * <p>{@link #NORMAL} is the default because it preserves meaningful leading indentation, which
 * source code depends on. {@link #AGGRESSIVE} is intended for prose, copied terminal output, and
 * prompt text where copied padding is never meaningful.</p>
 */
public enum PasteMode {
    /**
     * Removes ANSI/control artifacts, normalizes line endings, trims trailing whitespace from every
     * line, removes outer blank lines, and preserves leading indentation and tabs.
     */
    NORMAL,

    /**
     * Applies every {@link #NORMAL} transformation, then trims both ends of every line, removes
     * additional invisible formatting characters, and collapses runs of blank lines.
     */
    AGGRESSIVE;

    /**
     * Parses {@code normal} or {@code aggressive} case-insensitively.
     *
     * @return the matching mode, or {@code fallback} for a missing or unknown value
     */
    public static PasteMode parse(String value, PasteMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.strip();
        for (PasteMode mode : values()) {
            if (mode.name().equalsIgnoreCase(normalized)) {
                return mode;
            }
        }
        return fallback;
    }

    /**
     * The mode configured by the {@code braxton.paste.mode} system property, defaulting to
     * {@link #NORMAL}.
     */
    public static PasteMode configuredDefault() {
        try {
            return parse(System.getProperty("braxton.paste.mode"), NORMAL);
        } catch (SecurityException denied) {
            return NORMAL;
        }
    }
}

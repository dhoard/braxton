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

package io.github.dhoard.braxton.image;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Positive image support evidence. An application may supply the results of its own probes.
 *
 * @param kitty whether the terminal supports the Kitty graphics protocol
 * @param iterm2 whether the terminal supports iTerm2 inline images
 * @param sixel whether the terminal supports Sixel graphics
 */
public record ImageCapabilities(boolean kitty, boolean iterm2, boolean sixel) {
    /**
     * No image protocol: the terminal can only be sent text.
     */
    public static final ImageCapabilities NONE = new ImageCapabilities(false, false, false);

    /**
     * Selects the richest supported format, otherwise the portable text fallback.
     *
     * @return the richest supported format, or the portable text fallback
     */
    public ImageProtocol protocol() {
        if (kitty) return ImageProtocol.KITTY;
        if (iterm2) return ImageProtocol.ITERM2;
        return sixel ? ImageProtocol.SIXEL : ImageProtocol.HALF_BLOCK;
    }

    /**
     * Uses explicit identities only. Multiplexers default to text without probe evidence.
     *
     * @param environment the terminal environment values
     * @return capabilities derived from explicit terminal identities
     */
    public static ImageCapabilities detect(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return detect(environment.get("TERM"), environment);
    }

    /**
     * Infers support for a particular session rather than assuming the host's TERM applies.
     *
     * @param term the terminal identity from TERM
     * @param environment the terminal environment values
     * @return the capabilities supported by the supplied session identity
     */
    public static ImageCapabilities detect(String term, Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String name = term == null ? "" : term.toLowerCase(Locale.ROOT);
        if (name.isEmpty()
                || name.equals("dumb")
                || name.startsWith("screen")
                || name.startsWith("tmux")
                || !value(environment, "TMUX").isEmpty()
                || !value(environment, "STY").isEmpty()) return NONE;
        String program = value(environment, "TERM_PROGRAM");
        return new ImageCapabilities(
                name.equals("xterm-kitty") || name.equals("kitty"), program.equals("iTerm.app"), false);
    }

    private static String value(Map<String, String> environment, String key) {
        return Objects.toString(environment.get(key), "");
    }

    /**
     * Adds Sixel support only for feature 4 in a complete primary device-attributes reply.
     * This does not send a query or consume application input.
     *
     * @param reply the terminal capability reply
     * @return the value with the requested device attributes
     */
    public ImageCapabilities withDeviceAttributes(String reply) {
        if (reply == null || reply.length() > 256 || !reply.matches("\\x1b\\[\\?[0-9]+(?:;[0-9]+)*c")) {
            return this;
        }
        String[] parameters = reply.substring(3, reply.length() - 1).split(";");
        for (int i = 1; i < parameters.length; i++) {
            if (parameters[i].matches("0*4")) return new ImageCapabilities(kitty, iterm2, true);
        }
        return this;
    }
}

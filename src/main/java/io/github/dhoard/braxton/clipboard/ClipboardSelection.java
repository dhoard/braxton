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
 * A clipboard the terminal can address, named as OSC 52 names it.
 *
 * <p>The protocol has more selections than this library exposes - {@code 0} to {@code 7}, {@code s}
 * for the selection being typed into - but only these two are meaningful to a terminal application:
 * the system clipboard every platform has, and the primary selection X11 and Wayland keep for the
 * text that was last selected. A window manager that does not own a primary selection simply never
 * answers a read of it, which is reported as a timeout rather than as an empty clipboard.</p>
 *
 * @see <a href="https://invisible-island.net/xterm/ctlseqs/ctlseqs.html">the xterm control
 *     sequences reference</a>, {@code OSC 52}
 */
public enum ClipboardSelection {

    /** The system clipboard, OSC 52 selection {@code c}. Every supported platform has one. */
    SYSTEM('c'),

    /** The X11/Wayland primary selection, OSC 52 selection {@code p}. */
    PRIMARY('p');

    private final char code;

    ClipboardSelection(char code) {
        this.code = code;
    }

    /** The single character OSC 52 uses to name this selection. */
    public char code() {
        return code;
    }

    /**
     * The selection an OSC 52 selection character names.
     *
     * @param code the selection character
     * @return the selection, or null for a character this library does not address
     */
    public static ClipboardSelection of(char code) {
        for (ClipboardSelection selection : values()) {
            if (selection.code == code) {
                return selection;
            }
        }
        return null;
    }

    /**
     * Parses a selection name or character case-insensitively.
     *
     * @param value {@code system}, {@code primary}, {@code c} or {@code p}
     * @param fallback the value for a missing or unknown name
     * @return the matching selection, or {@code fallback}
     */
    public static ClipboardSelection parse(String value, ClipboardSelection fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.strip();
        for (ClipboardSelection selection : values()) {
            if (selection.name().equalsIgnoreCase(normalized) || normalized.equals(String.valueOf(selection.code))) {
                return selection;
            }
        }
        return fallback;
    }

    /** The selection name a status row and a configuration value spell it with. */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}

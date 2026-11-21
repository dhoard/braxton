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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.style.ThemeToken;

/**
 * How urgent a notification is.
 *
 * <p>{@link Toast} and {@link NotificationCenter} both carry one, and both paint it the same way:
 * the severity's {@link #marker()} in the theme role its {@link #token()} names, so an application
 * that raises a warning and an application that raises an error are told apart by the same two
 * cells in the same two widgets.</p>
 *
 * <p>The marker is one display column, because a notification row spends most of its width on the
 * message it carries; it is a glyph rather than a word so a row of them reads as a list.</p>
 */
public enum Severity {

    /**
     * Something the reader should know, but nothing that went wrong.
     */
    INFO("\u2022", ThemeToken.ACCENT),

    /**
     * Something an action accomplished.
     */
    SUCCESS("\u2714", ThemeToken.SUCCESS),

    /**
     * Something that worked but needs attention.
     */
    WARNING("!", ThemeToken.WARNING),

    /**
     * Something that failed.
     */
    ERROR("\u2716", ThemeToken.ERROR);

    private final String marker;

    private final ThemeToken token;

    Severity(String marker, ThemeToken token) {
        this.marker = marker;
        this.token = token;
    }

    /**
     * The one-column glyph painted in front of the message.
     *
     * @return the marker
     */
    public String marker() {
        return marker;
    }

    /**
     * The theme role the marker of this severity is painted in.
     *
     * @return the semantic token
     */
    public ThemeToken token() {
        return token;
    }
}

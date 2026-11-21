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

package io.github.dhoard.braxton.style;

/**
 * Semantic roles a widget can ask a theme for instead of hard-coding colors.
 */
public enum ThemeToken {
    /**
     * The normal foreground text style.
     */
    TEXT,
    /**
     * The secondary text style.
     */
    MUTED,
    /**
     * The accent style for emphasized content.
     */
    ACCENT,
    /**
     * The success feedback style.
     */
    SUCCESS,
    /**
     * The warning feedback style.
     */
    WARNING,
    /**
     * The error feedback style.
     */
    ERROR,
    /**
     * The selected-content style.
     */
    SELECTED,
    /**
     * The border and separator style.
     */
    BORDER,
    /**
     * The application background style.
     */
    BACKGROUND,
    /**
     * The style for unavailable controls.
     */
    DISABLED,
    /**
     * The style indicating keyboard focus.
     */
    FOCUS
}

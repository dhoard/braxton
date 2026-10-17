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

package io.github.dhoard.braxton.style;

/**
 * Policy for terminal column measurement.
 *
 * <p>Grapheme segmentation and column width are deliberately separate: a cluster is always one
 * editing unit, but it may occupy one or two cells. The pinned default follows the project's
 * Unicode 16.0.0 table (East Asian wide/fullwidth plus emoji), with ambiguous-width characters
 * narrow. Terminals can disagree; an application may pass a different policy to the renderer and to
 * its own measurement calls.</p>
 */
public record WidthPolicy(boolean wideEmoji) {

    /** Pinned project policy: East Asian wide/fullwidth and emoji occupy two columns. */
    public static final WidthPolicy PINNED = new WidthPolicy(true);

    /** Diagnostics/tests: every cluster occupies one column regardless of its code points. */
    public static final WidthPolicy ONE_COLUMN = new WidthPolicy(false);

    public WidthPolicy {
    }
}

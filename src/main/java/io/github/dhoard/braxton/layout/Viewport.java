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

package io.github.dhoard.braxton.layout;


/**
 * The terminal geometry handed to {@code Component.view}.
 *
 * <p>A viewport is a plain value: the runtime owns when it changes and the application owns any
 * geometry-dependent state it derives from it.</p>
 */
public record Viewport(int rows, int cols) {

    public static final Viewport EMPTY = new Viewport(0, 0);

    public Viewport {
        if (rows < 0 || cols < 0) {
            throw new IllegalArgumentException("viewport must be non-negative: " + rows + "x" + cols);
        }
    }

    public static Viewport of(Size size) {
        return new Viewport(size.rows(), size.cols());
    }

    public Size size() {
        return new Size(rows, cols);
    }

    public Rect bounds() {
        return new Rect(0, 0, rows, cols);
    }

    @Override
    public String toString() {
        return rows + "x" + cols;
    }
}

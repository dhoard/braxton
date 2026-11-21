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
 * A stylesheet error with a one-based line and column.
 */
public final class StylesheetException extends IllegalArgumentException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /**
     * The one-based stylesheet source line.
     */
    private final int line;
    /**
     * The one-based stylesheet source column.
     */
    private final int column;

    /**
     * Creates a {@code StylesheetException} value.
     *
     * @param message the typed message to deliver
     * @param line the one-based source line
     * @param column the zero-based column
     */
    public StylesheetException(String message, int line, int column) {
        super(message + " at " + line + ":" + column);
        this.line = line;
        this.column = column;
    }

    /**
     * Returns the one-based source line of the stylesheet error.
     *
     * @return the one-based source line of the stylesheet error
     */
    public int line() {
        return line;
    }

    /**
     * Returns the one-based source column of the stylesheet error.
     *
     * @return the one-based source column of the stylesheet error
     */
    public int column() {
        return column;
    }
}

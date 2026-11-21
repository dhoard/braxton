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

package io.github.dhoard.braxton.internal.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable multi-line text used by editing widgets.
 */
public final class TextBuffer {

    private final List<String> lines;

    /**
     * An empty buffer with one empty line.
     *
     * @return an empty buffer with one empty line
     */
    public static TextBuffer empty() {
        return new TextBuffer(List.of(""));
    }

    /**
     * Creates a buffer from lines; an empty input still has one editable line.
     *
     * @param lines the rows in display order
     * @return a buffer from lines; an empty input still has one editable line
     */
    public static TextBuffer of(List<String> lines) {
        List<String> copy = new ArrayList<>(lines);
        if (copy.isEmpty()) copy.add("");
        return new TextBuffer(List.copyOf(copy));
    }

    private TextBuffer(List<String> lines) {
        this.lines = lines;
    }

    /**
     * Returns the number of logical lines.
     *
     * @return the number of logical lines
     */
    public int lineCount() {
        return lines.size();
    }

    /**
     * Returns one logical line without its line-ending separator.
     *
     * @param row the zero-based row
     * @return one logical line without its line-ending separator
     */
    public String line(int row) {
        return row < 0 || row >= lines.size() ? "" : lines.get(row);
    }

    /**
     * Returns the immutable logical-line snapshot.
     *
     * @return the immutable logical-line snapshot
     */
    public List<String> lines() {
        return lines;
    }

    /**
     * Inserts text at a row and column, splitting lines on newline.
     *
     * @param row the zero-based row
     * @param column the zero-based column
     * @param text the text to process
     * @return inserts text at a row and column, splitting lines on newline
     */
    public TextBuffer insert(int row, int column, String text) {
        if (text == null || text.isEmpty() || row < 0 || row >= lines.size()) return this;
        String target = line(row);
        int at = Math.max(0, Math.min(column, target.length()));
        String merged = target.substring(0, at) + text + target.substring(at);
        List<String> next = new ArrayList<>(lines);
        next.set(row, merged);
        return of(next);
    }

    /**
     * Deletes a half-open range from one line.
     *
     * @param row the zero-based row
     * @param from the initial value
     * @param to the target value
     * @return deletes a half-open range from one line
     */
    public TextBuffer delete(int row, int from, int to) {
        if (row < 0 || row >= lines.size()) return this;
        String target = line(row);
        int start = Math.max(0, Math.min(from, target.length()));
        int end = Math.max(start, Math.min(to, target.length()));
        List<String> next = new ArrayList<>(lines);
        next.set(row, target.substring(0, start) + target.substring(end));
        return of(next);
    }
}

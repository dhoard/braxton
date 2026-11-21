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

package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.style.TextMeasure;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The text a terminal shows after reading the bytes a session wrote.
 *
 * <p>The fixture records bytes, and a session writes only what changed: every frame after the first
 * one addresses the cells that differ. A test that asks what the terminal shows therefore cannot
 * search the transcript for a line - the line was written once, and its later values arrive as one
 * corrected cell each. This model applies the sequences the runtime writes to a grid instead, so a
 * test can assert on what a user would see.</p>
 *
 * <p>It is deliberately small. Absolute and relative cursor moves, line and display erases, inserted
 * and deleted lines, the scrolling region, the alternate screen, carriage return, line feed and
 * printable text are applied; attributes, hyperlinks, modes and private sequences are read and
 * discarded, and a sequence the model does not know leaves the grid alone. It models presentation,
 * not a terminal: what it cannot show is not what it refuses.</p>
 */
final class TerminalScreen {

    private int rows;
    private int cols;
    private Buffer main;
    private Buffer alternate;
    private Buffer buffer;
    private boolean alternateUsed;
    private int row;
    private int col;
    private boolean wrapPending;
    private int[] margins;

    TerminalScreen(int rows, int cols) {
        resize(rows, cols);
    }

    /**
     * Takes the terminal's new size, which a full repaint follows.
     */
    synchronized void resize(int newRows, int newCols) {
        rows = Math.max(1, newRows);
        cols = Math.max(1, newCols);
        main = new Buffer(rows, cols);
        alternate = new Buffer(rows, cols);
        buffer = alternateUsed ? alternate : main;
        row = 0;
        col = 0;
        wrapPending = false;
        margins = null;
    }

    /**
     * The frame the session last painted, one line per row with trailing blanks removed.
     *
     * <p>When a session used the alternate screen, that is the buffer it painted: leaving the
     * alternate screen gives a terminal its own content back, but the session's last frame is still
     * what the session showed, which is what a test asks about.</p>
     */
    synchronized String text() {
        List<String> lines = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            lines.add(line(index));
        }
        int end = lines.size();
        while (end > 0 && lines.get(end - 1).isEmpty()) {
            end--;
        }
        return String.join("\n", lines.subList(0, end));
    }

    /**
     * One row of what the terminal shows, trailing blanks removed.
     */
    synchronized String line(int index) {
        StringBuilder painted = new StringBuilder();
        for (int column = 0; column < cols; column++) {
            if (shown().continuation[index][column]) {
                continue;
            }
            String cell = shown().text[index][column];
            painted.append(cell.isEmpty() ? " " : cell);
        }
        int end = painted.length();
        while (end > 0 && painted.charAt(end - 1) == ' ') {
            end--;
        }
        return painted.substring(0, end);
    }

    /**
     * Applies one chunk of the session's output.
     */
    synchronized void apply(String stream) {
        int index = 0;
        while (index < stream.length()) {
            char next = stream.charAt(index);
            if (next == 0x1B) {
                index = escape(stream, index);
                continue;
            }
            if (next == '\r') {
                col = 0;
                wrapPending = false;
                index++;
            } else if (next == '\n') {
                lineFeed();
                index++;
            } else if (next == '\b') {
                col = Math.max(0, col - 1);
                wrapPending = false;
                index++;
            } else if (next == '\t') {
                col = Math.min(cols - 1, (col / 8 + 1) * 8);
                wrapPending = false;
                index++;
            } else if (next < 0x20 || next == 0x7F) {
                // A control byte outside an escape sequence shows nothing.
                index++;
            } else {
                int end = clusterEnd(stream, index);
                write(stream.substring(index, end));
                index = end;
            }
        }
    }

    /**
     * Returns the index just past the escape sequence that starts at {@code at}.
     */
    private int escape(String stream, int at) {
        if (at + 1 >= stream.length()) {
            return at + 1;
        }
        char lead = stream.charAt(at + 1);
        if (lead == '[') {
            int end = at + 2;
            StringBuilder params = new StringBuilder();
            if (end < stream.length() && stream.charAt(end) == '?') {
                params.append('?');
                end++;
            }
            while (end < stream.length() && stream.charAt(end) >= 0x20 && stream.charAt(end) <= 0x3F) {
                params.append(stream.charAt(end));
                end++;
            }
            if (end >= stream.length()) {
                return stream.length();
            }
            apply(params.toString(), stream.charAt(end));
            return end + 1;
        }
        if (lead == ']') {
            int terminator = stream.indexOf("\u001B\\", at + 2);
            int bell = stream.indexOf('\u0007', at + 2);
            if (terminator < 0 && bell < 0) {
                return stream.length();
            }
            if (terminator < 0 || (bell >= 0 && bell < terminator)) {
                return bell + 1;
            }
            return terminator + 2;
        }
        return at + 2;
    }

    private void apply(String params, char command) {
        if (params.startsWith("?")) {
            // The alternate screen holds a frame of its own; every other private mode is a
            // presentation detail this model does not show.
            if (params.equals("?1049")) {
                if (command == 'h') {
                    alternateUsed = true;
                    buffer = alternate;
                    clear();
                } else if (command == 'l') {
                    buffer = main;
                    row = 0;
                    col = 0;
                    wrapPending = false;
                    margins = null;
                }
            }
            return;
        }
        switch (command) {
            case 'H', 'f' -> {
                row = Math.min(rows - 1, Math.max(0, parameter(params, 0, 1) - 1));
                col = Math.min(cols - 1, Math.max(0, parameter(params, 1, 1) - 1));
                wrapPending = false;
            }
            case 'G' -> {
                col = Math.min(cols - 1, Math.max(0, parameter(params, 0, 1) - 1));
                wrapPending = false;
            }
            case 'A' -> row = Math.max(0, row - parameter(params, 0, 1));
            case 'B' -> row = Math.min(rows - 1, row + parameter(params, 0, 1));
            case 'C' -> col = Math.min(cols - 1, col + parameter(params, 0, 1));
            case 'D' -> col = Math.max(0, col - parameter(params, 0, 1));
            case 'K' -> eraseLine(parameter(params, 0, 0));
            case 'J' -> eraseDisplay(parameter(params, 0, 0));
            case 'M' -> deleteLines(parameter(params, 0, 1));
            case 'L' -> insertLines(parameter(params, 0, 1));
            case 'r' -> {
                int top = parameter(params, 0, 1) - 1;
                int bottom = parameter(params, 1, rows) - 1;
                margins = bottom - top >= 1 ? new int[] {Math.max(0, top), Math.min(rows - 1, bottom)} : null;
                row = 0;
                col = 0;
            }
            default -> {
                // Attributes, modes, cursor shape, keyboard flags: nothing this model shows.
            }
        }
    }

    private void eraseLine(int mode) {
        int from = switch (mode) {
            case 1 -> 0;
            case 2 -> 0;
            default -> col;
        };
        int to = switch (mode) {
            case 1 -> Math.min(cols - 1, col);
            case 2 -> cols - 1;
            default -> cols - 1;
        };
        for (int column = from; column <= to; column++) {
            erase(row, column);
        }
    }

    private void eraseDisplay(int mode) {
        int firstRow = mode == 1 ? 0 : row;
        int lastRow = mode == 0 ? rows - 1 : row;
        for (int index = firstRow; index <= lastRow; index++) {
            for (int column = 0; column < cols; column++) {
                boolean after = index > row || (index == row && column >= col);
                boolean before = index < row || (index == row && column <= col);
                if ((mode == 0 && after) || (mode == 1 && before) || mode == 2) {
                    erase(index, column);
                }
            }
        }
    }

    private void deleteLines(int count) {
        int top = top();
        int bottom = bottom();
        if (row < top || row > bottom) {
            return;
        }
        for (int index = row; index <= bottom; index++) {
            copyRow(index, index + count <= bottom ? index + count : -1);
        }
        col = 0;
        wrapPending = false;
    }

    private void insertLines(int count) {
        int top = top();
        int bottom = bottom();
        if (row < top || row > bottom) {
            return;
        }
        for (int index = bottom; index >= row; index--) {
            copyRow(index, index - count >= row ? index - count : -1);
        }
        col = 0;
        wrapPending = false;
    }

    private void lineFeed() {
        wrapPending = false;
        if (row < rows - 1) {
            row++;
            return;
        }
        for (int index = 0; index < rows - 1; index++) {
            copyRow(index, index + 1);
        }
        copyRow(rows - 1, -1);
    }

    private void write(String cluster) {
        int width = Math.max(0, Math.min(2, TextMeasure.columns(cluster)));
        if (width == 0) {
            // A mark joins the cell before the cursor, which is how a terminal paints it.
            int base = col - 1;
            while (base >= 0 && buffer.continuation[row][base]) {
                base--;
            }
            if (base >= 0) {
                buffer.text[row][base] = buffer.text[row][base] + cluster;
            }
            return;
        }
        if (wrapPending || col + width > cols) {
            col = 0;
            lineFeed();
        }
        buffer.text[row][col] = cluster;
        buffer.continuation[row][col] = false;
        if (width == 2 && col + 1 < cols) {
            buffer.text[row][col + 1] = "";
            buffer.continuation[row][col + 1] = true;
        }
        col += width;
        if (col >= cols) {
            col = cols - 1;
            wrapPending = true;
        }
    }

    private void erase(int index, int column) {
        buffer.text[index][column] = "";
        buffer.continuation[index][column] = false;
    }

    private void clear() {
        for (int index = 0; index < rows; index++) {
            for (int column = 0; column < cols; column++) {
                erase(index, column);
            }
        }
        row = 0;
        col = 0;
        wrapPending = false;
    }

    private void copyRow(int to, int from) {
        for (int column = 0; column < cols; column++) {
            if (from < 0 || from >= rows) {
                erase(to, column);
            } else {
                buffer.text[to][column] = buffer.text[from][column];
                buffer.continuation[to][column] = buffer.continuation[from][column];
            }
        }
    }

    /**
     * The buffer a session's frame is read from: the alternate screen once one was used.
     */
    private Buffer shown() {
        return alternateUsed ? alternate : main;
    }

    private int top() {
        return margins == null ? 0 : margins[0];
    }

    private int bottom() {
        return margins == null ? rows - 1 : margins[1];
    }

    /**
     * The {@code index}-th parameter, or its default when the sequence does not carry it.
     */
    private static int parameter(String params, int index, int fallback) {
        int seen = 0;
        int start = 0;
        while (start <= params.length()) {
            int end = params.indexOf(';', start);
            String value =
                    params.substring(start, end < 0 ? params.length() : end).strip();
            if (seen == index) {
                // A parameter the grammar allows but this model has no number for - a '>' or '<'
                // marker, an intermediate byte - reads as absent.
                return value.isEmpty() || !value.chars().allMatch(Character::isDigit)
                        ? fallback
                        : Integer.parseInt(value);
            }
            seen++;
            if (end < 0) {
                return fallback;
            }
            start = end + 1;
        }
        return fallback;
    }

    /**
     * The end of the grapheme cluster that starts at {@code at}: a base and everything that joins it.
     */
    private static int clusterEnd(String stream, int at) {
        int end = at + Character.charCount(stream.codePointAt(at));
        while (end < stream.length() && joins(stream.charAt(end))) {
            end += Character.charCount(stream.codePointAt(end));
        }
        return end;
    }

    /**
     * One screen buffer: what each cell holds, and which cells are the second half of a glyph.
     */
    private static final class Buffer {
        private final String[][] text;
        private final boolean[][] continuation;

        Buffer(int rows, int cols) {
            text = new String[rows][cols];
            continuation = new boolean[rows][cols];
            for (String[] line : text) {
                Arrays.fill(line, "");
            }
        }
    }

    private static boolean joins(char next) {
        if (next == 0xFE0E || next == 0xFE0F || next == 0x200D) {
            return true;
        }
        int type = Character.getType(next);
        return type == Character.NON_SPACING_MARK
                || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }
}

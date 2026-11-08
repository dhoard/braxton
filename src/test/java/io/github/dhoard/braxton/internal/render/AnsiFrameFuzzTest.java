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

package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * What a terminal ends up showing, after the bytes the encoder writes are executed.
 *
 * <p>Every other render test reads the grid back through the grid's own API. That proves the grid is
 * internally consistent; it does not prove a terminal agrees. The grid can charge a cluster one column
 * while a terminal paints it in two, and then the row runs off the right margin and the tail lands in
 * the row below - the stream is wrong even though every grid-level assertion passes.</p>
 *
 * <p>So this test executes the stream. {@link Emulator} is a deliberately small VT model written from
 * the escape-sequence grammar and its own width rules, with no reference to {@link Screen},
 * {@link TextMeasure}, or the project's width tables. Feeding it {@link AnsiEncoder#frame} or
 * {@link AnsiEncoder#diff} and comparing what it displays with what the grid claims is a real
 * differential test: two independent models of the same row must agree.</p>
 */
class AnsiFrameFuzzTest {

    /**
     * Single grapheme clusters whose width is settled, so a disagreement points at the encoder rather
     * than at policy. One cluster per cell: {@link Screen#put} charges a cell the columns of the one
     * cluster it holds, so a multi-cluster string here would be an invalid call, not a defect.
     *
     * <p>U+200B is deliberately absent. Its East Asian width is ambiguous, so terminals themselves
     * disagree about whether it occupies a column; a fixed oracle cannot settle what the terminal
     * model has to leave open. {@link io.github.dhoard.braxton.style.WidthPolicy} records the same
     * caveat, and grid-level tests cover that character.</p>
     *
     * <p>The screen sweep paints these atoms one per cell; the layout sweep joins them into clusters,
     * and that set is {@link #LAYOUT_GRAPHEMES}.</p>
     *
     * <p>The two presentation selectors are present, joined to bases and bare. U+FE0E (text form) and
     * U+FE0F (emoji form) change the glyph asked for, not the number of cells: that is how the terminal
     * model here charges a cluster and how the layout measures one, and it matches the reference
     * terminal stack - Bubble Tea measures through {@code x/ansi} and {@code displaywidth}, whose VS15
     * rule is that it requests text presentation without affecting width. A CJK base followed by U+FE0E
     * used to be measured one column and painted two, which is the gap these atoms now check.</p>
     */
    private static final String[] GRAPHEMES = {
        "a",
        "Z",
        " ",
        "\u4E2D",
        "\u6587",
        "\uFF21",
        "\uAC00",
        "\u0301",
        "\u200D",
        "\u25CC",
        "\uD83D\uDE00",
        "\uD83D\uDE00\uFE0F",
        "\uD83D\uDE00\uFE0E",
        "e\u0301",
        "\uFE0F",
        "\uFE0E"
    };

    /**
     * The atoms the layout sweep combines. Every atom here is usable now that U+FE0E measures the same
     * number of columns as the base it follows.
     *
     * <p>{@code \u200D} is held out for one reason, and it is not a width disagreement: the layout charges
     * a joiner sequence such as U+1F600 U+200D U+1F600 as a single cluster of two columns, which is what
     * the reference stack does as well - {@code displaywidth} takes the width of a cluster from its
     * leading character and sums per cluster. A terminal that allocates a cell per code point paints the
     * same sequence as two glyphs and four cells. Bubble Tea closes that gap by switching the terminal
     * into Unicode Core mode (DEC 2027) when it measures by graphemes, so the terminal clusters the way
     * the framework did; this terminal model allocates per code point. Joiner sequences are therefore
     * outside what this pair of models can settle, and a renderer-level answer (emitting mode 2027, or
     * charging joiner sequences by their parts) is the decision to make, not a measurement fix here. The
     * atoms stay in the screen sweep, where each one is a cell of its own and the join never forms.</p>
     */
    private static final String[] LAYOUT_GRAPHEMES = {
        "a",
        "Z",
        " ",
        "\u4E2D",
        "\u6587",
        "\uFF21",
        "\uAC00",
        "\u0301",
        "\u25CC",
        "\uD83D\uDE00",
        "\uD83D\uDE00\uFE0F",
        "\uD83D\uDE00\uFE0E",
        "e\u0301",
        "\uFE0F",
        "\uFE0E"
    };

    private static final Style[] STYLES = {
        Style.NONE,
        Style.NONE.withBold(),
        Style.NONE.withUnderline().withForeground(new io.github.dhoard.braxton.style.Color(10, 20, 30)),
        Style.NONE.withBackground(new io.github.dhoard.braxton.style.Color(200, 30, 5))
    };

    @Test
    void aFramePaintsExactlyTheCellsTheGridHolds() {
        Random random = new Random(60219L);
        for (int round = 0; round < 4000; round++) {
            int rows = 1 + random.nextInt(4);
            int cols = 1 + random.nextInt(14);
            Screen screen = new Screen(rows, cols);
            for (int writes = 1 + random.nextInt(rows * cols); writes > 0; writes--) {
                putAsTerminalWould(screen, random, random.nextInt(rows), random.nextInt(cols));
            }
            boolean color = random.nextBoolean();
            Emulator terminal = new Emulator(rows, cols);
            String frame = AnsiEncoder.frame(screen, color);
            terminal.execute(frame);
            terminal.assertShows(screen, "frame " + show(frame));
        }
    }

    @Test
    void anIncrementalRepaintLeavesNothingBehindAndNothingMissing() {
        Random random = new Random(317734L);
        for (int round = 0; round < 3000; round++) {
            int rows = 1 + random.nextInt(5);
            int cols = 2 + random.nextInt(16);
            Screen before = randomScreen(random, rows, cols);
            Screen after = randomScreen(random, rows, cols);
            Emulator terminal = new Emulator(rows, cols);
            terminal.execute(AnsiEncoder.frame(before, true));
            terminal.assertShows(before, "the frame that opened the screen");
            String patch = AnsiEncoder.diff(before, after, true);
            terminal.execute(patch);
            terminal.assertShows(after, "diff " + show(patch));
        }
    }

    @Test
    void aLayoutSurvivesTheTerminalThatReadsIt() {
        Random random = new Random(20261108L);
        for (int round = 0; round < 900; round++) {
            View<Object> view = build(3, random, LAYOUT_GRAPHEMES);
            int maxCols = 1 + random.nextInt(20);
            LayoutEngine engine = new LayoutEngine();
            int cols = Math.max(1, engine.intrinsicWidth(view, maxCols));
            int rows = Math.max(1, engine.intrinsicHeight(view, cols));
            Screen painted = engine.layout(view, new Rect(0, 0, rows, cols)).screen();
            Emulator terminal = new Emulator(rows, cols);
            String frame = AnsiEncoder.frame(painted, true);
            terminal.execute(frame);
            terminal.assertShows(painted, "layout of " + view);
        }
    }

    /**
     * Write one cluster the way a correct caller would: charge the columns the project's
     * measurement gives it, and give a cluster with no base of its own the dotted circle that
     * stands in for the base it lacks.
     */
    private static void putAsTerminalWould(Screen screen, Random random, int row, int col) {
        String cluster = GRAPHEMES[random.nextInt(GRAPHEMES.length)];
        if (cluster.isEmpty()) {
            return;
        }
        String text = hasBase(cluster) ? cluster : TextMeasure.DOTTED_CIRCLE + cluster;
        int width = Math.max(1, Math.min(2, TextMeasure.columns(text)));
        String link = random.nextInt(4) == 0 ? "https://example.com/a" : null;
        screen.put(row, col, text, STYLES[random.nextInt(STYLES.length)], link, width);
    }

    /** Whether the cluster has a code point that can occupy a cell of its own. */
    private static boolean hasBase(String cluster) {
        for (int offset = 0; offset < cluster.length(); ) {
            int cp = cluster.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp == 0xFE0E || cp == 0xFE0F) {
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK
                    || type == Character.FORMAT) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static Screen randomScreen(Random random, int rows, int cols) {
        Screen screen = new Screen(rows, cols);
        for (int writes = random.nextInt(rows * cols + 1); writes > 0; writes--) {
            putAsTerminalWould(screen, random, random.nextInt(rows), random.nextInt(cols));
        }
        return screen;
    }

    private static View<Object> build(int depth, Random random, String[] atoms) {
        if (depth <= 0 || random.nextInt(3) == 0) {
            return Views.text(joinGraphemes(random, atoms));
        }
        return switch (random.nextInt(7)) {
            case 0 -> Views.border(Border.SINGLE, build(depth - 1, random, atoms));
            case 1 -> Views.padding(Insets.uniform(random.nextInt(3)), build(depth - 1, random, atoms));
            case 2 -> Views.row(Alignment.START, items(depth - 1, random, atoms));
            case 3 -> Views.column(Alignment.START, items(depth - 1, random, atoms));
            case 4 -> Views.overlay(build(depth - 1, random, atoms), build(depth - 1, random, atoms));
            case 5 -> Views.fixed(random.nextInt(6), build(depth - 1, random, atoms));
            default -> Views.grow(build(depth - 1, random, atoms));
        };
    }

    private static String joinGraphemes(Random random, String[] atoms) {
        StringBuilder text = new StringBuilder();
        for (int count = random.nextInt(5); count > 0; count--) {
            text.append(atoms[random.nextInt(atoms.length)]);
        }
        return text.toString();
    }

    private static List<View<Object>> items(int depth, Random random, String[] atoms) {
        List<View<Object>> items = new ArrayList<>();
        for (int count = 1 + random.nextInt(3); count > 0; count--) {
            items.add(build(depth, random, atoms));
        }
        return items;
    }

    /**
     * A minimal terminal: absolute cursor addressing, erase-line, SGR, OSC 8, and printable text that
     * advances the cursor by the columns the cluster really occupies.
     *
     * <p>Widths come from the JDK's own general-category data plus a hand-written list of the ranges
     * any vt100-class terminal paints two columns wide. Nothing here is shared with the code under
     * test, which is the point: a row that lands in the right place under both models is a row a
     * terminal will show correctly.</p>
     */
    private static final class Emulator {
        private final int rows;
        private final int cols;
        private final String[] text;
        private final boolean[] continuation;
        private int row;
        private int col;
        private String pending = "";
        private final List<String> faults = new ArrayList<>();

        Emulator(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
            this.text = new String[rows * cols];
            this.continuation = new boolean[rows * cols];
        }

        void execute(String stream) {
            int i = 0;
            while (i < stream.length()) {
                char c = stream.charAt(i);
                if (c == 0x1B) {
                    i = escape(stream, i);
                    continue;
                }
                if (c < 0x20 || c == 0x7F) {
                    // A raw control byte outside an escape sequence is content that has injected
                    // terminal behavior; nothing in the encoder is allowed to emit one.
                    faults.add("raw control byte U+%04x in the stream".formatted((int) c));
                    i++;
                    continue;
                }
                String cluster = clusterAt(stream, i);
                write(cluster);
                i += cluster.length();
            }
        }

        /**
         * The cluster the terminal paints at {@code at}: a base and everything that joins it. A
         * terminal decides a cluster's width after reading its selectors and marks - it does not paint
         * a glyph two columns wide and then discover that a following U+FE0E narrows it - so the
         * stream is grouped the same way here.
         */
        private static String clusterAt(String stream, int at) {
            char c = stream.charAt(at);
            int end = Character.isHighSurrogate(c)
                            && at + 1 < stream.length()
                            && Character.isLowSurrogate(stream.charAt(at + 1))
                    ? at + 2
                    : at + 1;
            while (end < stream.length() && joins(stream.charAt(end))) {
                end++;
            }
            return stream.substring(at, end);
        }

        /**
         * Marks, the zero-width joiner and the variation selectors: they change the cluster they
         * follow and never start one. Other format characters are not extenders - a zero-width space
         * stands between clusters, so a selector after it does not reach back to the base before it.
         */
        private static boolean joins(char c) {
            if (c == 0xFE0E || c == 0xFE0F || c == 0x200D) {
                return true;
            }
            int type = Character.getType(c);
            return type == Character.NON_SPACING_MARK
                    || type == Character.ENCLOSING_MARK
                    || type == Character.COMBINING_SPACING_MARK;
        }

        /** Returns the index just past the escape sequence that starts at {@code at}. */
        private int escape(String stream, int at) {
            if (at + 1 >= stream.length()) {
                faults.add("stream ends inside an escape sequence");
                return at + 1;
            }
            char lead = stream.charAt(at + 1);
            if (lead == '[') {
                int end = at + 2;
                StringBuilder params = new StringBuilder();
                while (end < stream.length() && stream.charAt(end) >= 0x20 && stream.charAt(end) <= 0x3F) {
                    params.append(stream.charAt(end));
                    end++;
                }
                if (end >= stream.length()) {
                    faults.add("stream ends inside a CSI sequence");
                    return stream.length();
                }
                apply(stream.charAt(end), params.toString());
                return end + 1;
            }
            if (lead == ']') {
                int terminator = stream.indexOf("\u001B\\", at + 2);
                if (terminator < 0) {
                    faults.add("stream ends inside an OSC string");
                    return stream.length();
                }
                if (!stream.startsWith("8;;", at + 2)) {
                    faults.add("unexpected OSC sequence " + show(stream.substring(at, terminator + 2)));
                }
                return terminator + 2;
            }
            faults.add("unrecognized escape sequence " + show(stream.substring(at, Math.min(stream.length(), at + 4))));
            return at + 2;
        }

        private void apply(char command, String params) {
            switch (command) {
                case 'H' -> {
                    if (!pending.isEmpty()) {
                        faults.add("invisible run %s never reached a painted cell".formatted(show(pending)));
                        pending = "";
                    }
                    List<Integer> n = numbers(params);
                    row = Math.max(0, (n.isEmpty() ? 1 : n.get(0)) - 1);
                    col = Math.max(0, (n.size() < 2 ? 1 : n.get(1)) - 1);
                }
                case 'K' -> {
                    int mode = numbers(params).isEmpty() ? 0 : numbers(params).get(0);
                    if (mode == 2) {
                        for (int col = 0; col < cols; col++) {
                            erase(row, col);
                        }
                    } else if (mode == 1) {
                        for (int col = 0; col <= this.col && col < cols; col++) {
                            erase(row, col);
                        }
                    } else {
                        for (int col = this.col; col < cols; col++) {
                            erase(row, col);
                        }
                    }
                }
                case 'm', 'l', 'h' -> {}
                case 'J' -> {
                    for (int row = 0; row < rows; row++) {
                        for (int col = 0; col < cols; col++) {
                            erase(row, col);
                        }
                    }
                    row = 0;
                    col = 0;
                }
                default -> faults.add("unrecognized CSI " + params + command);
            }
        }

        private void erase(int row, int col) {
            text[index(row, col)] = null;
            continuation[index(row, col)] = false;
        }

        private boolean isBase(int row, int col) {
            return text[index(row, col)] != null
                    && text[index(row, col)].codePointCount(0, text[index(row, col)].length()) > 0
                    && columns(text[index(row, col)]) == 2;
        }

        private void write(String cluster) {
            int width = columns(cluster);
            if (width == 0) {
                // A mark or joiner joins the cell before it and does not move the cursor. The cell
                // immediately before the cursor is the untouched second half of a wide glyph, so the
                // base is found by walking back over the halves the terminal wrote no bytes for.
                int base = col - 1;
                while (base >= 0 && continuation[index(row, base)]) {
                    base--;
                }
                if (base >= 0 && text[index(row, base)] != null) {
                    text[index(row, base)] += cluster;
                    return;
                }
                // Nothing to join: the run paints no column anywhere and the terminal simply carries it
                // into whatever cell is painted next. It has to be carried there, or the grid would be
                // holding characters no cell of the row ever received.
                pending += cluster;
                return;
            }
            if (col + width > cols) {
                // Deferred wrap: the terminal puts what no longer fits on the next row. A row that
                // reaches this point has outgrown the columns it was given.
                faults.add("cluster %s needs %d columns at %d,%d, only %d left in the row"
                        .formatted(show(cluster), width, row, col, cols - col));
                row = Math.min(rows - 1, row + 1);
                col = 0;
            }
            int at = index(row, col);
            text[at] = pending + cluster;
            pending = "";
            continuation[at] = false;
            for (int next = col + 1; next < Math.min(cols, col + width); next++) {
                text[index(row, next)] = null;
                continuation[index(row, next)] = true;
            }
            if (col + width > cols) {
                // Charged past the margin: the tail of the glyph has nowhere to go on this row.
                faults.add("cluster %s at %d,%d hangs past the %d-column row".formatted(show(cluster), row, col, cols));
            }
            col += width;
        }

        /** Columns this terminal model gives a cluster: JDK categories for marks, own ranges for width. */
        private static int columns(String cluster) {
            boolean wide = false;
            boolean base = false;
            for (int offset = 0; offset < cluster.length(); ) {
                int cp = cluster.codePointAt(offset);
                offset += Character.charCount(cp);
                if (cp == 0xFE0E || cp == 0xFE0F) {
                    // A presentation selector picks a glyph, not a cell count. VS15 asks for the text
                    // form and leaves the base's width standing; VS16 asks for the emoji form of a base
                    // that has one. No terminal narrows a wide ideograph by being asked for text.
                    wide |= cp == 0xFE0F;
                    continue;
                }
                int type = Character.getType(cp);
                if (type == Character.NON_SPACING_MARK
                        || type == Character.ENCLOSING_MARK
                        || type == Character.COMBINING_SPACING_MARK
                        || type == Character.FORMAT) {
                    continue;
                }
                base = true;
                if (isWide(cp)) {
                    wide = true;
                }
            }
            if (!base) {
                return 0;
            }
            return wide ? 2 : 1;
        }

        /** The ranges a vt100-class terminal paints two columns wide. */
        private static boolean isWide(int cp) {
            return (cp >= 0x1100 && cp <= 0x115F)
                    || (cp >= 0x2E80 && cp <= 0x303E)
                    || (cp >= 0x3041 && cp <= 0x33FF)
                    || (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0xA000 && cp <= 0xA4CF)
                    || (cp >= 0xAC00 && cp <= 0xD7A3)
                    || (cp >= 0xF900 && cp <= 0xFAFF)
                    || (cp >= 0xFE30 && cp <= 0xFE6F)
                    || (cp >= 0xFF00 && cp <= 0xFF60)
                    || (cp >= 0x1F300 && cp <= 0x1F64F)
                    || (cp >= 0x1F900 && cp <= 0x1F9FF);
        }

        void assertShows(Screen screen, String where) {
            if (!pending.isEmpty()) {
                faults.add("invisible run %s never reached a painted cell".formatted(show(pending)));
                pending = "";
            }
            // A half of a wide glyph left standing on its own is a broken cell on the screen. Checked
            // once the stream is complete: while a row is being repainted the encoder passes over such
            // a state for a moment, and a terminal in the middle of a write does too.
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    if (continuation[index(row, col)] && (col == 0 || !isBase(row, col - 1))) {
                        faults.add("orphaned wide cell at %d,%d".formatted(row, col));
                    }
                }
            }
            assertThat(faults)
                    .as("%s: the terminal had to reject the stream", where)
                    .isEmpty();
            for (int row = 0; row < rows; row++) {
                assertThat(display(row))
                        .as("%s: row %d is not what the grid says it shows", where, row)
                        .isEqualTo(screen.plainLine(row));
            }
        }

        /** What the row looks like: blanks occupy their column, wide glyphs hold two, trailing blanks go. */
        private String display(int row) {
            StringBuilder line = new StringBuilder();
            for (int col = 0; col < cols; col++) {
                if (continuation[index(row, col)]) {
                    continue;
                }
                String cell = text[index(row, col)];
                line.append(cell == null ? " " : cell);
            }
            int end = line.length();
            while (end > 0 && line.charAt(end - 1) == ' ') {
                end--;
            }
            return line.substring(0, end);
        }

        private int index(int row, int col) {
            return row * cols + col;
        }

        private static List<Integer> numbers(String params) {
            List<Integer> values = new ArrayList<>();
            for (String part : params.split(";")) {
                if (!part.isEmpty() && part.chars().allMatch(Character::isDigit)) {
                    values.add(Integer.parseInt(part));
                }
            }
            return values;
        }
    }

    private static String show(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append('"').toString();
    }
}

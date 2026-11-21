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

package io.github.dhoard.braxton.line;

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.terminal.TerminalException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A bounded count painted as a bar in the inline region.
 *
 * <p>It is the progress counterpart of {@link LineReader}: the same region, the same rule that
 * nothing above it is touched, and a row that is repainted in place as the count moves rather than
 * appended to the transcript. A row is a name, a bar of filled and empty cells, the count and the
 * percentage:</p>
 *
 * <pre>
 * Installing packages  ████░░░░░░░░  3/6   50%
 * </pre>
 *
 * <p>The count is bounded and the total is positive, because a bounded count is what makes a bar
 * honest: an operation with no known end is a spinner's, and {@code widget.Spinner} already is one.
 * A count outside the bound is clamped rather than rejected, so a caller's off-by-one cannot paint a
 * bar longer than its row or a percentage over one hundred.</p>
 *
 * <p>The prompt paints nothing it did not change: the region writes a frame only when the row
 * differs from the one already on screen, so stepping a count whose cell did not move costs no
 * bytes. On the way out the final frame stays on screen as the result, the rule an accepted line
 * follows, and the caller's next output starts on the line below it.</p>
 *
 * <p>The prompt reads nothing and owns no keys. A cancel gesture would have to take a keystroke away
 * from whatever reads next, and which gesture cancels an operation - and what it means - is the
 * application's decision: a caller that wants one cancels the work, closes the prompt and prints
 * what happened.</p>
 */
public final class ProgressPrompt implements AutoCloseable {

    /**
     * The glyph a filled bar cell paints.
     */
    private static final String FILLED = "\u2588";

    /**
     * The glyph an empty bar cell paints.
     */
    private static final String EMPTY = "\u2591";

    /**
     * The columns the row leaves empty between its parts.
     */
    private static final String SEPARATOR = "  ";

    /**
     * The columns the row spends on everything but its name, its count and its bar.
     */
    private static final int FIXED_COLUMNS = 3 * SEPARATOR.length() + 4;

    private final InlineRegion region;
    private final Theme theme;
    private final WidthPolicy policy;
    private final String label;
    private final long total;
    private long completed;
    private boolean closed;

    private ProgressPrompt(
            InlineRegion region, Theme theme, WidthPolicy policy, String label, long total, long completed) {
        this.region = region;
        this.theme = theme;
        this.policy = policy;
        this.label = LineText.singleRow(label);
        this.total = total;
        this.completed = completed;
    }

    /**
     * Opens the prompt where the terminal's cursor is and paints the count it starts at.
     *
     * <p>The reader supplies the session, the theme and the column policy, so a progress bar is
     * painted with the same theme and the same measurement as the lines around it. The prompt owns
     * the region until it is closed; closing it is what puts the cursor back below the row so the
     * caller can print what it likes there.</p>
     *
     * @param reader the reader that supplies the session and the painting; required
     * @param label the name painted before the bar; null is none
     * @param total the number of steps the count runs to; required, and positive
     * @return the prompt
     * @throws TerminalException when the session cannot be written to
     */
    public static ProgressPrompt open(LineReader reader, String label, long total) throws TerminalException {
        Objects.requireNonNull(reader, "reader");
        if (total <= 0) {
            throw new IllegalArgumentException("total must be positive");
        }
        InlineRegion region = InlineRegion.open(reader.session(), reader.profile());
        ProgressPrompt prompt = new ProgressPrompt(region, reader.theme(), reader.policy(), label, total, 0L);
        prompt.present();
        return prompt;
    }

    /**
     * The name painted before the bar.
     *
     * @return the name painted before the bar
     */
    public String label() {
        return label;
    }

    /**
     * The number of steps the count runs to.
     *
     * @return the number of steps the count runs to
     */
    public long total() {
        return total;
    }

    /**
     * The count as it stands.
     *
     * @return the count as it stands
     */
    public long completed() {
        return completed;
    }

    /**
     * Reports how far the work has got, and repaints the row when it changed.
     *
     * @param value the steps completed, clamped into {@code [0, total]}
     * @throws TerminalException when the session cannot be written to
     * @throws IllegalStateException when the prompt is already closed
     */
    public void update(long value) throws TerminalException {
        if (closed) {
            throw new IllegalStateException("the progress prompt is closed");
        }
        long bounded = Math.max(0L, Math.min(value, total));
        if (bounded == completed) {
            return;
        }
        completed = bounded;
        present();
    }

    /**
     * Reports one more step.
     *
     * @throws TerminalException when the session cannot be written to
     * @throws IllegalStateException when the prompt is already closed
     */
    public void step() throws TerminalException {
        update(completed + 1L);
    }

    /**
     * Leaves the row on screen and gives the region back, once.
     *
     * @throws TerminalException when the session cannot be written to
     */
    @Override
    public void close() throws TerminalException {
        if (closed) {
            return;
        }
        closed = true;
        region.leave(true);
    }

    /**
     * The row as plain text, which is what the prompt paints and what a verifier reads.
     *
     * @param label the name before the bar; null is none
     * @param completed the steps completed, clamped into {@code [0, total]}
     * @param total the number of steps; required, and positive
     * @param cols the columns the row may use
     * @return the row
     */
    static String text(String label, long completed, long total, int cols) {
        StringBuilder out = new StringBuilder();
        for (Part part : parts(label, completed, total, cols, WidthPolicy.PINNED)) {
            out.append(part.text());
        }
        return out.toString();
    }

    /**
     * Paints the row the count produces.
     */
    private void present() throws TerminalException {
        int cols = region.columns();
        Screen screen = new Screen(1, cols);
        StyledText row = StyledText.empty();
        for (Part part : parts(label, completed, total, cols, policy)) {
            row = row.append(StyledText.of(part.text(), theme.style(part.token())));
        }
        screen.write(new Rect(0, 0, 1, cols), row, policy);
        region.present(screen, Optional.empty());
    }

    /**
     * The row's parts, in order, so the painted row and the plain one cannot disagree.
     *
     * <p>The bar takes what is left after the name, the count and the percentage, and is omitted
     * entirely when there is no column for it: a row too narrow for a bar still says the name, the
     * count and the percentage rather than clipping the count away.</p>
     */
    private static List<Part> parts(String label, long completed, long total, int cols, WidthPolicy policy) {
        if (total <= 0) {
            throw new IllegalArgumentException("total must be positive");
        }
        long bounded = Math.max(0L, Math.min(completed, total));
        String name = LineText.singleRow(label);
        String count = bounded + "/" + total;
        String percentage = String.format(Locale.US, "%3d%%", Math.round(100.0 * bounded / total));
        WidthPolicy measured = policy == null ? WidthPolicy.PINNED : policy;
        // The name and the count are painted first and last, with three separators and the four
        // columns of "100%" between them; the bar takes whatever is left.
        int barColumns =
                cols - (TextMeasure.columns(name, measured) + TextMeasure.columns(count, measured) + FIXED_COLUMNS);
        List<Part> parts = new ArrayList<>(5);
        if (!name.isEmpty()) {
            parts.add(new Part(name, ThemeToken.ACCENT));
            parts.add(new Part(SEPARATOR, ThemeToken.TEXT));
        }
        if (barColumns > 0) {
            int filled = (int) Math.round((double) barColumns * bounded / total);
            int clamped = Math.max(0, Math.min(filled, barColumns));
            parts.add(new Part(FILLED.repeat(clamped), ThemeToken.SUCCESS));
            parts.add(new Part(EMPTY.repeat(barColumns - clamped), ThemeToken.MUTED));
            parts.add(new Part(SEPARATOR, ThemeToken.TEXT));
        }
        parts.add(new Part(count, ThemeToken.MUTED));
        parts.add(new Part(SEPARATOR + percentage, ThemeToken.TEXT));
        return List.copyOf(parts);
    }

    /**
     * One painted piece of the row: its text and the theme role it is painted in.
     */
    private record Part(String text, ThemeToken token) {}
}

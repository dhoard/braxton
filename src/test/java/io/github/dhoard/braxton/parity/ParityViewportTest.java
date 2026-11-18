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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import examples.parity.ViewportDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.internal.text.VisualRows;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.RenderedScreen;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.TextViewport;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 12 acceptance: wrapping, panning, paging, gutters, highlights and the demo scene.
 *
 * <p>Every assertion is against public behaviour: the rows a window paints, the facts it reports for
 * them, the model a gesture produces, the cells a width receives and what a routed key does to the
 * demo scene. The component a test drives is the shipped {@code TextViewport}, and the scene a
 * verifier runs is the demo's own, so a mounted composition is the one an application writes rather
 * than a second copy of it.</p>
 */
class ParityViewportTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLS = 80;

    /** The cells one CJK glyph occupies under the pinned width policy. */
    private static final String WIDE = "\u671d";

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A wrapping, non-following viewport of one size, which is what most tests need. */
    private static TextViewport.Config wrap(int rows, int columns) {
        return TextViewport.Config.defaults()
                .withMode(TextViewport.Mode.WRAP)
                .withFollowEnd(false)
                .withRows(rows)
                .withColumns(columns);
    }

    /** A panning, non-following viewport of one size. */
    private static TextViewport.Config pan(int rows, int columns) {
        return wrap(rows, columns).withMode(TextViewport.Mode.PAN);
    }

    private static TextViewport pane(TextViewport.Config config) {
        return new TextViewport(config);
    }

    /** A state at the first row over {@code lines}. */
    private static TextViewport.Model model(int rows, int columns, String... lines) {
        return new TextViewport.Model(0, 0, 0, false, true, rows, columns, content(lines), List.of(), -1);
    }

    private static TextViewport.Content content(String... lines) {
        List<StyledText> values = new ArrayList<>(lines.length);
        for (String line : lines) {
            values.add(StyledText.plain(line));
        }
        return TextViewport.Content.of(values);
    }

    private static TextViewport.Model apply(TextViewport view, TextViewport.Model state, TextViewport.Msg message) {
        return view.update(state, message).model();
    }

    /** The rows one window paints, as plain text. */
    private static List<String> painted(TextViewport.Model state, TextViewport.Config config) {
        return TextViewport.visible(state, config).stream()
                .map(row -> row.text().plainText())
                .toList();
    }

    /** The same window through the production layout and renderer. */
    private static List<String> screen(TextViewport view, TextViewport.Model state, int rows, int cols) {
        View<TextViewport.Msg> view1 = view.view(state, new ViewContext(new Viewport(rows, cols)));
        return new ScreenRenderer().plainLines(view1, new Viewport(rows, cols));
    }

    private static RenderedScreen rendered(TextViewport view, TextViewport.Model state, int rows, int cols) {
        View<TextViewport.Msg> view1 = view.view(state, new ViewContext(new Viewport(rows, cols)));
        return new ScreenRenderer().screen(view1, new Viewport(rows, cols));
    }

    private static InputEvent.KeyInput key(String spec) {
        // A non-printable key carries no text; one that carried its name would type into a field.
        return InputEvent.KeyInput.of(Key.parse(spec));
    }

    /** A key with the text a terminal reports for it, which is what types into the find field. */
    private static InputEvent.KeyInput typed(String text) {
        Key parsed = Key.parse(text);
        return InputEvent.KeyInput.of(parsed, text);
    }

    private static TextViewport.Highlight highlight(int line, int start, int end) {
        return new TextViewport.Highlight(line, start, end);
    }

    /**
     * A pre-indexed document that counts what it is asked for, so a test asserts the bound a frame
     * holds rather than the seconds it takes.
     */
    private static final class Counting implements TextViewport.Content {

        private static final StyledText LINE = StyledText.plain("row");

        private final int lines;
        private int counts;
        private int reads;
        private int fetches;
        private int lowest = Integer.MAX_VALUE;
        private int highest = -1;

        Counting(int lines) {
            this.lines = lines;
        }

        void reset() {
            counts = 0;
            reads = 0;
            fetches = 0;
            lowest = Integer.MAX_VALUE;
            highest = -1;
        }

        @Override
        public int lineCount() {
            counts++;
            return lines;
        }

        @Override
        public StyledText line(int index) {
            reads++;
            fetches++;
            lowest = Math.min(lowest, index);
            highest = Math.max(highest, index);
            return LINE;
        }
    }

    // ── R-01: logical lines, wrapping, panning and content changes ──────────

    @Test
    void anEmptyDocumentPaintsNothingAndMeasuresNothing() {
        TextViewport view = pane(wrap(4, 10));
        TextViewport.Model empty =
                new TextViewport.Model(0, 0, 0, false, true, 4, 10, TextViewport.Content.empty(), List.of(), -1);
        TextViewport.Config config = view.config();

        assertThat(TextViewport.visible(empty, config)).isEmpty();
        assertThat(TextViewport.totalRows(empty, config)).isZero();
        assertThat(TextViewport.firstVisibleRow(empty, config)).isZero();
        assertThat(TextViewport.verticalPercent(empty, config)).isZero();
        assertThat(TextViewport.horizontalPercent(empty, config)).isZero();
        assertThat(TextViewport.widestColumns(empty)).isZero();
        assertThat(painted(empty, config)).isEmpty();
        // Every gesture over nothing is a no-op that keeps the instance it was given.
        assertThat(apply(view, empty, new TextViewport.Msg.Down(3))).isSameAs(empty);
        assertThat(apply(view, empty, new TextViewport.Msg.Bottom())).isSameAs(empty);
        assertThat(apply(view, empty, new TextViewport.Msg.NextMatch())).isSameAs(empty);
    }

    @Test
    void aZeroSizedPanePaintsAndScrollsNothing() {
        TextViewport view = pane(wrap(0, 0));
        TextViewport.Config config = view.config();
        TextViewport.Model state = model(0, 0, "alpha", "beta");

        assertThat(TextViewport.visible(state, config)).isEmpty();
        assertThat(painted(state, config)).isEmpty();
        assertThat(apply(view, state, new TextViewport.Msg.PageDown())).isSameAs(state);
        assertThat(apply(view, state, new TextViewport.Msg.Right(4))).isSameAs(state);
        // The percentages stay defined: there is nothing on screen, so nothing is scrolled.
        assertThat(TextViewport.verticalPercent(state, config)).isZero();
        assertThat(TextViewport.horizontalPercent(state, config)).isZero();
    }

    @Test
    void aLineWiderThanThePaneWrapsIntoRowsThatFitIt() {
        TextViewport.Config config = wrap(4, 10);
        TextViewport.Model state = model(4, 10, "abcdefghijklmnopqrstuvwxy");

        assertThat(TextViewport.visible(state, config))
                .extracting(TextViewport.Row::row)
                .containsExactly(0, 1, 2);
        assertThat(TextViewport.visible(state, config))
                .extracting(TextViewport.Row::rows)
                .containsExactly(3, 3, 3);
        assertThat(TextViewport.visible(state, config))
                .extracting(TextViewport.Row::columns)
                .allSatisfy(columns -> assertThat(columns).isBetween(1, 10));
        assertThat(String.join("", painted(state, config))).isEqualTo("abcdefghijklmnopqrstuvwxy");
        assertThat(TextViewport.totalRows(state, config)).isEqualTo(3);
    }

    @Test
    void aRowNeverSplitsAWideGlyph() {
        TextViewport.Config config = wrap(8, 3);
        // Three cells: the wide glyph occupies two of them and the next one starts a row.
        TextViewport.Model state = model(8, 3, "a" + WIDE + "b");

        List<String> rows = painted(state, config);
        assertThat(rows).containsExactly("a" + WIDE, "b");
        assertThat(TextViewport.visible(state, config))
                .extracting(TextViewport.Row::columns)
                .containsExactly(3, 1);
        assertThat(String.join("", rows)).isEqualTo("a" + WIDE + "b");
    }

    @Test
    void aTrailingEmptyLineKeepsItsRow() {
        TextViewport.Config config = wrap(4, 10);
        TextViewport.Content content = TextViewport.Content.of(List.of(StyledText.plain("alpha\n")));

        assertThat(content.lineCount())
                .as("a document that ends in a break has an empty last line")
                .isEqualTo(2);
        TextViewport.Model state = new TextViewport.Model(0, 0, 0, false, true, 4, 10, content, List.of(), -1);
        assertThat(painted(state, config)).containsExactly("alpha", "");
    }

    @Test
    void contentSplitsEveryValueThatStillHoldsABreak() {
        TextViewport.Content content =
                TextViewport.Content.of(List.of(StyledText.plain("one\ntwo"), StyledText.plain("three")));

        assertThat(content.lineCount()).isEqualTo(3);
        assertThat(content.line(0).plainText()).isEqualTo("one");
        assertThat(content.line(1).plainText()).isEqualTo("two");
        assertThat(content.line(2).plainText()).isEqualTo("three");
    }

    @Test
    void panModeKeepsOneRowPerLineAndClipsItAtThePaneWidth() {
        TextViewport.Config config = pan(3, 5);
        TextViewport.Model state = model(3, 5, "abcdefgh", "ij");

        assertThat(TextViewport.visible(state, config))
                .extracting(TextViewport.Row::text, TextViewport.Row::columns)
                .containsExactly(tuple(StyledText.plain("abcde"), 5), tuple(StyledText.plain("ij"), 2));
        assertThat(TextViewport.totalRows(state, config)).isEqualTo(2);
    }

    @Test
    void aFollowingWindowShowsTheEndOfNewContent() {
        TextViewport view = pane(wrap(2, 10).withFollowEnd(true));
        TextViewport.Model state =
                new TextViewport.Model(0, 0, 0, true, true, 2, 10, content("one", "two"), List.of(), -1);

        TextViewport.Model appended =
                apply(view, state, new TextViewport.Msg.ContentChanged(content("one", "two", "three")));

        assertThat(appended.followEnd()).isTrue();
        assertThat(painted(appended, view.config())).containsExactly("two", "three");
        assertThat(TextViewport.verticalPercent(appended, view.config())).isEqualTo(1.0);
    }

    @Test
    void anAppendDoesNotMoveAReaderWhoScrolledBack() {
        TextViewport view = pane(wrap(2, 10));
        TextViewport.Model state = apply(
                view,
                new TextViewport.Model(0, 0, 0, true, true, 2, 10, content("one", "two", "three"), List.of(), -1),
                new TextViewport.Msg.Up(1));

        assertThat(state.followEnd()).isFalse();
        TextViewport.Model appended =
                apply(view, state, new TextViewport.Msg.ContentChanged(content("one", "two", "three", "four")));

        assertThat(appended.followEnd()).isFalse();
        assertThat(appended.topLine()).isEqualTo(state.topLine());
        assertThat(painted(appended, view.config())).containsExactly("one", "two");
    }

    @Test
    void shrinkingTheContentClampsTheAnchorInsteadOfThrowing() {
        TextViewport view = pane(wrap(2, 10));
        TextViewport.Model state =
                new TextViewport.Model(0, 0, 0, false, true, 2, 10, content("a", "b", "c", "d"), List.of(), -1);
        state = apply(view, state, new TextViewport.Msg.Bottom());
        assertThat(state.topLine()).isEqualTo(2);

        TextViewport.Model shrunk = apply(view, state, new TextViewport.Msg.ContentChanged(content("only")));

        assertThat(shrunk.topLine()).isZero();
        assertThat(painted(shrunk, view.config())).containsExactly("only");
    }

    @Test
    void aGutterTakesItsCellsFromTheContentColumn() {
        TextViewport.Gutter gutter = TextViewport.Gutter.lineNumbers(6);
        TextViewport.Config config = wrap(3, 10).withGutter(gutter);
        TextViewport.Model state = model(3, 10, "abcdefghij");

        assertThat(TextViewport.contentColumns(state, config)).isEqualTo(4);
        // Four content cells wrap the ten-cell line into three rows; the gutter is not part of them.
        assertThat(painted(state, config)).containsExactly("abcd", "efgh", "ij");
        assertThat(TextViewport.visible(state, config))
                .extracting(TextViewport.Row::columns)
                .containsExactly(4, 4, 2);

        // The gutter is painted beside the content cell for cell, so the content never shifts.
        TextViewport view = pane(config);
        assertThat(screen(view, state, 3, 10)).containsExactly("  1 \u2502 abcd", "    \u2502 efgh", "    \u2502 ij");
    }

    @Test
    void aGutterRenderWiderOrNarrowerThanItsWidthCannotShiftTheContent() {
        TextViewport.Gutter wide =
                new TextViewport.Gutter(3, facts -> StyledText.plain(facts.line() + "|" + (facts.line() + 1) + "|"));
        TextViewport.Config config = wrap(2, 8).withGutter(wide);
        TextViewport.Model state = model(2, 8, "ab");
        TextViewport view = pane(config);

        // The gutter's own text is wider than its width, so it is cut at the width it declared.
        assertThat(screen(view, state, 2, 8)).containsExactly("0|1ab", "");
    }

    @Test
    void stylesAndLinksSurviveWrappingAndSlicing() {
        Style bold = Style.NONE.withBold();
        StyledText line = StyledText.concat(
                StyledText.of("abc", Style.NONE),
                StyledText.of("def", bold),
                StyledText.of("gh", Style.NONE, "https://example.invalid"));
        TextViewport.Config config = wrap(3, 5);
        TextViewport.Model state = new TextViewport.Model(
                0, 0, 0, false, true, 3, 5, TextViewport.Content.of(List.of(line)), List.of(), -1);

        TextViewport view = pane(config);
        RenderedScreen screen = rendered(view, state, 3, 5);

        assertThat(screen.plainLine(0)).isEqualTo("abcde");
        assertThat(screen.plainLine(1)).isEqualTo("fgh");
        assertThat(screen.cell(0, 2).style()).isEqualTo(Style.NONE);
        assertThat(screen.cell(0, 4).style()).isEqualTo(bold);
        assertThat(screen.cell(1, 0).style()).isEqualTo(bold);
        assertThat(TextViewport.visible(state, config).get(1).text().spans())
                .as("the link survives the slice it was cut by")
                .anySatisfy(span -> assertThat(span.link()).isEqualTo("https://example.invalid"));
    }

    // ── R-02: scrolling, panning, percentages and geometry ──────────────────

    @Test
    void rowsMoveByVisualRowsAcrossWrappedLines() {
        TextViewport view = pane(wrap(2, 5));
        TextViewport.Model state = model(2, 5, "abcdefgh", "ijkl", "mnop", "qrst");

        TextViewport.Model down = apply(view, state, new TextViewport.Msg.Down(1));
        assertThat(down.topLine()).isZero();
        assertThat(down.topRow()).isEqualTo(1);
        assertThat(painted(down, view.config())).containsExactly("fgh", "ijkl");

        TextViewport.Model next = apply(view, down, new TextViewport.Msg.Down(1));
        assertThat(next.topLine()).isEqualTo(1);
        assertThat(next.topRow()).isZero();
        assertThat(painted(next, view.config())).containsExactly("ijkl", "mnop");

        TextViewport.Model back = apply(view, next, new TextViewport.Msg.Up(2));
        assertThat(back.topLine()).isZero();
        assertThat(back.topRow()).isZero();
        assertThat(apply(view, next, new TextViewport.Msg.Up(1)).topRow()).isEqualTo(1);
    }

    @Test
    void aPageMovesByThePaneHeightAndAHalfPageByHalfOfIt() {
        TextViewport view = pane(wrap(2, 5));
        TextViewport.Model state = model(2, 5, "a", "b", "c", "d", "e", "f");

        assertThat(apply(view, state, new TextViewport.Msg.PageDown()).topLine())
                .isEqualTo(2);
        assertThat(apply(view, state, new TextViewport.Msg.HalfPageDown()).topLine())
                .isEqualTo(1);
        assertThat(apply(view, state, new TextViewport.Msg.PageUp()).topLine()).isZero();
        // The last window is the fourth line: the pane is two rows tall and the content six.
        assertThat(apply(view, state, new TextViewport.Msg.Bottom()).topLine()).isEqualTo(4);
        assertThat(apply(view, state, new TextViewport.Msg.HalfPageUp()).topLine())
                .isZero();
    }

    @Test
    void topAndBottomClampToTheContentAndArmFollowEnd() {
        TextViewport view = pane(wrap(2, 5).withFollowEnd(true));
        TextViewport.Model state =
                new TextViewport.Model(0, 0, 0, true, true, 2, 5, content("a", "b", "c"), List.of(), -1);

        TextViewport.Model up = apply(view, state, new TextViewport.Msg.Top());
        assertThat(up.topLine()).isZero();
        assertThat(up.followEnd())
                .as("a deliberate upward move releases following")
                .isFalse();

        TextViewport.Model down = apply(view, up, new TextViewport.Msg.Bottom());
        assertThat(down.topLine()).isEqualTo(1);
        assertThat(down.followEnd()).as("End arms following again").isTrue();

        // Content that fits has one window; neither end can move, so neither changes the flags.
        TextViewport.Model shortContent =
                new TextViewport.Model(0, 0, 0, true, true, 4, 5, content("a"), List.of(), -1);
        assertThat(apply(view, shortContent, new TextViewport.Msg.Top())).isSameAs(shortContent);
        assertThat(apply(view, shortContent, new TextViewport.Msg.Down(3))).isSameAs(shortContent);
    }

    @Test
    void panningClampsToTheWidestLine() {
        TextViewport view = pane(pan(2, 4));
        TextViewport.Model state = model(2, 4, "abcdefghij", "ab");

        assertThat(TextViewport.widestColumns(state)).isEqualTo(10);
        TextViewport.Model right = apply(view, state, new TextViewport.Msg.Right(3));
        assertThat(right.firstColumn()).isEqualTo(3);
        assertThat(painted(right, view.config())).containsExactly("defg", "");

        TextViewport.Model far = apply(view, state, new TextViewport.Msg.Right(100));
        assertThat(far.firstColumn()).as("six cells of ten are scrollable").isEqualTo(6);
        assertThat(painted(far, view.config())).containsExactly("ghij", "");

        TextViewport.Model left = apply(view, far, new TextViewport.Msg.Left(100));
        assertThat(left.firstColumn()).isZero();
        assertThat(TextViewport.horizontalPercent(far, view.config())).isEqualTo(1.0);
        assertThat(TextViewport.horizontalPercent(state, view.config())).isZero();
    }

    @Test
    void horizontalGesturesDoNothingWhileWrapping() {
        TextViewport view = pane(wrap(2, 4));
        TextViewport.Model state = model(2, 4, "abcdefghij");

        assertThat(apply(view, state, new TextViewport.Msg.Right(2))).isSameAs(state);
        assertThat(apply(view, state, new TextViewport.Msg.Left(2))).isSameAs(state);
        assertThat(TextViewport.horizontalPercent(state, view.config())).isZero();
    }

    @Test
    void percentagesAreZeroWhenNothingOverflows() {
        TextViewport.Config config = wrap(4, 10);
        TextViewport.Model state = model(4, 10, "one", "two");

        assertThat(TextViewport.totalRows(state, config)).isEqualTo(2);
        assertThat(TextViewport.verticalPercent(state, config)).isZero();
        assertThat(TextViewport.horizontalPercent(state, config)).isZero();
    }

    @Test
    void theVerticalPercentageReachesOneAtTheLastWindow() {
        TextViewport config = pane(wrap(2, 5));
        TextViewport.Model state = model(2, 5, "a", "b", "c", "d", "e", "f");

        assertThat(TextViewport.firstVisibleRow(state, config.config())).isZero();
        TextViewport.Model middle = apply(config, state, new TextViewport.Msg.Down(2));
        assertThat(TextViewport.firstVisibleRow(middle, config.config())).isEqualTo(2);
        assertThat(TextViewport.verticalPercent(middle, config.config())).isEqualTo(0.5);
        TextViewport.Model last = apply(config, state, new TextViewport.Msg.Bottom());
        assertThat(TextViewport.verticalPercent(last, config.config())).isEqualTo(1.0);
    }

    @Test
    void aHalfPageOnATallPaneIsDefinedAndAOneRowPaneHasNone() {
        TextViewport oneRow = pane(wrap(1, 5));
        TextViewport.Model state = model(1, 5, "a", "b", "c");

        // One row has no half page, so the gesture is a no-op rather than a rounding surprise.
        assertThat(apply(oneRow, state, new TextViewport.Msg.HalfPageDown())).isSameAs(state);
        assertThat(apply(oneRow, state, new TextViewport.Msg.PageDown()).topLine())
                .isEqualTo(1);
    }

    @Test
    void aResizeKeepsTheLogicalAnchorItReflowed() {
        TextViewport wide = pane(wrap(4, 6));
        TextViewport.Model state = model(4, 6, "abcdefghijkl", "mnopqrstuvwx", "yz0123456789");
        // Each line is two rows at six columns, so the last window opens on line one.
        state = apply(wide, state, new TextViewport.Msg.Bottom());
        assertThat(state.topLine()).isEqualTo(1);
        assertThat(state.topRow()).isZero();

        TextViewport narrow = pane(wrap(4, 4));
        TextViewport.Model resized = apply(narrow, state, new TextViewport.Msg.Resized(4, 4));

        assertThat(resized.topLine()).as("the logical line survives the reflow").isEqualTo(1);
        assertThat(resized.topRow()).isZero();
        assertThat(resized.columns()).isEqualTo(4);
        assertThat(painted(resized, narrow.config()))
                .as("at four columns each line is three rows, so the window starts on line one")
                .containsExactly("mnop", "qrst", "uvwx", "yz01");

        // Growing the pane keeps the line and clamps the row it no longer has.
        TextViewport.Model grown = apply(pane(wrap(4, 20)), resized, new TextViewport.Msg.Resized(4, 20));
        assertThat(grown.topLine()).isEqualTo(1);
        assertThat(grown.topRow()).isZero();
        assertThat(painted(grown, wrap(4, 20))).containsExactly("mnopqrstuvwx", "yz0123456789");
    }

    @Test
    void aResizeWhileFollowingStaysAtTheEnd() {
        TextViewport view = pane(wrap(2, 10).withFollowEnd(true));
        TextViewport.Model state = new TextViewport.Model(
                0, 0, 0, true, true, 2, 10, content("one", "two", "three", "four"), List.of(), -1);

        TextViewport.Model shorter = apply(view, state, new TextViewport.Msg.Resized(3, 10));
        assertThat(shorter.followEnd()).isTrue();
        assertThat(painted(shorter, view.config())).containsExactly("two", "three", "four");
    }

    @Test
    void aGestureThatChangesNothingKeepsTheModelInstance() {
        TextViewport wrapPane = pane(wrap(3, 6));
        TextViewport panPane = pane(pan(3, 6));
        TextViewport.Model top = model(3, 6, "abcdefgh", "ij", "kl");
        TextViewport.Model bottom = apply(wrapPane, top, new TextViewport.Msg.Bottom());
        TextViewport.Model rightmost = apply(panPane, top, new TextViewport.Msg.Right(99));

        assertThat(apply(wrapPane, top, new TextViewport.Msg.Up(1))).isSameAs(top);
        assertThat(apply(wrapPane, top, new TextViewport.Msg.Left(1))).isSameAs(top);
        assertThat(apply(wrapPane, top, new TextViewport.Msg.Up(0))).isSameAs(top);
        assertThat(apply(wrapPane, top, new TextViewport.Msg.Down(0))).isSameAs(top);
        assertThat(apply(wrapPane, bottom, new TextViewport.Msg.Down(1))).isSameAs(bottom);
        assertThat(apply(wrapPane, bottom, new TextViewport.Msg.PageDown())).isSameAs(bottom);
        assertThat(apply(panPane, rightmost, new TextViewport.Msg.Right(2))).isSameAs(rightmost);
    }

    @Test
    void aFrameReadsOnlyTheLinesItsWindowReaches() {
        Counting content = new Counting(100_000);
        TextViewport.Model state = new TextViewport.Model(0, 50_000, 0, false, true, 20, 80, content, List.of(), -1);

        for (TextViewport.Mode mode : TextViewport.Mode.values()) {
            content.reset();
            TextViewport.Config config = wrap(20, 80).withMode(mode);
            View<TextViewport.Msg> view = pane(config).view(state, CONTEXT);
            assertThat(view).isNotNull();

            assertThat(content.reads)
                    .as("a %s frame reads the window, not the document", mode)
                    .isBetween(1, 22);
            assertThat(content.counts)
                    .as("a %s frame asks for the line count a constant number of times", mode)
                    .isBetween(1, 2);
            assertThat(content.highest - content.lowest)
                    .as("a %s frame reads a contiguous run of lines", mode)
                    .isBetween(0, 25);
        }
    }

    @Test
    void oneVerticalGestureReadsNoMoreThanAWindowOfLines() {
        Counting content = new Counting(100_000);
        TextViewport view = pane(wrap(20, 80));
        TextViewport.Model state = new TextViewport.Model(0, 50_000, 0, false, true, 20, 80, content, List.of(), -1);

        content.reset();
        apply(view, state, new TextViewport.Msg.Down(1));

        // The move itself touches one line; the clamp to the last window walks back one window.
        assertThat(content.fetches)
                .as("one row down reads a window, never the document")
                .isLessThanOrEqualTo(24);
    }

    @Test
    void theGestureMapDisablesWhatTheStateCannotDo() {
        TextViewport.Model state = model(3, 10, "alpha");

        KeyMap<TextViewport.Msg> wrapping = TextViewport.keys(state, wrap(3, 10));
        KeyMap<TextViewport.Msg> panning = TextViewport.keys(state, pan(3, 10));

        assertThat(wrapping.action("left"))
                .hasValueSatisfying(action -> assertThat(action.enabled()).isFalse());
        assertThat(wrapping.action("right"))
                .hasValueSatisfying(action -> assertThat(action.enabled()).isFalse());
        assertThat(panning.action("left"))
                .hasValueSatisfying(action -> assertThat(action.enabled()).isTrue());
        assertThat(wrapping.action("next-match"))
                .as("no published range is nothing to step to")
                .hasValueSatisfying(action -> assertThat(action.enabled()).isFalse());

        TextViewport.Model matched =
                new TextViewport.Model(0, 0, 0, false, true, 3, 10, content("alpha"), List.of(highlight(0, 0, 5)), -1);
        assertThat(TextViewport.keys(matched, wrap(3, 10)).action("next-match"))
                .hasValueSatisfying(action -> assertThat(action.enabled()).isTrue());
    }

    @Test
    void theGestureMapIsWhatHelpPaints() {
        TextViewport.Config config = pan(3, 20);
        StyledText help = Help.render(TextViewport.keys(model(3, 20, "alpha"), config), false, 200);

        assertThat(help.plainText())
                .contains("up")
                .contains("down")
                .contains("page up")
                .contains("left")
                .contains("right");
    }

    // ── R-03: gutters, ranges and match navigation ──────────────────────────

    @Test
    void aPublishedRangeIsPaintedInTheMatchStyle() {
        Style match = Style.NONE.withUnderline();
        Style selected = Style.NONE.withInvert();
        TextViewport.Config config = wrap(1, 20).withStyles(new TextViewport.Config.Styles(match, selected));
        TextViewport.Model state = new TextViewport.Model(
                0,
                0,
                0,
                false,
                true,
                1,
                20,
                content("one two one"),
                List.of(highlight(0, 0, 3), highlight(0, 8, 11)),
                1);
        TextViewport view = pane(config);
        RenderedScreen screen = rendered(view, state, 1, 20);

        assertThat(screen.plainLine(0)).isEqualTo("one two one");
        assertThat(screen.cell(0, 0).style())
                .as("an inactive range is the match style")
                .isEqualTo(match);
        assertThat(screen.cell(0, 4).style()).isEqualTo(Style.NONE);
        assertThat(screen.cell(0, 7).style()).isEqualTo(Style.NONE);
        assertThat(screen.cell(0, 8).style())
                .as("the active range is the selected style")
                .isEqualTo(match.merge(selected));
        assertThat(screen.cell(0, 10).style()).isEqualTo(match.merge(selected));
    }

    @Test
    void overlappingRangesPaintInPublicationOrderAndTheActiveOneLast() {
        Style match = Style.NONE.withUnderline();
        Style selected = Style.NONE.withInvert();
        TextViewport.Config config = wrap(1, 20).withStyles(new TextViewport.Config.Styles(match, selected));
        TextViewport.Model state = new TextViewport.Model(
                0,
                0,
                0,
                false,
                true,
                1,
                20,
                content("abcdefghij"),
                List.of(highlight(0, 0, 6), highlight(0, 4, 10)),
                1);

        RenderedScreen screen = rendered(pane(config), state, 1, 20);

        // The ranges are painted in publication order and the active one last, so the overlap keeps
        // the style of the range the reader is on and no later range can hide it.
        assertThat(screen.plainLine(0)).isEqualTo("abcdefghij");
        assertThat(screen.cell(0, 0).style())
                .as("only the first range covers this cell")
                .isEqualTo(match);
        assertThat(screen.cell(0, 4).style())
                .as("both ranges cover this cell, and the second is the active one")
                .isEqualTo(match.merge(selected));
        assertThat(screen.cell(0, 9).style()).isEqualTo(match.merge(selected));
    }

    @Test
    void aRangeOnAWideGlyphStylesEveryCellItCovers() {
        Style match = Style.NONE.withUnderline();
        TextViewport.Config config = wrap(1, 10).withStyles(new TextViewport.Config.Styles(match, Style.NONE));
        String text = "a" + WIDE + "b";
        TextViewport.Model state =
                new TextViewport.Model(0, 0, 0, false, true, 1, 10, content(text), List.of(highlight(0, 1, 2)), -1);

        RenderedScreen screen = rendered(pane(config), state, 1, 10);

        assertThat(screen.cell(0, 0).style()).isEqualTo(Style.NONE);
        assertThat(screen.cell(0, 1).style()).as("the glyph's own cell").isEqualTo(match);
        assertThat(screen.cell(0, 2).text())
                .as("the glyph's second cell continues it")
                .isEmpty();
        assertThat(screen.cell(0, 3).style()).isEqualTo(Style.NONE);
    }

    @Test
    void nextMatchWrapsAndBringsAHiddenRangeIntoView() {
        TextViewport view = pane(wrap(2, 10));
        TextViewport.Model state = new TextViewport.Model(
                0,
                0,
                0,
                false,
                true,
                2,
                10,
                content("a", "b", "c", "d", "e"),
                List.of(highlight(0, 0, 1), highlight(4, 0, 1)),
                -1);

        TextViewport.Model first = apply(view, state, new TextViewport.Msg.NextMatch());
        assertThat(first.active()).isZero();
        assertThat(painted(first, view.config())).containsExactly("a", "b");

        TextViewport.Model second = apply(view, first, new TextViewport.Msg.NextMatch());
        assertThat(second.active()).isEqualTo(1);
        assertThat(painted(second, view.config()))
                .as("the range on the last line is scrolled into view")
                .containsExactly("d", "e");

        TextViewport.Model wrapped = apply(view, second, new TextViewport.Msg.NextMatch());
        assertThat(wrapped.active()).isZero();
        assertThat(painted(wrapped, view.config())).containsExactly("a", "b");
    }

    @Test
    void previousMatchWrapsBackwardsAndSelectsTheLastFromNone() {
        TextViewport view = pane(wrap(2, 10));
        TextViewport.Model state = new TextViewport.Model(
                0,
                0,
                0,
                false,
                true,
                2,
                10,
                content("a", "b", "c", "d", "e"),
                List.of(highlight(0, 0, 1), highlight(4, 0, 1)),
                -1);

        TextViewport.Model last = apply(view, state, new TextViewport.Msg.PreviousMatch());
        assertThat(last.active())
                .as("the last range is the first step back from none")
                .isEqualTo(1);
        assertThat(painted(last, view.config())).containsExactly("d", "e");

        TextViewport.Model first = apply(view, last, new TextViewport.Msg.NextMatch());
        assertThat(first.active())
                .as("the last range wraps forward to the first")
                .isZero();
        assertThat(apply(view, first, new TextViewport.Msg.PreviousMatch()).active())
                .as("the first range wraps back to the last")
                .isEqualTo(1);
    }

    @Test
    void aRangeAboveTheWindowIsReachedByMovingTheAnchorUp() {
        TextViewport view = pane(wrap(2, 10));
        TextViewport.Model state = new TextViewport.Model(
                4, 0, 0, false, true, 2, 10, content("a", "b", "c", "d", "e", "f"), List.of(highlight(1, 0, 1)), -1);

        TextViewport.Model reached = apply(view, state, new TextViewport.Msg.NextMatch());

        assertThat(reached.active()).isZero();
        assertThat(painted(reached, view.config())).containsExactly("b", "c");
        assertThat(reached.followEnd()).isFalse();
    }

    @Test
    void anEmptyRangeIsTraversableAndScrollsToItsLine() {
        TextViewport view = pane(wrap(2, 10));
        TextViewport.Model state = new TextViewport.Model(
                0, 0, 0, false, true, 2, 10, content("a", "b", "c", "d"), List.of(highlight(3, 0, 0)), -1);

        TextViewport.Model reached = apply(view, state, new TextViewport.Msg.NextMatch());

        assertThat(reached.active()).isZero();
        assertThat(painted(reached, view.config())).containsExactly("c", "d");
        assertThat(TextViewport.visible(reached, view.config()).get(0).text().spans())
                .as("a zero-width range paints nothing")
                .allSatisfy(span -> assertThat(span.style()).isEqualTo(Style.NONE));
    }

    @Test
    void aRangeHiddenByTheHorizontalOffsetIsPannedIntoView() {
        TextViewport view = pane(pan(2, 10));
        TextViewport.Model state = new TextViewport.Model(
                0,
                0,
                0,
                false,
                true,
                2,
                10,
                content("0123456789abcdefghij", "second"),
                List.of(highlight(0, 17, 19)),
                -1);

        TextViewport.Model reached = apply(view, state, new TextViewport.Msg.NextMatch());

        // Moving as little as it can: nine cells off the left brings cells 17 and 18 into view.
        assertThat(reached.firstColumn()).isEqualTo(9);
        assertThat(painted(reached, view.config())).containsExactly("9abcdefghi", "");
        assertThat(TextViewport.horizontalPercent(reached, view.config())).isEqualTo(0.9);
    }

    @Test
    void publishingRangesRejectsOneThatDoesNotDescribeTheContent() {
        TextViewport view = pane(wrap(2, 10));
        TextViewport.Model state = model(2, 10, "alpha", "beta");

        assertThatThrownBy(() -> apply(view, state, new TextViewport.Msg.Highlights(List.of(highlight(2, 0, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the content");
        assertThatThrownBy(() -> apply(view, state, new TextViewport.Msg.Highlights(List.of(highlight(0, 0, 6)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside line");
        assertThatThrownBy(() -> apply(view, state, new TextViewport.Msg.Highlights(List.of(highlight(-1, 0, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be negative");
        assertThatThrownBy(() -> new TextViewport.Highlight(0, 3, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not precede");
    }

    @Test
    void publishingRangesRejectsOneThatSplitsAGrapheme() {
        TextViewport view = pane(wrap(2, 10));
        // "a" plus a combining acute accent is one cluster of two UTF-16 units.
        TextViewport.Model state = model(2, 10, "a\u0301bc");

        assertThatThrownBy(() -> apply(view, state, new TextViewport.Msg.Highlights(List.of(highlight(0, 1, 2)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grapheme boundaries");
    }

    @Test
    void newContentInvalidatesTheRangesOfTheVersionItReplaces() {
        TextViewport view = pane(wrap(2, 10));
        TextViewport.Model state =
                new TextViewport.Model(0, 0, 0, false, true, 2, 10, content("alpha"), List.of(highlight(0, 0, 5)), 0);

        TextViewport.Model replaced = apply(view, state, new TextViewport.Msg.ContentChanged(content("beta")));

        assertThat(replaced.highlights()).isEmpty();
        assertThat(replaced.active()).isEqualTo(-1);
        assertThat(replaced.activeHighlight()).isEmpty();
    }

    @Test
    void aGutterIsToldTheFactsOfEveryPaintedRow() {
        List<TextViewport.Facts> seen = new ArrayList<>();
        TextViewport.Gutter gutter = new TextViewport.Gutter(3, facts -> {
            seen.add(facts);
            return StyledText.plain(facts.first() ? Integer.toString(facts.line() + 1) : " ");
        });
        TextViewport.Config config = wrap(3, 10).withGutter(gutter);
        TextViewport.Model state = model(3, 10, "abcdefgh", "ij");
        TextViewport view = pane(config);

        assertThat(screen(view, state, 3, 10)).containsExactly("1  abcdefg", "   h", "2  ij");
        assertThat(seen)
                .as("a continuation row reports the line it continues")
                .containsExactly(
                        new TextViewport.Facts(0, 0, 2, 2),
                        new TextViewport.Facts(0, 1, 2, 2),
                        new TextViewport.Facts(1, 0, 1, 2));
    }

    @Test
    void theLineNumberGutterBlanksAContinuationRow() {
        TextViewport.Config config = wrap(2, 14).withGutter(TextViewport.Gutter.lineNumbers(6));
        TextViewport.Model state = model(2, 14, "abcdefghijklmn");

        assertThat(screen(pane(config), state, 2, 14)).containsExactly("  1 \u2502 abcdefgh", "    \u2502 ijklmn");
    }

    // ── R-05: purity, contracts and lifecycle ───────────────────────────────

    @Test
    void renderingTheSameStateTwicePaintsTheSameCells() {
        TextViewport.Config config = wrap(3, 12).withGutter(TextViewport.Gutter.lineNumbers(4));
        TextViewport.Model state = new TextViewport.Model(
                0, 0, 0, false, true, 3, 12, content("one two", "three four five"), List.of(highlight(0, 0, 3)), 0);
        TextViewport view = pane(config);

        assertThat(screen(view, state, 3, 12)).isEqualTo(screen(view, state, 3, 12));
        assertThat(state)
                .isEqualTo(new TextViewport.Model(
                        0, 0, 0, false, true, 3, 12, state.content(), List.of(highlight(0, 0, 3)), 0));
    }

    @Test
    void aReductionNeverChangesTheFocusTheApplicationOwns() {
        TextViewport view = pane(wrap(2, 5).withFollowEnd(true));
        TextViewport.Model state = model(2, 5, "a", "b", "c").withFocus(false);

        for (TextViewport.Msg message : List.of(
                new TextViewport.Msg.Down(1),
                new TextViewport.Msg.PageDown(),
                new TextViewport.Msg.Top(),
                new TextViewport.Msg.Bottom(),
                new TextViewport.Msg.ContentChanged(content("x")),
                new TextViewport.Msg.Resized(4, 4))) {
            assertThat(apply(view, state, message).focused()).isFalse();
        }
    }

    @Test
    void thePaneOwnsNoSubscriptionsAndIssuesNoCommands() {
        TextViewport view = pane(wrap(2, 5));
        TextViewport.Model state = model(2, 5, "a", "b", "c");

        assertThat(view.subscriptions(state)).isEqualTo(Subscription.none());
        for (TextViewport.Msg message :
                List.of(new TextViewport.Msg.Down(1), new TextViewport.Msg.Bottom(), new TextViewport.Msg.Right(2))) {
            Update<TextViewport.Model, TextViewport.Msg, Never> update = view.update(state, message);
            assertThat(update.command()).isEqualTo(Command.none());
            assertThat(update.outputs()).isEmpty();
        }
    }

    @Test
    void aModelClampsWhatItCannotRepresent() {
        TextViewport.Model state =
                new TextViewport.Model(-3, -2, -1, false, true, -5, -6, content("a"), List.of(highlight(0, 0, 1)), 7);

        assertThat(state.topLine()).isZero();
        assertThat(state.topRow()).isZero();
        assertThat(state.firstColumn()).isZero();
        assertThat(state.rows()).isZero();
        assertThat(state.columns()).isZero();
        assertThat(state.active())
                .as("an index into no range is no active range")
                .isEqualTo(-1);
        assertThat(TextViewport.Model.of(null).lineCount()).isZero();
    }

    @Test
    void theDefinitionRejectsAnImpossibleGeometry() {
        assertThatThrownBy(() -> TextViewport.Config.defaults().withRows(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
        assertThatThrownBy(() -> TextViewport.Config.defaults().withColumns(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("columns");
        assertThatThrownBy(() -> TextViewport.Config.defaults().withReservedRows(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservedRows");
        assertThatThrownBy(() -> new TextViewport.Gutter(-1, facts -> StyledText.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width");
        assertThatThrownBy(() -> TextViewport.Content.of(List.of((StyledText) null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullArgumentsAreRejected() {
        TextViewport view = pane(wrap(2, 5));
        TextViewport.Model state = model(2, 5, "a");

        assertThatThrownBy(() -> view.update(null, new TextViewport.Msg.Up(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.update(state, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.view(null, CONTEXT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> view.view(state, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TextViewport.visible(null, wrap(1, 1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> TextViewport.keys(null, wrap(1, 1))).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRepeatedGeometryMessageKeepsTheModelInstance() {
        TextViewport view = pane(wrap(2, 5));
        TextViewport.Model state =
                new TextViewport.Model(0, 0, 0, false, true, 2, 5, content("a", "b", "c"), List.of(), -1);

        assertThat(apply(view, state, new TextViewport.Msg.Resized(2, 5))).isSameAs(state);
        // A stale size still arrives: it is applied, and it clamps the anchor it invalidated.
        assertThat(apply(view, state, new TextViewport.Msg.Resized(-4, -9)).rows())
                .isZero();
        // A following window is normalized to the end even when the stored anchor is not there.
        TextViewport.Model following =
                new TextViewport.Model(0, 0, 0, true, true, 2, 5, state.content(), List.of(), -1);
        assertThat(apply(view, following, new TextViewport.Msg.Resized(2, 5)))
                .isEqualTo(new TextViewport.Model(1, 0, 0, true, true, 2, 5, state.content(), List.of(), -1));
    }

    @Test
    void anUnfocusedPaneReceivesNoGestures() {
        TextViewport.Config config = wrap(2, 5);
        TextViewport view = pane(config);
        TextViewport.Model focused = model(2, 5, "a", "b", "c");
        TextViewport.Model unfocused = focused.withFocus(false);

        assertThat(InputRouter.route(view.view(focused, CONTEXT), key("down"))).contains(new TextViewport.Msg.Down(1));
        assertThat(InputRouter.route(view.view(unfocused, CONTEXT), key("down")))
                .isEmpty();
        // Ctrl- and Alt-prefixed gestures are the application's shortcut space.
        assertThat(InputRouter.route(view.view(focused, CONTEXT), key("ctrl+down")))
                .isEmpty();
    }

    // ── R-04: the demo scene ────────────────────────────────────────────────

    private static ViewportDemo.Scene demo() {
        return new ViewportDemo.Scene();
    }

    private static ViewportDemo.Model start() {
        return start(SCREEN_ROWS, SCREEN_COLS);
    }

    private static ViewportDemo.Model start(int rows, int cols) {
        return demo().init(new InitContext(new Viewport(rows, cols), null)).model();
    }

    private static ViewportDemo.Model apply(ViewportDemo.Model state, ViewportDemo.Msg message) {
        return demo().update(state, message).model();
    }

    private static Optional<ViewportDemo.Msg> route(ViewportDemo.Model state, InputEvent event) {
        return InputRouter.route(demo().view(state, CONTEXT), event);
    }

    private static ViewportDemo.Model send(ViewportDemo.Model state, String keySpec) {
        return apply(
                state, route(state, key(keySpec)).orElseThrow(() -> new AssertionError("no message for " + keySpec)));
    }

    private static ViewportDemo.Model type(ViewportDemo.Model state, String text) {
        ViewportDemo.Model typed = state;
        for (char character : text.toCharArray()) {
            String value = String.valueOf(character);
            typed = apply(
                    typed, route(typed, typed(value)).orElseThrow(() -> new AssertionError("no message for " + value)));
        }
        return typed;
    }

    private static List<String> demoLines(ViewportDemo.Model state) {
        return new ScreenRenderer().plainLines(demo().view(state, CONTEXT), new Viewport(SCREEN_ROWS, SCREEN_COLS));
    }

    private static String demoRow(ViewportDemo.Model state, int row) {
        return demoLines(state).get(row).stripTrailing();
    }

    @Test
    void theDemoOpensAtTheTopWithLineNumbersAndNoMatches() {
        ViewportDemo.Model model = start();

        assertThat(demoRow(model, 0))
                .isEqualTo("mode=wrap numbers=on follow=off line=1/" + ViewportDemo.LINES.size()
                        + " total=" + TextViewport.totalRows(model.main(), ViewportDemo.mainConfig(model, 0, 0))
                        + " vscroll=0% hscroll=0%");
        assertThat(demoRow(model, 1)).startsWith("matches=0 active=0 query=- focus=main");
        assertThat(demoRow(model, 2)).isEqualTo("find:");
        assertThat(demoRow(model, 3))
                .as("the first line, with its number and its gutter")
                .isEqualTo("  1 \u2502 A pocket guide to the artichoke");
        assertThat(demoRow(model, ViewportDemo.HEADER_ROWS + ViewportDemo.mainRows(SCREEN_ROWS)))
                .as("the outline starts at its first entry")
                .startsWith("outline=pan line=1/" + ViewportDemo.OUTLINE.size());
    }

    @Test
    void theDemoWrapsOrPansTheSameDocument() {
        ViewportDemo.Model model = start();
        assertThat(model.main().firstColumn()).isZero();

        ViewportDemo.Model panned = send(model, "w");
        assertThat(panned.mode()).isEqualTo(TextViewport.Mode.PAN);
        assertThat(demoRow(panned, 0)).startsWith("mode=pan numbers=on");

        ViewportDemo.Model scrolled = send(panned, "right");
        assertThat(scrolled.main().firstColumn()).isPositive();
        assertThat(scrolled.main().topLine())
                .as("the mode switch keeps the logical line the reader was on")
                .isEqualTo(model.main().topLine());
        assertThat(demoRow(scrolled, 0)).contains("hscroll=");
        // Panned, the long row is one row, so the document needs fewer visual rows than wrapped.
        assertThat(TextViewport.totalRows(scrolled.main(), ViewportDemo.mainConfig(scrolled, 0, 0)))
                .isLessThan(TextViewport.totalRows(model.main(), ViewportDemo.mainConfig(model, 0, 0)));

        ViewportDemo.Model wrapped = send(scrolled, "w");
        assertThat(wrapped.mode()).isEqualTo(TextViewport.Mode.WRAP);
        assertThat(TextViewport.horizontalPercent(wrapped.main(), ViewportDemo.mainConfig(wrapped, 0, 0)))
                .isZero();
    }

    @Test
    void theDemoFindsEveryKeywordAndWalksThem() {
        ViewportDemo.Model model = start();

        ViewportDemo.Model searched = type(send(model, "/"), ViewportDemo.KEYWORD);
        int expected =
                ViewportDemo.matches(ViewportDemo.LINES, ViewportDemo.KEYWORD).size();

        assertThat(searched.main().highlights()).hasSize(expected);
        assertThat(demoRow(searched, 1)).startsWith("matches=" + expected + " active=1 query=" + ViewportDemo.KEYWORD);
        assertThat(expected).isPositive();

        // Typing already selected the first match; the content pane walks them from there.
        ViewportDemo.Model first = send(searched, "escape");
        assertThat(first.main().active()).isZero();
        ViewportDemo.Model second = send(first, "n");
        assertThat(second.main().active()).isEqualTo(1);
        assertThat(send(second, "alt+n").main().active()).isZero();
        // Walking back from the first match wraps to the last one.
        assertThat(send(first, "alt+n").main().active()).isEqualTo(expected - 1);
    }

    @Test
    void aMatchBelowTheWindowIsScrolledIntoView() {
        ViewportDemo.Model model = start();
        int last = ViewportDemo.LINES.size() - 1;
        assertThat(ViewportDemo.LINES.get(last).plainText()).contains(ViewportDemo.KEYWORD);

        ViewportDemo.Model searched = send(type(send(model, "/"), ViewportDemo.KEYWORD), "escape");
        assertThat(searched.main().topLine())
                .as("the first match is already on screen")
                .isZero();
        ViewportDemo.Model walked = searched;
        for (int index = 1; index < searched.main().highlights().size(); index++) {
            walked = send(walked, "n");
        }

        TextViewport.Highlight active =
                walked.main().highlights().get(walked.main().active());
        assertThat(active.line()).isEqualTo(ViewportDemo.LINES.size() - 1);
        assertThat(walked.main().active())
                .isEqualTo(searched.main().highlights().size() - 1);
        assertThat(walked.main().followEnd()).isFalse();
        assertThat(TextViewport.visible(walked.main(), ViewportDemo.mainConfig(walked, 0, 0)))
                .as("the line the active match is on is on screen")
                .extracting(TextViewport.Row::line)
                .contains(active.line());
    }

    @Test
    void anAppendedNoteFollowsOnlyWhileFollowing() {
        ViewportDemo.Model model = start();
        assertThat(model.main().followEnd()).isFalse();

        ViewportDemo.Model scrolled = send(model, "a");
        assertThat(scrolled.lines()).hasSize(ViewportDemo.LINES.size() + 1);
        assertThat(scrolled.main().followEnd()).isFalse();
        assertThat(scrolled.main().topLine()).isZero();

        // End arms following, and the next append stays on the last row.
        ViewportDemo.Model following = send(scrolled, "end");
        assertThat(following.main().followEnd()).isTrue();
        ViewportDemo.Model grown = send(following, "a");
        assertThat(grown.main().followEnd()).isTrue();
        assertThat(grown.main().topLine() + grown.main().rows())
                .isGreaterThanOrEqualTo(grown.lines().size() - 1);
    }

    @Test
    void theOutlineScrollsIndependentlyOfThePager() {
        ViewportDemo.Model model = start();
        int pagerLine = model.main().topLine();

        ViewportDemo.Model outline = send(model, "tab");
        assertThat(outline.focus()).isEqualTo(ViewportDemo.Focus.OUTLINE);
        ViewportDemo.Model scrolled = send(outline, "down");
        scrolled = send(scrolled, "down");

        assertThat(scrolled.outline().topLine()).isPositive();
        assertThat(scrolled.main().topLine()).as("the pager did not move").isEqualTo(pagerLine);
        assertThat(demoRow(scrolled, 0)).contains("line=" + (pagerLine + 1) + "/");
    }

    @Test
    void theDemoReportsBothScrollPercentages() {
        ViewportDemo.Model model = start();

        ViewportDemo.Model bottom = send(model, "end");
        assertThat(demoRow(bottom, 0)).contains("vscroll=100%");

        ViewportDemo.Model far = send(send(model, "w"), "right");
        far = apply(far, new ViewportDemo.Msg.Pane(0, new TextViewport.Msg.Right(1000)));

        assertThat(far.main().firstColumn()).isPositive();
        assertThat(demoRow(far, 0)).contains("hscroll=100%");
        assertThat(TextViewport.horizontalPercent(far.main(), ViewportDemo.mainConfig(far, 0, 0)))
                .isEqualTo(1.0);
    }

    @Test
    void theDemoSplitsTheTerminalBetweenItsPanes() {
        ViewportDemo.Model model = start();

        assertThat(model.main().rows()).isEqualTo(ViewportDemo.mainRows(SCREEN_ROWS));
        assertThat(model.outline().rows()).isEqualTo(ViewportDemo.outlineRows(SCREEN_ROWS));
        assertThat(model.main().columns()).isEqualTo(SCREEN_COLS);

        ViewportDemo.Model shorter = apply(model, new ViewportDemo.Msg.Resized(16, 60));

        assertThat(shorter.main().rows()).isEqualTo(ViewportDemo.mainRows(16));
        assertThat(shorter.outline().rows()).isEqualTo(ViewportDemo.outlineRows(16));
        assertThat(shorter.main().columns()).isEqualTo(60);
        assertThat(demoLines(shorter)).hasSize(SCREEN_ROWS);
    }

    @Test
    void theDemoCoversTheReferenceScrollerSurface() {
        // The composed scene is what the packaged demo runs: every pane row is painted through the
        // production layout, and the document's long line is the one that wraps or pans.
        ViewportDemo.Model model = start();
        List<String> lines = demoLines(model);

        assertThat(lines).hasSize(SCREEN_ROWS);
        assertThat(String.join("\n", lines)).contains("A pocket guide to the artichoke");
        assertThat(ViewportDemo.mainRows(SCREEN_ROWS)
                        + ViewportDemo.outlineRows(SCREEN_ROWS)
                        + ViewportDemo.HEADER_ROWS
                        + ViewportDemo.OUTLINE_HEADER_ROWS
                        + ViewportDemo.HELP_ROWS)
                .isEqualTo(SCREEN_ROWS);
        assertThat(TextMeasure.columns(ViewportDemo.LINES.stream()
                        .max(Comparator.comparingInt(line -> TextMeasure.columns(line.plainText())))
                        .orElseThrow()
                        .plainText()))
                .as("the fixture holds a line worth panning")
                .isGreaterThan(SCREEN_COLS);
        assertThat(VisualRows.TAB_WIDTH).isPositive();
    }
}

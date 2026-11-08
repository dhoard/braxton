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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Alignment;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * What every widget owes the box it was measured at, over random trees of every view and widget.
 *
 * <p>Each widget is written on its own, and each one decides for itself how wide it is, where its
 * border goes, and what happens to content that no longer fits. They share one contract, and the
 * container above them cannot tell them apart: measure, then paint, and what is painted has to fit the
 * box the same call just asked for. A widget that breaks that contract does not fail inside itself - it
 * corrupts whatever is painted next to it, which is why a single driver over every widget type finds
 * what a per-widget test does not.</p>
 *
 * <p>The leaves include the tricky content every widget has to survive at once: wide clusters, clusters
 * that claim no column of their own, marks that have to be drawn behind a dotted circle, tabs, and
 * newlines. Widgets that keep internal state are also driven through random message sequences, because
 * a widget whose scroll offset or selected cell can leave its own bounds paints a row that does not
 * exist.</p>
 */
class WidgetContractFuzzTest {

    private static final String[] TEXTS = {
        "alpha",
        "a b c",
        "\u4E2D\u6587 mixed",
        "\uD83D\uDE00 done",
        "\uFE0Fx",
        "e\u0301",
        "\u200B\u0301",
        "\uD83D\uDE42\uFE0E tail",
        "",
        "x".repeat(11),
        "tab\there",
        "line1\nline2",
        "  padded  ",
        "\u00ADsoft",
        "a\tb\tc",
        "\u25CC",
        "\uFF21\uFF22",
        "\uD83D\uDC69\u200D\uD83D\uDCBB"
    };

    private static final String[] MARKUP = {
        "<root>plain</root>",
        "<root><b>bold</b> and <i>italic</i></root>",
        "<root><fg color=\"#ff0000\">red</fg></root>",
        "<root>wide \u4E2D and emoji \uD83D\uDE00</root>",
        "<root>tab\there</root>",
        "<root></root>"
    };

    private static final Border[] BORDERS = {Border.SINGLE, Border.NONE, new Border('+', '+', '+', '+', '-', '|')};

    private static final Viewport VIEWPORT = new Viewport(12, 40);

    @Test
    void everyWidgetPaintsWithinTheBoxItWasMeasuredAt() {
        Random random = new Random(555_111L);
        for (int round = 0; round < 1200; round++) {
            View<Object> view = build(3, random);
            int maxCols = 1 + random.nextInt(26);
            LayoutEngine engine = new LayoutEngine();
            int width = engine.intrinsicWidth(view, maxCols);
            assertThat(width)
                    .as("measured width of %s against %d columns", view, maxCols)
                    .isBetween(0, maxCols);
            for (int cols : new int[] {Math.max(1, width), Math.max(1, width - 2), 1, maxCols}) {
                int rows = engine.intrinsicHeight(view, cols);
                assertThat(rows)
                        .as("measured height of %s at %d columns", view, cols)
                        .isNotNegative();
                assertThat(engine.intrinsicHeight(view, cols))
                        .as("measuring %s twice at %d columns gave different heights", view, cols)
                        .isEqualTo(rows);
                Screen painted = engine.layout(view, new Rect(0, 0, rows, cols)).screen();
                assertThat(painted.rows())
                        .as("%s at %dx%d painted a different number of rows", view, rows, cols)
                        .isEqualTo(rows);
                assertThat(painted.cols())
                        .as("%s at %dx%d painted a different number of columns", view, rows, cols)
                        .isEqualTo(cols);
                for (int row = 0; row < rows; row++) {
                    assertThat(TextMeasure.columns(painted.plainLine(row)))
                            .as(
                                    "%s at %dx%d: row %d [%s] fills more than the %d columns it was given",
                                    view, rows, cols, row, show(painted.plainLine(row)), cols)
                            .isLessThanOrEqualTo(cols);
                }
                assertThat(engine.layout(view, new Rect(0, 0, rows, cols)).screen())
                        .as("%s at %dx%d did not paint the same grid twice", view, rows, cols)
                        .isEqualTo(painted);
            }
        }
    }

    /** A scrolled or selected widget cannot let its own cursor leave the rows and columns it has. */
    @Test
    void aWidgetWithInternalStateNeverMovesOutsideItsOwnBounds() {
        Random random = new Random(808_080L);
        for (int round = 0; round < 400; round++) {
            ScrollView scroll = new ScrollView(new ScrollView.Config(1 + random.nextInt(5), random.nextBoolean()));
            ScrollView.Content content = content(random);
            ScrollView.Model scrollState = new ScrollView.Model(
                    random.nextInt(9), random.nextBoolean(), random.nextBoolean(), 1 + random.nextInt(5), content);
            for (int steps = 1 + random.nextInt(14); steps > 0; steps--) {
                scrollState = scroll.update(scrollState, scrollMessage(random, content))
                        .model();
                // The content can change under the pane, so the bound comes from the state itself:
                // the last row that can be the top of the window is the one that leaves a full window
                // of visible rows behind it.
                assertThat(scrollState.topLine())
                        .as(
                                "ScrollView scrolled to line %d of %d with a %d-row window",
                                scrollState.topLine(), scrollState.lineCount(), scrollState.visibleRows())
                        .isBetween(0, Math.max(0, scrollState.lineCount() - scrollState.visibleRows()));
                assertThat(scroll.view(scrollState, new ViewContext(VIEWPORT)))
                        .as("ScrollView view after %s", scrollState)
                        .isNotNull();
            }

            List<String> options = options(random);
            Selector selector = new Selector(new Selector.Config(options, Style.NONE, Style.NONE));
            Selector.Model choice =
                    new Selector.Model(options, random.nextInt(options.size() + 2) - 1, random.nextBoolean());
            for (int steps = 1 + random.nextInt(10); steps > 0; steps--) {
                choice = selector.update(choice, selectorMessage(random)).model();
                assertThat(choice.selected())
                        .as(
                                "Selector moved to option %d of %d",
                                choice.selected(), choice.options().size())
                        .isBetween(0, choice.options().size() - 1);
                assertThat(selector.view(choice, new ViewContext(VIEWPORT)))
                        .as("Selector view after %s", choice)
                        .isNotNull();
            }

            List<String> header = header(random);
            List<List<String>> table = grid(random, header.size());
            TableSelector gridWidget = new TableSelector(new TableSelector.Config(
                    TableSelector.Model.of(header, table), Style.NONE, Style.NONE, Style.NONE));
            TableSelector.Model cell =
                    new TableSelector.Model(header, table, random.nextInt(6), random.nextInt(6), random.nextBoolean());
            for (int steps = 1 + random.nextInt(10); steps > 0; steps--) {
                cell = gridWidget.update(cell, cellMessage(random)).model();
                assertThat(cell.row())
                        .as(
                                "TableSelector moved to row %d of %d",
                                cell.row(), cell.rows().size())
                        .isBetween(0, cell.rows().size() - 1);
                assertThat(cell.column())
                        .as(
                                "TableSelector moved to column %d of %d",
                                cell.column(), cell.header().size())
                        .isBetween(0, cell.header().size() - 1);
                assertThat(gridWidget.view(cell, new ViewContext(VIEWPORT)))
                        .as("TableSelector view after %s", cell)
                        .isNotNull();
            }
        }
    }

    /**
     * The row-level helpers the widgets share. A tab that survives expansion changes the columns of the
     * row that holds it, and a line break inside a single-row cell splits a row the grid counted as one.
     */
    @Test
    void theSharedRowHelpersLeaveNeitherTabsNorLineBreaksBehind() {
        Random random = new Random(313_373L);
        for (int round = 0; round < 6000; round++) {
            StyledText text = styled(random);
            StyledText expanded = TabStops.expand(text);
            assertThat(containsCharacter(expanded, '\t'))
                    .as("TabStops.expand left a tab in [%s]", expanded.plainText())
                    .isFalse();
            assertThat(TextMeasure.columns(expanded.plainText()))
                    .as("TabStops.expand shortened [%s] to [%s]", text.plainText(), expanded.plainText())
                    .isGreaterThanOrEqualTo(TextMeasure.columns(text.plainText()));
            assertThat(TabStops.expand(expanded))
                    .as("TabStops.expand is not stable on [%s]", expanded.plainText())
                    .isEqualTo(expanded);

            StyledText single = SingleRow.of(text);
            assertThat(containsCharacter(single, '\n'))
                    .as("SingleRow.of returned a line break for [%s]", text.plainText())
                    .isFalse();

            List<StyledText> shared = GridRows.shareOneWidth(List.of(styled(random), styled(random), styled(random)));
            int width = TextMeasure.columns(shared.getFirst().plainText());
            for (StyledText line : shared) {
                assertThat(TextMeasure.columns(line.plainText()))
                        .as(
                                "GridRows.shareOneWidth returned [%s] beside [%s]",
                                line.plainText(), shared.getFirst().plainText())
                        .isEqualTo(width);
            }
        }
    }

    /** Markup text is arbitrary application content: it has to reach the grid without moving it. */
    @Test
    void markupContentMeasuresAndPaintsLikeAnyOtherText() {
        Random random = new Random(909_091L);
        for (int round = 0; round < 400; round++) {
            String source = MARKUP[random.nextInt(MARKUP.length)];
            View<Object> view = Markup.view(source);
            int cols = 1 + random.nextInt(24);
            LayoutEngine engine = new LayoutEngine();
            int rows = Math.max(1, engine.intrinsicHeight(view, cols));
            assertThat(engine.intrinsicWidth(view, cols))
                    .as("markup [%s] at %d columns measured wider than it was offered", source, cols)
                    .isLessThanOrEqualTo(cols);
            Screen painted = engine.layout(view, new Rect(0, 0, rows, cols)).screen();
            for (int row = 0; row < rows; row++) {
                assertThat(TextMeasure.columns(painted.plainLine(row)))
                        .as("markup [%s] at %dx%d: row %d overflows", source, rows, cols, row)
                        .isLessThanOrEqualTo(cols);
            }
        }
    }

    private static ScrollView.Msg scrollMessage(Random random, ScrollView.Content content) {
        return switch (random.nextInt(7)) {
            case 0 -> new ScrollView.Msg.Up(1 + random.nextInt(5));
            case 1 -> new ScrollView.Msg.Down(1 + random.nextInt(5));
            case 2 -> new ScrollView.Msg.PageUp();
            case 3 -> new ScrollView.Msg.PageDown();
            case 4 -> new ScrollView.Msg.Home();
            case 5 -> new ScrollView.Msg.End();
            default ->
                random.nextBoolean()
                        ? new ScrollView.Msg.ContentChanged(content(random))
                        : new ScrollView.Msg.ViewportChanged(1 + random.nextInt(8));
        };
    }

    private static Selector.Msg selectorMessage(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> new Selector.Msg.Up();
            case 1 -> new Selector.Msg.Down();
            case 2 -> new Selector.Msg.Confirm();
            default -> new Selector.Msg.Cancel();
        };
    }

    private static TableSelector.Msg cellMessage(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> new TableSelector.Msg.Up();
            case 1 -> new TableSelector.Msg.Down();
            case 2 -> new TableSelector.Msg.Left();
            case 3 -> new TableSelector.Msg.Right();
            case 4 -> new TableSelector.Msg.Confirm();
            default -> new TableSelector.Msg.Cancel();
        };
    }

    private static ScrollView.Content content(Random random) {
        List<StyledText> lines = new ArrayList<>();
        for (int count = random.nextInt(9); count > 0; count--) {
            lines.add(styled(random));
        }
        return ScrollView.Content.of(lines);
    }

    private static List<String> options(Random random) {
        List<String> options = new ArrayList<>();
        for (int count = 1 + random.nextInt(4); count > 0; count--) {
            options.add(TEXTS[random.nextInt(TEXTS.length)]);
        }
        return options;
    }

    private static List<String> header(Random random) {
        List<String> header = new ArrayList<>();
        for (int count = 1 + random.nextInt(3); count > 0; count--) {
            header.add(TEXTS[random.nextInt(TEXTS.length)]);
        }
        return header;
    }

    private static List<List<String>> grid(Random random, int columns) {
        List<List<String>> rows = new ArrayList<>();
        for (int count = 1 + random.nextInt(4); count > 0; count--) {
            List<String> row = new ArrayList<>();
            for (int column = 0; column < columns; column++) {
                row.add(TEXTS[random.nextInt(TEXTS.length)]);
            }
            rows.add(row);
        }
        return rows;
    }

    private static StyledText styled(Random random) {
        return StyledText.of(TEXTS[random.nextInt(TEXTS.length)], Style.NONE.withBold());
    }

    private static boolean containsCharacter(StyledText text, char value) {
        return text.plainText().indexOf(value) >= 0;
    }

    private static View<Object> build(int depth, Random random) {
        if (depth <= 0 || random.nextInt(3) == 0) {
            return leaf(random);
        }
        return switch (random.nextInt(12)) {
            case 0 -> Views.border(BORDERS[random.nextInt(BORDERS.length)], child(depth - 1, random));
            case 1 ->
                Views.border(
                        BORDERS[random.nextInt(BORDERS.length)], styled(random), Style.NONE, child(depth - 1, random));
            case 2 -> Views.padding(Insets.uniform(random.nextInt(3)), child(depth - 1, random));
            case 3 -> Views.row(Alignment.START, items(depth - 1, random));
            case 4 -> Views.column(Alignment.START, items(depth - 1, random));
            case 5 -> Views.overlay(child(depth - 1, random), child(depth - 1, random));
            case 6 -> Views.fixed(random.nextInt(8), child(depth - 1, random));
            case 7 -> Views.grow(1 + random.nextInt(4), child(depth - 1, random));
            case 8 -> Views.gap(random.nextInt(4));
            case 9 -> Views.fill(Style.NONE.withBackground(new Color(1, 2, 3)));
            case 10 ->
                Views.keyed(
                        List.of(new Binding<>(Key.of("enter"), "msg")),
                        child(depth - 1, random),
                        List.of(new Binding<>(Key.of("q"), "quit")));
            default ->
                Views.focus(
                        io.github.dhoard.braxton.view.ElementId.of("f" + random.nextInt(3)),
                        true,
                        child(depth - 1, random));
        };
    }

    /** Every widget that turns content into a view, chosen at random. */
    private static View<Object> leaf(Random random) {
        return switch (random.nextInt(8)) {
            case 0 -> Views.text(styled(random));
            case 1 ->
                Badge.of()
                        .append(styled(random))
                        .append(styled(random), new Color(10, 20, 30))
                        .view();
            case 2 -> Quote.of().append(styled(random)).bar(">").view();
            case 3 ->
                Table.of()
                        .buffer(random.nextInt(3))
                        .border(BORDERS[random.nextInt(BORDERS.length)])
                        .row(styled(random), styled(random))
                        .row(styled(random), styled(random))
                        .view();
            case 4 -> ProgressBar.of(1 + random.nextInt(20)).view(random.nextInt(120), 1 + random.nextInt(100));
            case 5 -> Markup.view(MARKUP[random.nextInt(MARKUP.length)]);
            case 6 ->
                Gradient.view(
                        styled(random),
                        List.of(new Color(255, 0, 0), new Color(0, 0, 255)),
                        Gradient.Direction.HORIZONTAL);
            default -> Box.of(styled(random)).view(2 + random.nextInt(20));
        };
    }

    private static View<Object> child(int depth, Random random) {
        return build(depth, random);
    }

    private static List<View<Object>> items(int depth, Random random) {
        List<View<Object>> items = new ArrayList<>();
        for (int count = 1 + random.nextInt(3); count > 0; count--) {
            items.add(build(depth, random));
        }
        return items;
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

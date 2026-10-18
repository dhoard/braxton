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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;

/** Catalog widgets remain pure values with deterministic layout. */
class CatalogTest {

    @Test
    void tableColumnsAreTheWidestCellPlusTheBuffer() {
        Table table = Table.of().buffer(3);
        table.row(StyledText.plain("braxton"), StyledText.plain("row"));
        table.row(StyledText.of("longer", Style.NONE.withBold()), StyledText.plain("x"));
        assertThat(table.columnWidths()).containsExactly(10, 6);
    }

    @Test
    void aTableRowRejectsAMissingCellWithADiagnostic() {
        assertThatThrownBy(() -> Table.of().row(StyledText.plain("a"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> Table.of().row((List<StyledText>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    void tableRenderingDoesNotMutateTheRowsItWasGiven() {
        List<StyledText> first = new ArrayList<>(List.of(StyledText.plain("a")));
        List<List<StyledText>> rows = new ArrayList<>();
        rows.add(first);
        Table table = Table.of();
        table.row(first);
        table.renderLines();
        assertThat(first).hasSize(1);
        assertThat(first.get(0).plainText()).isEqualTo("a");
        assertThat(rows.get(0)).hasSize(1);
    }

    @Test
    void tableCellsStartAtColumnBoundaries() {
        Table table = Table.of().buffer(2);
        table.row(StyledText.plain("a"), StyledText.plain("b"));
        table.row(StyledText.plain("cc"), StyledText.plain("d"));
        List<StyledText> lines = table.renderLines();
        assertThat(lines.get(0).plainText()).isEqualTo("a   b  ");
        assertThat(lines.get(1).plainText()).isEqualTo("cc  d  ");
    }

    @Test
    void tableColumnsLineUpWhenACellContainsATabStop() {
        Table table = Table.of();
        table.row(StyledText.plain("a\tb"), StyledText.plain("c"));
        table.row(StyledText.plain("x"), StyledText.plain("y"));
        List<String> lines = new ScreenRenderer().plainLines(table.view(),
                new Viewport(2, 24));
        assertThat(lines.get(0).indexOf('c')).isEqualTo(lines.get(1).indexOf('y'));
    }

    @Test
    void tableCellsExpandATabFromTheLineStartAcrossSpans() {
        Table plain = Table.of().buffer(0);
        plain.row(StyledText.plain("ab\tx"));
        Table styled = Table.of().buffer(0);
        styled.row(StyledText.concat(StyledText.of("ab", Style.NONE.withBold()),
                StyledText.of("\tx", Style.NONE)));
        assertThat(styled.renderLines().get(0).plainText())
                .isEqualTo(plain.renderLines().get(0).plainText())
                .isEqualTo("ab  x");
    }

    @Test
    void markupTableColumnsLineUpWhenACellContainsATabStop() {
        StyledText rendered = Markup.parse("<root><table gap=\"1\">"
                + "<tr><td>a\tb</td><td>c</td></tr>"
                + "<tr><td>x</td><td>y</td></tr></table></root>");
        List<String> lines = new ScreenRenderer().plainLines(
                io.github.dhoard.braxton.view.Views.text(rendered), new Viewport(2, 24));
        assertThat(lines.get(0).indexOf('c')).isEqualTo(lines.get(1).indexOf('y'));
    }

    @Test
    void emptyTableRendersNothing() {
        assertThat(Table.of().render().plainText()).isEmpty();
        assertThat(Table.of().renderLines()).isEmpty();
    }

    @Test
    void boxDrawsABorderWithATitleAndClipsContent() {
        StyledText rendered = Box.of(StyledText.plain("hello world")).title("label").render(12);
        List<String> lines = List.of(rendered.plainText().split("\n", -1));
        assertThat(lines.get(0)).startsWith("\u250C").contains("label");
        assertThat(lines).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(12));
        assertThat(lines.get(lines.size() - 1)).startsWith("\u2514");
    }

    @Test
    void boxTruncatesALongTitleToItsFixedWidth() {
        StyledText rendered = Box.of("x").width(10).title("a very long title").render(40);
        List<String> lines = List.of(rendered.plainText().split("\n", -1));
        assertThat(lines).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(10));
        assertThat(lines.get(0)).startsWith("\u250C").contains("a ver").endsWith("\u2510");
    }

    @Test
    void boxTitleWithLineBreaksKeepsTheFrameOnOneLine() {
        StyledText rendered = Box.of("x").width(12).title("a\nb\tc").render(40);
        List<String> lines = List.of(rendered.plainText().split("\n", -1));
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("\u250C").contains("a b c").endsWith("\u2510");
        assertThat(TextMeasure.columns(lines.get(0))).isEqualTo(12);
    }

    @Test
    void aBorderedBoxKeepsEachContentSpanStyleAndLink() {
        StyledText content = StyledText.of("a", Style.NONE.withBold())
                .append(StyledText.of("b", Style.NONE, "https://example.test/info"));
        StyledText box = Box.of(content).render(20);
        assertThat(box.plainText()).contains("ab");
        assertThat(box.spans()).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("a");
            assertThat(span.style().bold()).isTrue();
        });
        assertThat(box.spans()).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("b");
            assertThat(span.link()).isEqualTo("https://example.test/info");
        });
    }

    @Test
    void aBoxTitleKeepsEachSpanStyleWithinTheFrameStyle() {
        StyledText title = StyledText.of("T", Style.NONE.withBold())
                .append(StyledText.of("i", Style.NONE.withForeground(Colors.ERROR_400)));
        StyledText box = Box.of("x").title(title).render(24);
        assertThat(box.spans()).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("T");
            assertThat(span.style().bold()).isTrue();
        });
        assertThat(box.spans()).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("i");
            assertThat(span.style().foreground()).isEqualTo(Colors.ERROR_400);
        });
    }

    @Test
    void borderlessBoxIsJustItsContent() {
        Box box = Box.of("plain").border(Border.NONE);
        assertThat(box.render(20).plainText()).isEqualTo("plain");
    }

    @Test
    void aBorderedTableClosesItsFrameAroundTheRows() {
        Table table = Table.of().buffer(1).border(Border.SINGLE);
        table.row(StyledText.plain("a"), StyledText.plain("bb"));
        table.row(StyledText.plain("\u65E5"), StyledText.plain("c"));
        String rendered = table.render().plainText();
        assertThat(rendered).isEqualTo("""
                \u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2510
                \u2502a  bb \u2502
                \u2502\u65E5 c  \u2502
                \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2518""");
        for (String line : rendered.split("\n", -1)) {
            assertThat(TextMeasure.columns(line)).as("line [%s]", line).isEqualTo(8);
        }
    }

    @Test
    void aBoxNarrowerThanTwoColumnsStillDrawsItsFrame() {
        String rendered = Box.of("body").border(Border.SINGLE).width(1).render(10).plainText();
        assertThat(rendered).isEqualTo("\u250C\u2510\n\u2502\u2502\n\u2514\u2518");
        for (String line : rendered.split("\n", -1)) {
            assertThat(TextMeasure.columns(line)).isEqualTo(2);
        }
    }

    @Test
    void aTitledBoxKeepsItsWidthWhenTheFrameHasNoRoomForTheTitle() {
        for (int width = 2; width <= 10; width++) {
            String rendered = Box.of("body").border(Border.ROUNDED).title("Title").width(width)
                    .render(width).plainText();
            for (String line : rendered.split("\n", -1)) {
                assertThat(TextMeasure.columns(line))
                        .as("width %d line [%s]", width, line)
                        .isEqualTo(width);
            }
        }
    }

    @Test
    void progressBarFillsFromTheLeftAndClampsItsValue() {
        ProgressBar bar = ProgressBar.of(10).icons("#", "-");
        assertThat(bar.render(5, 10).plainText()).isEqualTo("#####-----");
        assertThat(bar.render(50, 10).plainText()).isEqualTo("##########");
        assertThat(bar.render(-5, 10).plainText()).isEqualTo("----------");
        assertThat(bar.render(1, 0).plainText()).isEmpty();
    }

    @Test
    void progressBarSeparatesSegmentsAndShowsAPercentage() {
        ProgressBar bar = ProgressBar.of(4).buffer(1).icons("#", "-").showPercentage(true);
        assertThat(bar.render(2, 4).plainText()).isEqualTo("# # - - 50%");
    }

    @Test
    void progressBarIconsHonourTheRequestedCellCount() {
        ProgressBar bar = ProgressBar.of(10).icons("#", "-");
        assertThat(bar.icons(3, 5, 10)).containsExactly("#", "#", "#");
        assertThat(bar.icons(12, 5, 10)).hasSize(10);
        assertThat(bar.icons(0, 5, 10)).isEmpty();
    }

    @Test
    void quoteExpandsTabsSoItsLinesStayTheSameWidth() {
        StyledText rendered = Quote.of().append("a\tb").append("cc").render();
        assertThat(rendered.plainText()).isEqualTo("\u2502 a   b\n\u2502 cc   ");
        assertThat(rendered.plainText().split("\n", -1))
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isEqualTo(7));
    }

    @Test
    void quotePreservesTheStylingOfTheQuotedContent() {
        StyledText content = StyledText.of("hot",
                Style.NONE.withBold().withForeground(Colors.ERROR_400));
        StyledText quote = Quote.of().append(content).background(Colors.GRAY_600).render();
        assertThat(quote.plainText()).isEqualTo("\u2502 hot");
        List<StyledText.Span> spans = quote.spans();
        StyledText.Span body = spans.stream()
                .filter(span -> span.text().contains("hot"))
                .findFirst()
                .orElseThrow();
        assertThat(body.text()).isEqualTo("hot");
        assertThat(body.style().bold()).isTrue();
        assertThat(body.style().foreground()).isEqualTo(Colors.ERROR_400);
        assertThat(body.style().background()).isEqualTo(Colors.GRAY_600);
    }

    @Test
    void quoteKeepsItsBarAndBackground() {
        StyledText quote = Quote.of().append("one").append("two")
                .border(Colors.CYAN_400).render();
        assertThat(quote.plainText()).isEqualTo("\u2502 one\n\u2502 two");
        assertThat(quote.style().foreground()).isEqualTo(Colors.CYAN_400);
    }

    @Test
    void badgeKeepsEachLabelSpanStyleUnderTheSegmentBackground() {
        StyledText label = StyledText.of("a", Style.NONE.withForeground(Colors.ERROR_400))
                .append(StyledText.of("b", Style.NONE.withBold()));
        StyledText rendered = Badge.of().append(label, Colors.GRAY_600).render();
        assertThat(rendered.plainText()).isEqualTo(" ab ");
        assertThat(rendered.spans()).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("a");
            assertThat(span.style().foreground()).isEqualTo(Colors.ERROR_400);
            assertThat(span.style().background()).isEqualTo(Colors.GRAY_600);
        });
        assertThat(rendered.spans()).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("b");
            assertThat(span.style().bold()).isTrue();
            assertThat(span.style().background()).isEqualTo(Colors.GRAY_600);
        });
    }

    @Test
    void gradientKeepsTheTextAttributesAndLinksItColours() {
        StyledText content = StyledText.of("ab", Style.NONE.withBold())
                .append(StyledText.of("c", Style.NONE, "https://example.test/info"));
        StyledText gradient = Gradient.apply(content,
                List.of(Colors.ERROR_400, Colors.GRAY_600), Gradient.Direction.HORIZONTAL);
        assertThat(gradient.plainText()).isEqualTo("abc");
        assertThat(gradient.spans()).allSatisfy(span ->
                assertThat(span.style().foreground()).isNotNull());
        assertThat(gradient.spans()).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("a");
            assertThat(span.style().bold()).isTrue();
        });
        assertThat(gradient.spans()).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("c");
            assertThat(span.link()).isEqualTo("https://example.test/info");
        });
    }

    @Test
    void gradientColoursEachGraphemeAndRejectsAnEmptyPalette() {
        StyledText plain = StyledText.plain("abc");
        assertThat(Gradient.apply(plain, List.of(), Gradient.Direction.VERTICAL)).isEqualTo(plain);
        StyledText painted = Gradient.apply(plain,
                List.of(Colors.ERROR_400, Colors.SUCCESS_400), Gradient.Direction.HORIZONTAL);
        assertThat(painted.plainText()).isEqualTo("abc");
        assertThat(painted.spans()).hasSize(3);
        assertThat(painted.spans().get(0).style().foreground()).isEqualTo(Colors.ERROR_400);
        assertThat(painted.spans().get(2).style().foreground()).isEqualTo(Colors.SUCCESS_400);
    }

    @Test
    void gradientIsDeterministic() {
        StyledText plain = StyledText.plain("abc");
        List<io.github.dhoard.braxton.style.Color> colors = List.of(Colors.ERROR_400, Colors.SUCCESS_400);
        assertThat(Gradient.apply(plain, colors, Gradient.Direction.DIAGONAL))
                .isEqualTo(Gradient.apply(plain, colors, Gradient.Direction.DIAGONAL));
    }

    @Test
    void markupRendersTagsAttributesListsAndTables() {
        StyledText rendered = Markup.parse("""
                <root>
                  <p color="#ff0000" style="bold">Title</p>
                  <span style="italic">plain</span>
                  <a href="https://example.com" style="underline">link</a>
                  <ul icon="-" gap="1"><li>one</li><li>two</li></ul>
                  <table gap="2"><tr><td>a</td><td>b</td></tr><tr><td>cc</td><td>d</td></tr></table>
                </root>
                """);
        String plain = rendered.plainText();
        assertThat(plain).contains("Title");
        assertThat(plain).contains("- one");
        assertThat(plain).contains("a   b");
        assertThat(plain).contains("cc  d");
        assertThat(rendered.spans()).anySatisfy(span ->
                assertThat(span.link()).isEqualTo("https://example.com"));
        assertThat(rendered.spans()).anySatisfy(span ->
                assertThat(span.style().foreground()).isEqualTo(
                        io.github.dhoard.braxton.style.Color.parse("#ff0000").orElseThrow()));
    }

    @Test
    void markupNestsListsOnTheirOwnIndentedLines() {
        StyledText rendered = Markup.parse(
                "<root><ul gap=\"1\"><li>parent<ul gap=\"1\"><li>child</li></ul></li></ul></root>");
        assertThat(rendered.plainText()).isEqualTo(" \u2022 parent\n    \u2022 child");
    }

    @Test
    void markupListContinuationLinesAlignUnderTheItemText() {
        StyledText rendered = Markup.parse(
                "<root><ul gap=\"1\"><li>item<br/>tail</li></ul></root>");
        assertThat(rendered.plainText()).isEqualTo(" \u2022 item\n   tail");
    }

    @Test
    void markupStartsAParagraphOnANewLineAfterInlineText() {
        StyledText rendered = Markup.parse("<root>intro<p>para</p>tail</root>");
        assertThat(rendered.plainText()).isEqualTo("intro\npara\ntail");
    }

    @Test
    void markupStartsABlockChildOnANewLine() {
        StyledText rendered = Markup.parse(
                "<root><ul gap=\"1\"><li>item<table gap=\"1\"><tr><td>cell</td>"
                        + "</tr></table></li></ul></root>");
        assertThat(rendered.plainText()).startsWith(" \u2022 item\n   ");
        assertThat(rendered.plainText()).contains("cell");
    }

    @Test
    void markupListAppliesTheListStyleToTheBulletAndIndent() {
        StyledText rendered = Markup.parse(
                "<root><ul style=\"bold\" color=\"#ff0000\" gap=\"1\"><li>x</li></ul></root>");
        assertThat(rendered.plainText()).isEqualTo(" \u2022 x");
        assertThat(rendered.spans()).allSatisfy(span -> {
            assertThat(span.style().bold()).isTrue();
            assertThat(span.style().foreground())
                    .isEqualTo(io.github.dhoard.braxton.style.Color.parse("#ff0000").orElseThrow());
        });
    }

    @Test
    void markupTableAlignsColumnsAcrossMultiLineCells() {
        StyledText rendered = Markup.parse("<root><table gap=\"2\">"
                + "<tr><td>ab<br/>c</td><td>x</td></tr>"
                + "<tr><td>d</td><td>y</td></tr></table></root>");
        List<String> lines = rendered.lines().stream().map(StyledText::plainText).toList();
        int firstRowColumn = lines.get(0).indexOf('x');
        int secondRowColumn = lines.get(2).indexOf('y');
        assertThat(firstRowColumn).isGreaterThan(0);
        assertThat(secondRowColumn).isEqualTo(firstRowColumn);
    }

    @Test
    void markupPreservesNestedInheritanceAndCdata() {
        StyledText rendered = Markup.parse(
                "<root><span color=\"#00ff00\"><span style=\"bold\">x</span>y</span>"
                        + "<![CDATA[<literal>]]></root>");
        assertThat(rendered.plainText()).contains("<literal>");
        assertThat(rendered.spans().subList(0, 2)).allSatisfy(span ->
                assertThat(span.style().foreground()).isEqualTo(
                        io.github.dhoard.braxton.style.Color.parse("#00ff00").orElseThrow()));
    }

    @Test
    void malformedMarkupFailsWithADiagnostic() {
        assertThatThrownBy(() -> Markup.parse("<root><unclosed></root>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid markup");
        assertThatThrownBy(() -> Markup.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markupRejectsExternalEntities() {
        assertThatThrownBy(() -> Markup.parse("""
                <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <root>&xxe;</root>
                """))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedMarkupReportsThroughTheExceptionAndNotStderr() {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertThatThrownBy(() -> Markup.parse("<root><unclosed></root>"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid markup");
            assertThatThrownBy(() -> Markup.parse("""
                    <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <root>&xxe;</root>
                    """))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            System.setErr(original);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8))
                .as("parser diagnostics must not be written to stderr")
                .isEmpty();
    }

    @Test
    void badgeKeepsALabelWithLineBreaksOnOneRow() {
        StyledText rendered = Badge.of()
                .append(StyledText.of("a\nb\tc", Style.NONE.withBold()), Colors.GRAY_600)
                .render();
        assertThat(rendered.plainText()).isEqualTo(" a b c ");
        assertThat(rendered.spans()).anySatisfy(span -> {
            assertThat(span.text()).isEqualTo("a b c");
            assertThat(span.style().bold()).isTrue();
            assertThat(span.style().background()).isEqualTo(Colors.GRAY_600);
        });
    }

    @Test
    void badgePadsSegmentsAndSupportsPathways() {
        StyledText badge = Badge.of()
                .append(StyledText.plain("one"), Colors.BLUE_400)
                .append(StyledText.plain("two"), Colors.PURPLE_400)
                .pathway().render();
        assertThat(badge.plainText()).isEqualTo(" one  \u2192  two ");
    }

    @Test
    void selectorActionsClampAndEmitTypedIntents() {
        Selector.State state = Selector.State.of(List.of("a", "b"));
        assertThat(Selector.update(state, new Selector.Action.Up()).state().selected()).isZero();
        assertThat(Selector.update(state, new Selector.Action.Down()).state().selected()).isEqualTo(1);
        assertThat(Selector.update(state, new Selector.Action.Down()).state().selected()).isEqualTo(1);
        Selector.Intent chosen = Selector.update(state, new Selector.Action.Confirm()).intent();
        assertThat(chosen).isInstanceOf(Selector.Intent.Chosen.class);
        assertThat(((Selector.Intent.Chosen) chosen).value()).isEqualTo("a");
    }

    @Test
    void confirmingAnEmptySelectorHasNothingToChoose() {
        Selector.State empty = Selector.State.of(List.of());
        Selector.Result result = Selector.update(empty, new Selector.Action.Confirm());
        assertThat(result.intent()).isInstanceOf(Selector.Intent.None.class);
        assertThat(result.state()).isEqualTo(empty);
    }

    @Test
    void confirmingAnEmptyTableHasNothingToChoose() {
        TableSelector.State empty = TableSelector.State.of(List.of("h1"), List.of());
        TableSelector.Result result = TableSelector.update(empty, new TableSelector.Action.Confirm());
        assertThat(result.intent()).isInstanceOf(TableSelector.Intent.None.class);
        assertThat(result.state()).isEqualTo(empty);
        TableSelector.State headerless = TableSelector.State.of(List.of(), List.of(List.of("a")));
        assertThat(TableSelector.update(headerless, new TableSelector.Action.Confirm()).intent())
                .isInstanceOf(TableSelector.Intent.None.class);
    }

    @Test
    void tableSelectorColumnsLineUpWhenACellContainsATabStop() {
        TableSelector.State state = TableSelector.State.of(List.of("a\tb", "c"),
                List.of(List.of("x", "y")));
        View<Object> view = TableSelector.view(ElementId.of("table"), state, false, Style.NONE,
                Style.NONE, Style.NONE, action -> "");
        List<String> lines = new ScreenRenderer().plainLines(view, new Viewport(2, 20));
        assertThat(lines.get(0).indexOf('c')).isEqualTo(lines.get(1).indexOf('y'));
    }

    @Test
    void tableSelectorClampsAndReportsTheSelectedCell() {
        TableSelector.State state = TableSelector.State.of(List.of("h1", "h2"),
                List.of(List.of("a", "b"), List.of("c", "d")));
        state = TableSelector.update(state, new TableSelector.Action.Right()).state();
        assertThat(state.selectedValue()).isEqualTo("b");
        state = TableSelector.update(state, new TableSelector.Action.Down()).state();
        assertThat(state.selectedValue()).isEqualTo("d");
        TableSelector.Intent chosen = TableSelector.update(state,
                new TableSelector.Action.Confirm()).intent();
        assertThat(((TableSelector.Intent.Chosen) chosen).value()).isEqualTo("d");
    }
}

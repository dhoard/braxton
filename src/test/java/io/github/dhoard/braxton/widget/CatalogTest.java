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
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

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
    void borderlessBoxIsJustItsContent() {
        Box box = Box.of("plain").border(Border.NONE);
        assertThat(box.render(20).plainText()).isEqualTo("plain");
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
    void quoteKeepsItsBarAndBackground() {
        StyledText quote = Quote.of().append("one").append("two")
                .border(Colors.CYAN_400).render();
        assertThat(quote.plainText()).isEqualTo("\u2502 one\n\u2502 two");
        assertThat(quote.style().foreground()).isEqualTo(Colors.CYAN_400);
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

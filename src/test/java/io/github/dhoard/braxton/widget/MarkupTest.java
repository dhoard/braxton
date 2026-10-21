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

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;

/** Markup styling tokens, the regular reset, and the table grid. */
class MarkupTest {

    private static Style spanStyle(String xml) {
        StyledText parsed = Markup.parse(xml);
        assertThat(parsed.spans()).isNotEmpty();
        return parsed.spans().get(0).style();
    }

    @Test
    void styleTokensAreAppliedCaseInsensitivelyAndUnknownOnesAreIgnored() {
        Style style = spanStyle("<root><span style=\"BOLD Italic underline\">x</span></root>");
        assertThat(style.bold()).isTrue();
        assertThat(style.italic()).isTrue();
        assertThat(style.underline()).isTrue();
        assertThat(style.strikethrough()).isFalse();
        assertThat(style.invert()).isFalse();
        assertThat(style.hidden()).isFalse();
        assertThat(spanStyle("<root><span style=\"strikethrough invert hidden\">x</span></root>"))
                .satisfies(all -> {
                    assertThat(all.strikethrough()).isTrue();
                    assertThat(all.invert()).isTrue();
                    assertThat(all.hidden()).isTrue();
                });
        // An unknown token changes nothing and does not stop the ones after it.
        assertThat(spanStyle("<root><span style=\"blink bold\">x</span></root>").bold()).isTrue();
        assertThat(spanStyle("<root><span style=\"  \">x</span></root>")).isEqualTo(Style.NONE);
    }

    @Test
    void regularClearsEveryFlagWhateverOrderItIsWrittenIn() {
        // The reset is applied once the whole list is read, so "regular" wins in either position
        // while the colours it does not mention survive.
        for (String tokens : List.of("bold regular", "regular bold", "REGULAR Italic Underline")) {
            Style style = spanStyle("<root><span style=\"" + tokens + "\" color=\"#112233\""
                    + " bg-color=\"#445566\">x</span></root>");
            assertThat(style.bold()).as(tokens).isFalse();
            assertThat(style.italic()).as(tokens).isFalse();
            assertThat(style.underline()).as(tokens).isFalse();
            assertThat(style.foreground()).as(tokens).isEqualTo(Color.parse("#112233").orElseThrow());
            assertThat(style.background()).as(tokens).isEqualTo(Color.parse("#445566").orElseThrow());
        }
    }

    @Test
    void stylesNestAndAreInheritedByChildren() {
        StyledText parsed = Markup.parse(
                "<root><span style=\"bold\" color=\"#ff0000\">a<span style=\"italic\">b</span>c</span></root>");
        assertThat(parsed.plainText()).isEqualTo("abc");
        List<StyledText.Span> spans = parsed.spans();
        assertThat(spans).extracting(StyledText.Span::text).contains("a", "b", "c");
        assertThat(spans).filteredOn(span -> "b".equals(span.text())).singleElement()
                .satisfies(span -> {
                    assertThat(span.style().bold()).as("inherited").isTrue();
                    assertThat(span.style().italic()).isTrue();
                    assertThat(span.style().foreground())
                            .isEqualTo(Color.parse("#ff0000").orElseThrow());
                });
    }

    @Test
    void aDoctypeOrEntityDeclarationIsRejectedInsteadOfBeingResolved() {
        // The documented security rule: "Markup disables DTDs and external entities, so untrusted
        // text cannot fetch a file or URL." A parser that resolved this document would put the
        // file's contents (or a billion-laughs expansion) into the rendered text.
        String externalEntity =
                "<!DOCTYPE root [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><root>&xxe;</root>";
        assertThatThrownBy(() -> Markup.parse(externalEntity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DOCTYPE")
                .hasMessageNotContaining("root:");
        assertThatThrownBy(() -> Markup.parse(
                "<!DOCTYPE root SYSTEM \"file:///etc/passwd\"><root>hi</root>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Markup.parse(
                "<!DOCTYPE root [<!ENTITY laugh \"ha\">]><root>&laugh;</root>"))
                .isInstanceOf(IllegalArgumentException.class);
        // An undeclared entity is a malformed document too, not literal text to echo.
        assertThatThrownBy(
                () -> Markup.parse("<root>&undefined;</root>"))
                .isInstanceOf(IllegalArgumentException.class);
        // The ordinary document still renders, so the guard did not disable parsing.
        assertThat(Markup.parse("<root>hi</root>").plainText()).isEqualTo("hi");
    }

    @Test
    void aTableIsARectangularGridWithEveryCellPadded() {
        StyledText parsed = Markup.parse(
                "<root><table><tr><td>a</td><td>longer</td></tr>"
                        + "<tr><td>bb</td><td>c</td></tr></table></root>");
        List<String> lines = List.of(parsed.plainText().split("\n", -1));
        assertThat(lines).as("no trailing newline after the last row").hasSize(2);
        // Every cell is padded on its right to its column's widest cell plus the two-space gap, so
        // the last column is padded too and the grid stays rectangular.
        assertThat(lines.get(0)).isEqualTo("a   " + "longer  ");
        assertThat(lines.get(1)).isEqualTo("bb  " + "c       ");
        assertThat(lines).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).isEqualTo(12));
    }

    @Test
    void aShortRowPadsItsMissingCells() {
        StyledText parsed = Markup.parse(
                "<root><table><tr><td>a</td><td>b</td><td>c</td></tr>"
                        + "<tr><td>d</td></tr></table></root>");
        List<String> lines = List.of(parsed.plainText().split("\n", -1));
        assertThat(lines.get(0)).isEqualTo("a  b  c  ");
        assertThat(lines.get(1)).as("a missing cell is padded like an empty one")
                .isEqualTo("d  " + "   " + "   ");
        assertThat(TextMeasure.columns(lines.get(1)))
                .isEqualTo(TextMeasure.columns(lines.get(0)));
    }

    @Test
    void aTableGapIsHonouredAndDefaultsToOne() {
        StyledText wide = Markup.parse(
                "<root><table gap=\"3\"><tr><td>a</td><td>b</td></tr></table></root>");
        assertThat(wide.plainText().lines().toList().get(0)).isEqualTo("a   b   ");
        assertThat(Markup.parse("<root><table gap=\"nope\"><tr><td>a</td></tr></table></root>")
                .plainText().lines().toList().get(0))
                .as("an unusable gap falls back to the default").isEqualTo("a  ");
    }

    @Test
    void listsIndentTheirBulletAndHangTheContinuationLines() {
        StyledText parsed = Markup.parse(
                "<root><ul><li>first line that wraps</li><li>second</li></ul></root>");
        List<String> lines = List.of(parsed.plainText().split("\n", -1));
        assertThat(lines).allSatisfy(line -> assertThat(line).startsWith("  \u2022 "));
        assertThat(Markup.parse("<root><ul icon=\"-\" gap=\"0\"><li>x</li></ul></root>")
                .plainText().lines().toList().get(0)).isEqualTo("- x");
        assertThat(Markup.parse("<root><ul><li>bullet</li><p>text</p></ul></root>")
                .plainText().lines().toList().get(0))
                .as("only li children become bullets").isEqualTo("  \u2022 bullet");
    }

    /**
     * A list icon is a one-line prefix, so it is painted as one: a tab advances to the text view's
     * four-column stop and a line break becomes a space. Measuring the raw icon with
     * {@code TextMeasure.columns} (which counts a tab or a break as one column) calculated a
     * continuation indent under a column the painted prefix never reaches.
     */
    @Test
    void aListIconIsMeasuredAsThePaintedOneLinePrefix() {
        String tabbed = "<root><ul gap=\"0\" icon=\"&gt;&#9;\"><li>a<br/>b</li></ul></root>";
        assertThat(Markup.parse(tabbed).plainText()).isEqualTo(">    a\n     b");
        assertPaintedContinuationAlignsUnderTheItemText(tabbed, 'a', 'b');

        String broken = "<root><ul gap=\"0\" icon=\"a&#10;b\"><li>c<br/>d</li></ul></root>";
        assertThat(Markup.parse(broken).plainText()).isEqualTo("a b c\n    d");
        assertPaintedContinuationAlignsUnderTheItemText(broken, 'c', 'd');

        String carriageReturn = "<root><ul gap=\"0\" icon=\"e&#13;f\"><li>g<br/>h</li></ul></root>";
        assertThat(Markup.parse(carriageReturn).plainText()).isEqualTo("e f g\n    h");
        assertPaintedContinuationAlignsUnderTheItemText(carriageReturn, 'g', 'h');
    }

    /** The painted first-line item text and the painted continuation text share one column. */
    private static void assertPaintedContinuationAlignsUnderTheItemText(String xml, char item,
            char continuation) {
        List<String> painted = new io.github.dhoard.braxton.view.ScreenRenderer()
                .plainLines(Markup.view(xml), new io.github.dhoard.braxton.layout.Viewport(4, 24));
        assertThat(painted.get(1).indexOf(continuation))
                .as("continuation column in %s", painted)
                .isEqualTo(painted.get(0).indexOf(item))
                .isNotEqualTo(-1);
    }

    @Test
    void paragraphsAndBreaksEndTheirLines() {
        assertThat(Markup.parse("<root><p>one</p><p>two</p></root>").plainText())
                .as("the trailing newline is stripped").isEqualTo("one\ntwo");
        assertThat(Markup.parse("<root>a<br/>b</root>").plainText()).isEqualTo("a\nb");
        assertThat(Markup.parse("<root><unknown>kept</unknown></root>").plainText())
                .isEqualTo("kept");
    }

    @Test
    void emptyElementsMustBeClosedExplicitlyBecauseTheDocumentIsXml() {
        // The documented contract: markup is a well-formed XML document, not HTML, so an empty
        // element is closed explicitly. A bare void tag is a malformed document, not a break.
        assertThat(Markup.parse("<root>a<br/>b</root>").plainText()).isEqualTo("a\nb");
        assertThat(Markup.parse("<root><span></span>empty</root>").plainText()).isEqualTo("empty");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> Markup.parse("<root>a<br>b</root>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid markup");
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> Markup.parse("<p>one</p><p>two</p>"))
                .as("one document has exactly one root element")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid markup");
    }
}
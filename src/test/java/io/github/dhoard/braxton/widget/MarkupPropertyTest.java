package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Rendered markup is inert text and its tables keep one row width. */
class MarkupPropertyTest {

    @Test
    void renderedMarkupCarriesNoControlCharacters() {
        String xml = "<root><p>a\u001B[31mb</p><span style=\"bold\">c\u009Bd</span>"
                + "<ul><li>e\u0007</li></ul><table><tr><td>f</td></tr></table></root>";
        String plain = Markup.parse(xml).plainText();
        for (char c : plain.toCharArray()) {
            assertThat(c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7F && !(c >= 0x80 && c <= 0x9F)))
                    .as("control 0x%x", (int) c).isTrue();
        }
    }

    @Test
    void tableRowsShareOneWidth() {
        String xml = "<root><table gap=\"1\"><tr><td>\u65E5</td><td>b</td></tr>"
                + "<tr><td>ccc</td><td>\u672C\u672C</td></tr></table></root>";
        List<String> lines = Markup.parse(xml).plainText().lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.stream().map(io.github.dhoard.braxton.style.TextMeasure::columns).distinct())
                .hasSize(1);
    }

    @Test
    void aParentLinkAppliesToChildrenAndABackgroundIsInherited() {
        var linked = Markup.parse("<root><a href=\"https://x\"><span>one</span><span>two</span>"
                + "</a></root>");
        assertThat(linked.spans()).filteredOn(span -> !span.text().isBlank())
                .allSatisfy(span -> assertThat(span.link()).isEqualTo("https://x"));
        var background = Markup.parse("<root><span bg-color=\"#102030\"><span>inner</span>"
                + "</span></root>");
        assertThat(background.spans()).filteredOn(span -> !span.text().isBlank())
                .allSatisfy(span -> assertThat(span.style().background())
                        .isEqualTo(io.github.dhoard.braxton.style.Color.parse("#102030").orElseThrow()));
    }
}

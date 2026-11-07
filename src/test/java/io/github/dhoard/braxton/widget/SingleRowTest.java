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

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;

/** The one-row preparation shared by the widgets whose painted height is fixed. */
class SingleRowTest {

    @Test
    void survivingLineBreaksAndTabsBecomeOneSpaceEach() {
        StyledText value = StyledText.empty()
                .append(StyledText.of("a\nb", Style.NONE))
                .append(StyledText.of("c\r\nd", Style.NONE))
                .append(StyledText.of("e\tf", Style.NONE));

        assertThat(SingleRow.of(value).plainText()).isEqualTo("a bc de f");
    }

    @Test
    void eachSpanKeepsItsStyleAndLink() {
        StyledText value = StyledText.empty()
                .append(StyledText.of("a\nb", Style.NONE.withBold(), "https://one"))
                .append(StyledText.of("c\td", Style.NONE.withItalic(), "https://two"));

        List<StyledText.Span> spans = SingleRow.of(value).spans();

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).text()).isEqualTo("a b");
        assertThat(spans.get(0).style()).isEqualTo(Style.NONE.withBold());
        assertThat(spans.get(0).link()).isEqualTo("https://one");
        assertThat(spans.get(1).text()).isEqualTo("c d");
        assertThat(spans.get(1).style()).isEqualTo(Style.NONE.withItalic());
        assertThat(spans.get(1).link()).isEqualTo("https://two");
    }

    @Test
    void aBareCrIsAlreadyGoneAtTheSpanBoundarySoOnlyTheRawStringRouteKeepsThatSeparator() {
        // Span deletes a bare CR as an unrepresentable control character, so by the time a
        // StyledText exists there is no separator left to convert. A caller that still holds raw
        // text converts it first; this method cannot recover it.
        assertThat(SingleRow.of(StyledText.plain("a\rb")).plainText()).isEqualTo("ab");
        assertThat(LineBreaks.singleRowAndTabs("a\rb")).isEqualTo("a b");
        assertThat(LineBreaks.normalize("a\rb")).isEqualTo("a\nb");
    }

    @Test
    void nullIsEmptyAndAnAlreadyFlatValueIsUnchangedInShape() {
        assertThat(SingleRow.of(null).plainText()).isEmpty();
        assertThat(SingleRow.of(StyledText.plain("plain")).plainText()).isEqualTo("plain");
        assertThat(SingleRow.of(StyledText.empty()).plainText()).isEmpty();
    }
}

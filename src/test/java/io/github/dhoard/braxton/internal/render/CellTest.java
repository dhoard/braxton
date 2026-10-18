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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;

/** A cell is a value, and only an untouched one counts as empty: a blank still occupies a column. */
class CellTest {

    @Test
    void missingValuesBecomeAnEmptyCell() {
        assertThat(new Cell(null, null, null)).isEqualTo(Cell.EMPTY);
        assertThat(new Cell(null, null, null).isEmpty()).isTrue();
        assertThat(new Cell("", Style.NONE, "   ")).isEqualTo(Cell.EMPTY);
        assertThat(Cell.EMPTY.text()).isEmpty();
        assertThat(Cell.EMPTY.style()).isEqualTo(Style.NONE);
        assertThat(Cell.EMPTY.link()).isNull();
    }

    /** A blank carrying a style or a link is painted content, so it is not an empty cell. */
    @Test
    void aBlankThatCarriesSomethingIsNotEmpty() {
        assertThat(new Cell(" ", Style.NONE, null).isEmpty()).isFalse();
        assertThat(new Cell("", Style.fg(Colors.ERROR_400), null).isEmpty()).isFalse();
        assertThat(new Cell("", Style.NONE, "https://example.test").isEmpty()).isFalse();
        assertThat(new Cell("", Style.NONE.withBold(), null).isEmpty()).isFalse();
    }

    @Test
    void equalityIsByValueIncludingTheLink() {
        Cell cell = new Cell("\u65E5", Style.NONE.withBold(), "https://example.test");
        assertThat(cell).isEqualTo(new Cell("\u65E5", Style.NONE.withBold(),
                "https://example.test"));
        assertThat(cell).hasSameHashCodeAs(new Cell("\u65E5", Style.NONE.withBold(),
                "https://example.test"));
        assertThat(cell).isNotEqualTo(new Cell("\u65E5", Style.NONE.withBold(), null));
        assertThat(cell).isNotEqualTo(new Cell("\u65E5", Style.NONE, "https://example.test"));
        assertThat(cell).isNotEqualTo(new Cell("\u65E5", Style.NONE.withItalic(),
                "https://example.test"));
    }
}

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

package io.github.dhoard.braxton.style;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Colour parsing accepts only the documented {@code #RRGGBB} form. */
class ColorTest {

    @Test
    void parsesSixHexDigitsInEitherCase() {
        assertThat(Color.parse("#00ff7f")).contains(new Color(0, 255, 127));
        assertThat(Color.parse("#00FF7F")).contains(new Color(0, 255, 127));
        assertThat(Color.parse("  #000000  ")).contains(Color.BLACK);
        assertThat(Color.parse("#ffffff")).contains(Color.WHITE);
    }

    @Test
    void rejectsSignCharactersThatIntegerParsingWouldAccept() {
        assertThat(Color.parse("#+0ff00")).isEmpty();
        assertThat(Color.parse("#-0ff00")).isEmpty();
        assertThat(Color.parse("#-00000")).isEmpty();
    }

    @Test
    void rejectsMissingHashWrongLengthAndNonHexDigits() {
        assertThat(Color.parse(null)).isEmpty();
        assertThat(Color.parse("00ff00")).isEmpty();
        assertThat(Color.parse("#00ff0")).isEmpty();
        assertThat(Color.parse("#00ff000")).isEmpty();
        assertThat(Color.parse("#00gg00")).isEmpty();
        assertThat(Color.parse("")).isEmpty();
    }

    @Test
    void parseOrFallsBackForInvalidInput() {
        assertThat(Color.parseOr("not-a-colour", Color.BLACK)).isEqualTo(Color.BLACK);
        assertThat(Color.parseOr("#123456", Color.BLACK)).isEqualTo(new Color(0x12, 0x34, 0x56));
    }
}

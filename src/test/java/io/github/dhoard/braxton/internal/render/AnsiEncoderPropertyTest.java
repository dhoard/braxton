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

import io.github.dhoard.braxton.style.Style;

/** A styled row always closes its styling, whatever the mix of cells. */
class AnsiEncoderPropertyTest {

    @Test
    void everyStyledRowEndsItsStylingAndLinks() {
        Style[] styles = {Style.NONE, Style.NONE.withBold(), Style.NONE.withInvert(),
                Style.NONE.withUnderline()};
        for (Style first : styles) {
            for (Style second : styles) {
                Screen screen = new Screen(1, 4);
                screen.put(0, 0, "a", first, "https://x", 1);
                screen.put(0, 1, "b", second, null, 1);
                screen.put(0, 2, "c", Style.NONE, null, 1);
                String row = AnsiEncoder.encodeRow(screen, 0, true);
                int resets = row.split("\u001B\\[0m", -1).length - 1;
                if (!first.isPlain() || !second.isPlain()) {
                    assertThat(resets).as("styled cells must be reset").isGreaterThanOrEqualTo(1);
                }
                // Each link target opens once and is closed once; the close may follow a reset.
                int linkTransitions = row.split("\u001B\\]8;;", -1).length - 1;
                assertThat(linkTransitions).as("the link opens and closes").isGreaterThanOrEqualTo(2);
                assertThat(row.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "")
                        .replaceAll("\u001B\\]8;;[^\u001B]*\u001B\\\\", ""))
                        .as("only the cells' text is visible").isEqualTo("abc");
            }
        }
    }
}

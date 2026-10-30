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

import org.junit.jupiter.api.Test;

class EditorStateBoundaryTest {
    @Test
    void caretAndBothSelectionEndsRoundDownInsideWholeClusters() {
        String[] clusters = {"e\u0301", "\uD83D\uDE00", "\uD83D\uDC69\u200D\uD83D\uDCBB", "\uD83C\uDDFA\uD83C\uDDF8"};
        for (String cluster : clusters) {
            Editor.Model original = Editor.Model.of("a" + cluster + "z");
            for (int inside = 1; inside < cluster.length(); inside++) {
                int offset = 1 + inside;
                Editor.Model caret = original.withCaret(offset);
                assertThat(caret.caret()).as("inside %s at %d", cluster, offset).isEqualTo(1);
                assertThat(caret.anchor()).isEqualTo(1);
                assertThat(original.withSelection(offset, original.text().length()).selection())
                        .isEqualTo(cluster + "z");
                assertThat(original.withSelection(original.text().length(), offset).selection())
                        .isEqualTo(cluster + "z");
                assertThat(original.withCaret(1 + cluster.length()).caret()).isEqualTo(1 + cluster.length());
            }
            assertThat(original.withCaret(Integer.MIN_VALUE).caret()).isZero();
            assertThat(original.withCaret(Integer.MAX_VALUE).caret()).isEqualTo(original.text().length());
        }
    }
}

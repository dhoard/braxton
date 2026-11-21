/*
 * Copyright (c) 2026-present Douglas Hoard
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

package io.github.dhoard.braxton.internal.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct contracts for the shared text, selection and virtualization engines.
 */
class SharedEnginesTest {

    @Test
    void textBufferEditsAreImmutable() {
        TextBuffer original = TextBuffer.of(List.of("abc"));
        TextBuffer edited = original.insert(0, 1, "XY").delete(0, 3, 5);
        assertThat(original.line(0)).isEqualTo("abc");
        assertThat(edited.line(0)).isEqualTo("aXY");
        assertThat(TextBuffer.empty().lineCount()).isEqualTo(1);
    }

    @Test
    void selectionNormalisesBothDirections() {
        SelectionModel forward = new SelectionModel(1, 5, 3, 2);
        SelectionModel backward = new SelectionModel(3, 2, 1, 5);
        assertThat(forward.startRow()).isEqualTo(backward.startRow()).isEqualTo(1);
        assertThat(forward.startCol()).isEqualTo(backward.startCol()).isEqualTo(5);
        assertThat(forward.endCol()).isEqualTo(backward.endCol()).isEqualTo(2);
        assertThat(forward.moveHead(1, 5).collapse().headCol()).isEqualTo(5);
    }

    @Test
    void virtualScrollClampsAndEnsuresVisibility() {
        VirtualScroll scroll = VirtualScroll.of(100, 10);
        assertThat(scroll.scrollTo(200).offset()).isEqualTo(90);
        assertThat(scroll.page(1).offset()).isEqualTo(10);
        assertThat(scroll.ensureVisible(25).offset()).isEqualTo(16);
        assertThat(scroll.ensureVisible(-1).offset()).isZero();
        assertThat(scroll.lastVisibleExclusive()).isEqualTo(10);
    }
}

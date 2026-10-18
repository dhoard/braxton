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

package io.github.dhoard.braxton.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Geometry values: validation, emptiness, selection and clipping. */
class GeometryTest {

    @Test
    void dimensionsRejectNegativeValuesAndReportEmptiness() {
        assertThatThrownBy(() -> new Size(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Viewport(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Rect(-1, 0, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Insets(0, -1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Size.EMPTY.isEmpty()).isTrue();
        assertThat(new Size(1, 0).isEmpty()).isTrue();
        assertThat(new Size(1, 1).isEmpty()).isFalse();
        assertThat(new Rect(0, 0, 0, 5).isEmpty()).isTrue();
        assertThat(new Rect(0, 0, 1, 5).isEmpty()).isFalse();
        assertThat(Size.EMPTY.toString()).isEqualTo("0x0");
        assertThat(new Viewport(3, 4).toString()).isEqualTo("3x4");
    }

    @Test
    void insetShrinksAndNeverGoesNegative() {
        Rect rect = new Rect(2, 3, 10, 20);
        Rect shrunk = rect.inset(Insets.uniform(1));
        assertThat(shrunk).isEqualTo(new Rect(3, 4, 8, 18));
        Rect over = rect.inset(Insets.uniform(50));
        assertThat(over.rows()).isZero();
        assertThat(over.cols()).isZero();
        assertThat(Insets.symmetric(2, 3)).isEqualTo(new Insets(2, 3, 2, 3));
        assertThat(Insets.uniform(1).horizontal()).isEqualTo(2);
        assertThat(Insets.uniform(1).vertical()).isEqualTo(2);
    }

    @Test
    void clipIntersectsAndContainsUsesHalfOpenBounds() {
        Rect bounds = new Rect(0, 0, 10, 10);
        Rect clipped = new Rect(5, 5, 20, 20).clip(bounds);
        assertThat(clipped).isEqualTo(new Rect(5, 5, 5, 5));
        assertThat(new Rect(20, 20, 1, 1).clip(bounds).isEmpty()).isTrue();
        assertThat(bounds.contains(0, 0)).isTrue();
        assertThat(bounds.contains(9, 9)).isTrue();
        assertThat(bounds.contains(10, 9)).isFalse();
        assertThat(bounds.contains(-1, 0)).isFalse();
    }

    @Test
    void viewportConvertsToItsSizeAndBounds() {
        Viewport viewport = Viewport.of(new Size(4, 7));
        assertThat(viewport).isEqualTo(new Viewport(4, 7));
        assertThat(viewport.size()).isEqualTo(new Size(4, 7));
        assertThat(viewport.bounds()).isEqualTo(new Rect(0, 0, 4, 7));
        assertThat(Viewport.EMPTY.bounds().isEmpty()).isTrue();
    }

    @Test
    void bordersReportTheirVisibility() {
        assertThat(Border.NONE.isVisible()).isFalse();
        assertThat(Border.SINGLE.isVisible()).isTrue();
        assertThat(Border.ROUNDED.isVisible()).isTrue();
        assertThat(Border.DOUBLE.isVisible()).isTrue();
        assertThat(Alignment.values()).containsExactly(Alignment.START, Alignment.CENTER,
                Alignment.END);
    }
}

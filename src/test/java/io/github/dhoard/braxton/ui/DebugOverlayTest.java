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

package io.github.dhoard.braxton.ui;

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DebugOverlayTest {
    private static final class FocusedWidget implements Widget<String, String> {
        public Size measure(Size max, String model) {
            return new Size(Math.min(1, max.rows()), Math.min(5, max.cols()));
        }

        public void paint(Surface surface, Rect bounds, String model) {}

        public boolean canFocus(String model) {
            return true;
        }

        public boolean focused(String model) {
            return model.equals("focus");
        }
    }

    private static Element<String> leaf(String model) {
        return new Element.Leaf<>(new FocusedWidget(), model);
    }

    @Test
    void captureUsesTheRoutersActiveModalFocusAndActualViewport() {
        Element<String> root = new Element.Group<>(
                List.of(leaf("focus"), new Element.Modal<>(leaf("focus"))), Element.Direction.COLUMN);
        Rect viewport = new Rect(0, 0, 12, 40);
        var placement = ElementTree.layout(root, viewport);
        var snapshot = DebugOverlay.capture(placement, viewport, List.of("changed"), 42000);
        assertThat(snapshot.focusPath()).startsWith("root/1/0 FocusedWidget");
        assertThat(snapshot.bounds()).doesNotHaveDuplicates();
        assertThat(snapshot.messages()).containsExactly("changed");
        assertThat(snapshot.frameNanos()).isEqualTo(42000);
        var smallRoot = ElementTree.layout(leaf("off"), viewport);
        assertThat(smallRoot.bounds().rows()).isEqualTo(1);
        assertThat(DebugOverlay.capture(smallRoot, viewport, List.of(), 0).viewport())
                .isEqualTo(viewport);
    }

    @Test
    void captureBoundsItsHistoryWithoutLosingAFocusBeyondTheBoundLimit() {
        List<Element<String>> children = new ArrayList<>();
        for (int i = 0; i < 300; i++) children.add(leaf(i == 299 ? "focus" : "off"));
        Rect viewport = new Rect(0, 0, 400, 5);
        var snapshot = DebugOverlay.capture(
                ElementTree.layout(new Element.Group<>(children, Element.Direction.COLUMN), viewport),
                viewport,
                List.of("0", "1", "2", "3", "4"),
                -1);
        assertThat(snapshot.bounds()).hasSize(DebugOverlay.MAX_BOUNDS);
        assertThat(snapshot.focusPath()).startsWith("root/299 FocusedWidget");
        assertThat(snapshot.messages()).containsExactly("1", "2", "3", "4");
        assertThat(snapshot.frameNanos()).isZero();
        assertThatThrownBy(() -> snapshot.messages().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.bounds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void labelsCannotInjectControlsAndAreBoundedBeforeEncoding() {
        String raw = "a".repeat(150) + "\u001b[31m\n\t" + "z".repeat(1000);
        String label = DebugOverlay.label(raw);
        assertThat(label).hasSizeLessThanOrEqualTo(DebugOverlay.MAX_LABEL_CHARS).doesNotContain("\u001b", "\n", "\t");
        assertThat(DebugOverlay.label("a".repeat(159) + "\ud83d\ude00")).hasSize(159);
        assertThatThrownBy(() -> DebugOverlay.label(null)).isInstanceOf(NullPointerException.class);
        var snapshot = new DebugOverlay.Snapshot(new Rect(0, 0, 0, 0), List.of(), null, List.of(raw), 0);
        assertThat(snapshot.focusPath()).isEqualTo("none");
        assertThat(snapshot.messages().getFirst()).isEqualTo(label);
    }

    @Test
    void paintingPreservesSourceGlyphsAndShowsGeometryFocusMessagesAndTiming() {
        Screen screen = new Screen(12, 40);
        screen.put(0, 0, "H", Style.NONE, null, 1);
        screen.put(0, 1, "i", Style.NONE, null, 1);
        var snapshot = new DebugOverlay.Snapshot(
                new Rect(0, 0, 12, 40),
                List.of(new Rect(0, 0, 1, 2)),
                "root/0 FocusedWidget",
                List.of("changed"),
                42000);
        DebugOverlay.paint(snapshot, screen);
        assertThat(screen.plainLine(0)).isEqualTo("Hi");
        assertThat(screen.plainLines()).anySatisfy(line -> assertThat(line).contains("DEBUG bounds=1 frame=42us"));
        assertThat(screen.plainLines()).anySatisfy(line -> assertThat(line).contains("bounds 0,0 12x40"));
        assertThat(screen.plainLines()).anySatisfy(line -> assertThat(line).contains("focus=root/0 FocusedWidget"));
        assertThat(screen.plainLines()).anySatisfy(line -> assertThat(line).contains("msg=changed"));
    }

    @Test
    void tinyAndOutlyingBoundsAreClippedToTheActualSurface() {
        for (int[] size : new int[][] {{0, 0}, {1, 1}, {2, 3}, {12, 40}}) {
            Rect viewport = new Rect(0, 0, size[0], size[1]);
            Screen screen = new Screen(size[0], size[1]);
            Surface guarded = new Surface() {
                private void check(int row, int col) {
                    assertThat(viewport.contains(row, col)).isTrue();
                }

                public void put(int row, int col, String text, Style style, String link, int width) {
                    check(row, col);
                    screen.put(row, col, text, style, link, width);
                }

                public void restyle(int row, int col, Style style) {
                    check(row, col);
                    screen.restyle(row, col, style);
                }

                public void fill(Rect rect, Style style) {
                    assertThat(rect.clip(viewport)).isEqualTo(rect);
                    screen.fill(rect, style);
                }
            };
            DebugOverlay.paint(
                    new DebugOverlay.Snapshot(viewport, List.of(new Rect(8, 39, 100, 100)), "none", List.of(), 0),
                    guarded);
        }
    }
}

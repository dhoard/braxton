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

package io.github.dhoard.braxton.internal.runtime;

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.widget.Elements;
import org.junit.jupiter.api.Test;

class RuntimeDiagnosticsTest {
    private static Screen paint(RuntimeDiagnostics diagnostics, long started, long ended) {
        Screen screen = new Screen(12, 80);
        Rect bounds = new Rect(0, 0, 12, 80);
        var placement = ElementTree.layout(Elements.text("scene"), bounds);
        ElementTree.paint(placement, screen);
        diagnostics.paint(placement, bounds, screen, started, ended);
        return screen;
    }

    @Test
    void historyIsBoundedAndRenderingUsesSuppliedTime() {
        RuntimeDiagnostics d = new RuntimeDiagnostics();
        for (int i = 0; i < 6; i++) d.record("event-" + i);
        String text = String.join("\n", paint(d, 1000, 43000).plainLines());
        assertThat(text).contains("frame=42us", "event-2", "event-5").doesNotContain("event-0", "event-1");
    }

    @Test
    void formattingFailuresAreVisibleAndCannotBreakTheApplication() {
        RuntimeDiagnostics d = new RuntimeDiagnostics();
        d.record(new Object() {
            public String toString() {
                throw new IllegalStateException("broken formatter");
            }
        });
        assertThat(String.join("\n", paint(d, 0, 0).plainLines())).contains("unprintable", "IllegalStateException");
    }

    @Test
    void negativeAndOverflowedClockGapsAreClampedOrSaturated() {
        RuntimeDiagnostics d = new RuntimeDiagnostics();
        assertThat(String.join("\n", paint(d, 10, 0).plainLines())).contains("frame=0us");
        assertThat(String.join("\n", paint(d, Long.MAX_VALUE, Long.MIN_VALUE).plainLines()))
                .contains("frame=0us");
        assertThat(String.join("\n", paint(d, Long.MIN_VALUE, Long.MAX_VALUE).plainLines()))
                .contains("frame=" + Long.MAX_VALUE / 1000 + "us");
    }
}

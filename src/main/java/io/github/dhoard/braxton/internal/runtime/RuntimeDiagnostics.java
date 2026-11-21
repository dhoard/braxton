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

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.ui.DebugOverlay;
import io.github.dhoard.braxton.ui.ElementTree;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Owner-thread diagnostics, allocated only while enabled and retaining no tree or model.
 */
final class RuntimeDiagnostics {
    private final ArrayDeque<String> messages = new ArrayDeque<>();

    void record(Object message) {
        String summary;
        try {
            summary = DebugOverlay.label(String.valueOf(message));
        } catch (RuntimeException failure) {
            // Formatting an application-owned message must not break the application. Report the
            // formatting failure in the overlay rather than suppressing it silently.
            summary = DebugOverlay.label("unprintable " + message.getClass().getSimpleName() + " ("
                    + failure.getClass().getSimpleName() + ")");
        }
        if (messages.size() == DebugOverlay.MAX_MESSAGES) messages.removeFirst();
        messages.addLast(summary);
    }

    <Msg> void paint(ElementTree.Placement<Msg> placement, Rect viewport, Surface surface, long started, long ended) {
        long elapsed;
        try {
            elapsed = Math.max(0, Math.subtractExact(ended, started));
        } catch (ArithmeticException overflow) {
            elapsed = ended > started ? Long.MAX_VALUE : 0;
        }
        DebugOverlay.paint(DebugOverlay.capture(placement, viewport, List.copyOf(messages), elapsed), surface);
    }
}

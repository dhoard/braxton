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

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;

/**
 * Time-driven animation frames.
 *
 * <p>Frames are pure functions of the runtime's monotonic clock, so there is no per-widget timer,
 * thread, or executor. A view subscribes to ticks and passes the current time in.</p>
 */
public final class Animation {

    private static final String[] SPINNER = {"\u25C7", "\u25C8", "\u25C6", "\u25C8"};

    private Animation() {
    }

    /** A four-frame spinner advancing every 250 ms. */
    public static StyledText spinner(long nowNanos, long startedNanos, Style style) {
        long elapsedMillis = Math.max(0, (nowNanos - startedNanos) / 1_000_000L);
        int frame = Math.floorMod(elapsedMillis / 250L, SPINNER.length);
        return StyledText.of(SPINNER[frame], style);
    }

    /** A shimmering highlight travelling across {@code text}. */
    public static StyledText shimmer(String text, long nowNanos, Color base, Color highlight) {
        String value = text == null ? "" : text;
        Style baseStyle = base == null ? Style.NONE : Style.fg(base);
        Style highlightStyle = highlight == null ? baseStyle : Style.fg(highlight);
        if (value.isEmpty()) {
            return StyledText.empty();
        }
        java.util.List<String> clusters = io.github.dhoard.braxton.style.TextMeasure.graphemes(value);
        int period = clusters.size() + 12;
        int position = Math.floorMod(nowNanos / 70_000_000L, Math.max(1, period));
        StyledText out = StyledText.empty();
        for (int i = 0; i < clusters.size(); i++) {
            boolean lit = i >= position - 3 && i <= position;
            out = out.append(StyledText.of(clusters.get(i), lit ? highlightStyle : baseStyle));
        }
        return out;
    }

    /** The number of frames in a full spinner cycle. */
    public static int spinnerFrames() {
        return SPINNER.length;
    }
}

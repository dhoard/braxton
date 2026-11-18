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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import java.util.List;

/**
 * Time-driven animation frames.
 *
 * <p>Frames are pure functions of the runtime's monotonic clock, so there is no per-widget timer,
 * thread, or executor. A view subscribes to ticks and passes the current time in.</p>
 *
 * <p>The methods here are the original fixed animations: the four-diamond {@link #spinner} and the
 * {@link #shimmer} highlight. A spinner whose frames, cadence or style the application chooses is
 * {@link Spinner}, which is also a component that owns its own interval; both select a frame with
 * the same {@link Spinner#frameIndex} arithmetic, so the two cannot disagree about which frame a
 * given time belongs to.</p>
 */
public final class Animation {

    /** The legacy spinner's frames: four diamonds rehearsed in a quarter-second cadence. */
    private static final List<String> SPINNER = List.of("\u25C7", "\u25C8", "\u25C6", "\u25C8");

    /** How long one legacy spinner frame lasts, in nanoseconds. */
    private static final long SPINNER_STEP_NANOS = 250_000_000L;

    private Animation() {}

    /**
     * A four-frame spinner advancing every 250 ms.
     *
     * <p>This is the original fixed spinner, kept identical for the callers that already use it:
     * four diamond frames on a quarter-second cadence. Use {@link Spinner} for frames, cadence or
     * style the application chooses.</p>
     *
     * @param nowNanos the current monotonic time, in nanoseconds
     * @param startedNanos the time the animation started, in nanoseconds
     * @param style the style applied to the frame
     * @return the frame for that time
     */
    public static StyledText spinner(long nowNanos, long startedNanos, Style style) {
        String frame = Spinner.frameAt(SPINNER, SPINNER_STEP_NANOS, Spinner.elapsed(startedNanos, nowNanos));
        return StyledText.of(frame, style);
    }

    /**
     * A shimmering highlight travelling across {@code text}.
     *
     * @param text the text to animate; null is empty
     * @param nowNanos the current monotonic time, in nanoseconds
     * @param base the colour of the unlit text, or null for the inherited colour
     * @param highlight the colour of the highlight, or null for {@code base}
     * @return the styled text for that time
     */
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
        StyledText[] parts = new StyledText[clusters.size()];
        for (int i = 0; i < clusters.size(); i++) {
            boolean lit = i >= position - 3 && i <= position;
            parts[i] = StyledText.of(clusters.get(i), lit ? highlightStyle : baseStyle);
        }
        return StyledText.concat(parts);
    }

    /**
     * The number of frames in a full spinner cycle.
     *
     * @return the frame count
     */
    public static int spinnerFrames() {
        return SPINNER.size();
    }
}

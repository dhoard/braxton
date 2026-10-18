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

import java.util.ArrayList;
import java.util.List;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/**
 * A horizontal bar with an active/inactive fill, an optional percentage label, and separators.
 *
 * <p>Values are clamped: a bar never renders more cells than its width, and an unusable width or
 * maximum leaves the bar empty instead of throwing. The bar is one row, so line breaks and tabs in
 * its icons, prepended label, or appended label become spaces.</p>
 */
public final class ProgressBar {

    private final int width;
    private final int buffer;
    private final String activeIcon;
    private final String inactiveIcon;
    private final Style activeStyle;
    private final Style inactiveStyle;
    private final boolean showPercentage;
    private final StyledText prepend;
    private final StyledText append;

    private ProgressBar(int width, int buffer, String activeIcon, String inactiveIcon,
            Style activeStyle, Style inactiveStyle, boolean showPercentage, StyledText prepend,
            StyledText append) {
        this.width = width;
        this.buffer = buffer;
        this.activeIcon = activeIcon;
        this.inactiveIcon = inactiveIcon;
        this.activeStyle = activeStyle;
        this.inactiveStyle = inactiveStyle;
        this.showPercentage = showPercentage;
        this.prepend = prepend;
        this.append = append;
    }

    public static ProgressBar of(int width) {
        return new ProgressBar(width, 0, "\u2588", "\u2591", Style.NONE,
                Style.NONE.withItalic(), false, StyledText.empty(), StyledText.empty());
    }

    public ProgressBar width(int value) {
        return new ProgressBar(value, buffer, activeIcon, inactiveIcon, activeStyle, inactiveStyle,
                showPercentage, prepend, append);
    }

    public ProgressBar buffer(int value) {
        return new ProgressBar(width, Math.max(0, value), activeIcon, inactiveIcon, activeStyle,
                inactiveStyle, showPercentage, prepend, append);
    }

    public ProgressBar icons(String active, String inactive) {
        return new ProgressBar(width, buffer, singleRow(active), singleRow(inactive), activeStyle,
                inactiveStyle, showPercentage, prepend, append);
    }

    public ProgressBar colors(Color active, Color inactive) {
        return new ProgressBar(width, buffer, activeIcon, inactiveIcon,
                active == null ? activeStyle : Style.fg(active),
                inactive == null ? inactiveStyle : Style.fg(inactive), showPercentage, prepend,
                append);
    }

    public ProgressBar showPercentage(boolean value) {
        return new ProgressBar(width, buffer, activeIcon, inactiveIcon, activeStyle, inactiveStyle,
                value, prepend, append);
    }

    public ProgressBar prepend(StyledText value) {
        return new ProgressBar(width, buffer, activeIcon, inactiveIcon, activeStyle, inactiveStyle,
                showPercentage, singleRow(value), append);
    }

    public ProgressBar append(StyledText value) {
        return new ProgressBar(width, buffer, activeIcon, inactiveIcon, activeStyle, inactiveStyle,
                showPercentage, prepend, singleRow(value));
    }

    /** A bar occupies one row, so line breaks and tabs become spaces, keeping each span's style. */
    private static StyledText singleRow(StyledText text) {
        if (text == null) {
            return StyledText.empty();
        }
        StyledText result = StyledText.empty();
        for (StyledText.Span span : text.spans()) {
            result = result.append(StyledText.of(flatten(span.text()), span.style(), span.link()));
        }
        return result;
    }

    private static String singleRow(String text) {
        return text == null ? "" : flatten(text);
    }

    private static String flatten(String text) {
        return text.replace('\n', ' ').replace('\t', ' ');
    }

    /** Renders a fraction of {@code value / max}; invalid bounds render an empty bar. */
    public StyledText render(double value, double max) {
        int cells = Math.max(0, width);
        if (cells == 0 || max <= 0 || !Double.isFinite(max)) {
            return StyledText.empty();
        }
        double clamped = Math.max(0, Math.min(1, value / max));
        int filled = (int) Math.round(clamped * cells);
        StyledText bar = prepend;
        for (int i = 0; i < cells; i++) {
            if (i > 0 && buffer > 0) {
                bar = bar.append(StyledText.plain(" ".repeat(buffer)));
            }
            boolean on = i < filled;
            bar = bar.append(StyledText.of(on ? activeIcon : inactiveIcon,
                    on ? activeStyle : inactiveStyle));
        }
        if (showPercentage) {
            bar = bar.append(StyledText.of(
                    String.format(java.util.Locale.ROOT, " %d%%", Math.round(clamped * 100)),
                    Style.NONE));
        }
        return bar.append(append);
    }

    /** A single-line view of the bar. */
    public View<Object> view(double value, double max) {
        return Views.text(render(value, max));
    }

    /** Visible cell count for a rendered bar (useful for tests). */
    public int columns(double value, double max) {
        return TextMeasure.columns(render(value, max).plainText());
    }

    /** Up to {@code count} icon cells from the left, used by tests to assert fill order. */
    public List<String> icons(int count, double value, double max) {
        List<String> icons = new ArrayList<>();
        if (count <= 0) {
            return icons;
        }
        StyledText bar = render(value, max);
        for (String grapheme : TextMeasure.graphemes(bar.plainText())) {
            if (icons.size() >= count) {
                break;
            }
            icons.add(grapheme);
        }
        return icons;
    }
}

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

package io.github.dhoard.braxton.style;

/**
 * Immutable text styling: colours plus the SGR attributes Braxton emits.
 *
 * <p>{@code null} colours mean "inherit nothing"; the renderer simply omits that component. Use the
 * {@code with*} methods to derive a changed style; a style instance is shared freely across span
 * values and is safe to compare.</p>
 */
public record Style(Color foreground, Color background, boolean bold, boolean italic,
        boolean underline, boolean strikethrough, boolean invert, boolean hidden) {

    public static final Style NONE = new Style(null, null, false, false, false, false, false, false);

    public Style {
    }

    public static Style fg(Color foreground) {
        return NONE.withForeground(foreground);
    }

    public static Style bg(Color background) {
        return NONE.withBackground(background);
    }

    public Style withForeground(Color color) {
        return new Style(color, background, bold, italic, underline, strikethrough, invert, hidden);
    }

    public Style withBackground(Color color) {
        return new Style(foreground, color, bold, italic, underline, strikethrough, invert, hidden);
    }

    public Style withBold() {
        return new Style(foreground, background, true, italic, underline, strikethrough, invert, hidden);
    }

    public Style withItalic() {
        return new Style(foreground, background, bold, true, underline, strikethrough, invert, hidden);
    }

    public Style withUnderline() {
        return new Style(foreground, background, bold, italic, true, strikethrough, invert, hidden);
    }

    public Style withStrikethrough() {
        return new Style(foreground, background, bold, italic, underline, true, invert, hidden);
    }

    public Style withInvert() {
        return new Style(foreground, background, bold, italic, underline, strikethrough, true, hidden);
    }

    public Style withHidden() {
        return new Style(foreground, background, bold, italic, underline, strikethrough, invert, true);
    }

    /** True when this style would emit no SGR parameters at all. */
    public boolean isPlain() {
        return foreground == null && background == null && !bold && !italic && !underline
                && !strikethrough && !invert && !hidden;
    }

    /** Applies {@code overlay} on top of this style, inheriting unspecified colours. */
    public Style merge(Style overlay) {
        if (overlay == null) {
            return this;
        }
        return new Style(
                overlay.foreground != null ? overlay.foreground : foreground,
                overlay.background != null ? overlay.background : background,
                bold || overlay.bold,
                italic || overlay.italic,
                underline || overlay.underline,
                strikethrough || overlay.strikethrough,
                invert || overlay.invert,
                hidden || overlay.hidden);
    }
}

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

import static io.github.dhoard.braxton.widget.Markdown.DEFAULT_INDENT;
import static io.github.dhoard.braxton.widget.Markdown.DEFAULT_LINK_SCHEMES;
import static io.github.dhoard.braxton.widget.Markdown.DEFAULT_WIDTH;

import io.github.dhoard.braxton.widget.Markdown.Highlighter;
import io.github.dhoard.braxton.widget.Markdown.ImageHook;
import io.github.dhoard.braxton.widget.Markdown.Limits;
import io.github.dhoard.braxton.widget.Markdown.Theme;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The immutable definition of one projection.
 *
 * @param theme the styles the document paints with
 * @param highlighter the code-block highlighter
 * @param limits the bounds the projection is held to
 * @param width the cells one projected row may occupy
 * @param indent the cells one nesting level indents by
 * @param linkSchemes the link schemes that keep their hyperlink target, lower case
 * @param imageHook the hook that paints an image standing alone in a paragraph, or null to paint
 *     its alt text
 */
public record MarkdownConfig(
        Theme theme,
        Highlighter highlighter,
        Limits limits,
        int width,
        int indent,
        List<String> linkSchemes,
        ImageHook imageHook) {

    /**
     * Substitutes the defaults for a missing value and normalizes a negative width or indent.
     */
    public MarkdownConfig {
        theme = theme == null ? Theme.light() : theme;
        highlighter = highlighter == null ? Highlighter.light() : highlighter;
        limits = limits == null ? Limits.defaults() : limits;
        width = Math.max(0, width);
        indent = Math.max(0, indent);
        List<String> schemes = linkSchemes == null ? DEFAULT_LINK_SCHEMES : linkSchemes;
        List<String> normalized = new ArrayList<>(schemes.size());
        for (String scheme : schemes) {
            if (scheme != null && !scheme.isBlank()) {
                normalized.add(scheme.strip().toLowerCase(Locale.ROOT));
            }
        }
        linkSchemes = List.copyOf(normalized);
    }

    /**
     * The light definition: a light theme, its highlighter, the default bounds and an
     * eighty-column projection.
     *
     * @return the default definition
     */
    public static MarkdownConfig defaults() {
        return new MarkdownConfig(
                Theme.light(),
                Highlighter.light(),
                Limits.defaults(),
                DEFAULT_WIDTH,
                DEFAULT_INDENT,
                DEFAULT_LINK_SCHEMES,
                null);
    }

    /**
     * The dark definition: a dark theme and its highlighter, everything else default.
     *
     * @return the dark definition
     */
    public static MarkdownConfig dark() {
        return defaults().withTheme(Theme.dark()).withHighlighter(Highlighter.dark());
    }

    /**
     * The same definition with a different theme.
     *
     * @param value the theme
     * @return the new definition
     */
    public MarkdownConfig withTheme(Theme value) {
        return new MarkdownConfig(value, highlighter, limits, width, indent, linkSchemes, imageHook);
    }

    /**
     * The same definition with a different code highlighter.
     *
     * @param value the highlighter
     * @return the new definition
     */
    public MarkdownConfig withHighlighter(Highlighter value) {
        return new MarkdownConfig(theme, value, limits, width, indent, linkSchemes, imageHook);
    }

    /**
     * The same definition with different bounds.
     *
     * @param value the bounds
     * @return the new definition
     */
    public MarkdownConfig withLimits(Limits value) {
        return new MarkdownConfig(theme, highlighter, value, width, indent, linkSchemes, imageHook);
    }

    /**
     * The same definition with a different projection width.
     *
     * @param value the cells one row may occupy; zero paints every token on its own row
     * @return the new definition
     */
    public MarkdownConfig withWidth(int value) {
        return new MarkdownConfig(theme, highlighter, limits, value, indent, linkSchemes, imageHook);
    }

    /**
     * The same definition with a different nesting indent.
     *
     * @param value the cells one nesting level indents by
     * @return the new definition
     */
    public MarkdownConfig withIndent(int value) {
        return new MarkdownConfig(theme, highlighter, limits, width, value, linkSchemes, imageHook);
    }

    /**
     * The same definition with a different set of link schemes.
     *
     * @param value the schemes that keep a hyperlink target; null restores the defaults
     * @return the new definition
     */
    public MarkdownConfig withLinkSchemes(List<String> value) {
        return new MarkdownConfig(theme, highlighter, limits, width, indent, value, imageHook);
    }

    /**
     * The same definition with a different image hook.
     *
     * @param value the hook that paints the images a document names; null to paint alt text
     * @return the new definition
     */
    public MarkdownConfig withImageHook(ImageHook value) {
        return new MarkdownConfig(theme, highlighter, limits, width, indent, linkSchemes, value);
    }
}

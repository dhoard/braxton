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

import io.github.dhoard.braxton.internal.markdown.CodeHighlighter;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a markdown document into the styled logical lines a Braxton pane paints.
 *
 * <p>{@code Markdown} is the dialect Braxton supports, the themes that paint it, and the bounds that
 * keep a hostile document from costing an unbounded parse. It is a pure renderer plus a small,
 * bounded projection cache: {@link #document(String)} answers an immutable {@link Document} whose
 * {@link Document#lines()} are logical lines - one {@link StyledText} per row, holding no line break
 * - which are exactly what {@link TextViewport} pages. There is no component here and no message:
 * an application owns the source, decides when it changed and publishes the projection, so a
 * markdown page is one mount of the existing pager rather than a second scrolling implementation.</p>
 *
 * <h2>Dialect</h2>
 *
 * <p>The dialect is CommonMark plus the GFM extensions: tables, strikethrough, task list items and
 * autolinks, so a bare {@code https://example.com} is a link and so is {@code <https://example.com>}.
 * Block quotes, ordered and bullet lists (nested and tight or loose), fenced and indented code
 * blocks, thematic breaks, hard and soft line breaks, escapes, images, and inline HTML are all
 * recognised. An image paints its alt text and nothing else: a terminal renderer does not fetch a
 * picture. Raw HTML is <em>literal text</em> - every {@code <div>}, {@code <script>} and
 * {@code <style>} in a document is painted as the characters that were written, never as terminal
 * output and never executed, which is the same promise {@link Markup} keeps for its own grammar.</p>
 *
 * <h2>Links</h2>
 *
 * <p>A link paints its text with the theme's link style and carries a hyperlink target only when its
 * destination names one of {@link Config#linkSchemes()} - {@code http}, {@code https} and
 * {@code mailto} by default. A destination with no scheme, a relative path, a {@code javascript:} or
 * {@code file:} target, and a destination containing a control character all paint as plain styled
 * text with no target, so the renderer cannot hand a terminal a scheme it was not asked to allow.
 * The renderer itself never opens a link, reads a file or resolves a reference.</p>
 *
 * <h2>Themes and code highlighting</h2>
 *
 * <p>{@link Theme#light()} and {@link Theme#dark()} are the two documented palettes, and
 * {@link Config#defaults()} and {@link Config#dark()} pair each with a matching code highlighter.
 * Highlighting is a {@link Highlighter}: a pure function from a code block's language and text to
 * styled spans, so an application can plug its own in. The bundled tokenizers cover Java, JSON and a
 * POSIX shell; any other language, and a fence with no language, paints its text unchanged.</p>
 *
 * <h2>Bounds</h2>
 *
 * <p>Every projection is bounded by {@link Limits}: the input's UTF-8 size, the number of nodes the
 * renderer visits, the block and inline nesting it descends, and the cells it paints. A document
 * over a limit does not render partially: {@link Document#diagnostics()} names the limit that
 * stopped it with a typed {@link Diagnostic}, {@link Document#failed()} is then true, and the
 * document's lines are the diagnostic rows themselves, styled with the theme's diagnostic style. The
 * documented defaults are the ceilings - {@link Limits} keeps a caller inside them - so no
 * configuration can make a projection unbounded. The input size is checked before the text reaches
 * the parser, and the nesting limit is also handed to the parser, so a pathological document is
 * bounded while it is parsed rather than after an unbounded tree exists.</p>
 *
 * <h2>Caching</h2>
 *
 * <p>{@link #document(String)} keeps the few projections it answered last, keyed by the source text
 * and the whole configuration, and answers the same {@link Document} instance for a repeated
 * request. Rendering the same document again at the same width and theme therefore costs a lookup,
 * which is what a frame of an unchanged pager, a redraw after a theme toggle back to the previous
 * theme, or a second pane over one source relies on. A cache of four entries covers a document, its
 * light and dark projections and one more width without letting a resizing session keep a
 * projection per column count. Instances are safe to share between threads: a projection is
 * immutable and the cache is synchronized.</p>
 *
 * <h2>Paging a rendered document</h2>
 *
 * <p>{@link Document#content()} is the snapshot a {@link TextViewport} takes, so a pager is the
 * application's composition of this renderer, {@link TextViewport} for the window and
 * {@link TextInput} for a find field:</p>
 *
 * <pre>{@code
 * Markdown markdown = new Markdown(Markdown.Config.defaults().withWidth(columns));
 * Markdown.Document document = markdown.document(source);
 * Update<Model, Msg, Never> published =
 *         pager.update(model, new TextViewport.Msg.ContentChanged(document.content()));
 * }</pre>
 *
 * <p>Width is projection data: a prose paragraph is word-wrapped to {@link Config#width()} when it
 * is projected, so a wider terminal is a new projection rather than a second wrapping rule in the
 * pager. A renderer that keeps a document at one width inside a pane that changed width paints rows
 * that no longer fit; the demo re-projects on a resize, and the pager keeps its logical anchor.</p>
 */
public final class Markdown {

    /** The width a projection wraps prose to when the configuration names none. */
    public static final int DEFAULT_WIDTH = 80;

    /** The cells one nesting level of a list or a quote indents by. */
    public static final int DEFAULT_INDENT = 2;

    /** The largest input, in UTF-8 bytes, a projection accepts by default. */
    public static final int DEFAULT_MAX_INPUT_BYTES = 1 << 20;

    /** The most nodes a projection visits by default. */
    public static final int DEFAULT_MAX_NODES = 100_000;

    /** The deepest block and inline nesting a projection descends by default. */
    public static final int DEFAULT_MAX_DEPTH = 128;

    /** The most cells a projection paints by default, counting every row as at least one. */
    public static final long DEFAULT_MAX_CELLS = 1_000_000L;

    /** The link schemes a projected document keeps unless the configuration names others. */
    public static final List<String> DEFAULT_LINK_SCHEMES = List.of("http", "https", "mailto");

    /** How many projections one instance keeps. */
    private static final int CACHE_ENTRIES = 4;

    private final Config config;

    private final Cache cache = new Cache();

    /**
     * A renderer with one definition.
     *
     * @param config the definition, or null for the defaults
     */
    public Markdown(Config config) {
        this.config = config == null ? Config.defaults() : config;
    }

    /**
     * A renderer with the default definition.
     *
     * @return the renderer
     */
    public static Markdown of() {
        return new Markdown(Config.defaults());
    }

    /**
     * A renderer with one definition.
     *
     * @param config the definition, or null for the defaults
     * @return the renderer
     */
    public static Markdown of(Config config) {
        return new Markdown(config);
    }

    /**
     * The definition this renderer projects with.
     *
     * @return the definition
     */
    public Config config() {
        return config;
    }

    /**
     * The projection of one source at this definition's width, theme and bounds.
     *
     * <p>A repeated request for the same source and definition answers the same instance, so a
     * caller can compare it by reference to know that nothing was re-parsed. The source is never
     * modified, held beyond the cache or interpreted as anything but text.</p>
     *
     * @param source the markdown source; null is empty
     * @return the projection, never null
     */
    public Document document(String source) {
        String text = source == null ? "" : source;
        Key key = new Key(text, config);
        Document cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Document document = MarkdownRenderer.render(text, config);
        cache.put(key, document);
        return document;
    }

    /**
     * Drops the cached projections.
     *
     * <p>A definition is immutable, so a cached projection stays correct for the definition that
     * produced it; dropping the cache only gives back the memory its few documents hold.</p>
     */
    public void clearCache() {
        cache.clear();
    }

    /** The cache key: the whole projection input, because every part of it changes the output. */
    private record Key(String source, Config config) {}

    /** A bounded, synchronized, least-recently-used map from a key to its projection. */
    private static final class Cache {

        private final Map<Key, Document> entries = new LinkedHashMap<>(8, 0.75f, true);

        synchronized Document get(Key key) {
            return entries.get(key);
        }

        synchronized void put(Key key, Document document) {
            entries.put(key, document);
            Iterator<Key> oldest = entries.keySet().iterator();
            while (entries.size() > CACHE_ENTRIES && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }

        synchronized void clear() {
            entries.clear();
        }
    }

    /**
     * The bounds one projection is held to.
     *
     * <p>Each value is a ceiling, not a target: {@link #DEFAULT_MAX_INPUT_BYTES} and the other
     * documented defaults are the largest a projection accepts, so a definition can lower a bound
     * for a document it does not trust and cannot raise one. A value below one is treated as one,
     * because a zero-sized bound could not project the empty document either.</p>
     *
     * @param inputBytes the largest accepted source, in UTF-8 bytes
     * @param nodes the most parser nodes a projection visits
     * @param depth the deepest block and inline nesting a projection descends
     * @param cells the most cells a projection paints, counting one per row at least
     */
    public record Limits(int inputBytes, int nodes, int depth, long cells) {

        /**
         * Keeps every bound inside the documented ceiling.
         */
        public Limits {
            inputBytes = clamp(inputBytes, DEFAULT_MAX_INPUT_BYTES);
            nodes = clamp(nodes, DEFAULT_MAX_NODES);
            depth = clamp(depth, DEFAULT_MAX_DEPTH);
            cells = clamp(cells, DEFAULT_MAX_CELLS);
        }

        /**
         * The documented defaults.
         *
         * @return the default bounds
         */
        public static Limits defaults() {
            return new Limits(DEFAULT_MAX_INPUT_BYTES, DEFAULT_MAX_NODES, DEFAULT_MAX_DEPTH, DEFAULT_MAX_CELLS);
        }

        private static int clamp(int value, int ceiling) {
            return Math.max(1, Math.min(value, ceiling));
        }

        private static long clamp(long value, long ceiling) {
            return Math.max(1L, Math.min(value, ceiling));
        }
    }

    /**
     * What one styled role paints with.
     *
     * <p>A style is merged over the styles around it, so an emphasis inside a heading keeps the
     * heading's colour and adds the italic attribute, and a code block's background survives the
     * highlighter's token colours. A theme is a value: two themes with the same styles are equal, so
     * a projection cached under one is answered under the other.</p>
     *
     * @param text the body text
     * @param headings the six heading levels, index 0 for level 1
     * @param emphasis italic emphasis
     * @param strong strong emphasis
     * @param strikethrough struck-through text
     * @param code inline code
     * @param codeBlock the base style of a fenced or indented code block
     * @param link link text
     * @param quote quoted text
     * @param muted raw HTML, image alt text and the quotation bar
     * @param marker list bullets, ordered numbers, task boxes and list continuation indents
     * @param rule a thematic break
     * @param border the table frame
     * @param header table header cells
     * @param diagnostic a bound a document was stopped by
     */
    public record Theme(
            Style text,
            List<Style> headings,
            Style emphasis,
            Style strong,
            Style strikethrough,
            Style code,
            Style codeBlock,
            Style link,
            Style quote,
            Style muted,
            Style marker,
            Style rule,
            Style border,
            Style header,
            Style diagnostic) {

        /** The number of heading levels markdown has. */
        private static final int LEVELS = 6;

        /**
         * Keeps every style non-null and the heading list at exactly six levels.
         */
        public Theme {
            text = orPlain(text);
            emphasis = orPlain(emphasis);
            strong = orPlain(strong);
            strikethrough = orPlain(strikethrough);
            code = orPlain(code);
            codeBlock = orPlain(codeBlock);
            link = orPlain(link);
            quote = orPlain(quote);
            muted = orPlain(muted);
            marker = orPlain(marker);
            rule = orPlain(rule);
            border = orPlain(border);
            header = orPlain(header);
            diagnostic = orPlain(diagnostic);
            List<Style> levels = headings == null || headings.isEmpty() ? List.of(Style.NONE) : headings;
            List<Style> normalized = new ArrayList<>(LEVELS);
            for (int level = 0; level < LEVELS; level++) {
                normalized.add(orPlain(levels.get(Math.min(level, levels.size() - 1))));
            }
            headings = List.copyOf(normalized);
        }

        private static Style orPlain(Style style) {
            return style == null ? Style.NONE : style;
        }

        /**
         * The style a heading of one level paints with.
         *
         * @param level the heading level, 1 to 6
         * @return the style for that level, the nearest one when the level is outside that range
         */
        public Style heading(int level) {
            return headings.get(Math.max(0, Math.min(level, LEVELS) - 1));
        }

        /**
         * A palette for a light terminal background.
         *
         * @return the light theme
         */
        public static Theme light() {
            return new Theme(
                    Style.NONE,
                    List.of(
                            Style.fg(Colors.INDIGO_400).withBold().withUnderline(),
                            Style.fg(Colors.INDIGO_400).withBold(),
                            Style.fg(Colors.BLUE_400).withBold(),
                            Style.fg(Colors.GRAY_600).withBold(),
                            Style.fg(Colors.GRAY_600).withBold(),
                            Style.fg(Colors.GRAY_500).withBold()),
                    Style.NONE.withItalic(),
                    Style.NONE.withBold(),
                    Style.NONE.withStrikethrough(),
                    Style.fg(Colors.ROSE_400),
                    Style.bg(Colors.GRAY_100),
                    Style.fg(Colors.BLUE_400).withUnderline(),
                    Style.fg(Colors.GRAY_600).withItalic(),
                    Style.fg(Colors.GRAY_500),
                    Style.fg(Colors.TEAL_400),
                    Style.fg(Colors.GRAY_400),
                    Style.fg(Colors.GRAY_400),
                    Style.NONE.withBold(),
                    Style.fg(Colors.ERROR_500));
        }

        /**
         * A palette for a dark terminal background.
         *
         * @return the dark theme
         */
        public static Theme dark() {
            return new Theme(
                    Style.NONE,
                    List.of(
                            Style.fg(Colors.CYAN_400).withBold().withUnderline(),
                            Style.fg(Colors.CYAN_400).withBold(),
                            Style.fg(Colors.BLUE_400).withBold(),
                            Style.fg(Colors.GRAY_200).withBold(),
                            Style.fg(Colors.GRAY_200).withBold(),
                            Style.fg(Colors.GRAY_300).withBold()),
                    Style.NONE.withItalic(),
                    Style.NONE.withBold(),
                    Style.NONE.withStrikethrough(),
                    Style.fg(Colors.ROSE_400),
                    Style.bg(Colors.GRAY_600),
                    Style.fg(Colors.BLUE_400).withUnderline(),
                    Style.fg(Colors.GRAY_200).withItalic(),
                    Style.fg(Colors.GRAY_400),
                    Style.fg(Colors.TEAL_400),
                    Style.fg(Colors.GRAY_500),
                    Style.fg(Colors.GRAY_500),
                    Style.NONE.withBold(),
                    Style.fg(Colors.ERROR_300));
        }

        /**
         * The same theme with a different body style.
         *
         * @param value the style body text paints with
         * @return the new theme
         */
        public Theme withText(Style value) {
            return new Theme(
                    value,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with different heading styles.
         *
         * @param value the styles for levels 1 to 6, padded with the last one it holds
         * @return the new theme
         */
        public Theme withHeadings(List<Style> value) {
            return new Theme(
                    text,
                    value,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different emphasis style.
         *
         * @param value the style italic emphasis paints with
         * @return the new theme
         */
        public Theme withEmphasis(Style value) {
            return new Theme(
                    text,
                    headings,
                    value,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different strong style.
         *
         * @param value the style strong emphasis paints with
         * @return the new theme
         */
        public Theme withStrong(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    value,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different strikethrough style.
         *
         * @param value the style struck-through text paints with
         * @return the new theme
         */
        public Theme withStrikethrough(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    value,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different inline-code style.
         *
         * @param value the style inline code paints with
         * @return the new theme
         */
        public Theme withCode(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    value,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different code-block style.
         *
         * @param value the base style a code block paints with
         * @return the new theme
         */
        public Theme withCodeBlock(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    value,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different link style.
         *
         * @param value the style link text paints with
         * @return the new theme
         */
        public Theme withLink(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    value,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different quotation style.
         *
         * @param value the style quoted text paints with
         * @return the new theme
         */
        public Theme withQuote(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    value,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different muted style.
         *
         * @param value the style raw HTML, image alt text and the quotation bar paint with
         * @return the new theme
         */
        public Theme withMuted(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    value,
                    marker,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different marker style.
         *
         * @param value the style list markers and continuation indents paint with
         * @return the new theme
         */
        public Theme withMarker(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    value,
                    rule,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different rule style.
         *
         * @param value the style a thematic break paints with
         * @return the new theme
         */
        public Theme withRule(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    value,
                    border,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different table-frame style.
         *
         * @param value the style a table frame paints with
         * @return the new theme
         */
        public Theme withBorder(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    value,
                    header,
                    diagnostic);
        }

        /**
         * The same theme with a different table-header style.
         *
         * @param value the style table header cells paint with
         * @return the new theme
         */
        public Theme withHeader(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    value,
                    diagnostic);
        }

        /**
         * The same theme with a different diagnostic style.
         *
         * @param value the style a bound's diagnostic row paints with
         * @return the new theme
         */
        public Theme withDiagnostic(Style value) {
            return new Theme(
                    text,
                    headings,
                    emphasis,
                    strong,
                    strikethrough,
                    code,
                    codeBlock,
                    link,
                    quote,
                    muted,
                    marker,
                    rule,
                    border,
                    header,
                    value);
        }
    }

    /**
     * Highlights one fenced code block.
     *
     * <p>An implementation must be pure and deterministic: the same language and text answer the
     * same spans, nothing is read or written, and no terminal escape sequence is produced. The
     * renderer merges the theme's code-block style over the spans the highlighter answers, so a
     * highlighter only has to name token colours.</p>
     */
    @FunctionalInterface
    public interface Highlighter {

        /**
         * The spans one code block paints with.
         *
         * @param language the fence's language, lower case, empty when it declared none
         * @param code the block's text without its fences
         * @return the styled block, whose line breaks are the block's own
         */
        StyledText highlight(String language, String code);

        /**
         * A highlighter that paints code exactly as it was written.
         *
         * @return the plain highlighter
         */
        static Highlighter plain() {
            return Bundled.PLAIN;
        }

        /**
         * The bundled Java, JSON and shell tokenizers with their light palette.
         *
         * @return the light highlighter
         */
        static Highlighter light() {
            return Bundled.LIGHT;
        }

        /**
         * The bundled Java, JSON and shell tokenizers with their dark palette.
         *
         * @return the dark highlighter
         */
        static Highlighter dark() {
            return Bundled.DARK;
        }
    }

    /** The three highlighters the widget ships, so a definition's identity is stable. */
    private enum Bundled implements Highlighter {
        PLAIN,
        LIGHT,
        DARK;

        @Override
        public StyledText highlight(String language, String code) {
            return switch (this) {
                case PLAIN -> StyledText.plain(code == null ? "" : code);
                case LIGHT -> CodeHighlighter.highlight(code, language, CodeHighlighter.Styles.light());
                case DARK -> CodeHighlighter.highlight(code, language, CodeHighlighter.Styles.dark());
            };
        }
    }

    /**
     * A limit that stopped a projection.
     *
     * <p>Diagnostics are typed values, not strings: an application can tell an input that was too
     * large from a document that painted too many cells and say so in its own words.
     * {@link #message()} is the bounded description the renderer paints when it stops.</p>
     */
    public sealed interface Diagnostic {

        /**
         * A bounded, single-line description of this diagnostic.
         *
         * @return the description, with no control characters
         */
        String message();

        /**
         * The source was larger than the projection accepts.
         *
         * @param bytes the source's UTF-8 size
         * @param limit the largest accepted size
         */
        record InputTooLarge(long bytes, long limit) implements Diagnostic {

            @Override
            public String message() {
                return "input too large: " + bytes + " bytes, limit " + limit;
            }
        }

        /**
         * The document held more nodes than the projection visits.
         *
         * @param nodes the nodes visited when the bound was crossed
         * @param limit the most nodes a projection visits
         */
        record TooManyNodes(long nodes, long limit) implements Diagnostic {

            @Override
            public String message() {
                return "too many nodes: more than " + limit;
            }
        }

        /**
         * The document nested deeper than the projection descends.
         *
         * @param depth the depth that crossed the bound
         * @param limit the deepest nesting a projection descends
         */
        record NestingTooDeep(int depth, int limit) implements Diagnostic {

            @Override
            public String message() {
                return "nesting too deep: more than " + limit;
            }
        }

        /**
         * The document painted more cells than the projection allows.
         *
         * @param cells the cells painted when the bound was crossed
         * @param limit the most cells a projection paints
         */
        record TooManyCells(long cells, long limit) implements Diagnostic {

            @Override
            public String message() {
                return "too many cells: more than " + limit;
            }
        }
    }

    /**
     * One immutable projection: the stylistic rows a document paints and any bound that stopped it.
     *
     * <p>A document that fits its bounds holds rows and no diagnostics. One that does not holds
     * diagnostics and their rows instead, so a caller can paint what {@link #lines()} returns either
     * way and report {@link #diagnostics()} beside it.</p>
     */
    public static final class Document {

        private final List<StyledText> lines;

        private final List<Diagnostic> diagnostics;

        private final TextViewport.Content content;

        private volatile StyledText text;

        /**
         * A projection of some rows.
         *
         * <p>The renderer only ever publishes single-line rows, so the content snapshot is built
         * over them directly instead of splitting every row again, and the whole-document value is
         * joined on first use: a pager that never asks for it never pays for it.</p>
         *
         * @param lines the logical lines, in paint order
         * @param diagnostics the bounds that stopped the projection
         */
        Document(List<StyledText> lines, List<Diagnostic> diagnostics) {
            this.lines = List.copyOf(lines);
            this.diagnostics = List.copyOf(diagnostics);
            this.content = new Rows(this.lines);
        }

        /**
         * The projected logical lines, in paint order.
         *
         * @return an immutable list of single-line values
         */
        public List<StyledText> lines() {
            return lines;
        }

        /**
         * The bounds that stopped the projection, in the order they were crossed.
         *
         * @return an immutable list, empty when the document fit its bounds
         */
        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        /**
         * Whether a bound stopped this projection.
         *
         * @return whether {@link #diagnostics()} is not empty
         */
        public boolean failed() {
            return !diagnostics.isEmpty();
        }

        /**
         * The number of projected logical lines.
         *
         * @return the line count
         */
        public int lineCount() {
            return lines.size();
        }

        /**
         * The snapshot a {@link TextViewport} pages.
         *
         * @return the content snapshot of {@link #lines()}, built once per projection
         */
        public TextViewport.Content content() {
            return content;
        }

        /**
         * The whole projection as one styled value, its lines separated by line breaks.
         *
         * @return the projection as one value
         */
        public StyledText text() {
            StyledText joined = text;
            if (joined == null) {
                StyledText value = StyledText.empty();
                for (int index = 0; index < lines.size(); index++) {
                    if (index > 0) {
                        value = value.append("\n");
                    }
                    value = value.append(lines.get(index));
                }
                text = value;
                joined = value;
            }
            return joined;
        }

        /**
         * A one-element view of the whole projection.
         *
         * @return a text view holding {@link #text()}
         */
        public View<Object> view() {
            return Views.text(text());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Document document
                    && lines.equals(document.lines)
                    && diagnostics.equals(document.diagnostics);
        }

        @Override
        public int hashCode() {
            return Objects.hash(lines, diagnostics);
        }

        /** Bounded diagnostic form; never contains escape sequences. */
        @Override
        public String toString() {
            return "Markdown.Document[" + lines.size() + " lines, diagnostics=" + diagnostics.size() + "]";
        }
    }

    /**
     * A projection's content snapshot: the rows the renderer already made single-line.
     *
     * <p>Identity is the content version, as {@link TextViewport.Content} requires, so two snapshots
     * over equal rows are still two versions and a model comparison never walks the document.</p>
     */
    private static final class Rows implements TextViewport.Content {

        private final List<StyledText> lines;

        private Rows(List<StyledText> lines) {
            this.lines = lines;
        }

        @Override
        public int lineCount() {
            return lines.size();
        }

        @Override
        public StyledText line(int index) {
            return lines.get(index);
        }

        @Override
        public String toString() {
            return "Markdown.Content[" + lines.size() + " lines]";
        }
    }

    /**
     * The immutable definition of one projection.
     *
     * @param theme the styles the document paints with
     * @param highlighter the code-block highlighter
     * @param limits the bounds the projection is held to
     * @param width the cells one projected row may occupy
     * @param indent the cells one nesting level indents by
     * @param linkSchemes the link schemes that keep their hyperlink target, lower case
     */
    public record Config(
            Theme theme, Highlighter highlighter, Limits limits, int width, int indent, List<String> linkSchemes) {

        /**
         * Substitutes the defaults for a missing value and normalizes a negative width or indent.
         */
        public Config {
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
        public static Config defaults() {
            return new Config(
                    Theme.light(),
                    Highlighter.light(),
                    Limits.defaults(),
                    DEFAULT_WIDTH,
                    DEFAULT_INDENT,
                    DEFAULT_LINK_SCHEMES);
        }

        /**
         * The dark definition: a dark theme and its highlighter, everything else default.
         *
         * @return the dark definition
         */
        public static Config dark() {
            return defaults().withTheme(Theme.dark()).withHighlighter(Highlighter.dark());
        }

        /**
         * The same definition with a different theme.
         *
         * @param value the theme
         * @return the new definition
         */
        public Config withTheme(Theme value) {
            return new Config(value, highlighter, limits, width, indent, linkSchemes);
        }

        /**
         * The same definition with a different code highlighter.
         *
         * @param value the highlighter
         * @return the new definition
         */
        public Config withHighlighter(Highlighter value) {
            return new Config(theme, value, limits, width, indent, linkSchemes);
        }

        /**
         * The same definition with different bounds.
         *
         * @param value the bounds
         * @return the new definition
         */
        public Config withLimits(Limits value) {
            return new Config(theme, highlighter, value, width, indent, linkSchemes);
        }

        /**
         * The same definition with a different projection width.
         *
         * @param value the cells one row may occupy; zero paints every token on its own row
         * @return the new definition
         */
        public Config withWidth(int value) {
            return new Config(theme, highlighter, limits, value, indent, linkSchemes);
        }

        /**
         * The same definition with a different nesting indent.
         *
         * @param value the cells one nesting level indents by
         * @return the new definition
         */
        public Config withIndent(int value) {
            return new Config(theme, highlighter, limits, width, value, linkSchemes);
        }

        /**
         * The same definition with a different set of link schemes.
         *
         * @param value the schemes that keep a hyperlink target; null restores the defaults
         * @return the new definition
         */
        public Config withLinkSchemes(List<String> value) {
            return new Config(theme, highlighter, limits, width, indent, value);
        }
    }
}

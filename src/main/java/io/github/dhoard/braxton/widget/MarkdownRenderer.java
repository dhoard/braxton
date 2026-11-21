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
import io.github.dhoard.braxton.internal.markdown.MarkdownParser;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;

/**
 * The projection behind {@link Markdown}: one walk over the parsed document that produces the
 * styled logical lines a pane paints.
 *
 * <p>This class is where Braxton's markdown dialect becomes both style and geometry. It owns the
 * theme application, the width budget - prose is word-wrapped, code is broken at the cell that fits
 * - the prefixes a block quote and a list add, and the bounds {@link Markdown.Limits} declares. It
 * is package-private on purpose: the public surface is {@link Markdown}, and an application never
 * needs a parser node.</p>
 *
 * <p>Nothing here is shared between projections. One projection throws away its own counters and
 * its own accumulated rows when it finishes or when a bound stops it, so a failed parse leaves no
 * state behind for the next one - there is no thread-local, no static buffer and no parser object to
 * reuse.</p>
 */
final class MarkdownRenderer {

    /**
     * The bar a block quote paints in front of every row it holds.
     */
    private static final String QUOTE_BAR = "\u2502";

    /**
     * The bullet a bullet list paints in front of an item that has no task marker.
     */
    private static final String BULLET = "\u2022";

    /**
     * The cells a task list's checkbox occupies, which is {@code [x] } or {@code [ ] }.
     */
    private static final int TASK_MARKER = 4;

    private final MarkdownConfig config;

    private final Markdown.Theme theme;

    private final List<StyledText> lines = new ArrayList<>();

    private final List<Markdown.Diagnostic> diagnostics = new ArrayList<>();

    private int visited;

    private long cells;

    private MarkdownRenderer(MarkdownConfig config) {
        this.config = config;
        this.theme = config.theme();
    }

    /**
     * Projects one source.
     *
     * @param source the markdown source; null is never passed here
     * @param config the definition
     * @return the projection
     */
    static Markdown.Document render(String source, MarkdownConfig config) {
        return new MarkdownRenderer(config).project(source);
    }

    private Markdown.Document project(String source) {
        long bytes = utf8Bytes(source);
        if (bytes > config.limits().inputBytes()) {
            diagnostics.add(
                    new Markdown.Diagnostic.InputTooLarge(bytes, config.limits().inputBytes()));
            return document();
        }
        int width = Math.max(1, config.width());
        try {
            Node root = MarkdownParser.parse(source, config.limits().depth());
            for (StyledText line : blocks(root, width, 0)) {
                emit(line);
            }
        } catch (Limit limit) {
            diagnostics.add(limit.diagnostic);
        }
        return document();
    }

    /**
     * The projection of what this walk accumulated, or of the diagnostics that stopped it.
     *
     * <p>A document over a bound is not published partially: the rows are replaced by one row per
     * diagnostic, styled by the theme, so a reader sees the reason instead of a document that
     * silently stops mid-page. Those rows are the widget's own short strings, so they are not
     * charged to the cell budget that just failed.</p>
     */
    private Markdown.Document document() {
        if (diagnostics.isEmpty()) {
            return new Markdown.Document(lines, List.of());
        }
        List<StyledText> rows = new ArrayList<>(diagnostics.size());
        for (Markdown.Diagnostic diagnostic : diagnostics) {
            rows.add(StyledText.of("markdown: " + diagnostic.message(), theme.diagnostic()));
        }
        return new Markdown.Document(rows, diagnostics);
    }

    /**
     * The source's UTF-8 size, counted without allocating the bytes.
     */
    private static long utf8Bytes(String source) {
        long bytes = 0;
        for (int index = 0; index < source.length(); ) {
            int codePoint = source.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint < 0x80) {
                bytes += 1;
            } else if (codePoint < 0x800) {
                bytes += 2;
            } else if (codePoint < 0x10000) {
                bytes += 3;
            } else {
                bytes += 4;
            }
        }
        return bytes;
    }

    /**
     * Appends one projected line, charging it to the cell budget and splitting any break it holds.
     */
    private void emit(StyledText value) {
        for (StyledText line : value.lines()) {
            // A row costs at least one cell, so the budget bounds the row count as well as the
            // painted width - otherwise a document of empty rows could allocate rows without limit.
            cells += Math.max(1, TextMeasure.columns(line.plainText()));
            if (cells > config.limits().cells()) {
                throw new Limit(new Markdown.Diagnostic.TooManyCells(
                        cells, config.limits().cells()));
            }
            lines.add(line);
        }
    }

    /**
     * Charges one parser node to the node budget.
     */
    private void visit() {
        visited++;
        if (visited > config.limits().nodes()) {
            throw new Limit(new Markdown.Diagnostic.TooManyNodes(
                    visited, config.limits().nodes()));
        }
    }

    /**
     * Refuses a nesting the projection does not descend.
     */
    private void requireDepth(int depth) {
        if (depth > config.limits().depth()) {
            throw new Limit(new Markdown.Diagnostic.NestingTooDeep(
                    depth, config.limits().depth()));
        }
    }

    // ── blocks ───────────────────────────────────────────────────────────────

    /**
     * The rows of a container's children, one blank row between two blocks that read as separate.
     *
     * <p>The one exception is inside a list item: a list that directly follows the paragraph
     * introducing it is not separated, because the list is the rest of that item's block and a
     * blank row there breaks the visual nesting a reader uses to tell the two levels apart. At the
     * document's own level the same pair is separated, which is the blank row a reader expects
     * between a paragraph and the list after it.</p>
     */
    private List<StyledText> blocks(Node parent, int width, int depth) {
        requireDepth(depth);
        List<StyledText> out = new ArrayList<>();
        Node previous = null;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            visit();
            List<StyledText> rendered = block(child, width, depth + 1);
            if (rendered.isEmpty()) {
                continue;
            }
            if (previous != null && separated(parent, previous, child)) {
                out.add(StyledText.empty());
            }
            out.addAll(rendered);
            previous = child;
        }
        return out;
    }

    private static boolean separated(Node parent, Node previous, Node next) {
        return !(parent instanceof ListItem && previous instanceof Paragraph && next instanceof ListBlock);
    }

    private List<StyledText> block(Node node, int width, int depth) {
        requireDepth(depth);
        return switch (node) {
            case Heading heading -> heading(heading, width, depth);
            case Paragraph paragraph -> paragraph(paragraph, width, depth);
            case ThematicBreak ignored -> List.of(StyledText.of("\u2500".repeat(width), theme.rule()));
            case BlockQuote quote -> quote(quote, width, depth);
            case ListBlock list -> list(list, width, depth);
            case FencedCodeBlock fence -> code(fence.getLiteral(), fence.getInfo(), width);
            case IndentedCodeBlock code -> code(code.getLiteral(), "", width);
            case HtmlBlock html -> wordWrap(StyledText.of(html.getLiteral().strip(), theme.muted()), width);
            case TableBlock table -> table(table, width, depth);
            default -> blocks(node, width, depth);
        };
    }

    /**
     * A paragraph: its inline content word-wrapped, or the rows an image hook answers for a
     * paragraph that holds nothing but an image.
     *
     * <p>The hook is asked only when the image stands alone, so the rows it answers replace one
     * block row for row without moving text that shares the paragraph. A null answer paints the alt
     * text the inline walk produced, which is what a renderer without a hook paints for every
     * image.</p>
     */
    private List<StyledText> paragraph(Paragraph paragraph, int width, int depth) {
        Markdown.ImageHook hook = config.imageHook();
        Node first = paragraph.getFirstChild();
        if (hook != null && first instanceof Image image && first.getNext() == null) {
            visit();
            StyledText alt = inline(image, theme.muted(), null, depth + 1);
            List<StyledText> rows = hook.rows(
                    new Markdown.ImageRef(image.getDestination(), alt.plainText(), image.getTitle()),
                    Math.max(1, width));
            if (rows != null) {
                List<StyledText> out = new ArrayList<>();
                for (StyledText row : rows) {
                    if (row == null) {
                        continue;
                    }
                    for (StyledText piece : row.lines()) {
                        out.addAll(hardWrap(piece, width));
                    }
                }
                return out;
            }
        }
        return wordWrap(inline(paragraph, theme.text(), null, depth), width);
    }

    private List<StyledText> heading(Heading heading, int width, int depth) {
        int level = Math.max(1, Math.min(heading.getLevel(), 6));
        return wordWrap(inline(heading, theme.heading(level), null, depth), width);
    }

    /**
     * A block quote: a bar in front of every row of its content, the content itself in the quote
     * style.
     *
     * <p>The bar and its padding occupy one nesting level, so a quote inside a list stays aligned
     * with the list's content and a nested quote steps in again.</p>
     */
    private List<StyledText> quote(BlockQuote quote, int width, int depth) {
        int bar = Math.max(2, config.indent());
        List<StyledText> body = blocks(quote, Math.max(1, width - bar), depth + 1);
        if (body.isEmpty()) {
            return List.of(StyledText.of(QUOTE_BAR, theme.muted()));
        }
        StyledText prefix = StyledText.of(QUOTE_BAR + " ".repeat(bar - 1), theme.muted());
        List<StyledText> out = new ArrayList<>(body.size());
        for (StyledText line : body) {
            out.add(StyledText.concat(prefix, restyle(line, theme.quote())));
        }
        return out;
    }

    /**
     * A list: one marker per item and its content aligned under the marker's end.
     *
     * <p>The marker column is decided for the whole list, not per item, so a list numbered past nine
     * keeps its content in one column and an item with a task box or a bullet stays aligned with the
     * numbered items beside it. A tight list puts its items in consecutive rows and a loose one puts
     * a blank row between them.</p>
     */
    private List<StyledText> list(ListBlock list, int width, int depth) {
        int markerWidth = markerWidth(list);
        int number = startNumber(list);
        boolean loose = !list.isTight();
        List<StyledText> out = new ArrayList<>();
        boolean first = true;
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem item)) {
                continue;
            }
            visit();
            requireDepth(depth + 1);
            if (!first && loose) {
                out.add(StyledText.empty());
            }
            first = false;
            StyledText marker = marker(item, list, number, markerWidth);
            if (list instanceof OrderedList) {
                number++;
            }
            List<StyledText> body = blocks(item, Math.max(1, width - markerWidth), depth + 1);
            if (body.isEmpty()) {
                body = List.of(StyledText.empty());
            }
            StyledText rest = StyledText.of(" ".repeat(markerWidth), theme.marker());
            for (int index = 0; index < body.size(); index++) {
                out.add(StyledText.concat(index == 0 ? marker : rest, body.get(index)));
            }
        }
        return out;
    }

    /**
     * The cells a list's marker column occupies, the same for every item in the list.
     */
    private int markerWidth(ListBlock list) {
        int width = Math.max(2, config.indent());
        boolean task = false;
        int items = 0;
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem item)) {
                continue;
            }
            items++;
            task |= item.getFirstChild() instanceof TaskListItemMarker;
        }
        if (list instanceof OrderedList) {
            int last = startNumber(list) + Math.max(0, items - 1);
            width = Math.max(width, String.valueOf(last).length() + 2);
        }
        if (task) {
            width = Math.max(width, TASK_MARKER);
        }
        return width;
    }

    /**
     * The marker one item paints, padded to the list's marker column.
     */
    private StyledText marker(ListItem item, ListBlock list, int number, int markerWidth) {
        String text;
        if (item.getFirstChild() instanceof TaskListItemMarker task) {
            text = task.isChecked() ? "[x]" : "[ ]";
        } else if (list instanceof OrderedList) {
            text = number + ".";
        } else {
            text = BULLET;
        }
        int pad = Math.max(1, markerWidth - TextMeasure.columns(text));
        return StyledText.of(text + " ".repeat(pad), theme.marker());
    }

    /**
     * The number an ordered list starts at.
     */
    private static int startNumber(ListBlock list) {
        if (list instanceof OrderedList ordered) {
            Integer start = ordered.getMarkerStartNumber();
            return start == null ? 1 : Math.max(0, start);
        }
        return 0;
    }

    /**
     * A GFM table: framed cells with the alignment its delimiter row declared.
     *
     * <p>{@link Table} decides the column widths - a markdown table has no widths of its own - and
     * the projection's width budget truncates a column that does not fit rather than wrapping a
     * cell, because a wrapped cell would break the grid a reader scans. A cell's content is one row:
     * a line break inside it paints as a space.</p>
     */
    private List<StyledText> table(TableBlock block, int width, int depth) {
        List<List<StyledText>> rows = new ArrayList<>();
        List<Table.Alignment> alignments = new ArrayList<>();
        for (Node section = block.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) {
                    continue;
                }
                visit();
                List<StyledText> cells = new ArrayList<>();
                boolean headerRow = rows.isEmpty();
                int column = 0;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (!(cell instanceof TableCell tableCell)) {
                        continue;
                    }
                    visit();
                    Style base = tableCell.isHeader() ? theme.text().merge(theme.header()) : theme.text();
                    // One space before the cell is the table's inner padding: Table's own buffer is
                    // the space after it, so a framed markdown cell is padded on both sides.
                    cells.add(StyledText.concat(
                            StyledText.of(" ", Style.NONE), singleRow(inline(tableCell, base, null, depth + 1))));
                    if (headerRow) {
                        alignments.add(alignment(tableCell));
                    }
                    column++;
                }
                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            }
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        Table table = Table.of()
                .border(Border.SINGLE)
                .borderStyle(theme.border())
                .buffer(1)
                .width(width)
                .alignments(alignments);
        for (List<StyledText> row : rows) {
            table.row(row);
        }
        return table.render().lines();
    }

    /**
     * The alignment one GFM cell declares.
     */
    private static Table.Alignment alignment(TableCell cell) {
        TableCell.Alignment alignment = cell.getAlignment();
        if (alignment == null) {
            return Table.Alignment.LEFT;
        }
        return switch (alignment) {
            case LEFT -> Table.Alignment.LEFT;
            case CENTER -> Table.Alignment.CENTER;
            case RIGHT -> Table.Alignment.RIGHT;
        };
    }

    /**
     * A code block: its lines highlighted and merged over the theme's code-block style, each broken
     * at the cell that fits so no projected row is wider than the projection.
     */
    private List<StyledText> code(String literal, String info, int width) {
        String language = CodeHighlighter.language(info);
        StyledText highlighted = config.highlighter()
                .highlight(language, literal == null ? "" : literal)
                .stripTrailingNewlines();
        List<StyledText> out = new ArrayList<>();
        for (StyledText line : highlighted.lines()) {
            out.addAll(hardWrap(restyle(line, theme.codeBlock()), width));
        }
        return out.isEmpty() ? List.of(StyledText.empty()) : out;
    }

    // ── inlines ──────────────────────────────────────────────────────────────

    /**
     * The styled value one inline container paints.
     *
     * <p>A hard break is a line break in the value, so the paragraph that holds it is wrapped into
     * separate rows; a soft break is a space, which is where a source line ends and the sentence does
     * not. Raw inline HTML is literal text and an image is its alt text: neither can smuggle a
     * terminal escape sequence, and neither is resolved.</p>
     */
    private StyledText inline(Node parent, Style base, String link, int depth) {
        requireDepth(depth);
        StyledText out = StyledText.empty();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            visit();
            out = out.append(inlineNode(child, base, link, depth + 1));
        }
        return out;
    }

    private StyledText inlineNode(Node node, Style base, String link, int depth) {
        requireDepth(depth);
        return switch (node) {
            case Text text -> StyledText.of(inlineText(text.getLiteral()), base, link);
            case Code code -> StyledText.of(inlineText(code.getLiteral()), base.merge(theme.code()), null);
            case Emphasis emphasis -> inline(emphasis, base.merge(theme.emphasis()), link, depth);
            case StrongEmphasis strong -> inline(strong, base.merge(theme.strong()), link, depth);
            case Strikethrough struck -> inline(struck, base.merge(theme.strikethrough()), link, depth);
            case Link target -> {
                String destination = safeTarget(target.getDestination());
                yield inline(target, base.merge(theme.link()), destination == null ? link : destination, depth);
            }
            case Image image -> inlineImage(image, base.merge(theme.muted()), depth);
            case SoftLineBreak ignored -> StyledText.of(" ", base);
            case HardLineBreak ignored -> StyledText.of("\n", base);
            case HtmlInline html -> StyledText.of(inlineText(html.getLiteral()), base.merge(theme.muted()), link);
            case TaskListItemMarker ignored -> StyledText.empty();
            default -> inline(node, base, link, depth);
        };
    }

    /**
     * The value one image paints where it stands: the hook's inline answer when it has one, and the
     * alt text otherwise.
     *
     * <p>The alt text is the image's own inline content, so a nested emphasis or code span in the
     * alt text paints the way it would anywhere else.</p>
     */
    private StyledText inlineImage(Image image, Style style, int depth) {
        StyledText alt = inline(image, style, null, depth);
        Markdown.ImageHook hook = config.imageHook();
        if (hook == null) {
            return alt;
        }
        StyledText replacement =
                hook.inline(new Markdown.ImageRef(image.getDestination(), alt.plainText(), image.getTitle()), style);
        return replacement == null ? alt : replacement;
    }

    /**
     * Inline text with its tabs taken out, so a projected row's cells are the cells it measures.
     */
    private static String inlineText(String value) {
        return value == null ? "" : value.replace('\t', ' ');
    }

    /**
     * The hyperlink target a destination may carry.
     *
     * <p>Only a scheme the configuration allows keeps a target; a relative destination, an unknown
     * scheme and a value holding a control character are all dropped, and the link's text paints as
     * styled text with no target at all. The renderer never resolves the destination, so an allowed
     * one is passed to the terminal unchanged.</p>
     */
    private String safeTarget(String destination) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        for (int index = 0; index < destination.length(); index++) {
            if (Character.isISOControl(destination.charAt(index))) {
                return null;
            }
        }
        int colon = destination.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String scheme = destination.substring(0, colon).toLowerCase(Locale.ROOT);
        for (int index = 0; index < scheme.length(); index++) {
            char value = scheme.charAt(index);
            boolean allowed = (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '+'
                    || value == '-'
                    || value == '.';
            if (!allowed) {
                return null;
            }
        }
        return config.linkSchemes().contains(scheme) ? destination : null;
    }

    // ── geometry ─────────────────────────────────────────────────────────────

    /**
     * One grapheme cluster of a projected value: where it starts, ends and how wide it paints.
     */
    private record Cluster(int start, int end, int columns) {}

    /**
     * The clusters of one line, measured the way the renderer paints them.
     */
    private static List<Cluster> clusters(String plain) {
        List<Cluster> clusters = new ArrayList<>();
        int offset = 0;
        for (String cluster : TextMeasure.graphemes(plain)) {
            int end = offset + cluster.length();
            clusters.add(new Cluster(offset, end, TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED)));
            offset = end;
        }
        return clusters;
    }

    /**
     * Word-wraps a value into rows no wider than {@code width}.
     *
     * <p>A row ends at the last space that fits, so a phrase stays whole; a single word wider than
     * the row is broken at the cell that fits, which is the same rule the layout engine wraps by.
     * Spaces at a row's start and end are dropped, and a line break in the value starts a new row
     * whatever width is left, because a hard break is the author's.</p>
     */
    private static List<StyledText> wordWrap(StyledText value, int width) {
        int limit = Math.max(1, width);
        List<StyledText> out = new ArrayList<>();
        for (StyledText segment : value.lines()) {
            out.addAll(wrapSegment(segment, limit));
        }
        return out.isEmpty() ? List.of(StyledText.empty()) : out;
    }

    private static List<StyledText> wrapSegment(StyledText segment, int limit) {
        String plain = segment.plainText();
        List<Cluster> clusters = clusters(plain);
        List<StyledText> out = new ArrayList<>();
        int index = 0;
        while (true) {
            while (index < clusters.size() && plain.charAt(clusters.get(index).start()) == ' ') {
                index++;
            }
            if (index >= clusters.size()) {
                if (out.isEmpty()) {
                    out.add(StyledText.empty());
                }
                return out;
            }
            int cells = 0;
            int breakAt = -1;
            int position = index;
            while (position < clusters.size()) {
                Cluster cluster = clusters.get(position);
                if (cells > 0 && plain.charAt(cluster.start()) == ' ') {
                    breakAt = position;
                }
                if (cells > 0 && cells + cluster.columns() > limit) {
                    break;
                }
                cells += cluster.columns();
                position++;
            }
            int endIndex;
            int next;
            if (position >= clusters.size()) {
                endIndex = clusters.size();
                next = clusters.size();
            } else if (breakAt > index) {
                endIndex = breakAt;
                next = breakAt + 1;
            } else {
                endIndex = position;
                next = position;
            }
            if (endIndex <= index) {
                endIndex = index + 1;
            }
            int end = endIndex >= clusters.size()
                    ? plain.length()
                    : clusters.get(endIndex).start();
            out.add(segment.slice(clusters.get(index).start(), end));
            index = Math.max(next, index + 1);
        }
    }

    /**
     * Breaks a value into rows no wider than {@code width}, without preferring a space.
     *
     * <p>A code line has no wrapping points of its own: breaking it at a space would change what the
     * source says, so it is broken at the cell that fits and its indentation is kept as written.</p>
     */
    private static List<StyledText> hardWrap(StyledText value, int width) {
        int limit = Math.max(1, width);
        String plain = value.plainText();
        List<Cluster> clusters = clusters(plain);
        List<StyledText> out = new ArrayList<>();
        int index = 0;
        while (index < clusters.size()) {
            int cells = 0;
            int position = index;
            while (position < clusters.size()) {
                int next = cells + clusters.get(position).columns();
                if (cells > 0 && next > limit) {
                    break;
                }
                cells = next;
                position++;
            }
            int end = position >= clusters.size()
                    ? plain.length()
                    : clusters.get(position).start();
            out.add(value.slice(clusters.get(index).start(), end));
            index = Math.max(position, index + 1);
        }
        return out.isEmpty() ? List.of(StyledText.empty()) : out;
    }

    /**
     * The same content with one style merged over every span, keeping each span's own style.
     */
    private static StyledText restyle(StyledText value, Style style) {
        if (style.isPlain()) {
            return value;
        }
        StyledText out = StyledText.empty();
        for (StyledText.Span span : value.spans()) {
            out = out.append(StyledText.of(span.text(), span.style().merge(style), span.link()));
        }
        return out;
    }

    /**
     * The value flattened to one row, because a grid cell cannot hold a line break.
     */
    private static StyledText singleRow(StyledText value) {
        String plain = value.plainText();
        if (plain.indexOf('\n') < 0 && plain.indexOf('\t') < 0) {
            return value;
        }
        StyledText out = StyledText.empty();
        for (StyledText.Span span : value.spans()) {
            out = out.append(
                    StyledText.of(span.text().replace('\t', ' ').replace('\n', ' '), span.style(), span.link()));
        }
        return out;
    }

    /**
     * A bound was crossed while this projection ran.
     *
     * <p>It is an exception because the walk has to stop wherever it is - inside a nested list, an
     * inline run or a table cell - and because the diagnostic it carries is the whole answer: no
     * partially projected row survives it. Stack traces are off, so crossing a bound costs what a
     * return costs.</p>
     */
    private static final class Limit extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final transient Markdown.Diagnostic diagnostic;

        private Limit(Markdown.Diagnostic diagnostic) {
            super(diagnostic.message(), null, false, false);
            this.diagnostic = diagnostic;
        }
    }
}

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

package io.github.dhoard.braxton.internal.markdown;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

/**
 * The one place Braxton's markdown dialect is defined, and the only file that decides which
 * third-party parser implementation is used.
 *
 * <p>The dialect is CommonMark plus the officially maintained GFM extensions: tables,
 * strikethrough, task list items and autolinks. The parser's own AST types never leave this package;
 * {@code widget.Markdown} renders from the node tree this class returns and publishes
 * {@code StyledText} values, so no parser type is part of Braxton's public API and no parser object
 * is reachable from a widget or a model.</p>
 *
 * <p>Two of the parser's own bounds are wired to the caller's nesting limit, so a pathological
 * document is bounded while it is parsed rather than after an unbounded tree exists: a document
 * nested deeper than that limit stops opening new blocks and new inline containers. The input byte
 * limit belongs to the caller, which refuses to hand a value to the parser at all when it is over
 * its budget, so the tree the parser builds is bounded by the bytes it was given either way.</p>
 *
 * <p>The parser is not thread-safe, so a fresh one is built per parse. Extensions are stateless and
 * shared; building a parser is a handful of list copies next to the parse that follows.</p>
 */
public final class MarkdownParser {

    /**
     * Every extension Braxton's dialect enables, in parser order.
     */
    private static final List<Extension> EXTENSIONS = List.of(
            AutolinkExtension.create(),
            StrikethroughExtension.create(),
            TablesExtension.create(),
            TaskListItemsExtension.create());

    private MarkdownParser() {}

    /**
     * Parses one markdown document into the parser's own tree.
     *
     * @param source the document; null is empty
     * @param maxNesting the deepest block and inline nesting the parser may open, at least one
     * @return the parsed document node, never null
     */
    public static Node parse(String source, int maxNesting) {
        int nesting = Math.max(1, maxNesting);
        Parser parser = Parser.builder()
                .extensions(EXTENSIONS)
                .maxOpenBlockParsers(nesting)
                .maxInlineNesting(nesting)
                .build();
        return parser.parse(source == null ? "" : source);
    }
}

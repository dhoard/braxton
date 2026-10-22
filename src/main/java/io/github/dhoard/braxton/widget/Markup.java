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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/**
 * Renders XML-style rich text into {@link StyledText}.
 *
 * <p>Supported tags: {@code span}, {@code p}, {@code a}, {@code ul}/{@code li}, {@code table}/
 * {@code tr}/{@code td}, and {@code br}. Attributes are {@code color}, {@code bg-color}, and a
 * space-separated {@code style} list. The document's own root element is rendered, so a document
 * may be a list, a table, or a paragraph without an extra wrapper. Parsing never resolves external entities, DTDs, or network
 * resources; malformed markup fails with a diagnostic instead of rendering partial output.</p>
 *
 * <p>The input is a well-formed XML document, not HTML: it has exactly one root element and an
 * element that encloses no content is closed explicitly, so a line break is written {@code <br/>}
 * and a bare {@code <br>} is rejected like any other malformed document.</p>
 *
 * <p>A list {@code icon} is a one-line prefix, so a line break in it becomes a space and a tab
 * advances to the same four-column stops the text view paints; continuation lines therefore stay
 * under the first line's item text.</p>
 *
 * <p>A table is a rectangular grid: every row occupies the same number of columns. A row is painted
 * as one grapheme-cluster sequence, so each cell's columns are charged inside that sequence rather
 * than to the cell measured on its own - a cluster with no base character of its own at the head of
 * a cell joins the pad of the cell before it instead of occupying a cell of its own.</p>
 */
public final class Markup {

    private static final String DEFAULT_BULLET = "\u2022";

    private static final ErrorHandler QUIET = new ErrorHandler() {
        @Override
        public void warning(SAXParseException exception) {
            // Non-fatal: parsing continues and the caller sees the resulting document.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };
    private static final int DEFAULT_GAP = 2;

    private Markup() {
    }

    /** Parses and renders markup; throws {@link IllegalArgumentException} for invalid input. */
    public static StyledText parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("markup must not be blank");
        }
        Element root = document(xml).getDocumentElement();
        if (root == null) {
            throw new IllegalArgumentException("markup requires a single root element");
        }
        // The root is the caller's outermost element, not a wrapper: rendering its children
        // instead would drop a root list's bullets, a root table's columns, a root paragraph's
        // attributes and a root link's href. A <root> or <span> wrapper still renders exactly as
        // before, because those tags already render their children.
        return renderNode(root, Style.NONE, null).stripTrailingNewlines();
    }

    private static Document document(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            // JAXP's default handler prints "[Fatal Error] ..." to stderr before rethrowing. Callers
            // already receive a diagnostic through IllegalArgumentException, so reporting is routed
            // exclusively through the exception and stderr stays clean.
            builder.setErrorHandler(QUIET);
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception failure) {
            throw new IllegalArgumentException("invalid markup: " + failure.getMessage(), failure);
        }
    }

    private static StyledText renderChildren(Node parent, Style inherited, String link) {
        StyledText out = StyledText.empty();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (startsOwnLine(child, out)) {
                out = out.append(StyledText.plain("\n"));
            }
            out = out.append(renderNode(child, inherited, link));
        }
        return out;
    }

    /** True when a block child must break the line of the content already rendered before it. */
    private static boolean startsOwnLine(Node node, StyledText rendered) {
        if (node == null || node.getNodeType() != Node.ELEMENT_NODE || rendered.isEmpty()) {
            return false;
        }
        String tag = ((Element) node).getTagName().toLowerCase(java.util.Locale.ROOT);
        if (!"p".equals(tag) && !"ul".equals(tag) && !"table".equals(tag)) {
            return false;
        }
        String plain = rendered.plainText();
        if (plain.endsWith("\n")) {
            return false;
        }
        return !plain.substring(plain.lastIndexOf('\n') + 1).isBlank();
    }

    private static StyledText renderNode(Node node, Style inherited, String link) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            return StyledText.of(node.getNodeValue(), inherited, link);
        }
        if (node.getNodeType() != Node.ELEMENT_NODE) {
            return StyledText.empty();
        }
        Element element = (Element) node;
        String tag = element.getTagName().toLowerCase(java.util.Locale.ROOT);
        Style style = applyAttributes(element, inherited);
        String target = element.getAttribute("href");
        String effectiveLink = target == null || target.isBlank() ? link : target;
        return switch (tag) {
            case "span", "a" -> renderChildren(element, style, effectiveLink);
            case "p" -> renderChildren(element, style, effectiveLink).append("\n");
            case "br" -> StyledText.plain("\n");
            case "ul" -> renderList(element, style, effectiveLink);
            case "table" -> renderTable(element, style, effectiveLink);
            case "tr", "td", "li", "root" -> renderChildren(element, style, effectiveLink);
            default -> renderChildren(element, style, effectiveLink);
        };
    }

    private static Style applyAttributes(Element element, Style inherited) {
        Style style = inherited;
        Color foreground = Color.parse(element.getAttribute("color")).orElse(null);
        if (foreground != null) {
            style = style.withForeground(foreground);
        }
        Color background = Color.parse(element.getAttribute("bg-color")).orElse(null);
        if (background != null) {
            style = style.withBackground(background);
        }
        String attributes = element.getAttribute("style");
        if (!attributes.isBlank()) {
            boolean reset = false;
            for (String token : attributes.trim().split("\\s+")) {
                switch (token.toLowerCase(java.util.Locale.ROOT)) {
                    case "bold" -> style = style.withBold();
                    case "italic" -> style = style.withItalic();
                    case "underline" -> style = style.withUnderline();
                    case "strikethrough" -> style = style.withStrikethrough();
                    case "invert" -> style = style.withInvert();
                    case "hidden" -> style = style.withHidden();
                    case "regular" -> reset = true;
                    default -> {
                        // Unknown style tokens are ignored; the text still renders.
                    }
                }
            }
            if (reset) {
                style = new Style(style.foreground(), style.background(), false, false, false,
                        false, false, false);
            }
        }
        return style;
    }

    private static StyledText renderList(Element list, Style style, String link) {
        String bullet = list.hasAttribute("icon") && !list.getAttribute("icon").isBlank()
                ? list.getAttribute("icon") : DEFAULT_BULLET;
        int gap = parseInt(list.getAttribute("gap"), DEFAULT_GAP);
        // The bullet prefixes one line, so it is normalized the way every other one-line
        // decoration is: a line break becomes a space and a tab advances to the same four-column
        // stops the text view paints. Measuring the raw icon instead (a tab and a break are one
        // column to TextMeasure.columns) hung continuation lines under a column the painted
        // prefix never reaches, or split the item across rows.
        StyledText prefix = TabStops.expand(StyledText.of(
                " ".repeat(Math.max(0, gap)) + singleLine(bullet) + " ", style, link));
        String continuation = " ".repeat(TextMeasure.columns(prefix.plainText()));
        StyledText out = StyledText.empty();
        NodeList children = list.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element item)
                    || !"li".equalsIgnoreCase(item.getTagName())) {
                continue;
            }
            StyledText content = renderChildren(item, style, link);
            StyledText rendered = prefix
                    .append(indentAfterFirstLine(content, continuation, style, link));
            out = out.append(rendered);
            if (!rendered.plainText().endsWith("\n")) {
                out = out.append(StyledText.of("\n", style, link));
            }
        }
        return out;
    }

    /** A list bullet prefixes one line, so CRLF, CR and LF each become one space. */
    private static String singleLine(String value) {
        return LineBreaks.singleRow(value);
    }

    /** Aligns every line after the first under the first line's text, keeping span styling. */
    private static StyledText indentAfterFirstLine(StyledText content, String indent, Style style,
            String link) {
        List<StyledText> lines = content.lines();
        StyledText out = StyledText.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(StyledText.of("\n", style, link));
                if (!lines.get(i).isEmpty()) {
                    out = out.append(StyledText.of(indent, style, link));
                }
            }
            out = out.append(lines.get(i));
        }
        return out;
    }

    private static StyledText renderTable(Element table, Style style, String link) {
        int gap = parseInt(table.getAttribute("gap"), DEFAULT_GAP);
        List<List<StyledText>> rows = new ArrayList<>();
        NodeList children = table.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element row
                    && "tr".equalsIgnoreCase(row.getTagName())) {
                List<StyledText> cells = new ArrayList<>();
                NodeList rowChildren = row.getChildNodes();
                for (int j = 0; j < rowChildren.getLength(); j++) {
                    if (rowChildren.item(j) instanceof Element cell
                            && "td".equalsIgnoreCase(cell.getTagName())) {
                        cells.add(renderChildren(cell, style, link));
                    }
                }
                rows.add(cells);
            }
        }
        int columns = 0;
        for (List<StyledText> row : rows) {
            columns = Math.max(columns, row.size());
        }
        int[] widths = new int[columns];
        for (List<StyledText> row : rows) {
            for (int column = 0; column < row.size(); column++) {
                String plain = TabStops.expand(row.get(column)).plainText();
                int width = 0;
                for (String line : plain.split("\n", -1)) {
                    width = Math.max(width, TextMeasure.columns(line));
                }
                widths[column] = Math.max(widths[column], width);
            }
        }
        List<StyledText> renderedRows = new ArrayList<>();
        for (List<StyledText> row : rows) {
            List<List<StyledText>> cellLines = new ArrayList<>(columns);
            int height = 1;
            for (int column = 0; column < columns; column++) {
                StyledText cell = column < row.size()
                        ? TabStops.expand(row.get(column)) : StyledText.empty();
                List<StyledText> lines = cell.lines();
                cellLines.add(lines);
                height = Math.max(height, lines.size());
            }
            for (int line = 0; line < height; line++) {
                StyledText rendered = StyledText.empty();
                // The row is painted as one cluster sequence, so the cell is charged inside the row:
                // a cluster with no base of its own at the head of the cell joins the gap of the cell
                // before it and takes no column of its own, while a variation selector there widens
                // that gap by a column. Padding from the cell measured alone makes the rows of one
                // table different widths.
                String painted = "";
                for (int column = 0; column < columns; column++) {
                    List<StyledText> lines = cellLines.get(column);
                    StyledText value = line < lines.size() ? lines.get(line) : StyledText.empty();
                    String plain = value.plainText();
                    int pad = Math.max(gap, GridRows.pad(painted, plain, widths[column]) + gap);
                    painted = painted + plain + " ".repeat(pad);
                    rendered = rendered.append(value)
                            .append(StyledText.of(" ".repeat(pad), style));
                }
                renderedRows.add(rendered);
            }
        }
        StyledText out = StyledText.empty();
        for (StyledText row : GridRows.shareOneWidth(renderedRows)) {
            out = out.append(row).append("\n");
        }
        return out;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? fallback : parsed;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /** A one-element view of the rendered markup. */
    public static View<Object> view(String xml) {
        return Views.text(parse(xml));
    }
}

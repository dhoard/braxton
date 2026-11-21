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

package io.github.dhoard.braxton.style;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An owned, line-aware stylesheet: type, class, id, state and descendant selectors.
 */
public final class StyleSheet {

    /**
     * One element as seen by selector matching.
     *
     * @param type the input event kind
     * @param classes the element's stylesheet classes
     * @param id the local identity of the resource
     * @param states the element's active pseudo-states
     */
    public record Element(String type, Set<String> classes, String id, Set<String> states) {
        /**
         * Creates a {@code Element} value.
         *
         * @param type the input event kind
         * @param classes the element's stylesheet classes
         * @param id the local identity of the resource
         * @param states the element's active pseudo-states
         */
        public Element {
            classes = Set.copyOf(classes);
            states = Set.copyOf(states);
        }
    }

    private record Part(String type, Set<String> classes, String id, String state) {}

    private record Rule(List<Part> descendants, Style style) {}

    private final List<Rule> rules;

    private StyleSheet(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Parses a stylesheet; malformed input reports its one-based line and column.
     *
     * @param source the stylesheet source text
     * @return the parsed immutable stylesheet
     */
    public static StyleSheet parse(String source) {
        if (source == null) throw new IllegalArgumentException("source is required");
        List<Rule> rules = new ArrayList<>();
        int at = 0;
        int line = 1;
        int column = 1;
        while (at < source.length()) {
            char c = source.charAt(at);
            if (Character.isWhitespace(c)) {
                int start = at;
                while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++;
                Position p = advance(source, start, at, line, column);
                line = p.line;
                column = p.column;
                continue;
            }
            if (c == '/' && at + 1 < source.length() && source.charAt(at + 1) == '*') {
                int end = source.indexOf("*/", at + 2);
                if (end < 0) throw new StylesheetException("unterminated comment", line, column);
                Position p = advance(source, at, end + 2, line, column);
                at = end + 2;
                line = p.line;
                column = p.column;
                continue;
            }
            int selectorLine = line;
            int selectorColumn = column;
            int brace = source.indexOf('{', at);
            if (brace < 0) throw new StylesheetException("missing '{'", selectorLine, selectorColumn);
            String selector = source.substring(at, brace).trim();
            List<Part> parts = parseSelector(selector, selectorLine, selectorColumn);
            Position afterBrace = advance(source, at, brace + 1, line, column);
            at = brace + 1;
            line = afterBrace.line;
            column = afterBrace.column;
            Style style = Style.NONE;
            while (at < source.length() && source.charAt(at) != '}') {
                int declarationLine = line;
                int declarationColumn = column;
                int semi = source.indexOf(';', at);
                int close = source.indexOf('}', at);
                if (close < 0) throw new StylesheetException("missing '}'", declarationLine, declarationColumn);
                if (semi < 0 || semi > close) semi = close;
                boolean endsBlock = semi == close;
                String declaration = source.substring(at, semi).trim();
                if (!declaration.isEmpty()) style = apply(style, declaration, declarationLine, declarationColumn);
                int nextAt = endsBlock ? semi : semi + 1;
                Position next = advance(source, at, nextAt, line, column);
                at = nextAt;
                line = next.line;
                column = next.column;
                if (endsBlock) break;
            }
            if (at >= source.length()) throw new StylesheetException("missing '}'", selectorLine, selectorColumn);
            rules.add(new Rule(parts, style));
            Position after = advance(source, at, at + 1, line, column);
            at++;
            line = after.line;
            column = after.column;
        }
        return new StyleSheet(rules);
    }

    /**
     * Applies widget default, theme token, matching stylesheet rules and explicit style in that order.
     *
     * @param ancestors the ancestor elements used for selector matching
     * @param element the root element
     * @param explicit the explicit application style
     * @param themeStyle the resolved theme token style
     * @param widgetDefault the widget's default style
     * @return the resolved style after cascading all applicable rules
     */
    public Style resolve(
            Element element, List<Element> ancestors, Style widgetDefault, Style themeStyle, Style explicit) {
        Style result = widgetDefault == null ? Style.NONE : widgetDefault;
        if (themeStyle != null) result = result.merge(themeStyle);
        for (Rule rule : rules)
            if (matches(rule.descendants(), element, ancestors)) result = result.merge(rule.style());
        return explicit == null ? result : result.merge(explicit);
    }

    private static boolean matches(List<Part> parts, Element element, List<Element> ancestors) {
        if (parts.isEmpty()) return false;
        if (!matchesPart(parts.get(parts.size() - 1), element)) return false;
        int ancestor = ancestors.size() - 1;
        for (int i = parts.size() - 2; i >= 0; i--) {
            boolean found = false;
            while (ancestor >= 0) {
                if (matchesPart(parts.get(i), ancestors.get(ancestor--))) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static boolean matchesPart(Part part, Element element) {
        if (!part.type().isEmpty() && !part.type().equals(element.type())) return false;
        if (!part.id().isEmpty() && !part.id().equals(element.id())) return false;
        if (!part.state().isEmpty() && !element.states().contains(part.state())) return false;
        return element.classes().containsAll(part.classes());
    }

    private static List<Part> parseSelector(String selector, int line, int column) {
        List<Part> parts = new ArrayList<>();
        for (String compound : selector.split("\\s+")) {
            if (compound.isEmpty()) continue;
            int i = 0;
            StringBuilder type = new StringBuilder();
            while (i < compound.length() && isName(compound.charAt(i))) type.append(compound.charAt(i++));
            Set<String> classes = new LinkedHashSet<>();
            String id = "";
            String state = "";
            while (i < compound.length()) {
                char c = compound.charAt(i++);
                if (c != '.' && c != '#' && c != ':') throw new StylesheetException("bad selector", line, column);
                StringBuilder name = new StringBuilder();
                while (i < compound.length() && isName(compound.charAt(i))) name.append(compound.charAt(i++));
                if (name.isEmpty()) throw new StylesheetException("empty selector name", line, column);
                if (c == '.') classes.add(name.toString());
                else if (c == '#') id = name.toString();
                else state = name.toString();
            }
            parts.add(new Part(type.toString(), classes, id, state));
        }
        if (parts.isEmpty()) throw new StylesheetException("empty selector", line, column);
        return parts;
    }

    private static boolean isName(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    private static Style apply(Style style, String declaration, int line, int column) {
        int colon = declaration.indexOf(':');
        if (colon <= 0) throw new StylesheetException("missing ':' in declaration", line, column);
        String property = declaration.substring(0, colon).trim();
        String value = declaration.substring(colon + 1).trim();
        return switch (property) {
            case "color" ->
                Color.parse(value)
                        .map(color -> style.withForeground(color))
                        .orElseThrow(() -> new StylesheetException("bad color", line, column));
            case "background" ->
                Color.parse(value)
                        .map(color -> style.withBackground(color))
                        .orElseThrow(() -> new StylesheetException("bad background", line, column));
            case "bold" -> Boolean.parseBoolean(value) ? style.withBold() : style;
            case "italic" -> Boolean.parseBoolean(value) ? style.withItalic() : style;
            case "underline" -> Boolean.parseBoolean(value) ? style.withUnderline() : style;
            case "strikethrough" -> Boolean.parseBoolean(value) ? style.withStrikethrough() : style;
            case "invert" -> Boolean.parseBoolean(value) ? style.withInvert() : style;
            default -> throw new StylesheetException("unknown property: " + property, line, column);
        };
    }

    private record Position(int line, int column) {}

    private static Position advance(String source, int from, int to, int line, int column) {
        for (int i = from; i < to && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                column = 1;
            } else column++;
        }
        return new Position(line, column);
    }
}

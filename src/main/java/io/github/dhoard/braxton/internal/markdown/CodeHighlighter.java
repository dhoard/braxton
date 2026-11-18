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

import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import java.util.Locale;
import java.util.Set;

/**
 * The tokenizers behind {@code Markdown.Highlighter}: a pure, allocation-bounded split of one code
 * block into styled token spans.
 *
 * <p>Three dialects are recognised - Java, JSON and a POSIX shell - and every other language falls
 * back to plain text, so an unknown fence is painted exactly as it was written. Nothing here reads a
 * file, runs a program or emits terminal escapes: a tokenizer walks the source once and answers
 * {@link StyledText} spans carrying foreground colours only, which the markdown renderer merges over
 * the theme's code-block style. That is what keeps a highlight palette usable under either theme
 * without a second parsing pass.</p>
 *
 * <p>The scanner is a hand-written single pass rather than a regular expression, so no input can
 * make it backtrack: every token is consumed from its first character to its end, and the running
 * index only ever moves forward. A token boundary is a plain ASCII character class decision, so a
 * surrogate pair is never split.</p>
 */
public final class CodeHighlighter {

    /**
     * The token colours one highlight palette uses.
     *
     * @param keyword the style of a language keyword
     * @param string the style of a string or character literal
     * @param comment the style of a comment
     * @param number the style of a numeric literal
     * @param literal the style of a literal word such as {@code true}, {@code false} or {@code null}
     */
    public record Styles(Style keyword, Style string, Style comment, Style number, Style literal) {

        /** Substitutes a plain style for a missing colour. */
        public Styles {
            keyword = keyword == null ? Style.NONE : keyword;
            string = string == null ? Style.NONE : string;
            comment = comment == null ? Style.NONE : comment;
            number = number == null ? Style.NONE : number;
            literal = literal == null ? Style.NONE : literal;
        }

        /**
         * A palette for a light terminal background.
         *
         * @return the light palette
         */
        public static Styles light() {
            return new Styles(
                    Style.fg(Colors.PURPLE_400),
                    Style.fg(Colors.SUCCESS_500),
                    Style.fg(Colors.GRAY_500),
                    Style.fg(Colors.ORANGE_400),
                    Style.fg(Colors.BLUE_400));
        }

        /**
         * A palette for a dark terminal background.
         *
         * @return the dark palette
         */
        public static Styles dark() {
            return new Styles(
                    Style.fg(Colors.VIOLET_400),
                    Style.fg(Colors.SUCCESS_300),
                    Style.fg(Colors.GRAY_400),
                    Style.fg(Colors.YELLOW_200),
                    Style.fg(Colors.CYAN_400));
        }
    }

    /** How one source language decides what a token is. */
    private enum Dialect {
        JAVA(
                "//",
                "/*",
                true,
                Set.of(
                        "abstract",
                        "assert",
                        "boolean",
                        "break",
                        "byte",
                        "case",
                        "catch",
                        "char",
                        "class",
                        "const",
                        "continue",
                        "default",
                        "do",
                        "double",
                        "else",
                        "enum",
                        "extends",
                        "final",
                        "finally",
                        "float",
                        "for",
                        "goto",
                        "if",
                        "implements",
                        "import",
                        "instanceof",
                        "int",
                        "interface",
                        "long",
                        "native",
                        "new",
                        "package",
                        "permits",
                        "private",
                        "protected",
                        "public",
                        "record",
                        "return",
                        "sealed",
                        "short",
                        "static",
                        "strictfp",
                        "super",
                        "switch",
                        "synchronized",
                        "this",
                        "throw",
                        "throws",
                        "transient",
                        "try",
                        "var",
                        "void",
                        "volatile",
                        "while",
                        "yield")),
        JSON(null, null, false, Set.of()),
        SHELL(
                "#",
                null,
                true,
                Set.of(
                        "case",
                        "do",
                        "done",
                        "elif",
                        "else",
                        "esac",
                        "export",
                        "fi",
                        "for",
                        "function",
                        "if",
                        "in",
                        "local",
                        "readonly",
                        "return",
                        "set",
                        "then",
                        "unset",
                        "while"));

        private final String lineComment;
        private final String blockComment;
        private final boolean backtickStrings;
        private final Set<String> keywords;

        Dialect(String lineComment, String blockComment, boolean backtickStrings, Set<String> keywords) {
            this.lineComment = lineComment;
            this.blockComment = blockComment;
            this.backtickStrings = backtickStrings;
            this.keywords = keywords;
        }
    }

    /** The words a literal token holds, whichever dialect reads them. */
    private static final Set<String> LITERALS = Set.of("true", "false", "null");

    private CodeHighlighter() {}

    /**
     * The language a fenced block declares: the fence's info string up to its first space, folded to
     * lower case. An info string of {@code java title="x"} names {@code java}.
     *
     * @param info the info string, or null
     * @return the language name, empty when the fence declares none
     */
    public static String language(String info) {
        if (info == null) {
            return "";
        }
        String trimmed = info.strip();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        return trimmed.substring(0, end).toLowerCase(Locale.ROOT);
    }

    /**
     * Highlights one code block.
     *
     * @param code the block's text, without a trailing fence
     * @param language the language name {@link #language(String)} answered
     * @param styles the palette to paint with
     * @return the block's spans; plain text when the language is not recognised
     */
    public static StyledText highlight(String code, String language, Styles styles) {
        String source = code == null ? "" : code;
        Dialect dialect = dialect(language);
        if (dialect == null || source.isEmpty() || styles == null) {
            return StyledText.plain(source);
        }
        StyledText out = StyledText.empty();
        int length = source.length();
        int plain = 0;
        int index = 0;
        while (index < length) {
            Style style = null;
            int end = index;
            if (dialect.blockComment != null && source.startsWith(dialect.blockComment, index)) {
                int close = source.indexOf("*/", index + 2);
                end = close < 0 ? length : close + 2;
                style = styles.comment();
            } else if (dialect.lineComment != null && source.startsWith(dialect.lineComment, index)) {
                int newline = source.indexOf('\n', index);
                end = newline < 0 ? length : newline;
                style = styles.comment();
            } else {
                char value = source.charAt(index);
                if (value == '"' || value == '\'' || (dialect.backtickStrings && value == '`')) {
                    end = stringEnd(source, index, value);
                    style = styles.string();
                } else if (Character.isDigit(value) && !identifierPart(previous(source, index))) {
                    end = numberEnd(source, index);
                    style = styles.number();
                } else if (identifierStart(value)) {
                    end = identifierEnd(source, index);
                    String word = source.substring(index, end);
                    style = LITERALS.contains(word)
                            ? styles.literal()
                            : dialect.keywords.contains(word) ? styles.keyword() : null;
                }
            }
            if (style == null) {
                index++;
                continue;
            }
            if (index > plain) {
                out = out.append(StyledText.plain(source.substring(plain, index)));
            }
            out = out.append(StyledText.of(source.substring(index, end), style));
            index = end;
            plain = index;
        }
        if (plain < length) {
            out = out.append(StyledText.plain(source.substring(plain)));
        }
        return out.isEmpty() ? StyledText.plain(source) : out;
    }

    /** The dialect a language name selects, or null when the name is not a bundled one. */
    private static Dialect dialect(String language) {
        return switch (language == null ? "" : language) {
            case "java" -> Dialect.JAVA;
            case "json" -> Dialect.JSON;
            case "sh", "shell", "bash", "zsh", "ksh", "dash" -> Dialect.SHELL;
            default -> null;
        };
    }

    /** The end of the string literal that opens at {@code start}, unterminated strings running to
     * the end of the block. A Java text block is one token, its newlines included. */
    private static int stringEnd(String source, int start, char quote) {
        int length = source.length();
        if (quote == '"' && source.startsWith("\"\"\"", start)) {
            int close = source.indexOf("\"\"\"", start + 3);
            return close < 0 ? length : close + 3;
        }
        int index = start + 1;
        while (index < length) {
            char value = source.charAt(index);
            if (value == '\\') {
                index += 2;
                continue;
            }
            if (value == quote) {
                return index + 1;
            }
            if (value == '\n' && quote != '`') {
                // An unterminated single-line string ends at the line break, which is how a reader
                // sees it: the rest of the line is code again, not string.
                return index;
            }
            index++;
        }
        return length;
    }

    /** The end of the numeric literal that opens at {@code start}. */
    private static int numberEnd(String source, int start) {
        int index = start;
        int length = source.length();
        while (index < length) {
            char value = source.charAt(index);
            if (Character.isLetterOrDigit(value) || value == '_' || value == '.') {
                index++;
                continue;
            }
            if ((value == '+' || value == '-') && index > start) {
                char before = source.charAt(index - 1);
                if (before == 'e' || before == 'E') {
                    index++;
                    continue;
                }
            }
            break;
        }
        return index;
    }

    /** The end of the identifier that opens at {@code start}. */
    private static int identifierEnd(String source, int start) {
        int index = start;
        while (index < source.length() && identifierPart(source.charAt(index))) {
            index++;
        }
        return index;
    }

    /** The character before {@code index}, or a space at the start of the block. */
    private static char previous(String source, int index) {
        return index == 0 ? ' ' : source.charAt(index - 1);
    }

    private static boolean identifierStart(char value) {
        return value == '_' || value == '$' || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
    }

    private static boolean identifierPart(char value) {
        return identifierStart(value) || (value >= '0' && value <= '9');
    }
}

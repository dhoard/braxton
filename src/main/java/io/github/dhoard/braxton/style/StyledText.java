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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable styled text: an ordered list of spans, each carrying text, a {@link Style}, and an
 * optional hyperlink target.
 *
 * <p>Content never contains terminal escape sequences or an unpaired surrogate, so it can be
 * encoded as UTF-8 unchanged and only the renderer turns styles into SGR/OSC 8 output. Appending
 * merges adjacent spans with identical styling so measurement and copying stay simple.</p>
 */
public final class StyledText {

    /** One styled run of text. {@code link} may be null but cannot contain C0/C1 controls. */
    public record Span(String text, Style style, String link) {

        public Span {
            text = sanitize(text == null ? "" : text);
            style = style == null ? Style.NONE : style;
            link = link == null || link.isBlank() ? null : link;
            if (link != null && containsControl(link)) {
                throw new IllegalArgumentException("link target must not contain control characters");
            }
        }

        /**
         * Removes C0/C1 controls except tab and newline, and drops unpaired surrogates, so content
         * can neither inject terminal commands nor carry a text unit that the UTF-8 write boundary
         * would replace with a different character.
         */
        private static String sanitize(String value) {
            boolean clean = true;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                        i++;
                        continue;
                    }
                    clean = false;
                    break;
                }
                if (Character.isLowSurrogate(c)
                        || !(c == '\n' || c == '\t'
                        || (c >= 0x20 && c != 0x7F && !(c >= 0x80 && c <= 0x9F)))) {
                    clean = false;
                    break;
                }
            }
            if (clean) {
                return value;
            }
            StringBuilder safe = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    // Both halves of a scalar value survive; a lone half is dropped like a control
                    // character, because UTF-8 cannot encode it and the terminal would paint '?'.
                    if (i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                        safe.append(c).append(value.charAt(++i));
                    }
                    continue;
                }
                if (Character.isLowSurrogate(c)) {
                    continue;
                }
                if (c == '\n' || c == '\t' || (c >= 0x20 && c != 0x7F && !(c >= 0x80 && c <= 0x9F))) {
                    safe.append(c);
                }
            }
            return safe.toString();
        }

        private static boolean containsControl(String value) {
            return value.codePoints().anyMatch(Character::isISOControl);
        }
    }

    private static final StyledText EMPTY = new StyledText(List.of());

    private final List<Span> spans;

    private StyledText(List<Span> spans) {
        this.spans = spans;
    }

    public static StyledText empty() {
        return EMPTY;
    }

    public static StyledText plain(String text) {
        return text == null || text.isEmpty() ? EMPTY : of(text, Style.NONE);
    }

    public static StyledText of(String text, Style style) {
        return of(text, style, null);
    }

    public static StyledText of(String text, Style style, String link) {
        if (text == null || text.isEmpty()) {
            return EMPTY;
        }
        return new StyledText(List.of(new Span(text, style, link)));
    }

    /** Joins values that may be heterogeneous, preserving each span's own styling. */
    public static StyledText concat(StyledText... parts) {
        List<Span> spans = new ArrayList<>();
        Span last = null;
        StringBuilder run = null;
        for (StyledText part : parts) {
            Objects.requireNonNull(part, "part");
            for (Span span : part.spans) {
                if (last != null && last.style().equals(span.style())
                        && Objects.equals(last.link(), span.link())) {
                    if (run == null) {
                        run = new StringBuilder(last.text());
                    }
                    run.append(span.text());
                } else {
                    if (last != null) {
                        spans.add(run == null ? last : new Span(run.toString(), last.style(), last.link()));
                    }
                    last = span;
                    run = null;
                }
            }
        }
        if (last != null) {
            spans.add(run == null ? last : new Span(run.toString(), last.style(), last.link()));
        }
        return spans.isEmpty() ? EMPTY : new StyledText(List.copyOf(spans));
    }

    public List<Span> spans() {
        return spans;
    }

    public boolean isEmpty() {
        return plainText().isEmpty();
    }

    /** The concatenated content with all styling removed. */
    public String plainText() {
        if (spans.size() == 1) {
            return spans.get(0).text();
        }
        StringBuilder text = new StringBuilder();
        for (Span span : spans) {
            text.append(span.text());
        }
        return text.toString();
    }

    /** UTF-16 length of the content. */
    public int length() {
        return plainText().length();
    }

    /** Terminal columns of the widest line under {@link WidthPolicy#PINNED}. */
    public int columns() {
        int widest = 0;
        for (String line : plainText().split("\n", -1)) {
            widest = Math.max(widest, TextMeasure.columns(line));
        }
        return widest;
    }

    /** Number of grapheme clusters across all lines. */
    public int graphemeCount() {
        return TextMeasure.graphemeCount(plainText());
    }

    /** The style of the first span, or {@link Style#NONE}. */
    public Style style() {
        return spans.isEmpty() ? Style.NONE : spans.get(0).style();
    }

    public StyledText append(StyledText other) {
        Objects.requireNonNull(other, "other");
        if (other.spans.isEmpty()) {
            return this;
        }
        if (spans.isEmpty()) {
            return other;
        }
        List<Span> merged = new ArrayList<>(spans.size() + other.spans.size());
        merged.addAll(spans);
        for (Span span : other.spans) {
            Span last = merged.get(merged.size() - 1);
            if (last.style().equals(span.style()) && Objects.equals(last.link(), span.link())) {
                merged.set(merged.size() - 1, new Span(last.text() + span.text(), last.style(), last.link()));
            } else {
                merged.add(span);
            }
        }
        return new StyledText(List.copyOf(merged));
    }

    public StyledText append(String text) {
        return append(plain(text));
    }

    /** Returns the same content with every span restyled, keeping links. */
    public StyledText withStyle(Style style) {
        if (spans.isEmpty()) {
            return this;
        }
        List<Span> restyled = new ArrayList<>(spans.size());
        for (Span span : spans) {
            restyled.add(new Span(span.text(), style, span.link()));
        }
        return new StyledText(List.copyOf(restyled));
    }

    /** Returns the same content with every span linked to {@code target}. */
    public StyledText withLink(String target) {
        if (spans.isEmpty()) {
            return this;
        }
        List<Span> linked = new ArrayList<>(spans.size());
        for (Span span : spans) {
            linked.add(new Span(span.text(), span.style(), target));
        }
        return new StyledText(List.copyOf(linked));
    }

    /** Removes trailing newline characters without disturbing styling. */
    public StyledText stripTrailingNewlines() {
        List<Span> trimmed = new ArrayList<>(spans);
        int index = trimmed.size() - 1;
        while (index >= 0) {
            Span last = trimmed.get(index);
            String text = last.text();
            if (text.isEmpty()) {
                index--;
                continue;
            }
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == '\n') {
                end--;
            }
            if (end == text.length()) {
                break;
            }
            trimmed.subList(index, trimmed.size()).clear();
            if (end > 0) {
                trimmed.add(new Span(text.substring(0, end), last.style(), last.link()));
                break;
            }
            index--;
        }
        return trimmed.isEmpty() ? EMPTY : new StyledText(List.copyOf(trimmed));
    }

    /** Splits this value on {@code \n}, returning one entry per line (never null entries). */
    public List<StyledText> lines() {
        List<StyledText> lines = new ArrayList<>();
        StyledText current = EMPTY;
        for (Span span : spans) {
            String text = span.text();
            int start = 0;
            while (true) {
                int newline = text.indexOf('\n', start);
                String piece = newline < 0 ? text.substring(start) : text.substring(start, newline);
                if (!piece.isEmpty()) {
                    current = current.append(new StyledText(List.of(new Span(piece, span.style(), span.link()))));
                }
                if (newline < 0) {
                    break;
                }
                lines.add(current);
                current = EMPTY;
                start = newline + 1;
            }
        }
        lines.add(current);
        return List.copyOf(lines);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StyledText text && spans.equals(text.spans);
    }

    @Override
    public int hashCode() {
        return spans.hashCode();
    }

    /** Bounded diagnostic form; never contains escape sequences and never includes links. */
    @Override
    public String toString() {
        String text = plainText();
        String preview = text.length() > 60 ? text.substring(0, 60) + "..." : text;
        return "StyledText[" + preview.replace("\n", "\\n") + "]";
    }
}

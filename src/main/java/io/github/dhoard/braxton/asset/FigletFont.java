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

package io.github.dhoard.braxton.asset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A FIGlet font parsed from a {@code .flf} stream.
 *
 * <p>The parser is data-only: it reads the header, skips comments, stores the printable ASCII
 * glyphs, and renders text by concatenating glyph rows. Malformed fonts fail with a diagnostic that
 * names the source instead of rendering partial art.</p>
 */
public final class FigletFont {

    private final String name;
    private final int height;
    private final char hardBlank;
    private final Map<Character, List<String>> glyphs;

    private FigletFont(String name, int height, char hardBlank, Map<Character, List<String>> glyphs) {
        this.name = name;
        this.height = height;
        this.hardBlank = hardBlank;
        this.glyphs = Map.copyOf(glyphs);
    }

    public static FigletFont parse(InputStream stream, String name) throws IOException {
        if (stream == null) {
            throw new IOException("font not found: " + name);
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new IOException("font " + name + " is empty");
        }
        String header = lines.get(0);
        if (!header.startsWith("flf2a") || header.length() < 7) {
            throw new IOException("font " + name + " is not a FIGlet font");
        }
        char hardBlankChar = header.charAt(5);
        String[] parts = header.substring(6).trim().split("\\s+");
        int height;
        int commentLines;
        try {
            height = Integer.parseInt(parts[0]);
            commentLines = parts.length > 4 ? Integer.parseInt(parts[4]) : 0;
        } catch (RuntimeException malformed) {
            throw new IOException("font " + name + " has an invalid header", malformed);
        }
        if (height <= 0) {
            throw new IOException("font " + name + " has an invalid height");
        }
        // The cursor is derived in long arithmetic and every bound is checked against what the
        // stream actually holds, so an absurd header is reported instead of overflowing the index
        // (or the ArrayList capacity) on the way to the diagnostic.
        long start = 1L + Math.max(0, commentLines);
        if (start > lines.size()) {
            throw new IOException("font " + name + " is truncated");
        }
        int index = (int) start;
        Map<Character, List<String>> glyphs = new HashMap<>();
        String firstRow = index < lines.size() ? lines.get(index) : "";
        char endMark = firstRow.isEmpty() ? hardBlankChar : firstRow.charAt(firstRow.length() - 1);
        for (char code = 32; code <= 126; code++) {
            if (height > lines.size() - index) {
                // A FIGlet font carries one glyph per printable ASCII code, so a short stream is
                // malformed: keeping the glyphs read so far would render partial art.
                throw new IOException("font " + name + " is truncated");
            }
            List<String> rows = new ArrayList<>(Math.min(height, lines.size() - index));
            for (int row = 0; row < height; row++) {
                String raw = lines.get(index + row);
                rows.add(stripEndMark(raw, endMark));
            }
            index += height;
            glyphs.put(code, List.copyOf(rows));
        }
        return new FigletFont(name, height, hardBlankChar, glyphs);
    }

    private static String stripEndMark(String raw, char endMark) {
        int end = raw.length();
        while (end > 0 && (raw.charAt(end - 1) == endMark
                || (endMark == '@' && raw.charAt(end - 1) == '@'))) {
            end--;
        }
        return raw.substring(0, end);
    }

    public String name() {
        return name;
    }

    public int height() {
        return height;
    }

    public char hardBlank() {
        return hardBlank;
    }

    /** Renders {@code text}; unknown characters are skipped. */
    public List<String> render(String text) {
        String value = text == null ? "" : text;
        List<String> result = new ArrayList<>();
        for (String line : value.split("\n", -1)) {
            List<String> rows = new ArrayList<>();
            for (int row = 0; row < height; row++) {
                rows.add("");
            }
            for (int i = 0; i < line.length(); i++) {
                List<String> glyph = glyphs.get(line.charAt(i));
                if (glyph == null) {
                    continue;
                }
                for (int row = 0; row < height; row++) {
                    rows.set(row, rows.get(row) + glyph.get(row));
                }
            }
            for (String row : rows) {
                result.add(row.replace(hardBlank, ' ').stripTrailing());
            }
        }
        return List.copyOf(result);
    }
}

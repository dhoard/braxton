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

package io.github.dhoard.braxton.internal.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The generated column-width table is reproducible from the pinned local data.
 *
 * <p>The README promises an offline-reproducible table; the generator can detect a hand edit but no
 * gate runs it. This test re-derives the wide set from the checked-in Unicode files and compares it
 * with {@link Unicode16Width#isWide(int)} for every code point, so a stale or hand-edited table
 * fails here instead of silently disagreeing with the documented policy.</p>
 */
class Unicode16WidthReproducibilityTest {

    private static final Path DATA = Path.of("tools", "unicode", "16.0.0");
    private static final int MAX_CODE_POINT = 0x10FFFF;

    @Test
    void everyCodePointAgreesWithThePinnedData() throws IOException {
        List<int[]> wide = new ArrayList<>();
        wide.addAll(ranges(DATA.resolve("EastAsianWidth.txt"), "W", "F"));
        wide.addAll(ranges(DATA.resolve("emoji-data.txt"), "Emoji_Presentation"));
        assertThat(wide).as("the pinned files must yield wide ranges").isNotEmpty();

        List<int[]> merged = merge(wide);
        int next = 0;
        for (int codePoint = 0; codePoint <= MAX_CODE_POINT; codePoint++) {
            while (next < merged.size() && merged.get(next)[1] < codePoint) {
                next++;
            }
            boolean expected = next < merged.size() && merged.get(next)[0] <= codePoint
                    && codePoint <= merged.get(next)[1];
            if (Unicode16Width.isWide(codePoint) != expected) {
                throw new AssertionError("U+" + Integer.toHexString(codePoint).toUpperCase()
                        + " is " + (expected ? "wide" : "narrow") + " in the pinned data but the"
                        + " generated table disagrees");
            }
        }
        assertThat(merged).as("the generated table holds one range per merged run").hasSize(122);
    }

    private static List<int[]> ranges(Path file, String... wanted) throws IOException {
        List<int[]> found = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String content = line.split("#", 2)[0].strip();
            if (content.isEmpty()) {
                continue;
            }
            String[] fields = content.split(";");
            String value = fields[1].strip();
            boolean matches = false;
            for (String candidate : wanted) {
                matches |= candidate.equals(value);
            }
            if (!matches) {
                continue;
            }
            String codePoints = fields[0].strip();
            int dots = codePoints.indexOf("..");
            int start = Integer.parseInt(dots < 0 ? codePoints : codePoints.substring(0, dots), 16);
            int end = dots < 0 ? start : Integer.parseInt(codePoints.substring(dots + 2), 16);
            found.add(new int[] {start, end});
        }
        return found;
    }

    private static List<int[]> merge(List<int[]> ranges) {
        ranges.sort((left, right) -> left[0] == right[0]
                ? Integer.compare(left[1], right[1]) : Integer.compare(left[0], right[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : ranges) {
            if (!merged.isEmpty() && range[0] <= merged.get(merged.size() - 1)[1] + 1) {
                int[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new int[] {range[0], range[1]});
            }
        }
        return merged;
    }
}

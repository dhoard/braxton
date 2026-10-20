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

package io.github.dhoard.braxton.benchmark;

/**
 * Shared fixture text for the benchmarks. Kept in one place so a corpus change moves every
 * benchmark together and the {@code @Param} names stay comparable across cycles.
 */
final class BenchCorpus {

    private static final String ASCII_UNIT = "the quick brown fox jumps over the lazy dog 0123456789 ";
    private static final String MIXED_UNIT = "a漢🙂b" + "́" + "\tz ";
    private static final String CJK_UNIT = "漢字テストの文章";

    private BenchCorpus() {
    }

    /**
     * A corpus of at least {@code size} characters.
     *
     * @param name {@code ascii} for printable ASCII only, {@code mixed} for a wide glyph, an emoji,
     *         a combining mark and a tab interleaved with ASCII
     * @param size minimum character count
     */
    static String build(String name, int size) {
        String unit = switch (name) {
            case "ascii" -> ASCII_UNIT;
            case "mixed" -> MIXED_UNIT;
            case "cjk" -> CJK_UNIT;
            default -> throw new IllegalArgumentException("unknown corpus: " + name);
        };
        StringBuilder out = new StringBuilder(Math.max(size, unit.length()));
        while (out.length() < size) {
            out.append(unit);
        }
        return out.substring(0, Math.max(size, unit.length()));
    }
}

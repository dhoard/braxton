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

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The documented palette is distinct data, and each numbered scale darkens as the number grows. */
class ColorsTest {

    private static final List<String> SCALES = List.of("GRAY_", "ERROR_", "WARNING_", "SUCCESS_");

    /** The palette in declaration order, so a scale's numbers are compared in the order it lists. */
    private static Map<String, Color> palette() {
        Map<String, Color> palette = new LinkedHashMap<>();
        for (Field field : Colors.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Color.class) {
                try {
                    palette.put(field.getName(), (Color) field.get(null));
                } catch (IllegalAccessException failure) {
                    throw new AssertionError("palette field is not readable: " + field, failure);
                }
            }
        }
        return palette;
    }

    private static double luminance(Color color) {
        return 0.2126 * color.red() + 0.7152 * color.green() + 0.0722 * color.blue();
    }

    @Test
    void everyPaletteEntryIsADistinctInRangeColour() {
        Map<String, Color> palette = palette();
        assertThat(palette).hasSize(38);
        List<Color> seen = new ArrayList<>();
        for (Map.Entry<String, Color> entry : palette.entrySet()) {
            assertThat(seen).as("%s repeats another entry", entry.getKey())
                    .doesNotContain(entry.getValue());
            seen.add(entry.getValue());
            assertThat(Color.parse(entry.getValue().hex())).as("%s round-trips", entry.getKey())
                    .contains(entry.getValue());
        }
    }

    /** A numbered scale is ordered light to dark, so the scale reads as a ramp in the examples. */
    @Test
    void eachNumberedScaleDarkensAsItsNumberGrows() {
        Map<String, Color> palette = palette();
        for (String scale : SCALES) {
            List<String> names = palette.keySet().stream().filter(name -> name.startsWith(scale))
                    .toList();
            assertThat(names).as("entries of %s", scale).hasSizeGreaterThan(1);
            double previous = Double.MAX_VALUE;
            for (String name : names) {
                double current = luminance(palette.get(name));
                assertThat(current).as("%s is darker than its predecessor", name)
                        .isLessThan(previous);
                previous = current;
            }
        }
    }

    @Test
    void whiteAndBlackBoundThePalette() {
        Map<String, Color> palette = palette();
        assertThat(Colors.WHITE).isEqualTo(Color.parse("#ffffff").orElseThrow());
        assertThat(Colors.BLACK).isEqualTo(Color.parse("#000000").orElseThrow());
        for (Map.Entry<String, Color> entry : palette.entrySet()) {
            if ("WHITE".equals(entry.getKey()) || "BLACK".equals(entry.getKey())) {
                continue;
            }
            assertThat(luminance(entry.getValue())).as("luminance of %s", entry.getKey())
                    .isBetween(luminance(Colors.BLACK), luminance(Colors.WHITE));
        }
    }

    /** Anchors pin the documented values, so a re-typed palette shows up as a data change. */
    @Test
    void theDocumentedAnchorsKeepTheirValues() {
        assertThat(Colors.GRAY_400.hex()).isEqualTo("#717680");
        assertThat(Colors.ERROR_400.hex()).isEqualTo("#f04438");
        assertThat(Colors.WARNING_400.hex()).isEqualTo("#f79009");
        assertThat(Colors.SUCCESS_400.hex()).isEqualTo("#17b26a");
        assertThat(Colors.BLUE_400.hex()).isEqualTo("#2e90fa");
        assertThat(Colors.TEAL_400.hex()).isEqualTo("#15b79e");
    }
}

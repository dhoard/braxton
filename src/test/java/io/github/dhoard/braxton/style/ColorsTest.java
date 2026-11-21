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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The documented palette is distinct data, and each numbered scale darkens as the number grows.
 */
class ColorsTest {

    private static final List<String> SCALES = List.of("GRAY_", "ERROR_", "WARNING_", "SUCCESS_");

    /**
     * The palette in declaration order, so a scale's numbers are compared in the order it lists.
     */
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
            assertThat(seen).as("%s repeats another entry", entry.getKey()).doesNotContain(entry.getValue());
            seen.add(entry.getValue());
            assertThat(Color.parse(entry.getValue().hex()))
                    .as("%s round-trips", entry.getKey())
                    .contains(entry.getValue());
        }
    }

    /**
     * A numbered scale is ordered light to dark, so the scale reads as a ramp in the examples.
     */
    @Test
    void eachNumberedScaleDarkensAsItsNumberGrows() {
        Map<String, Color> palette = palette();
        for (String scale : SCALES) {
            List<String> names = palette.keySet().stream()
                    .filter(name -> name.startsWith(scale))
                    .toList();
            assertThat(names).as("entries of %s", scale).hasSizeGreaterThan(1);
            double previous = Double.MAX_VALUE;
            for (String name : names) {
                double current = luminance(palette.get(name));
                assertThat(current)
                        .as("%s is darker than its predecessor", name)
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
            assertThat(luminance(entry.getValue()))
                    .as("luminance of %s", entry.getKey())
                    .isBetween(luminance(Colors.BLACK), luminance(Colors.WHITE));
        }
    }

    /**
     * Anchors pin the documented values, so a re-typed palette shows up as a data change.
     */
    @Test
    void theDocumentedAnchorsKeepTheirValues() {
        assertThat(Colors.GRAY_400.hex()).isEqualTo("#717680");
        assertThat(Colors.ERROR_400.hex()).isEqualTo("#f04438");
        assertThat(Colors.WARNING_400.hex()).isEqualTo("#f79009");
        assertThat(Colors.SUCCESS_400.hex()).isEqualTo("#17b26a");
        assertThat(Colors.BLUE_400.hex()).isEqualTo("#2e90fa");
        assertThat(Colors.TEAL_400.hex()).isEqualTo("#15b79e");
    }

    @Test
    void theSixteenAnsiColoursAreTheXtermOnes() {
        assertThat(Colors.ansi16(0).hex()).isEqualTo("#000000");
        assertThat(Colors.ansi16(1).hex()).isEqualTo("#800000");
        assertThat(Colors.ansi16(7).hex()).isEqualTo("#c0c0c0");
        assertThat(Colors.ansi16(8).hex()).isEqualTo("#808080");
        assertThat(Colors.ansi16(12).hex()).isEqualTo("#0000ff");
        assertThat(Colors.ansi16(15).hex()).isEqualTo("#ffffff");
        assertThatThrownBy(() -> Colors.ansi16(16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Colors.ansi16(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theXtermPaletteIsTheCubeAndTheGreyRamp() {
        assertThat(Colors.ansi256(16).hex()).isEqualTo("#000000");
        assertThat(Colors.ansi256(17).hex()).isEqualTo("#00005f");
        assertThat(Colors.ansi256(21).hex()).isEqualTo("#0000ff");
        assertThat(Colors.ansi256(196).hex()).isEqualTo("#ff0000");
        assertThat(Colors.ansi256(231).hex()).isEqualTo("#ffffff");
        assertThat(Colors.ansi256(232).hex()).isEqualTo("#080808");
        assertThat(Colors.ansi256(255).hex()).isEqualTo("#eeeeee");
        assertThatThrownBy(() -> Colors.ansi256(256)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyPaletteEntryMapsToItself() {
        for (int index = 16; index <= 0xFF; index++) {
            assertThat(Colors.nearestAnsi256(Colors.ansi256(index)))
                    .as("palette entry %d", index)
                    .isEqualTo(index);
        }
        for (int index = 0; index < 16; index++) {
            assertThat(Colors.nearestAnsi16(Colors.ansi16(index)))
                    .as("ANSI color %d", index)
                    .isEqualTo(index);
        }
    }

    @Test
    void theNearestColourIsTheOneItLooksLike() {
        assertThat(Colors.nearestAnsi16(Colors.BLUE_400))
                .as("a mid blue maps to blue, not to the cyan a channel distance would pick")
                .isEqualTo(4);
        assertThat(Colors.nearestAnsi16(Colors.ERROR_400)).isEqualTo(9);
        assertThat(Colors.nearestAnsi16(Color.WHITE)).isEqualTo(15);
        assertThat(Colors.nearestAnsi16(Color.BLACK)).isIn(0);
        assertThat(Colors.nearestAnsi256(Color.BLACK))
                .as("the ANSI entries are never candidates, so black maps to the cube")
                .isEqualTo(16);
        assertThat(Colors.nearestAnsi256(Color.WHITE)).isEqualTo(231);
        assertThat(Colors.nearestAnsi256(Colors.GRAY_500)).isBetween(232, 255);
        // The same input always maps to the same entry, memoized or not.
        assertThat(Colors.nearestAnsi256(Colors.TEAL_400)).isEqualTo(Colors.nearestAnsi256(Colors.TEAL_400));
    }

    @Test
    void darknessIsOneRuleWithOneBoundary() {
        assertThat(Colors.isDark(new Color(0x7f, 0x7f, 0x7f)))
                .as("just below halfway")
                .isTrue();
        assertThat(Colors.isDark(new Color(0x80, 0x80, 0x80)))
                .as("halfway exactly")
                .isFalse();
        assertThat(Colors.isDark(Color.BLACK)).isTrue();
        assertThat(Colors.isDark(Color.WHITE)).isFalse();
        assertThat(Colors.isDark(new Color(0x00, 0x00, 0xff)))
                .as("blue carries little luma")
                .isTrue();
        assertThat(Colors.isDark(new Color(0xff, 0xff, 0x00)))
                .as("yellow carries a lot")
                .isFalse();
    }
}

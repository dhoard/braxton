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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A picture, painted one character per pixel, from input nobody designed.
 *
 * <p>An image view has one job that the rest of the terminal depends on: the grid it paints has to be
 * the grid it measures. Every row has to be as wide as the pixels it holds, a transparent pixel has to
 * take up exactly as much room as an opaque one, and repeating a pixel has to multiply its width rather
 * than rearrange it. A row that comes out one column short shifts everything after it, and the encoder
 * paints the shifted row without complaint.</p>
 *
 * <p>Symbols the cluster rules attach to the pixel before them - a bare combining mark, a lone
 * variation selector, half a flag - cannot hold that rule at all. They are excluded from the width law
 * and checked separately for the collapse the project pins as measurement policy.</p>
 *
 * <p>The unit of the grid is measured, not assumed: a one pixel image rendered at scale one says how
 * wide one pixel is for a given symbol, and every other render of that symbol has to be a whole number
 * of those units. Colour has its own oracle - an exact RGB match is replaced and everything else keeps
 * its own value - and the pixel store is checked against the image library that produced it.</p>
 */
@Timeout(value = 120, unit = java.util.concurrent.TimeUnit.SECONDS)
class ImageViewFuzzTest {

    /** Symbols a pixel might be painted with, including ones the terminal boundary dislikes. */
    private static final String[] SYMBOLS = {
        "\u2588",
        "#",
        "ab",
        "\u4E2D",
        "\uD83D\uDE00",
        "\uD83D\uDE00\uFE0F",
        "\u00E9\u0301",
        "\u200D",
        "a\u200D",
        "\u200Da",
        "\uFE0F",
        "\u200B",
        "\n",
        "a\nb",
        "\t",
        "\u0000",
        "\u0007\u001B[31m",
        "\uD83D",
        "\uDE00",
        "",
        " ",
        "\u2588\u200D\u2588",
    };

    /**
     * Symbols that begin with something the cluster rules attach to the character before them: a bare
     * combining mark, a variation selector on its own, a single regional indicator waiting for its
     * partner. Repeating them does not multiply their width predictably: a row of marks measures one
     * or two cells instead of four, and a row of lone indicators pairs up and measures more. They
     * cannot hold the one-cell-per-pixel rule the rest of this class checks.
     * {@code ImageViewTest.aCombiningMarkAttachesToThePixelBeforeItByTheUnicodeClusterRule} pins that
     * as measurement policy the renderer deliberately does not correct, so they are checked for that
     * behaviour below instead of here.
     */
    private static final String[] MERGING_SYMBOLS = {"\u0301", "\u0301\u0301", "\uFE0E", "\uD83C\uDDE6"};

    private static final int[] SCALES = {Integer.MIN_VALUE, -7, 0, 1, 2, 3, 5, 9, 16, 64};

    @Test
    void everyRowIsAsWideAsThePixelsItHolds() {
        Random random = new Random(2_828_427L);
        for (int round = 0; round < 2_500; round++) {
            ImageAsset asset = ImageAsset.of(image(random));
            String symbol = SYMBOLS[random.nextInt(SYMBOLS.length)];
            int scale = Math.max(1, SCALES[random.nextInt(SCALES.length)]);
            String where =
                    "image %dx%d symbol %s scale %d".formatted(asset.width(), asset.height(), show(symbol), scale);
            String painted =
                    ImageView.of(asset).symbol(symbol).scale(scale).render().plainText();

            if (asset.height() == 0) {
                assertThat(painted)
                        .as("%s: an image with no rows painted something", where)
                        .isEmpty();
                continue;
            }
            List<String> rows = rows(painted, where);
            int unit = unit(symbol);
            assertThat(unit)
                    .as("%s: one pixel painted %d columns, so no grid can be built from it", where, unit)
                    .isPositive();
            for (int row = 0; row < rows.size(); row++) {
                assertThat(TextMeasure.columns(rows.get(row)))
                        .as(
                                "%s: row %d is %d columns wide, but %d pixels at %d columns each were asked for",
                                where, row, TextMeasure.columns(rows.get(row)), asset.width(), unit * scale)
                        .isEqualTo(asset.width() * unit * scale);
            }
        }
    }

    /** A symbol that carries no column falls back to the block rather than painting a short grid. */
    @Test
    void aSymbolThatCarriesNoColumnFallsBackAndRepeatingAPixelOnlyMakesItWider() {
        ImageAsset grid = ImageAsset.of(image(new Random(11), 3, 2));
        for (String symbol : SYMBOLS) {
            String where = "symbol " + show(symbol);
            List<String> rows = rows(ImageView.of(grid).symbol(symbol).render().plainText(), where);
            int width = TextMeasure.columns(rows.getFirst());
            assertThat(width).as("%s: painted a row with no width", where).isPositive();
            for (int row = 1; row < rows.size(); row++) {
                assertThat(TextMeasure.columns(rows.get(row)))
                        .as("%s: row %d is a different width than row 0", where, row)
                        .isEqualTo(width);
            }
            // Repeating a pixel scales its width by the repetition, and does not rearrange it.
            for (int scale : new int[] {2, 3, 7, 40}) {
                List<String> scaled = rows(
                        ImageView.of(grid).symbol(symbol).scale(scale).render().plainText(), where);
                assertThat(scaled).as("%s at scale %d: rows", where, scale).hasSize(rows.size());
                for (int row = 0; row < rows.size(); row++) {
                    assertThat(TextMeasure.columns(scaled.get(row)))
                            .as("%s at scale %d: row %d", where, scale, row)
                            .isEqualTo(width * scale);
                }
            }
        }
    }

    /**
     * The merging symbols do not hold the grid rule, and that is the pinned decision rather than an
     * accident: they are recorded here so the rule above says what it means.
     */
    @Test
    void aSymbolTheClusterRulesAttachToThePixelBeforeItIsMeasuredThatWayOnPurpose() {
        ImageAsset grid = ImageAsset.of(image(new Random(29), 4, 2));
        for (String symbol : MERGING_SYMBOLS) {
            List<String> rows = rows(ImageView.of(grid).symbol(symbol).render().plainText(), "symbol " + show(symbol));
            int measured = TextMeasure.columns(rows.getFirst());
            int law = grid.width() * unit(symbol);
            // Marks and selectors collapse the row; unpaired regional indicators pair up across the
            // pixels between them and stretch it. Either way the grid is no longer one cell per pixel,
            // which is the policy ImageViewTest pins for the combining-mark case.
            assertThat(measured)
                    .as(
                            "symbol %s: a %d pixel row measured %d columns, the same as the one-cell-per-pixel rule asks for",
                            show(symbol), grid.width(), measured)
                    .isNotEqualTo(law);
        }
    }

    /** Transparent pixels hold their cell, and every pixel keeps the colour it was given. */
    @Test
    void transparentPixelsHoldTheirCellAndOpaquePixelsKeepTheirColour() {
        Random random = new Random(1_732_050L);
        for (int round = 0; round < 1_200; round++) {
            ImageAsset asset = ImageAsset.of(image(random));
            Map<String, String> rules = new HashMap<>();
            Map<Integer, Color> replacements = new HashMap<>();
            for (int at = random.nextInt(4); at > 0; at--) {
                String from = hex(random);
                String to = hex(random);
                rules.put(from, to);
                Color parsedFrom = Color.parse(from).orElse(null);
                Color parsedTo = Color.parse(to).orElse(null);
                if (parsedFrom != null && parsedTo != null) {
                    replacements.put(rgb(parsedFrom), parsedTo);
                }
            }
            String symbol = SYMBOLS[random.nextInt(SYMBOLS.length)];
            int scale = Math.max(1, SCALES[random.nextInt(SCALES.length)]);
            ImageView view = ImageView.of(asset);
            for (Map.Entry<String, String> rule : rules.entrySet()) {
                view = view.replace(rule.getKey(), rule.getValue());
            }
            StyledText painted = view.symbol(symbol).scale(scale).render();
            String where =
                    "image %dx%d rules %s symbol %s".formatted(asset.width(), asset.height(), rules, show(symbol));

            int unit = unit(symbol);
            for (String row : rows(painted.plainText(), where)) {
                assertThat(TextMeasure.columns(row))
                        .as("%s: row %s is not a whole number of pixels", where, show(row))
                        .isEqualTo(asset.width() * unit * scale);
            }

            Set<Color> wanted = new HashSet<>();
            for (int y = 0; y < asset.height(); y++) {
                for (int x = 0; x < asset.width(); x++) {
                    int argb = asset.argb(x, y);
                    if ((argb >>> 24) == 0) {
                        continue;
                    }
                    int value = argb & 0xFFFFFF;
                    Color own = new Color((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
                    wanted.add(replacements.getOrDefault(value, own));
                }
            }
            Set<Color> paintedColors = new HashSet<>();
            for (StyledText.Span span : painted.spans()) {
                Color foreground = span.style().foreground();
                if (foreground != null) {
                    paintedColors.add(foreground);
                }
            }
            assertThat(paintedColors)
                    .as(
                            "%s: painted colours %s do not match the pixels and replacements %s",
                            where, paintedColors, wanted)
                    .isEqualTo(wanted);
        }
    }

    /** The pixel store is the image library's answer, and coordinates outside it are transparent. */
    @Test
    void theAssetCopiesTheImageAndAnswersOutsideItsBoundsWithNothing() {
        Random random = new Random(1_618_033L);
        for (int round = 0; round < 400; round++) {
            BufferedImage source = image(random);
            ImageAsset asset = ImageAsset.of(source);
            String where = "image %dx%d".formatted(source.getWidth(), source.getHeight());
            assertThat(asset.width()).as("%s: width", where).isEqualTo(source.getWidth());
            assertThat(asset.height()).as("%s: height", where).isEqualTo(source.getHeight());
            assertThat(asset.pixels())
                    .as("%s: pixel copy length", where)
                    .hasSize(source.getWidth() * source.getHeight());
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    assertThat(asset.argb(x, y))
                            .as("%s: pixel (%d,%d)", where, x, y)
                            .isEqualTo(source.getRGB(x, y));
                    assertThat(asset.pixels()[y * source.getWidth() + x])
                            .as("%s: pixel (%d,%d) in the copy", where, x, y)
                            .isEqualTo(source.getRGB(x, y));
                }
            }
            for (int[] outside : new int[][] {
                {-1, 0},
                {0, -1},
                {source.getWidth(), 0},
                {0, source.getHeight()},
                {source.getWidth() + 7, source.getHeight() + 11},
                {Integer.MIN_VALUE, Integer.MAX_VALUE}
            }) {
                assertThat(asset.argb(outside[0], outside[1]))
                        .as("%s: (%d,%d) is outside the image", where, outside[0], outside[1])
                        .isZero();
            }
            // The copy is a copy: writing through it does not change the asset.
            int[] copy = asset.pixels();
            if (copy.length > 0) {
                int original = copy[0];
                copy[0] ^= 0x00FF_FFFF;
                assertThat(asset.pixels()[0])
                        .as("%s: the pixel copy is the asset's own array", where)
                        .isEqualTo(original);
            }
        }
        assertThatThrownBy(() -> ImageAsset.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImageView.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Whatever a file happens to contain, loading is either a picture or an IOException. */
    @Test
    void aFileThatIsNotAnImageIsAnIOExceptionAndNothingElse(@TempDir Path directory) throws IOException {
        Random random = new Random(104_729L);
        for (int round = 0; round < 300; round++) {
            byte[] body = new byte[random.nextInt(600)];
            random.nextBytes(body);
            int shape = round % 4;
            if (shape == 0 && body.length > 8) {
                System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, body, 0, 8);
            } else if (shape == 1) {
                body = "P2\n1 1\n255\n0\n".getBytes(StandardCharsets.US_ASCII);
            } else if (shape == 2) {
                body = new byte[] {
                    (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00
                };
            }
            Path file = directory.resolve("maybe-" + round + ".png");
            Files.write(file, body);
            try {
                ImageAsset asset = ImageAsset.load(file);
                assertThat(asset.width())
                        .as("file %d of %d bytes parsed to a zero width image", round, body.length)
                        .isPositive();
                assertThat(asset.height())
                        .as("file %d of %d bytes parsed to a zero height image", round, body.length)
                        .isPositive();
            } catch (IOException expected) {
                // A picture nobody could paint is a load failure, not a half-built asset.
            } catch (RuntimeException failure) {
                throw new AssertionError(
                        "file " + round + " of " + body.length + " bytes failed as " + failure, failure);
            }
        }
        assertThatThrownBy(() -> ImageAsset.load(null)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ImageAsset.load(directory.resolve("absent.png")))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> ImageAsset.load(directory)).isInstanceOf(IOException.class);

        // A file that really is a picture survives the round trip.
        BufferedImage source = image(new Random(4_242L), 4, 3);
        Path real = directory.resolve("real.png");
        ImageIO.write(source, "png", real.toFile());
        ImageAsset loaded = ImageAsset.load(real);
        assertThat(loaded.width()).as("a png round trip width").isEqualTo(source.getWidth());
        assertThat(loaded.height()).as("a png round trip height").isEqualTo(source.getHeight());
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                assertThat(loaded.argb(x, y))
                        .as("a png round trip pixel (%d,%d)", x, y)
                        .isEqualTo(source.getRGB(x, y));
            }
        }
    }

    /**
     * How wide one pixel is for a given symbol: one opaque pixel, rendered on its own. Measuring the
     * unit instead of computing it keeps the grid claim independent of how the view sanitizes a symbol.
     */
    private static int unit(String symbol) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        probe.setRGB(0, 0, 0xFF01_0203);
        List<String> rows =
                rows(ImageView.of(ImageAsset.of(probe)).symbol(symbol).render().plainText(), "probe");
        return rows.isEmpty() ? 0 : TextMeasure.columns(rows.getFirst());
    }

    /** The rows of a painted picture. A row separator is painted between every pair of image rows. */
    private static List<String> rows(String painted, String where) {
        String[] parts = painted.split("\n", -1);
        long separators = painted.chars().filter(value -> value == '\n').count();
        assertThat((long) parts.length - 1)
                .as("%s: %d row separators for %d rows", where, separators, parts.length)
                .isEqualTo(separators);
        List<String> rows = new ArrayList<>();
        for (String part : parts) {
            rows.add(part);
        }
        return rows;
    }

    /** AWT refuses a BufferedImage with no rows or columns, so the smallest picture is 1x1. */
    private static BufferedImage image(Random random) {
        return image(random, 1 + random.nextInt(6), 1 + random.nextInt(5));
    }

    private static BufferedImage image(Random random, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = switch (random.nextInt(4)) {
                    case 0 -> 0x0000_0000;
                    case 1 -> random.nextInt();
                    case 2 -> 0xFF00_0000 | random.nextInt(0x100_0000);
                    default -> 0xFF00_0000 | (random.nextInt(3) * 0x0001_0101);
                };
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private static int rgb(Color color) {
        return (color.red() << 16) | (color.green() << 8) | color.blue();
    }

    private static String hex(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> "#%06x".formatted(random.nextInt(0x100_0000));
            case 1 -> "#%06X".formatted(random.nextInt(0x100_0000));
            case 2 -> "#GGGGGG";
            case 3 -> "#12345";
            case 4 -> "123456";
            default -> random.nextBoolean() ? "" : null;
        };
    }

    private static String show(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder("\"");
        for (int i = 0; i < Math.min(value.length(), 24); i++) {
            char c = value.charAt(i);
            text.append(c >= 0x20 && c < 0x7F ? String.valueOf(c) : String.format("\\u%04x", (int) c));
        }
        return text.append(value.length() > 24 ? "...\"" : "\"").toString();
    }
}

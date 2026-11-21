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

package io.github.dhoard.braxton.image;

import io.github.dhoard.braxton.asset.ImageAsset;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Widget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded image output. Decoding remains at the {@link ImageAsset} boundary.
 *
 * <p>Dimensions are pixels. {@link #fit} downsamples without enlarging or changing aspect ratio;
 * sampling is nearest neighbor. A half-block cell contains two vertical pixels, so a raster of
 * 80 by 48 pixels occupies 80 columns and 24 rows. Native protocols retain the raster's pixels;
 * the terminal decides their physical size. Sixel uses a 216-color cube. Alpha is retained in
 * Kitty/iTerm2 and composited against {@link #withBackground} for Sixel and half blocks.</p>
 *
 * <p>Encoding rejects a raster larger than 4,194,304 pixels, an axis over 4,096 pixels, or output
 * over 64 Mi characters. Call {@code fit} before encoding an asset wider than this limit.
 * A session write is an explicit low-level operation at its current cursor; do not interleave
 * it with an MVU runtime's output. Use {@link #element} for composed MVU fallback images.</p>
 */
public final class ImageOutput {
    static final int MAX_PIXELS = 4_194_304;
    static final int MAX_AXIS = 4096;
    static final int MAX_OUTPUT = 64 * 1024 * 1024;
    private final ImageAsset source;
    private final int width;
    private final int height;
    private final Color background;

    private ImageOutput(ImageAsset source, int width, int height, Color background) {
        this.source = Objects.requireNonNull(source, "image");
        this.width = width;
        this.height = height;
        this.background = Objects.requireNonNull(background, "background");
    }

    /**
     * Creates image output using the asset's original dimensions.
     *
     * @param image the bounded immutable image asset
     * @return image output using the asset's original dimensions
     */
    public static ImageOutput of(ImageAsset image) {
        Objects.requireNonNull(image, "image");
        return new ImageOutput(image, image.width(), image.height(), Color.BLACK);
    }

    /**
     * Returns the width in pixels.
     *
     * @return the width in pixels
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height in pixels.
     *
     * @return the height in pixels
     */
    public int height() {
        return height;
    }

    /**
     * Chooses the opaque background used when the output format cannot represent alpha.
     *
     * @param color the color to apply
     * @return the value with the requested background
     */
    public ImageOutput withBackground(Color color) {
        return new ImageOutput(source, width, height, color);
    }

    /**
     * Fits within positive pixel bounds, retaining aspect ratio and never enlarging the raster.
     *
     * @param maxWidth the maximum output width in pixels
     * @param maxHeight the maximum output height in pixels
     * @return a fitted image preserving aspect ratio without enlargement
     */
    public ImageOutput fit(int maxWidth, int maxHeight) {
        if (maxWidth < 1 || maxHeight < 1) {
            throw new IllegalArgumentException("image fit bounds must be positive");
        }
        double scale = Math.min(
                1.0,
                Math.min(
                        (double) Math.min(maxWidth, MAX_AXIS) / width,
                        (double) Math.min(maxHeight, MAX_AXIS) / height));
        return new ImageOutput(
                source, Math.max(1, (int) (width * scale)), Math.max(1, (int) (height * scale)), background);
    }

    private static void checkSize(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_AXIS || height > MAX_AXIS || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException(
                    "image raster must be positive, at most 4096 per axis and 4194304 pixels");
        }
    }

    int argb(int x, int y) {
        return source.argb((int) ((long) x * source.width() / width), (int) ((long) y * source.height() / height));
    }

    Color color(int x, int y) {
        if (y >= height) return background;
        int pixel = argb(x, y);
        int alpha = pixel >>> 24;
        return new Color(
                blend((pixel >>> 16) & 255, background.red(), alpha),
                blend((pixel >>> 8) & 255, background.green(), alpha),
                blend(pixel & 255, background.blue(), alpha));
    }

    private static int blend(int value, int background, int alpha) {
        return (value * alpha + background * (255 - alpha) + 127) / 255;
    }

    /**
     * Styled upper-half blocks, with one background pixel below each foreground pixel.
     *
     * @return styled upper-half blocks, with one background pixel below each foreground pixel
     */
    public StyledText halfBlocks() {
        checkSize(width, height);
        List<StyledText> parts = new ArrayList<>();
        for (int y = 0; y < height; y += 2) {
            if (y > 0) parts.add(StyledText.plain("\n"));
            for (int x = 0; x < width; x++) {
                parts.add(StyledText.of("\u2580", Style.fg(color(x, y)).withBackground(color(x, y + 1))));
            }
        }
        return StyledText.concat(parts.toArray(StyledText[]::new));
    }

    /**
     * A measured leaf painting the portable fallback, without opening or writing a terminal.
     *
     * @param <Msg> the message type
     * @return a measured leaf painting the portable fallback, without opening or writing a terminal
     */
    public <Msg> Element<Msg> element() {
        checkSize(width, height);
        return new Element.Leaf<>(new HalfBlockWidget<Msg>(), this);
    }

    /**
     * An image is a clipped grid, not wrapping prose; paint only the visible pixels.
     */
    private static final class HalfBlockWidget<Msg> implements Widget<ImageOutput, Msg> {
        public Size measure(Size max, ImageOutput image) {
            return new Size(
                    Math.max(0, Math.min(max.rows(), (image.height + 1) / 2)),
                    Math.max(0, Math.min(max.cols(), image.width)));
        }

        public void paint(Surface surface, Rect bounds, ImageOutput image) {
            for (int row = 0; row < Math.min(bounds.rows(), (image.height + 1) / 2); row++) {
                for (int col = 0; col < Math.min(bounds.cols(), image.width); col++) {
                    Style style = Style.fg(image.color(col, row * 2)).withBackground(image.color(col, row * 2 + 1));
                    surface.put(bounds.row() + row, bounds.col() + col, "\u2580", style, null, 1);
                }
            }
        }
    }

    /**
     * Encodes only library-owned protocol syntax and pixel data; no caller text is interpolated.
     *
     * @param protocol the selected graphics protocol
     * @return encodes only library-owned protocol syntax and pixel data; no caller text is interpolated
     */
    public String encode(ImageProtocol protocol) {
        return encode(protocol, ColorProfile.TRUECOLOR);
    }

    /**
     * Encodes the fallback with the requested color profile; native raster data retains its colors.
     *
     * @param protocol the selected graphics protocol
     * @param profile the color profile used for output
     * @return encodes the fallback with the requested color profile; native raster data retains its colors
     */
    public String encode(ImageProtocol protocol, ColorProfile profile) {
        checkSize(width, height);
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(profile, "profile");
        return switch (protocol) {
            case KITTY -> ImageCodecs.kitty(this);
            case ITERM2 -> ImageCodecs.iterm2(this);
            case SIXEL -> ImageCodecs.sixel(this);
            case HALF_BLOCK -> textOutput(profile);
        };
    }

    private String textOutput(ColorProfile profile) {
        StringBuilder out = new StringBuilder();
        for (StyledText.Span span : halfBlocks().spans()) {
            out.append(AnsiEncoder.sgr(span.style(), profile))
                    .append(span.text().replace("\n", "\r\n"));
            if (out.length() > MAX_OUTPUT - AnsiEncoder.RESET.length()) {
                throw new IllegalArgumentException("half-block output exceeds 64 Mi characters; downsample the image");
            }
        }
        out.append(AnsiEncoder.RESET);
        return out.toString();
    }

    /**
     * Selects from explicit evidence and writes at the current cursor, then flushes.
     * Session NO_COLOR and missing color support force an uncolored text fallback.
     *
     * @param session the terminal session used for input and output
     * @param capabilities the detected terminal facts
     * @return the format actually written
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    public ImageProtocol write(TerminalSession session, ImageCapabilities capabilities) throws TerminalException {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(capabilities, "capabilities");
        ColorProfile profile = ColorProfile.negotiated(session.capabilities().color(), System.getenv("NO_COLOR"));
        ImageProtocol protocol = profile == ColorProfile.NONE ? ImageProtocol.HALF_BLOCK : capabilities.protocol();
        session.write(encode(protocol, profile));
        session.flush();
        return protocol;
    }

    /**
     * Detects conservative image support for this session; unknown terminals receive half blocks.
     *
     * @param session the terminal session used for input and output
     * @return detects conservative image support for this session; unknown terminals receive half blocks
     * @throws io.github.dhoard.braxton.terminal.TerminalException if the terminal operation fails
     */
    public ImageProtocol write(TerminalSession session) throws TerminalException {
        Objects.requireNonNull(session, "session");
        return write(session, ImageCapabilities.detect(session.capabilities().term(), System.getenv()));
    }
}

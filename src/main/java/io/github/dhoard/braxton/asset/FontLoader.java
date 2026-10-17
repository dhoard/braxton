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

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/**
 * Loads the bundled FIGlet fonts from the classpath.
 *
 * <p>Loading is an explicit effect: constructing a view never touches the filesystem or classpath.
 * Loaded fonts are immutable and may be cached by the caller.</p>
 */
public final class FontLoader {

    /** The ten fonts shipped in {@code src/main/resources/fonts}. */
    public enum Family {
        BIG("big"),
        BLOCK("block"),
        DIGITAL("digital"),
        LEAN("lean"),
        SHADOW("shadow"),
        SLANT("slant"),
        SMALL("small"),
        SMSHADOW("smshadow"),
        STANDARD("standard"),
        TERM("term");

        private final String resource;

        Family(String resource) {
            this.resource = resource;
        }

        public String resource() {
            return "/fonts/" + resource + ".flf";
        }
    }

    private static final Map<Family, FigletFont> CACHE = new EnumMap<>(Family.class);

    private FontLoader() {
    }

    /** Loads (and caches) a bundled font. */
    public static synchronized FigletFont load(Family family) throws IOException {
        Family key = family == null ? Family.STANDARD : family;
        FigletFont cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try (InputStream stream = FontLoader.class.getResourceAsStream(key.resource())) {
            if (stream == null) {
                throw new IOException("font resource missing: " + key.resource());
            }
            FigletFont font = FigletFont.parse(stream, key.resource());
            CACHE.put(key, font);
            return font;
        }
    }

    /** Loads a font from an arbitrary stream, e.g. a test fixture. */
    public static FigletFont load(InputStream stream, String name) throws IOException {
        return FigletFont.parse(stream, name);
    }
}

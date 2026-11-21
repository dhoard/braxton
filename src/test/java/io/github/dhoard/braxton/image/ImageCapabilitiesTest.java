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

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageCapabilitiesTest {
    @Test
    void unknownAndMultiplexedTerminalsFallBack() {
        for (String term : new String[] {"", "dumb", "xterm-256color", "unknown", "screen-256color", "tmux-256color"}) {
            assertThat(ImageCapabilities.detect(Map.of("TERM", term)).protocol())
                    .isEqualTo(ImageProtocol.HALF_BLOCK);
        }
        assertThat(ImageCapabilities.detect(Map.of("TERM", "xterm-kitty", "TMUX", "socket"))
                        .protocol())
                .isEqualTo(ImageProtocol.HALF_BLOCK);
        assertThat(ImageCapabilities.detect(Map.of("TERM", "xterm-kitty", "STY", "session"))
                        .protocol())
                .isEqualTo(ImageProtocol.HALF_BLOCK);
        assertThat(ImageCapabilities.detect(null, Map.of())).isEqualTo(ImageCapabilities.NONE);
        Map<String, String> absent = new java.util.HashMap<>();
        absent.put("TERM_PROGRAM", null);
        absent.put("TMUX", null);
        absent.put("STY", null);
        assertThat(ImageCapabilities.detect("xterm-256color", absent)).isEqualTo(ImageCapabilities.NONE);
        assertThatThrownBy(() -> ImageCapabilities.detect(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void onlyRecognizedIdentitiesEnableNativeFormats() {
        assertThat(ImageCapabilities.detect(Map.of("TERM", "xterm-kitty")).protocol())
                .isEqualTo(ImageProtocol.KITTY);
        assertThat(ImageCapabilities.detect(Map.of("TERM", "KITTY")).protocol()).isEqualTo(ImageProtocol.KITTY);
        assertThat(ImageCapabilities.detect(Map.of("TERM", "xterm-256color", "TERM_PROGRAM", "iTerm.app"))
                        .protocol())
                .isEqualTo(ImageProtocol.ITERM2);
        assertThat(ImageCapabilities.detect("dumb", Map.of("TERM_PROGRAM", "iTerm.app")))
                .isEqualTo(ImageCapabilities.NONE);
    }

    @Test
    void explicitSupportHasDeterministicPrecedence() {
        assertThat(new ImageCapabilities(true, true, true).protocol()).isEqualTo(ImageProtocol.KITTY);
        assertThat(new ImageCapabilities(false, true, true).protocol()).isEqualTo(ImageProtocol.ITERM2);
        assertThat(new ImageCapabilities(false, false, true).protocol()).isEqualTo(ImageProtocol.SIXEL);
    }

    @Test
    void sixelRequiresACompletePrimaryAttributesFeature() {
        assertThat(ImageCapabilities.NONE
                        .withDeviceAttributes("\u001b[?64;1;4;22c")
                        .protocol())
                .isEqualTo(ImageProtocol.SIXEL);
        assertThat(ImageCapabilities.NONE
                        .withDeviceAttributes("\u001b[?64;004c")
                        .sixel())
                .isTrue();
        for (String reply : new String[] {
            "",
            "4",
            "\u001b[?4c",
            "\u001b[?64;14c",
            "\u001b[?64;4",
            "\u001b[>64;4c",
            "\u001b[?64;4cBAD",
            "0".repeat(257)
        }) {
            assertThat(ImageCapabilities.NONE.withDeviceAttributes(reply)).isEqualTo(ImageCapabilities.NONE);
        }
        assertThat(ImageCapabilities.NONE.withDeviceAttributes(null)).isEqualTo(ImageCapabilities.NONE);
        ImageCapabilities known = new ImageCapabilities(true, false, false);
        assertThat(known.withDeviceAttributes("\u001b[?64;4c")).isEqualTo(new ImageCapabilities(true, false, true));
    }
}

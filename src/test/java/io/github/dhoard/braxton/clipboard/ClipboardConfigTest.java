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

package io.github.dhoard.braxton.clipboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Configuration from system properties degrades to defaults instead of failing startup. */
class ClipboardConfigTest {

    private static String withProperty(String name, String value, java.util.function.Supplier<String> body) {
        String previous = System.getProperty(name);
        try {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
            return body.get();
        } finally {
            if (previous == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, previous);
            }
        }
    }

    @Test
    void defaultsAndBuilderClampTheirBounds() {
        ClipboardConfig defaults = ClipboardConfig.defaults();
        assertThat(defaults.osc52Enabled()).isTrue();
        assertThat(defaults.maxPayloadBytes()).isEqualTo(ClipboardConfig.DEFAULT_MAX_PAYLOAD_BYTES);
        assertThat(defaults.maxInternalChars()).isEqualTo(ClipboardConfig.DEFAULT_MAX_INTERNAL_CHARS);
        assertThat(defaults.tmuxStrategy()).isEqualTo(ClipboardConfig.DEFAULT_TMUX_STRATEGY);
        ClipboardConfig clamped = ClipboardConfig.builder().maxPayloadBytes(0).maxInternalChars(-5)
                .tmuxStrategy(null).build();
        assertThat(clamped.maxPayloadBytes()).isEqualTo(1);
        assertThat(clamped.maxInternalChars()).isEqualTo(1);
        assertThat(clamped.tmuxStrategy()).isEqualTo(ClipboardConfig.DEFAULT_TMUX_STRATEGY);
        ClipboardConfig roundTrip = defaults.toBuilder().osc52Enabled(false).build();
        assertThat(roundTrip.osc52Enabled()).isFalse();
        assertThat(roundTrip.maxPayloadBytes()).isEqualTo(defaults.maxPayloadBytes());
    }

    @Test
    void invalidPropertiesFallBackToDefaults() {
        assertThat(withProperty("braxton.clipboard.maxPayloadBytes", "abc",
                () -> Integer.toString(ClipboardConfig.fromSystemProperties().maxPayloadBytes())))
                .isEqualTo(Integer.toString(ClipboardConfig.DEFAULT_MAX_PAYLOAD_BYTES));
        assertThat(withProperty("braxton.clipboard.maxPayloadBytes", "0",
                () -> Integer.toString(ClipboardConfig.fromSystemProperties().maxPayloadBytes())))
                .isEqualTo("1");
        assertThat(withProperty("braxton.clipboard.enabled", "nonsense",
                () -> Boolean.toString(ClipboardConfig.fromSystemProperties().osc52Enabled())))
                .isEqualTo("true");
        assertThat(withProperty("braxton.clipboard.enabled", "false",
                () -> Boolean.toString(ClipboardConfig.fromSystemProperties().osc52Enabled())))
                .isEqualTo("false");
        assertThat(withProperty("braxton.clipboard.tmux", "direct",
                () -> ClipboardConfig.fromSystemProperties().tmuxStrategy().name()))
                .isEqualTo("DIRECT");
        assertThat(withProperty("braxton.clipboard.tmux", "nope",
                () -> ClipboardConfig.fromSystemProperties().tmuxStrategy().name()))
                .isEqualTo(ClipboardConfig.DEFAULT_TMUX_STRATEGY.name());
    }

    @Test
    void pasteModeAndTmuxStrategyParseCaseInsensitivelyWithFallbacks() {
        assertThat(PasteMode.parse(" aggressive ", PasteMode.NORMAL)).isEqualTo(PasteMode.AGGRESSIVE);
        assertThat(PasteMode.parse("other", PasteMode.NORMAL)).isEqualTo(PasteMode.NORMAL);
        assertThat(PasteMode.parse(null, PasteMode.AGGRESSIVE)).isEqualTo(PasteMode.AGGRESSIVE);
        assertThat(TmuxStrategy.parse("Tmux_Passthrough", TmuxStrategy.AUTO))
                .isEqualTo(TmuxStrategy.TMUX_PASSTHROUGH);
        assertThat(TmuxStrategy.parse("", TmuxStrategy.OFF)).isEqualTo(TmuxStrategy.OFF);
        assertThat(withProperty("braxton.paste.mode", "aggressive",
                () -> PasteMode.configuredDefault().name())).isEqualTo("AGGRESSIVE");
        assertThat(withProperty("braxton.paste.mode", "junk",
                () -> PasteMode.configuredDefault().name())).isEqualTo("NORMAL");
    }
}

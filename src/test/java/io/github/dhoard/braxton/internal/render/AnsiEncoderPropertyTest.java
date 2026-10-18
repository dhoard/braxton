package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Style;

/** A styled row always closes its styling, whatever the mix of cells. */
class AnsiEncoderPropertyTest {

    @Test
    void everyStyledRowEndsItsStylingAndLinks() {
        Style[] styles = {Style.NONE, Style.NONE.withBold(), Style.NONE.withInvert(),
                Style.NONE.withUnderline()};
        for (Style first : styles) {
            for (Style second : styles) {
                Screen screen = new Screen(1, 4);
                screen.put(0, 0, "a", first, "https://x", 1);
                screen.put(0, 1, "b", second, null, 1);
                screen.put(0, 2, "c", Style.NONE, null, 1);
                String row = AnsiEncoder.encodeRow(screen, 0);
                int resets = row.split("\u001B\\[0m", -1).length - 1;
                if (!first.isPlain() || !second.isPlain()) {
                    assertThat(resets).as("styled cells must be reset").isGreaterThanOrEqualTo(1);
                }
                assertThat(row).endsWith("\u001B[0m");
                assertThat(row.split("\u001B\\]8;;\u001B\\\\", -1).length - 1)
                        .as("the link opens and closes").isEqualTo(2);
            }
        }
    }
}

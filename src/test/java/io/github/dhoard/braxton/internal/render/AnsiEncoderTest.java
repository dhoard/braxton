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

package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.Views;

/** The encoder is the only place escape sequences are produced. */
class AnsiEncoderTest {

    @Test
    void paintsBeforeClearingOnlyTheUnusedSuffix() {
        Screen screen = new Screen(1, 6);
        screen.put(0, 0, "n", Style.NONE, null, 1);
        screen.put(0, 1, "e", Style.NONE, null, 1);
        screen.put(0, 2, "w", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true))
                .isEqualTo("\u001B[1;1Hnew\u001B[1;4H\u001B[0m\u001B[0K");
    }

    @Test
    void anEmptyRowResetsStyleBeforeClearingAndAZeroWidthRowWritesNothing() {
        assertThat(AnsiEncoder.encodeRow(new Screen(1, 6), 0, true))
                .isEqualTo("\u001B[1;1H\u001B[0m\u001B[2K");
        assertThat(AnsiEncoder.frame(new Screen(2, 0), true)).isEmpty();
    }

    @Test
    void aFullRowDoesNotClearItsNewContent() {
        Screen screen = new Screen(1, 2);
        screen.put(0, 0, "a", Style.NONE, null, 1);
        screen.put(0, 1, "b", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true)).isEqualTo("\u001B[1;1Hab");
    }

    @Test
    void suffixClearingStartsAfterTheWholeWideGlyph() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "界", Style.NONE, null, 2);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true))
                .isEqualTo("\u001B[1;1H界\u001B[1;3H\u001B[0m\u001B[0K");
        Screen full = new Screen(1, 2);
        full.put(0, 0, "界", Style.NONE, null, 2);
        assertThat(AnsiEncoder.encodeRow(full, 0, true)).isEqualTo("\u001B[1;1H界");
    }

    @Test
    void fullWidthSpillRepairPropagatesThroughConsecutiveFullRows() {
        Screen previous = new Screen(3, 2);
        Screen next = new Screen(3, 2);
        for (Screen screen : new Screen[] {previous, next}) {
            screen.put(1, 0, "b", Style.NONE, null, 1);
            screen.put(1, 1, "c", Style.NONE, null, 1);
            screen.put(2, 0, "d", Style.NONE, null, 1);
        }
        next.put(0, 1, "x", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(previous, next, true))
                .isEqualTo(AnsiEncoder.frame(next, true));
        assertThat(AnsiEncoder.diff(next, next, true)).isEmpty();
    }

    @Test
    void styledLinkedContentClosesBeforeTheNeutralSuffixErase() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "x", Style.bg(Colors.BLACK).withBold(), "https://example.com", 1);
        String row = AnsiEncoder.encodeRow(screen, 0, true);
        assertThat(row).doesNotContain("\u001B[2K");
        assertThat(row).endsWith("x\u001B]8;;\u001B\\\u001B[0m\u001B[1;2H\u001B[0m\u001B[0K");
    }

    @Test
    void fullWidthBackgroundSpacesAreContentNotAnUnusedSuffix() {
        Screen screen = new Screen(1, 2);
        screen.put(0, 0, " ", Style.bg(Colors.BLACK), null, 1);
        screen.put(0, 1, " ", Style.bg(Colors.BLACK), null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true))
                .contains("48;2;0;0;0m  ").endsWith("\u001B[0m")
                .doesNotContain("\u001B[0K", "\u001B[2K", "\u001B[1;3H");
    }

    @Test
    void sgrEncodesTrueColourAndAttributes() {
        String sgr = AnsiEncoder.sgr(Style.fg(Colors.ERROR_400).withBold()
                .withBackground(Colors.BLACK));
        assertThat(sgr).contains("1;");
        assertThat(sgr).contains("38;2;240;68;56");
        assertThat(sgr).contains("48;2;0;0;0");
        assertThat(AnsiEncoder.sgr(Style.NONE)).isEmpty();
    }

    @Test
    void sgrEncodesEveryTextAttribute() {
        assertThat(AnsiEncoder.sgr(Style.NONE.withItalic())).isEqualTo("\u001B[3m");
        assertThat(AnsiEncoder.sgr(Style.NONE.withHidden())).isEqualTo("\u001B[8m");
        assertThat(AnsiEncoder.sgr(Style.NONE.withStrikethrough())).isEqualTo("\u001B[9m");
    }

    @Test
    void rowsAreAddressedAbsolutelyAndTheirUnusedSuffixIsCleared() {
        Screen screen = new Screen(2, 4);
        screen.put(1, 0, "x", Style.NONE, null, 1);
        String row = AnsiEncoder.encodeRow(screen, 1, true);
        assertThat(row).isEqualTo("\u001B[2;1Hx\u001B[2;2H\u001B[0m\u001B[0K");
    }

    @Test
    void diffsOnlyChangedRows() {
        Screen first = new Screen(2, 3);
        first.put(0, 0, "a", Style.NONE, null, 1);
        Screen second = new Screen(2, 3);
        second.put(0, 0, "a", Style.NONE, null, 1);
        second.put(1, 0, "b", Style.NONE, null, 1);
        String diff = AnsiEncoder.diff(first, second, true);
        assertThat(diff).contains("\u001B[2;1H");
        assertThat(diff).doesNotContain("\u001B[1;1H");
    }

    @Test
    void aResizedScreenIsRepaintedInFullInsteadOfDiffed() {
        // Cell-by-cell diffs only make sense for the same geometry; a resize must repaint every row
        // of the new screen or stale columns survive.
        Screen previous = new Screen(2, 4);
        previous.put(0, 0, "x", Style.NONE, null, 1);
        Screen resized = new Screen(3, 6);
        resized.put(0, 0, "y", Style.NONE, null, 1);
        String diff = AnsiEncoder.diff(previous, resized, true);
        assertThat(diff).isEqualTo(AnsiEncoder.frame(resized, true));
        assertThat(diff).contains("\u001B[3;1H");
    }

    /**
     * A row that writes its last column repaints the row below it.
     *
     * <p>A terminal that renders a cluster wider than the grid charged it finishes such a row past
     * the terminal's right edge, and the tail it wraps lands at the start of the next row - a row
     * that owns none of those cells. That row's own cells did not change, so a diff that only wrote
     * changed rows would leave the wrapped cells there for as long as the frame stayed still. The
     * row after a full row is written too, which clears them in the same batch; a row that stops
     * short of the edge cannot spill, so the row after it keeps the diff's silence.</p>
     */
    @Test
    void aRowThatReachesTheLastColumnRepaintsTheRowBelowIt() {
        Screen previous = new Screen(3, 4);
        Screen next = new Screen(3, 4);
        for (Screen screen : java.util.List.of(previous, next)) {
            screen.put(1, 0, "m", Style.NONE, null, 1);
            screen.put(2, 0, "z", Style.NONE, null, 1);
        }
        for (int col = 0; col < 4; col++) {
            previous.put(0, col, "f", Style.NONE, null, 1);
            next.put(0, col, "g", Style.NONE, null, 1);
        }
        String diff = AnsiEncoder.diff(previous, next, false);
        assertThat(diff)
                .as("the changed full-width row and the row it can spill into")
                .contains("\u001B[1;1H", "\u001B[2;1H")
                .doesNotContain("\u001B[3;1H");

        Screen narrowPrevious = new Screen(3, 4);
        Screen narrowNext = new Screen(3, 4);
        for (Screen screen : java.util.List.of(narrowPrevious, narrowNext)) {
            screen.put(1, 0, "m", Style.NONE, null, 1);
            screen.put(2, 0, "z", Style.NONE, null, 1);
        }
        narrowPrevious.put(0, 0, "ab", Style.NONE, null, 1);
        narrowNext.put(0, 0, "ac", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(narrowPrevious, narrowNext, false))
                .as("a changed row that stops short of the edge spills nowhere")
                .contains("\u001B[1;1H")
                .doesNotContain("\u001B[2;1H", "\u001B[3;1H");
    }

    @Test
    void aContinuationInsideThePaintedPrefixIsSkippedRatherThanPaintedAgain() {
        Screen screen = new Screen(1, 6);
        screen.put(0, 0, "界", Style.NONE, null, 2);
        screen.put(0, 2, "a", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true))
                .isEqualTo("\u001B[1;1H界a\u001B[1;4H\u001B[0m\u001B[0K");
    }

    @Test
    void aGapClosesAnOpenLinkBeforeItsBlankColumn() {
        Screen screen = new Screen(1, 6);
        screen.put(0, 0, "a", Style.NONE, "https://example.com", 1);
        screen.put(0, 2, "b", Style.NONE, "https://example.com", 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true))
                .isEqualTo("\u001B[1;1H\u001B]8;;https://example.com\u001B\\a\u001B]8;;\u001B\\ "
                        + "\u001B]8;;https://example.com\u001B\\b\u001B]8;;\u001B\\"
                        + "\u001B[1;4H\u001B[0m\u001B[0K");
    }

    @Test
    void aDifferentLinkClosesThePreviousOneBeforeOpening() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "a", Style.NONE, "https://one", 1);
        screen.put(0, 1, "b", Style.NONE, "https://two", 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true))
                .contains("a\u001B]8;;\u001B\\\u001B]8;;https://two\u001B\\b");
    }

    @Test
    void diffDetectsAContinuationChangeWhenTheCellsThemselvesAreEqual() {
        Screen previous = new Screen(1, 2);
        previous.put(0, 0, "界", Style.NONE, null, 1);
        Screen next = new Screen(1, 2);
        next.put(0, 0, "界", Style.NONE, null, 2);
        assertThat(AnsiEncoder.diff(previous, next, true)).isEqualTo("\u001B[1;1H界");
    }

    @Test
    void aRowThatBecomesEmptyIsClearedAndSpillsNowhere() {
        Screen previous = new Screen(2, 3);
        previous.put(0, 0, "a", Style.NONE, null, 1);
        Screen next = new Screen(2, 3);
        assertThat(AnsiEncoder.diff(previous, next, true))
                .isEqualTo("\u001B[1;1H\u001B[0m\u001B[2K");
    }

    @Test
    void geometryChangesPaintTheWholeFrame() {
        Screen previous = new Screen(2, 4);
        previous.put(0, 0, "a", Style.NONE, null, 1);
        Screen taller = new Screen(3, 4);
        taller.put(2, 0, "b", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(previous, taller, true))
                .isEqualTo(AnsiEncoder.frame(taller, true))
                .contains("\u001B[1;1H", "\u001B[2;1H", "\u001B[3;1H");
        Screen wider = new Screen(2, 5);
        wider.put(0, 0, "a", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(previous, wider, true))
                .isEqualTo(AnsiEncoder.frame(wider, true));
    }

    @Test
    void linksAreOpenedAndClosedOncePerRun() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "a", Style.NONE, "https://example.com", 1);
        screen.put(0, 1, "b", Style.NONE, "https://example.com", 1);
        String row = AnsiEncoder.encodeRow(screen, 0, true);
        assertThat(row.split("\u001B]8;;https://example.com", -1)).hasSize(2);
        assertThat(row).contains("\u001B\\");
    }

    @Test
    void rawScreenHyperlinksCannotCarryC1ControlsIntoOsc8() {
        for (char control = 0x80; control <= 0x9f; control++) {
            Screen screen = new Screen(1, 2);
            screen.put(0, 0, "x", Style.NONE, "https://example.com/a" + control + "b", 1);
            String encoded = AnsiEncoder.encodeRow(screen, 0, true);
            assertThat(encoded).doesNotContain(String.valueOf(control));
            assertThat(encoded).contains("https://example.com/ab");
        }
    }

    @Test
    void dataCannotInjectEscapesBecauseContentIsSanitizedBeforeEncoding() {
        var content = StyledText.of("x\u001B[31my", Style.NONE);
        var screen = new io.github.dhoard.braxton.internal.render.LayoutEngine()
                .layout(Views.text(content), new Rect(0, 0, 1, 10)).screen();
        String row = AnsiEncoder.encodeRow(screen, 0, true);
        assertThat(row).contains("x[31my");
        assertThat(row).doesNotContain("\u001B[31m");
    }

    @Test
    void interiorBlankCellsAreEncodedSoLaterContentKeepsItsColumn() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 1, "b", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true))
                .isEqualTo("\u001B[1;1H b\u001B[1;3H\u001B[0m\u001B[0K");
    }

    @Test
    void aBlankCellAfterStyledContentDoesNotInheritItsStyle() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "a", Style.bg(io.github.dhoard.braxton.style.Colors.BLACK), null, 1);
        screen.put(0, 2, "c", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true))
                .endsWith("a\u001B[0m c\u001B[1;4H\u001B[0m\u001B[0K");
    }

    @Test
    void trailingBlankCellsAreNotEncoded() {
        Screen screen = new Screen(1, 6);
        screen.put(0, 0, "a", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true))
                .isEqualTo("\u001B[1;1Ha\u001B[1;2H\u001B[0m\u001B[0K");
    }

    @Test
    void aFullFrameWritesEveryRowInOrder() {
        Screen screen = new Screen(2, 2);
        screen.put(0, 0, "a", Style.NONE, null, 1);
        screen.put(1, 0, "b", Style.NONE, null, 1);
        String frame = AnsiEncoder.frame(screen, true);
        assertThat(frame.indexOf("\u001B[1;1H")).isLessThan(frame.indexOf("\u001B[2;1H"));
    }
    @Test
    void disablingColorKeepsTextAndOtherAttributesWithoutEmittingRgbControls() {
        Screen screen = new Screen(1,3);
        screen.put(0,0,"x",io.github.dhoard.braxton.style.Style.NONE.withBold()
                .withForeground(new io.github.dhoard.braxton.style.Color(255,0,0))
                .withBackground(new io.github.dhoard.braxton.style.Color(0,0,255)),null,1);
        String plain = AnsiEncoder.frame(screen,false);
        assertThat(plain).contains("x", "\u001b[1m").doesNotContain("38;2", "48;2");
        assertThat(AnsiEncoder.frame(screen,true)).contains("38;2", "48;2");
    }
}

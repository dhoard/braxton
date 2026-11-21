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

package io.github.dhoard.braxton.internal.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import org.junit.jupiter.api.Test;

/**
 * The encoder is the only place escape sequences are produced.
 */
class AnsiEncoderTest {

    @Test
    void presentationSequencesRefuseAMissingValueAndCarryAnEmptyOne() {
        assertThatThrownBy(() -> AnsiEncoder.defaultColor(10, null))
                .as("a default color needs a color")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnsiEncoder.defaultColor(9, Color.WHITE))
                .as("only the three configurable colors have a default form")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10, 11 or 12");
        assertThatThrownBy(() -> AnsiEncoder.progress(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnsiEncoder.programStatus(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(AnsiEncoder.title(null))
                .as("no title is an empty OSC 2 payload, which clears it")
                .isEqualTo("\u001B]2;\u001B\\");
        assertThat(AnsiEncoder.title("")).isEqualTo(AnsiEncoder.title(null));
    }

    @Test
    void relativeMovesWriteNothingToMoveNowhere() {
        assertThat(AnsiEncoder.moveUp(3)).isEqualTo("\u001B[3A");
        assertThat(AnsiEncoder.moveUp(0))
                .as("a move of no lines is no sequence")
                .isEmpty();
        assertThat(AnsiEncoder.moveUp(-2)).isEmpty();
        assertThat(AnsiEncoder.moveDown(2)).isEqualTo("\u001B[2B");
        assertThat(AnsiEncoder.moveDown(0))
                .as("a move of no lines is no sequence")
                .isEmpty();
        assertThat(AnsiEncoder.moveDown(-1)).isEmpty();
        assertThat(AnsiEncoder.moveToColumn(4)).isEqualTo("\u001B[5G");
        assertThat(AnsiEncoder.moveToColumn(0))
                .as("the first column needs no sequence")
                .isEmpty();
        assertThat(AnsiEncoder.moveToColumn(-1)).isEmpty();
    }

    @Test
    void rowContentCarriesTheStyledCellsOnly() {
        Screen empty = new Screen(1, 4);
        assertThat(AnsiEncoder.rowContent(empty, 0, ColorProfile.TRUECOLOR))
                .as("a row that paints nothing has no content")
                .isEmpty();
        assertThat(AnsiEncoder.rowContent(new Screen(1, 0), 0, ColorProfile.TRUECOLOR))
                .as("a zero-width row has no content")
                .isEmpty();
        Screen styled = new Screen(1, 4);
        styled.put(0, 0, "a", Style.NONE.withUnderline(), null, 1);
        styled.put(0, 2, "b", Style.NONE, null, 1);
        assertThat(AnsiEncoder.rowContent(styled, 0, ColorProfile.TRUECOLOR))
                .as("the gap between two painted cells stays a space, and the style is reset")
                .isEqualTo("\u001B[4ma\u001B[0m b");
        Screen wide = new Screen(1, 2);
        wide.put(0, 0, "界", Style.NONE, null, 2);
        assertThat(AnsiEncoder.rowContent(wide, 0, ColorProfile.TRUECOLOR))
                .as("a wide glyph is painted once, with its continuation column left implicit")
                .isEqualTo("界");
        assertThat(AnsiEncoder.paintedEnd(wide, 0))
                .as("the painted extent covers the continuation column")
                .isEqualTo(2);
        assertThat(AnsiEncoder.paintedEnd(empty, 0)).isZero();
    }

    @Test
    void paintsBeforeClearingOnlyTheUnusedSuffix() {
        Screen screen = new Screen(1, 6);
        screen.put(0, 0, "n", Style.NONE, null, 1);
        screen.put(0, 1, "e", Style.NONE, null, 1);
        screen.put(0, 2, "w", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true)).isEqualTo("\u001B[1;1Hnew\u001B[1;4H\u001B[0m\u001B[0K");
    }

    @Test
    void anEmptyRowResetsStyleBeforeClearingAndAZeroWidthRowWritesNothing() {
        assertThat(AnsiEncoder.encodeRow(new Screen(1, 6), 0, true)).isEqualTo("\u001B[1;1H\u001B[0m\u001B[2K");
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
        assertThat(AnsiEncoder.encodeRow(screen, 0, true)).isEqualTo("\u001B[1;1H界\u001B[1;3H\u001B[0m\u001B[0K");
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
        String diff = AnsiEncoder.diff(previous, next, true);
        assertThat(diff)
                .as("the one changed cell, and then the two rows a full row can spill into")
                .contains("\u001B[1;2H")
                .contains("\u001B[2;1H", "\u001B[3;1H");
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
                .contains("48;2;0;0;0m  ")
                .endsWith("\u001B[0m")
                .doesNotContain("\u001B[0K", "\u001B[2K", "\u001B[1;3H");
    }

    @Test
    void sgrEncodesTrueColourAndAttributes() {
        String sgr = AnsiEncoder.sgr(Style.fg(Colors.ERROR_400).withBold().withBackground(Colors.BLACK));
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
        assertThat(AnsiEncoder.encodeRow(screen, 0, true)).isEqualTo("\u001B[1;1H界a\u001B[1;4H\u001B[0m\u001B[0K");
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
        assertThat(AnsiEncoder.diff(previous, next, true)).isEqualTo("\u001B[1;1H\u001B[0m\u001B[2K");
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
        assertThat(AnsiEncoder.diff(previous, wider, true)).isEqualTo(AnsiEncoder.frame(wider, true));
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
        io.github.dhoard.braxton.internal.render.Screen screen =
                new io.github.dhoard.braxton.internal.render.Screen(1, 10);
        io.github.dhoard.braxton.ui.ElementTree.paint(
                io.github.dhoard.braxton.ui.ElementTree.layout(
                        io.github.dhoard.braxton.widget.Elements.text(content), new Rect(0, 0, 1, 10)),
                screen);
        String row = AnsiEncoder.encodeRow(screen, 0, true);
        assertThat(row).contains("x[31my");
        assertThat(row).doesNotContain("\u001B[31m");
    }

    @Test
    void interiorBlankCellsAreEncodedSoLaterContentKeepsItsColumn() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 1, "b", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true)).isEqualTo("\u001B[1;1H b\u001B[1;3H\u001B[0m\u001B[0K");
    }

    @Test
    void aBlankCellAfterStyledContentDoesNotInheritItsStyle() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "a", Style.bg(io.github.dhoard.braxton.style.Colors.BLACK), null, 1);
        screen.put(0, 2, "c", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true)).endsWith("a\u001B[0m c\u001B[1;4H\u001B[0m\u001B[0K");
    }

    @Test
    void trailingBlankCellsAreNotEncoded() {
        Screen screen = new Screen(1, 6);
        screen.put(0, 0, "a", Style.NONE, null, 1);
        assertThat(AnsiEncoder.encodeRow(screen, 0, true)).isEqualTo("\u001B[1;1Ha\u001B[1;2H\u001B[0m\u001B[0K");
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
        Screen screen = new Screen(1, 3);
        screen.put(
                0,
                0,
                "x",
                io.github.dhoard.braxton.style.Style.NONE
                        .withBold()
                        .withForeground(new io.github.dhoard.braxton.style.Color(255, 0, 0))
                        .withBackground(new io.github.dhoard.braxton.style.Color(0, 0, 255)),
                null,
                1);
        String plain = AnsiEncoder.frame(screen, false);
        assertThat(plain).contains("x", "\u001b[1m").doesNotContain("38;2", "48;2");
        assertThat(AnsiEncoder.frame(screen, true)).contains("38;2", "48;2");
    }

    @Test
    void sgrNamesAColourInTheProfileItIsEncodedFor() {
        Style style = Style.fg(Colors.ERROR_400).withBold().withBackground(Colors.BLUE_400);
        assertThat(AnsiEncoder.sgr(style, ColorProfile.TRUECOLOR))
                .isEqualTo("\u001B[1;38;2;240;68;56;48;2;46;144;250m");
        assertThat(AnsiEncoder.sgr(style, ColorProfile.ANSI256))
                .as("a 256-color session names palette indices, nearest first")
                .isEqualTo("\u001B[1;38;5;" + Colors.nearestAnsi256(Colors.ERROR_400) + ";48;5;"
                        + Colors.nearestAnsi256(Colors.BLUE_400) + "m");
        assertThat(AnsiEncoder.sgr(style, ColorProfile.ANSI16))
                .as("a 16-color session uses the classic foreground and background codes")
                .isEqualTo("\u001B[1;91;44m");
        assertThat(AnsiEncoder.sgr(style, ColorProfile.NONE))
                .as("losing color keeps the attribute")
                .isEqualTo("\u001B[1m");
        assertThat(AnsiEncoder.sgr(style, ColorProfile.AUTO))
                .as("an unresolved profile never encodes a frame; it encodes as truecolor")
                .isEqualTo(AnsiEncoder.sgr(style, ColorProfile.TRUECOLOR));
        assertThat(AnsiEncoder.sgr(style, null))
                .as("a missing profile is the truecolor one the library has always emitted")
                .isEqualTo(AnsiEncoder.sgr(style, ColorProfile.TRUECOLOR));
        assertThat(AnsiEncoder.sgr(
                        Style.NONE.withBold().withBackground(new Color(0x40, 0x00, 0x00)), ColorProfile.ANSI16))
                .as("a dim background uses the dim background codes")
                .isEqualTo("\u001B[1;41m");
        assertThat(AnsiEncoder.sgr(Style.NONE.withBold().withBackground(Colors.ERROR_400), ColorProfile.ANSI16))
                .as("a bright background uses the bright background codes")
                .isEqualTo("\u001B[1;101m");
        assertThat(AnsiEncoder.sgr(Style.NONE.withBold(), ColorProfile.ANSI256))
                .as("a style with no color asks the palette for nothing")
                .isEqualTo("\u001B[1m");
    }

    @Test
    void aFrameEncodedForANarrowerProfileUsesThatProfilesColourForm() {
        Screen screen = new Screen(1, 2);
        screen.put(0, 0, "x", Style.fg(Colors.ERROR_400), "https://example.com", 1);
        String truecolor = AnsiEncoder.frame(screen, ColorProfile.TRUECOLOR);
        String ansi256 = AnsiEncoder.frame(screen, ColorProfile.ANSI256);
        String plain = AnsiEncoder.frame(screen, ColorProfile.NONE);

        assertThat(truecolor).contains("38;2;240;68;56");
        assertThat(ansi256).contains("38;5;").doesNotContain("38;2;");
        assertThat(plain)
                .as("a session without color still writes the hyperlink")
                .contains("\u001B]8;;https://example.com\u001B\\")
                .doesNotContain("38;2;", "38;5;", "38;");
    }

    @Test
    void theLegacyColourFlagIsTheTruecolorProfile() {
        Screen screen = new Screen(1, 2);
        screen.put(0, 0, "x", Style.fg(Colors.ERROR_400), null, 1);
        assertThat(AnsiEncoder.frame(screen, true)).isEqualTo(AnsiEncoder.frame(screen, ColorProfile.TRUECOLOR));
        assertThat(AnsiEncoder.frame(screen, false)).isEqualTo(AnsiEncoder.frame(screen, ColorProfile.NONE));
        assertThat(AnsiEncoder.encodeRow(screen, 0, false))
                .isEqualTo(AnsiEncoder.encodeRow(screen, 0, ColorProfile.NONE));
        Screen none = new Screen(1, 2);
        none.put(0, 0, "x", Style.NONE, null, 1);
        assertThat(AnsiEncoder.diff(none, none, false)).isEmpty();
        assertThat(AnsiEncoder.diff(null, screen, false)).isEqualTo(AnsiEncoder.frame(screen, ColorProfile.NONE));
    }
}

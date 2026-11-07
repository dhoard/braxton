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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.RenderedScreen;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.ScrollView.Msg;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What the window paints, and what it must not paint. The rows are asserted through the production
 * layout and renderer, so clipping, wide clusters and styling are the ones a terminal receives.
 */
class ScrollViewRenderTest {

    private static final Color RED = new Color(255, 0, 0);

    private static ScrollView pane(int visibleRows, boolean follow) {
        return new ScrollView(new ScrollView.Config(visibleRows, follow));
    }

    private static ScrollView.Model state(ScrollView view, ScrollView.Content content) {
        var init = view.init(new InitContext(null, null));
        return view.update(init.model(), new Msg.ContentChanged(content)).model();
    }

    private static ScrollView.Model state(int visibleRows, boolean follow, String... lines) {
        ScrollView view = pane(visibleRows, follow);
        return state(view, ScrollView.Content.of(
                Arrays.stream(lines).map(StyledText::plain).toList()));
    }

    /**
     * The pane as a parent embeds it: a view mapped to a parent message type, rendered with the
     * context the parent passes. The context is deliberately larger than the pane so a window that
     * read it instead of its own state would paint the wrong rows.
     */
    private static View<String> paneView(ScrollView view, ScrollView.Model state) {
        return view.view(state, new ViewContext(new Viewport(24, 80))).map(message -> "pane");
    }

    private static List<String> rows(View<String> view, int viewportRows, int viewportCols) {
        return new ScreenRenderer().plainLines(view, new Viewport(viewportRows, viewportCols));
    }

    @Test
    void theWindowPaintsExactlyTheRowsItCovers() {
        ScrollView view = pane(3, false);
        ScrollView.Model top = state(3, false, "L0", "L1", "L2", "L3", "L4");

        assertThat(rows(paneView(view, top), 3, 10)).containsExactly("L0", "L1", "L2");

        ScrollView.Model scrolled =
                view.update(top, new Msg.Down(2)).model();
        assertThat(rows(paneView(view, scrolled), 3, 10)).containsExactly("L2", "L3", "L4");
        assertThat(ScrollView.visibleLines(scrolled))
                .extracting(StyledText::plainText)
                .containsExactly("L2", "L3", "L4");
    }

    @Test
    void blankRowsKeepTheirRow() {
        ScrollView view = pane(4, false);
        assertThat(rows(paneView(view, state(4, false, "a", "", "b", "")), 4, 3))
                .containsExactly("a", "", "b", "");
    }

    @Test
    void stylesAndLinksSurviveTheWindow() {
        StyledText styled =
                StyledText.empty()
                        .append(StyledText.of("ab", Style.fg(RED)))
                        .append(StyledText.of("cd", Style.NONE.withBold()));
        ScrollView view = pane(1, false);
        ScrollView.Model state = state(view, ScrollView.Content.of(List.of(styled)));

        RenderedScreen screen =
                new ScreenRenderer()
                        .screen(paneView(view, state), new Viewport(1, 4));

        assertThat(screen.plainLine(0)).isEqualTo("abcd");
        assertThat(screen.cell(0, 0).style()).isEqualTo(Style.fg(RED));
        assertThat(screen.cell(0, 1).style()).isEqualTo(Style.fg(RED));
        assertThat(screen.cell(0, 2).style()).isEqualTo(Style.NONE.withBold());
        assertThat(screen.cell(0, 3).style()).isEqualTo(Style.NONE.withBold());
    }

    @Test
    void aRowWiderThanThePaneIsClippedInsteadOfTakingASecondRow() {
        ScrollView view = pane(3, false);
        ScrollView.Model state = state(3, false, "short", "0123456789", "after");

        assertThat(rows(paneView(view, state), 3, 4))
                .as("the wide row must not push the rows below it out of the window")
                .containsExactly("shor", "0123", "afte");
    }

    @Test
    void wideClustersAreClippedWholeAndNeverSplit() {
        ScrollView view = pane(4, false);
        ScrollView.Model state = state(4, false,
                "\u65E5\u672C\u8A9E", "e\u0301x",
                "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "\uD83D\uDE42z");

        assertThat(rows(paneView(view, state), 4, 4))
                .as("two columns of the wide row fit; a half glyph is never painted")
                .containsExactly("\u65E5\u672C", "e\u0301x",
                        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "\uD83D\uDE42z");
        assertThat(rows(paneView(view, state), 4, 3))
                .as("a wide cluster that does not fit is dropped whole")
                .containsExactly("\u65E5", "e\u0301x",
                        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "\uD83D\uDE42z");
        assertThat(rows(paneView(view, state), 4, 1))
                .as("one column keeps the combining cluster and drops the two-column ones")
                .containsExactly("", "e\u0301", "", "z");
    }

    @Test
    void aTrailingNewlineIsAnEmptyRowThePaneKeeps() {
        // Preparing rows from text is the application's job, and the editor's logical lines are the
        // existing rule for it: a trailing break yields a final empty row, which the pane keeps.
        List<String> prepared = Editor.lines(Editor.Model.of("a\n"));
        assertThat(prepared).containsExactly("a", "");

        ScrollView view = pane(2, false);
        ScrollView.Model state = state(view, ScrollView.Content.of(
                prepared.stream().map(StyledText::plain).toList()));
        assertThat(rows(paneView(view, state), 2, 3)).containsExactly("a", "");
    }

    @Test
    void aBreakInsideARowIsNotASecondRow() {
        ScrollView view = pane(2, false);
        ScrollView.Model state = state(view,
                ScrollView.Content.of(List.of(StyledText.plain("a\nb"), StyledText.plain("c"))));

        assertThat(rows(paneView(view, state), 2, 3))
                .as("a row is one display row; the pane declares it, it does not re-flow it")
                .containsExactly("a", "c");
    }

    @Test
    void lineEndingsArePreparedByTheApplicationAndThePaneNeverRewritesARow() {
        // A row is an application-prepared value: StyledText already drops a bare carriage return
        // as an unrepresentable control character, so an application flattens or normalizes raw
        // text before publishing it. The pane keeps the value it is given, and a break that is
        // still in it is clipped rather than split into a second screen row.
        StyledText normalized = StyledText.plain(LineBreaks.normalize("a\r\nb"));
        assertThat(normalized.plainText()).isEqualTo("a\nb");

        ScrollView.Content content = ScrollView.Content.of(List.of(normalized));
        assertThat(content.line(0)).isSameAs(normalized);
        ScrollView view = pane(1, false);
        assertThat(rows(paneView(view, state(view, content)), 1, 4)).containsExactly("a");
        assertThat(StyledText.plain("a\rb").plainText())
                .as("a bare CR never reaches a row as a break")
                .isEqualTo("ab");
    }

    @Test
    void thePanePaintsNothingOutsideItsRectangle() {
        ScrollView view = pane(2, false);
        // In a row the fixed size is the pane's width in columns; each pane is two rows tall.
        View<String> left = Views.fixed(4, paneView(view, state(2, false, "aaaa", "bbbb")));
        View<String> right = Views.fixed(4, paneView(view, state(2, false, "cccc", "dddd")));
        View<String> both = Views.column(Views.row(left, right), Views.text("footer"));

        assertThat(rows(both, 3, 8)).containsExactly("aaaacccc", "bbbbdddd", "footer");
    }

    @Test
    void aNestedPaneUsesItsAllocatedHeightAndNotTheRootViewport() {
        ScrollView view = pane(3, true);
        ScrollView.Model state = state(3, true, "L0", "L1", "L2", "L3", "L4", "L5", "L6");
        View<String> nested =
                Views.column(Views.fixed(3, paneView(view, state)), Views.text("footer"));

        // The pane's own view context says 24 rows and the root viewport says 8; neither is the
        // pane's height, and neither may decide the window.
        List<String> painted = rows(nested, 8, 10);
        assertThat(painted.subList(0, 4)).containsExactly("L4", "L5", "L6", "footer");
        assertThat(painted.subList(4, 8)).containsOnly("");

        ScrollView.Model paged = view.update(state, new Msg.PageUp()).model();
        assertThat(paged.topLine())
                .as("a page is the pane's height, not the root's")
                .isEqualTo(1);
    }

    @Test
    void aFramedPaneClipsToItsInnerRectangle() {
        ScrollView view = pane(3, false);
        ScrollView.Model state = state(3, false, "aaaa", "bbbb", "cccc");
        View<String> framed =
                Views.fixed(7, Views.border(Border.SINGLE,
                        Views.padding(Insets.uniform(1), paneView(view, state))));

        assertThat(rows(framed, 7, 8))
                .containsExactly("\u250C\u2500\u2500\u2500\u2500\u2500\u2500\u2510",
                        "\u2502      \u2502",
                        "\u2502 aaaa \u2502",
                        "\u2502 bbbb \u2502",
                        "\u2502 cccc \u2502",
                        "\u2502      \u2502",
                        "\u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2518");
    }

    @Test
    void zeroSizedGeometryIsSafe() {
        ScrollView view = pane(0, true);
        ScrollView.Model empty = state(0, true, "a", "b");

        assertThat(rows(paneView(view, empty), 3, 5)).containsOnly("");
        assertThat(rows(paneView(view, state(2, false, "ab", "cd")), 2, 0)).containsOnly("");

        ScrollView view1 = pane(2, false);
        View<String> pane = paneView(view1, state(2, false, "ab", "cd"));
        assertThat(new ScreenRenderer().plainLines(pane, Viewport.EMPTY)).isEmpty();
    }

    @Test
    void identicalStatePaintsAnIdenticalScreenIncludingStyles() {
        StyledText styled =
                StyledText.empty()
                        .append(StyledText.of("label ", Style.NONE.withBold()))
                        .append(StyledText.of("\u65E5\u672C\u8A9E", Style.fg(RED)));
        ScrollView view = pane(3, true);
        ScrollView.Model state = state(view,
                ScrollView.Content.of(List.of(styled, StyledText.plain(""), styled)));

        RenderedScreen first =
                new ScreenRenderer().screen(paneView(view, state), new Viewport(3, 8));
        RenderedScreen second =
                new ScreenRenderer().screen(paneView(view, state), new Viewport(3, 8));

        assertThat(second.plainLines()).isEqualTo(first.plainLines());
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 8; col++) {
                assertThat(second.cell(row, col)).isEqualTo(first.cell(row, col));
            }
        }
    }

    @Test
    void focusChangesRoutingOnlyNotPixels() {
        ScrollView view = pane(2, false);
        ScrollView.Model state = state(2, false, "a", "b");

        List<String> focused =
                rows(view.view(state.withFocus(true), new ViewContext(new Viewport(2, 4)))
                        .map(message -> "pane"), 2, 4);
        List<String> unfocused =
                rows(view.view(state.withFocus(false), new ViewContext(new Viewport(2, 4)))
                        .map(message -> "pane"), 2, 4);

        assertThat(focused).containsExactly("a", "b");
        assertThat(unfocused).isEqualTo(focused);
    }
}

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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.style.StyledText;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Word wrapping and the pane's own literal search.
 */
class TextViewportWordWrapTest {

    private static TextViewport.Config wordWrap(int rows, int columns) {
        return TextViewport.Config.defaults()
                .withMode(TextViewport.Mode.WORD_WRAP)
                .withFollowEnd(false)
                .withRows(rows)
                .withColumns(columns);
    }

    private static TextViewport.Content content(String... lines) {
        List<StyledText> values = new ArrayList<>(lines.length);
        for (String line : lines) {
            values.add(StyledText.plain(line));
        }
        return TextViewport.Content.of(values);
    }

    private static TextViewport.Model model(int rows, int columns, String... lines) {
        return new TextViewport.Model(0, 0, 0, false, true, rows, columns, content(lines), List.of(), -1);
    }

    private static List<String> painted(TextViewport.Model state, TextViewport.Config config) {
        return TextViewport.visible(state, config).stream()
                .map(row -> row.text().plainText())
                .toList();
    }

    @Test
    void wordWrapMovesAWordToTheNextRow() {
        TextViewport.Config config = wordWrap(3, 8);
        TextViewport.Model state = model(3, 8, "alpha beta gamma");

        assertThat(painted(state, config)).containsExactly("alpha", "beta", "gamma");
        assertThat(TextViewport.totalRows(state, config)).isEqualTo(3);
    }

    @Test
    void aWordWiderThanThePaneIsStillCut() {
        TextViewport.Config config = wordWrap(2, 4);
        TextViewport.Model state = model(2, 4, "abcdefghij");

        assertThat(painted(state, config)).containsExactly("abcd", "efgh");
    }

    @Test
    void findIsLiteralNonOverlappingAndPerLine() {
        TextViewport.Content content = content("aaaa", "aAbA", "nothing");

        assertThat(TextViewport.find(content, "aa", false))
                .containsExactly(new TextViewport.Highlight(0, 0, 2), new TextViewport.Highlight(0, 2, 4));
        assertThat(TextViewport.find(content, "aa", true))
                .containsExactly(
                        new TextViewport.Highlight(0, 0, 2),
                        new TextViewport.Highlight(0, 2, 4),
                        new TextViewport.Highlight(1, 0, 2));
        assertThat(TextViewport.find(content, "", false)).isEmpty();
        assertThat(TextViewport.find(content, "zz", false)).isEmpty();
    }

    @Test
    void aFoundRangeIsPublishedAndReached() {
        TextViewport.Config config = wordWrap(2, 8);
        TextViewport view = new TextViewport(config);
        TextViewport.Model state = model(2, 8, "alpha beta gamma", "delta epsilon");
        List<TextViewport.Highlight> matches = TextViewport.find(state.content(), "gamma", false);

        TextViewport.Model published =
                view.update(state, new TextViewport.Msg.Highlights(matches)).model();
        assertThat(published.highlights()).hasSize(1);

        TextViewport.Model reached =
                view.update(published, new TextViewport.Msg.NextMatch()).model();
        assertThat(reached.activeHighlight()).contains(new TextViewport.Highlight(0, 11, 16));
        assertThat(painted(reached, config)).containsExactly("beta", "gamma");
    }

    @Test
    void wordWrapKeepsTheAnchorLogical() {
        TextViewport.Config config = wordWrap(2, 8);
        TextViewport view = new TextViewport(config);
        TextViewport.Model state = model(2, 8, "alpha beta gamma");

        TextViewport.Model moved =
                view.update(state, new TextViewport.Msg.Down(1)).model();
        assertThat(TextViewport.firstVisibleRow(moved, config)).isEqualTo(1);
        assertThat(painted(moved, config)).containsExactly("beta", "gamma");
    }
}

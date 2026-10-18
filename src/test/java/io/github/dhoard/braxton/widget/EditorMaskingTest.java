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

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.TextMeasure;

/** Masked drafts paint one mask per cluster, keep line structure, and never copy. */
class EditorMaskingTest {

    private static final EditorOptions PASSWORD = EditorOptions.defaults()
            .withMasking(EditorOptions.Masking.PASSWORD);
    private static final EditorOptions HIDDEN = EditorOptions.defaults()
            .withMasking(EditorOptions.Masking.HIDDEN);

    @Test
    void aPasswordDraftMasksEachClusterAndKeepsItsLines() {
        EditorState state = EditorState.of("ab\uD83D\uDE00\ncd").withCaret(0);
        var rendered = Editor.render(state, PASSWORD, false);
        assertThat(rendered.plainText().split("\n", -1)).hasSize(2);
        assertThat(rendered.plainText()).doesNotContain("a").doesNotContain("b")
                .contains("\u2022");
        assertThat(TextMeasure.graphemeCount(rendered.plainText())).isEqualTo(6);
        assertThat(state.displayText(PASSWORD).split("\n", -1)).hasSize(2);
        assertThat(state.displayText(PASSWORD)).doesNotContain("a");
    }

    @Test
    void aHiddenDraftRendersNoContentAndNeverCopies() {
        EditorState state = EditorState.of("secret");
        assertThat(Editor.render(state, HIDDEN, false).plainText()).isEmpty();
        assertThat(state.displayText(HIDDEN)).isEmpty();
        assertThat(Editor.copyableText(state, PASSWORD)).isEmpty();
        assertThat(Editor.copyableText(state, HIDDEN)).isEmpty();
        assertThat(Editor.update(state, HIDDEN, new EditorAction.Copy()).intent())
                .isEqualTo(new EditorIntent.Copy(""));
    }
}

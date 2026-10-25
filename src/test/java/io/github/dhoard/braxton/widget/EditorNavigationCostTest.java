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

import java.text.BreakIterator;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.testing.AllocationProbe;

class EditorNavigationCostTest {

    @Test
    void verticalMovementSegmentsTheTargetLineOnce() {
        String line = "界".repeat(1024);
        EditorState state = new EditorState(line + "\n" + line, 1024, 1024, 0);
        assertThat(AllocationProbe.bytes(() -> Editor.update(state, EditorOptions.defaults(),
                new EditorAction.MoveDown()))).isLessThan(2_000_000);
    }

    @Test
    void verticalMovementMatchesWholeDraftBoundaryReference() {
        for (String text : List.of("a\nb", "\n\n", "界🙂\tq\né界\tmore", "a\r\nb",
                "a\n\u0301b", "👨‍👩‍👧\n🇺🇸abc", "a\n\u200Bb")) {
            for (int i = 0; i <= text.length(); i++) {
                for (int preferred : new int[]{0, 1, 3, 10}) {
                    for (int direction : new int[]{-1, 1}) {
                        EditorState state = new EditorState(text, i, i, preferred);
                        EditorAction action = direction < 0
                                ? new EditorAction.MoveUp() : new EditorAction.MoveDown();
                        assertThat(Editor.update(state, EditorOptions.defaults(), action).state())
                                .isEqualTo(reference(state, direction));
                    }
                }
            }
        }
    }

    private static EditorState reference(EditorState state, int direction) {
        String text = state.text();
        int line = state.lineAt(state.caret());
        int start = state.lineStart(line);
        int used = 0;
        for (String cluster : TextMeasure.graphemes(text.substring(start, state.caret()))) {
            used += width(cluster, used);
        }
        int column = state.preferredColumn() > 0 ? state.preferredColumn() : used;
        int target = Math.max(0, Math.min(state.lineCount() - 1, line + direction));
        int position = state.lineStart(target);
        int end = state.lineEnd(target);
        used = 0;
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        while (position < end) {
            int next = Math.min(iterator.following(position), end);
            int width = width(text.substring(position, next), used);
            if (used + width > column) {
                break;
            }
            used += width;
            position = next;
        }
        return new EditorState(text, position, position, column);
    }

    private static int width(String cluster, int used) {
        return "\t".equals(cluster) ? 4 - used % 4
                : TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED);
    }

    @Test
    void lineExtractionRetainsEmptyLinesAndEverySeededBreakForm() {
        // The draft is prepared where it is entered, so a seeded CR/LF pair or lone CR is a break of
        // the draft itself, and no extracted line keeps a stray carriage return.
        assertThat(Editor.lines(EditorState.of(""))).containsExactly("");
        assertThat(Editor.lines(EditorState.of("a\n"))).containsExactly("a", "");
        assertThat(Editor.lines(EditorState.of("\n\n"))).containsExactly("", "", "");
        assertThat(Editor.lines(EditorState.of("a\r\nb"))).containsExactly("a", "b");
        assertThat(Editor.lines(EditorState.of("a\rb"))).containsExactly("a", "b");
        assertThat(Editor.lines(EditorState.of("界\n🙂"))).containsExactly("界", "🙂");
    }
}

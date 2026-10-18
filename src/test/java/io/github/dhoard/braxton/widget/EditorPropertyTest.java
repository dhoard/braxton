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

import java.util.Random;

import org.junit.jupiter.api.Test;

/** Inserting then backspacing the same cluster restores the draft, at every position. */
class EditorPropertyTest {

    @Test
    void anInsertedClusterIsRemovedExactlyByBackspace() {
        String[] clusters = {"a", "\u65E5", "\uD83D\uDE00", "e\u0301", "\uD83D\uDC68\u200D\uD83D\uDC69"};
        Random random = new Random(7L);
        EditorOptions options = EditorOptions.defaults();
        for (int iteration = 0; iteration < 400; iteration++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < random.nextInt(6); i++) {
                text.append(clusters[random.nextInt(clusters.length)]);
            }
            EditorState start = EditorState.of(text.toString());
            int caret = random.nextInt(start.text().length() + 1);
            EditorState placed = start.withCaret(caret);
            int boundary = caret == 0 ? 0 : io.github.dhoard.braxton.style.TextMeasure
                    .previousBoundary(start.text(), caret);
            EditorState atBoundary = start.withCaret(boundary);
            String cluster = clusters[random.nextInt(clusters.length)];
            EditorState inserted = Editor.update(atBoundary, options, new EditorAction.Insert(cluster))
                    .state();
            EditorState removed = Editor.update(inserted, options, new EditorAction.Backspace())
                    .state();
            assertThat(removed.text()).as("insert then backspace at %d", boundary)
                    .isEqualTo(start.text());
            assertThat(removed.caret()).isEqualTo(boundary);
            assertThat(placed.text()).isEqualTo(start.text());
        }
    }
}

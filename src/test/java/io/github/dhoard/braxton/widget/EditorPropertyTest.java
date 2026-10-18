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

import io.github.dhoard.braxton.style.TextMeasure;

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

    /**
     * Every action sequence leaves the state on grapheme boundaries, never grows the draft past its
     * limit, renders exactly the visible line count, and never paints a hidden draft's content.
     */
    @Test
    void arandomActionSequenceKeepsTheStateConsistent() {
        Random random = new Random(11L);
        String[] atoms = {"a", "b", " ", "\n", "\u65E5", "\uD83D\uDE00", "e\u0301", "1"};
        for (int iteration = 0; iteration < 400; iteration++) {
            EditorState state = EditorState.of(randomText(random, atoms));
            int limit = 1 + random.nextInt(12);
            EditorOptions options = EditorOptions.defaults().withMaxLength(limit);
            if (random.nextBoolean()) {
                options = options.withVisibleLines(1 + random.nextInt(3));
            }
            if (random.nextBoolean()) {
                options = options.withMasking(random.nextBoolean()
                        ? EditorOptions.Masking.PASSWORD : EditorOptions.Masking.HIDDEN);
            }
            for (int step = 0; step < 25; step++) {
                EditorState before = state;
                state = Editor.update(state, options, randomAction(random)).state();
                assertThat(TextMeasure.isBoundary(state.text(), state.caret()))
                        .as("caret %d of [%s]", state.caret(), state.text()).isTrue();
                assertThat(TextMeasure.isBoundary(state.text(), state.anchor()))
                        .as("anchor %d of [%s]", state.anchor(), state.text()).isTrue();
                assertThat(state.text().length())
                        .as("draft length against the limit after step %d", step)
                        .isLessThanOrEqualTo(Math.max(before.text().length(), limit));
                String rendered = Editor.render(state, options, true).plainText();
                assertThat(rendered.split("\n", -1))
                        .as("visible rows for [%s]", state.text())
                        .hasSize(Editor.visibleLineCount(state, options));
                if (options.masking() == EditorOptions.Masking.NONE) {
                    for (String row : Editor.render(state, options, false).plainText()
                            .split("\n", -1)) {
                        assertThat(Editor.lines(state))
                                .as("row [%s] of the visible window for [%s]", row, state.text())
                                .contains(row);
                    }
                } else {
                    assertThat(rendered).as("masked draft [%s]", state.text())
                            .doesNotContain("1").doesNotContain("b");
                }
            }
        }
    }

    private static String randomText(Random random, String[] atoms) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < random.nextInt(6); i++) {
            text.append(atoms[random.nextInt(atoms.length)]);
        }
        return text.toString();
    }

    private static EditorAction randomAction(Random random) {
        return switch (random.nextInt(13)) {
            case 0 -> new EditorAction.Insert(random.nextBoolean() ? "x" : "\r\n");
            case 1 -> new EditorAction.Paste(random.nextBoolean() ? "p" : "p\n\nq");
            case 2 -> new EditorAction.Backspace();
            case 3 -> new EditorAction.Delete();
            case 4 -> new EditorAction.MoveLeft();
            case 5 -> new EditorAction.MoveRight();
            case 6 -> new EditorAction.MoveUp();
            case 7 -> new EditorAction.MoveDown();
            case 8 -> new EditorAction.MoveHome();
            case 9 -> new EditorAction.MoveEnd();
            case 10 -> new EditorAction.SelectAll();
            case 11 -> new EditorAction.Newline();
            default -> new EditorAction.Copy();
        };
    }
}

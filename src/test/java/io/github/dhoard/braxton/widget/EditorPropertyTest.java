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

import io.github.dhoard.braxton.style.TextMeasure;

import org.junit.jupiter.api.Test;

import java.util.Random;

/** Inserting then backspacing the same cluster restores the draft, at every position. */
class EditorPropertyTest {

    @Test
    void anInsertedClusterIsRemovedExactlyByBackspace() {
        String[] clusters = {"a", "\u65E5", "\uD83D\uDE00", "e\u0301", "\uD83D\uDC68\u200D\uD83D\uDC69"};
        Random random = new Random(7L);
        Editor.Config options = Editor.Config.defaults();
        for (int iteration = 0; iteration < 400; iteration++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < random.nextInt(6); i++) {
                text.append(clusters[random.nextInt(clusters.length)]);
            }
            Editor.Model start = Editor.Model.of(text.toString());
            int caret = random.nextInt(start.text().length() + 1);
            Editor.Model placed = start.withCaret(caret);
            int boundary = caret == 0 ? 0 : io.github.dhoard.braxton.style.TextMeasure
                    .previousBoundary(start.text(), caret);
            Editor.Model atBoundary = start.withCaret(boundary);
            String cluster = clusters[random.nextInt(clusters.length)];
            Editor.Model inserted =
                    new Editor(options).update(atBoundary, new Editor.Msg.Insert(cluster)).model();
            Editor.Model removed =
                    new Editor(options).update(inserted, new Editor.Msg.Backspace()).model();
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
        // A seeded draft is the application's own text, so the atoms include every break form it may
        // carry: the state's entry points prepare them, the reducer prepares what it inserts.
        String[] atoms = {"a", "b", " ", "\n", "\r\n", "\r", "\u65E5", "\uD83D\uDE00", "e\u0301",
                "1"};
        for (int iteration = 0; iteration < 400; iteration++) {
            Editor.Model state = Editor.Model.of(randomText(random, atoms));
            assertThat(state.text()).as("seeded draft").doesNotContain("\r");
            int limit = 1 + random.nextInt(12);
            Editor.Config options = Editor.Config.defaults().withMaxLength(limit);
            if (random.nextBoolean()) {
                options = options.withVisibleLines(1 + random.nextInt(3));
            }
            if (random.nextBoolean()) {
                options =
                        options.withMasking(
                                random.nextBoolean()
                                        ? Editor.Config.Masking.PASSWORD
                                        : Editor.Config.Masking.HIDDEN);
            }
            for (int step = 0; step < 25; step++) {
                Editor.Model before = state;
                state = new Editor(options).update(state, randomAction(random)).model();
                assertThat(state.text()).as("no carriage return survives step %d", step)
                        .doesNotContain("\r");
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
                if (options.masking() == Editor.Config.Masking.NONE) {
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

    private static Editor.Msg randomAction(Random random) {
        return switch (random.nextInt(13)) {
            case 0 -> new Editor.Msg.Insert(random.nextBoolean() ? "x" : "\r\n");
            case 1 -> new Editor.Msg.Paste(random.nextBoolean() ? "p" : "p\n\nq");
            case 2 -> new Editor.Msg.Backspace();
            case 3 -> new Editor.Msg.Delete();
            case 4 -> new Editor.Msg.MoveLeft();
            case 5 -> new Editor.Msg.MoveRight();
            case 6 -> new Editor.Msg.MoveUp();
            case 7 -> new Editor.Msg.MoveDown();
            case 8 -> new Editor.Msg.MoveHome();
            case 9 -> new Editor.Msg.MoveEnd();
            case 10 -> new Editor.Msg.SelectAll();
            case 11 -> new Editor.Msg.Newline();
            default -> new Editor.Msg.Copy();
        };
    }
}

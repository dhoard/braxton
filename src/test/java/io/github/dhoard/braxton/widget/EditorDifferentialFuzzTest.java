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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * What the draft looks like, computed a second time from a different representation.
 *
 * <p>{@link Editor} keeps its state as UTF-16 offsets into one string and moves them with the boundary
 * helpers. A test that reads those same helpers back is only checking that the code agrees with itself.
 * This test keeps the same document as a list of grapheme clusters with the caret and anchor as
 * positions <em>between clusters</em>, applies the same messages, and compares the two after every
 * single step. Insertions, deletions, selections, line-edge moves and the length limit all land in
 * different arithmetic in the two models, so an off-by-one on one side shows up immediately on the
 * other.</p>
 *
 * <p>Line breaks are the richest part of the input space: an application can hand the draft text
 * containing CR or CR/LF, and every offset the state reports has to count the characters the rows
 * actually draw. They are in the atoms on purpose.</p>
 *
 * <p>Up, down and paste are held out of the comparison. A vertical move crosses wrapped rows and
 * carries a preferred column, which makes the row model part of the question rather than a way of
 * answering it; a paste first goes through a sanitizer that rewrites blank lines and trailing spaces,
 * so the draft it produces is the answer to a different question. Both are held to their own invariants
 * elsewhere: vertical moves in {@code EditorPropertyTest}, the sanitizer in
 * {@code PasteSanitizerPropertyTest}.</p>
 */
class EditorDifferentialFuzzTest {

    /** Draft and inserted text, including every break form an application may hand over. */
    private static final String[] ATOMS = {
        "a",
        "b",
        " ",
        "\n",
        "\r\n",
        "\r",
        "\u65E5",
        "\uD83D\uDE00",
        "e\u0301",
        "\t",
        "1",
        "\uD83D\uDC68\u200D\uD83D\uDC69"
    };

    @Test
    void theDraftAndItsCaretMatchAnIndependentClusterModel() {
        run(OptionalInt.empty(), 60_000);
    }

    @Test
    void theLengthLimitLeavesTheSameDraftTheEditorSaysItKept() {
        Random random = new Random(20261109L);
        for (int round = 0; round < 600; round++) {
            // A limit of 1 or 2 forces the truncation path on almost every insert, which is where a
            // limit that counts clusters instead of units, or cuts a cluster in half, becomes visible.
            run(OptionalInt.of(1 + random.nextInt(3)), 60);
        }
    }

    private void run(OptionalInt maxLength, int sequences) {
        Random random = new Random(424_242L + sequences + maxLength.orElse(-1));
        for (int sequence = 0; sequence < sequences; sequence++) {
            Editor.Config config = maxLength.isPresent()
                    ? Editor.Config.defaults().withMaxLength(maxLength.getAsInt())
                    : Editor.Config.defaults().withUnlimitedLength();
            Editor editor = new Editor(config);

            StringBuilder seed = new StringBuilder();
            for (int count = random.nextInt(6); count > 0; count--) {
                seed.append(ATOMS[random.nextInt(ATOMS.length)]);
            }
            Doc expected = new Doc();
            Editor.Model state = Editor.Model.of(seed.toString());
            expected.insertWithinLimit(seed.toString(), OptionalInt.empty());

            for (int step = 0; step < 14; step++) {
                int action = random.nextInt(12);
                Editor.Msg message = switch (action) {
                    case 0 -> new Editor.Msg.Insert(ATOMS[random.nextInt(ATOMS.length)]);
                    case 1 -> new Editor.Msg.Backspace();
                    case 2 -> new Editor.Msg.Delete();
                    case 3 -> new Editor.Msg.MoveLeft();
                    case 4 -> new Editor.Msg.MoveRight();
                    case 5 -> new Editor.Msg.MoveHome();
                    case 6 -> new Editor.Msg.MoveEnd();
                    case 7 -> new Editor.Msg.Newline();
                    case 8 -> new Editor.Msg.SelectAll();
                    case 9 -> new Editor.Msg.Clear();
                    default -> null;
                };
                String where = "step %d of [%s] (caret %d, anchor %d), limit %s"
                        .formatted(step, state.text(), state.caret(), state.anchor(), maxLength);
                if (message != null) {
                    state = editor.update(state, message).model();
                    applyToModel(expected, message, config);
                } else if (random.nextBoolean()) {
                    // The application places the caret itself, for a click or a drag. The offset it
                    // supplies is its own coordinate, so it may sit inside a cluster or past the end of
                    // the draft.
                    int requested = random.nextInt(state.text().length() + 3) - 1;
                    state = state.withCaret(requested);
                    expected.withCaretAt(requested);
                } else {
                    int caret = random.nextInt(state.text().length() + 3) - 1;
                    int anchor = random.nextInt(state.text().length() + 3) - 1;
                    state = new Editor.Model(state.text(), caret, anchor, state.preferredColumn());
                    expected.placeCaret(caret);
                    expected.placeAnchor(anchor);
                }
                assertAgrees(state, expected, where);
            }
        }
    }

    private static void assertAgrees(Editor.Model state, Doc expected, String where) {
        String expectedText = expected.text();
        assertThat(state.text()).as("%s: the draft itself", where).isEqualTo(expectedText);
        assertThat(state.caret())
                .as(
                        "%s: caret at cluster %d of the model, which is offset %d",
                        where, expected.caret, expected.caretOffset())
                .isEqualTo(expected.caretOffset());
        assertThat(state.anchor())
                .as(
                        "%s: anchor at cluster %d of the model, which is offset %d",
                        where, expected.anchor, expected.anchorOffset())
                .isEqualTo(expected.anchorOffset());
        assertThat(state.hasSelection())
                .as("%s: whether a range is selected", where)
                .isEqualTo(expected.anchor != expected.caret);
        if (state.hasSelection()) {
            assertThat(state.selectionStart())
                    .as("%s: selection start", where)
                    .isEqualTo(Math.min(expected.caretOffset(), expected.anchorOffset()));
            assertThat(state.selectionEnd())
                    .as("%s: selection end", where)
                    .isEqualTo(Math.max(expected.caretOffset(), expected.anchorOffset()));
        }
    }

    private static void applyToModel(Doc expected, Editor.Msg message, Editor.Config config) {
        if (message instanceof Editor.Msg.Insert insert) {
            expected.insertWithinLimit(insert.text(), config.maxLength());
        } else if (message instanceof Editor.Msg.Backspace) {
            expected.backspace();
        } else if (message instanceof Editor.Msg.Delete) {
            expected.delete();
        } else if (message instanceof Editor.Msg.MoveLeft) {
            expected.move(-1);
        } else if (message instanceof Editor.Msg.MoveRight) {
            expected.move(1);
        } else if (message instanceof Editor.Msg.MoveHome) {
            expected.lineEdge(true);
        } else if (message instanceof Editor.Msg.MoveEnd) {
            expected.lineEdge(false);
        } else if (message instanceof Editor.Msg.Newline) {
            expected.insertWithinLimit("\n", config.maxLength());
        } else if (message instanceof Editor.Msg.SelectAll) {
            expected.selectAll();
        } else if (message instanceof Editor.Msg.Clear) {
            expected.clear();
        }
    }

    /** The document as clusters, with the caret and anchor between them. */
    private static final class Doc {
        private final List<String> clusters = new ArrayList<>();
        private int caret;
        private int anchor;

        String text() {
            return String.join("", clusters);
        }

        int caretOffset() {
            return offset(caret);
        }

        int anchorOffset() {
            return offset(anchor);
        }

        private int offset(int clusterIndex) {
            int units = 0;
            for (int i = 0; i < clusterIndex; i++) {
                units += clusters.get(i).length();
            }
            return units;
        }

        /** The cluster positions that bound the line holding {@code index}. */
        private int[] lineBounds(int index) {
            int start = 0;
            for (int i = index - 1; i >= 0; i--) {
                if (clusters.get(i).equals("\n")) {
                    start = i + 1;
                    break;
                }
            }
            int end = clusters.size();
            for (int i = index; i < clusters.size(); i++) {
                if (clusters.get(i).equals("\n")) {
                    end = i;
                    break;
                }
            }
            return new int[] {start, end};
        }

        void insertWithinLimit(String raw, OptionalInt maxLength) {
            int start = Math.min(caret, anchor);
            int end = Math.max(caret, anchor);
            List<String> added = clusters(prepare(raw));
            if (maxLength.isPresent()) {
                int available = maxLength.getAsInt() - (text().length() - units(start, end));
                List<String> fitting = new ArrayList<>();
                int used = 0;
                for (String cluster : added) {
                    if (used + cluster.length() > available) {
                        break;
                    }
                    fitting.add(cluster);
                    used += cluster.length();
                }
                added = fitting;
            }
            if (added.isEmpty()) {
                return;
            }
            clusters.subList(start, end).clear();
            clusters.addAll(start, added);
            caret = start + added.size();
            anchor = caret;
        }

        private int units(int fromCluster, int toCluster) {
            return offset(toCluster) - offset(fromCluster);
        }

        void backspace() {
            int start = Math.min(caret, anchor);
            int end = Math.max(caret, anchor);
            if (start != end) {
                clusters.subList(start, end).clear();
                caret = start;
                anchor = start;
                return;
            }
            if (caret == 0) {
                return;
            }
            clusters.remove(caret - 1);
            caret--;
            anchor = caret;
        }

        void delete() {
            int start = Math.min(caret, anchor);
            int end = Math.max(caret, anchor);
            if (start != end) {
                clusters.subList(start, end).clear();
                caret = start;
                anchor = start;
                return;
            }
            if (caret >= clusters.size()) {
                return;
            }
            clusters.remove(caret);
            anchor = caret;
        }

        void move(int direction) {
            // A horizontal move steps from the caret and drops the selection; it does not collapse to
            // the near edge of a selected range first.
            caret = Math.max(0, Math.min(clusters.size(), caret + direction));
            anchor = caret;
        }

        void lineEdge(boolean start) {
            int[] bounds = lineBounds(caret);
            caret = start ? bounds[0] : bounds[1];
            anchor = caret;
        }

        void selectAll() {
            anchor = 0;
            caret = clusters.size();
        }

        void clear() {
            clusters.clear();
            caret = 0;
            anchor = 0;
        }

        /** The state {@code Model.withCaret} reaches: one coordinate for both ends of the range. */
        void withCaretAt(int requestedOffset) {
            caret = boundaryBefore(requestedOffset);
            anchor = caret;
        }

        /** The two ends of a range the application names directly. */
        void placeCaret(int requestedOffset) {
            caret = boundaryBefore(requestedOffset);
        }

        void placeAnchor(int requestedOffset) {
            anchor = boundaryBefore(requestedOffset);
        }

        /** The last cluster boundary at or before {@code requestedOffset}. */
        private int boundaryBefore(int requestedOffset) {
            int target = Math.max(0, Math.min(requestedOffset, text().length()));
            int index = 0;
            int units = 0;
            for (int i = 0; i < clusters.size(); i++) {
                if (units + clusters.get(i).length() > target) {
                    break;
                }
                units += clusters.get(i).length();
                index = i + 1;
            }
            return index;
        }

        private static List<String> clusters(String text) {
            List<String> clusters = new ArrayList<>();
            BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
            iterator.setText(text);
            for (int start = iterator.first(), end = iterator.next();
                    end != BreakIterator.DONE;
                    start = end, end = iterator.next()) {
                clusters.add(text.substring(start, end));
            }
            return clusters;
        }

        /** CR and CR/LF each become the single LF the rows draw. */
        private static String prepare(String raw) {
            return raw.replace("\r\n", "\n").replace('\r', '\n');
        }
    }
}

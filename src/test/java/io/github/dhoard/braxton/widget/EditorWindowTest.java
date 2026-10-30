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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

/** The editor's render window follows the caret and never paints more lines than it may. */
class EditorWindowTest {

    private static final String FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69";

    private static Editor.Config options(int visibleLines, int maxLength) {
        return Editor.Config.defaults()
                .withPrompt("> ", Style.NONE)
                .withVisibleLines(visibleLines)
                .withMaxLength(maxLength);
    }

    /** Each line is two columns ("L0"), so line {@code i} starts at column 3 * i. */
    private static String draftText(int lines) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            if (i > 0) {
                text.append('\n');
            }
            text.append('L').append(i);
        }
        return text.toString();
    }

    private static Editor.Model draft(int lines, int caret) {
        String text = draftText(lines);
        int position = Math.max(0, Math.min(caret, text.length()));
        return new Editor.Model(text, position, position, 0);
    }

    private static List<String> rendered(Editor.Model state, Editor.Config options) {
        StyledText rendered = Editor.render(state, options, true);
        return List.of(rendered.plainText().split("\n", -1));
    }

    /**
     * The rows a window must paint. The prompt marks the first visible line only, and the block
     * cursor covers the grapheme under it, or is appended when the caret is at the end of a line.
     */
    private static List<String> expected(int first, int visible, int caretLine, int caretColumn) {
        List<String> rows = new java.util.ArrayList<>();
        for (int i = first; i < first + visible; i++) {
            String body = "L" + i;
            String line = i == caretLine
                    ? (caretColumn >= body.length() ? body + Editor.CURSOR
                            : body.substring(0, caretColumn) + Editor.CURSOR
                                    + body.substring(caretColumn + 1))
                    : body;
            rows.add((i == first ? "> " : "  ") + line);
        }
        return rows;
    }

    @Test
    void theWindowAlwaysContainsTheCaretAndPaintsNoMoreThanItMay() {
        for (int visibleLines = 1; visibleLines <= 4; visibleLines++) {
            Editor.Config options = options(visibleLines, 1_000);
            for (int lines = 1; lines <= 8; lines++) {
                for (int caret = 0; caret <= lines * 3; caret++) {
                    Editor.Model state = draft(lines, Math.min(caret, lines * 3));
                    int caretLine = state.lineAt(state.caret());
                    int visible = Math.min(visibleLines, lines);
                    int first = Math.max(0, Math.min(caretLine - visible + 1, lines - visible));
                    List<String> painted = rendered(state, options);
                    assertThat(painted).as("visible %d lines %d caret %d", visibleLines, lines,
                            caret).isEqualTo(expected(first, visible, caretLine,
                                    state.caret() - 3 * caretLine));
                    assertThat(String.join("", painted)).as("the prompt appears once")
                            .containsOnlyOnce("> ");
                }
            }
        }
    }

    @Test
    void theWindowScrollsDownWithTheCaretAndNeverBackwards() {
        Editor.Config options = options(2, 1_000);
        int previousFirst = -1;
        for (int caretLine = 0; caretLine < 6; caretLine++) {
            List<String> painted = rendered(draft(6, caretLine * 3), options);
            int firstVisible = Math.max(0, Math.min(caretLine - 1, 6 - 2));
            assertThat(painted).as("caret line %d scrolls the window to it", caretLine)
                    .isEqualTo(expected(firstVisible, 2, caretLine, 0));
            assertThat(firstVisible).as("the window never moves back")
                    .isGreaterThanOrEqualTo(previousFirst);
            previousFirst = firstVisible;
        }
        assertThat(rendered(draft(6, 0), options)).isEqualTo(expected(0, 2, 0, 0));
    }

    @Test
    void anEmptyOrSingleLineDraftPaintsOneLine() {
        Editor.Config options = options(4, 1_000);
        assertThat(rendered(Editor.Model.empty(), options)).containsExactly("> " + Editor.CURSOR);
        assertThat(rendered(draft(1, 0), options)).isEqualTo(expected(0, 1, 0, 0));
        assertThat(rendered(draft(1, 1), options)).isEqualTo(expected(0, 1, 0, 1));
        assertThat(rendered(draft(1, 2), options)).isEqualTo(expected(0, 1, 0, 2));
        assertThatThrownBy(() -> options(0, 1_000))
                .as("a zero visible-line budget is rejected at configuration time")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxVisibleLines");
    }

    @Test
    void pastingAtTheLengthLimitKeepsWhatFitsAndWholeGraphemes() {
        Editor.Config options = options(4, 4);
        Editor.Model filled = Editor.Model.of("abcd");
        io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> exact =
                new Editor(options).update(filled, new Editor.Msg.Paste("wxyz"));
        assertThat(exact.model().text()).isEqualTo("abcd").isEqualTo(exact.model().text());

        io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> fitting =
                new Editor(options).update(Editor.Model.of("ab"), new Editor.Msg.Paste("cd"));
        assertThat(fitting.model().text()).isEqualTo("abcd");
        assertThat(fitting.model().caret()).isEqualTo(4);

        // One grapheme over the limit is dropped whole, never cut in half.
        io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> over =
                new Editor(options)
                        .update(Editor.Model.of("ab"), new Editor.Msg.Paste("c" + FAMILY));
        assertThat(over.model().text()).isEqualTo("abc");
        assertThat(
                        Character.isHighSurrogate(
                                over.model().text().charAt(over.model().text().length() - 1)))
                .isFalse();
    }

    @Test
    void aSelectionIsReplacedByAPasteThatFitsInsideTheLimit() {
        Editor.Config options = options(4, 6);
        Editor.Model selected = Editor.Model.of("abcdef").withSelection(1, 4);
        io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> result =
                new Editor(options).update(selected, new Editor.Msg.Paste("X"));
        assertThat(result.model().text()).isEqualTo("aXef");
        io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> grown =
                new Editor(options)
                        .update(
                                Editor.Model.of("abcdef").withSelection(0, 6),
                                new Editor.Msg.Paste("123456"));
        assertThat(grown.model().text()).isEqualTo("123456");
        io.github.dhoard.braxton.mvu.Update<Editor.Model, Editor.Msg, Editor.Output> shrunk =
                new Editor(options)
                        .update(
                                Editor.Model.of("abcdef").withSelection(0, 6),
                                new Editor.Msg.Paste("1234567890"));
        assertThat(shrunk.model().text()).isEqualTo("123456");
    }

    @Test
    void randomDraftsAndCaretsNeverPaintMoreThanTheWindow() {
        Random random = new Random(307L);
        String[] pieces = {"a", FAMILY, "\n", " ", "bc"};
        for (int iteration = 0; iteration < 500; iteration++) {
            StringBuilder text = new StringBuilder();
            int count = random.nextInt(10);
            for (int i = 0; i < count; i++) {
                text.append(pieces[random.nextInt(pieces.length)]);
            }
            String source = text.toString();
            Editor.Model state =
                    new Editor.Model(
                            source,
                            random.nextInt(source.length() + 1),
                            random.nextInt(source.length() + 1),
                            0);
            int visibleLines = 1 + random.nextInt(5);
            List<String> painted = rendered(state, options(visibleLines, 1_000));
            assertThat(painted).as("iteration %d", iteration)
                    .hasSize(Math.min(visibleLines, state.lineCount()));
            assertThat(String.join("", painted)).as("iteration %d keeps the prompt once", iteration)
                    .containsOnlyOnce("> ");
        }
    }

    @Test
    void theViewFitsEveryWidthIncludingOneNarrowerThanThePrompt() {
        // The editor view is a text view: the layout engine clips and wraps it, so any real width
        // must paint without exceeding the viewport and must keep the caret reachable - including a
        // width narrower than the prompt itself.
        Editor.Model state = Editor.Model.of("hello\nworld").withCaret(3).withSelection(1, 6);
        io.github.dhoard.braxton.view.View<String> view =
                new Editor(Editor.Config.defaults().withPrompt(">  ", Style.NONE))
                        .view(
                                (state).withFocus(true),
                                new io.github.dhoard.braxton.mvu.ViewContext(
                                        io.github.dhoard.braxton.layout.Viewport.EMPTY))
                        .map(action -> "msg");
        for (int width = 1; width <= 8; width++) {
            for (int rows = 1; rows <= 3; rows++) {
                List<String> lines = new io.github.dhoard.braxton.view.ScreenRenderer()
                        .plainLines(view, new io.github.dhoard.braxton.layout.Viewport(rows, width));
                assertThat(lines).as("width %d rows %d", width, rows).hasSize(rows);
                for (String line : lines) {
                    assertThat(io.github.dhoard.braxton.style.TextMeasure.columns(line))
                            .as("width %d rows %d line [%s]", width, rows, line)
                            .isLessThanOrEqualTo(width);
                }
            }
        }
        assertThat(
                        Editor.render(
                                        state,
                                        Editor.Config.defaults().withPrompt(">  ", Style.NONE),
                                        true)
                                .plainText())
                .as("the prompt is painted once, at the first visible line")
                .startsWith(">  ")
                .contains("\n");
        String focused =
                Editor.render(Editor.Model.of("x"), Editor.Config.defaults(), true).plainText();
        String idle =
                Editor.render(Editor.Model.of("x"), Editor.Config.defaults(), false).plainText();
        assertThat(idle).as("an unfocused editor paints only its text").isEqualTo("x");
        assertThat(focused).as("a focused editor adds one cursor cell").hasSize(2)
                .startsWith("x");
    }
}

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

package io.github.dhoard.braxton.line;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Cursor;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The one-row presentation of a line editor, asserted on a surface with no terminal.
 */
class LineRendererTest {

    private static final LineRenderer PLAIN = new LineRenderer(new Theme("plain", Map.of()), WidthPolicy.PINNED);

    @Test
    void paintsThePromptThenTheLineAndPlacesTheCaret() {
        LineEditor editor = LineEditor.emacs().withText("abc");
        Screen screen = new Screen(1, 20);

        Optional<Cursor> caret = PLAIN.paint(screen, 0, 20, editor, "> ");

        assertThat(screen.plainLine(0)).isEqualTo("> abc");
        assertThat(caret).contains(Cursor.at(0, 5));
        assertThat(PLAIN.paint(new Screen(1, 20), 0, 20, LineEditor.emacs(), "> "))
                .contains(Cursor.at(0, 2));
    }

    @Test
    void scrollsTheLeastItCanSoTheCaretStaysInsideTheRow() {
        LineEditor editor = LineEditor.emacs().withText("abcdefghij");
        Screen screen = new Screen(1, 7);

        Optional<Cursor> caret = PLAIN.paint(screen, 0, 7, editor, "> ");

        assertThat(screen.plainLine(0)).isEqualTo("> ghij");
        assertThat(caret).contains(Cursor.at(0, 6));
        assertThat(PLAIN.scrollOffset(editor, "> ", 7)).isEqualTo(6);
        assertThat(PLAIN.scrollOffset(editor.withCaret(0), "> ", 7)).isZero();
        assertThat(PLAIN.scrollOffset(null, "> ", 7)).isZero();
    }

    @Test
    void aWideClusterIsShownWholeOrNotAtAll() {
        LineEditor editor = LineEditor.emacs().withText("\u6f22b").withCaret(1);
        Screen screen = new Screen(1, 2);

        Optional<Cursor> caret = PLAIN.paint(screen, 0, 2, editor, "");

        assertThat(screen.plainLine(0)).isEqualTo("b");
        assertThat(caret).contains(Cursor.at(0, 0));
        assertThat(PLAIN.scrollOffset(editor, "", 2)).isEqualTo(2);
    }

    @Test
    void aNarrowRowKeepsTheCaretInsideIt() {
        LineEditor editor = LineEditor.emacs().withText("abc");
        Screen screen = new Screen(1, 5);

        Optional<Cursor> caret = PLAIN.paint(screen, 0, 4, editor, "long-prompt> ");

        assertThat(screen.plainLine(0)).isEqualTo("long");
        assertThat(caret).contains(Cursor.at(0, 3));
    }

    @Test
    void aRowWithNoColumnsPaintsNothingAndAsksForNoCaret() {
        Screen screen = new Screen(1, 4);

        assertThat(PLAIN.paint(screen, 0, 0, LineEditor.emacs().withText("abc"), "> "))
                .isEmpty();
        assertThat(PLAIN.paint(screen, 0, -3, LineEditor.emacs().withText("abc"), "> "))
                .isEmpty();
        assertThat(screen.plainLine(0)).isEmpty();
        assertThat(PLAIN.scrollOffset(LineEditor.emacs(), "> ", 0)).isZero();
    }

    @Test
    void theRowsStylesComeFromTheTheme() {
        LineRenderer renderer = new LineRenderer(Themes.DARK, WidthPolicy.PINNED);
        Screen screen = new Screen(1, 20);

        renderer.paint(screen, 0, 20, LineEditor.emacs().withText("ab"), "> ");

        Style accent = Themes.DARK.style(ThemeToken.ACCENT);
        Style text = Themes.DARK.style(ThemeToken.TEXT);
        assertThat(screen.style(0, 0)).isEqualTo(accent);
        assertThat(screen.style(0, 1)).isEqualTo(accent);
        assertThat(screen.style(0, 2)).isEqualTo(text);
        assertThat(screen.style(0, 3)).isEqualTo(text);
        assertThat(renderer.theme()).isEqualTo(Themes.DARK);
        assertThat(renderer.policy()).isEqualTo(WidthPolicy.PINNED);
        assertThat(new LineRenderer(null, null).theme()).isEqualTo(Themes.DARK);
        assertThat(new LineRenderer().theme()).isEqualTo(Themes.DARK);
        assertThat(new LineRenderer().policy()).isEqualTo(WidthPolicy.PINNED);
        assertThat(renderer.withTheme(null).theme()).isEqualTo(Themes.DARK);
        assertThat(renderer.withPolicy(null).policy()).isEqualTo(WidthPolicy.PINNED);
    }

    @Test
    void aRunningSearchPaintsItsOwnPromptAndPlacesTheCaretOnTheMatch() {
        LineEditor editor = LineEditor.emacs().withHistory(History.of(List.of("make test", "run tests")));
        LineEditor searching =
                editor.update(new LineEditor.Msg.SearchBackward()).editor();
        Screen screen = new Screen(1, 40);

        Optional<Cursor> caret = PLAIN.paint(screen, 0, 40, searching, "> ");

        int emptyQuery = "(reverse-i-search)`': ".length();
        assertThat(screen.plainLine(0)).isEqualTo("(reverse-i-search)`': run tests");
        assertThat(caret).contains(Cursor.at(0, emptyQuery));

        LineEditor narrowed =
                searching.update(new LineEditor.Msg.SearchInsert("test")).editor();
        Screen query = new Screen(1, 40);
        Optional<Cursor> atMatch = PLAIN.paint(query, 0, 40, narrowed, "> ");
        int withQuery = "(reverse-i-search)`test': ".length();
        assertThat(query.plainLine(0)).isEqualTo("(reverse-i-search)`test': run tests");
        assertThat(atMatch).contains(Cursor.at(0, withQuery + 4));

        LineEditor failed =
                searching.update(new LineEditor.Msg.SearchInsert("zz")).editor();
        Screen failure = new Screen(1, 40);
        PLAIN.paint(failure, 0, 40, failed, "> ");
        assertThat(failure.plainLine(0)).startsWith("(failed reverse-i-search)`zz': ");

        // The search prompt is what the row shows, so a narrow row clips it and keeps the caret inside.
        Screen narrow = new Screen(1, 10);
        Optional<Cursor> clipped = PLAIN.paint(narrow, 0, 10, narrowed, "> ");
        assertThat(clipped).contains(Cursor.at(0, 9));
        assertThat(narrow.plainLine(0)).isEqualTo("(reverse-i");
        assertThat(PLAIN.scrollOffset(narrowed, "> ", 10)).isZero();
    }

    @Test
    void thePromptAndTheLineCannotCarryAControlCharacter() {
        LineEditor editor = LineEditor.emacs().withText("a\u001bb");
        Screen screen = new Screen(1, 20);

        Optional<Cursor> caret = PLAIN.paint(screen, 0, 20, editor, "[\u0007]\n");

        assertThat(screen.plainLine(0)).isEqualTo("[ ] a b");
        assertThat(caret).contains(Cursor.at(0, 7));
    }

    @Test
    void aHighlighterColorsTheClustersItNamesAndLeavesTheCaretWhereItWas() {
        Style keyword = Style.fg(new Color(200, 40, 40));
        LineEditor editor = LineEditor.emacs()
                .withText("ab cd")
                .withHighlighter(text -> Arrays.asList(keyword, null, keyword, null, keyword));

        assertThat(editor.highlighter()).isPresent();
        Screen screen = new Screen(1, 20);
        Optional<Cursor> caret = PLAIN.paint(screen, 0, 20, editor, "> ");

        assertThat(screen.plainLine(0))
                .as("highlighting changes no cell's text")
                .isEqualTo("> ab cd");
        assertThat(caret).contains(Cursor.at(0, 7));
        assertThat(PLAIN.scrollOffset(editor, "> ", 20)).isZero();
        assertThat(screen.style(0, 2)).isEqualTo(keyword);
        assertThat(screen.style(0, 3)).isEqualTo(Style.NONE);
        assertThat(screen.style(0, 4)).isEqualTo(keyword);
        assertThat(screen.style(0, 5)).isEqualTo(Style.NONE);
        assertThat(screen.style(0, 6)).isEqualTo(keyword);

        // The highlight merges over the theme's line style rather than replacing it.
        Screen themed = new Screen(1, 20);
        new LineRenderer(Themes.DARK, WidthPolicy.PINNED).paint(themed, 0, 20, editor, "> ");
        assertThat(themed.style(0, 2))
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT).merge(keyword));

        // A shorter answer leaves the rest of the row in the line's own style, and null colors none.
        LineEditor partial = LineEditor.emacs().withText("abcd").withHighlighter(text -> Arrays.asList(keyword, null));
        Screen shortAnswer = new Screen(1, 10);
        PLAIN.paint(shortAnswer, 0, 10, partial, "");
        assertThat(shortAnswer.style(0, 0)).isEqualTo(keyword);
        assertThat(shortAnswer.style(0, 1)).isEqualTo(Style.NONE);
        assertThat(shortAnswer.style(0, 2)).isEqualTo(Style.NONE);
        assertThat(shortAnswer.style(0, 3)).isEqualTo(Style.NONE);

        Screen nothing = new Screen(1, 10);
        PLAIN.paint(nothing, 0, 10, LineEditor.emacs().withText("ab").withHighlighter(text -> null), "");
        assertThat(nothing.style(0, 0)).isEqualTo(Style.NONE);
    }

    @Test
    void aMaskedRowPaintsOneGlyphPerClusterAndKeepsTheCaretInMaskCells() {
        LineEditor editor = LineEditor.emacs().withText("a\u6f22b").withMasked(true);

        Screen screen = new Screen(1, 10);
        Optional<Cursor> caret = PLAIN.paint(screen, 0, 10, editor, "> ");

        assertThat(screen.plainLine(0)).isEqualTo("> ***");
        assertThat(caret).as("the caret sits after three mask cells").contains(Cursor.at(0, 5));
        assertThat(editor.text()).as("the editor still holds the real text").isEqualTo("a\u6f22b");

        Screen wide = new Screen(1, 10);
        Optional<Cursor> before = PLAIN.paint(wide, 0, 10, editor.withCaret(1), "> ");
        assertThat(wide.plainLine(0)).isEqualTo("> ***");
        assertThat(before).as("a wide cluster takes one mask cell").contains(Cursor.at(0, 3));

        // Masking scrolls like any other row, and it hides what a highlighter would color.
        LineEditor longMasked = LineEditor.emacs()
                .withText("abcdefghij")
                .withMasked(true)
                .withHighlighter(text -> List.of(Style.fg(new Color(10, 20, 30))));
        Screen scrolled = new Screen(1, 7);
        Optional<Cursor> end = PLAIN.paint(scrolled, 0, 7, longMasked, "> ");
        assertThat(scrolled.plainLine(0)).as("the caret keeps its cell").isEqualTo("> ****");
        assertThat(end).contains(Cursor.at(0, 6));
        assertThat(PLAIN.scrollOffset(longMasked, "> ", 7)).isEqualTo(6);
        assertThat(scrolled.style(0, 2)).isEqualTo(Style.NONE);

        // A mask that comes and goes leaves the row's own arithmetic alone.
        assertThat(plainText(editor.withMasked(false), "> ", 10)).isEqualTo("> a\u6f22b");
    }

    @Test
    void theMenuRowBracketsTheHighlightedCandidateAndScrollsToIt() {
        LineEditor editor =
                LineEditor.emacs().withText("gi").withCompleter((text, caret) -> List.of("git", "gist", "gimp"));
        LineEditor menu = editor.update(new LineEditor.Msg.Complete()).editor();
        assertThat(menu.completion()).isPresent();

        Screen screen = new Screen(2, 24);
        PLAIN.paintMenu(screen, 1, 24, menu);
        assertThat(screen.plainLine(1)).isEqualTo("[git]  gist  gimp");

        LineEditor third = menu.update(new LineEditor.Msg.CompleteNext())
                .editor()
                .update(new LineEditor.Msg.CompleteNext())
                .editor();
        Screen stepped = new Screen(2, 24);
        PLAIN.paintMenu(stepped, 1, 24, third);
        assertThat(stepped.plainLine(1)).isEqualTo("git  gist  [gimp]");

        // A narrow row scrolls so the highlighted candidate is whole and its neighbors are clipped.
        Screen narrow = new Screen(2, 10);
        PLAIN.paintMenu(narrow, 1, 10, third);
        assertThat(narrow.plainLine(1)).isEqualTo("st  [gimp]");

        // The highlighted candidate carries the selection and the rest are muted.
        Screen themed = new Screen(2, 24);
        new LineRenderer(Themes.DARK, WidthPolicy.PINNED).paintMenu(themed, 1, 24, menu);
        assertThat(themed.style(1, 0)).isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(themed.style(1, 7)).isEqualTo(Themes.DARK.style(ThemeToken.MUTED));

        // No menu, no menu row.
        Screen none = new Screen(2, 24);
        PLAIN.paintMenu(none, 1, 24, editor);
        assertThat(none.plainLine(1)).isEmpty();
        PLAIN.paintMenu(none, 1, 0, menu);
        assertThat(none.plainLine(1)).isEmpty();
    }

    /**
     * A whole row as a terminal would show it, for the cases that only care about the text.
     */
    private static String plainText(LineEditor editor, String prompt, int cols) {
        Screen screen = new Screen(1, cols);
        PLAIN.paint(screen, 0, cols, editor, prompt);
        return screen.plainLine(0);
    }
}

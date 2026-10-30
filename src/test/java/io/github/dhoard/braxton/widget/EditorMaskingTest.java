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

import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ScreenRenderer;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Masked drafts paint one mask per cluster, keep line structure, move by the columns they paint,
 * and never copy.
 */
class EditorMaskingTest {

    private static final Editor.Config PASSWORD =
            Editor.Config.defaults().withMasking(Editor.Config.Masking.PASSWORD);
    private static final Editor.Config HIDDEN =
            Editor.Config.defaults().withMasking(Editor.Config.Masking.HIDDEN);

    /** Every row a viewport paints for {@code state}, through the real layout path. */
    private static List<String> paintedLines(Editor.Model state, Editor.Config options) {
        return new ScreenRenderer()
                .plainLines(
                        new Editor(options)
                                .view(
                                        (state).withFocus(true),
                                        new io.github.dhoard.braxton.mvu.ViewContext(
                                                io.github.dhoard.braxton.layout.Viewport.EMPTY))
                                .map((Editor.Msg action) -> action),
                        new Viewport(2, 24));
    }

    /** Painted column of the block cursor in {@code row}, or -1 when the row does not draw one. */
    private static int blockColumn(String row) {
        return row.indexOf(Editor.CURSOR);
    }

    @Test
    void aPasswordDraftMasksEachClusterAndKeepsItsLines() {
        Editor.Model state = Editor.Model.of("ab\uD83D\uDE00\ncd").withCaret(0);
        var rendered = Editor.render(state, PASSWORD, false);
        assertThat(rendered.plainText().split("\n", -1)).hasSize(2);
        assertThat(rendered.plainText()).doesNotContain("a").doesNotContain("b")
                .contains("\u2022");
        assertThat(TextMeasure.graphemeCount(rendered.plainText())).isEqualTo(6);
        assertThat(state.displayText(PASSWORD).split("\n", -1)).hasSize(2);
        assertThat(state.displayText(PASSWORD)).doesNotContain("a");
    }

    @Test
    void aCrLfPairSeededIntoTheDraftMasksAsTheEditorsLineBreak() {
        // Editor.Model prepares the draft it is given, so a seeded CR/LF pair is the editor's own
        // LF:
        // one line break, not a mask glyph for a character no row paints. The painted and masked
        // views of such a draft therefore agree, and the break keeps the line count it paints.
        // (Drafts the reducer reaches are already normalized by Editor.insert and PasteSanitizer.)
        for (String draft : new String[] {"a\r\nb", "\r\n", "a\n\r\nb", "a\r", "\r\n\r\n"}) {
            Editor.Model state = Editor.Model.of(draft).withCaret(0);
            String display = state.displayText(PASSWORD);
            assertThat(state.text()).as("draft of [%s]", visible(draft)).doesNotContain("\r");
            assertThat(display).as("masked display of [%s]", visible(draft))
                    .isEqualTo(Editor.render(state, PASSWORD, false).plainText());
            assertThat(display.split("\n", -1)).as("masked lines of [%s]", visible(draft))
                    .hasSize(state.lineCount());
        }
        assertThat(Editor.Model.of("a\r\nb").displayText(PASSWORD)).isEqualTo("\u2022\n\u2022");
    }

    private static String visible(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    @Test
    void aHiddenDraftRendersNoContentAndNeverCopies() {
        Editor.Model state = Editor.Model.of("secret");
        assertThat(Editor.render(state, HIDDEN, false).plainText()).isEmpty();
        assertThat(state.displayText(HIDDEN)).isEmpty();
        assertThat(Editor.copyableText(state, PASSWORD)).isEmpty();
        assertThat(Editor.copyableText(state, HIDDEN)).isEmpty();
        assertThat(new Editor(HIDDEN).update(state, new Editor.Msg.Copy()).outputs().getFirst())
                .isEqualTo(new Editor.Output.Copy(""));
    }

    @Test
    void aVerticalMoveInAPasswordDraftReturnsToTheColumnTheMaskedRowPaints() {
        // A masked row draws one glyph per cluster, so the block is painted two cells into a line of
        // two emoji, and the column a vertical move returns to is that painted column. Measuring the
        // cleartext the row never draws - two columns per astral cluster - aims two columns right of
        // where the block was drawn, so the block jumps sideways on the move.
        String draft = "\uD83D\uDE00\uD83D\uDE00\nabcdefgh";
        Editor.Model state = new Editor.Model(draft, 4, 4, 0);
        List<String> before = paintedLines(state, PASSWORD);
        assertThat(before).containsExactly("\u2022\u2022" + Editor.CURSOR, "\u2022".repeat(8));

        Editor.Model down = new Editor(PASSWORD).update(state, new Editor.Msg.MoveDown()).model();
        assertThat(down.preferredColumn()).isEqualTo(2);
        assertThat(down.caret()).isEqualTo(draft.indexOf('c'));
        List<String> after = paintedLines(down, PASSWORD);
        assertThat(after).containsExactly("\u2022\u2022",
                "\u2022\u2022" + Editor.CURSOR + "\u2022".repeat(5));
        assertThat(blockColumn(after.get(1))).isEqualTo(blockColumn(before.get(0)));
        assertThat(new Editor(PASSWORD).update(down, new Editor.Msg.MoveUp()).model().caret())
                .isEqualTo(4);

        // The same draft unmasked keeps the cleartext columns: the mask is the only difference.
        Editor.Model clear =
                new Editor(Editor.Config.defaults())
                        .update(state, new Editor.Msg.MoveDown())
                        .model();
        assertThat(clear.preferredColumn()).isEqualTo(4);
        assertThat(clear.caret()).isEqualTo(draft.indexOf('e'));
    }

    @Test
    void aVerticalMoveInAPasswordDraftCountsATabAsTheSingleMaskGlyphItPaints() {
        // A paste under the default PasteMode.NORMAL keeps tabs, and a masked row draws one glyph for
        // a tab: three cells before the block, not the four-column stop an unmasked row expands it
        // to. The tab is where the disagreement between the two rules is largest.
        String draft = "a\tb\nabcdefgh";
        Editor.Model state = new Editor.Model(draft, 3, 3, 0);
        assertThat(paintedLines(state, PASSWORD))
                .containsExactly("\u2022\u2022\u2022" + Editor.CURSOR, "\u2022".repeat(8));

        Editor.Model down = new Editor(PASSWORD).update(state, new Editor.Msg.MoveDown()).model();
        assertThat(down.preferredColumn()).isEqualTo(3);
        assertThat(down.caret()).isEqualTo(draft.indexOf('d'));
        assertThat(paintedLines(down, PASSWORD)).containsExactly("\u2022\u2022\u2022",
                "\u2022\u2022\u2022" + Editor.CURSOR + "\u2022".repeat(4));
        assertThat(new Editor(PASSWORD).update(down, new Editor.Msg.MoveUp()).model().caret())
                .isEqualTo(3);

        Editor.Model clear =
                new Editor(Editor.Config.defaults())
                        .update(state, new Editor.Msg.MoveDown())
                        .model();
        assertThat(clear.preferredColumn()).isEqualTo(5);
        assertThat(clear.caret()).isEqualTo(draft.indexOf('f'));
    }

    @Test
    void aVerticalMoveInAPasswordDraftWithAPromptCountsThePromptAndEveryMaskGlyph() {
        // The prompt prefixes every masked line, so the caret's column is the prompt's painted
        // width plus one cell per masked cluster, and the target line is sought from that same
        // base.
        Editor.Config password = PASSWORD.withPrompt("> ", Style.NONE);
        String draft = "\u65E5\u65E5\nabcd";
        Editor.Model state = new Editor.Model(draft, 2, 2, 0);
        assertThat(paintedLines(state, password))
                .containsExactly("> \u2022\u2022" + Editor.CURSOR, "  " + "\u2022".repeat(4));

        Editor.Model down = new Editor(password).update(state, new Editor.Msg.MoveDown()).model();
        assertThat(down.preferredColumn()).isEqualTo(4);
        assertThat(down.caret()).isEqualTo(draft.indexOf('c'));
        assertThat(paintedLines(down, password)).containsExactly("> \u2022\u2022",
                "  \u2022\u2022" + Editor.CURSOR + "\u2022");
        assertThat(new Editor(password).update(down, new Editor.Msg.MoveUp()).model().caret())
                .isEqualTo(2);
    }

    @Test
    void aHiddenDraftPaintsItsBlockAtThePromptColumnAndRemembersTheMaskedColumn() {
        // A hidden row draws no glyph before the block, so the block sits at the prompt's column
        // wherever the caret is: no offset of a hidden line paints a column a vertical move could
        // return to. The remembered column is still the masked layout's - one cell per cluster - so
        // a move returns the caret to the cluster that layout names, never to the cleartext columns
        // a hidden row does not draw.
        String draft = "\uD83D\uDE00\uD83D\uDE00\nabcdefgh";
        Editor.Model state = new Editor.Model(draft, 4, 4, 0);
        assertThat(blockColumn(paintedLines(state, HIDDEN).get(0))).isZero();

        Editor.Model down = new Editor(HIDDEN).update(state, new Editor.Msg.MoveDown()).model();
        assertThat(down.preferredColumn()).isEqualTo(2);
        assertThat(down.caret()).isEqualTo(draft.indexOf('c'));
        assertThat(blockColumn(paintedLines(down, HIDDEN).get(1))).isZero();
        assertThat(new Editor(HIDDEN).update(down, new Editor.Msg.MoveUp()).model().caret())
                .isEqualTo(4);
    }

    @Test
    void copyTextIsRawWhileCopyableTextHonoursMasking() {
        // Documented distinction: copyText returns the selection or the draft and deliberately
        // ignores masking; copyableText (which the Copy action uses) refuses a masked draft.
        Editor.Model state = Editor.Model.of("secret").withCaret(3);
        assertThat(Editor.copyText(state)).isEqualTo("secret");
        assertThat(Editor.copyText(state.withSelection(0, 2))).isEqualTo("se");
        assertThat(Editor.copyableText(state, PASSWORD)).isEmpty();
        assertThat(Editor.copyableText(state, Editor.Config.defaults())).contains("secret");
        assertThat(new Editor(PASSWORD).update(state, new Editor.Msg.Copy()).outputs().getFirst())
                .isEqualTo(new Editor.Output.Copy(""));
    }
}

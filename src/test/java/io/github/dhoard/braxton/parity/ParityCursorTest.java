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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import examples.parity.CursorDemo;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.Carets;
import io.github.dhoard.braxton.internal.render.CursorPresenter;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Cursor;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.ui.InteractionState;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.Widget;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorConfig;
import io.github.dhoard.braxton.widget.Elements;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 18 acceptance: the caret a widget declares, the cell the frame presents it on, and the
 * sequences that present it.
 *
 * <p>A caret is widget model data, not a tree node: the focused widget answers
 * {@code cursor(bounds, model)} and the runtime presents that one answer. Every assertion here is
 * against public behavior - the value a widget returns, the cell a frame resolves, the style a
 * virtual caret paints, and the sequences a presenter emits.</p>
 */
class ParityCursorTest {

    private static final Viewport VIEWPORT = new Viewport(8, 24);

    private static final ViewContext CONTEXT = new ViewContext(VIEWPORT);

    private static ElementScreen screen(Element<?> view) {
        return ElementScreen.render(view, VIEWPORT.rows(), VIEWPORT.cols());
    }

    private static ElementScreen screen(Element<?> view, int rows, int cols) {
        return ElementScreen.render(view, rows, cols);
    }

    // ── R-01: cursor intent is immutable model data ──────────────────────────

    @Test
    void aCursorDescribesShapeBlinkVisibilityAndColourWithoutATerminal() {
        Cursor cursor = Cursor.at(2, 5)
                .withShape(Cursor.Shape.BAR)
                .withMode(Cursor.Mode.STEADY)
                .withStrategy(Cursor.Strategy.VIRTUAL)
                .withColor(Color.WHITE);
        assertThat(cursor.shape()).isEqualTo(Cursor.Shape.BAR);
        assertThat(cursor.mode()).isEqualTo(Cursor.Mode.STEADY);
        assertThat(cursor.strategy()).isEqualTo(Cursor.Strategy.VIRTUAL);
        assertThat(cursor.color()).isEqualTo(Color.WHITE);
        assertThat(cursor.visible()).as("a steady cursor is shown").isTrue();
        assertThat(cursor.withMode(Cursor.Mode.HIDDEN).visible())
                .as("a hidden cursor is not shown")
                .isFalse();
        assertThat(Cursor.at(0, 0).withColor(null).color())
                .as("no color means the terminal's own")
                .isNull();
    }

    @Test
    void aNegativePositionIsRejectedRatherThanDrawnSomewhereElse() {
        assertThatThrownBy(() -> Cursor.at(-1, 0))
                .as("negative row")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> Cursor.at(0, -1)).as("negative column").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cursor.at(0, 0).movedTo(0, -2))
                .as("a move is validated like a position")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingPresentationValueFallsBackToTheDocumentedDefault() {
        Cursor cursor = new Cursor(null, null, null, null, 0, 0);
        assertThat(cursor.shape()).isEqualTo(Cursor.Shape.BLOCK);
        assertThat(cursor.mode()).isEqualTo(Cursor.Mode.BLINK);
        assertThat(cursor.strategy()).isEqualTo(Cursor.Strategy.NATIVE);
        assertThat(Cursor.at(0, 0).withShape(null).shape()).isEqualTo(Cursor.Shape.BLOCK);
        assertThat(Cursor.at(0, 0).withMode(null).mode()).isEqualTo(Cursor.Mode.BLINK);
        assertThat(Cursor.at(0, 0).withStrategy(null).strategy()).isEqualTo(Cursor.Strategy.NATIVE);
    }

    @Test
    void aVirtualBlinkIsResolvedFromModelTimeAndANativeOneIsNot() {
        Cursor virtual = Cursor.at(0, 0).withStrategy(Cursor.Strategy.VIRTUAL).withMode(Cursor.Mode.BLINK);
        assertThat(virtual.atTime(0).visible()).as("first half of the period").isTrue();
        assertThat(virtual.atTime(Cursor.BLINK_MILLIS / 2 - 1).visible()).isTrue();
        assertThat(virtual.atTime(Cursor.BLINK_MILLIS / 2).visible())
                .as("second half of the period")
                .isFalse();
        assertThat(virtual.atTime(Cursor.BLINK_MILLIS).visible())
                .as("the period repeats")
                .isTrue();
        Cursor nativeCursor = Cursor.at(0, 0).withMode(Cursor.Mode.BLINK);
        assertThat(nativeCursor.atTime(1))
                .as("the terminal blinks a native cursor")
                .isSameAs(nativeCursor);
        Cursor steady = Cursor.at(0, 0).withMode(Cursor.Mode.STEADY).withStrategy(Cursor.Strategy.VIRTUAL);
        assertThat(steady.atTime(999)).as("a steady cursor ignores the clock").isSameAs(steady);
    }

    // ── R-02: the cell the frame presents ────────────────────────────────────

    /**
     * A widget that paints its model and reports a caret at a fixed offset inside itself.
     */
    private static Element<Object> caretAt(String text, boolean focused, int rowOffset, int colOffset) {
        return new Element.Leaf<>(
                new CaretField(rowOffset, colOffset), new CaretModel(text, focused), Sizing.measured());
    }

    private record CaretModel(String text, boolean focused) {}

    private static final class CaretField implements Widget<CaretModel, Object> {

        private final int rowOffset;
        private final int colOffset;
        private final Cursor.Mode mode;

        CaretField(int rowOffset, int colOffset) {
            this(rowOffset, colOffset, Cursor.Mode.BLINK);
        }

        CaretField(int rowOffset, int colOffset, Cursor.Mode mode) {
            this.rowOffset = rowOffset;
            this.colOffset = colOffset;
            this.mode = mode;
        }

        @Override
        public Size measure(Size max, CaretModel model) {
            int rows = (int) model.text().chars().filter(c -> c == '\n').count() + 1;
            int cols = 0;
            for (String line : model.text().split("\n", -1)) {
                cols = Math.max(cols, line.length());
            }
            return new Size(Math.min(rows, max.rows()), Math.min(cols, max.cols()));
        }

        @Override
        public void paint(Surface surface, Rect bounds, CaretModel model) {
            surface.write(bounds, StyledText.plain(model.text()), WidthPolicy.PINNED);
        }

        @Override
        public boolean canFocus(CaretModel model) {
            return true;
        }

        @Override
        public boolean focused(CaretModel model) {
            return model.focused();
        }

        @Override
        public InteractionState state(CaretModel model) {
            return InteractionState.NONE.withFocused(model.focused());
        }

        @Override
        public Cursor cursor(Rect bounds, CaretModel model) {
            return Cursor.at(bounds.row() + rowOffset, bounds.col() + colOffset).withMode(mode);
        }
    }

    @Test
    void paddingAndAFrameMoveTheCaretToTheCellItIsDrawnIn() {
        Element<Object> view = Elements.pad(
                1,
                Elements.frame(
                        Border.SINGLE, StyledText.empty(), Style.NONE, Elements.column(caretAt("hello", true, 0, 2))));
        ElementScreen screen = screen(view);
        Cursor cursor = screen.cursor();
        assertThat(cursor).isNotNull();
        assertThat(cursor.row()).as("padding row + frame row").isEqualTo(2);
        assertThat(cursor.column())
                .as("padding column + frame column + local column")
                .isEqualTo(4);
        assertThat(screen.line(2)).as("the caret is over the painted text").contains("│hello");
    }

    @Test
    void aCaretIsTranslatedIntoASplitPaneAndInsideAColumn() {
        Element<Object> view = Elements.column(
                Elements.spacer(1),
                Elements.row(Elements.grow(caretAt("left", false, 0, 0)), Elements.grow(caretAt("right", true, 0, 0))));
        Cursor cursor = screen(view).cursor();
        assertThat(cursor).isNotNull();
        assertThat(cursor.row()).as("the gap row is above").isEqualTo(1);
        assertThat(cursor.column())
                .as("the second pane starts after half the row")
                .isEqualTo(12);
    }

    @Test
    void onlyTheFocusedWidgetPlacesTheFrameCaret() {
        Element<Object> focusedLeft =
                Elements.row(Elements.grow(caretAt("left", true, 0, 0)), Elements.grow(caretAt("right", false, 0, 0)));
        assertThat(screen(focusedLeft).cursor()).satisfies(cursor -> {
            assertThat(cursor.row()).isZero();
            assertThat(cursor.column()).isZero();
        });

        Element<Object> focusedRight =
                Elements.row(Elements.grow(caretAt("left", false, 0, 0)), Elements.grow(caretAt("right", true, 0, 0)));
        assertThat(screen(focusedRight).cursor()).satisfies(cursor -> {
            assertThat(cursor.column()).as("the right pane starts at twelve").isEqualTo(12);
        });

        Element<Object> neither =
                Elements.row(Elements.grow(caretAt("left", false, 0, 0)), Elements.grow(caretAt("right", false, 0, 0)));
        assertThat(screen(neither).cursor()).as("no focused widget, no caret").isNull();
    }

    @Test
    void aModalLayerIsAScreenOfItsOwnForTheCaret() {
        Element<Object> base = caretAt("base", true, 0, 0);
        Element<Object> dialog = Elements.pad(2, caretAt("panel", true, 0, 0));
        assertThat(screen(new Element.Modal<>(Elements.column(base, dialog))).cursor())
                .as("the dialog's own caret")
                .satisfies(cursor -> {
                    assertThat(cursor.row())
                            .as("the dialog's first row, inset by two")
                            .isEqualTo(3);
                    assertThat(cursor.column()).isEqualTo(2);
                });
        assertThat(screen(Elements.stack(
                                Elements.column(base), new Element.Modal<>(Elements.column(Elements.text("panel")))))
                        .cursor())
                .as("a dialog without a caret hides the one behind it")
                .isNull();
        assertThat(screen(Elements.stack(Elements.column(base), Elements.column(Elements.text("panel"))))
                        .cursor())
                .as("a non-modal layer does not")
                .isNotNull();
    }

    @Test
    void aCaretOnTheSecondHalfOfAWideGlyphMovesToTheGlyphItBelongsTo() {
        Screen painted = new Screen(1, 4);
        ElementScreen screen = screen(Elements.column(caretAt("\u754c x", true, 0, 1)), 1, 4);
        painted.put(0, 0, "\u754c", Style.NONE, null, 2);

        Cursor resolved = Carets.resolve(painted, Cursor.at(0, 1));

        assertThat(resolved).as("the continuation cell is not addressable").isNotNull();
        assertThat(resolved.row()).isZero();
        assertThat(resolved.column()).isZero();
        assertThat(screen.lines()).isNotNull();
    }

    @Test
    void aCaretOffTheScreenOrOutsideItsWidgetIsHidden() {
        Screen small = new Screen(4, 10);
        assertThat(Carets.resolve(small, Cursor.at(0, 30)))
                .as("beyond the last column")
                .isNull();
        assertThat(Carets.resolve(small, Cursor.at(4, 0)))
                .as("below the last row")
                .isNull();
        assertThat(Carets.resolve(new Screen(0, 0), Cursor.at(0, 0)))
                .as("a zero-sized frame shows nothing")
                .isNull();
        // A widget whose caret is outside the rectangle it was arranged in is reporting a cell it
        // never painted, so the frame does not present it.
        Element<Object> escaped =
                new Element.Leaf<>(new CaretField(0, 40), new CaretModel("x", true), Sizing.measured());
        assertThat(screen(escaped).cursor()).isNull();
    }

    @Test
    void aVirtualCursorMarksTheCellAndANativeOneDoesNot() {
        Screen screen = new Screen(1, 3);
        screen.put(0, 0, "a", Style.NONE, null, 1);
        screen.put(0, 1, "b", Style.NONE, null, 1);
        screen.put(0, 2, "c", Style.NONE, null, 1);

        Carets.mark(screen, Cursor.at(0, 1).withStrategy(Cursor.Strategy.VIRTUAL));

        assertThat(screen.style(0, 1).invert()).as("the caret cell is reversed").isTrue();
        assertThat(screen.style(0, 0).invert()).as("its neighbours are not").isFalse();
        assertThat(screen.plainLine(0)).as("the text under the caret is kept").isEqualTo("abc");

        Screen nativeScreen = new Screen(1, 3);
        nativeScreen.put(0, 0, "a", Style.NONE, null, 1);
        Carets.mark(nativeScreen, Cursor.at(0, 0));
        assertThat(nativeScreen.style(0, 0).isPlain())
                .as("a native cursor is not painted into the frame")
                .isTrue();
    }

    @Test
    void aVirtualCaretPastTheTextMarksACellThatWouldOtherwiseBeBlank() {
        Screen screen = new Screen(1, 4);
        screen.put(0, 0, "a", Style.NONE, null, 1);
        screen.put(0, 1, "b", Style.NONE, null, 1);
        screen.put(0, 2, "c", Style.NONE, null, 1);

        Carets.mark(screen, Cursor.at(0, 3).withStrategy(Cursor.Strategy.VIRTUAL));

        assertThat(screen.text(0, 3)).as("a blank cell needs a painted space").isEqualTo(" ");
        assertThat(screen.style(0, 3).invert()).isTrue();
        assertThat(screen.plainLine(0)).as("trailing padding is still trimmed").isEqualTo("abc");
    }

    @Test
    void aHiddenCursorShowsNothingAtAll() {
        Element<Object> hidden = new Element.Leaf<>(
                new CaretField(0, 0, Cursor.Mode.HIDDEN), new CaretModel("abc", true), Sizing.measured());
        ElementScreen screen = screen(hidden);
        assertThat(screen.cursor()).isNull();
        assertThat(screen.canvas().style(0, 0).isPlain()).isTrue();
    }

    @Test
    void aOneCellViewportAndAnAbsurdPositionStayBounded() {
        ElementScreen single = screen(Elements.column(caretAt("x", true, 0, 0)), 1, 1);
        assertThat(single.cursor()).as("the only cell of a one-cell screen").isNotNull();

        assertThat(screen(Elements.column(caretAt(" ", true, 0, 0)), 4, 4).cursor())
                .as("a caret over a blank cell is still a cell")
                .isNotNull();
        assertThat(Carets.resolve(new Screen(4, 4), Cursor.at(Integer.MAX_VALUE, Integer.MAX_VALUE)))
                .as("an unrepresentable position is dropped, not wrapped onto the screen")
                .isNull();
    }

    @Test
    void aMaskedEditorCountsOneCellPerClusterForItsCaret() {
        Editor editor = new Editor(EditorConfig.defaults()
                .withMasking(EditorConfig.Masking.PASSWORD)
                .withCursor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.BLINK)));
        Editor.Model model = editor.init(new InitContext(VIEWPORT, null)).model();
        model = editor.update(model, new Editor.Msg.Insert("\u754c\u754c"))
                .model()
                .withFocus(true);
        ElementScreen screen = screen(editor.view(model, CONTEXT));
        assertThat(screen.cursor()).isNotNull();
        assertThat(screen.cursor().column())
                .as("two mask glyphs occupy two cells, however wide the cleartext is")
                .isEqualTo(2);
        assertThat(screen.line(0)).as("the draft is masked").isEqualTo("\u2022\u2022");
    }

    @Test
    void layoutIsPureSoTheSameTreeResolvesTheSameCaret() {
        Element<Object> view = Elements.column(caretAt("alpha\nbeta", true, 1, 1));
        ElementScreen first = screen(view);
        ElementScreen second = screen(view);
        assertThat(second.lines()).isEqualTo(first.lines());
        assertThat(second.cursor()).isEqualTo(first.cursor());
    }

    // ── the editor's own caret ───────────────────────────────────────────────

    /**
     * One slot in a parent model, so an editor can be mounted the way an application mounts it.
     */
    private record Field(Editor.Model model) {}

    private static Editor editor(Editor.CursorPolicy policy) {
        return new Editor(EditorConfig.defaults().withPrompt("> ", Style.NONE).withCursor(policy));
    }

    private static Editor.Model typed(Editor editor, String text) {
        Editor.Model model = editor.init(new InitContext(VIEWPORT, null)).model();
        return editor.update(model, new Editor.Msg.Insert(text)).model().withFocus(true);
    }

    @Test
    void adefaultEditorKeepsThePaintedBlockAndDeclaresNoCursor() {
        Editor editor = new Editor(EditorConfig.defaults());
        assertThat(EditorConfig.defaults().cursor().caret()).isEqualTo(Editor.Caret.PAINTED);
        ElementScreen screen = screen(editor.view(typed(editor, "ab"), CONTEXT));
        assertThat(screen.cursor())
                .as("the painted block is content, not a cursor")
                .isNull();
        assertThat(screen.line(0)).as("the block is still painted at the caret").isEqualTo("ab\u2588");
    }

    @Test
    void aNativeEditorDeclaresItsCaretCellAndPaintsNoBlock() {
        Editor editor = editor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.STEADY));
        ElementScreen screen = screen(editor.view(typed(editor, "ab"), CONTEXT));
        Cursor cursor = screen.cursor();
        assertThat(cursor).isNotNull();
        assertThat(cursor.row()).isZero();
        assertThat(cursor.column()).as("the prompt occupies two cells").isEqualTo(4);
        assertThat(cursor.shape()).isEqualTo(Cursor.Shape.BLOCK);
        assertThat(cursor.mode()).isEqualTo(Cursor.Mode.STEADY);
        assertThat(cursor.strategy()).isEqualTo(Cursor.Strategy.NATIVE);
        assertThat(screen.line(0)).as("no block glyph is painted").isEqualTo("> ab");
    }

    @Test
    void aVirtualEditorDeclaresAPaintedCaretAndMarksItsCell() {
        Editor editor = editor(Editor.CursorPolicy.virtualCursor(Cursor.Shape.BLOCK, Cursor.Mode.STEADY));
        ElementScreen screen = screen(editor.view(typed(editor, "ab"), CONTEXT));
        Cursor cursor = screen.cursor();
        assertThat(cursor).isNotNull();
        assertThat(cursor.strategy()).isEqualTo(Cursor.Strategy.VIRTUAL);
        // The runtime paints a virtual caret into the frame; here the frame is the painted cells, so
        // the marker is applied the way the runtime applies it.
        Screen painted = new Screen(VIEWPORT.rows(), VIEWPORT.cols());
        Surface surface = painted;
        Element<Editor.Msg> tree = editor.view(typed(editor, "ab"), CONTEXT);
        io.github.dhoard.braxton.ui.ElementTree.paint(
                io.github.dhoard.braxton.ui.ElementTree.layout(tree, VIEWPORT.bounds()), surface);
        Cursor placed = Carets.resolve(
                painted,
                InteractionRouter.cursor(io.github.dhoard.braxton.ui.ElementTree.layout(tree, VIEWPORT.bounds())));
        Carets.mark(painted, placed);
        assertThat(painted.style(0, 4).invert())
                .as("the caret cell is reversed")
                .isTrue();
    }

    @Test
    void anEditorCaretFollowsTheLineAndThePromptOfTheWindowItIsIn() {
        Editor editor = editor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BAR, Cursor.Mode.BLINK));
        Editor.Model model = typed(editor, "one\r\ntwo");
        Cursor cursor = screen(editor.view(model, CONTEXT)).cursor();
        assertThat(cursor).isNotNull();
        assertThat(cursor.row()).as("the caret is on the second painted row").isEqualTo(1);
        assertThat(cursor.column())
                .as("continuation rows carry the prompt's width")
                .isEqualTo(5);

        Editor.Model home = editor.update(model, new Editor.Msg.MoveHome()).model();
        Cursor atStart = screen(editor.view(home, CONTEXT)).cursor();
        assertThat(atStart).isNotNull();
        assertThat(atStart.column())
                .as("the start of a continuation row is the prompt indent")
                .isEqualTo(2);
    }

    @Test
    void anEditorCaretIsPlacedInTheWindowTheEditorPaints() {
        Editor editor = new Editor(EditorConfig.defaults()
                .withPrompt("> ", Style.NONE)
                .withVisibleLines(2)
                .withCursor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.BLINK)));
        Editor.Model model = editor.init(new InitContext(VIEWPORT, null)).model();
        model = editor.update(model, new Editor.Msg.Insert("one\r\ntwo\r\nthree\r\nfour"))
                .model()
                .withFocus(true);
        ElementScreen screen = screen(editor.view(model, CONTEXT));
        Cursor cursor = screen.cursor();
        assertThat(cursor).isNotNull();
        assertThat(cursor.row()).as("the caret is on the window's last row").isEqualTo(1);
        assertThat(cursor.column())
                .as("at the end of the last line, past the prompt")
                .isEqualTo(6);
        assertThat(screen.line(0)).as("the window scrolled to the caret").isEqualTo("> three");
    }

    @Test
    void aMountedEditorPlacesItsCaret() {
        Editor editor = editor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.BLINK));
        Mount<Field, Object, Never, Editor.Model, Editor.Msg, Editor.Output> mount = Mount.of(
                ComponentId.of("field"),
                editor,
                io.github.dhoard.braxton.mvu.ModelLens.of(Field::model, (parent, next) -> new Field(next)),
                message -> message,
                OutputRoute.toMessage(output -> output));
        ElementScreen screen = screen(mount.view(new Field(typed(editor, "ab")), CONTEXT));

        // The caret belongs to the widget that paints it, so a mounted editor places the same cell
        // an unmounted one does: the mount maps its tree, not its caret.
        assertThat(screen.cursor()).isNotNull();
        assertThat(screen.cursor().column()).isEqualTo(4);
    }

    @Test
    void anUnfocusedEditorShowsNoCaretAtAll() {
        Editor editor = editor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.BLINK));
        Editor.Model model = typed(editor, "ab").withFocus(false);
        ElementScreen screen = screen(editor.view(model, CONTEXT));
        assertThat(screen.cursor()).isNull();
        assertThat(screen.line(0)).isEqualTo("> ab");
    }

    // ── R-03: sequence construction ──────────────────────────────────────────

    @Test
    void everyShapeAndBlinkPairHasItsOwnDecscusrParameter() {
        assertThat(AnsiEncoder.cursorStyle(Cursor.Shape.BLOCK, true)).isEqualTo("\u001B[1 q");
        assertThat(AnsiEncoder.cursorStyle(Cursor.Shape.BLOCK, false)).isEqualTo("\u001B[2 q");
        assertThat(AnsiEncoder.cursorStyle(Cursor.Shape.UNDERLINE, true)).isEqualTo("\u001B[3 q");
        assertThat(AnsiEncoder.cursorStyle(Cursor.Shape.UNDERLINE, false)).isEqualTo("\u001B[4 q");
        assertThat(AnsiEncoder.cursorStyle(Cursor.Shape.BAR, true)).isEqualTo("\u001B[5 q");
        assertThat(AnsiEncoder.cursorStyle(Cursor.Shape.BAR, false)).isEqualTo("\u001B[6 q");
        assertThat(AnsiEncoder.moveCursor(0, 0)).isEqualTo("\u001B[1;1H");
        assertThat(AnsiEncoder.moveCursor(23, 79)).isEqualTo("\u001B[24;80H");
        assertThat(AnsiEncoder.cursorColor(new Color(0xFF, 0x0A, 0x00)))
                .as("every channel is emitted as two hex digits")
                .isEqualTo("\u001B]12;rgb:ff/0a/00\u001B\\");
        assertThat(AnsiEncoder.RESET_CURSOR_STYLE).isEqualTo("\u001B[0 q");
    }

    @Test
    void aPresenterWritesOnlyWhatChanged() {
        CursorPresenter presenter = new CursorPresenter(true);
        Cursor cursor = Cursor.at(2, 3).withShape(Cursor.Shape.UNDERLINE).withMode(Cursor.Mode.STEADY);
        String first = presenter.frame(Optional.of(cursor));
        assertThat(first).as("shape, move, then show").isEqualTo("\u001B[4 q" + "\u001B[3;4H" + "\u001B[?25h");
        assertThat(presenter.frame(Optional.of(cursor))).as("nothing changed").isEmpty();

        String moved = presenter.frame(Optional.of(cursor.movedTo(2, 4)));
        assertThat(moved).as("a move only").isEqualTo("\u001B[3;5H");

        String shaped = presenter.frame(Optional.of(cursor.movedTo(2, 4).withShape(Cursor.Shape.BAR)));
        assertThat(shaped).as("a shape only").isEqualTo("\u001B[6 q");

        String coloured = presenter.frame(
                Optional.of(cursor.movedTo(2, 4).withShape(Cursor.Shape.BAR).withColor(Color.WHITE)));
        assertThat(coloured).isEqualTo("\u001B]12;rgb:ff/ff/ff\u001B\\");

        String cleared = presenter.frame(Optional.of(cursor.movedTo(2, 4)
                .withShape(Cursor.Shape.BAR)
                .withColor(Color.WHITE)
                .withMode(Cursor.Mode.HIDDEN)));
        assertThat(cleared).as("hidden forgets the position").isEqualTo("\u001B[?25l");
        assertThat(presenter.frame(Optional.empty())).as("already hidden").isEmpty();
        assertThat(presenter.frame(Optional.of(cursor)))
                .as("showing again puts the shape back, drops the color, moves and shows")
                .isEqualTo("\u001B[4 q" + "\u001B]112\u001B\\" + "\u001B[3;4H" + "\u001B[?25h");
    }

    @Test
    void paintingContentRestatesTheCaretBecauseTheFrameMovedTheTerminalCursor() {
        CursorPresenter presenter = new CursorPresenter(true);
        Cursor cursor = Cursor.at(1, 4);
        presenter.frame(Optional.of(cursor));
        assertThat(presenter.frame(Optional.of(cursor)))
                .as("an idle frame writes nothing")
                .isEmpty();
        presenter.contentPainted();
        assertThat(presenter.frame(Optional.of(cursor)))
                .as("a frame that repainted moved the cursor, so the caret is put back")
                .isEqualTo("\u001B[2;5H");
    }

    @Test
    void aPresenterHidesTheCursorForAVirtualCaretAndEmitsNoColourWhenColourIsOff() {
        CursorPresenter virtual = new CursorPresenter(true);
        assertThat(virtual.frame(Optional.of(Cursor.at(0, 0)))).contains("\u001B[?25h");
        assertThat(virtual.frame(Optional.of(Cursor.at(0, 0).withStrategy(Cursor.Strategy.VIRTUAL))))
                .as("a painted caret leaves the terminal cursor hidden")
                .isEqualTo("\u001B[?25l");

        CursorPresenter plain = new CursorPresenter(false);
        assertThat(plain.frame(Optional.of(Cursor.at(1, 1).withColor(Color.WHITE))))
                .as("a session that emits no color emits no cursor color")
                .isEqualTo("\u001B[1 q" + "\u001B[2;2H" + "\u001B[?25h");
        assertThat(plain.reset()).as("nothing colored to reset").isEqualTo("\u001B[0 q");
    }

    @Test
    void aResetPutsBackOnlyWhatTheSessionChangedAndOnlyOnce() {
        CursorPresenter presenter = new CursorPresenter(true);
        assertThat(presenter.reset())
                .as("a session that never moved the cursor resets nothing")
                .isEmpty();
        presenter.frame(Optional.of(Cursor.at(0, 0).withColor(Color.WHITE)));
        presenter.frame(Optional.of(Cursor.at(1, 1).withColor(Color.WHITE)));
        assertThat(presenter.reset())
                .as("the shape and the color, in that order, are the whole restoration")
                .isEqualTo("\u001B[0 q" + "\u001B]112\u001B\\");
        assertThat(presenter.reset())
                .as("a second exit path writes nothing more")
                .isEmpty();
    }

    // ── R-04: the demo scene ─────────────────────────────────────────────────

    private static CursorDemo.Model send(CursorDemo.Scene scene, CursorDemo.Model model, CursorDemo.Msg... messages) {
        CursorDemo.Model state = model;
        for (CursorDemo.Msg message : messages) {
            state = scene.update(state, message).model();
        }
        return state;
    }

    private static ElementScreen demo(CursorDemo.Scene scene, CursorDemo.Model model) {
        return ElementScreen.render(scene.view(model, new ViewContext(new Viewport(24, 80))), 24, 80);
    }

    @Test
    void theDemoShowsTheFocusedPaneCaretOnlyAndMovesItThroughTheOtherPane() {
        CursorDemo.Scene scene = new CursorDemo.Scene();
        CursorDemo.Model model =
                scene.init(new InitContext(new Viewport(24, 80), null)).model();
        assertThat(demo(scene, model).cursor()).as("the left pane's caret").satisfies(cursor -> {
            assertThat(cursor.row()).isEqualTo(4);
            assertThat(cursor.column()).isEqualTo(1);
        });

        CursorDemo.Model right = send(scene, model, new CursorDemo.Msg.ToggleFocus());
        assertThat(demo(scene, right).cursor()).as("the right pane's caret").satisfies(cursor -> {
            assertThat(cursor.row()).isEqualTo(4);
            assertThat(cursor.column()).as("the second pane starts at forty").isEqualTo(41);
        });

        CursorDemo.Model down = send(scene, model, new CursorDemo.Msg.Move(1, 0));
        assertThat(demo(scene, down).cursor())
                .satisfies(cursor ->
                        assertThat(cursor.row()).as("a row of the pane").isEqualTo(5));
    }

    @Test
    void theDemoSwitchesStrategyAndShapeAndOnlyAVirtualCaretUsesAClock() {
        CursorDemo.Scene scene = new CursorDemo.Scene();
        CursorDemo.Model model =
                scene.init(new InitContext(new Viewport(24, 80), null)).model();
        assertThat(scene.subscriptions(model))
                .as("a native caret runs no timer")
                .isEqualTo(Subscription.none());

        CursorDemo.Model virtual =
                send(scene, model, new CursorDemo.Msg.ToggleStrategy(), new CursorDemo.Msg.CycleShape());
        assertThat(virtual.strategy()).isEqualTo(Cursor.Strategy.VIRTUAL);
        assertThat(virtual.shape()).isEqualTo(Cursor.Shape.UNDERLINE);
        assertThat(scene.subscriptions(virtual))
                .as("a blinking virtual caret ticks")
                .isNotEqualTo(Subscription.none());
        ElementScreen screen = demo(scene, virtual);
        assertThat(screen.cursor()).isNotNull();
        assertThat(screen.cursor().strategy()).isEqualTo(Cursor.Strategy.VIRTUAL);

        CursorDemo.Model off = send(scene, virtual, new CursorDemo.Msg.Tick());
        assertThat(demo(scene, off).cursor())
                .as("the second half of the period is blank")
                .isNull();
        CursorDemo.Model on = send(scene, off, new CursorDemo.Msg.Tick());
        assertThat(demo(scene, on).cursor()).as("and it comes back").isNotNull();
        assertThat(scene.subscriptions(send(scene, on, new CursorDemo.Msg.ToggleMode())))
                .as("a steady virtual caret does not need the clock either")
                .isEqualTo(Subscription.none());
    }

    @Test
    void theDemoResolvesACaretSteppedOntoTheSecondHalfOfAWideGlyph() {
        CursorDemo.Scene scene = new CursorDemo.Scene();
        CursorDemo.Model model =
                scene.init(new InitContext(new Viewport(24, 80), null)).model();
        CursorDemo.Model stepped = send(scene, model, new CursorDemo.Msg.Move(1, 6));
        assertThat(demo(scene, stepped).cursor()).satisfies(cursor -> {
            assertThat(cursor.row()).isEqualTo(5);
            assertThat(cursor.column()).as("the glyph starts one cell left").isEqualTo(6);
        });
    }

    @Test
    void theDemoCanBeLaunchedFromAMain() {
        assertThat(java.util.Arrays.stream(CursorDemo.class.getDeclaredMethods())
                        .filter(method -> method.getName().equals("main"))
                        .count())
                .as("the packaged demo needs an entry point")
                .isEqualTo(1);
        assertThat(CursorDemo.defaultKeys().actions()).isNotEmpty();
        assertThat(CursorDemo.PANE_WIDTH).isEqualTo(40);
    }

    @Test
    void aFrameWithoutAFocusedWidgetShowsNoCaret() {
        Element<Object> tree = Elements.column(Elements.text("plain"));
        assertThat(InteractionRouter.cursor(
                        io.github.dhoard.braxton.ui.ElementTree.layout(tree, new Rect(0, 0, 2, 10))))
                .as("a tree nobody focuses has no caret to present")
                .isNull();
    }
}

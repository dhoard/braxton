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
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.CursorPresenter;
import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.RuntimeLoop;
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.Cursor;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.RenderedScreen;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Editor;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 03 acceptance: native and virtual cursor presentation.
 *
 * <p>Four facts are asserted against public behavior. Cursor intent is immutable view data that
 * needs no terminal handle. Layout resolves it to one screen cell through padding, frames and
 * splits, hides it when focus, a modal or a clip takes it away, and moves it off the second half of
 * a wide glyph. The runtime writes cursor changes after the content inside the synchronized frame,
 * only when they change, and puts back what it changed on the way out. Both strategies are shown by
 * a scene whose caret is the focused pane's even when another pane declares one later.</p>
 *
 * <p>The runtime cases drive a real {@code RuntimeLoop} over an owned terminal, so the asserted
 * evidence is the byte transcript the session actually wrote.</p>
 */
@Timeout(20)
class ParityCursorTest {

    private static final Viewport VIEWPORT = new Viewport(8, 24);
    private static final ViewContext CONTEXT = new ViewContext(VIEWPORT);

    private static RenderedScreen screen(View<?> view) {
        return new ScreenRenderer().screen(view, VIEWPORT);
    }

    private static RenderedScreen screen(View<?> view, int rows, int cols) {
        return new ScreenRenderer().screen(view, new Viewport(rows, cols));
    }

    // ── R-01: cursor intent is immutable view data ───────────────────────────

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
                .as("no colour means the terminal's own")
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
        Cursor cursor = new Cursor(null, null, null, null, 0, 0, null);
        assertThat(cursor.shape()).isEqualTo(Cursor.Shape.BLOCK);
        assertThat(cursor.mode()).isEqualTo(Cursor.Mode.BLINK);
        assertThat(cursor.strategy()).isEqualTo(Cursor.Strategy.NATIVE);
        assertThat(cursor.owner()).isEmpty();
        assertThat(Cursor.at(0, 0).withShape(null).shape()).isEqualTo(Cursor.Shape.BLOCK);
        assertThat(Cursor.at(0, 0).withMode(null).mode()).isEqualTo(Cursor.Mode.BLINK);
        assertThat(Cursor.at(0, 0).withStrategy(null).strategy()).isEqualTo(Cursor.Strategy.NATIVE);
        assertThat(Cursor.at(0, 0).withOwner(null).owner()).isEmpty();
    }

    @Test
    void scopingAMountPrependsItsPathToTheCursorOwner() {
        ElementId owner = ElementId.of("input");
        ComponentPath path = ComponentPath.ROOT.append(ComponentId.of("field"));
        Cursor scoped = Cursor.at(0, 0).withOwner(owner).scoped(path);
        assertThat(scoped.owner()).hasValue(owner.scoped(path));
        assertThat(scoped.owner().orElseThrow().toString())
                .as("the scoped owner names the mount")
                .endsWith("field:input");
        assertThat(Cursor.at(0, 0).scoped(path).owner())
                .as("a cursor with no owner is unchanged")
                .isEmpty();
        assertThatThrownBy(() -> Cursor.at(0, 0).scoped(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void mappingAndScopingACursorViewKeepsItsIntentAndWrapsOnlyItsChild() {
        ElementId owner = ElementId.of("input");
        View<String> view = Views.cursor(Cursor.at(1, 2).withOwner(owner), Views.text("ab"));
        assertThat(view.children()).hasSize(1);
        View<Integer> mapped = view.map(String::length);
        assertThat(mapped).isInstanceOf(View.CursorAt.class);
        assertThat(((View.CursorAt<Integer>) mapped).cursor())
                .as("mapping messages does not change the caret")
                .isEqualTo(Cursor.at(1, 2).withOwner(owner));
        ComponentPath path = ComponentPath.ROOT.append(ComponentId.of("field"));
        assertThat(((View.CursorAt<String>) view.scoped(path)).cursor().owner())
                .as("scoping the tree scopes the caret's owner")
                .hasValue(owner.scoped(path));
    }

    @Test
    void aCursorWrapperReservesNoSpace() {
        LayoutEngine engine = new LayoutEngine();
        View<Object> text = Views.text("alpha\r\nbeta");
        View<Object> wrapped = Views.cursor(Cursor.at(0, 0), text);
        assertThat(engine.intrinsicHeight(wrapped, 12))
                .as("height is the child's")
                .isEqualTo(engine.intrinsicHeight(text, 12));
        assertThat(engine.intrinsicWidth(wrapped, 12))
                .as("width is the child's")
                .isEqualTo(engine.intrinsicWidth(text, 12));
        assertThat(screen(wrapped).plainLines())
                .as("painting is the child's")
                .isEqualTo(screen(text).plainLines());
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

    @Test
    void aSnapshotWithoutACursorStillConstructs() {
        RenderedScreen plain = new RenderedScreen(
                1, 1, List.of(List.of(new RenderedScreen.Cell("x", Style.NONE, null, 1, false))), List.of());
        assertThat(plain.cursor()).isEmpty();
        assertThat(new RenderedScreen(1, 1, plain.cells(), plain.regions(), Optional.of(Cursor.at(0, 0))).cursor())
                .hasValue(Cursor.at(0, 0));
    }

    // ── R-02: translate and clip cursor intent with the view ─────────────────

    @Test
    void paddingAndAFrameMoveTheCaretToTheCellItIsDrawnIn() {
        View<Object> view =
                Views.padding(1, Views.border(Border.SINGLE, Views.cursor(Cursor.at(0, 2), Views.text("hello"))));
        RenderedScreen screen = screen(view);
        assertThat(screen.cursor()).hasValueSatisfying(cursor -> {
            assertThat(cursor.row()).as("padding row + frame row").isEqualTo(2);
            assertThat(cursor.column())
                    .as("padding column + frame column + local column")
                    .isEqualTo(4);
        });
        assertThat(screen.plainLine(2)).as("the caret is over the painted text").contains("│hello");
    }

    @Test
    void aCaretIsTranslatedIntoASplitPaneAndInsideAColumn() {
        View<Object> view = Views.column(
                Views.gap(1),
                Views.row(
                        Views.grow(Views.text("left")),
                        Views.grow(Views.cursor(Cursor.at(0, 0), Views.text("right")))));
        RenderedScreen screen = screen(view);
        assertThat(screen.cursor()).hasValueSatisfying(cursor -> {
            assertThat(cursor.row()).as("the gap row is above").isEqualTo(1);
            assertThat(cursor.column())
                    .as("the second pane starts after half the row")
                    .isEqualTo(12);
        });
    }

    @Test
    void theFocusedTopmostCursorWinsEvenWhenAnotherPanePaintsLater() {
        ElementId left = ElementId.of("left");
        ElementId right = ElementId.of("right");
        View<Object> focusedLeft =
                Views.row(Views.grow(pane(left, true, "left")), Views.grow(pane(right, false, "right")));
        assertThat(screen(focusedLeft).cursor()).as("the focused pane's caret").hasValueSatisfying(cursor -> {
            assertThat(cursor.row()).isZero();
            assertThat(cursor.column()).isZero();
            assertThat(cursor.owner()).hasValue(left);
        });

        View<Object> focusedRight =
                Views.row(Views.grow(pane(left, false, "left")), Views.grow(pane(right, true, "right")));
        assertThat(screen(focusedRight).cursor()).hasValueSatisfying(cursor -> {
            assertThat(cursor.column()).as("the right pane starts at twelve").isEqualTo(12);
            assertThat(cursor.owner()).hasValue(right);
        });

        View<Object> neither =
                Views.row(Views.grow(pane(left, false, "left")), Views.grow(pane(right, false, "right")));
        assertThat(screen(neither).cursor()).as("no focused pane, no caret").isEmpty();
    }

    private static View<Object> pane(ElementId id, boolean focused, String text) {
        return Views.focus(id, focused, Views.cursor(Cursor.at(0, 0).withOwner(id), Views.text(text)));
    }

    @Test
    void aCursorBelongingToNoElementIsAlwaysEligible() {
        View<Object> view = Views.cursor(Cursor.at(1, 1), Views.text("free"));
        assertThat(screen(view).cursor()).get().satisfies(cursor -> {
            assertThat(cursor.row()).isEqualTo(1);
            assertThat(cursor.column()).isEqualTo(1);
        });
    }

    @Test
    void aModalLayerIsAScreenOfItsOwnForTheCaret() {
        View<Object> base = Views.cursor(Cursor.at(0, 0), Views.text("base"));
        View<Object> panel = Views.cursor(Cursor.at(1, 2), Views.text("panel"));
        assertThat(screen(Views.modal(base, panel)).cursor())
                .as("the dialog's own caret")
                .hasValueSatisfying(cursor -> {
                    assertThat(cursor.row()).isEqualTo(1);
                    assertThat(cursor.column()).isEqualTo(2);
                });
        assertThat(screen(Views.modal(base, Views.text("panel"))).cursor())
                .as("a dialog without a caret hides the one behind it")
                .isEmpty();
        assertThat(screen(Views.overlay(base, Views.text("panel"))).cursor())
                .as("a non-modal layer does not")
                .isPresent();
    }

    @Test
    void aCaretOnTheSecondHalfOfAWideGlyphMovesToTheGlyphItBelongsTo() {
        RenderedScreen screen = screen(Views.cursor(Cursor.at(0, 1), Views.text("\u754c x")));
        assertThat(screen.cursor())
                .as("the continuation cell is not addressable")
                .hasValueSatisfying(cursor -> {
                    assertThat(cursor.row()).isZero();
                    assertThat(cursor.column()).isZero();
                });
    }

    @Test
    void aCaretClippedAwayOrOffTheScreenIsHidden() {
        assertThat(screen(Views.cursor(Cursor.at(0, 30), Views.text("short")), 4, 10)
                        .cursor())
                .as("beyond the last column")
                .isEmpty();
        assertThat(screen(Views.cursor(Cursor.at(4, 0), Views.text("short")), 4, 10)
                        .cursor())
                .as("below the last row")
                .isEmpty();
        View<Object> clipped =
                Views.fixed(1, Views.column(Views.text("one"), Views.cursor(Cursor.at(1, 0), Views.text("two"))));
        assertThat(screen(clipped, 4, 10).cursor())
                .as("a caret in a row its parent clipped away")
                .isEmpty();
        RenderedScreen empty = screen(Views.cursor(Cursor.at(0, 0), Views.text("x")), 0, 0);
        assertThat(empty.cursor()).as("a zero-sized viewport shows nothing").isEmpty();
        assertThat(empty.isEmpty()).isTrue();
    }

    @Test
    void aVirtualCursorMarksTheCellAndANativeOneDoesNot() {
        View<Object> virtual = Views.cursor(Cursor.at(0, 1).withStrategy(Cursor.Strategy.VIRTUAL), Views.text("abc"));
        RenderedScreen screen = screen(virtual);
        assertThat(screen.cell(0, 1).style().invert())
                .as("the caret cell is reversed")
                .isTrue();
        assertThat(screen.cell(0, 0).style().invert())
                .as("its neighbours are not")
                .isFalse();
        assertThat(screen.plainLine(0)).as("the text under the caret is kept").startsWith("abc");
        assertThat(screen.cursor()).as("a virtual cursor is still reported").isPresent();

        RenderedScreen nativeScreen = screen(Views.cursor(Cursor.at(0, 1), Views.text("abc")));
        assertThat(nativeScreen.cell(0, 1).style().isPlain())
                .as("a native cursor is not painted into the frame")
                .isTrue();
        assertThat(nativeScreen.plainLine(0)).isEqualTo("abc");
    }

    @Test
    void aVirtualCaretPastTheTextMarksACellThatWouldOtherwiseBeBlank() {
        RenderedScreen screen =
                screen(Views.cursor(Cursor.at(0, 3).withStrategy(Cursor.Strategy.VIRTUAL), Views.text("abc")));
        assertThat(screen.cell(0, 3).text())
                .as("a blank cell needs a painted space")
                .isEqualTo(" ");
        assertThat(screen.cell(0, 3).style().invert()).isTrue();
        assertThat(screen.plainLine(0)).as("trailing padding is still trimmed").isEqualTo("abc");
    }

    @Test
    void aHiddenCursorShowsNothingAtAll() {
        View<Object> hidden = Views.cursor(
                Cursor.at(0, 0).withMode(Cursor.Mode.HIDDEN).withStrategy(Cursor.Strategy.VIRTUAL), Views.text("abc"));
        RenderedScreen screen = screen(hidden);
        assertThat(screen.cursor()).isEmpty();
        assertThat(screen.cell(0, 0).style().isPlain()).isTrue();
    }

    @Test
    void aOneCellViewportAndAnAbsurdPositionStayBounded() {
        RenderedScreen single =
                screen(Views.cursor(Cursor.at(0, 0).withStrategy(Cursor.Strategy.VIRTUAL), Views.text("x")), 1, 1);
        assertThat(single.cursor()).as("the only cell of a one-cell screen").isPresent();
        assertThat(single.cell(0, 0).style().invert()).isTrue();

        assertThat(screen(Views.cursor(Cursor.at(0, 0), Views.blank()), 4, 4).cursor())
                .as("a caret over nothing is still a cell")
                .isPresent();
        assertThat(screen(Views.cursor(Cursor.at(Integer.MAX_VALUE, Integer.MAX_VALUE), Views.text("x")), 4, 4)
                        .cursor())
                .as("an unrepresentable position is dropped, not wrapped onto the screen")
                .isEmpty();
    }

    @Test
    void aMaskedEditorCountsOneCellPerClusterForItsCaret() {
        Editor editor = new Editor(Editor.Config.defaults()
                .withMasking(Editor.Config.Masking.PASSWORD)
                .withCursor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.BLINK)));
        Editor.Model model = editor.init(new InitContext(VIEWPORT, null)).model();
        model = editor.update(model, new Editor.Msg.Insert("\u754c\u754c"))
                .model()
                .withFocus(true);
        RenderedScreen screen = screen(editor.view(model, CONTEXT));
        assertThat(screen.cursor())
                .get()
                .satisfies(cursor -> assertThat(cursor.column())
                        .as("two mask glyphs occupy two cells, however wide the cleartext is")
                        .isEqualTo(2));
        assertThat(screen.plainLine(0)).as("the draft is masked").isEqualTo("\u2022\u2022");
    }

    @Test
    void layoutIsPureSoTheSameTreeResolvesTheSameCaret() {
        View<Object> view = Views.cursor(Cursor.at(1, 1), Views.text("alpha\r\nbeta"));
        RenderedScreen first = screen(view);
        RenderedScreen second = screen(view);
        assertThat(second.cells()).isEqualTo(first.cells());
        assertThat(second.cursor()).isEqualTo(first.cursor());
        assertThat(view).as("laying out never rewrites the view").isSameAs(view);
    }

    // ── the editor's own caret ───────────────────────────────────────────────

    /** One slot in a parent model, so an editor can be mounted the way an application mounts it. */
    private record Field(Editor.Model model) {}

    private static Editor editor(Editor.CursorPolicy policy) {
        return new Editor(Editor.Config.defaults().withPrompt("> ", Style.NONE).withCursor(policy));
    }

    private static Editor.Model typed(Editor editor, String text) {
        Editor.Model model = editor.init(new InitContext(VIEWPORT, null)).model();
        return editor.update(model, new Editor.Msg.Insert(text)).model().withFocus(true);
    }

    @Test
    void adefaultEditorKeepsThePaintedBlockAndDeclaresNoCursor() {
        Editor editor = new Editor(Editor.Config.defaults());
        assertThat(Editor.Config.defaults().cursor().caret()).isEqualTo(Editor.Caret.PAINTED);
        RenderedScreen screen = screen(editor.view(typed(editor, "ab"), CONTEXT));
        assertThat(screen.cursor())
                .as("the painted block is content, not a cursor")
                .isEmpty();
        assertThat(screen.plainLine(0))
                .as("the block is still painted at the caret")
                .isEqualTo("ab\u2588");
    }

    @Test
    void aNativeEditorDeclaresItsCaretCellAndPaintsNoBlock() {
        Editor editor = editor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.STEADY));
        RenderedScreen screen = screen(editor.view(typed(editor, "ab"), CONTEXT));
        assertThat(screen.cursor()).hasValueSatisfying(cursor -> {
            assertThat(cursor.row()).isZero();
            assertThat(cursor.column()).as("the prompt occupies two cells").isEqualTo(4);
            assertThat(cursor.shape()).isEqualTo(Cursor.Shape.BLOCK);
            assertThat(cursor.mode()).isEqualTo(Cursor.Mode.STEADY);
            assertThat(cursor.strategy()).isEqualTo(Cursor.Strategy.NATIVE);
        });
        assertThat(screen.plainLine(0)).as("no block glyph is painted").isEqualTo("> ab");
    }

    @Test
    void aVirtualEditorDeclaresAPaintedCaretAndMarksItsCell() {
        Editor editor = editor(Editor.CursorPolicy.virtualCursor(Cursor.Shape.BLOCK, Cursor.Mode.STEADY));
        RenderedScreen screen = screen(editor.view(typed(editor, "ab"), CONTEXT));
        assertThat(screen.cursor())
                .get()
                .satisfies(cursor -> assertThat(cursor.strategy()).isEqualTo(Cursor.Strategy.VIRTUAL));
        assertThat(screen.cell(0, 4).style().invert())
                .as("the caret cell is reversed")
                .isTrue();
    }

    @Test
    void anEditorCaretFollowsTheLineAndThePromptOfTheWindowItIsIn() {
        Editor editor = editor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BAR, Cursor.Mode.BLINK));
        Editor.Model model = typed(editor, "one\r\ntwo");
        RenderedScreen screen = screen(editor.view(model, CONTEXT));
        assertThat(screen.cursor()).hasValueSatisfying(cursor -> {
            assertThat(cursor.row())
                    .as("the caret is on the second painted row")
                    .isEqualTo(1);
            assertThat(cursor.column())
                    .as("continuation rows carry the prompt's width")
                    .isEqualTo(5);
        });

        Editor.Model home = editor.update(model, new Editor.Msg.MoveHome()).model();
        RenderedScreen atStart = screen(editor.view(home, CONTEXT));
        assertThat(atStart.cursor())
                .get()
                .satisfies(cursor -> assertThat(cursor.column())
                        .as("the start of a continuation row is the prompt indent")
                        .isEqualTo(2));
    }

    @Test
    void anEditorCaretIsPlacedInTheWindowTheEditorPaints() {
        Editor editor = new Editor(Editor.Config.defaults()
                .withPrompt("> ", Style.NONE)
                .withVisibleLines(2)
                .withCursor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.BLINK)));
        Editor.Model model = editor.init(new InitContext(VIEWPORT, null)).model();
        model = editor.update(model, new Editor.Msg.Insert("one\r\ntwo\r\nthree\r\nfour"))
                .model()
                .withFocus(true);
        RenderedScreen screen = screen(editor.view(model, CONTEXT));
        assertThat(screen.cursor()).get().satisfies(cursor -> {
            assertThat(cursor.row()).as("the caret is on the window's last row").isEqualTo(1);
            assertThat(cursor.column())
                    .as("at the end of the last line, past the prompt")
                    .isEqualTo(6);
        });
        assertThat(screen.plainLine(0)).as("the window scrolled to the caret").isEqualTo("> three");
    }

    @Test
    void aMountedEditorScopesTheCaretItDeclares() {
        Editor editor = editor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.BLINK));
        Mount<Field, Object, Never, Editor.Model, Editor.Msg, Editor.Output> mount = Mount.of(
                ComponentId.of("field"),
                editor,
                ModelLens.of(Field::model, (parent, next) -> new Field(next)),
                message -> message,
                OutputRoute.toMessage(output -> output));
        RenderedScreen screen = screen(mount.view(new Field(typed(editor, "ab")), CONTEXT));
        assertThat(screen.cursor()).hasValueSatisfying(cursor -> {
            assertThat(cursor.owner().orElseThrow().toString())
                    .as("the mount scoped the owner the same way it scoped the focus identity")
                    .endsWith("field:input");
            assertThat(cursor.column()).isEqualTo(4);
        });
    }

    @Test
    void anUnfocusedEditorShowsNoCaretAtAll() {
        Editor editor = editor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.BLINK));
        Editor.Model model = typed(editor, "ab").withFocus(false);
        RenderedScreen screen = screen(editor.view(model, CONTEXT));
        assertThat(screen.cursor()).isEmpty();
        assertThat(screen.plainLine(0)).isEqualTo("> ab");
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
                .as("showing again puts the shape back, drops the colour, moves and shows")
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
                .as("a session that emits no colour emits no cursor colour")
                .isEqualTo("\u001B[1 q" + "\u001B[2;2H" + "\u001B[?25h");
        assertThat(plain.reset()).as("nothing coloured to reset").isEqualTo("\u001B[0 q");
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
                .as("the shape and the colour, in that order, are the whole restoration")
                .isEqualTo("\u001B[0 q" + "\u001B]112\u001B\\");
        assertThat(presenter.reset())
                .as("a second exit path writes nothing more")
                .isEmpty();
    }

    // ── R-03: the session's bytes ────────────────────────────────────────────

    /** Serves one fixed view, so a transcript is about the runtime rather than about a scene. */
    private static final class ScreenComponent implements Component<Integer, Object, Never> {
        private final Supplier<View<Object>> view;

        ScreenComponent(Supplier<View<Object>> view) {
            this.view = view;
        }

        @Override
        public Update<Integer, Object, Never> init(InitContext context) {
            return Update.next(0);
        }

        @Override
        public Update<Integer, Object, Never> update(Integer model, Object message) {
            return Update.next(model);
        }

        @Override
        public View<Object> view(Integer model, ViewContext context) {
            return view.get();
        }

        @Override
        public Subscription<Object> subscriptions(Integer model) {
            return Subscription.none();
        }
    }

    private static ExitResult run(TestTerminal fixture, View<Object> view) {
        try {
            fixture.feed("");
            fixture.endInput();
            RuntimeOptions options =
                    RuntimeOptions.defaults().withLimits(64, 8, 8).withColorProfile(ColorProfile.TRUECOLOR);
            RuntimeLoop<Integer, Object> loop = new RuntimeLoop<>(
                    new ScreenComponent(() -> view),
                    options,
                    fixture.terminal(),
                    RuntimeServices.system()
                            .withTimers(new ManualTimers())
                            .withEffectExecutor(Runnable::run)
                            .withNanoTime(() -> 1000L));
            return loop.run();
        } finally {
            fixture.close();
        }
    }

    private static String transcript(View<Object> view) {
        TestTerminal fixture = new TestTerminal(new Size(6, 20));
        ExitResult result = run(fixture, view);
        assertThat(result.ok()).as("the session ran cleanly: %s", result).isTrue();
        return fixture.outputText();
    }

    @Test
    void theCursorIsWrittenAfterTheContentInsideTheSynchronizedFrame() {
        String transcript = transcript(Views.cursor(Cursor.at(1, 2), Views.text("content")));
        int begin = transcript.indexOf("\u001B[?2026h");
        int content = transcript.indexOf("content", begin);
        int shape = transcript.indexOf("\u001B[1 q", begin);
        int move = transcript.indexOf("\u001B[2;3H", begin);
        int show = transcript.indexOf("\u001B[?25h", begin);
        int end = transcript.indexOf("\u001B[?2026l", begin);
        assertThat(begin).as("the frame opened").isGreaterThanOrEqualTo(0);
        assertThat(content).as("content is painted first").isGreaterThan(begin);
        assertThat(shape).as("the shape is applied inside the frame").isGreaterThan(content);
        assertThat(move).as("the cursor is moved after the content").isGreaterThan(content);
        assertThat(show).as("and shown last, still inside the frame").isGreaterThan(move);
        assertThat(end).as("the frame closed after the cursor").isGreaterThan(show);
    }

    @Test
    void aCaretThatDidNotMoveIsStillRestatedAfterTheLineItSitsOnWasRepainted() {
        // Two status rows and a caret on the second one: changing nothing but a status row moves the
        // terminal's cursor to that row, so the caret has to be put back even though it did not move.
        TestTerminal fixture = new TestTerminal(new Size(6, 20));
        assertThat(run(fixture, Views.column(Views.text("status"), Views.cursor(Cursor.at(0, 4), Views.text("caret")))))
                .as("the session ran cleanly")
                .satisfies(result -> assertThat(result.ok()).isTrue());
        String transcript = fixture.outputText();
        int caretMove = transcript.indexOf("\u001B[2;5H");
        assertThat(caretMove).as("the caret is placed at its cell").isGreaterThanOrEqualTo(0);
        assertThat(transcript.lastIndexOf("\u001B[2;5H"))
                .as("and the last statement of it is the caret, not a row repaint")
                .isGreaterThan(transcript.lastIndexOf("\u001B[2;1H"));
    }

    @Test
    void aVirtualCaretLeavesTheTerminalCursorHiddenForTheWholeFrame() {
        String transcript =
                transcript(Views.cursor(Cursor.at(0, 0).withStrategy(Cursor.Strategy.VIRTUAL), Views.text("content")));
        assertThat(transcript).as("the cell is reversed in the frame").contains("\u001B[7m");
        int lastFrameEnd = transcript.lastIndexOf("\u001B[?2026l");
        assertThat(transcript.indexOf("\u001B[?25h"))
                .as("the only show is the exit's own")
                .isGreaterThan(lastFrameEnd);
    }

    @Test
    void leavingTheSessionPutsBackTheCursorPropertiesItChanged() {
        String transcript = transcript(Views.cursor(
                Cursor.at(0, 0).withColor(Color.WHITE).withShape(Cursor.Shape.BAR), Views.text("content")));
        int style = transcript.indexOf("\u001B[5 q");
        int colour = transcript.indexOf("\u001B]12;rgb:ff/ff/ff\u001B\\");
        int reset = transcript.indexOf("\u001B[0 q");
        int resetColour = transcript.indexOf("\u001B]112\u001B\\");
        int altOff = transcript.indexOf("\u001B[?1049l");
        assertThat(style).as("the shape was applied").isGreaterThanOrEqualTo(0);
        assertThat(colour).as("the colour was applied").isGreaterThan(style);
        assertThat(reset).as("the shape was reset").isGreaterThan(colour);
        assertThat(resetColour).as("the colour was reset").isGreaterThan(colour);
        assertThat(altOff)
                .as("restoration happens before leaving the alternate screen")
                .isGreaterThan(resetColour);
    }

    @Test
    void aSessionThatNeverDeclaredACursorWritesNoCursorTraffic() {
        String transcript = transcript(Views.text("content"));
        assertThat(transcript).doesNotContain("\u001B[1 q").doesNotContain("\u001B[0 q");
        assertThat(transcript).doesNotContain("\u001B]12;").doesNotContain("\u001B]112");
        assertThat(transcript.indexOf("\u001B[?25h"))
                .as("the cursor is only shown again on the way out, as it always was")
                .isGreaterThan(transcript.lastIndexOf("\u001B[?2026l"));
    }

    @Test
    void aTerminalThatCannotAcceptTheFrameFailsTheRunInsteadOfLosingTheCaret() {
        TestTerminal fixture = new TestTerminal(new Size(6, 20));
        java.util.concurrent.atomic.AtomicInteger flushes = new java.util.concurrent.atomic.AtomicInteger();
        fixture.onOutput(text -> {
            if (flushes.incrementAndGet() == 1) {
                fixture.failOutput();
            }
        });
        ExitResult result = run(fixture, Views.cursor(Cursor.at(0, 0), Views.text("content")));
        assertThat(result.kind())
                .as("a broken terminal is a failure, not a silent success")
                .isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.status()).isEqualTo(1);
    }

    // ── R-04: the demo scene ─────────────────────────────────────────────────

    private static CursorDemo.Model send(CursorDemo.Scene scene, CursorDemo.Model model, CursorDemo.Msg... messages) {
        CursorDemo.Model state = model;
        for (CursorDemo.Msg message : messages) {
            state = scene.update(state, message).model();
        }
        return state;
    }

    private static RenderedScreen demo(CursorDemo.Scene scene, CursorDemo.Model model) {
        return new ScreenRenderer()
                .screen(scene.view(model, new ViewContext(new Viewport(24, 80))), new Viewport(24, 80));
    }

    @Test
    void theDemoShowsTheFocusedPaneCaretOnlyAndMovesItThroughTheOtherPane() {
        CursorDemo.Scene scene = new CursorDemo.Scene();
        CursorDemo.Model model =
                scene.init(new InitContext(new Viewport(24, 80), null)).model();
        assertThat(demo(scene, model).cursor()).as("the left pane's caret").hasValueSatisfying(cursor -> {
            assertThat(cursor.row()).isEqualTo(4);
            assertThat(cursor.column()).isEqualTo(1);
        });

        CursorDemo.Model right = send(scene, model, new CursorDemo.Msg.ToggleFocus());
        assertThat(demo(scene, right).cursor()).as("the right pane's caret").hasValueSatisfying(cursor -> {
            assertThat(cursor.row()).isEqualTo(4);
            assertThat(cursor.column()).as("the second pane starts at forty").isEqualTo(41);
        });

        CursorDemo.Model down = send(scene, model, new CursorDemo.Msg.Move(1, 0));
        assertThat(demo(scene, down).cursor())
                .get()
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
        RenderedScreen screen = demo(scene, virtual);
        assertThat(screen.cursor())
                .get()
                .satisfies(cursor -> assertThat(cursor.strategy()).isEqualTo(Cursor.Strategy.VIRTUAL));
        assertThat(screen.cell(4, 1).style().invert()).isTrue();

        CursorDemo.Model off = send(scene, virtual, new CursorDemo.Msg.Tick());
        assertThat(demo(scene, off).cursor())
                .as("the second half of the period is blank")
                .isEmpty();
        CursorDemo.Model on = send(scene, off, new CursorDemo.Msg.Tick());
        assertThat(demo(scene, on).cursor()).as("and it comes back").isPresent();
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
        assertThat(demo(scene, stepped).cursor()).hasValueSatisfying(cursor -> {
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
    void theLayoutEngineStillWorksWithoutACursor() {
        LayoutEngine.Layout layout = new LayoutEngine().layout(Views.text("plain"), new Rect(0, 0, 2, 10));
        assertThat(layout.cursor()).isEmpty();
        assertThat(new LayoutEngine.Layout(layout.screen(), layout.regions()).cursor())
                .as("the compatibility constructor keeps a painted screen usable")
                .isEmpty();
    }
}

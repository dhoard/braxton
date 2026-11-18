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

package examples.parity;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.Cursor;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.Help;
import java.util.List;
import java.util.Locale;

/**
 * Phase 04 parity demo: two editors, every multiline editing mode, in one scene.
 *
 * <p>Each pane is its own mounted {@link Editor} textarea, so the two own independent drafts,
 * carets and selections, and each one's caret is scoped to its own mount path. Only the pane whose
 * model is focused paints a caret and receives the typing.</p>
 *
 * <ul>
 *   <li>{@code lines=} paints a line-number gutter, numbered once per logical line; the rows a line
 *       was wrapped into continue under a blank gutter.</li>
 *   <li>{@code height=fixed|dynamic} switches between the configured row count and a height that
 *       follows the wrapped content, between two and six rows and counting no further than forty
 *       content rows.</li>
 *   <li>{@code cursor=painted|native|virtual} is the caret presentation: the block painted into the
 *       text, the terminal's own cursor, or a marked cell.</li>
 *   <li>{@code placeholder=on} paints a placeholder while a draft is empty; it is never part of the
 *       draft, so it is never submitted or copied.</li>
 *   <li>{@code wrap=} is the declared width the rows are wrapped at. The scene derives it from the
 *       viewport on every frame, so a resize re-wraps both panes and moves their carets with
 *       them.</li>
 * </ul>
 *
 * <p>The scene's own gestures are all modified - {@code alt+} and {@code tab} - so an unmodified key
 * reaches the focused editor as text. Everything the scene reports is on the status rows, so a
 * terminal verifier can assert the scene without decoding styling.</p>
 */
public final class EditorDemo {

    private static final Style STATUS = Style.NONE.withBold();
    private static final Style FOCUSED_FRAME = Style.NONE.withBold();
    private static final Style TITLE = Style.NONE.withBold();

    /** The background the row the caret is on is painted with. */
    public static final Color CURSOR_LINE = new Color(0x2A, 0x2A, 0x3A);

    /** The two drafts the scene starts with, so wrapping and a gutter are visible at once. */
    public static final String LEFT_TEXT = "alpha beta gamma delta epsilon zeta eta theta iota";

    /** The second starter draft. */
    public static final String RIGHT_TEXT = "one two three four five six seven eight nine ten";

    /** The smallest height a dynamic height takes, in rows. */
    public static final int MIN_ROWS = 2;

    /** The largest height a dynamic height takes, in rows. */
    public static final int MAX_ROWS = 6;

    /** The largest number of content rows a dynamic height counts. */
    public static final int MAX_CONTENT_ROWS = 40;

    private EditorDemo() {}

    /**
     * The scene state.
     *
     * @param left the left editor's own model
     * @param right the right editor's own model
     * @param leftFocused whether the left pane holds focus
     * @param lineNumbers whether each logical line's number is painted
     * @param dynamicHeight whether the painted height follows the content
     * @param caret how the caret is drawn
     * @param placeholder whether an empty draft shows a placeholder
     * @param last what the scene last did, for the status rows
     */
    public record Model(
            Editor.Model left,
            Editor.Model right,
            boolean leftFocused,
            boolean lineNumbers,
            boolean dynamicHeight,
            Editor.Caret caret,
            boolean placeholder,
            String last) {

        /** The focused pane's model, which is the one that types. */
        public Editor.Model focused() {
            return leftFocused ? left : right;
        }

        Model withLeft(Editor.Model value) {
            return new Model(value, right, leftFocused, lineNumbers, dynamicHeight, caret, placeholder, last);
        }

        Model withRight(Editor.Model value) {
            return new Model(left, value, leftFocused, lineNumbers, dynamicHeight, caret, placeholder, last);
        }

        Model withLeftFocused(boolean value) {
            return new Model(
                    left.withFocus(value),
                    right.withFocus(!value),
                    value,
                    lineNumbers,
                    dynamicHeight,
                    caret,
                    placeholder,
                    last);
        }

        Model withLineNumbers(boolean value) {
            return new Model(left, right, leftFocused, value, dynamicHeight, caret, placeholder, last);
        }

        Model withDynamicHeight(boolean value) {
            return new Model(left, right, leftFocused, lineNumbers, value, caret, placeholder, last);
        }

        Model withCaret(Editor.Caret value) {
            return new Model(left, right, leftFocused, lineNumbers, dynamicHeight, value, placeholder, last);
        }

        Model withPlaceholder(boolean value) {
            return new Model(left, right, leftFocused, lineNumbers, dynamicHeight, caret, value, last);
        }

        Model withLast(String value) {
            return new Model(left, right, leftFocused, lineNumbers, dynamicHeight, caret, placeholder, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A message from the left editor.
         *
         * @param value the editor's message
         */
        record Left(Editor.Msg value) implements Msg {}

        /**
         * A message from the right editor.
         *
         * @param value the editor's message
         */
        record Right(Editor.Msg value) implements Msg {}

        /**
         * An output one of the editors routed upward.
         *
         * @param left whether the left editor produced it
         * @param value the semantic output
         */
        record Output(boolean left, Editor.Output value) implements Msg {}

        /** Moves focus to the other pane. */
        record Next() implements Msg {}

        /** Toggles the line-number gutter. */
        record ToggleLineNumbers() implements Msg {}

        /** Toggles a height that follows the content. */
        record ToggleHeight() implements Msg {}

        /** Cycles painted, native and virtual carets. */
        record CycleCursor() implements Msg {}

        /** Toggles the placeholder an empty draft shows. */
        record TogglePlaceholder() implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions.
     *
     * <p>Every gesture carries a modifier or is a focus gesture, so an unmodified key still reaches
     * the focused editor as text.</p>
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Focus",
                        KeyAction.<Msg>of("next", new Msg.Next(), "tab", "next pane", "tab"),
                        KeyAction.<Msg>of("previous", new Msg.Next(), "shift+tab", "next pane", "shift+tab")),
                KeyMap.group(
                        "Presentation",
                        KeyAction.<Msg>of(
                                "line-numbers", new Msg.ToggleLineNumbers(), "alt+l", "line numbers", "alt+l"),
                        KeyAction.<Msg>of("height", new Msg.ToggleHeight(), "alt+t", "dynamic height", "alt+t"),
                        KeyAction.<Msg>of("cursor", new Msg.CycleCursor(), "alt+m", "caret strategy", "alt+m"),
                        KeyAction.<Msg>of("placeholder", new Msg.TogglePlaceholder(), "alt+p", "placeholder", "alt+p")),
                KeyMap.group("General", KeyAction.<Msg>of("quit", new Msg.Quit(), "esc", "quit", "escape", "ctrl+c")));
    }

    /** Runs the demo against the owned terminal. */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * The editing policy of one frame.
     *
     * <p>A resize has to reach both panes, so the width is derived from the viewport rather than
     * fixed: each pane is half the terminal and its frame takes two columns. The declared width is
     * what vertical movement and a dynamic height are computed for, so keeping it equal to the space
     * the pane is actually given is what makes wrapping, the caret and the row count agree.</p>
     *
     * @param model the scene state
     * @param cols the columns of the terminal, or zero when only the editing policy is needed
     * @return the policy
     */
    public static Editor.Config config(Model model, int cols) {
        Editor.Config base = Editor.Config.defaults()
                .withVisibleLines(MAX_ROWS)
                .withCursor(cursorPolicy(model.caret()))
                .withPresentation(Editor.Presentation.plain()
                        .withPlaceholder(model.placeholder() ? "Type something" : "", Style.NONE)
                        .withLineNumbers(model.lineNumbers(), Style.NONE)
                        .withCursorLine(Style.bg(CURSOR_LINE))
                        .withDynamicHeight(model.dynamicHeight(), MIN_ROWS, MAX_ROWS)
                        .withMaxContentLines(MAX_CONTENT_ROWS));
        // The width is only known while the view is built. A reduction does not depend on it, so
        // the update path asks for the policy alone.
        return cols > 0 ? base.withWrapWidth(Math.max(1, cols / 2 - 2)) : base;
    }

    /**
     * The caret presentation the scene asks for.
     *
     * @param caret the presentation
     * @return the policy
     */
    public static Editor.CursorPolicy cursorPolicy(Editor.Caret caret) {
        return switch (caret) {
            case PAINTED -> Editor.CursorPolicy.painted();
            case NATIVE -> Editor.CursorPolicy.nativeCursor(Cursor.Shape.BLOCK, Cursor.Mode.BLINK);
            case VIRTUAL -> Editor.CursorPolicy.virtualCursor(Cursor.Shape.BAR, Cursor.Mode.STEADY);
        };
    }

    /** The demo scene: two mounted textareas, three status rows and the help lines. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            Model model = new Model(
                            Editor.Model.of(LEFT_TEXT),
                            Editor.Model.of(RIGHT_TEXT).withFocus(false),
                            true,
                            false,
                            false,
                            Editor.Caret.PAINTED,
                            false,
                            "none")
                    .withLeftFocused(true);
            var left = mount(true, model).initialize(context);
            var right = mount(false, model).initialize(context);
            // The fixture is the demo's own state: a mounted editor starts with an empty draft, and
            // the scene gives each pane the text it starts with.
            return Update.next(new Model(
                    left.model().withText(LEFT_TEXT).withFocus(true),
                    right.model().withText(RIGHT_TEXT).withFocus(false),
                    true,
                    model.lineNumbers(),
                    model.dynamicHeight(),
                    model.caret(),
                    model.placeholder(),
                    model.last()));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Left value -> mount(true, model).update(model, value.value());
                case Msg.Right value -> mount(false, model).update(model, value.value());
                case Msg.Output output -> Update.next(describe(model, output));
                case Msg.Next next -> Update.next(model.withLeftFocused(!model.leftFocused()));
                case Msg.ToggleLineNumbers toggle ->
                    Update.next(model.withLineNumbers(!model.lineNumbers()).withLast("line-numbers"));
                case Msg.ToggleHeight toggle ->
                    Update.next(model.withDynamicHeight(!model.dynamicHeight()).withLast("height"));
                case Msg.CycleCursor cycle ->
                    Update.next(model.withCaret(nextCaret(model.caret())).withLast("caret"));
                case Msg.TogglePlaceholder toggle ->
                    Update.next(model.withPlaceholder(!model.placeholder()).withLast("placeholder"));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int cols = context.viewport().cols();
            // The scene's gestures are matched before the focused editor's own map: the editor
            // binds modified keys too, and the scene's toggles are what the user aimed at.
            return Views.keyed(
                    keys.bindings(),
                    Views.column(
                            Views.text(presentation(model, cols), STATUS),
                            Views.text(paneStatus("left", model.left(), model, cols), STATUS),
                            Views.text(paneStatus("right", model.right(), model, cols), STATUS),
                            Views.text("last=" + model.last(), STATUS),
                            Views.gap(1),
                            Views.row(pane(true, model, context), pane(false, model, context)),
                            Views.gap(1),
                            Help.view(keys, false, cols)),
                    List.of());
        }

        /** One pane: the mounted editor inside a frame that marks which pane holds focus. */
        private View<Msg> pane(boolean left, Model model, ViewContext context) {
            boolean focused = left == model.leftFocused();
            return Views.grow(
                    1,
                    Views.border(
                            Border.SINGLE,
                            StyledText.of(left ? "left" : "right", focused ? TITLE : Style.NONE),
                            focused ? FOCUSED_FRAME : Style.NONE,
                            mount(left, model).view(model, context)));
        }

        /**
         * One editor, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the scene's presentation choices on every use, so a toggle
         * or a resize is a new policy for the same mounted slot. Nothing else about the mount changes:
         * its identity, its lens and its routes are the same values every time.</p>
         */
        private Mount<Model, Msg, Never, Editor.Model, Editor.Msg, Editor.Output> mount(boolean left, Model model) {
            Editor.Config config = config(model, 0);
            return Mount.of(
                    ComponentId.of(left ? "left" : "right"),
                    Editor.textarea(config),
                    ModelLens.of(
                            m -> left ? m.left() : m.right(),
                            (m, child) -> left ? m.withLeft(child) : m.withRight(child)),
                    left ? (Editor.Msg value) -> new Msg.Left(value) : value -> new Msg.Right(value),
                    OutputRoute.toMessage(output -> new Msg.Output(left, output)));
        }

        /** The presentation row: the policy the two panes are painted with. */
        private static String presentation(Model model, int cols) {
            return "focus=" + (model.leftFocused() ? "left" : "right")
                    + " lines=" + (model.lineNumbers() ? "on" : "off")
                    + " height=" + (model.dynamicHeight() ? "dynamic" : "fixed")
                    + " cursor=" + model.caret().name().toLowerCase(Locale.ROOT)
                    + " placeholder=" + (model.placeholder() ? "on" : "off")
                    + " wrap=" + Math.max(1, cols / 2 - 2);
        }

        /** One pane's row: its draft's length, the caret's offset and the rows it paints. */
        private static String paneStatus(String name, Editor.Model pane, Model model, int cols) {
            int width = Math.max(1, cols / 2 - 2);
            return name + " len=" + pane.text().length()
                    + " caret=" + pane.caret()
                    + " rows=" + Editor.visibleLineCount(pane, config(model, 0), width);
        }

        /** What the scene last saw from an editor, as one token so a verifier can assert it. */
        private static Model describe(Model model, Msg.Output output) {
            String what = switch (output.value()) {
                case Editor.Output.Submit submit -> "submit:" + submit.text().length();
                case Editor.Output.Copy copy -> "copy:" + copy.text().length();
                case Editor.Output.PasteRequest request -> "paste";
            };
            return model.withLast((output.left() ? "left-" : "right-") + what);
        }

        private static Editor.Caret nextCaret(Editor.Caret caret) {
            return switch (caret) {
                case PAINTED -> Editor.Caret.NATIVE;
                case NATIVE -> Editor.Caret.VIRTUAL;
                case VIRTUAL -> Editor.Caret.PAINTED;
            };
        }
    }
}

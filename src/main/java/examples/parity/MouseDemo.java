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
import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Insets;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.RichList;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Phase 17 parity demo: pointer input over a split scene, with a modal layer over it.
 *
 * <p>The scene paints two panes side by side - an editor on the left, a list on the right - and logs
 * every pointer report it sees before the element under the pointer does. Two status rows report the
 * scene and the last report, so a terminal verifier can assert what a click at a known cell did
 * without decoding styling.</p>
 *
 * <p>What the demo is for:</p>
 *
 * <ul>
 *   <li><b>Hit testing.</b> A click is delivered to the element painted under the cell, so the same
 *       cell means different things in the two panes. Nothing here maps cells itself: the widgets
 *       receive a report already translated into their own rectangle.</li>
 *   <li><b>Dragging.</b> A press captures the element it landed on, so motion that leaves the pane -
 *       over the other pane, or below both - keeps selecting in the editor that captured it. The
 *       status row counts reports rather than presses, which is what makes the continuation
 *       visible.</li>
 *   <li><b>Modal isolation.</b> {@code alt+m} opens a dialog over both panes. The dialog is a route
 *       of its own: a click inside it reaches its list, and a click outside it reaches neither pane,
 *       however clearly the pane is painted under that cell. A route of its own also means the
 *       dialog's tree carries the scene's gestures, which is what keeps {@code alt+m} able to close
 *       it.</li>
 *   <li><b>The wheel.</b> The wheel lands on what it is over: over the list it steps the selection,
 *       over the editor it does nothing, and no report is ever also delivered as a wheel key.</li>
 * </ul>
 *
 * <p>The application asks for {@link MouseMode#CELL_MOTION}, which is what drag selection needs: a
 * release and the motion between presses are only reported by a terminal that was asked for them.
 * Presses, releases and wheel gestures are reported in every mode.</p>
 */
public final class MouseDemo {

    /** The id of the left pane's mount. */
    public static final String LEFT_PANE = "left";

    /** The id of the right pane's mount. */
    public static final String RIGHT_PANE = "right";

    /** The id of the dialog's mount. */
    public static final String DIALOG = "dialog";

    /** The draft the left pane starts with. */
    public static final String DRAFT = "Click in this pane to place the caret, and drag to select.";

    /** The items the right pane lists. */
    public static final List<RichList.Item> ITEMS = List.of(
            RichList.Item.of("item-01", "Lantern", "Hangs above the stall"),
            RichList.Item.of("item-02", "Sourdough", "Named after a friend"),
            RichList.Item.of("item-03", "Wool socks", "Thicker than they look"),
            RichList.Item.of("item-04", "Mending kit", "Needle, thread and scissors"));

    /** The items the dialog lists, so a click inside the modal is visible as its own selection. */
    public static final List<RichList.Item> DIALOG_ITEMS =
            List.of(RichList.Item.of("dialog-01", "Discard"), RichList.Item.of("dialog-02", "Keep"));

    /** The two status rows above the panes. */
    public static final int STATUS_ROWS = 2;

    /** The two help rows below the panes. */
    public static final int HELP_ROWS = 2;

    /** The rows a mount is told the scene paints around it. */
    public static final int RESERVED_ROWS = STATUS_ROWS + HELP_ROWS;

    /** The panes' rows when the view is initialized without a terminal. */
    public static final int PANE_ROWS = 24 - RESERVED_ROWS;

    /** The rows the dialog's box occupies, edges included. */
    public static final int DIALOG_ROWS = 5;

    /** The columns the dialog's box occupies, edges included. */
    public static final int DIALOG_COLS = 40;

    /** The blank rows the dialog is inset by, which is where its first painted row starts. */
    public static final int DIALOG_TOP = 1;

    /** The blank columns the dialog is inset by, which is where its first painted column starts. */
    public static final int DIALOG_LEFT = 20;

    private static final Style STATUS = Style.NONE.withBold();

    private MouseDemo() {}

    /**
     * Where the keyboard is.
     *
     * <p>One fact, as in every other parity scene: the panes derive their focus from it, so exactly
     * one element can hold the keyboard.</p>
     */
    public enum Pane {

        /** The left pane holds the keyboard. */
        LEFT,

        /** The right pane holds the keyboard. */
        RIGHT
    }

    /**
     * The scene state.
     *
     * @param left the mounted editor's state
     * @param right the mounted list's state
     * @param dialog the mounted dialog list's state
     * @param pane which pane holds the keyboard
     * @param modal whether the dialog is open over the panes
     * @param reports how many pointer reports the scene has seen
     * @param pointer the last report, as one token
     * @param last what the scene last did
     * @param expandedHelp whether help lists every group
     */
    public record Model(
            Editor.Model left,
            RichList.Model right,
            RichList.Model dialog,
            Pane pane,
            boolean modal,
            int reports,
            String pointer,
            String last,
            boolean expandedHelp) {

        /** Substitutes initialized defaults and derives every focus flag. */
        public Model {
            pane = pane == null ? Pane.LEFT : pane;
            pointer = pointer == null ? "none" : pointer;
            last = last == null ? "none" : last;
            left = (left == null ? Editor.Model.of(DRAFT) : left).withFocus(!modal && pane == Pane.LEFT);
            right = (right == null ? RichList.Model.of(ITEMS, PANE_ROWS) : right)
                    .withFocus(!modal && pane == Pane.RIGHT);
            dialog = (dialog == null ? RichList.Model.of(DIALOG_ITEMS, DIALOG_ROWS - 2) : dialog).withFocus(modal);
            reports = Math.max(0, reports);
        }

        /**
         * Whether the dialog is open.
         *
         * @return whether the modal layer is painted
         */
        public boolean dialogOpen() {
            return modal;
        }

        /**
         * The draft the left pane holds.
         *
         * @return the draft
         */
        public String draft() {
            return left.text();
        }

        /**
         * The id of the right pane's selection.
         *
         * @return the selected id, or an empty string
         */
        public String selected() {
            return right.selectedId();
        }

        /**
         * The id of the dialog's selection.
         *
         * @return the selected id, or an empty string
         */
        public String dialogSelection() {
            return dialog.selectedId();
        }

        Model withLeft(Editor.Model value) {
            return new Model(value, right, dialog, pane, modal, reports, pointer, last, expandedHelp);
        }

        Model withRight(RichList.Model value) {
            return new Model(left, value, dialog, pane, modal, reports, pointer, last, expandedHelp);
        }

        Model withDialog(RichList.Model value) {
            return new Model(left, right, value, pane, modal, reports, pointer, last, expandedHelp);
        }

        Model withPane(Pane value) {
            return new Model(left, right, dialog, value, modal, reports, pointer, last, expandedHelp);
        }

        Model withModal(boolean value) {
            return new Model(left, right, dialog, pane, value, reports, pointer, last, expandedHelp);
        }

        Model withReport(String value) {
            return new Model(left, right, dialog, pane, modal, reports + 1, value, last, expandedHelp);
        }

        Model withLast(String value) {
            return new Model(left, right, dialog, pane, modal, reports, pointer, value, expandedHelp);
        }

        Model withExpandedHelp(boolean value) {
            return new Model(left, right, dialog, pane, modal, reports, pointer, last, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A message from the mounted editor.
         *
         * @param value the editor's message
         */
        record Left(Editor.Msg value) implements Msg {}

        /**
         * An output the left pane routed to the scene.
         *
         * @param value the editor's output
         */
        record LeftOutput(Editor.Output value) implements Msg {}

        /**
         * A message from the mounted list.
         *
         * @param value the list's message
         */
        record Right(RichList.Msg value) implements Msg {}

        /**
         * A message from the mounted dialog list.
         *
         * @param value the dialog's message
         */
        record Dialog(RichList.Msg value) implements Msg {}

        /**
         * An output the right pane routed to the scene.
         *
         * @param value the list's output
         */
        record RightOutput(RichList.Output value) implements Msg {}

        /**
         * An output the dialog routed to the scene.
         *
         * @param value the dialog's output
         */
        record DialogOutput(RichList.Output value) implements Msg {}

        /**
         * One pointer report the scene observed, as the token the status row paints.
         *
         * @param token the report
         */
        record Seen(String token) implements Msg {}

        /** Moves the keyboard between the panes. */
        record TogglePane() implements Msg {}

        /** Opens or closes the dialog. */
        record ToggleModal() implements Msg {}

        /** Toggles full help. */
        record ToggleHelp() implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The session options the demo runs with: pointer input with drag motion.
     *
     * @return the options
     */
    public static RuntimeOptions options() {
        return RuntimeOptions.defaults().withTerminal(TerminalOptions.DEFAULT.withMouseMode(MouseMode.CELL_MOTION));
    }

    /** Runs the demo against the owned terminal. */
    public static ExitResult run() {
        return Application.run(new Scene(), options());
    }

    /**
     * Runs the demo.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * The left pane's editing policy.
     *
     * <p>The wrap width is not declared, so the editor re-wraps to whatever the split gives it.</p>
     *
     * @return the policy
     */
    public static Editor.Config editorConfig() {
        return Editor.Config.defaults();
    }

    /**
     * The right pane's list definition.
     *
     * @param rows the rows the pane holds
     * @return the definition
     */
    public static RichList.Config listConfig(int rows) {
        return RichList.Config.defaults()
                .withItems(ITEMS)
                .withTitle("Items")
                .withRows(Math.max(0, rows))
                .withReservedRows(RESERVED_ROWS);
    }

    /**
     * The dialog's list definition.
     *
     * @return the definition
     */
    public static RichList.Config dialogConfig() {
        return RichList.Config.defaults()
                .withItems(DIALOG_ITEMS)
                .withTitle("Dialog")
                .withRows(DIALOG_ROWS - 2);
    }

    /**
     * The scene's own actions.
     *
     * <p>They are bubble bindings, so a focused pane consumes a gesture first, except for the two
     * that are the scene's alone.</p>
     *
     * @return the map
     */
    public static KeyMap<Msg> keys() {
        return KeyMap.of(
                KeyMap.group(
                        "Focus",
                        KeyAction.<Msg>of("pane", new Msg.TogglePane(), "tab", "switch pane", "tab"),
                        // Alt, because a plain letter is what a focused editor types: the dialog's
                        // gesture has to be one no field can consume.
                        KeyAction.<Msg>of("modal", new Msg.ToggleModal(), "alt+m", "dialog", "alt+m")),
                KeyMap.group(
                        "Scene",
                        KeyAction.<Msg>of("help", new Msg.ToggleHelp(), "?", "full help", "?"),
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "ctrl+c", "quit", "ctrl+c")));
    }

    /**
     * The first status row: the scene.
     *
     * @param model the scene state
     * @return the row
     */
    public static String status(Model model) {
        return "pane=" + model.pane().name().toLowerCase(Locale.ROOT)
                + " dialog=" + (model.dialogOpen() ? "open" : "closed")
                + " draft=" + model.draft().length()
                + " caret=" + model.left.caret()
                + " sel=" + (model.selected().isEmpty() ? "none" : model.selected())
                + " dlg=" + (model.dialogSelection().isEmpty() ? "none" : model.dialogSelection())
                + " last=" + model.last();
    }

    /**
     * The second status row: what the pointer did.
     *
     * @param model the scene state
     * @return the row
     */
    public static String pointerLine(Model model) {
        return "pointer=" + model.pointer() + " reports=" + model.reports();
    }

    /**
     * The help rows the scene paints.
     *
     * @param model the scene state
     * @param cols the columns a row may paint
     * @return the rows
     */
    public static List<StyledText> helpLines(Model model, int cols) {
        List<StyledText> rows =
                new ArrayList<>(Help.render(keys(), model.expandedHelp(), cols).lines());
        return List.copyOf(rows);
    }

    /**
     * The dialog's box: a frame around the mounted dialog list.
     *
     * <p>It is fixed-size and inset, so a verifier knows which cells belong to it and which cells
     * belong to the panes painted underneath: rows {@code DIALOG_TOP} to {@code DIALOG_TOP +
     * DIALOG_ROWS} of the body, and columns {@link #DIALOG_LEFT} to {@code DIALOG_LEFT +
     * DIALOG_COLS}.</p>
     *
     * @param content the dialog's own rows
     * @param <Msg> the scene message type
     * @return the boxed dialog
     */
    private static <Msg> View<Msg> dialogBox(View<Msg> content) {
        // A fill first, so the dialog is opaque: a modal that let the panes show through its own
        // blank cells would read as a hole rather than as a layer above them.
        View<Msg> box = Views.overlay(Views.fill(Style.NONE), Views.border(Border.SINGLE, content));
        // The column and row are what make the reservations real: a Fixed outside one is a
        // transparent wrapper, and the border would stretch to whatever the layer was given.
        return Views.padding(
                new Insets(DIALOG_TOP, 0, 0, DIALOG_LEFT),
                Views.column(Views.fixed(DIALOG_ROWS, Views.row(Views.fixed(DIALOG_COLS, box)))));
    }

    /** What the editor asked for, as one token. */
    private static String output(Editor.Output output) {
        return switch (output) {
            case Editor.Output.Submit submit -> "submit:" + submit.text().length();
            case Editor.Output.Copy copy -> "copy:" + copy.text().length();
            case Editor.Output.PasteRequest request -> "paste";
        };
    }

    /** What the list asked for, as one token. */
    private static String output(RichList.Output output) {
        return switch (output) {
            case RichList.Output.Chosen chosen -> "chose:" + chosen.item().id();
            case RichList.Output.Cancelled cancelled -> "cancelled";
        };
    }

    /** The scene: three mounted children, two status rows, the split or the dialog, and help. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final Editor left = Editor.textarea(editorConfig());
        private final RichList right = new RichList(listConfig(PANE_ROWS));
        private final RichList dialog = new RichList(dialogConfig());
        private final Mount<Model, Msg, Never, Editor.Model, Editor.Msg, Editor.Output> leftMount = Mount.of(
                ComponentId.of(LEFT_PANE),
                left,
                ModelLens.of(Model::left, Model::withLeft),
                Msg.Left::new,
                OutputRoute.toMessage(Msg.LeftOutput::new));
        private final Mount<Model, Msg, Never, RichList.Model, RichList.Msg, RichList.Output> rightMount = Mount.of(
                ComponentId.of(RIGHT_PANE),
                right,
                ModelLens.of(Model::right, Model::withRight),
                Msg.Right::new,
                OutputRoute.toMessage(Msg.RightOutput::new));
        private final Mount<Model, Msg, Never, RichList.Model, RichList.Msg, RichList.Output> dialogMount = Mount.of(
                ComponentId.of(DIALOG),
                dialog,
                ModelLens.of(Model::dialog, Model::withDialog),
                Msg.Dialog::new,
                OutputRoute.toMessage(Msg.DialogOutput::new));

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            // The scene paints the visible list's actions in its own help row, so each list's own
            // help row is off; the dialog has no room for a status row either.
            Model model = new Model(
                    leftMount.initialize(context).model().withText(DRAFT),
                    rightMount
                            .initialize(context)
                            .model()
                            .withChrome(
                                    RichList.Chrome.defaults().withHelp(false).withPagination(false)),
                    dialogMount
                            .initialize(context)
                            .model()
                            .withChrome(RichList.Chrome.defaults()
                                    .withHelp(false)
                                    .withPagination(false)
                                    .withStatus(false)),
                    Pane.LEFT,
                    false,
                    0,
                    "none",
                    "none",
                    false);
            return Update.next(model);
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                // The pane that acted takes the keyboard. That is the scene consuming a click's
                // focus intent: the widgets never move focus themselves, and the application is
                // what decides that a press in a pane means the keyboard is in that pane.
                case Msg.Left value -> leftMount.update(model.withPane(Pane.LEFT), value.value());
                case Msg.LeftOutput value -> Update.next(model.withLast("editor-" + output(value.value())));
                case Msg.Right value -> rightMount.update(model.withPane(Pane.RIGHT), value.value());
                case Msg.RightOutput value -> Update.next(model.withLast("list-" + output(value.value())));
                case Msg.Dialog value -> dialogMount.update(model, value.value());
                case Msg.DialogOutput value -> Update.next(model.withLast("dialog-" + output(value.value())));
                case Msg.Seen seen -> Update.next(model.withReport(seen.token()));
                case Msg.TogglePane toggle ->
                    Update.next(model.withPane(model.pane() == Pane.LEFT ? Pane.RIGHT : Pane.LEFT)
                            .withLast("focus"));
                case Msg.ToggleModal modal ->
                    Update.next(model.withModal(!model.dialogOpen()).withLast("dialog"));
                case Msg.ToggleHelp help ->
                    Update.next(model.withExpandedHelp(!model.expandedHelp()).withLast("help"));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.batch(
                    leftMount.subscriptions(model), rightMount.subscriptions(model), dialogMount.subscriptions(model));
        }

        @Override
        public ComponentTree children(Model model) {
            return ComponentTree.of(leftMount.tree(model), rightMount.tree(model), dialogMount.tree(model));
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int cols = Math.max(1, context.viewport().cols());
            List<View<Msg>> rows = new ArrayList<>(STATUS_ROWS + 1 + HELP_ROWS);
            rows.add(Views.fixed(1, Views.text(status(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(pointerLine(model), STATUS)));
            rows.add(Views.grow(panes(model, context)));
            for (StyledText line : helpLines(model, cols)) {
                rows.add(Views.fixed(1, Views.text(line)));
            }
            // The scene's own observer wraps everything, so a report is logged before the element
            // under the pointer is offered it. It never consumes one: the log is an observation, not
            // a handler. The scene's own gestures are bubble bindings, so a focused pane sees a key
            // first and a field still types the letters it owns.
            return Views.keyed(List.of(), Scene::observe, Views.column(rows), keys().bindings());
        }

        /** The split, or the split under the dialog when it is open. */
        private View<Msg> panes(Model model, ViewContext context) {
            View<Msg> split = Views.row(
                    Views.grow(leftMount.view(model, context)),
                    Views.fixed(1, Views.text(StyledText.plain("\u2502"))),
                    Views.grow(rightMount.view(model, context)));
            if (!model.dialogOpen()) {
                return split;
            }
            // A modal layer is a route of its own, so the layer carries the scene's own gestures:
            // without them the dialog could not be closed, and a session key would only work while
            // the dialog was shut.
            return Views.modal(split, Views.onKeys(keys().bindings(), dialogBox(dialogMount.view(model, context))));
        }

        /** Records one pointer report without consuming it. */
        private static Optional<Msg> observe(InputEvent event) {
            if (!(event instanceof InputEvent.PointerInput pointer)) {
                return Optional.empty();
            }
            PointerEvent report = pointer.pointer();
            return Optional.of(new Msg.Seen(describe(report)));
        }

        /** One report as the token the status row paints. */
        private static String describe(PointerEvent event) {
            return event.action().name().toLowerCase(Locale.ROOT) + ":"
                    + event.button().name().toLowerCase(Locale.ROOT) + "@" + event.row() + "," + event.column();
        }
    }
}

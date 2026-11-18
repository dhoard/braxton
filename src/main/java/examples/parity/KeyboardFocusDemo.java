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
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.KeyboardEnhancements;
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
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.Help;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Phase 02 parity demo: enhanced keyboard input and terminal focus.
 *
 * <p>The session asks the terminal for two things an application can only request: the enhanced
 * keyboard flags it needs, and focus reporting. Both are requests, so the demo shows what actually
 * arrived rather than what was asked for.</p>
 *
 * <ul>
 *   <li>{@code last=} names the event type and the key of the most recent key event, so
 *       {@code press:enter} and {@code press:shift+enter} are visibly different gestures, and
 *       {@code press:tab} and {@code press:ctrl+i} are too.</li>
 *   <li>{@code caps=} is the flag set the terminal reported after the request, and
 *       {@code event-types=} says whether it includes event types. Nothing is assumed: a silent
 *       terminal leaves {@code caps=none}.</li>
 *   <li>{@code term=} is the terminal window's own focus, reported separately from the field's
 *       {@code field=} focus. Losing the terminal window changes {@code term=} and nothing else.</li>
 *   <li>The editor is a normal {@link Editor}: text is typed into it, and the demo reports each
 *       gesture without changing the editing policy.</li>
 * </ul>
 *
 * <p>Everything the scene records is on the status block, so a terminal verifier can assert the
 * scene without reading styled cells.</p>
 */
public final class KeyboardFocusDemo {

    /** The demo's editing policy: a plain, unframed field with a visible caret. */
    private static final Editor.Config EDITOR = Editor.Config.defaults();

    private static final Style TITLE = Style.NONE.withBold();

    /** The most draft characters the status row echoes, so a long draft cannot wrap it. */
    private static final int ECHO = 40;

    /**
     * The enhanced keyboard flags this demo asks for: everything a key event can report.
     *
     * <p>Disambiguation is what tells Enter from Shift+Enter and Tab from Ctrl+I; event types are
     * what make a release visible at all; the remaining flags are the protocol's own dependencies,
     * since associated text is defined only together with reporting every key as an escape code.</p>
     */
    public static final KeyboardEnhancements REQUESTED = KeyboardEnhancements.NONE
            .withDisambiguate(true)
            .withReportEventTypes(true)
            .withReportAllKeys(true)
            .withReportAssociatedText(true);

    /** The session options the demo runs with. */
    public static final TerminalOptions TERMINAL =
            TerminalOptions.DEFAULT.withKeyboardEnhancements(REQUESTED).withFocusReporting(true);

    private KeyboardFocusDemo() {}

    /**
     * The scene state.
     *
     * @param editor the editor field, which owns its own focus
     * @param reported the flags the terminal reported, or null when it has not answered
     * @param terminalFocused whether the terminal window has focus
     * @param last the most recent key event, in {@code kind:key} form
     * @param out the most recent semantic output the field produced
     * @param expanded whether the grouped full help is shown
     */
    public record Model(
            Editor.Model editor,
            KeyboardEnhancements reported,
            boolean terminalFocused,
            String last,
            String out,
            boolean expanded) {

        Model withEditor(Editor.Model value) {
            return new Model(value, reported, terminalFocused, last, out, expanded);
        }

        Model withReported(KeyboardEnhancements value) {
            return new Model(editor, value, terminalFocused, last, out, expanded);
        }

        Model withTerminalFocused(boolean value) {
            return new Model(editor, reported, value, last, out, expanded);
        }

        Model withLast(String value) {
            return new Model(editor, reported, terminalFocused, value, out, expanded);
        }

        Model withOut(String value) {
            return new Model(editor, reported, terminalFocused, last, value, expanded);
        }

        Model withExpanded(boolean value) {
            return new Model(editor, reported, terminalFocused, last, out, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {
        /** One key event the scene observed, in routing order. */
        record Key(InputEvent.KeyInput event) implements Msg {}

        /** The terminal window gained or lost focus. */
        record TerminalFocus(boolean gained) implements Msg {}

        /** The terminal answered which enhanced-keyboard flags it has enabled. */
        record Enhancements(KeyboardEnhancements flags) implements Msg {}

        /** The editor's own message, forwarded through the mount. */
        record EditorMessage(Editor.Msg value) implements Msg {}

        /** The editor's semantic output. */
        record Output(Editor.Output value) implements Msg {}

        /** Switches between compact and grouped full help. */
        record ToggleHelp() implements Msg {}

        /** Moves the field's own focus, so the demo shows that model focus is not terminal focus. */
        record ToggleField() implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions.
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "View",
                        KeyAction.<Msg>of("toggle-help", new Msg.ToggleHelp(), "?", "toggle help", "?"),
                        KeyAction.<Msg>of("toggle-field", new Msg.ToggleField(), "f", "toggle field focus", "f")),
                KeyMap.group("General", KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "ctrl+c")));
    }

    /** Runs the demo against the owned terminal with the session options it demonstrates. */
    public static ExitResult run() {
        return Application.run(new Scene(), RuntimeOptions.defaults().withTerminal(TERMINAL));
    }

    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /** The demo scene: one editor field, a status block, and the help lines. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        private final Mount<Model, Msg, Never, Editor.Model, Editor.Msg, Editor.Output> field = Mount.of(
                ComponentId.of("field"),
                new Editor(EDITOR),
                ModelLens.of(Model::editor, Model::withEditor),
                Msg.EditorMessage::new,
                OutputRoute.toMessage(Msg.Output::new));

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            Model model = new Model(
                    field.initialize(context).model().withFocus(true),
                    null,
                    // The terminal reports a change, never a starting state, so the window starts
                    // focused because the user started this session in it.
                    true,
                    "none",
                    "none",
                    false);
            return Update.next(model);
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Key key -> forward(model, key.event());
                case Msg.TerminalFocus focus ->
                    Update.next(model.withTerminalFocused(focus.gained())
                            .withLast(focus.gained() ? "focus-gained" : "focus-lost"));
                case Msg.Enhancements reported -> Update.next(model.withReported(reported.flags()));
                // A child message arrives through the mount, which is the only place that knows how
                // to apply it to the field and route whatever the field emits.
                case Msg.EditorMessage editor -> field.update(model, editor.value());
                // The field's semantic output is its own fact: a submit must not overwrite the
                // key event that asked for it, because the demo exists to report the event.
                case Msg.Output output -> Update.next(model.withOut(describeOutput(output.value())));
                case Msg.ToggleHelp toggle -> Update.next(model.withExpanded(!model.expanded()));
                case Msg.ToggleField toggle ->
                    Update.next(model.withEditor(
                            model.editor().withFocus(!model.editor().focused())));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int width = context.viewport().cols();
            View<Msg> body = Views.column(
                    Views.text(capabilities(model), TITLE),
                    Views.text(state(model)),
                    Views.text("editor=" + echo(model.editor().text())
                            + " field="
                            + (model.editor().focused() ? "focused" : "blurred")
                            + " out="
                            + model.out()),
                    Views.gap(1),
                    field.view(model, context),
                    Views.gap(1),
                    Help.view(keys, model.expanded(), width));
            // The scene observes every key event and every terminal notification, then forwards what
            // an editor action applies. Interception is what makes the event type visible: a release
            // would otherwise be indistinguishable from the press it ends.
            return Views.keyed(keys.bindings(), Scene::observe, body, List.of());
        }

        @Override
        public ComponentTree children(Model model) {
            return ComponentTree.of(field.tree(model));
        }

        /** Records one event, and consumes every event the scene is interested in. */
        private static Optional<Msg> observe(InputEvent event) {
            return switch (event) {
                case InputEvent.KeyInput key -> Optional.of(new Msg.Key(key));
                case InputEvent.FocusInput focus -> Optional.of(new Msg.TerminalFocus(focus.gained()));
                case InputEvent.KeyboardEnhancementsInput report -> Optional.of(new Msg.Enhancements(report.flags()));
                case InputEvent.PasteInput paste -> Optional.empty();
                case InputEvent.ResizeInput size -> Optional.empty();
                // A reply answers a query rather than naming a gesture, so this scene neither
                // records nor consumes it.
                case InputEvent.TerminalReplyInput reply -> Optional.empty();
                // Pointer input is not part of this scene's keyboard story: it is neither recorded
                // nor consumed, so it never becomes a key event.
                case InputEvent.PointerInput pointer -> Optional.empty();
            };
        }

        /**
         * Records the gesture and applies whatever editor action it names.
         *
         * <p>The editor's own mapping decides what a gesture means, so the demo reports the event
         * type without becoming a second editing policy. A release never reaches that mapping: it
         * is recorded and nothing else, which is what keeps a key-up from typing or submitting.
         * An unfocused field takes no action either, because focus is model data the scene owns.</p>
         */
        private Update<Model, Msg, Never> forward(Model model, InputEvent.KeyInput event) {
            Model recorded = model.withLast(describe(event));
            if (!event.activates() || !recorded.editor().focused()) {
                return Update.next(recorded);
            }
            Optional<Editor.Msg> action = Editor.actionFor(event, EDITOR);
            if (action.isEmpty()) {
                return Update.next(recorded);
            }
            // Dispatched rather than applied here, so the field's own message path stays the one that
            // updates it and its outputs cross the mount boundary as they always do.
            return Update.next(recorded, Command.dispatch(new Msg.EditorMessage(action.get())));
        }

        private static String describe(InputEvent.KeyInput event) {
            return kind(event.type()) + ":" + event.key();
        }

        private static String kind(KeyEventType type) {
            return type.name().toLowerCase(Locale.ROOT);
        }

        private static String describeOutput(Editor.Output output) {
            return switch (output) {
                case Editor.Output.Submit submit -> "submit";
                case Editor.Output.Copy copy -> "copy";
                case Editor.Output.PasteRequest paste -> "paste";
            };
        }

        private static String capabilities(Model model) {
            KeyboardEnhancements reported = model.reported();
            return "caps=" + (reported == null ? "none" : String.valueOf(reported.flags()))
                    + " event-types=" + (reported != null && reported.reportEventTypes() ? "on" : "off")
                    + " focus-report=" + (TERMINAL.focusReporting() ? "on" : "off");
        }

        private static String state(Model model) {
            return "last=" + model.last() + " term=" + (model.terminalFocused() ? "focused" : "blurred");
        }

        /** A one-line, bounded echo of a draft, so a status row cannot wrap. */
        private static String echo(String text) {
            String flat = text.replace('\n', ' ');
            return flat.length() <= ECHO ? flat : flat.substring(0, ECHO) + Help.ELLIPSIS;
        }
    }
}

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
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.EditorConfig;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.TextInput;
import io.github.dhoard.braxton.widget.TextInputConfig;
import java.util.List;
import java.util.Optional;

/**
 * Phase 05 parity demo: a three-field form built from single-line text inputs.
 *
 * <p>Each field is its own mounted {@link TextInput}, so the three own independent drafts, carets
 * and selections and only the focused one receives typing. The scene owns what the fields
 * deliberately do not: which field has focus, the candidate list a completion is answered with, and
 * what a submission means.</p>
 *
 * <ul>
 *   <li>{@code nickname} is validated, completes from a static repository list and scrolls
 *       horizontally once the draft is wider than its sixteen columns.</li>
 *   <li>{@code email} is validated and is never offered a candidate.</li>
 *   <li>{@code password} is masked: its draft is painted one bullet per cluster, is never copied,
 *       and is never completed.</li>
 * </ul>
 *
 * <p>The scene's own gestures are {@code tab}, {@code shift+tab}, {@code escape} and {@code ctrl+c},
 * all registered as <em>bubble</em> bindings: the focused field sees a key first, so {@code tab}
 * completes a field with a candidate and falls through to the scene's focus traversal when it has
 * none. Everything the scene reports is on status rows, so a terminal verifier can assert the scene
 * without decoding styling.</p>
 */
public final class TextInputDemo {

    private static final Style STATUS = Style.NONE.withBold();

    private static final Style PROMPT = Style.NONE.withBold();

    private static final Style SUGGESTION = Style.NONE.withItalic();

    /**
     * The static candidate list the nickname field completes from; no network is involved.
     */
    public static final List<String> REPOS = List.of(
            "braxton", "branch", "bubbles", "bubbletea", "glamour", "gum", "harmonica", "huh", "lipgloss", "wish");

    /**
     * The columns the nickname field paints in.
     */
    public static final int NICKNAME_WIDTH = 16;

    /**
     * The columns the email field paints in.
     */
    public static final int EMAIL_WIDTH = 24;

    /**
     * The columns the password field paints in.
     */
    public static final int PASSWORD_WIDTH = 12;

    /**
     * The field names, in focus order.
     */
    public static final List<String> FIELDS = List.of("nickname", "email", "password");

    private TextInputDemo() {}

    /**
     * The scene state.
     *
     * @param nickname the nickname field's own model
     * @param email the email field's own model
     * @param password the password field's own model
     * @param focus the index of the field that owns focus
     * @param last what the scene last saw from a field
     */
    public record Model(
            TextInput.Model nickname, TextInput.Model email, TextInput.Model password, int focus, String last) {

        /**
         * One field's model.
         *
         * @param index the field index
         * @return that field's model
         */
        public TextInput.Model field(int index) {
            return switch (index) {
                case 0 -> nickname;
                case 1 -> email;
                case 2 -> password;
                default -> throw new IllegalArgumentException("no field " + index);
            };
        }

        Model withField(int index, TextInput.Model value) {
            return switch (index) {
                case 0 -> new Model(value, email, password, focus, last);
                case 1 -> new Model(nickname, value, password, focus, last);
                case 2 -> new Model(nickname, email, value, focus, last);
                default -> throw new IllegalArgumentException("no field " + index);
            };
        }

        Model withFocus(int value) {
            return new Model(
                    nickname.withFocus(value == 0),
                    email.withFocus(value == 1),
                    password.withFocus(value == 2),
                    value,
                    last);
        }

        Model withLast(String value) {
            return new Model(nickname, email, password, focus, value);
        }

        /**
         * Whether every field passes its own validator.
         *
         * @return whether the form could be submitted
         */
        public boolean complete() {
            return nickname.valid() && email.valid() && password.valid();
        }
    }

    /**
     * Every message the scene handles.
     */
    public sealed interface Msg {

        /**
         * A message from one field.
         *
         * @param index the field index
         * @param value the field's message
         */
        record Field(int index, TextInput.Msg value) implements Msg {}

        /**
         * An output one field routed upward.
         *
         * @param index the field index
         * @param value the semantic output
         */
        record Output(int index, TextInput.Output value) implements Msg {}

        /**
         * Moves focus to the next field, wrapping.
         */
        record NextField() implements Msg {}

        /**
         * Moves focus to the previous field, wrapping.
         */
        record PreviousField() implements Msg {}

        /**
         * Ends the session.
         */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions.
     *
     * <p>They are bubble bindings, so a focused field sees every key first: {@code tab} completes a
     * field that has a candidate and only then reaches this map, which is why one key can both
     * accept a completion and move on.</p>
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Focus",
                        KeyAction.<Msg>of("next-field", new Msg.NextField(), "tab", "next field", "tab"),
                        KeyAction.<Msg>of(
                                "previous-field", new Msg.PreviousField(), "shift+tab", "previous field", "shift+tab")),
                KeyMap.group("General", KeyAction.<Msg>of("quit", new Msg.Quit(), "esc", "quit", "escape", "ctrl+c")));
    }

    /**
     * Runs the demo against the owned terminal.
     *
     * @return the structured session exit outcome
     */
    public static ExitResult run() {
        return Application.run(new Scene());
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
     * The policy of one field.
     *
     * <p>Every field is a plain {@link TextInput} configured from here, so the three differ only in
     * the parts a form really varies: the width, the placeholder, the validator, the mask and
     * whether completion is offered.</p>
     *
     * @param index the field index
     * @return that field's policy
     */
    public static TextInputConfig config(int index) {
        return switch (index) {
            case 0 ->
                TextInputConfig.defaults()
                        .withWidth(NICKNAME_WIDTH)
                        .withMaxLength(32)
                        .withValidator(TextInputDemo::validateNickname)
                        .withCompletion(TextInput.Completion.prefix())
                        .withPlaceholder("Nickname", Style.NONE)
                        .withPrompt("", Style.NONE)
                        .withStyles(Style.NONE, Style.NONE.withInvert(), SUGGESTION);
            case 1 ->
                TextInputConfig.defaults()
                        .withWidth(EMAIL_WIDTH)
                        .withMaxLength(64)
                        .withValidator(TextInputDemo::validateEmail)
                        .withPlaceholder("Email", Style.NONE);
            case 2 ->
                TextInputConfig.defaults()
                        .withWidth(PASSWORD_WIDTH)
                        .withMaxLength(16)
                        .withMasking(EditorConfig.Masking.PASSWORD)
                        .withValidator(TextInputDemo::validatePassword)
                        .withPlaceholder("Password", Style.NONE);
            default -> throw new IllegalArgumentException("no field " + index);
        };
    }

    /**
     * A nickname needs at least three characters.
     *
     * <p>The reasons are single tokens, so a status row stays one terminal row and a verifier can
     * assert it without decoding a line that wrapped.</p>
     */
    private static TextInput.Validation validateNickname(String text) {
        if (text.isBlank()) {
            return TextInput.Validation.invalid("empty");
        }
        return text.length() < 3 ? TextInput.Validation.invalid("too-short") : TextInput.Validation.ok();
    }

    /**
     * An email needs exactly one {@code @} and a domain behind it.
     */
    private static TextInput.Validation validateEmail(String text) {
        if (text.isBlank()) {
            return TextInput.Validation.invalid("empty");
        }
        int at = text.indexOf('@');
        if (at < 0 || at != text.lastIndexOf('@')) {
            return TextInput.Validation.invalid("needs-an-at");
        }
        return text.indexOf('.', at) < 0 ? TextInput.Validation.invalid("needs-a-domain") : TextInput.Validation.ok();
    }

    /**
     * A password needs eight characters.
     */
    private static TextInput.Validation validatePassword(String text) {
        return text.length() < 8 ? TextInput.Validation.invalid("too-short") : TextInput.Validation.ok();
    }

    /**
     * The form scene: three mounted fields, five status rows and the help line.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            Model model = new Model(fieldModel(0), fieldModel(1), fieldModel(2), 0, "none").withFocus(0);
            return Update.next(model);
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Field field -> mount(field.index(), model).update(model, field.value());
                case Msg.Output output -> answer(model, output);
                case Msg.NextField next -> Update.next(model.withFocus((model.focus() + 1) % FIELDS.size()));
                case Msg.PreviousField previous ->
                    Update.next(model.withFocus((model.focus() + FIELDS.size() - 1) % FIELDS.size()));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public Element<Msg> view(Model model, ViewContext context) {
            return scene(model, context);
        }

        /**
         * The gestures this scene answers once no widget has taken them.
         */
        @Override
        public List<Binding<Msg>> bindings(Model model) {
            return keys.bindings();
        }

        /**
         * The scene as one element tree.
         */
        private Element<Msg> scene(Model model, ViewContext context) {

            int cols = context.viewport().cols();
            // The scene's gestures are bubble bindings: a focused field sees a key first, so tab
            // completes a field with a candidate and only then reaches the focus traversal.
            return Elements.column(
                    Elements.text(headline(model), STATUS),
                    Elements.text(status(0, model), STATUS),
                    Elements.text(status(1, model), STATUS),
                    Elements.text(status(2, model), STATUS),
                    Elements.text("last=" + model.last(), STATUS),
                    Elements.spacer(1),
                    row(0, model, context),
                    row(1, model, context),
                    row(2, model, context),
                    Elements.spacer(1),
                    Help.element(keys, Help.Config.defaults(), false, cols));
        }

        /**
         * One labeled field: the label the form owns, then the mounted field.
         */
        private Element<Msg> row(int index, Model model, ViewContext context) {
            String label = switch (index) {
                case 0 -> "Nickname: ";
                case 1 -> "Email:    ";
                default -> "Password: ";
            };
            return Elements.row(
                    Elements.text(label, PROMPT),
                    Elements.grow(1, mount(index, model).view(model, context)));
        }

        /**
         * One field, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the field's policy on every use. Nothing else about the
         * mount changes: its identity, its lens and its routes are the same values every time.</p>
         */
        private Mount<Model, Msg, Never, TextInput.Model, TextInput.Msg, TextInput.Output> mount(
                int index, Model model) {
            return Mount.of(
                    ComponentId.of(FIELDS.get(index)),
                    new TextInput(config(index)),
                    ModelLens.of(
                            (Model m) -> m.field(index), (Model m, TextInput.Model child) -> m.withField(index, child)),
                    (TextInput.Msg value) -> new Msg.Field(index, value),
                    OutputRoute.toMessage(output -> new Msg.Output(index, output)));
        }

        /**
         * What the scene does with what a field reported.
         *
         * <p>A request for candidates is answered immediately from the static list - that is the
         * whole completion protocol - and every other output is recorded on the last row. An answer
         * names the revision it was asked about, so an answer that arrives late cannot replace the
         * candidates of a draft the user has already changed.</p>
         */
        private Update<Model, Msg, Never> answer(Model model, Msg.Output output) {
            Optional<String> said = describe(output.index(), output.value());
            Model next = said.map(model::withLast).orElse(model);
            if (output.value() instanceof TextInput.Output.SuggestionsRequested request && output.index() == 0) {
                // The mounted field applies the answer to its own draft; the scene only supplies it.
                return mount(0, next).update(next, new TextInput.Msg.Candidates(request.revision(), REPOS));
            }
            return Update.next(next);
        }

        /**
         * One field's starting model: empty, validated by its own policy, and unfocused.
         */
        private static TextInput.Model fieldModel(int index) {
            TextInput field = new TextInput(config(index));
            return field.validated(TextInput.Model.empty());
        }

        /**
         * The first status row: the form's own state.
         */
        private static String headline(Model model) {
            return "fields=" + FIELDS.size()
                    + " focus=" + FIELDS.get(model.focus())
                    + " completion=" + (config(0).completion().enabled() ? "on" : "off")
                    + " form=" + (model.complete() ? "ready" : "incomplete");
        }

        /**
         * One field's status row: what its own model holds, so a verifier can assert the field
         * without reading styled cells.
         */
        private static String status(int index, Model model) {
            TextInput.Model field = model.field(index);
            TextInputConfig opts = config(index);
            StringBuilder row = new StringBuilder(FIELDS.get(index));
            row.append(" len=").append(field.text().length());
            row.append(" caret=").append(field.caret());
            row.append(" scroll=")
                    .append(TextInput.scrollOffset(
                            field, opts, opts.appearance().width()));
            if (opts.completion().enabled()) {
                row.append(" cand=")
                        .append(TextInput.selectedCandidate(field, opts).orElse("none"));
            }
            if (opts.masking() == EditorConfig.Masking.PASSWORD) {
                row.append(" masked=yes");
            }
            row.append(" valid=").append(field.valid() ? "yes" : "no");
            row.append(" msg=")
                    .append(field.valid() ? "none" : field.validation().message());
            return row.toString();
        }

        /**
         * What the scene last saw from a field, as one token so a verifier can assert it.
         */
        private static Optional<String> describe(int index, TextInput.Output output) {
            String name = FIELDS.get(index);
            return switch (output) {
                case TextInput.Output.Submit submit ->
                    Optional.of("submit:" + name + ":" + submit.text().length());
                case TextInput.Output.Rejected rejected ->
                    Optional.of("reject:" + name + ":"
                            + rejected.validation().message().replace(' ', '-'));
                case TextInput.Output.ValidationChanged changed ->
                    Optional.of(
                            "validation:" + name + ":" + (changed.validation().valid() ? "valid" : "invalid"));
                case TextInput.Output.Copy copy ->
                    Optional.of("copy:" + name + ":" + copy.text().length());
                case TextInput.Output.PasteRequest request -> Optional.of("paste:" + name);
                // A request for candidates is answered on every keystroke, so recording it would
                // bury every other output under it.
                case TextInput.Output.SuggestionsRequested requested -> Optional.empty();
            };
        }
    }
}

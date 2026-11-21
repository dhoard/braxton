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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A form: an ordered set of labeled fields, one validation policy, and one answer when the user
 * submits it.
 *
 * <p>A form is the part of a screen that owns what its fields cannot: which field the user is on,
 * what is wrong with each field, and whether the whole thing may be submitted. The fields
 * themselves stay with the application. This kernel has exactly one focused leaf, so a form that
 * composes independently focusable widgets cannot also own the keyboard while a field is editing;
 * instead the application keeps each field's widget instance and model, renders each field's
 * element, and hands the form the values when it validates or submits. That division is what makes
 * a text input, a select and a checkbox usable in the same form without the form knowing what any
 * of them is.</p>
 *
 * <h2>The model</h2>
 *
 * <p>{@link Model} carries the field definitions in display order, the id of the focused field,
 * and the typed {@link Error errors} validation produced. The canonical constructor keeps the model
 * self-consistent: field ids are unique, the focused id names a field (or is empty when there are
 * none), and an error names a field and appears once, in field order. An application may build any
 * model it likes and still paint a form; the reductions enforce the same rules.</p>
 *
 * <h2>Messages</h2>
 *
 * <p>{@link Msg.Next} and {@link Msg.Previous} move focus and wrap at either end, so {@code tab} at
 * the last field leads back to the first, the rule {@code TextInputDemo}'s scene and every dialog
 * already follow. {@link Msg.Focus} moves focus to a named field, which is what a press on a field's
 * label reports. {@link Msg.Validate} records errors without moving focus, because validating after
 * every keystroke must not take the user somewhere else. {@link Msg.Submit} validates, moves focus to
 * the first invalid field when there is one, and reports exactly one output: {@link
 * Output.Submitted} when every field passes, or {@link Output.Rejected} with the errors when at
 * least one does not.</p>
 *
 * <p>Focus is form data, not application data: the messages above move it and the application reads
 * {@link Model#focused()} afterward to set the matching field's model. A message that could not
 * change anything - an unknown field id, an empty form, a validator that again reports the same
 * errors - returns the incoming model instance, the rule every bundled widget follows.</p>
 *
 * <h2>Keys and pointer</h2>
 *
 * <p>{@link #defaultKeys()} defines {@code tab} and {@code shift+tab} for traversing the fields, and
 * {@link #keys()} is what the form routes, so {@link Help} describes what actually fires. An
 * application installs them as its own {@link io.github.dhoard.braxton.input.Binding bindings}: the
 * focused field sees a key first, so {@code tab} completes a field that has a completion and only
 * then moves on. A submit key is deliberately not in the map: a submit message carries the field
 * values, and only the application holds them.</p>
 *
 * <p>A left press on a field's label row focuses that field. The press is the label row's, not the
 * form's, so a press inside the field itself reaches the field's own widget and a click that places
 * a caret is never taken away by the form.</p>
 *
 * <h2>Themes and narrow terminals</h2>
 *
 * <p>A field's label is the theme's {@code TEXT}, preceded by a focus marker in the theme's {@code
 * ACCENT} when the field is the focused one. An error row is painted in the theme's {@code ERROR}
 * with a {@code !} marker, so an invalid field is legible without reading the message. Labels and
 * error messages are clipped rather than wrapped, because a form row is one line, and a form given
 * no cells paints nothing.</p>
 *
 * <h2>Instances</h2>
 *
 * <p>A form is one object per placed form, held across frames the way a component is: a label row
 * keeps a pointer gesture with the widget it started on, so a form rebuilt every frame would lose
 * that gesture between frames.</p>
 *
 * @see Elements#form(Form, Form.Model, java.util.Map, java.util.function.Function)
 */
public final class Form implements Component<Form.Model, Form.Msg, Form.Output> {

    /**
     * The marker painted before the label of the focused field.
     */
    public static final String FOCUS_MARKER = "\u276F";

    /**
     * The marker a field without focus paints, so every label starts in the same column.
     */
    public static final String FOCUS_GAP = " ";

    private final Config config;

    /**
     * The label rows as press targets, so a press on a label focuses its field.
     */
    private final FormLabel label = new FormLabel();

    /**
     * The error rows' painter: a marker and the reason in the theme's error style.
     */
    private final FormErrorRow<Msg> errorRow = new FormErrorRow<>();

    /**
     * A form over its fields, accepting every set of values.
     *
     * @param fields the fields, top to bottom
     */
    public Form(List<Field> fields) {
        this(Config.of(fields));
    }

    /**
     * A form over its configuration.
     *
     * @param config the fields and the validation policy; required
     */
    public Form(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * One field of a form.
     *
     * <p>A field is its identity and its label, and nothing else: the editor it shows is the
     * application's element, and the value it holds is the application's model. The identity is what
     * an error and a focus report name, so it is stable across a reordering of the form.</p>
     *
     * @param id the identity, unique across the fields of one form; never blank
     * @param label the label painted above the editor; null is empty
     */
    public record Field(String id, StyledText label) {

        /**
         * Rejects a field without an id and substitutes an empty label.
         */
        public Field {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("field id is required");
            }
            label = label == null ? StyledText.empty() : label;
        }

        /**
         * A field with a plain label.
         *
         * @param id the identity; never blank
         * @param label the plain label; null is empty
         * @return the field
         */
        public static Field of(String id, String label) {
            return new Field(id, StyledText.plain(label));
        }

        /**
         * This field with another label.
         *
         * @param value the label; null is empty
         * @return the field
         */
        public Field withLabel(StyledText value) {
            return new Field(id, value);
        }

        /**
         * A field list with unique identities, as an immutable list.
         *
         * @param fields the fields; null is empty
         * @return the copied fields
         */
        static List<Field> copyAll(List<Field> fields) {
            if (fields == null) {
                return List.of();
            }
            List<Field> copy = new ArrayList<>(fields.size());
            Set<String> ids = new HashSet<>(fields.size());
            for (Field field : fields) {
                Field present = Objects.requireNonNull(field, "field");
                if (!ids.add(present.id())) {
                    throw new IllegalArgumentException("duplicate field id: " + present.id());
                }
                copy.add(present);
            }
            return List.copyOf(copy);
        }
    }

    /**
     * One validation failure, on one field.
     *
     * <p>The field id is what lets a form move focus to the first invalid field, and the message is
     * what the error row paints. An error that names no field of the form is dropped by {@link
     * Model}, so a validator that reports a field the form no longer shows cannot leave an error
     * nobody can see.</p>
     *
     * @param field the id of the field the error is about; never blank
     * @param message the reason, for the user to read; blank becomes {@code "invalid"}
     */
    public record Error(String field, String message) {

        /**
         * Rejects an error that names no field and substitutes a reason for a blank one.
         */
        public Error {
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("field id is required");
            }
            message = message == null || message.isBlank() ? "invalid" : message;
        }
    }

    /**
     * The values of the fields of one submission, by field id.
     *
     * <p>Values are what the application hands the form when it validates: the form does not hold a
     * second copy of what the field models already carry, so a value can never disagree with the
     * field it came from. A field the map does not name reads as empty, which is what a validator
     * checking a required field needs.</p>
     *
     * @param texts the text of each field, by id
     */
    public record Values(Map<String, String> texts) {

        /**
         * Copies the texts and substitutes an empty string for a missing value.
         */
        public Values {
            Objects.requireNonNull(texts, "texts");
            Map<String, String> copy = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : texts.entrySet()) {
                String id = Objects.requireNonNull(entry.getKey(), "field id");
                if (id.isBlank()) {
                    throw new IllegalArgumentException("field id is required");
                }
                copy.put(id, entry.getValue() == null ? "" : entry.getValue());
            }
            texts = Map.copyOf(copy);
        }

        /**
         * No values at all.
         *
         * @return the empty values
         */
        public static Values empty() {
            return new Values(Map.of());
        }

        /**
         * The values of a map.
         *
         * @param texts the text of each field, by id; required
         * @return the values
         */
        public static Values of(Map<String, String> texts) {
            return new Values(texts);
        }

        /**
         * The text of one field, or empty when the values do not name it.
         *
         * @param id the field id; null reads as empty
         * @return the text
         */
        public String text(String id) {
            if (id == null) {
                return "";
            }
            String value = texts.get(id);
            return value == null ? "" : value;
        }

        /**
         * These values with one field's text replaced.
         *
         * @param id the field id; never blank
         * @param text the text; null is empty
         * @return the new values
         */
        public Values with(String id, String text) {
            Map<String, String> copy = new LinkedHashMap<>(texts);
            copy.put(Objects.requireNonNull(id, "field id"), text == null ? "" : text);
            return new Values(copy);
        }
    }

    /**
     * A pure validation policy for a set of field values.
     *
     * <p>The validator runs on submission and whenever the application asks the form to validate,
     * and it must not perform I/O. An error it reports for a field the form does not show is dropped,
     * and the first error in field order is the field {@link Msg.Submit} moves focus to. An exception
     * it throws is not swallowed: a validator that fails is a configuration failure, and the runtime
     * reports it as one.</p>
     */
    @FunctionalInterface
    public interface Validator {

        /**
         * Validates one set of values.
         *
         * @param values the values to validate; never null
         * @return the errors, in any order; never null
         */
        List<Error> validate(Values values);

        /**
         * A policy that accepts every set of values.
         *
         * @return the policy
         */
        static Validator none() {
            return values -> List.of();
        }
    }

    /**
     * How a form is configured.
     *
     * @param fields the fields, top to bottom
     * @param validator the validation policy; {@link Validator#none()} when null
     */
    public record Config(List<Field> fields, Validator validator) {

        /**
         * Copies the fields and substitutes an accepting policy for a missing validator.
         */
        public Config {
            fields = Field.copyAll(fields);
            validator = validator == null ? Validator.none() : validator;
        }

        /**
         * A configuration over its fields, accepting every set of values.
         *
         * @param fields the fields
         * @return the configuration
         */
        public static Config of(List<Field> fields) {
            return new Config(fields, Validator.none());
        }

        /**
         * This configuration over other fields.
         *
         * @param value the fields; null is empty
         * @return the new configuration
         */
        public Config withFields(List<Field> value) {
            return new Config(value, validator);
        }

        /**
         * This configuration with another validation policy.
         *
         * @param value the policy; null accepts every set of values
         * @return the new configuration
         */
        public Config withValidator(Validator value) {
            return new Config(fields, value);
        }
    }

    /**
     * Immutable form state.
     *
     * <p>The canonical constructor resolves the focused id against the fields, drops errors that name
     * no field, keeps one error per field, and orders the errors by field so the first one is the
     * first invalid field the reader would see.</p>
     *
     * @param fields the fields, top to bottom
     * @param focused the id of the focused field, or empty when there are no fields
     * @param errors the validation errors, one per invalid field, in field order
     */
    public record Model(List<Field> fields, String focused, List<Error> errors) {

        /**
         * Normalizes the fields, the focused id and the errors.
         */
        public Model {
            fields = Field.copyAll(fields);
            focused = resolve(fields, focused);
            errors = admittedErrors(fields, errors);
        }

        /**
         * A form focused on its first field, with no errors.
         *
         * @param fields the fields
         * @return the model
         */
        public static Model of(List<Field> fields) {
            return new Model(fields, "", List.of());
        }

        /**
         * This form over other fields, with the focused id resolved again.
         *
         * @param value the fields; null is empty
         * @return the new model
         */
        public Model withFields(List<Field> value) {
            return new Model(value, focused, errors);
        }

        /**
         * This form with another field focused.
         *
         * @param value the id to focus; null or an unknown id focuses the first field
         * @return the new model
         */
        public Model withFocused(String value) {
            return new Model(fields, value, errors);
        }

        /**
         * This form with another set of errors.
         *
         * @param value the errors; null is none, and an error naming no field is dropped
         * @return the new model
         */
        public Model withErrors(List<Error> value) {
            return new Model(fields, focused, value);
        }

        /**
         * Whether a field is the focused one.
         *
         * @param id the field id; null is false
         * @return whether the field has focus
         */
        public boolean isFocused(String id) {
            return focused.equals(id);
        }

        /**
         * The error of one field, if it has one.
         *
         * @param id the field id; null finds nothing
         * @return the error, or empty
         */
        public Optional<Error> error(String id) {
            for (Error error : errors) {
                if (error.field().equals(id)) {
                    return Optional.of(error);
                }
            }
            return Optional.empty();
        }

        /**
         * Whether one field is invalid.
         *
         * @param id the field id; null is valid
         * @return whether the field has an error
         */
        public boolean invalid(String id) {
            return error(id).isPresent();
        }

        /**
         * Whether every field passes the last validation.
         *
         * @return whether there are no errors
         */
        public boolean valid() {
            return errors.isEmpty();
        }

        /**
         * The id a field list admits: the named one when it exists, the first one otherwise.
         */
        private static String resolve(List<Field> fields, String id) {
            if (id != null && !id.isBlank()) {
                for (Field field : fields) {
                    if (field.id().equals(id)) {
                        return id;
                    }
                }
            }
            return fields.isEmpty() ? "" : fields.get(0).id();
        }

        /**
         * The errors a field list admits: known fields only, one each, in field order.
         */
        private static List<Error> admittedErrors(List<Field> fields, List<Error> errors) {
            if (errors == null || errors.isEmpty()) {
                return List.of();
            }
            Map<String, Error> byField = new LinkedHashMap<>();
            for (Error error : errors) {
                Error present = Objects.requireNonNull(error, "error");
                // The first report for a field wins, so a validator may check a rule twice and still
                // leave the reader one reason per row.
                if (known(fields, present.field())) {
                    byField.putIfAbsent(present.field(), present);
                }
            }
            if (byField.isEmpty()) {
                return List.of();
            }
            List<Error> ordered = new ArrayList<>(byField.size());
            for (Field field : fields) {
                Error error = byField.get(field.id());
                if (error != null) {
                    ordered.add(error);
                }
            }
            return List.copyOf(ordered);
        }

        /**
         * Whether a field list holds an id.
         */
        private static boolean known(List<Field> fields, String id) {
            for (Field field : fields) {
                if (field.id().equals(id)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * The form's messages.
     */
    public sealed interface Msg {

        /**
         * Focuses the next field, wrapping to the first at the end.
         */
        record Next() implements Msg {}

        /**
         * Focuses the previous field, wrapping to the last at the beginning.
         */
        record Previous() implements Msg {}

        /**
         * Focuses one field, which is what a press on its label reports.
         *
         * @param id the field to focus; an unknown id changes nothing
         */
        record Focus(String id) implements Msg {

            /**
             * Substitutes an empty id for a missing one, which focuses nothing.
             */
            public Focus {
                id = id == null ? "" : id;
            }
        }

        /**
         * Validates without submitting: the errors are recorded and focus stays where it is.
         *
         * @param values the field values to validate; required
         */
        record Validate(Values values) implements Msg {

            /**
             * Rejects missing values.
             */
            public Validate {
                values = Objects.requireNonNull(values, "values");
            }
        }

        /**
         * Validates and submits: focus moves to the first invalid field, and one output reports the
         * result.
         *
         * @param values the field values to submit; required
         */
        record Submit(Values values) implements Msg {

            /**
             * Rejects missing values.
             */
            public Submit {
                values = Objects.requireNonNull(values, "values");
            }
        }
    }

    /**
     * What the owner should do after a message.
     */
    public sealed interface Output {

        /**
         * Every field passed and the values were submitted.
         *
         * @param values the values that were submitted
         */
        record Submitted(Values values) implements Output {

            /**
             * Rejects missing values.
             */
            public Submitted {
                values = Objects.requireNonNull(values, "values");
            }
        }

        /**
         * At least one field failed, so nothing was submitted.
         *
         * @param errors the errors, in field order
         */
        record Rejected(List<Error> errors) implements Output {

            /**
             * Copies the errors and rejects an empty list, which would be a submission.
             */
            public Rejected {
                errors = errors == null ? List.of() : List.copyOf(errors);
                if (errors.isEmpty()) {
                    throw new IllegalArgumentException("errors are required");
                }
            }
        }
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * How this form is configured.
     *
     * @return how this form is configured
     */
    public Config config() {
        return config;
    }

    /**
     * The form's own rows: its labels, one blank row in place of each editor, and its errors.
     *
     * <p>This is what a form that has been given no editors paints, and it is what a component's
     * view can show on its own. A form that places its fields uses {@link #element(Model, Map,
     * Function)} instead.</p>
     *
     * @param model the state to paint; required
     * @return the element, emitting this form's messages
     */
    public Element<Msg> element(Model model) {
        return element(Objects.requireNonNull(model, "model"), Map.of(), message -> message);
    }

    /**
     * The form with its fields placed: each label row, the application's editor, and the error row
     * when the field is invalid.
     *
     * <p>The application keeps each field's widget instance and model - the kernel has one focused
     * leaf, so the form cannot hold the keyboard while a field is editing - and renders each field's
     * element with that field's model. This method only places them: it composes the caller's
     * elements, never rebuilds them, so a text input keeps its instance, its pointer gesture and its
     * caret. A field the map does not name gets one blank row in place of its editor, so a form that
     * is missing an editor still paints its labels and errors.</p>
     *
     * @param <Out> the message type of the composed tree
     * @param model the state to paint; required
     * @param fieldElements each field's editor, by field id; null places none
     * @param messages maps this form's messages into the composed tree's message type; required
     * @return the composed element
     */
    public <Out> Element<Out> element(
            Model model, Map<String, Element<Out>> fieldElements, Function<? super Msg, ? extends Out> messages) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(messages, "messages");
        Map<String, Element<Out>> editors = fieldElements == null ? Map.of() : fieldElements;
        List<Element<Out>> blocks = new ArrayList<>(model.fields().size());
        for (Field field : model.fields()) {
            blocks.add(block(model, field, editors.get(field.id()), messages));
        }
        return Elements.column(blocks);
    }

    /**
     * The default actions: traversing the fields.
     *
     * <p>Submission is deliberately absent. A submit message carries the field values, and only the
     * application holds those, so the application binds its own submit key and builds the {@link
     * Msg.Submit} from what it stores. The traversal keys are here because they need nothing the
     * form does not already know.</p>
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(KeyMap.group(
                "Focus",
                KeyAction.<Msg>of("next-field", new Msg.Next(), "tab", "next field", "tab"),
                KeyAction.<Msg>of("previous-field", new Msg.Previous(), "shift+tab", "previous field", "shift+tab")));
    }

    /**
     * The actions this form handles.
     *
     * @return the actions this form handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(Model.of(config.fields()));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        // Focus is this widget's data: the traversal messages move it, so the reduction is not
        // wrapped in a refocus that would put the incoming field back.
        return WidgetUpdate.settled(model, message, action -> reduce(model, action), state -> state);
    }

    /**
     * The form's chrome: its labels, one blank row in place of each editor, and its errors.
     *
     * <p>A component's view has no editors to place, because the field widgets belong to the
     * application; an application that places its fields composes {@link #element(Model, Map,
     * Function)} in its own view instead.</p>
     *
     * @param model the state to paint; required
     * @param context the view context; required
     * @return the form's rows without its editors
     */
    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return element(model);
    }

    /**
     * One field's block: its label row, its editor and its error row.
     */
    private <Out> Element<Out> block(
            Model model, Field field, Element<Out> editor, Function<? super Msg, ? extends Out> messages) {
        boolean focused = model.isFocused(field.id());
        Element<Out> labelRow = new Element.Leaf<FormLabel.Model, Msg>(
                        label,
                        new FormLabel.Model(
                                new ChoiceRow.Row(
                                        StyledText.plain(focused ? FOCUS_MARKER : FOCUS_GAP),
                                        field.label(),
                                        focused,
                                        false,
                                        focused),
                                field.id()),
                        Sizing.fixed(1))
                .map(messages);
        List<Element<Out>> lines = new ArrayList<>(3);
        lines.add(labelRow);
        lines.add(editor == null ? Elements.spacer(1) : editor);
        model.error(field.id())
                .ifPresent(error -> lines.add(new Element.Leaf<FormErrorRow.Row, Msg>(
                                errorRow, new FormErrorRow.Row(StyledText.plain(error.message())), Sizing.fixed(1))
                        .map(messages)));
        return Elements.column(lines);
    }

    private Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Next next -> move(state, 1);
            case Msg.Previous previous -> move(state, -1);
            case Msg.Focus focus -> focus(state, focus.id());
            case Msg.Validate validate -> validate(state, validate.values());
            case Msg.Submit submit -> submit(state, submit.values());
        };
    }

    /**
     * Focus moved by a number of fields, wrapping at either end.
     */
    private static Result move(Model state, int delta) {
        int size = state.fields().size();
        if (size == 0) {
            return new Result(state, null);
        }
        // The model resolves its focused id to a field, so the current row is always present here.
        int current = indexOf(state.fields(), state.focused());
        int target = Math.floorMod(current + delta, size);
        return new Result(state.withFocused(state.fields().get(target).id()), null);
    }

    /**
     * Focus moved to a named field; an unknown id changes nothing.
     */
    private static Result focus(Model state, String id) {
        if (indexOf(state.fields(), id) < 0) {
            return new Result(state, null);
        }
        return new Result(state.withFocused(id), null);
    }

    /**
     * Errors recorded without a submission, leaving focus where the user put it.
     */
    private Result validate(Model state, Values values) {
        return new Result(state.withErrors(errors(values)), null);
    }

    /**
     * Errors recorded for a submission: focus moves to the first invalid field, and one output says
     * whether the values were submitted or refused.
     */
    private Result submit(Model state, Values values) {
        Model next = state.withErrors(errors(values));
        if (next.valid()) {
            return new Result(next, new Output.Submitted(values));
        }
        Model focused = next.withFocused(next.errors().get(0).field());
        return new Result(focused, new Output.Rejected(focused.errors()));
    }

    /**
     * The configured policy's errors, as the model will keep them.
     */
    private List<Error> errors(Values values) {
        List<Error> errors = config.validator().validate(values);
        return Objects.requireNonNull(errors, "errors");
    }

    /**
     * The row of a field, or -1.
     */
    private static int indexOf(List<Field> fields, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int index = 0; index < fields.size(); index++) {
            if (fields.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The form: its rows, its errors, focus traversal, presses, themes and its submission.
 */
class FormTest {

    private static final List<Form.Field> FIELDS = List.of(
            Form.Field.of("name", "Name"), Form.Field.of("email", "Email"), Form.Field.of("password", "Password"));

    /**
     * A name is required and an email needs an {@code @}.
     */
    private static final Form.Validator VALIDATOR = values -> {
        List<Form.Error> errors = new ArrayList<>();
        if (values.text("name").isBlank()) {
            errors.add(new Form.Error("name", "required"));
        }
        if (!values.text("email").contains("@")) {
            errors.add(new Form.Error("email", "needs an @"));
        }
        return errors;
    };

    private final Form form = new Form(Form.Config.of(FIELDS).withValidator(VALIDATOR));

    @Test
    void aFormPaintsEachLabelAndOneBlankRowInPlaceOfItsEditor() {
        Form empty = new Form(List.of());
        Form.Model none = empty.init(new InitContext(null, null)).model();
        assertThat(ElementScreen.render(empty.element(none), 3, 8).lines()).containsOnly("");

        Form.Model model = form.init(new InitContext(null, null)).model();
        assertThat(model.focused()).isEqualTo("name");
        assertThat(ElementScreen.render(form.element(model), 6, 12).lines())
                .containsExactly(
                        Form.FOCUS_MARKER + " Name",
                        "",
                        Form.FOCUS_GAP + " Email",
                        "",
                        Form.FOCUS_GAP + " Password",
                        "");
        // A focused field is the one with the accented marker; every label still starts in one column.
        Form.Model onEmail = apply(model, new Form.Msg.Next());
        assertThat(ElementScreen.render(form.element(onEmail), 6, 12).lines())
                .containsExactly(
                        Form.FOCUS_GAP + " Name",
                        "",
                        Form.FOCUS_MARKER + " Email",
                        "",
                        Form.FOCUS_GAP + " Password",
                        "");
        // The component view paints the same chrome as the form that places no editors.
        assertThat(ElementScreen.render(form.view(model, new ViewContext(new Viewport(6, 12))), 6, 12)
                        .lines())
                .isEqualTo(ElementScreen.render(form.element(model), 6, 12).lines());
    }

    @Test
    void theFormsEditorsSitBetweenTheirLabelsAndTheirErrorRows() {
        Form.Model model = form.init(new InitContext(null, null)).model();
        Map<String, Element<Form.Msg>> editors = editors();
        assertThat(form.config().fields()).isEqualTo(FIELDS);
        // A form given no editors at all paints the same blocks with blank rows in their place.
        assertThat(ElementScreen.render(form.element(model, null, message -> message), 6, 20)
                        .lines())
                .isEqualTo(ElementScreen.render(form.element(model), 6, 20).lines());

        assertThat(ElementScreen.render(form.element(model, editors, message -> message), 6, 20)
                        .lines())
                .containsExactly(
                        Form.FOCUS_MARKER + " Name",
                        "Amanda",
                        Form.FOCUS_GAP + " Email",
                        "amanda@example",
                        Form.FOCUS_GAP + " Password",
                        "correct horse");

        Form.Model invalid = model.withErrors(List.of(new Form.Error("email", "needs an @")));
        assertThat(ElementScreen.render(form.element(invalid, editors, message -> message), 7, 20)
                        .lines())
                .containsExactly(
                        Form.FOCUS_MARKER + " Name",
                        "Amanda",
                        Form.FOCUS_GAP + " Email",
                        "amanda@example",
                        FormErrorRow.MARKER + " needs an @",
                        Form.FOCUS_GAP + " Password",
                        "correct horse");
        assertThat(invalid.error("password")).isEmpty();
        assertThat(invalid.error("email").map(Form.Error::message)).contains("needs an @");
        assertThat(invalid.invalid("email")).isTrue();
        assertThat(invalid.invalid("name")).isFalse();
    }

    @Test
    void aNarrowOrEmptyFormClipsRatherThanOverflows() {
        Form.Model invalid =
                form.init(new InitContext(null, null)).model().withErrors(List.of(new Form.Error("name", "required")));

        assertThat(ElementScreen.render(form.element(invalid), 0, 0).lines()).isEmpty();
        assertThat(ElementScreen.render(form.element(invalid), 0, 12).lines()).isEmpty();
        // Three columns hold the marker and one letter of the label; the label is clipped, not moved.
        assertThat(ElementScreen.render(form.element(invalid), 3, 3).line(0)).isEqualTo(Form.FOCUS_MARKER + " N");
        // Three columns hold the error marker and one letter of the reason.
        assertThat(ElementScreen.render(form.element(invalid), 3, 3).line(2)).isEqualTo(FormErrorRow.MARKER + " r");
        assertThat(ElementScreen.render(form.element(invalid), 3, 1).line(2)).isEqualTo(FormErrorRow.MARKER);
        assertThat(ElementScreen.render(form.element(invalid), 3, 2).line(2))
                .as("the marker and its gap are all two columns hold")
                .isEqualTo(FormErrorRow.MARKER);
    }

    @Test
    void tabAndShiftTabMoveFocusAndWrapAtEitherEnd() {
        Form.Model model = form.init(new InitContext(null, null)).model();

        assertThat(apply(model, new Form.Msg.Next()).focused()).isEqualTo("email");
        assertThat(apply(apply(model, new Form.Msg.Next()), new Form.Msg.Next()).focused())
                .isEqualTo("password");
        assertThat(apply(apply(apply(model, new Form.Msg.Next()), new Form.Msg.Next()), new Form.Msg.Next())
                        .focused())
                .as("tab at the last field leads back to the first")
                .isEqualTo("name");
        assertThat(apply(model, new Form.Msg.Previous()).focused())
                .as("shift+tab at the first field leads to the last")
                .isEqualTo("password");
        assertThat(apply(apply(model, new Form.Msg.Previous()), new Form.Msg.Previous())
                        .focused())
                .isEqualTo("email");

        // The gestures the application installs, from the same map Help renders.
        Element<Form.Msg> root = form.element(model);
        List<Binding<Form.Msg>> keys = form.keys().bindings();
        assertThat(InteractionRouter.route(root, InputEvent.KeyInput.of(Key.TAB), List.of(), keys))
                .containsExactly(new Form.Msg.Next());
        assertThat(InteractionRouter.route(root, InputEvent.KeyInput.of(Key.parse("shift+tab")), List.of(), keys))
                .containsExactly(new Form.Msg.Previous());
        assertThat(InteractionRouter.route(root, InputEvent.KeyInput.of(Key.ENTER), List.of(), keys))
                .as("a submit key belongs to the application, which holds the values")
                .isEmpty();
        assertThat(form.keys().actions()).extracting(KeyAction::id).containsExactly("next-field", "previous-field");
    }

    @Test
    void submitReportsOneSubmittedOnlyWhenEveryFieldPasses() {
        Form.Model model = form.init(new InitContext(null, null)).model();
        Form.Values valid = Form.Values.of(Map.of("name", "Amanda", "email", "amanda@example"));
        Form.Values invalid = Form.Values.of(Map.of("name", " ", "email", "amanda"));

        var accepted = form.update(model, new Form.Msg.Submit(valid));
        assertThat(accepted.model().valid()).isTrue();
        assertThat(accepted.model().focused()).isEqualTo("name");
        assertThat(accepted.outputs()).containsExactly(new Form.Output.Submitted(valid));

        var refused = form.update(model, new Form.Msg.Submit(invalid));
        assertThat(refused.model().valid()).isFalse();
        assertThat(refused.model().errors())
                .containsExactly(new Form.Error("name", "required"), new Form.Error("email", "needs an @"));
        assertThat(refused.model().focused())
                .as("the first invalid field in form order takes focus")
                .isEqualTo("name");
        assertThat(refused.outputs())
                .containsExactly(new Form.Output.Rejected(
                        List.of(new Form.Error("name", "required"), new Form.Error("email", "needs an @"))));

        // A form focused further down still moves to the first invalid field.
        var moved = form.update(model.withFocused("password"), new Form.Msg.Submit(invalid));
        assertThat(moved.model().focused()).isEqualTo("name");
        // The same refusal again is still reported as one output.
        assertThat(form.update(refused.model(), new Form.Msg.Submit(invalid)).outputs())
                .containsExactly(refused.outputs().get(0));
    }

    @Test
    void validateRecordsErrorsWithoutMovingFocus() {
        Form.Model model = form.init(new InitContext(null, null)).model().withFocused("password");
        Form.Values values = Form.Values.of(Map.of("email", "amanda"));

        var checked = form.update(model, new Form.Msg.Validate(values));
        assertThat(checked.model().focused())
                .as("validating while typing must not move the user")
                .isEqualTo("password");
        assertThat(checked.model().errors())
                .containsExactly(new Form.Error("name", "required"), new Form.Error("email", "needs an @"));
        assertThat(checked.outputs()).isEmpty();

        var cleared = form.update(
                checked.model(), new Form.Msg.Validate(Form.Values.of(Map.of("name", "Amanda", "email", "a@b"))));
        assertThat(cleared.model().valid()).isTrue();
        assertThat(cleared.model().focused()).isEqualTo("password");
    }

    @Test
    void aPressOnALabelFocusesItsFieldAndAPressInTheEditorDoesNot() {
        Form.Model model = form.init(new InitContext(null, null)).model();
        Element<Form.Msg> root = form.element(model, editors(), message -> message);

        assertThat(pointer(root, 6, 20, press(2, 0)))
                .as("the email label is on the third row")
                .containsExactly(new Form.Msg.Focus("email"));
        assertThat(pointer(root, 6, 20, press(0, 3))).containsExactly(new Form.Msg.Focus("name"));
        assertThat(pointer(root, 6, 20, press(1, 0)))
                .as("a press inside an editor belongs to the editor")
                .isEmpty();
        assertThat(pointer(root, 6, 20, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 2, 0)))
                .isEmpty();
        assertThat(pointer(root, 6, 20, PointerEvent.release(PointerButton.LEFT, Modifiers.NONE, 2, 0)))
                .isEmpty();

        // The form composes the application's message type, so the press reaches it through the map.
        Element<String> mapped = form.element(model, Map.of("name", Elements.text("Amanda")), Object::toString);
        List<String> recorded = new ArrayList<>();
        ElementTree.Placement<String> placement = ElementTree.layout(mapped, new Rect(0, 0, 6, 20));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, press(0, 0), recorded::add);
        assertThat(recorded).containsExactly(new Form.Msg.Focus("name").toString());
    }

    @Test
    void theLabelsAndTheErrorsArePaintedInTheThemesStyles() {
        Form.Model model =
                form.init(new InitContext(null, null)).model().withErrors(List.of(new Form.Error("name", "required")));
        Element<Form.Msg> root = form.element(model, editors(), message -> message);

        ElementScreen dark = ElementScreen.render(root, 7, 20, Themes.DARK);
        ElementScreen light = ElementScreen.render(root, 7, 20, Themes.LIGHT);

        assertThat(dark.styles(0).get(0))
                .as("the focused marker is the accent")
                .isEqualTo(Themes.DARK.style(ThemeToken.ACCENT));
        assertThat(dark.styles(0).get(2)).as("the label is text").isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(dark.styles(3).get(0))
                .as("an unfocused marker is muted")
                .isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(3).get(2)).isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(dark.styles(2).get(0))
                .as("the error marker is the error style")
                .isEqualTo(Themes.DARK.style(ThemeToken.ERROR));
        assertThat(dark.styles(2).get(2))
                .as("the whole reason is the error style")
                .isEqualTo(Themes.DARK.style(ThemeToken.ERROR));
        assertThat(light.styles(0).get(0)).isEqualTo(Themes.LIGHT.style(ThemeToken.ACCENT));
        assertThat(light.styles(2).get(0)).isEqualTo(Themes.LIGHT.style(ThemeToken.ERROR));
        assertThat(light.styles(2).get(2)).isEqualTo(Themes.LIGHT.style(ThemeToken.ERROR));
    }

    @Test
    void fieldsValuesAndModelsAreValidatedAndNormalized() {
        assertThatThrownBy(() -> Form.Field.of(" ", "label"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field id");
        assertThatThrownBy(() -> new Form.Field(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Form.Field.of("a", null).label().isEmpty()).isTrue();
        assertThat(Form.Field.of("a", "A").withLabel(null).label().isEmpty()).isTrue();
        assertThatThrownBy(() -> new Form.Config(List.of(Form.Field.of("a", "A"), Form.Field.of("a", "Again")), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate field id");
        assertThat(Form.Config.of(null).fields()).isEmpty();
        assertThat(Form.Config.of(FIELDS).withFields(null).fields()).isEmpty();
        assertThat(Form.Config.of(FIELDS).withValidator(null).validator().validate(Form.Values.empty()))
                .isEmpty();
        assertThatThrownBy(() -> new Form.Config(Arrays.asList(Form.Field.of("a", "A"), null), null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new Form.Error(" ", "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field id");
        assertThat(new Form.Error("a", null).message()).isEqualTo("invalid");
        assertThat(new Form.Error("a", " ").message()).isEqualTo("invalid");

        assertThatThrownBy(() -> Form.Values.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Form.Values.of(Map.of(" ", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field id");
        Map<String, String> withNull = new LinkedHashMap<>();
        withNull.put("a", null);
        assertThat(Form.Values.of(withNull).text("a")).isEmpty();
        assertThat(Form.Values.empty().text("a")).isEmpty();
        assertThat(Form.Values.empty().text(null)).isEmpty();
        assertThat(Form.Values.empty().with("a", "A").text("a")).isEqualTo("A");
        assertThat(Form.Values.empty().with("a", null).text("a")).isEmpty();
        assertThatThrownBy(() -> Form.Values.empty().with(null, "A")).isInstanceOf(NullPointerException.class);

        // A model resolves its focused id, drops errors for fields it does not show and keeps one
        // error per field in field order.
        Form.Model model = new Form.Model(
                FIELDS,
                "gone",
                List.of(
                        new Form.Error("password", "short"),
                        new Form.Error("name", "required"),
                        new Form.Error("name", "again"),
                        new Form.Error("gone", "missing")));
        assertThat(model.focused()).isEqualTo("name");
        assertThat(model.errors())
                .containsExactly(new Form.Error("name", "required"), new Form.Error("password", "short"));
        assertThat(model.isFocused("name")).isTrue();
        assertThat(model.isFocused(null)).isFalse();
        assertThat(model.error(null)).isEmpty();
        assertThat(model.withErrors(null).valid()).isTrue();
        assertThat(model.withFocused(null).focused()).isEqualTo("name");
        assertThat(model.withFocused("email").focused()).isEqualTo("email");
        assertThat(model.withFields(List.of(Form.Field.of("only", "Only"))).focused())
                .isEqualTo("only");
        assertThat(model.withFields(List.of(Form.Field.of("only", "Only"))).errors())
                .isEmpty();
        assertThat(Form.Model.of(null).focused()).isEmpty();
        assertThat(Form.Model.of(null).fields()).isEmpty();

        // An empty form has nothing to move to and submits trivially.
        Form empty = new Form(List.of());
        Form.Model none = empty.init(new InitContext(null, null)).model();
        assertThat(none.focused()).isEmpty();
        assertThat(empty.update(none, new Form.Msg.Next()).model()).isSameAs(none);
        assertThat(empty.update(none, new Form.Msg.Previous()).model()).isSameAs(none);
        assertThat(empty.update(none, new Form.Msg.Focus("gone")).model()).isSameAs(none);
        assertThat(empty.update(none, new Form.Msg.Submit(Form.Values.empty())).outputs())
                .containsExactly(new Form.Output.Submitted(Form.Values.empty()));

        // An unknown focus, an unchanged focus and an unchanged error list keep the instance.
        Form.Model present = form.init(new InitContext(null, null)).model();
        assertThat(form.update(present, new Form.Msg.Focus("gone")).model()).isSameAs(present);
        assertThat(form.update(present, new Form.Msg.Focus(null)).model()).isSameAs(present);
        assertThat(form.update(present, new Form.Msg.Focus("name")).model()).isSameAs(present);
        Form.Model checked =
                form.update(present, new Form.Msg.Validate(Form.Values.empty())).model();
        assertThat(checked.errors()).hasSize(2);
        assertThat(form.update(checked, new Form.Msg.Validate(Form.Values.empty()))
                        .model())
                .isSameAs(checked);

        assertThatThrownBy(() -> new Form((Form.Config) null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> form.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> form.update(null, new Form.Msg.Next())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> form.update(present, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> form.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> form.element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> form.element(present, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> form.element(present, Map.of(), null)).isInstanceOf(NullPointerException.class);
        assertThat(new Form.Msg.Focus(null).id()).isEmpty();
        assertThatThrownBy(() -> new Form.Msg.Submit(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Form.Msg.Validate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Form.Output.Submitted(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Form.Output.Rejected(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Form.Output.Rejected(null)).isInstanceOf(IllegalArgumentException.class);
    }

    private Form.Model apply(Form.Model model, Form.Msg message) {
        return form.update(model, message).model();
    }

    /**
     * The three editors every layout test places, one row each.
     */
    private static Map<String, Element<Form.Msg>> editors() {
        return Map.of(
                "name", Elements.text("Amanda"),
                "email", Elements.text("amanda@example"),
                "password", Elements.text("correct horse"));
    }

    private static List<Form.Msg> pointer(Element<Form.Msg> root, int rows, int cols, PointerEvent report) {
        List<Form.Msg> recorded = new ArrayList<>();
        ElementTree.Placement<Form.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, report, recorded::add);
        return recorded;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }
}

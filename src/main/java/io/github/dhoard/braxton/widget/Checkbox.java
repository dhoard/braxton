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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A checkbox: one label, one box, one yes or no.
 *
 * <p>The box is the theme's accent when it is checked and the theme's muted style when it is not, so
 * the state is legible without reading the glyph, and the row is a themed leaf so a live theme
 * switch repaints it without the model changing. The label is the widget's configuration and may be
 * styled; the theme's text style is resolved underneath whatever the application set.</p>
 *
 * <p>{@code space} toggles the box and so does a left press anywhere on the row. Nothing else is a
 * checkbox gesture: {@code enter} belongs to the form the box sits in, so the widget declines it
 * rather than swallowing it, and an arrow is answered by whatever moves focus. The gesture is a
 * {@link KeyMap} - {@link #defaultKeys()} defines it and {@link #keys()} is what the widget routes,
 * so {@link Help} describes what actually fires.</p>
 *
 * <p>A toggle reports an {@link Output.Changed} carrying the new state, because the box is a value
 * the application stores: an application that flipped the state on its own would be behind the
 * paint, and one that flipped it twice would agree with the paint and still be wrong.</p>
 *
 * <p>A focused box fills its row with the theme's {@code SELECTED} and merges the theme's
 * {@code FOCUS} into the box and the label, so a box that owns the keyboard is visible at a glance.
 * A label wider than the row is clipped rather than wrapped, because a checkbox is one line.</p>
 *
 * <p>A checkbox is one object per placed box, held across frames the way a component is, because the
 * router keeps a pointer gesture with the widget it started on.</p>
 *
 * @see Elements#checkbox(Checkbox, Checkbox.Model, java.util.function.Function)
 */
public final class Checkbox
        implements Component<Checkbox.Model, Checkbox.Msg, Checkbox.Output>, Interactive<Checkbox.Model, Checkbox.Msg> {

    /**
     * The box of a checked checkbox.
     */
    public static final String CHECKED = "[\u2713]";

    /**
     * The box of an unchecked checkbox, as wide as {@link #CHECKED}.
     */
    public static final String UNCHECKED = "[ ]";

    private final StyledText label;

    /**
     * The row's painter: the box and the label in the theme's styles.
     */
    private final ChoiceRow<Msg> row = new ChoiceRow<>();

    /**
     * A checkbox over its label.
     *
     * @param label the label painted after the box; null is empty
     */
    public Checkbox(StyledText label) {
        this.label = label == null ? StyledText.empty() : label;
    }

    /**
     * Immutable checkbox state.
     *
     * @param checked whether the box is checked
     * @param focused whether the checkbox owns focus
     */
    public record Model(boolean checked, boolean focused) {

        /**
         * A focused checkbox in a given state.
         *
         * @param checked whether the box is checked
         * @return the model
         */
        public static Model of(boolean checked) {
            return new Model(checked, true);
        }

        /**
         * This checkbox checked or unchecked.
         *
         * @param value whether the box is checked
         * @return the new model
         */
        public Model withChecked(boolean value) {
            return new Model(value, focused);
        }

        /**
         * The same checkbox, focused or not.
         *
         * @param value whether the checkbox owns focus
         * @return the new model
         */
        public Model withFocus(boolean value) {
            return new Model(checked, value);
        }
    }

    /**
     * The checkbox's messages.
     */
    public sealed interface Msg {

        /**
         * Flips the box.
         */
        record Toggle() implements Msg {}
    }

    /**
     * What the owner should do after a gesture.
     */
    public sealed interface Output {

        /**
         * The box was flipped.
         *
         * @param checked the box's new state
         */
        record Changed(boolean checked) implements Output {}
    }

    private record Result(Model state, Output intent) implements WidgetResult<Model, Output> {}

    /**
     * The label this checkbox paints.
     *
     * @return the label this checkbox paints
     */
    public StyledText label() {
        return label;
    }

    /**
     * The checkbox's element: its one row, and the checkbox itself answering for it.
     *
     * @param model the state to paint; required
     * @return the element, emitting this checkbox's messages
     */
    public Element<Msg> element(Model model) {
        return scene(Objects.requireNonNull(model, "model"));
    }

    /**
     * The default action: one key that flips the box.
     *
     * @return the default key map
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(KeyMap.group(
                "Actions", KeyAction.<Msg>of("toggle", new Msg.Toggle(), "space", "check or clear", "space")));
    }

    /**
     * The actions this checkbox handles.
     *
     * @return the actions this checkbox handles
     */
    public KeyMap<Msg> keys() {
        return defaultKeys();
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        Objects.requireNonNull(context, "context");
        return Update.next(Model.of(false));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        return WidgetUpdate.settled(
                model, message, action -> reduce(model, action), state -> state.withFocus(model.focused()));
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return scene(model);
    }

    /**
     * The checkbox's key, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, Model model, Consumer<Msg> emit) {
        if (!key.activates()) {
            // A release carries the same key its press did, so it never fires the press a second time.
            return;
        }
        Optional<KeyAction<Msg>> action = keys().actionFor(key.key());
        action.ifPresent(value -> emit.accept(value.message()));
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(Model model) {
        return model.focused();
    }

    /**
     * A press anywhere on the row flips the box; no other report is a checkbox gesture.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
        if (!pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        PointerEvent local = pointer.localTo(bounds.row(), bounds.col());
        if (local.row() >= 0 && local.row() < bounds.rows()) {
            emit.accept(new Msg.Toggle());
        }
    }

    /**
     * The one row, with the checkbox as the leaf that answers for it.
     */
    private Element<Msg> scene(Model model) {
        List<Element<Msg>> lines = new ArrayList<>(1);
        lines.add(new Element.Leaf<ChoiceRow.Row, Msg>(
                row,
                // A checkbox is one row, so its row is the highlight while the box owns focus: that is
                // what fills it with the theme's selection and merges the theme's focus style into the
                // box and the label.
                new ChoiceRow.Row(
                        StyledText.plain(model.checked() ? CHECKED : UNCHECKED),
                        label,
                        model.checked(),
                        model.focused(),
                        model.focused()),
                Sizing.fixed(1)));
        return Elements.stack(Elements.column(lines), new Element.Leaf<>(this, model, Sizing.measured()));
    }

    private static Result reduce(Model state, Msg action) {
        return switch (action) {
            case Msg.Toggle toggle -> {
                boolean checked = !state.checked();
                yield new Result(new Model(checked, state.focused()), new Output.Changed(checked));
            }
        };
    }
}

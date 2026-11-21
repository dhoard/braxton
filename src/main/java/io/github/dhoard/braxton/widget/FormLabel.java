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

import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The label row of a form field, which is also the target that focuses it.
 *
 * <p>A form's label is one row of the form, and a press anywhere on that row focuses the field below
 * it. The row paints exactly what a {@link ChoiceRow} paints - a marker in the first columns, one
 * separating column, then the label - so a form label and a choice row cannot drift apart in style,
 * spacing or clipping. Wrapping the painter rather than restating it is what keeps the theme's
 * {@code TEXT}, {@code ACCENT} and focus rules in one place.</p>
 *
 * <p>The press stays the row's own: the label is a leaf of its own block, so a press inside the
 * field's editor below it reaches the editor and never the form. That is what lets a text input
 * place its caret without the form taking the click away.</p>
 */
final class FormLabel implements ThemedWidget<FormLabel.Model, Form.Msg> {

    /**
     * One label row.
     *
     * @param row the row the shared painter draws
     * @param field the id of the field the row labels, which a press focuses; never blank
     */
    record Model(ChoiceRow.Row row, String field) {

        /**
         * Rejects a missing row and a row without a field.
         */
        Model {
            row = Objects.requireNonNull(row, "row");
            if (field == null || field.isBlank()) {
                throw new IllegalArgumentException("field id is required");
            }
        }
    }

    /**
     * The shared label painter this row delegates to.
     */
    private final ChoiceRow<Form.Msg> row = new ChoiceRow<>();

    /**
     * One row, as wide as the marker and the label need, inside what is offered.
     */
    @Override
    public Size measure(Size max, Model model) {
        return row.measure(max, model.row());
    }

    /**
     * The whole width it is offered: a field's label is a row of the form it sits in.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Model model) {
        return row.arrange(available, measured, model.row());
    }

    /**
     * Paints the marker and the label in the theme's styles.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        row.paint(surface, bounds, model.row(), theme);
    }

    /**
     * A left press on the label focuses the field it labels.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Form.Msg> emit) {
        if (!pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        emit.accept(new Form.Msg.Focus(model.field()));
    }
}

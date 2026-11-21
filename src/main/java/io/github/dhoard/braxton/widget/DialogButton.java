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
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One button of a {@link Dialog}: a bracketed label, the highlight while the dialog's keys are on it,
 * and the press that activates it.
 *
 * <p>The button answers its own press instead of letting the dialog work out which button a column
 * belonged to: it is placed where the layout puts it and it is what a pointer reaches, so the press
 * and the cells cannot disagree about which action was meant. That is also why it is a leaf of its
 * own rather than a row inside the dialog's interactive leaf.</p>
 *
 * <p>The brackets are the theme's {@code BORDER} and the label the theme's {@code TEXT}, so a row of
 * buttons reads as chrome around a word. The button the dialog's keys are on is filled with the
 * theme's {@code SELECTED}, and a highlighted button in a focused dialog merges {@code FOCUS} into
 * what it paints over that fill, the rule every highlighted row in this package follows.</p>
 *
 * @param <Msg> the message type of the dialog the button is placed in
 */
final class DialogButton<Msg> implements ThemedWidget<DialogButton.Model<Msg>, Msg> {

    /**
     * The character painted before a button's label.
     */
    static final String OPEN = "[";

    /**
     * The character painted after a button's label.
     */
    static final String CLOSE = "]";

    /**
     * One painted button.
     *
     * @param action the action the button paints and reports; required
     * @param highlighted whether the dialog's keys are on this button
     * @param focused whether the dialog holds focus
     * @param press the message a press on this button emits; required
     */
    record Model<Msg>(Option action, boolean highlighted, boolean focused, Msg press) {

        /**
         * Rejects a button without an action or a message.
         */
        Model {
            action = Objects.requireNonNull(action, "action");
            press = Objects.requireNonNull(press, "press");
        }
    }

    /**
     * One button, as wide as its brackets and label need, inside what is offered.
     */
    @Override
    public Size measure(Size max, Model<Msg> model) {
        if (model == null || max.rows() <= 0 || max.cols() <= 0) {
            return new Size(0, 0);
        }
        return new Size(1, Math.min(max.cols(), columns(model.action().label())));
    }

    /**
     * The whole width it is offered: the highlight covers the button, which is what a button is.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Model<Msg> model) {
        return new Rect(available.row(), available.col(), Math.min(available.rows(), 1), available.cols());
    }

    /**
     * Paints the highlight, then the bracketed label.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model<Msg> model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (model == null || bounds.isEmpty() || bounds.rows() <= 0) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        if (model.highlighted()) {
            surface.fill(bounds, resolved.style(ThemeToken.SELECTED));
        }
        Style edge = resolved.style(ThemeToken.BORDER);
        Style label = resolved.style(ThemeToken.TEXT);
        if (model.highlighted() && model.focused()) {
            // Focus colors the button's content rather than its selection's background: the theme's
            // focus style is a foreground style, and filling a button with it would color the cells
            // the label does not cover.
            Style focus = resolved.style(ThemeToken.FOCUS);
            edge = edge.merge(focus);
            label = label.merge(focus);
        }
        surface.write(bounds, label(model.action().label(), edge, label), WidthPolicy.PINNED);
    }

    /**
     * A press on the button activates its action.
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, Model<Msg> model, Consumer<Msg> emit) {
        if (pointer == null || model == null || !pointer.isLeftPress() || bounds.isEmpty()) {
            return;
        }
        emit.accept(model.press());
    }

    /**
     * The bracketed label, with the brackets and the text in their own styles.
     */
    private static StyledText label(StyledText value, Style edge, Style text) {
        return StyledText.of(OPEN, edge)
                .append(RowText.styled(RowText.singleRow(value), text))
                .append(StyledText.of(CLOSE, edge));
    }

    /**
     * The columns a button asks for: its brackets and its label.
     */
    private static int columns(StyledText value) {
        return TextMeasure.columns(OPEN) + RowText.singleRow(value).columns() + TextMeasure.columns(CLOSE);
    }
}

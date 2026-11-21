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

import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.util.Objects;

/**
 * The box a {@link Dialog} draws around its content: a border and a title in the theme's border
 * style.
 *
 * <p>{@link Elements#frame} takes the style to draw with at build time, which is right for a scene
 * that has already chosen its colors and wrong for a widget whose contract is that a live theme
 * switch repaints it. This leaf is the themed form of the same decoration: it delegates the painting
 * to {@link Frame}, so the corners, the edges and the title are painted by the one implementation
 * that already knows how, and it resolves that style from the frame's theme instead of from its
 * model.</p>
 *
 * <p>Like the frame it wraps, it asks for no cells of its own - the content beside it decides the
 * size - and it answers no pointer report, because a click inside a bordered region belongs to what
 * is inside it.</p>
 *
 * @param <Msg> the message type of the dialog the box is placed in
 */
final class DialogFrame<Msg> implements ThemedWidget<DialogFrame.Model, Msg> {

    private final Frame<Msg> frame = Frame.of();

    /**
     * What the box draws.
     *
     * @param border the border characters; null draws nothing
     * @param title the title on the top edge; null or empty for none
     */
    record Model(Border border, StyledText title) {

        /**
         * Substitutes the defaults for a missing border or title.
         */
        Model {
            border = border == null ? Border.NONE : border;
            title = title == null ? StyledText.empty() : title;
        }
    }

    /**
     * A decoration asks for nothing: the content beside it decides the size.
     */
    @Override
    public Size measure(Size max, Model model) {
        return frame.measure(max, drawn(model, Style.NONE));
    }

    /**
     * The whole rectangle it is offered; the content beside it sets the size.
     */
    @Override
    public Rect arrange(Rect available, Size measured, Model model) {
        return available;
    }

    /**
     * Paints the border and the title in the theme's border style.
     */
    @Override
    public void paint(Surface surface, Rect bounds, Model model, Theme theme) {
        Objects.requireNonNull(surface, "surface");
        if (model == null) {
            return;
        }
        Theme resolved = theme == null ? Themes.DARK : theme;
        frame.paint(surface, bounds, drawn(model, resolved.style(ThemeToken.BORDER)));
    }

    /**
     * The frame's own model, with the border and title this box draws.
     */
    private static Frame.Model drawn(Model model, Style style) {
        Model present = model == null ? new Model(Border.NONE, null) : model;
        return Frame.Model.of(present.border()).withTitle(present.title()).withStyle(style);
    }
}

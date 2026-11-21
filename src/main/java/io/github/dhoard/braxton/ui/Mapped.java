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

package io.github.dhoard.braxton.ui;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Theme;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One widget lifted into another message type.
 *
 * <p>A child component speaks its own message type and its parent speaks another, so the tree a
 * parent composes carries the parent's type. Mapping is the whole answer: every message the wrapped
 * widget emits goes through the mapper and nothing else changes, which is how
 * {@link Element#map(Function)} turns a child's element tree into a parent's without rebuilding the
 * widget.</p>
 *
 * <p>The wrapper is transparent in every phase - measure, arrange, paint, focus, hit testing, keys,
 * input, pointer and caret all reach the wrapped widget - so mapping a subtree cannot change its
 * geometry, its focus state or where its caret sits. It also carries a theme through when the wrapped
 * widget resolves its styles from one, because hiding the theme would repaint a themed widget in
 * styles nobody asked for.</p>
 *
 * @param <M> the widget's model type
 * @param <Msg> the message type the wrapped widget emits
 * @param <Msg2> the message type the tree speaks
 */
final class Mapped<M, Msg, Msg2> implements ThemedWidget<M, Msg2> {

    private final Widget<M, Msg> widget;
    private final Function<? super Msg, ? extends Msg2> mapper;

    Mapped(Widget<M, Msg> widget, Function<? super Msg, ? extends Msg2> mapper) {
        this.widget = Objects.requireNonNull(widget, "widget");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * The widget this wrapper lifts, which is what an interaction identity belongs to.
     */
    Widget<M, Msg> lifted() {
        return widget;
    }

    @Override
    public Size measure(Size max, M model) {
        return widget.measure(max, model);
    }

    @Override
    public Rect arrange(Rect available, Size measured, M model) {
        return widget.arrange(available, measured, model);
    }

    @Override
    public void paint(Surface surface, Rect bounds, M model) {
        widget.paint(surface, bounds, model);
    }

    @Override
    public void paint(Surface surface, Rect bounds, M model, Theme theme) {
        if (widget instanceof ThemedWidget<M, Msg> themed) {
            themed.paint(surface, bounds, model, theme);
        } else {
            widget.paint(surface, bounds, model);
        }
    }

    @Override
    public boolean canFocus(M model) {
        return widget.canFocus(model);
    }

    @Override
    public boolean focused(M model) {
        return widget.focused(model);
    }

    @Override
    public InteractionState state(M model) {
        return widget.state(model);
    }

    @Override
    public boolean hit(Rect bounds, int row, int col, M model) {
        return widget.hit(bounds, row, col, model);
    }

    @Override
    public void onKey(InputEvent.KeyInput key, M model, Consumer<Msg2> emit) {
        widget.onKey(key, model, message -> emit.accept(mapper.apply(message)));
    }

    /**
     * Paste and the other text events reach the wrapped widget too.
     *
     * <p>A paste is input rather than a gesture, so it arrives through {@code onInput} beside the
     * keys; a wrapper that forwarded one and not the other made a mapped text field refuse text a
     * user pasted into it, which is exactly the failure a composition cannot see from its own
     * source.</p>
     */
    @Override
    public void onInput(InputEvent event, M model, Consumer<Msg2> emit) {
        widget.onInput(event, model, message -> emit.accept(mapper.apply(message)));
    }

    @Override
    public Cursor cursor(Rect bounds, M model) {
        return widget.cursor(bounds, model);
    }

    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, M model, Consumer<Msg2> emit) {
        widget.onPointer(pointer, bounds, model, message -> emit.accept(mapper.apply(message)));
    }
}

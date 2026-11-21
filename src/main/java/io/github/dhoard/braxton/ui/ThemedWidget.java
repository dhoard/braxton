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

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Theme;

/**
 * A widget that resolves its styles from the active semantic theme.
 *
 * @param <M> the model type
 * @param <Msg> the message type
 */
public interface ThemedWidget<M, Msg> extends Widget<M, Msg> {

    /**
     * Paints with the current theme; interaction state remains in the model.
     *
     * @param surface the clipped surface to paint
     * @param bounds the allocated cell rectangle
     * @param model the immutable model to inspect
     * @param theme the resolved application theme
     */
    void paint(Surface surface, Rect bounds, M model, Theme theme);

    @Override
    default void paint(Surface surface, Rect bounds, M model) {
        paint(surface, bounds, model, ThemesRef.DEFAULT);
    }

    /**
     * Fallback theme for a paint call made without a live theme context.
     */
    final class ThemesRef {
        private ThemesRef() {}

        static final Theme DEFAULT = io.github.dhoard.braxton.style.Themes.DARK;
    }
}

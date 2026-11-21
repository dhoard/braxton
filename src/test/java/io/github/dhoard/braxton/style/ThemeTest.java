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

package io.github.dhoard.braxton.style;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.ui.InteractionState;
import io.github.dhoard.braxton.ui.ThemedWidget;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Semantic themes switch by repainting, without changing widget models or interaction state.
 */
class ThemeTest {

    private static final class AccentLabel implements ThemedWidget<Integer, String> {

        @Override
        public Size measure(Size max, Integer model) {
            return new Size(1, 2);
        }

        @Override
        public void paint(Surface surface, Rect bounds, Integer model, Theme theme) {
            surface.put(bounds.row(), bounds.col(), "X", theme.style(ThemeToken.TEXT), null, 1);
        }

        @Override
        public InteractionState state(Integer model) {
            return InteractionState.NONE.withFocused(model == 1);
        }

        @Override
        public boolean canFocus(Integer model) {
            return true;
        }
    }

    @Test
    void builtInThemesCoverEverySemanticToken() {
        for (Theme theme : List.of(Themes.LIGHT, Themes.DARK, Themes.HIGH_CONTRAST)) {
            for (ThemeToken token : ThemeToken.values())
                assertThat(theme.style(token)).isNotNull();
        }
        assertThat(Themes.LIGHT.style(ThemeToken.TEXT)).isNotEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(Themes.HIGH_CONTRAST.name()).isEqualTo("high-contrast");
    }

    @Test
    void storeNotifiesOnRealSwitchOnly() {
        ThemeStore store = new ThemeStore(Themes.LIGHT);
        AtomicInteger notices = new AtomicInteger();
        Runnable unsubscribe = store.onChange(notices::incrementAndGet);
        store.set(Themes.LIGHT);
        store.set(Themes.DARK);
        assertThat(notices.get()).isEqualTo(1);
        unsubscribe.run();
        store.set(Themes.HIGH_CONTRAST);
        assertThat(notices.get()).isEqualTo(1);
        assertThat(store.get()).isEqualTo(Themes.HIGH_CONTRAST);
    }

    @Test
    void switchingThemeRepaintsWithoutLosingState() {
        Element<String> root = new Element.Leaf<>(new AccentLabel(), 1);
        var placement = ElementTree.layout(root, new Rect(0, 0, 3, 8));
        Screen screen = new Screen(3, 8);

        ElementTree.paint(placement, screen, Themes.LIGHT);
        var light = screen.toCanvas().style(0, 0);
        ElementTree.paint(placement, screen, Themes.DARK);
        var dark = screen.toCanvas().style(0, 0);

        assertThat(light).isNotEqualTo(dark);
        // A repaint does not touch interaction state: the focusable set is what the model says.
        assertThat(InteractionRouter.focusables(placement)).hasSize(1);
    }
}

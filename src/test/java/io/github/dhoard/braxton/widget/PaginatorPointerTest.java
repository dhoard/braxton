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

import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.style.TextMeasure;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * The page indicator's pointer mapping and the click that jumps to a page.
 */
class PaginatorPointerTest {

    private static Paginator.Config config() {
        return Paginator.Config.defaults();
    }

    private static Paginator.Model model(int page, int pages) {
        return new Paginator.Model(page, pages, 1, true);
    }

    @Test
    void aDotNamesItsOwnPage() {
        Paginator.Config config = config();
        Paginator.Model state = model(0, 3);

        assertThat(Paginator.pageAt(state, config, 40, 0)).hasValue(0);
        assertThat(Paginator.pageAt(state, config, 40, 1)).isEmpty();
        assertThat(Paginator.pageAt(state, config, 40, 2)).hasValue(1);
        assertThat(Paginator.pageAt(state, config, 40, 4)).hasValue(2);
        assertThat(Paginator.pageAt(state, config, 40, 5)).isEmpty();
        assertThat(Paginator.pageAt(state, config, 40, 400)).isEmpty();
    }

    @Test
    void theWindowFollowsTheCurrentPage() {
        Paginator.Config config = config();
        Paginator.Model last = model(19, 20);

        int ellipsis = TextMeasure.columns(Paginator.ELLIPSIS);
        OptionalInt first = Paginator.pageAt(last, config, 40, 0);
        assertThat(first).isEmpty();
        assertThat(Paginator.pageAt(last, config, 40, ellipsis + 1)).hasValue(13);
    }

    @Test
    void anIndicatorThatNamesNoPageNamesNoPage() {
        assertThat(Paginator.pageAt(model(0, 0), config(), 40, 0)).isEmpty();
        assertThat(Paginator.pageAt(model(0, 5), config().withAppearance(Paginator.Appearance.numeric()), 40, 0))
                .isEmpty();
        assertThat(Paginator.pageAt(model(0, 5), config(), 0, 0)).isEmpty();
        assertThat(Paginator.pageAt(model(0, 5), config(), 40, -1)).isEmpty();
    }

    @Test
    void aClickOnADotAsksForThatPage() {
        Paginator paginator = new Paginator(config());
        Paginator.Model state = model(0, 3);

        assertThat(click(paginator, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, 4)))
                .contains(new Paginator.Msg.GoTo(2));
        assertThat(click(paginator, state, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, 1)))
                .isEmpty();
        assertThat(click(paginator, state, PointerEvent.release(PointerButton.LEFT, Modifiers.NONE, 0, 0)))
                .isEmpty();
    }

    /**
     * The message a report produces, offered to the pager the way the runtime offers it.
     */
    private static Optional<Paginator.Msg> click(Paginator paginator, Paginator.Model state, PointerEvent report) {
        List<Paginator.Msg> produced = new java.util.ArrayList<>(1);
        paginator.onPointer(report, new Rect(0, 0, 1, 40), state, produced::add);
        return produced.stream().findFirst();
    }
}

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

package io.github.dhoard.braxton.line;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyEventType;
import org.junit.jupiter.api.Test;

/**
 * The two prompt key maps, asserted as the one definition of what a prompt's keys mean.
 */
class PromptKeysTest {

    @Test
    void aSingleChoiceListChoosesWithEnterOrSpace() {
        assertThat(PromptKeys.command(PromptKeys.single(), press("enter"))).contains(PromptKeys.Command.CHOOSE);
        assertThat(PromptKeys.command(PromptKeys.single(), press("space"))).contains(PromptKeys.Command.CHOOSE);
        // The LF terminal's Enter arrives as ctrl+j, and it answers the same question.
        assertThat(PromptKeys.command(PromptKeys.single(), press("ctrl+j"))).contains(PromptKeys.Command.CHOOSE);
    }

    @Test
    void aMultipleChoiceListMarksWithSpaceAndAcceptsWithEnter() {
        assertThat(PromptKeys.command(PromptKeys.multiple(), press("space"))).contains(PromptKeys.Command.TOGGLE);
        assertThat(PromptKeys.command(PromptKeys.multiple(), press("enter"))).contains(PromptKeys.Command.CHOOSE);
    }

    @Test
    void theMovementAndCancellationGesturesAreTheSameInBothLists() {
        for (var map : java.util.List.of(PromptKeys.single(), PromptKeys.multiple())) {
            assertThat(PromptKeys.command(map, press("up"))).contains(PromptKeys.Command.UP);
            assertThat(PromptKeys.command(map, press("down"))).contains(PromptKeys.Command.DOWN);
            assertThat(PromptKeys.command(map, press("home"))).contains(PromptKeys.Command.FIRST);
            assertThat(PromptKeys.command(map, press("end"))).contains(PromptKeys.Command.LAST);
            assertThat(PromptKeys.command(map, press("pageup"))).contains(PromptKeys.Command.PAGE_UP);
            assertThat(PromptKeys.command(map, press("pagedown"))).contains(PromptKeys.Command.PAGE_DOWN);
            assertThat(PromptKeys.command(map, press("escape"))).contains(PromptKeys.Command.CANCEL);
            assertThat(PromptKeys.command(map, press("ctrl+c"))).contains(PromptKeys.Command.CANCEL);
        }
    }

    @Test
    void aGestureNeitherListNamesAsksForNothing() {
        assertThat(PromptKeys.command(PromptKeys.single(), press("tab"))).isEmpty();
        assertThat(PromptKeys.command(PromptKeys.multiple(), press("alt+d"))).isEmpty();
        assertThat(PromptKeys.command(PromptKeys.single(), null)).isEmpty();
        assertThat(PromptKeys.command(null, press("enter"))).isEmpty();
    }

    @Test
    void aReleaseAPasteOrAReplyAsksForNothing() {
        assertThat(PromptKeys.command(PromptKeys.single(), release("enter"))).isEmpty();
        assertThat(PromptKeys.command(PromptKeys.single(), new InputEvent.PasteInput("enter")))
                .isEmpty();
        assertThat(PromptKeys.command(PromptKeys.single(), new InputEvent.FocusInput(true)))
                .isEmpty();
    }

    private static InputEvent press(String spec) {
        return InputEvent.KeyInput.of(Key.parse(spec), "");
    }

    private static InputEvent release(String spec) {
        InputEvent event = press(spec);
        return ((InputEvent.KeyInput) event).withType(KeyEventType.RELEASE);
    }
}

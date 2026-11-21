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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.*;

import examples.parity.DebugOverlayDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.testing.Routing;
import io.github.dhoard.braxton.ui.DebugOverlay;
import io.github.dhoard.braxton.ui.ElementTree;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParityDebugOverlayTest {
    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static ComponentDriver<DebugOverlayDemo.Model, DebugOverlayDemo.Msg, Never> scene() {
        return ComponentDriver.initialize(new DebugOverlayDemo.Scene(), new InitContext(CONTEXT.viewport(), null));
    }

    private static void press(ComponentDriver<DebugOverlayDemo.Model, DebugOverlayDemo.Msg, Never> driver, String key) {
        for (var message :
                Routing.route(driver.component(), driver.model(), CONTEXT, InputEvent.KeyInput.of(Key.parse(key))))
            driver.send(message);
    }

    @Test
    void leavingTheDiagnosticsDemoDisablesItsSessionPreference(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        var scene = new examples.parity.Showcase.Scene(root);
        var initial = scene.init(new InitContext(CONTEXT.viewport(), null));
        int index = scene.entries().size() - 1;
        assertThat(scene.entries().get(index).id()).isEqualTo("31");
        var opened = scene.update(initial.model(), new examples.parity.Showcase.Msg.Open(index));
        assertThat(scene.update(opened.model(), new examples.parity.Showcase.Msg.Back())
                        .command())
                .isEqualTo(Command.debugOverlay(false));
        var replaced = scene.update(opened.model(), new examples.parity.Showcase.Msg.Open(0))
                .command();
        if (replaced instanceof Command.Sequence<?> sequence) {
            assertThat(sequence.commands().getFirst()).isEqualTo(Command.debugOverlay(false));
        } else {
            assertThat(replaced).isEqualTo(Command.debugOverlay(false));
        }
    }

    @Test
    void diagnosticsUseAPresentationCommandAndDoNotTakeTheWidgetsKeys() {
        var scene = new DebugOverlayDemo.Scene();
        assertThat(scene.init(new InitContext(CONTEXT.viewport(), null)).command())
                .isEqualTo(Command.debugOverlay(true));
        var driver = scene();
        press(driver, "space");
        assertThat(driver.model().left().checked()).isTrue();
        press(driver, "tab");
        assertThat(driver.model().right().focused()).isTrue();
        press(driver, "space");
        assertThat(driver.model().right().checked()).isTrue();
        assertThat(driver.model().changes()).isEqualTo(2);
        var toggle = driver.send(new DebugOverlayDemo.Msg.ToggleDebug());
        assertThat(toggle.command()).isEqualTo(Command.debugOverlay(false));
        assertThat(toggle.model().left().checked()).isTrue();
        assertThat(driver.send(new DebugOverlayDemo.Msg.Quit()).command()).isEqualTo(Command.quit());
    }

    @Test
    void theCapturedFocusPathFollowsTheSameTreeAsRouting() {
        var driver = scene();
        var first = DebugOverlay.capture(
                ElementTree.layout(driver.view(CONTEXT), CONTEXT.viewport().bounds()),
                CONTEXT.viewport().bounds(),
                List.of(),
                0);
        press(driver, "tab");
        var second = DebugOverlay.capture(
                ElementTree.layout(driver.view(CONTEXT), CONTEXT.viewport().bounds()),
                CONTEXT.viewport().bounds(),
                List.of(),
                0);
        assertThat(first.focusPath()).startsWith("root/2");
        assertThat(second.focusPath()).startsWith("root/3");
        assertThat(second.bounds()).contains(new Rect(0, 0, 24, 80));
    }

    @Test
    void tinyScenesStillRenderWithoutDiagnosticOrNativeSideEffects() {
        var driver = scene();
        for (int[] size : new int[][] {{0, 0}, {1, 1}, {4, 8}, {12, 32}}) {
            var context = new ViewContext(new Viewport(size[0], size[1]));
            var screen = ElementScreen.render(driver.view(context), size[0], size[1]);
            assertThat(screen.canvas().rows()).isEqualTo(size[0]);
        }
    }
}

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

import examples.parity.RichOutputDemo;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.testing.Pilot;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ParityRichOutputTest {
    private static Pilot<RichOutputDemo.Model, RichOutputDemo.Msg> pilot() {
        return Pilot.of(
                new RichOutputDemo.Scene(),
                event -> {
                    if (event instanceof InputEvent.KeyInput key)
                        return switch (key.key().name()) {
                            case "space" -> new RichOutputDemo.Msg.Animate();
                            case "m" -> new RichOutputDemo.Msg.ToggleMotion();
                            case "q" -> new RichOutputDemo.Msg.Quit();
                            default -> throw new AssertionError("unexpected test key");
                        };
                    throw new AssertionError("unexpected test input");
                },
                new Viewport(24, 80));
    }

    @Test
    void normalMotionUsesManualTimeAndStopsWhenSettled() {
        var pilot = pilot();
        var scene = new RichOutputDemo.Scene();
        assertThat(pilot.snapshot().text())
                .contains("motion=normal value=0 target=0 state=settled moves=0", "image fallback=half-block");
        assertThat(scene.subscriptions(pilot.model())).isInstanceOf(Subscription.None.class);
        pilot.press("space");
        assertThat(scene.subscriptions(pilot.model())).isInstanceOf(Subscription.Interval.class);
        pilot.advance(Duration.ofMillis(300)).dispatch(new RichOutputDemo.Msg.Tick(pilot.nowNanos()));
        assertThat(pilot.model().value()).isEqualTo(0.5);
        pilot.advance(Duration.ofMillis(300)).dispatch(new RichOutputDemo.Msg.Tick(pilot.nowNanos()));
        assertThat(pilot.model().value()).isEqualTo(1);
        assertThat(scene.subscriptions(pilot.model())).isInstanceOf(Subscription.None.class);
        var settled = pilot.model();
        pilot.dispatch(new RichOutputDemo.Msg.Tick(Long.MAX_VALUE));
        assertThat(pilot.model()).isEqualTo(settled);
    }

    @Test
    void reducedMotionSettlesAtOnceAndNeverSubscribes() {
        var pilot = pilot().press("space");
        pilot.advance(Duration.ofMillis(150))
                .dispatch(new RichOutputDemo.Msg.Tick(pilot.nowNanos()))
                .press("m");
        assertThat(pilot.snapshot().text()).contains("motion=reduced value=100 target=100 state=settled", "frame=a");
        assertThat(new RichOutputDemo.Scene().subscriptions(pilot.model())).isInstanceOf(Subscription.None.class);
        pilot.press("space");
        assertThat(pilot.model().value()).isZero();
        assertThat(pilot.snapshot().text()).contains("motion=reduced value=0 target=0 state=settled moves=2");
        pilot.press("m");
        assertThat(pilot.model().value()).isZero();
        assertThat(new RichOutputDemo.Scene().subscriptions(pilot.model())).isInstanceOf(Subscription.None.class);
    }

    @Test
    void retargetingAnActiveTransitionDoesNotJumpAndTheSceneCanQuit() {
        var pilot = pilot().press("space");
        pilot.advance(Duration.ofMillis(300)).dispatch(new RichOutputDemo.Msg.Tick(pilot.nowNanos()));
        double displayed = pilot.model().value();
        pilot.press("space");
        assertThat(pilot.model().value()).isEqualTo(displayed);
        assertThat(pilot.model().transition().to()).isZero();
        pilot.press("q");
        assertThat(pilot.quitStatus()).hasValue(0);
    }

    @Test
    void tinyAndNarrowViewportsAreClipped() {
        var pilot = pilot();
        for (int[] size : new int[][] {{0, 0}, {1, 1}, {4, 8}, {12, 32}}) {
            var context = new io.github.dhoard.braxton.mvu.ViewContext(new Viewport(size[0], size[1]));
            var screen = io.github.dhoard.braxton.testing.ElementScreen.render(
                    new RichOutputDemo.Scene().view(pilot.model(), context), size[0], size[1]);
            assertThat(screen.canvas().rows()).isEqualTo(size[0]);
            assertThat(screen.canvas().cols()).isEqualTo(size[1]);
        }
    }
}

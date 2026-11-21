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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The toast: what it paints, its one scoped interval, its expiry, its presses and its themes.
 */
class ToastTest {

    private static final Duration TWO_SECONDS = Duration.ofSeconds(2);

    private final Toast toast = new Toast(Toast.Config.of(TWO_SECONDS));

    @Test
    void aHiddenToastPaintsNothingAndAShownOnePaintsItsSeverityAndMessage() {
        Toast toastWidget = new Toast();
        Toast.Model hidden = toastWidget.init(new InitContext(null, null)).model();

        assertThat(hidden.showing()).isFalse();
        assertThat(ElementScreen.render(Elements.toast(toastWidget, hidden), 1, 20)
                        .lines())
                .containsExactly("");
        assertThat(toastWidget
                        .measure(new io.github.dhoard.braxton.layout.Size(3, 20), hidden)
                        .rows())
                .isZero();

        Toast.Model shown = apply(hidden, Toast.Msg.Show.of(Severity.SUCCESS, "Saved the file"));
        assertThat(shown.showing()).isTrue();
        assertThat(shown.severity()).isEqualTo(Severity.SUCCESS);
        assertThat(shown.remaining())
                .as("a shown toast holds the duration it was configured with")
                .isEqualTo(TWO_SECONDS);
        assertThat(ElementScreen.render(Elements.toast(toastWidget, shown), 1, 20)
                        .lines())
                .containsExactly("\u2714 Saved the file");
        // Every severity paints its own one-column marker and then the message.
        for (Severity severity : Severity.values()) {
            Toast.Model marked = toastWidget
                    .update(shown, Toast.Msg.Show.of(severity, "note"))
                    .model();
            assertThat(ElementScreen.render(Elements.toast(toastWidget, marked), 1, 12)
                            .line(0))
                    .as("the %s marker", severity)
                    .isEqualTo(severity.marker() + " note");
        }
        // An empty message is only the marker, and a narrower row clips rather than wraps.
        Toast.Model bare =
                toastWidget.update(shown, Toast.Msg.Show.of(Severity.ERROR, "")).model();
        assertThat(ElementScreen.render(Elements.toast(toastWidget, bare), 1, 6).line(0))
                .isEqualTo("\u2716");
        assertThat(ElementScreen.render(Elements.toast(toastWidget, shown), 1, 6)
                        .line(0))
                .isEqualTo("\u2714 Save");
        assertThat(ElementScreen.render(Elements.toast(toastWidget, shown), 1, 1)
                        .line(0))
                .isEqualTo("\u2714");
        assertThat(ElementScreen.render(Elements.toast(toastWidget, shown), 1, 0)
                        .lines())
                .containsExactly("");
        assertThat(ElementScreen.render(Elements.toast(toastWidget, shown), 0, 20)
                        .lines())
                .isEmpty();
    }

    @Test
    void oneScopedIntervalRunsWhileItShowsAndStopsWhenItExpires() {
        Toast.Model hidden = toast.init(new InitContext(null, null)).model();
        assertThat(toast.subscriptions(hidden)).isInstanceOf(Subscription.None.class);

        Update<Toast.Model, Toast.Msg, Toast.Output> shown =
                toast.update(hidden, Toast.Msg.Show.of(Severity.INFO, "Working"));
        Toast.Model running = shown.model();
        assertThat(shown.outputs()).isEmpty();
        assertThat(running.armed())
                .as("a shown toast asks for the reading that names its deadline")
                .isTrue();
        assertThat(shown.command()).isInstanceOf(Command.MonotonicTime.class);
        Subscription.Interval<Toast.Msg> interval = (Subscription.Interval<Toast.Msg>) toast.subscriptions(running);
        assertThat(interval.period()).isEqualTo(Toast.DEFAULT_TICK_PERIOD);

        // The first reading names the deadline; the toast lasts its configured duration from there.
        long epoch = running.epoch();
        Toast.Model armed = apply(running, new Toast.Msg.Reading(epoch, 1_000_000_000L));
        assertThat(armed.armed()).isFalse();
        assertThat(armed.remaining()).isEqualTo(TWO_SECONDS);
        Toast.Model half = apply(armed, new Toast.Msg.Reading(epoch, 2_000_000_000L));
        assertThat(half.remaining()).isEqualTo(Duration.ofSeconds(1));
        // A late or reordered reading can only shorten the time that is left.
        assertThat(apply(half, new Toast.Msg.Reading(epoch, 1_500_000_000L)).remaining())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(apply(half, new Toast.Msg.Reading(epoch, 2_500_000_000L)).remaining())
                .isEqualTo(Duration.ofMillis(500));

        Update<Toast.Model, Toast.Msg, Toast.Output> expired =
                toast.update(half, new Toast.Msg.Reading(epoch, 3_000_000_000L));
        assertThat(expired.model().showing()).isFalse();
        assertThat(expired.model().remaining()).isZero();
        assertThat(expired.outputs()).containsExactly(new Toast.Output.Expired());
        assertThat(expired.command()).as("an expired toast asks for nothing").isInstanceOf(Command.None.class);
        assertThat(toast.subscriptions(expired.model()))
                .as("the interval stops when the toast expires")
                .isInstanceOf(Subscription.None.class);

        // A duplicate or queued reading of the run that ended reports nothing a second time.
        Update<Toast.Model, Toast.Msg, Toast.Output> again =
                toast.update(expired.model(), new Toast.Msg.Reading(epoch, 3_100_000_000L));
        assertThat(again.outputs()).isEmpty();
        assertThat(again.model()).isSameAs(expired.model());
        assertThat(toast.update(armed, new Toast.Msg.Reading(epoch + 1L, 4_000_000_000L))
                        .model())
                .as("a reading of another run can neither name a deadline nor end this one")
                .isSameAs(armed);
    }

    @Test
    void dismissingStopsTheRunAndALateReadingCannotReviveIt() {
        Toast.Model running = apply(toast.init(new InitContext(null, null)).model(), Toast.Msg.Show.of("Done"));
        long epoch = running.epoch();

        Update<Toast.Model, Toast.Msg, Toast.Output> dismissed = toast.update(running, new Toast.Msg.Dismiss());
        assertThat(dismissed.model().showing()).isFalse();
        assertThat(dismissed.model().epoch()).isNotEqualTo(epoch);
        assertThat(dismissed.outputs())
                .as("the application asked for it, so nothing is reported")
                .isEmpty();
        assertThat(toast.update(dismissed.model(), new Toast.Msg.Dismiss()).model())
                .as("dismissing a hidden toast changes nothing")
                .isSameAs(dismissed.model());
        assertThat(toast.update(dismissed.model(), new Toast.Msg.Reading(epoch, 9_000_000_000L))
                        .model())
                .as("a reading mapped before the dismiss cannot bring the toast back")
                .isSameAs(dismissed.model());

        // Showing again replaces what is up and starts its time over.
        Toast.Model first = apply(
                apply(running, new Toast.Msg.Reading(epoch, 1_000_000_000L)),
                new Toast.Msg.Reading(epoch, 2_000_000_000L));
        Toast.Model second = apply(first, Toast.Msg.Show.of(Severity.WARNING, "Careful"));
        assertThat(second.message().plainText()).isEqualTo("Careful");
        assertThat(second.severity()).isEqualTo(Severity.WARNING);
        assertThat(second.remaining()).isEqualTo(TWO_SECONDS);
        assertThat(second.epoch()).isEqualTo(epoch + 1L);
        assertThat(toast.update(first, Toast.Msg.Show.of(Severity.WARNING, "Careful"))
                        .command())
                .isInstanceOf(Command.MonotonicTime.class);
    }

    @Test
    void aZeroLengthToastExpiresAtOnceAndReportsItsOneExpiry() {
        Toast instant = new Toast(Toast.Config.of(Duration.ZERO));
        Toast.Model hidden = instant.init(new InitContext(null, null)).model();

        Update<Toast.Model, Toast.Msg, Toast.Output> shown = instant.update(hidden, Toast.Msg.Show.of("Gone"));
        assertThat(shown.model().showing()).isFalse();
        assertThat(shown.outputs()).containsExactly(new Toast.Output.Expired());
        assertThat(shown.command()).isInstanceOf(Command.None.class);
        assertThat(instant.subscriptions(shown.model())).isInstanceOf(Subscription.None.class);
    }

    @Test
    void aPressDismissesItAndItsRowIsPaintedInTheThemesStyles() {
        Toast.Model running =
                apply(toast.init(new InitContext(null, null)).model(), Toast.Msg.Show.of(Severity.ERROR, "Failed"));
        Element<Toast.Msg> root = Elements.toast(toast, running);

        assertThat(press(root, 1, 20, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, 4)))
                .containsExactly(new Toast.Msg.Dismiss());
        assertThat(press(root, 1, 20, PointerEvent.press(PointerButton.RIGHT, Modifiers.NONE, 0, 4)))
                .isEmpty();
        assertThat(press(root, 1, 20, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 0, 4)))
                .isEmpty();
        assertThat(press(
                        Elements.toast(toast, Toast.Model.hidden()),
                        1,
                        20,
                        PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, 0, 0)))
                .isEmpty();

        ElementScreen dark = ElementScreen.render(root, 1, 20, Themes.DARK);
        ElementScreen light = ElementScreen.render(root, 1, 20, Themes.LIGHT);
        assertThat(dark.styles(0).get(0))
                .as("the marker is the severity's role")
                .isEqualTo(Themes.DARK.style(ThemeToken.ERROR));
        assertThat(dark.styles(0).get(2))
                .as("the message is the theme's text style")
                .isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(light.styles(0).get(0))
                .as("a live theme switch repaints the row")
                .isEqualTo(Themes.LIGHT.style(ThemeToken.ERROR));
        assertThat(toast.view(running, new ViewContext(new Viewport(1, 20)))).isNotNull();
    }

    @Test
    void configsAndModelsAreValidatedAndNormalized() {
        assertThatThrownBy(() -> Toast.Config.of(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duration");
        assertThatThrownBy(() -> new Toast.Config(TWO_SECONDS, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tick period");
        assertThatThrownBy(() -> new Toast.Config(TWO_SECONDS, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Toast.Config(Duration.ofSeconds(Long.MAX_VALUE), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanoseconds");
        assertThatThrownBy(() -> new Toast.Config(TWO_SECONDS, Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanoseconds");
        assertThatThrownBy(() -> new Toast.Config(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Toast(null)).isInstanceOf(NullPointerException.class);
        assertThat(Toast.Config.defaults().duration()).isEqualTo(Toast.DEFAULT_DURATION);
        assertThat(Toast.Config.defaults().tickPeriod()).isEqualTo(Toast.DEFAULT_TICK_PERIOD);
        assertThat(Toast.Config.of(TWO_SECONDS)
                        .withTickPeriod(Duration.ofMillis(50))
                        .tickPeriod())
                .isEqualTo(Duration.ofMillis(50));
        assertThat(Toast.Config.of(TWO_SECONDS)
                        .withDuration(Duration.ofSeconds(1))
                        .duration())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(Toast.Config.of(TWO_SECONDS).durationNanos()).isEqualTo(2_000_000_000L);

        // A model and a message substitute their defaults rather than carrying a null.
        Toast.Model model = new Toast.Model(null, null, true, 1L, -5L, Countdown.UNSET_DEADLINE, 0L);
        assertThat(model.severity()).isEqualTo(Severity.INFO);
        assertThat(model.message().isEmpty()).isTrue();
        assertThat(model.remainingNanos()).isZero();
        assertThat(model.armed()).isTrue();
        assertThat(Toast.Model.hidden().armed()).isFalse();
        assertThat(new Toast.Msg.Show(null, null).severity()).isEqualTo(Severity.INFO);
        assertThat(new Toast.Msg.Show(null, null).message().isEmpty()).isTrue();
        assertThat(Toast.Msg.Show.of(null).message().isEmpty()).isTrue();
        assertThat(Toast.Msg.Show.of(null, "text").severity()).isEqualTo(Severity.INFO);
        assertThat(Toast.Msg.Show.of(Severity.WARNING, "text").message().plainText())
                .isEqualTo("text");

        assertThatThrownBy(() -> toast.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> toast.update(null, new Toast.Msg.Dismiss())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> toast.update(Toast.Model.hidden(), null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> toast.subscriptions(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> toast.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> toast.element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> toast.paint(null, new Rect(0, 0, 1, 1), Toast.Model.hidden(), null))
                .isInstanceOf(NullPointerException.class);
    }

    private Toast.Model apply(Toast.Model model, Toast.Msg message) {
        return toast.update(model, message).model();
    }

    private static List<Toast.Msg> press(Element<Toast.Msg> root, int rows, int cols, PointerEvent report) {
        List<Toast.Msg> recorded = new ArrayList<>();
        ElementTree.Placement<Toast.Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, report, recorded::add);
        return recorded;
    }
}

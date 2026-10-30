/*
 * Copyright (c) 2026-present Doug Hoard
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

package io.github.dhoard.braxton.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.Views;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

/** Application entry values: transitions, exit results, init context, runtime options. */
class AppValueTest {

    @Test
    void aTransitionRequiresACommandAndOffersStayAndNext() {
        assertThatThrownBy(() -> new Update<>("m", null, java.util.List.of()))
                .isInstanceOf(NullPointerException.class);
        Update<String, String, Never> stay = Update.next("m");
        assertThat(stay.command()).isInstanceOf(Command.None.class);
        assertThat(Update.next("m", Command.quit(2)).command()).isInstanceOf(Command.Quit.class);
        assertThat(Update.<String, String, Never>next("m").command())
                .isInstanceOf(Command.None.class);
    }

    @Test
    void exitResultsDistinguishKindsAndKeepCleanupFailures() {
        assertThat(ExitResult.quit(0).ok()).isTrue();
        assertThat(ExitResult.quit(3).ok()).isFalse();
        assertThat(ExitResult.inputClosed().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(ExitResult.inputClosed().ok()).isTrue();
        Throwable cause = new IllegalStateException("primary");
        ExitResult failure = ExitResult.failure(cause, List.of(new RuntimeException("cleanup")));
        assertThat(failure.ok()).isFalse();
        assertThat(failure.failure()).isSameAs(cause);
        assertThat(failure.cleanupFailures()).hasSize(1);
        assertThat(ExitResult.failure(cause, null).cleanupFailures()).isEmpty();
        assertThat(failure.toString()).contains("FAILURE").contains("IllegalStateException")
                .doesNotContain("\u001B");
    }

    @Test
    void anInitContextContainsOnlyImmutableFacts() {
        InitContext context = new InitContext(null, null);
        assertThat(context.viewport()).isEqualTo(Viewport.EMPTY);
        assertThat(context.capabilities()).isEqualTo(TerminalCapabilities.PLAIN);
        assertThat(new InitContext(Viewport.of(new Size(2, 3)), null).viewport()).isEqualTo(new Viewport(2,3));
    }

    @Test
    void runtimeOptionsValidateTheirLimitsAndDefaults() {
        RuntimeOptions defaults = RuntimeOptions.defaults();
        assertThat(defaults.mailboxCapacity()).isEqualTo(RuntimeOptions.DEFAULT_MAILBOX_CAPACITY);
        assertThat(defaults.maxConcurrentEffects()).isEqualTo(RuntimeOptions.DEFAULT_MAX_EFFECTS);
        assertThatThrownBy(
                        () ->
                                new RuntimeOptions(
                                        null,
                                        0,
                                        1,
                                        1,
                                        RuntimeOptions.DEFAULT_MAX_COMPONENTS,
                                        RuntimeOptions.DEFAULT_MAX_COMPONENT_DEPTH,
                                        RuntimeOptions.DEFAULT_MAX_LOCAL_DISPATCHES_PER_TURN,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
        assertThatThrownBy(
                        () ->
                                new RuntimeOptions(
                                        null,
                                        1,
                                        -1,
                                        1,
                                        RuntimeOptions.DEFAULT_MAX_COMPONENTS,
                                        RuntimeOptions.DEFAULT_MAX_COMPONENT_DEPTH,
                                        RuntimeOptions.DEFAULT_MAX_LOCAL_DISPATCHES_PER_TURN,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RuntimeOptions(
                                        Duration.ofSeconds(-1),
                                        1,
                                        0,
                                        0,
                                        RuntimeOptions.DEFAULT_MAX_COMPONENTS,
                                        RuntimeOptions.DEFAULT_MAX_COMPONENT_DEPTH,
                                        RuntimeOptions.DEFAULT_MAX_LOCAL_DISPATCHES_PER_TURN,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RuntimeOptions(
                                        Duration.ofSeconds(Long.MAX_VALUE),
                                        1,
                                        0,
                                        0,
                                        RuntimeOptions.DEFAULT_MAX_COMPONENTS,
                                        RuntimeOptions.DEFAULT_MAX_COMPONENT_DEPTH,
                                        RuntimeOptions.DEFAULT_MAX_LOCAL_DISPATCHES_PER_TURN,
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanoseconds");
        RuntimeOptions limited = defaults.withLimits(8, 2, 3);
        assertThat(limited.mailboxCapacity()).isEqualTo(8);
        assertThat(limited.maxSubscriptions()).isEqualTo(3);
        assertThat(limited.input()).isSameAs(defaults.input());
    }

    private static final class DefaultSubscriptions implements Component<String, String, Never> {
        @Override
        public Update<String, String, Never> init(InitContext context) {
            return Update.next("");
        }

        @Override
        public Update<String, String, Never> update(String model, String message) {
            return Update.next(model);
        }

        @Override
        public io.github.dhoard.braxton.view.View<String> view(String model, ViewContext context) {
            return Views.text("x");
        }
    }

    @Test
    void aProgramDefaultsToNoSubscriptions() {
        Subscription<String> subscriptions = new DefaultSubscriptions().subscriptions("");
        assertThat(subscriptions).isInstanceOf(Subscription.None.class);
    }
}

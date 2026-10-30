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

package io.github.dhoard.braxton.internal.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.app.*;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Regressions for frame behavior recovered from the installed Braxton artifact. */
@Timeout(10)
class RuntimeFrameRecoveryTest {
    @Test
    void eachFrameEndsSynchronizationBeforeItsSingleFlushAndRestorationEndsItAgain() {
        try (var recording = new RecordingTerminal()) {
            var result = recording.run();
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(recording.events).containsExactly("begin", "end", "frame", "end", "restore");
            assertThat(recording.fixture.writes().stream().filter(s -> s.contains("ready")))
                    .hasSize(1);
        }
    }

    @Test
    void aFailedBeginStillEndsSynchronizationAndRetainsSecondaryFailure() {
        try (var recording = new RecordingTerminal()) {
            recording.beginFailure = new IllegalStateException("begin failed");
            recording.endFailure = new IllegalArgumentException("end failed");
            recording.failEndAt = 1;
            var result = recording.run();
            assertThat(result.failure()).isSameAs(recording.beginFailure);
            assertThat(result.failure().getSuppressed()).containsExactly(recording.endFailure);
            assertThat(recording.events).containsExactly("begin", "end", "end", "restore");
            assertThat(recording.fixture.isOpen()).isFalse();
        }
    }

    @Test
    void aFailedEndStillFlushesTheFrameAndRestoresModes() {
        try (var recording = new RecordingTerminal()) {
            recording.endFailure = new IllegalStateException("end failed");
            recording.failEndAt = 1;
            var result = recording.run();
            assertThat(result.failure()).isSameAs(recording.endFailure);
            assertThat(recording.events).containsExactly("begin", "end", "frame", "end", "restore");
            assertThat(recording.fixture.outputText()).contains("ready", "\u001b[?25h", "\u001b[?1049l");
        }
    }

    @Test
    void aFailedRestorationEndDoesNotPreventCursorAndModeRestoration() {
        try (var recording = new RecordingTerminal()) {
            recording.endFailure = new IllegalStateException("restore end failed");
            recording.failEndAt = 2;
            var result = recording.run();
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.cleanupFailures()).containsExactly(recording.endFailure);
            assertThat(recording.fixture.outputText()).contains("\u001b[?25h", "\u001b[?1049l");
            assertThat(recording.fixture.isOpen()).isFalse();
        }
    }

    private static final class RecordingTerminal implements AutoCloseable {
        final JLineTestTerminal fixture = new JLineTestTerminal();
        final List<String> events = new ArrayList<>();
        RuntimeException beginFailure;
        RuntimeException endFailure;
        int failEndAt;
        int ends;

        ExitResult run() {
            fixture.onOutput(text -> {
                if (text.contains("ready")) events.add("frame");
                if (text.contains("\u001b[?25h")) events.add("restore");
            });
            Terminal terminal = (Terminal) Proxy.newProxyInstance(
                    Terminal.class.getClassLoader(), new Class<?>[] {Terminal.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("beginSynchronizedUpdate")) {
                            events.add("begin");
                            if (beginFailure != null) throw beginFailure;
                            return null;
                        }
                        if (method.getName().equals("endSynchronizedUpdate")) {
                            events.add("end");
                            if (++ends == failEndAt) throw endFailure;
                            return null;
                        }
                        try {
                            return method.invoke(fixture.terminal(), args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            Component<Integer, String, Never> program =
                    new Component<>() {
                        public Update<Integer, String, Never> init(InitContext context) {
                            return Update.next(0, Command.quit());
                        }

                        public Update<Integer, String, Never> update(
                                Integer model, String message) {
                            return Update.next(model);
                        }

                        public View<String> view(Integer model, ViewContext context) {
                            return Views.text("ready");
                        }
                    };
            return new RuntimeLoop<>(program, RuntimeOptions.defaults(), terminal,
                    RuntimeServices.system()).run();
        }

        public void close() {
            fixture.close();
        }
    }
}

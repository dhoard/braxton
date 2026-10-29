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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.dhoard.braxton.app.*;
import io.github.dhoard.braxton.effect.*;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.view.*;

/** Frame boundaries and failure state, using real JLine output over owned streams. */
@Timeout(10)
class FramePresentationTest {
    private static final String BEGIN = "\u001b[?2026h";
    private static final String END = "\u001b[?2026l";

    private static Program<Integer, String> program(Runnable prepare, boolean update) {
        return new Program<>() {
            public Transition<Integer, String> init(InitContext context) {
                return Transition.next(0, update ? Command.none() : Command.quit());
            }
            public Transition<Integer, String> update(Integer model, String message) {
                return Transition.next(1, Command.quit());
            }
            public View<String> view(Integer model, Viewport viewport) {
                prepare.run();
                return Views.text("ready");
            }
            public Subscription<String> subscriptions(Integer model) {
                return update ? Subscription.source(SubscriptionId.of("update"), "update", emitter -> {
                    emitter.accept("update");
                    return () -> {};
                }, value -> (String) value) : Subscription.none();
            }
        };
    }

    private static Object field(RuntimeLoop<?, ?> loop, String name) throws Exception {
        var field = RuntimeLoop.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(loop);
    }

    private static boolean ffmModuleHasNativeAccess() {
        try {
            return Class.forName("org.jline.terminal.ffm.FfmTerminalProvider")
                    .getModule().isNativeAccessEnabled();
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    private static void invoke(RuntimeLoop<?, ?> loop, String name, String argument) {
        try {
            var method = RuntimeLoop.class.getDeclaredMethod(name, String.class);
            method.setAccessible(true);
            method.invoke(loop, argument);
        } catch (InvocationTargetException failure) {
            throw (RuntimeException) failure.getCause();
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime())
                    .as("condition not reached before the deadline")
                    .isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private static Program<Integer, String> liveProgram(
            java.util.function.Function<Viewport, View<String>> view) {
        return new Program<>() {
            public Transition<Integer, String> init(InitContext context) {
                return Transition.next(0, Command.none());
            }
            public Transition<Integer, String> update(Integer model, String message) {
                return Transition.next(model);
            }
            public View<String> view(Integer model, Viewport viewport) {
                return view.apply(viewport);
            }
        };
    }

    @Test
    void presentationResamplesSizeChangedDuringInitialization() {
        try (var fixture = new JLineTestTerminal()) {
            var seen = new AtomicReference<Viewport>();
            Program<Integer, String> p = new Program<>() {
                public Transition<Integer, String> init(InitContext context) {
                    fixture.resize(new io.github.dhoard.braxton.layout.Size(4, 10));
                    return Transition.next(0, Command.quit());
                }
                public Transition<Integer, String> update(Integer model, String message) {
                    return Transition.next(model);
                }
                public View<String> view(Integer model, Viewport viewport) {
                    seen.set(viewport);
                    return Views.text("x".repeat(viewport.cols()));
                }
            };
            var loop = new RuntimeLoop<>(p, RuntimeOptions.defaults(), fixture.terminal(),
                    RuntimeServices.system());
            assertThat(loop.run().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(seen.get()).isEqualTo(new Viewport(4, 10));
            assertThat(loop.screen().rows()).isEqualTo(4);
            assertThat(loop.screen().cols()).isEqualTo(10);
        }
    }

    @Test
    void nonemptyFramesHaveOneOrderedBracketAndEmptyDiffsHaveNoOutput() {
        try (var fixture = new JLineTestTerminal()) {
            AtomicInteger views = new AtomicInteger();
            var result = JLineTestTerminal.run(program(views::incrementAndGet, true),
                    RuntimeOptions.defaults(), fixture);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(views.get()).isEqualTo(2);
            // ExternalTerminal may flush transport chunks independently of frame ownership.
            String output = fixture.outputText();
            int start = output.indexOf(BEGIN);
            int end = output.indexOf(END, start);
            assertThat(start).isGreaterThanOrEqualTo(0);
            assertThat(end).isGreaterThan(start);
            String frame = output.substring(start, end + END.length());
            assertThat(frame).startsWith(BEGIN).endsWith(END).contains("ready");
            assertThat(frame.indexOf("ready")).isBetween(BEGIN.length(), frame.indexOf(END));
            assertThat(frame.split(java.util.regex.Pattern.quote(BEGIN), -1)).hasSize(2);
            assertThat(frame.split(java.util.regex.Pattern.quote(END), -1)).hasSize(2);
            assertThat(output.split(java.util.regex.Pattern.quote(BEGIN), -1)).hasSize(2);
            assertThat(output).contains(END + "\u001b[?2004l");
            assertThat(fixture.writes().stream().filter(s -> s.contains("ready"))).hasSize(1);
        }
    }

    @Test
    void everyFailureStageAttemptsClosureAndFlushWithoutAcceptingTheScreen() throws Exception {
        for (String stage : List.of("begin", "payload", "end", "flush", "writer")) {
            try (var fixture = new JLineTestTerminal()) {
                RuntimeException failure = new IllegalStateException(stage + " failed");
                AtomicBoolean once = new AtomicBoolean();
                AtomicInteger ends = new AtomicInteger();
                AtomicInteger flushes = new AtomicInteger();
                var p = program(() -> {
                    fixture.beforeWrite(text -> {
                        if (text.equals(END)) ends.incrementAndGet();
                        boolean selected = stage.equals("begin") && text.equals(BEGIN)
                                || stage.equals("payload") && text.contains("ready")
                                || stage.equals("end") && text.equals(END);
                        if (selected && once.compareAndSet(false, true)) throw failure;
                        if (stage.equals("writer") && text.contains("ready")) fixture.failOutput();
                    });
                    fixture.beforeFlush(() -> {
                        flushes.incrementAndGet();
                        if (stage.equals("flush") && once.compareAndSet(false, true)) throw failure;
                    });
                }, false);
                var loop = new RuntimeLoop<>(p, RuntimeOptions.defaults(), fixture.terminal(),
                        RuntimeServices.system());
                var result = loop.run();
                assertThat(result.kind()).as(stage).isEqualTo(ExitResult.Kind.FAILURE);
                if (stage.equals("writer")) assertThat(result.failure()).hasMessage("terminal output failed");
                else assertThat(result.failure()).as(stage).isSameAs(failure);
                assertThat(ends.get()).as("frame and restoration closure: " + stage).isEqualTo(2);
                assertThat(flushes.get()).as(stage).isGreaterThanOrEqualTo(2);
                assertThat(field(loop, "currentScreen")).as(stage).isNull();
                assertThat(field(loop, "viewDirty")).as(stage).isEqualTo(true);
                assertThat(fixture.isOpen()).isFalse();
            }
        }
    }

    @Test
    void primaryPayloadFailureRetainsBothClosureAndFlushFailures() {
        try (var fixture = new JLineTestTerminal()) {
            var primary = new IllegalStateException("payload");
            var ending = new IllegalArgumentException("end");
            var flushing = new IllegalStateException("flush");
            AtomicBoolean endOnce = new AtomicBoolean();
            AtomicBoolean flushOnce = new AtomicBoolean();
            var p = program(() -> {
                fixture.beforeWrite(text -> {
                    if (text.contains("ready")) throw primary;
                    if (text.equals(END) && endOnce.compareAndSet(false, true)) throw ending;
                });
                fixture.beforeFlush(() -> {
                    if (flushOnce.compareAndSet(false, true)) throw flushing;
                });
            }, false);
            var result = JLineTestTerminal.run(p, RuntimeOptions.defaults(), fixture);
            assertThat(result.failure()).isSameAs(primary);
            assertThat(primary.getSuppressed()).containsExactly(ending);
            assertThat(ending.getSuppressed()).containsExactly(flushing);
            assertThat(fixture.outputText()).contains("\u001b[?25h", "\u001b[?1049l");
        }
    }

    @Test
    void constructorAcceptsNullOptionsAndTheTwoArgumentForm() {
        Program<Integer, String> p = program(() -> {}, false);
        assertThat(new RuntimeLoop<>(p, null)).isNotNull();
        assertThat(new RuntimeLoop<>(p, RuntimeOptions.defaults())).isNotNull();
    }

    @Test
    void aSecondRunIsRejectedBeforeAnyTerminalWork() {
        try (var fixture = new JLineTestTerminal()) {
            var loop = new RuntimeLoop<>(program(() -> {}, false), RuntimeOptions.defaults(),
                    fixture.terminal(), RuntimeServices.system());
            assertThat(loop.run().kind()).isEqualTo(ExitResult.Kind.QUIT);
            var second = loop.run();
            assertThat(second.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(second.failure()).hasMessage("runtime can only run once");
            assertThat(second.cleanupFailures()).isEmpty();
            assertThat(loop.model()).isEqualTo(0);
            assertThat(loop.view()).isNotNull();
        }
    }

    @Test
    void anUnsuppliedTerminalWithoutNativeAccessFailsAndReleasesTheConsoleClaim() {
        Assumptions.assumeFalse(ffmModuleHasNativeAccess(),
                "the FFM module has native access in this JVM");
        var first = new RuntimeLoop<>(program(() -> {}, false), RuntimeOptions.defaults()).run();
        assertThat(first.kind()).isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(first.failure()).hasMessageContaining("--enable-native-access");
        var second = new RuntimeLoop<>(program(() -> {}, false), RuntimeOptions.defaults()).run();
        assertThat(second.failure())
                .as("the ownership claim is released after the failed open")
                .hasMessageContaining("--enable-native-access");
    }

    @Test
    void anInputReaderRuntimeFailureIsReportedInsteadOfHanging() {
        try (var fixture = new JLineTestTerminal()) {
            fixture.failInput(new IllegalStateException("reader boom"));
            var result = JLineTestTerminal.run(
                    liveProgram(viewport -> Views.text("ready")), RuntimeOptions.defaults(), fixture);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.failure()).hasMessage("reader boom");
            assertThat(fixture.isOpen()).isFalse();
        }
    }

    @Test
    void anInputReaderIoFailureIsReportedInsteadOfHanging() {
        try (var fixture = new JLineTestTerminal()) {
            fixture.failInputWith(new java.io.IOException("reader io"));
            var result = JLineTestTerminal.run(
                    liveProgram(viewport -> Views.text("ready")), RuntimeOptions.defaults(), fixture);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.failure()).hasMessage("reader io");
            assertThat(fixture.isOpen()).isFalse();
        }
    }

    @Test
    void aSecondRuntimeCannotClaimTheConsole() throws Exception {
        var owned = RuntimeLoop.class.getDeclaredField("OWNED");
        owned.setAccessible(true);
        var claimed = (AtomicBoolean) owned.get(null);
        Assumptions.assumeFalse(claimed.get(), "another test claimed the console");
        claimed.set(true);
        try {
            var result = new RuntimeLoop<>(program(() -> {}, false), RuntimeOptions.defaults()).run();
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.failure())
                    .hasMessage("another Braxton application owns the terminal");
        } finally {
            claimed.set(false);
        }
    }

    @Test
    void anExternallyClosedMailboxEndsTheLoopCleanly() throws Exception {
        try (var fixture = new JLineTestTerminal()) {
            var loop = new RuntimeLoop<>(
                    liveProgram(viewport -> Views.text("ready")),
                    RuntimeOptions.defaults(), fixture.terminal(), RuntimeServices.system());
            var result = new AtomicReference<ExitResult>();
            Thread owner = Thread.ofVirtual().start(() -> result.set(loop.run()));
            try {
                await(() -> fixture.outputText().contains("ready"));
                ((Mailbox) field(loop, "mailbox")).close();
                owner.join(5000);
            } finally {
                fixture.endInput();
                owner.join(5000);
            }
            assertThat(owner.isAlive()).isFalse();
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
            assertThat(result.get().failure()).isNull();
        }
    }

    @Test
    void aDeadInputReaderWithoutABufferedEventFailsInsteadOfHanging() throws Exception {
        try (var fixture = new JLineTestTerminal()) {
            var loop = new RuntimeLoop<>(
                    liveProgram(viewport -> Views.text("ready")),
                    RuntimeOptions.defaults(), fixture.terminal(), RuntimeServices.system());
            var result = new AtomicReference<ExitResult>();
            Thread owner = Thread.ofVirtual().start(() -> result.set(loop.run()));
            try {
                await(() -> fixture.outputText().contains("ready"));
                Thread dead = Thread.ofVirtual().start(() -> {});
                dead.join(5000);
                setField(loop, "inputThread", dead);
                owner.join(5000);
            } finally {
                fixture.endInput();
                owner.join(5000);
            }
            assertThat(owner.isAlive()).isFalse();
            assertThat(result.get().kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.get().failure())
                    .hasMessage("input reader stopped unexpectedly");
            assertThat(result.get().cleanupFailures()).isEmpty();
        }
    }

    @Test
    void mouseOptionsEnterAndLeaveMouseMode() {
        try (var fixture = new JLineTestTerminal()) {
            var options = RuntimeOptions.defaults()
                    .withTerminal(TerminalOptions.DEFAULT.withMouse(true));
            var result = JLineTestTerminal.run(program(() -> {}, false), options, fixture);
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.QUIT);
            String output = fixture.outputText();
            assertThat(output).contains(AnsiEncoder.ENABLE_MOUSE, AnsiEncoder.DISABLE_MOUSE);
            assertThat(output.indexOf(AnsiEncoder.ENABLE_MOUSE))
                    .isLessThan(output.indexOf(AnsiEncoder.DISABLE_MOUSE));
        }
    }

    @Test
    void aWindowChangeSignalResizesAndRepaintsTheNewViewport() throws Exception {
        try (var fixture = new JLineTestTerminal()) {
            var loop = new RuntimeLoop<>(
                    liveProgram(viewport -> Views.text(viewport.rows() + "x" + viewport.cols())),
                    RuntimeOptions.defaults(), fixture.terminal(), RuntimeServices.system());
            var result = new AtomicReference<ExitResult>();
            Thread owner = Thread.ofVirtual().start(() -> result.set(loop.run()));
            try {
                await(() -> fixture.outputText().contains("24x80"));
                fixture.resize(new Size(5, 10));
                fixture.terminal().raise(Terminal.Signal.WINCH);
                await(() -> fixture.outputText().contains("5x10"));
            } finally {
                fixture.endInput();
                owner.join(5000);
            }
            assertThat(owner.isAlive()).isFalse();
            assertThat(result.get().failure()).isNull();
        }
    }

    @Test
    void anUnavailableResizeMeasurementFallsBackToTheCommittedViewport() throws Exception {
        try (var fixture = new JLineTestTerminal()) {
            var diagnostics = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var services = RuntimeServices.system().withDiagnostics(diagnostics::add);
            var loop = new RuntimeLoop<>(
                    liveProgram(viewport -> Views.text(viewport.rows() + "x" + viewport.cols())),
                    RuntimeOptions.defaults(), fixture.terminal(), services);
            var result = new AtomicReference<ExitResult>();
            Thread owner = Thread.ofVirtual().start(() -> result.set(loop.run()));
            try {
                await(() -> fixture.outputText().contains("24x80"));
                fixture.failSize(new IllegalStateException("temporary size failure"));
                fixture.terminal().raise(Terminal.Signal.WINCH);
                await(() -> diagnostics.contains("terminal size temporarily unavailable"));
                assertThat(fixture.outputText()).doesNotContain("0x0");
            } finally {
                fixture.endInput();
                owner.join(5000);
            }
            assertThat(owner.isAlive()).isFalse();
            assertThat(result.get().failure()).isNull();
        }
    }

    @Test
    void restorationEndAndBrokenOutputAreBothReported() {
        try (var fixture = new JLineTestTerminal()) {
            var endFailure = new IllegalStateException("restore end failed");
            AtomicInteger ends = new AtomicInteger();
            fixture.beforeWrite(text -> {
                if (text.contains(AnsiEncoder.SHOW_CURSOR)) fixture.failOutput();
            });
            Terminal terminal = (Terminal) java.lang.reflect.Proxy.newProxyInstance(
                    Terminal.class.getClassLoader(), new Class<?>[] {Terminal.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("endSynchronizedUpdate")) {
                            if (ends.incrementAndGet() == 2) throw endFailure;
                            return null;
                        }
                        try {
                            return method.invoke(fixture.terminal(), arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            var loop = new RuntimeLoop<>(program(() -> {}, false), RuntimeOptions.defaults(),
                    terminal, RuntimeServices.system());
            var result = loop.run();
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(result.failure()).hasMessage("terminal output failed");
            assertThat(result.failure().getSuppressed()).containsExactly(endFailure);
            assertThat(ends.get()).isEqualTo(2);
        }
    }

    @Test
    void stoppingRuntimeRejectsControlAndFrameOutput() {
        try (var fixture = new JLineTestTerminal()) {
            var loop = new RuntimeLoop<>(program(() -> {}, false), RuntimeOptions.defaults(),
                    fixture.terminal(), RuntimeServices.system());
            assertThat(loop.run().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThatThrownBy(() -> invoke(loop, "writeOutput", "late"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime is stopping");
            assertThatThrownBy(() -> invoke(loop, "writeFrame", "late"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("runtime is stopping");
            assertThat(fixture.outputText()).doesNotContain("late");
        }
    }

    @Test
    void uncheckedKeepsRuntimeAndErrorIdentityAndWrapsCheckedFailures() {
        var runtime = new IllegalStateException("runtime");
        assertThat(RuntimeLoop.unchecked(runtime)).isSameAs(runtime);
        assertThatThrownBy(() -> RuntimeLoop.unchecked(new AssertionError("error")))
                .isInstanceOf(AssertionError.class)
                .hasMessage("error");
        assertThat(RuntimeLoop.unchecked(new java.io.IOException("checked")))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(java.io.IOException.class);
    }

    @Test
    void shutdownWaitsForAnAdmittedFrameAndRejectsSubsequentFrames() throws Exception {
        try (var fixture = new JLineTestTerminal()) {
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            AtomicBoolean once = new AtomicBoolean();
            var p = program(() -> fixture.beforeFlush(() -> {
                if (!once.compareAndSet(false, true)) return;
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("frame not released");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }), false);
            var loop = new RuntimeLoop<>(p, RuntimeOptions.defaults(), fixture.terminal(), RuntimeServices.system());
            var result = new AtomicReference<ExitResult>();
            var shutdownFailure = new AtomicReference<Throwable>();
            Thread owner = Thread.ofVirtual().start(() -> result.set(loop.run()));
            Thread shutdown = null;
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                shutdown = Thread.ofVirtual().start(() -> {
                    try { loop.shutdownRestore(); }
                    catch (Throwable failure) { shutdownFailure.set(failure); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!(boolean) field(loop, "stopping") && System.nanoTime() < deadline) Thread.yield();
                assertThat(field(loop, "stopping")).isEqualTo(true);
                assertThat(shutdown.isAlive()).isTrue();
                assertThat(fixture.outputText()).doesNotContain("ready", "\u001b[?25h");
            } finally {
                release.countDown();
                if (shutdown != null) shutdown.join(5000);
                fixture.endInput();
                owner.join(5000);
            }
            assertThat(owner.isAlive()).isFalse();
            assertThat(shutdownFailure.get()).isNull();
            assertThat(result.get().failure()).isNull();
            String output = fixture.outputText();
            assertThat(output.indexOf("ready")).isLessThan(output.indexOf("\u001b[?25h"));
            assertThat(output.substring(output.indexOf("\u001b[?25h"))).doesNotContain(BEGIN);
            // Orderly restoration is once-only even when cleanup follows the shutdown task.
            assertThat(output.split(java.util.regex.Pattern.quote("\u001b[?25h"), -1)).hasSize(2);
        }
    }
}

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import examples.parity.InlineIoDemo;
import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.IoOptions;
import io.github.dhoard.braxton.app.MessageFilter;
import io.github.dhoard.braxton.app.ProgramHandle;
import io.github.dhoard.braxton.app.ProgramResult;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.runtime.RuntimeLoop;
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.io.IOException;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 23 acceptance: inline output, streaming and headless I/O.
 *
 * <p>Four things are asserted against observable behavior. A session can be given its own bytes -
 * a reader and a writer the caller owns and the runtime never closes - and no terminal device, native
 * library or device configuration is involved. A non-alternate-screen session paints a managed live
 * region that moves relatively, erases only what it owns, and never addresses a row absolutely. Durable
 * output appears above that region, in the same write as the repaint that follows it, and is a defined
 * no-op on the alternate screen and plain text when the renderer is off. A renderer-disabled session
 * runs the whole program and writes no escape sequence at all.</p>
 *
 * <p>Every case drives a real {@code RuntimeLoop} through {@link Application} or one of its construction
 * seams, so the evidence is the transcript a session wrote and the model it committed, not a helper's
 * return value.</p>
 */
@Timeout(120)
class ParityIoTest {

    /**
     * One synchronized-output bracket start; a presented frame opens exactly one.
     */
    private static final String BEGIN = "\u001b[?2026h";

    /**
     * One synchronized-output bracket end.
     */
    private static final String END = "\u001b[?2026l";

    /**
     * An absolute cursor address, which an inline frame must never write.
     */
    private static final Pattern ABSOLUTE_MOVE = Pattern.compile("\u001b\\[\\d+;\\d+H");

    /**
     * An erase of the whole display, which would destroy the user's own scrollback.
     */
    private static final String ERASE_DISPLAY = "\u001b[2J";

    private static final long WAIT_SECONDS = 10L;

    /**
     * The color the fixture's first row is drawn in, so a negotiated color profile is observable.
     */
    private static final Color ACCENT = new Color(0x21, 0xAF, 0x7A);

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * A writer the test can read while the session writes, counting flushes as frames.
     */
    private static final class Recorder extends Writer {

        private final StringBuilder text = new StringBuilder();
        private final List<String> writes = new ArrayList<>();
        private final AtomicInteger flushes = new AtomicInteger();

        @Override
        public synchronized void write(char[] buffer, int offset, int length) {
            text.append(buffer, offset, length);
        }

        @Override
        public synchronized void flush() {
            flushes.incrementAndGet();
            writes.add(text.toString());
        }

        @Override
        public void close() {}

        synchronized String text() {
            return text.toString();
        }

        /**
         * Every write the session flushed, as the transcript looked at that moment.
         */
        synchronized List<String> writes() {
            return List.copyOf(writes);
        }

        synchronized int flushCount() {
            return flushes.get();
        }
    }

    /**
     * A connected pipe the test holds open: the reader is the session's input and the writer keeps the
     * connection alive, so a session that has no input yet keeps waiting instead of seeing it close.
     */
    private static final class Input implements AutoCloseable {

        private final PipedReader reader;
        private final PipedWriter writer;

        Input() {
            try {
                reader = new PipedReader();
                writer = new PipedWriter(reader);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        Reader reader() {
            return reader;
        }

        @Override
        public void close() throws IOException {
            reader.close();
            writer.close();
        }
    }

    /**
     * A reader and a writer that count closes, so ownership can be asserted.
     */
    private static final class Owner {
        private final AtomicInteger closes = new AtomicInteger();
        private final StringWriter sink = new StringWriter();
        private final Reader reader = new StringReader("");

        Reader reader() {
            return new java.io.FilterReader(reader) {
                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        }

        Writer writer() {
            return new Writer() {
                @Override
                public void write(char[] buffer, int offset, int length) throws IOException {
                    sink.write(buffer, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    sink.flush();
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        }

        int closes() {
            return closes.get();
        }
    }

    /**
     * A scene whose rows are its model, so a frame's shape is the test's to choose.
     *
     * <p>{@code a} adds a row, {@code b} removes one, {@code c} clears them all and {@code q} quits;
     * {@code commands} decides what else a message asks for. Every message is also delivered through
     * the command function, which is how a test makes a key print, fail or do nothing at all.</p>
     *
     * @param commands the command a message returns, or null for none
     * @return the program
     */
    private static Component<List<String>, String, Never> rows(Function<String, Command<String>> commands) {
        KeyMap<String> keys = KeyMap.of(KeyMap.group(
                "Rows",
                KeyAction.<String>of("add", "a", "a", "add a row", "a"),
                KeyAction.<String>of("remove", "b", "b", "remove the last row", "b"),
                KeyAction.<String>of("clear", "c", "c", "clear every row", "c"),
                KeyAction.<String>of("print", "p", "p", "print a durable line", "p"),
                KeyAction.<String>of("quit", "q", "q", "quit", "q", "ctrl+c")));
        return new Component<>() {
            @Override
            public Update<List<String>, String, Never> init(InitContext context) {
                return Update.next(List.of());
            }

            @Override
            public Update<List<String>, String, Never> update(List<String> model, String message) {
                List<String> next = new ArrayList<>(model);
                switch (message) {
                    case "a" -> next.add("row " + (next.size() + 1));
                    case "b" -> {
                        if (!next.isEmpty()) {
                            next.remove(next.size() - 1);
                        }
                    }
                    case "c" -> next.clear();
                    default -> {
                        // Every other message changes no row: the command function below decides what
                        // it does, and a test that sends a message straight to the handle still gets a
                        // committed model to wait for.
                    }
                }
                Command<String> command = commands.apply(message);
                return command == null ? Update.next(List.copyOf(next)) : Update.next(List.copyOf(next), command);
            }

            @Override
            public Element<String> view(List<String> model, ViewContext context) {
                List<Element<String>> lines = new ArrayList<>();
                lines.add(Elements.text("scene rows=" + model.size(), Style.fg(ACCENT)));
                for (String row : model) {
                    lines.add(Elements.text(row));
                }
                return Elements.column(lines);
            }

            /**
             * The gestures this fixture answers, once no leaf has taken them.
             */
            @Override
            public java.util.List<Binding<String>> claims(java.util.List<String> model) {
                return keys.bindings();
            }
        };
    }

    /**
     * The quit binding every fixture scene shares.
     */
    private static Command<String> quitOn(String message) {
        return "q".equals(message) ? Command.quit() : null;
    }

    /**
     * Options for a session that paints the terminal's own screen instead of the alternate one.
     */
    private static RuntimeOptions inline() {
        return RuntimeOptions.defaults().withTerminal(TerminalOptions.DEFAULT.withAlternateScreen(false));
    }

    /**
     * Waits until the recorder's text satisfies {@code condition}.
     */
    private static String awaitText(Recorder recorder, java.util.function.Predicate<String> condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            String text = recorder.text();
            if (condition.test(text)) {
                return text;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(what + " did not happen; transcript: " + visible(recorder.text()));
    }

    /**
     * A transcript with escape sequences made visible, so a failure message is readable.
     */
    private static String visible(String text) {
        return text.replace("\u001b", "\\e").replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * The frames the recorder has flushed.
     */
    private static List<String> frames(Recorder recorder) {
        List<String> all = recorder.writes();
        return all.isEmpty() ? List.of() : List.copyOf(all);
    }

    // ── R-01: explicit I/O, environment and size ─────────────────────────────

    @Test
    void aStreamSessionRendersToTheSuppliedWriterAndReportsInputClosed() {
        Recorder output = new Recorder();
        IoOptions io =
                IoOptions.defaults().withStreams(new StringReader(""), output).withSize(6, 40);
        ExitResult result = Application.run(rows(message -> quitOn(message)), io);

        assertThat(result.kind()).as("an empty stream ends the session").isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(output.text())
                .as("the frame was written to the supplied writer")
                .contains("scene rows=0");
        assertThat(output.text())
                .as("a stream session never enters the alternate screen")
                .doesNotContain(AnsiEncoder.ALT_SCREEN_ON);
        assertThat(result.cleanupFailures()).isEmpty();
    }

    @Test
    void aSuppliedStreamIsNeverClosed() throws Exception {
        Owner owner = new Owner();
        IoOptions io = IoOptions.defaults().withStreams(owner.reader(), owner.writer());
        ExitResult result = Application.run(rows(message -> quitOn(message)), io);

        assertThat(result.kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(owner.closes())
                .as("the runtime flushes the caller's streams and closes neither")
                .isZero();
        owner.writer().write("still mine");
        owner.writer().flush();
        assertThat(owner.closes()).as("the caller can still use its own stream").isZero();
    }

    @Test
    void aRendererDisabledSessionWritesNoEscapeSequenceAndStillReturnsItsModel() throws Exception {
        Recorder output = new Recorder();
        try (Input input = new Input()) {
            IoOptions io =
                    IoOptions.defaults().withStreams(input.reader(), output).withRenderer(false);
            ProgramHandle<List<String>, String> handle = Application.start(rows(message -> null), io);

            assertThat(handle.trySend("a")).isNotEqualTo(ProgramHandle.Send.CLOSED);
            assertThat(handle.quit()).as("a graceful quit ends the run").isTrue();
            ProgramResult<List<String>> result = handle.await();

            assertThat(result.exit().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(result.model())
                    .as("the model the run reached is its result")
                    .containsExactly("row 1");
            assertThat(output.text())
                    .as("a renderer-disabled session writes no frame, mode or bracket")
                    .isEmpty();
            assertThat(output.writes())
                    .as("nothing was written at all")
                    .allSatisfy(write -> assertThat(write).isEmpty());
        }
    }

    @Test
    void aRendererDisabledSessionStillRunsFiltersEffectsAndDurableOutput() throws Exception {
        Recorder output = new Recorder();
        AtomicInteger filtered = new AtomicInteger();
        MessageFilter filter = message -> {
            filtered.incrementAndGet();
            return message;
        };
        try (Input input = new Input()) {
            IoOptions io =
                    IoOptions.defaults().withStreams(input.reader(), output).withRenderer(false);
            Component<List<String>, String, Never> program = rows(message -> switch (message) {
                case "p" -> Command.println("printed one");
                case "q" -> Command.quit();
                default -> null;
            });
            ProgramHandle<List<String>, String> handle =
                    Application.start(program, RuntimeOptions.defaults().withMessageFilter(filter), io);

            assertThat(handle.trySend("p")).isEqualTo(ProgramHandle.Send.ACCEPTED);
            awaitText(output, text -> text.contains("printed one"), "the durable line");
            assertThat(handle.quit()).isTrue();
            ProgramResult<List<String>> result = handle.await();

            assertThat(result.exit().kind()).isEqualTo(ExitResult.Kind.QUIT);
            assertThat(filtered)
                    .as("the filter saw the messages the session delivered")
                    .hasValueGreaterThan(0);
            assertThat(output.text())
                    .as("durable output in a renderer-disabled session is the text and nothing else")
                    .isEqualTo("printed one\n");
        }
    }

    @Test
    void theSessionsEnvironmentOverridesReachColourNegotiation() {
        try (TestTerminal fixture = new TestTerminal()) {
            fixture.endInput();
            RuntimeLoop<List<String>, String> coloured = new RuntimeLoop<>(
                    rows(message -> quitOn(message)),
                    inline(),
                    fixture.terminal(),
                    RuntimeServices.system(),
                    null,
                    IoOptions.defaults().withEnvironment(Map.of("NO_COLOR", "")));
            assertThat(coloured.run().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
            assertThat(fixture.outputText())
                    .as("a color terminal frames a colored cell with color")
                    .contains("38;2;");
        }
        try (TestTerminal fixture = new TestTerminal()) {
            fixture.endInput();
            RuntimeLoop<List<String>, String> plain = new RuntimeLoop<>(
                    rows(message -> quitOn(message)),
                    inline(),
                    fixture.terminal(),
                    RuntimeServices.system(),
                    null,
                    IoOptions.defaults().withEnvironment(Map.of("NO_COLOR", "1")));
            assertThat(plain.run().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
            assertThat(fixture.outputText())
                    .as("NO_COLOR from the session's own environment removes color")
                    .doesNotContain("38;2;");
        }
        assertThat(IoOptions.defaults().withEnvironment(Map.of("NO_COLOR", "1")).environment("NO_COLOR"))
                .as("an override wins over the process environment")
                .isEqualTo("1");
        assertThat(IoOptions.defaults().environment("BRAXTON_UNSET_FOR_TESTS_23"))
                .as("an unset variable with no override is absent")
                .isNull();
        assertThat(IoOptions.defaults().renderer())
                .as("the default session paints frames")
                .isTrue();
    }

    @Test
    void aSizeOverrideIsTheViewportTheSessionLaysOutFor() {
        List<Size> viewports = new CopyOnWriteArrayList<>();
        Component<List<String>, String, Never> program = new Component<>() {
            @Override
            public Update<List<String>, String, Never> init(InitContext context) {
                return Update.next(List.of());
            }

            @Override
            public Update<List<String>, String, Never> update(List<String> model, String message) {
                return Update.next(model);
            }

            @Override
            public Element<String> view(List<String> model, ViewContext context) {
                viewports.add(
                        new Size(context.viewport().rows(), context.viewport().cols()));
                return Elements.text("scene sized");
            }
        };
        Recorder output = new Recorder();
        IoOptions io =
                IoOptions.defaults().withStreams(new StringReader(""), output).withSize(7, 33);
        Application.run(program, io);

        assertThat(viewports).as("the session laid out for at least one frame").isNotEmpty();
        assertThat(viewports.get(0))
                .as("the viewport is the size the session was given, not a measured device")
                .isEqualTo(new Size(7, 33));
    }

    @Test
    void aReadFailureIsReportedAsAFailureOfTheRun() {
        Reader broken = new Reader() {
            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                throw new IOException("input is gone");
            }

            @Override
            public void close() {}
        };
        ExitResult result =
                Application.run(rows(message -> null), IoOptions.defaults().withStreams(broken, new Recorder()));

        assertThat(result.kind()).as("a failed read is a failed run").isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.failure()).isNotNull();
    }

    @Test
    void aWriteFailureIsReportedAsAFailureOfTheRun() {
        Writer broken = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) throws IOException {
                throw new IOException("output is gone");
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        ExitResult result =
                Application.run(rows(message -> null), IoOptions.defaults().withStreams(new PipedReader(), broken));

        assertThat(result.kind()).as("a failed write is a failed run").isEqualTo(ExitResult.Kind.FAILURE);
        assertThat(result.failure()).isNotNull();
    }

    // ── R-02: the inline live region ─────────────────────────────────────────

    @Test
    void anInlineFrameMovesRelativelyAndNeverClearsAboveItsRegion() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    rows(ParityIoTest::quitOn), inline(), fixture.terminal(), RuntimeServices.system());

            drive(fixture, loop, "a", "a", "b", "q");

            String transcript = fixture.outputText();
            assertThat(ABSOLUTE_MOVE.matcher(transcript).find())
                    .as("an inline frame must never address a row absolutely: " + visible(transcript))
                    .isFalse();
            assertThat(transcript)
                    .as("nothing may erase the user's own scrollback")
                    .doesNotContain(ERASE_DISPLAY);
            assertThat(transcript)
                    .as("the region is cleared forward from its own first row")
                    .contains(AnsiEncoder.ERASE_DISPLAY_BELOW);
            assertThat(transcript)
                    .as("rows are separated by a carriage return and a line feed")
                    .contains("\r\n");
            assertThat(transcript)
                    .as("the session entered no alternate screen")
                    .doesNotContain(AnsiEncoder.ALT_SCREEN_ON);
        }
    }

    @Test
    void aShorterViewErasesOnlyTheRowsBelowTheRegion() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    rows(ParityIoTest::quitOn), inline(), fixture.terminal(), RuntimeServices.system());

            drive(fixture, loop, "a", "a", "a", "b", "b", "q");

            String transcript = fixture.outputText();
            assertThat(transcript).as("no frame cleared the whole display").doesNotContain(ERASE_DISPLAY);
            assertThat(ABSOLUTE_MOVE.matcher(transcript).find())
                    .as("shrinking repaints relatively: " + visible(transcript))
                    .isFalse();
            List<String> shorter = fixture.writes().stream()
                    .filter(write -> write.contains("scene rows=1"))
                    .toList();
            assertThat(shorter).as("the shorter frame was presented").isNotEmpty();
            assertThat(shorter.get(shorter.size() - 1))
                    .as("the shorter frame clears the rows the view no longer paints")
                    .contains(AnsiEncoder.ERASE_DISPLAY_BELOW);
        }
    }

    @Test
    void aResizeRepaintsFromTheRegionOrigin() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    rows(ParityIoTest::quitOn), inline(), fixture.terminal(), RuntimeServices.system());

            Thread owner = Thread.ofVirtual().start(() -> {
                fixture.onOutput(text -> {
                    if (text.contains("scene rows=1")) {
                        fixture.resize(new Size(10, 40));
                        fixture.signalResize();
                    }
                });
                loop.run();
            });
            fixture.feed("a");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (!ABSOLUTE_MOVE.matcher(fixture.outputText()).find()
                    && !fixture.outputText().contains("scene rows=1")
                    && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            Thread.sleep(120L);
            fixture.feed("q");
            owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

            String transcript = fixture.outputText();
            assertThat(transcript).doesNotContain(ERASE_DISPLAY);
            assertThat(ABSOLUTE_MOVE.matcher(transcript).find())
                    .as("a resize repaint stays relative: " + visible(transcript))
                    .isFalse();
            assertThat(transcript)
                    .as("the resized frame repaints the region forward from its origin")
                    .contains(AnsiEncoder.ERASE_DISPLAY_BELOW);
        }
    }

    @Test
    void anEmptyViewClearsTheRegionAndWritesNoRows() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    rows(ParityIoTest::quitOn), inline(), fixture.terminal(), RuntimeServices.system());

            drive(fixture, loop, "a", "c", "q");

            String transcript = fixture.outputText();
            assertThat(transcript).doesNotContain(ERASE_DISPLAY);
            assertThat(ABSOLUTE_MOVE.matcher(transcript).find()).isFalse();
            assertThat(fixture.writes())
                    .as("an empty view clears the region it owned and paints no row")
                    .anySatisfy(write ->
                            assertThat(write).contains("scene rows=0").contains(AnsiEncoder.ERASE_DISPLAY_BELOW));
        }
    }

    @Test
    void anIdleInlineSessionPresentsNothing() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    rows(ParityIoTest::quitOn), inline(), fixture.terminal(), RuntimeServices.system());

            Thread owner = Thread.ofVirtual().start(loop::run);
            fixture.feed("a");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (!fixture.outputText().contains("scene rows=1") && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            Thread.sleep(120L);
            String before = fixture.outputText();
            int writes = fixture.writes().size();
            Thread.sleep(150L);
            assertThat(fixture.outputText())
                    .as("an idle inline session writes nothing between frames")
                    .isEqualTo(before);
            assertThat(fixture.writes()).hasSize(writes);
            fixture.feed("q");
            owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
    }

    // ── R-03: durable output ─────────────────────────────────────────────────

    @Test
    void aPrintedLineIsWrittenAboveTheRepaintedRegionInOneFrame() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    rows(message -> switch (message) {
                        case "p" -> Command.println("durable line");
                        case "q" -> Command.quit();
                        default -> null;
                    }),
                    inline(),
                    fixture.terminal(),
                    RuntimeServices.system());

            drive(fixture, loop, "a", "p", "q");

            String printed = fixture.writes().stream()
                    .filter(write -> write.contains("durable line"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("nothing was printed: " + visible(fixture.outputText())));
            assertThat(printed)
                    .as("the line is written inside the same synchronized update as the repaint")
                    .startsWith(BEGIN)
                    .endsWith(END);
            int line = printed.indexOf("durable line");
            assertThat(printed.substring(0, line))
                    .as("the managed region is erased before the line is written where it began")
                    .contains(AnsiEncoder.ERASE_DISPLAY_BELOW);
            assertThat(printed.substring(line))
                    .as("the durable line is followed by the region repainted below it")
                    .startsWith("durable line\r\n")
                    .contains("scene rows=1");
            assertThat(printed)
                    .as("the repaint after a print starts at the new origin, with no move up")
                    .contains("\r\n\r" + AnsiEncoder.ERASE_DISPLAY_BELOW);
        }
    }

    @Test
    void printingOnTheAlternateScreenChangesNothing() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    rows(message -> switch (message) {
                        case "p" -> Command.println("never shown");
                        case "q" -> Command.quit();
                        default -> null;
                    }),
                    RuntimeOptions.defaults(),
                    fixture.terminal(),
                    RuntimeServices.system());

            drive(fixture, loop, "a", "p", "q");

            assertThat(fixture.outputText())
                    .as("the alternate screen has no scrollback, so a print is an explicit no-op")
                    .doesNotContain("never shown");
        }
    }

    @Test
    void printedTextIsNormalizedAndCannotCarryControlSequences() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    rows(message -> switch (message) {
                        case "p" -> Command.println("a\r\nb\rc\u001b[31mred\tend\u0007");
                        case "q" -> Command.quit();
                        default -> null;
                    }),
                    inline(),
                    fixture.terminal(),
                    RuntimeServices.system());

            drive(fixture, loop, "a", "p", "q");

            String printed = fixture.writes().stream()
                    .filter(write -> write.contains("red"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("nothing was printed: " + visible(fixture.outputText())));
            assertThat(printed)
                    .as("CRLF and a bare CR both end a line, and the escape sequence is dropped")
                    .contains("a\r\nb\r\nc[31mred\tend\r\n");
            assertThat(printed)
                    .as("the escape byte itself is gone, so what follows is inert text")
                    .doesNotContain("\u001b[31m");
            assertThat(printed).as("the bell byte is dropped too").doesNotContain("\u0007");
        }
    }

    @Test
    void aLongPrintedLineDoesNotCorruptTheRegionBelow() throws Exception {
        String longLine = "x".repeat(500);
        try (TestTerminal fixture = new TestTerminal()) {
            RuntimeLoop<List<String>, String> loop = new RuntimeLoop<>(
                    rows(message -> switch (message) {
                        case "p" -> Command.println(longLine);
                        case "q" -> Command.quit();
                        default -> null;
                    }),
                    inline(),
                    fixture.terminal(),
                    RuntimeServices.system());

            drive(fixture, loop, "a", "p", "q");

            String printed = fixture.writes().stream()
                    .filter(write -> write.contains(longLine))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("nothing was printed"));
            assertThat(printed)
                    .as("the whole line is written even past the terminal's width")
                    .contains(longLine + "\r\n");
            assertThat(printed)
                    .as("the region is still repainted below it in the same write")
                    .contains("scene rows=1");
            assertThat(printed).startsWith(BEGIN).endsWith(END);
        }
    }

    @Test
    void aThrowingDiagnosticSinkCannotFailTheRun() throws Exception {
        Recorder output = new Recorder();
        try (Input input = new Input()) {
            IoOptions io = IoOptions.defaults()
                    .withStreams(input.reader(), output)
                    .withRenderer(false)
                    .withDiagnostics(message -> {
                        throw new IllegalStateException("the sink is broken");
                    });
            ProgramHandle<List<String>, String> handle =
                    Application.start(rows(ParityIoTest::failingTask), RuntimeOptions.defaults(), io);

            assertThat(handle.trySend("p")).isEqualTo(ProgramHandle.Send.ACCEPTED);
            Thread.sleep(200L);
            assertThat(handle.quit()).isTrue();
            ProgramResult<List<String>> result = handle.await();

            assertThat(result.exit().kind())
                    .as("a report about the session is not part of it")
                    .isEqualTo(ExitResult.Kind.QUIT);
            assertThat(result.exit().failure()).isNull();
        }
    }

    @Test
    void aDiagnosticSinkReceivesWhatTheRuntimeReports() throws Exception {
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        Recorder output = new Recorder();
        try (Input input = new Input()) {
            IoOptions io = IoOptions.defaults()
                    .withStreams(input.reader(), output)
                    .withRenderer(false)
                    .withDiagnostics(diagnostics::add);
            ProgramHandle<List<String>, String> handle =
                    Application.start(rows(ParityIoTest::failingTask), RuntimeOptions.defaults(), io);

            assertThat(handle.trySend("p")).isEqualTo(ProgramHandle.Send.ACCEPTED);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (diagnostics.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            assertThat(handle.quit()).isTrue();
            handle.await();

            assertThat(diagnostics)
                    .as("the caller's sink is where the runtime reports")
                    .anyMatch(message -> message.contains("failure mapper threw"));
        }
    }

    // ── R-04: the demo ───────────────────────────────────────────────────────

    @Test
    void theDemoParsesItsLaunchModes() {
        assertThat(InlineIoDemo.mode(new String[0])).isEqualTo(InlineIoDemo.Mode.INLINE);
        assertThat(InlineIoDemo.mode(new String[] {"--inline"})).isEqualTo(InlineIoDemo.Mode.INLINE);
        assertThat(InlineIoDemo.mode(new String[] {"--pipe"})).isEqualTo(InlineIoDemo.Mode.PIPE);
        assertThat(InlineIoDemo.mode(new String[] {"--headless"})).isEqualTo(InlineIoDemo.Mode.HEADLESS);
        assertThatThrownBy(() -> InlineIoDemo.mode(new String[] {"--nope"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDemoScenePrintsOneLinePerInstalledPackage() {
        InlineIoDemo.Model start = InlineIoDemo.Model.start();
        assertThat(InlineIoDemo.status(start)).contains("installed=0").contains("phase=idle");
        assertThat(InlineIoDemo.live(start)).contains("parser").contains("[------]");

        InlineIoDemo.Scene scene = new InlineIoDemo.Scene();
        Update<InlineIoDemo.Model, InlineIoDemo.Msg, Never> started =
                scene.update(start, new InlineIoDemo.Msg.InstallAll());
        assertThat(started.model().phase()).isEqualTo("installing");
        assertThat(started.model().automatic()).isTrue();
        assertThat(started.command()).isInstanceOf(Command.Task.class);

        Update<InlineIoDemo.Model, InlineIoDemo.Msg, Never> installed =
                scene.update(started.model(), new InlineIoDemo.Msg.Installed("parser"));
        List<Command<InlineIoDemo.Msg>> steps = flatten(installed.command());
        assertThat(steps.get(0))
                .as("the finished package becomes a durable line naming it")
                .isEqualTo(Command.println("installed parser"));
        assertThat(steps)
                .as("a self-running install continues with the next package")
                .hasSize(2);

        InlineIoDemo.Model manual = new InlineIoDemo.Model(0, "installing", false, 1);
        assertThat(flatten(scene.update(manual, new InlineIoDemo.Msg.Installed("parser"))
                        .command()))
                .as("a single install stops after its own line")
                .hasSize(1);

        InlineIoDemo.Model last = new InlineIoDemo.Model(5, "installing", true, 1);
        assertThat(flatten(scene.update(last, new InlineIoDemo.Msg.Installed("examples"))
                        .command()))
                .as("the last package ends the run of lines")
                .hasSize(1);
        assertThat(InlineIoDemo.summary(installed.model())).startsWith("final installed=1");
    }

    @Test
    void theDemoStreamSceneTurnsLinesIntoDurableOutput() throws Exception {
        List<InlineIoDemo.StreamMsg> emitted = new CopyOnWriteArrayList<>();
        Subscription.Source<InlineIoDemo.StreamMsg> source = (Subscription.Source<InlineIoDemo.StreamMsg>)
                new InlineIoDemo.StreamScene(new StringReader("alpha\nbeta\n"))
                        .subscriptions(InlineIoDemo.StreamModel.start());
        AutoCloseable reader = source.start().apply(value -> emitted.add((InlineIoDemo.StreamMsg) value));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (emitted.size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        reader.close();

        assertThat(emitted)
                .as("every line arrives, then exactly one end-of-input")
                .containsExactly(
                        new InlineIoDemo.StreamMsg.Line(1, "alpha"),
                        new InlineIoDemo.StreamMsg.Line(2, "beta"),
                        new InlineIoDemo.StreamMsg.Eof());

        InlineIoDemo.StreamScene scene = new InlineIoDemo.StreamScene(new StringReader(""));
        Update<InlineIoDemo.StreamModel, InlineIoDemo.StreamMsg, Never> line =
                scene.update(InlineIoDemo.StreamModel.start(), new InlineIoDemo.StreamMsg.Line(1, "alpha"));
        assertThat(line.model().lines()).containsExactly("alpha");
        assertThat(line.command()).isEqualTo(Command.println("line=1 text=alpha"));
        assertThat(scene.update(line.model(), new InlineIoDemo.StreamMsg.Eof()).command())
                .as("end of input ends the session")
                .isEqualTo(Command.quit());
    }

    @Test
    void theDemoHeadlessSessionRunsOnConfiguredStreamsAndReportsItsModel() {
        StringWriter output = new StringWriter();
        ProgramResult<InlineIoDemo.StreamModel> result =
                InlineIoDemo.session(new StringReader("alpha\nbeta\n"), output);

        assertThat(result.exit().kind()).isEqualTo(ExitResult.Kind.QUIT);
        assertThat(result.model().lines()).containsExactly("alpha", "beta");
        assertThat(result.model().finished()).isTrue();
        assertThat(output.toString())
                .as("a headless session writes its durable lines as plain text")
                .isEqualTo("line=1 text=alpha\nline=2 text=beta\n");
        assertThat(output.toString().indexOf('\u001b'))
                .as("no escape sequence reaches a headless output")
                .isEqualTo(-1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The failing task both diagnostic cases need: its work fails and its failure mapper throws.
     */
    private static Command<String> failingTask(String message) {
        if (!"p".equals(message)) {
            return quitOn(message);
        }
        return Command.task(
                EffectId.of("failing"),
                () -> {
                    throw new IllegalStateException("the task failed");
                },
                value -> "unreachable",
                failure -> {
                    throw new IllegalStateException("the failure mapper threw");
                });
    }

    /**
     * Runs one loop on its own thread, feeding each key once the previous frame was painted.
     */
    private static void drive(TestTerminal fixture, RuntimeLoop<List<String>, String> loop, String... keys)
            throws InterruptedException {
        Thread owner = Thread.ofVirtual().start(loop::run);
        for (String key : keys) {
            fixture.feed(key);
            Thread.sleep(80L);
        }
        owner.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertThat(owner.isAlive())
                .as("the session ended when it was asked to: " + visible(fixture.outputText()))
                .isFalse();
    }

    /**
     * One command flattened into its steps, so a batch's contents can be asserted.
     */
    private static <Msg> List<Command<Msg>> flatten(Command<Msg> command) {
        if (command instanceof Command.None<Msg>) {
            return List.of();
        }
        if (command instanceof Command.Batch<Msg> batch) {
            List<Command<Msg>> steps = new ArrayList<>();
            for (Command<Msg> item : batch.commands()) {
                steps.addAll(flatten(item));
            }
            return steps;
        }
        return List.of(command);
    }
}

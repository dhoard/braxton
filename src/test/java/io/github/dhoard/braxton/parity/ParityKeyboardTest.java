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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import examples.parity.KeyboardFocusDemo;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.input.KeyboardEnhancements;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.input.TestReaders;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.RuntimeLoop;
import io.github.dhoard.braxton.internal.runtime.RuntimeServices;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.internal.terminal.TimedReader;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorConfig;
import io.github.dhoard.braxton.widget.Elements;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Phase 02 acceptance: enhanced keyboard input and terminal focus.
 *
 * <p>Four facts are asserted against public behavior. A decoded key keeps its identity, modifiers,
 * associated text and event type, and only a press or a repeat activates. The session asks the
 * terminal for the flags an application requested, and restores them on every exit. Terminal focus
 * arrives as its own event and never rewrites model focus. A release runs no binding and inserts no
 * text.
 *
 * <p>The negotiation cases drive a real {@code RuntimeLoop} over an owned {@link TestTerminal}, so
 * the asserted evidence is the byte transcript the session actually wrote.
 */
@ExtendWith(TestReaders.class)
class ParityKeyboardTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    /**
     * The flags the demo requests: disambiguation, event types, every key, associated text.
     */
    private static final int REQUESTED = 1 | 2 | 8 | 16;

    // ── R-01: decoding enhanced key events ───────────────────────────────────

    /**
     * Serves one byte per read, so UTF-8 scalars and escape sequences are genuinely split.
     */
    private static final class ByteAtATime extends InputStream {
        private final byte[] data;
        private final int stallBefore;
        private final long stallMillis;
        private int index;
        private boolean stalled;

        ByteAtATime(String text) {
            this(text, -1, 0L);
        }

        /**
         * Waits {@code stallMillis} before serving byte {@code stallBefore}.
         */
        ByteAtATime(String text, int stallBefore, long stallMillis) {
            this.data = text.getBytes(UTF_8);
            this.stallBefore = stallBefore;
            this.stallMillis = stallMillis;
        }

        @Override
        public int read() throws IOException {
            if (!stalled && index == stallBefore) {
                stalled = true;
                try {
                    Thread.sleep(stallMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("stalled test input interrupted", interrupted);
                }
            }
            return index < data.length ? data[index++] & 0xFF : -1;
        }
    }

    private static List<InputEvent> decodeAll(String text) throws IOException {
        TimedReader reader = TestReaders.utf8(new ByteAtATime(text));
        InputDecoder decoder = new InputDecoder(reader);
        List<InputEvent> events = new ArrayList<>();
        Optional<InputEvent> event;
        while ((event = decoder.read()).isPresent()) {
            events.add(event.get());
        }
        return events;
    }

    private static InputEvent.KeyInput decodeOne(String text) throws IOException {
        List<InputEvent> events = decodeAll(text);
        assertThat(events).as("exactly one event for %s", printable(text)).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(InputEvent.KeyInput.class);
        return (InputEvent.KeyInput) events.get(0);
    }

    /**
     * A fixture's bytes as the escapes a failure message can read.
     */
    private static String printable(String text) {
        StringBuilder shown = new StringBuilder();
        text.codePoints().forEach(code -> {
            if (code == 0x1B) {
                shown.append("ESC");
            } else if (code < 0x20 || code == 0x7F) {
                shown.append(String.format("0x%02x", code));
            } else {
                shown.appendCodePoint(code);
            }
        });
        return shown.toString();
    }

    @Test
    void shiftEnterDiffersFromEnter() throws IOException {
        InputEvent.KeyInput plain = decodeOne("\r");
        assertThat(plain.key()).as("Enter keeps its legacy byte").isEqualTo(Key.ENTER);
        assertThat(plain.type()).isEqualTo(KeyEventType.PRESS);
        assertThat(plain.text()).as("Enter produces no text").isEmpty();

        InputEvent.KeyInput shifted = decodeOne("\u001B[13;2u");
        assertThat(shifted.key()).isEqualTo(Key.of("enter", Modifiers.SHIFT));
        assertThat(shifted.key()).as("the two gestures are different keys").isNotEqualTo(plain.key());
        assertThat(shifted.text()).isEmpty();
    }

    @Test
    void ctrlIDiffersFromTab() throws IOException {
        InputEvent.KeyInput tab = decodeOne("\t");
        assertThat(tab.key()).as("Tab keeps its legacy byte").isEqualTo(Key.TAB);
        assertThat(tab.text()).isEmpty();

        InputEvent.KeyInput ctrlI = decodeOne("\u001B[105;5u");
        assertThat(ctrlI.key()).isEqualTo(Key.of("i", Modifiers.CTRL));
        assertThat(ctrlI.key()).isNotEqualTo(tab.key());
        assertThat(ctrlI.text()).as("a Ctrl chord carries no text").isEmpty();
    }

    @Test
    void pressRepeatAndReleaseKeepKeyModifiersAndAssociatedText() throws IOException {
        // Split across timed reads: the same sequence must decode the same way however it arrives.
        InputEvent.KeyInput press = decodeOne("\u001B[97;2;65u");
        assertThat(press.key()).isEqualTo(Key.of("a", Modifiers.SHIFT));
        assertThat(press.type()).isEqualTo(KeyEventType.PRESS);
        assertThat(press.text()).as("the reported text is kept as reported").isEqualTo("A");
        assertThat(press.activates()).isTrue();

        InputEvent.KeyInput repeat = decodeOne("\u001B[97;2:2;65u");
        assertThat(repeat.type()).isEqualTo(KeyEventType.REPEAT);
        assertThat(repeat.activates()).as("a repeat is a press for routing").isTrue();
        assertThat(repeat.key()).as("a repeat keeps the key").isEqualTo(press.key());
        assertThat(repeat.text()).as("a repeat keeps the text").isEqualTo(press.text());

        InputEvent.KeyInput release = decodeOne("\u001B[97;2:3;65u");
        assertThat(release.type()).isEqualTo(KeyEventType.RELEASE);
        assertThat(release.activates()).as("a release never activates").isFalse();
        assertThat(release.key()).as("a release keeps the key").isEqualTo(press.key());
        assertThat(release.text()).as("a release keeps the text").isEqualTo(press.text());

        assertThat(press).as("only the event type distinguishes the three").isNotEqualTo(repeat);
        assertThat(repeat).isNotEqualTo(release);
    }

    @Test
    void aSequenceSplitAcrossTimedReadsDecodesOnce() throws IOException {
        // The stall sits inside the sequence and stays well under the Escape timeout, which is the
        // case a fragmented read produces when the terminal is slow to finish a sequence.
        TimedReader reader = TestReaders.utf8(new ByteAtATime("\u001B[97;2:3;65u", 4, 10L));
        InputEvent event = new InputDecoder(reader).read().orElseThrow();
        assertThat(event).isInstanceOf(InputEvent.KeyInput.class);
        assertThat(((InputEvent.KeyInput) event).type()).isEqualTo(KeyEventType.RELEASE);
    }

    @Test
    void alternateKeyCodesSupplyTheShiftedCharacter() throws IOException {
        // The shifted key is reported alongside the unshifted one: the key stays "a", the text is "A".
        InputEvent.KeyInput shifted = decodeOne("\u001B[97:65;2u");
        assertThat(shifted.key()).isEqualTo(Key.of("a", Modifiers.SHIFT));
        assertThat(shifted.text()).isEqualTo("A");

        InputEvent.KeyInput base = decodeOne("\u001B[97:65:99;2u");
        assertThat(base.key())
                .as("the base-layout code names a physical key, not this key")
                .isEqualTo(shifted.key());
        assertThat(base.text()).isEqualTo("A");
    }

    @Test
    void unicodeKeysDecodeAsOneScalar() throws IOException {
        InputEvent.KeyInput eAcute = decodeOne("\u001B[233u");
        assertThat(eAcute.key()).isEqualTo(Key.of("\u00E9"));
        assertThat(eAcute.text()).isEqualTo("\u00E9");

        InputEvent.KeyInput emoji = decodeOne("\u001B[128512u");
        assertThat(emoji.text()).as("a supplementary scalar is one character").isEqualTo("\uD83D\uDE00");
        assertThat(emoji.key().name()).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void aTextOnlyEventTakesItsKeyFromTheText() throws IOException {
        // The protocol reports key number 0 when the operating system produced text without a key,
        // which is what an Option chord that the OS resolved looks like.
        InputEvent.KeyInput text = decodeOne("\u001B[0;;229u");
        assertThat(text.key()).isEqualTo(Key.of("\u00E5"));
        assertThat(text.text()).isEqualTo("\u00E5");
        assertThat(text.activates()).isTrue();
    }

    @Test
    void functionalKeysCarryModifiersAndEventTypes() throws IOException {
        InputEvent.KeyInput ctrlUp = decodeOne("\u001B[1;5A");
        assertThat(ctrlUp.key()).isEqualTo(Key.of("up", Modifiers.CTRL));
        assertThat(ctrlUp.type()).isEqualTo(KeyEventType.PRESS);
        assertThat(ctrlUp.text()).isEmpty();

        InputEvent.KeyInput ctrlDeleteUp = decodeOne("\u001B[3;5:3~");
        assertThat(ctrlDeleteUp.key()).isEqualTo(Key.of("delete", Modifiers.CTRL));
        assertThat(ctrlDeleteUp.type()).isEqualTo(KeyEventType.RELEASE);
        assertThat(ctrlDeleteUp.activates()).isFalse();

        InputEvent.KeyInput shiftF1 = decodeOne("\u001B[1;2P");
        assertThat(shiftF1.key()).isEqualTo(Key.of("f1", Modifiers.SHIFT));

        // The private-use numbers the protocol reserves for function keys are named, not invented.
        assertThat(decodeOne("\u001B[57376u").key()).isEqualTo(Key.of("f13"));
        assertThat(decodeOne("\u001B[57344u").key())
                .as("the deprecated Escape number")
                .isEqualTo(Key.ESCAPE);
    }

    @Test
    void malformedOrUnrepresentableParametersAreDroppedInsteadOfInvented() throws IOException {
        String following = "x";
        for (String malformed : List.of(
                "\u001B[;5u", // no key code
                "\u001B[97;5:9u", // an event type the protocol does not define
                "\u001B[97;0u", // a modifier field below the protocol's one-based minimum
                "\u001B[97;1;1;1u", // too many parameter groups
                "\u001B[999999999u", // a value no code point can hold
                "\u001B[55296u", // the surrogate range is not a scalar
                "\u001B[0;1;7u", // associated text that is a control code
                "\u001B[97;9u", // Super, which Modifiers cannot carry
                "\u001B[97;17u", // Hyper
                "\u001B[97;33u", // Meta
                "\u001B[97:65:99:1;2u", // too many key-code sub-fields
                "\u001B[57358u")) { // a functional key this project does not name
            List<InputEvent> events = decodeAll(malformed + following);
            assertThat(events).as("%s decodes to nothing", printable(malformed)).hasSize(1);
            assertThat(((InputEvent.KeyInput) events.get(0)).text())
                    .as("the input after %s still arrives", printable(malformed))
                    .isEqualTo(following);
        }
    }

    @Test
    void anOverlongEnhancedSequenceIsSkippedWholeInsteadOfLeakingItsTail() throws IOException {
        List<InputEvent> events = decodeAll("\u001B[" + "1".repeat(70) + "u" + "x");
        assertThat(events).hasSize(1);
        assertThat(((InputEvent.KeyInput) events.get(0)).text()).isEqualTo("x");
    }

    @Test
    void legacyInputStillDecodesAsPressesOnly() throws IOException {
        InputEvent.KeyInput typed = decodeOne("a");
        assertThat(typed.type()).isEqualTo(KeyEventType.PRESS);
        assertThat(typed.activates()).isTrue();
        assertThat(typed.text()).isEqualTo("a");

        InputEvent.KeyInput altEnter = decodeOne("\u001B\r");
        assertThat(altEnter.key()).as("the legacy Alt form still decodes").isEqualTo(Key.of("enter", Modifiers.ALT));
        assertThat(altEnter.type()).isEqualTo(KeyEventType.PRESS);
    }

    @Test
    void theLegacyConstructorStillBuildsAPress() {
        InputEvent.KeyInput legacy = new InputEvent.KeyInput(Key.of("a"), "a", 7L);
        assertThat(legacy.type()).isEqualTo(KeyEventType.PRESS);
        assertThat(legacy.activates()).isTrue();
        assertThat(legacy.capturedAtNanos()).isEqualTo(7L);
        assertThat(InputEvent.KeyInput.of(Key.of("a"), "a").type()).isEqualTo(KeyEventType.PRESS);
        assertThat(legacy.withType(KeyEventType.PRESS))
                .as("an unchanged kind is the same value")
                .isSameAs(legacy);
        assertThat(legacy.withType(KeyEventType.RELEASE).type()).isEqualTo(KeyEventType.RELEASE);
        assertThat(legacy.withType(KeyEventType.PRESS))
                .as("an unchanged kind is the same value")
                .isSameAs(legacy);
    }

    // ── R-02: negotiation and restoration ────────────────────────────────────

    /**
     * Records every input event the runtime routes, in order.
     */
    private static final class Observer implements Component<List<String>, Object, Never> {

        @Override
        public Update<List<String>, Object, Never> init(InitContext context) {
            return Update.next(List.of());
        }

        @Override
        public Update<List<String>, Object, Never> update(List<String> model, Object message) {
            List<String> next = new ArrayList<>(model);
            next.add(describe(message));
            return Update.next(next);
        }

        @Override
        public Element<Object> view(List<String> model, ViewContext context) {
            return Elements.text("observed " + model.size());
        }

        /**
         * Every event becomes a message, so the session records what arrived.
         */
        @Override
        public java.util.List<Object> observed(InputEvent event) {
            return List.of(event);
        }

        private static String describe(Object message) {
            return switch (message) {
                case InputEvent.KeyInput key -> "key:" + key.type() + ":" + key.key() + ":" + key.text();
                case InputEvent.FocusInput focus -> "focus:" + focus.gained();
                case InputEvent.KeyboardEnhancementsInput report ->
                    "caps:" + report.flags().flags();
                case InputEvent.PasteInput paste -> "paste:" + paste.text();
                case InputEvent.PointerInput pointer ->
                    "pointer:" + pointer.pointer().action();
                case InputEvent.ResizeInput size -> "resize:" + size.size();
                default -> String.valueOf(message);
            };
        }
    }

    /**
     * One owned session: the events it observed and the bytes it wrote.
     */
    private record Session(List<String> observed, String transcript, ExitResult result) {}

    /**
     * Runs one whole session over an owned stream fixture.
     *
     * <p>The input is fed and ended before the run starts, so the observation order is the order the
     * terminal sent and no wall clock is involved.</p>
     */
    private static Session session(TerminalOptions terminal, String input) {
        TestTerminal fixture = new TestTerminal(new Size(8, 40));
        try {
            fixture.feed(input);
            fixture.endInput();
            RuntimeOptions options =
                    RuntimeOptions.defaults().withLimits(64, 8, 8).withTerminal(terminal);
            RuntimeLoop<List<String>, Object> loop = new RuntimeLoop<>(
                    new Observer(),
                    options,
                    fixture.terminal(),
                    RuntimeServices.system()
                            .withTimers(new ManualTimers())
                            .withEffectExecutor(Runnable::run)
                            .withNanoTime(() -> 1000L));
            ExitResult result = loop.run();
            return new Session(List.copyOf(loop.model()), fixture.outputText(), result);
        } finally {
            fixture.close();
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static final TerminalOptions REQUESTING = TerminalOptions.DEFAULT
            .withKeyboardEnhancements(KeyboardFocusDemo.REQUESTED)
            .withFocusReporting(true);

    @Test
    void theRequestedFlagsArePushedOnceQueriedOnceAndPoppedOnce() {
        Session session = session(REQUESTING, "");
        assertThat(session.transcript())
                .as("the request is pushed as the flags the application asked for")
                .contains("\u001B[>" + REQUESTED + "u");
        assertThat(occurrences(session.transcript(), "\u001B[>" + REQUESTED + "u"))
                .as("enabled once")
                .isEqualTo(1);
        assertThat(occurrences(session.transcript(), "\u001B[?u"))
                .as("the terminal is asked once which flags it has")
                .isEqualTo(1);
        assertThat(occurrences(session.transcript(), "\u001B[<u"))
                .as("restored once")
                .isEqualTo(1);
        assertThat(session.transcript().indexOf("\u001B[<u"))
                .as("the pop precedes leaving the alternate screen, whose stack is separate")
                .isLessThan(session.transcript().indexOf("\u001B[?1049l"));
        assertThat(occurrences(session.transcript(), "\u001B[?1004h"))
                .as("focus reporting is enabled once")
                .isEqualTo(1);
        assertThat(session.transcript().indexOf("\u001B[?1004l"))
                .as("focus reporting is disabled on exit")
                .isGreaterThan(session.transcript().indexOf("\u001B[?1004h"));
        assertThat(session.result().kind()).isEqualTo(ExitResult.Kind.INPUT_CLOSED);
        assertThat(session.observed()).as("a silent terminal reports nothing").isEmpty();
    }

    @Test
    void aSilentTerminalKeepsLegacyInputAndIsStillRestored() {
        Session session = session(REQUESTING, "a");
        assertThat(session.observed())
                .as("typing after an unanswered request is still one legacy press")
                .containsExactly("key:PRESS:a:a");
        assertThat(session.transcript())
                .as("a terminal that ignores the protocol is asked anyway, and popped anyway")
                .contains("\u001B[<u");
    }

    @Test
    void aSupportedTerminalReportsTheFlagsItEnabled() {
        Session session = session(REQUESTING, "\u001B[?" + REQUESTED + "u");
        assertThat(session.observed()).containsExactly("caps:" + REQUESTED);
    }

    @Test
    void aPartialNegotiationReportsOnlyTheFlagsTheTerminalEnabled() {
        // The terminal implements disambiguation and event types but not the rest.
        Session session = session(REQUESTING, "\u001B[?3u");
        assertThat(session.observed()).containsExactly("caps:3");
        assertThat(session.transcript())
                .as("the request was still the full set; the terminal decides what took effect")
                .contains("\u001B[>" + REQUESTED + "u");
    }

    @Test
    void aRepeatedSessionPushesAndPopsTheSameSingleLevel() {
        KeyboardEnhancements earlier = KeyboardEnhancements.NONE.withDisambiguate(true);
        Session first = session(REQUESTING, "\u001B[?3u");
        Session second = session(REQUESTING.withKeyboardEnhancements(earlier), "\u001B[?1u");
        assertThat(occurrences(first.transcript(), "\u001B[<u")).isEqualTo(1);
        assertThat(occurrences(second.transcript(), "\u001B[<u")).isEqualTo(1);
        assertThat(second.transcript())
                .as("the second session pushes exactly the flags it asked for")
                .contains("\u001B[>1u");
        assertThat(second.observed()).containsExactly("caps:1");
    }

    /**
     * A program whose first update throws, so the session ends in failure rather than a quit.
     */
    private static final class Failing implements Component<Integer, String, Never> {

        @Override
        public Update<Integer, String, Never> init(InitContext context) {
            return Update.next(0);
        }

        @Override
        public Update<Integer, String, Never> update(Integer model, String message) {
            throw new IllegalStateException("application failure");
        }

        @Override
        public Element<String> view(Integer model, ViewContext context) {
            return Elements.text("failing");
        }

        /**
         * Every event becomes a message, so the very first one fails the update.
         */
        @Override
        public java.util.List<String> observed(InputEvent event) {
            return List.of("first");
        }
    }

    @Test
    void aFailedSessionStillRestoresTheKeyboardStackAndFocusReporting() {
        TestTerminal fixture = new TestTerminal(new Size(8, 40));
        ExitResult result;
        try {
            fixture.feed("a");
            RuntimeOptions options =
                    RuntimeOptions.defaults().withLimits(64, 8, 8).withTerminal(REQUESTING);
            result = new RuntimeLoop<>(
                            new Failing(),
                            options,
                            fixture.terminal(),
                            RuntimeServices.system()
                                    .withTimers(new ManualTimers())
                                    .withEffectExecutor(Runnable::run)
                                    .withNanoTime(() -> 1000L))
                    .run();
            assertThat(result.kind()).isEqualTo(ExitResult.Kind.FAILURE);
            assertThat(occurrences(fixture.outputText(), "\u001B[<u"))
                    .as("a failing session restores what it pushed")
                    .isEqualTo(1);
            assertThat(fixture.outputText())
                    .as("and switches focus reporting off")
                    .contains("\u001B[?1004l");
        } finally {
            fixture.close();
        }
    }

    @Test
    void aSessionThatAsksForNothingWritesNoKeyboardOrFocusSequences() {
        Session session = session(TerminalOptions.DEFAULT, "a");
        assertThat(session.transcript())
                .doesNotContain("\u001B[>")
                .doesNotContain("\u001B[?u")
                .doesNotContain("\u001B[<u")
                .doesNotContain("\u001B[?1004h")
                .doesNotContain("\u001B[?1004l");
        assertThat(session.observed()).containsExactly("key:PRESS:a:a");
    }

    @Test
    void focusNotificationsArriveInTheOrderTheTerminalSentThem() {
        TerminalOptions focusOnly = TerminalOptions.DEFAULT.withFocusReporting(true);
        Session session = session(focusOnly, "\u001B[O\u001B[I");
        assertThat(session.observed()).containsExactly("focus:false", "focus:true");
        assertThat(session.transcript())
                .as("no keyboard sequence is written when no enhancement is requested")
                .doesNotContain("\u001B[>")
                .doesNotContain("\u001B[<u");
    }

    // ── R-03: terminal focus is not model focus ──────────────────────────────

    @Test
    void terminalFocusIsItsOwnEventAndNotAKeystroke() throws IOException {
        List<InputEvent> events = decodeAll("\u001B[I\u001B[O");
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isEqualTo(new InputEvent.FocusInput(true));
        assertThat(events.get(1)).isEqualTo(new InputEvent.FocusInput(false));
        assertThat(events).allSatisfy(event -> assertThat(event).isNotInstanceOf(InputEvent.KeyInput.class));
    }

    @Test
    void aTerminalBlurNeverRewritesEditorModelFocus() {
        List<InputEvent> seen = new ArrayList<>();
        Editor editor = new Editor(EditorConfig.defaults());
        Editor.Model draft = Editor.Model.of("draft");
        Element<Editor.Msg> body = editor.view(draft, CONTEXT);
        Component<String, String, Never> component =
                component(model -> body.map(message -> "editor:" + message), model -> List.of(), event -> {
                    seen.add(event);
                    return List.of("saw:" + event);
                });

        assertThat(draft.focused()).as("the draft starts focused").isTrue();
        assertThat(route(component, "m", new InputEvent.FocusInput(false)))
                .contains("saw:" + new InputEvent.FocusInput(false));
        assertThat(route(component, "m", new InputEvent.FocusInput(true)))
                .contains("saw:" + new InputEvent.FocusInput(true));

        // The notifications reached routing and the editor's state and painting are untouched: a
        // terminal focus change is not model data, so no framework path can fold one into the other.
        assertThat(draft.focused()).isTrue();
        assertThat(lines(editor.view(draft, CONTEXT)))
                .as("the focused paint is unchanged")
                .isEqualTo(lines(body));
        assertThat(seen).hasSize(2);
        assertThat(seen).allSatisfy(event -> assertThat(event).isInstanceOf(InputEvent.FocusInput.class));
    }

    @Test
    void aReleaseRunsNoBindingAndInsertsNoText() {
        Component<String, String, Never> component = component(
                model -> Elements.text(model), model -> List.of(Binding.of("a", "captured")), event -> List.of());

        InputEvent.KeyInput press = InputEvent.KeyInput.of(Key.of("a"), "a");
        assertThat(route(component, "m", press)).as("a press is claimed").contains("captured");

        // The same gesture on the way up reports the same key and text, and does nothing at all.
        InputEvent.KeyInput release = press.withType(KeyEventType.RELEASE);
        assertThat(route(component, "m", release)).isEmpty();

        // A repeat still activates, so holding a key down keeps typing.
        assertThat(route(component, "m", press.withType(KeyEventType.REPEAT))).contains("captured");
    }

    @Test
    void aReleaseStillReachesObservedSoAnApplicationCanSeeIt() {
        List<String> seen = new ArrayList<>();
        Component<String, String, Never> component =
                component(model -> Elements.text("x"), model -> List.of(), event -> {
                    seen.add(String.valueOf(event));
                    return List.of();
                });
        InputEvent.KeyInput release = InputEvent.KeyInput.of(Key.of("a"), "a", KeyEventType.RELEASE);
        assertThat(route(component, "m", release)).isEmpty();
        assertThat(seen).as("observing is the full-event hook").hasSize(1);
    }

    /**
     * A component whose whole behavior is the three hooks a key path reads.
     */
    private static Component<String, String, Never> component(
            java.util.function.Function<String, Element<String>> view,
            java.util.function.Function<String, List<Binding<String>>> claims,
            java.util.function.Function<InputEvent, List<String>> observed) {
        return new Component<>() {
            @Override
            public Update<String, String, Never> init(InitContext context) {
                return Update.next("");
            }

            @Override
            public Update<String, String, Never> update(String model, String message) {
                return Update.next(message);
            }

            @Override
            public Element<String> view(String model, ViewContext context) {
                return view.apply(model);
            }

            @Override
            public List<Binding<String>> claims(String model) {
                return claims.apply(model);
            }

            @Override
            public List<String> observed(InputEvent event) {
                return observed.apply(event);
            }
        };
    }

    /**
     * One event through the runtime's own steps: claims, then the tree, then the last tier.
     */
    private static List<String> route(Component<String, String, Never> component, String model, InputEvent event) {
        return io.github.dhoard.braxton.testing.Routing.route(component, model, CONTEXT, event);
    }

    // ── R-04: the demo scene ────────────────────────────────────────────────

    private static ComponentDriver<KeyboardFocusDemo.Model, KeyboardFocusDemo.Msg, Never> demo() {
        return ComponentDriver.initialize(new KeyboardFocusDemo.Scene(), new InitContext(null, null));
    }

    private static List<String> lines(ComponentDriver<KeyboardFocusDemo.Model, KeyboardFocusDemo.Msg, ?> driver) {
        return lines(driver.view(CONTEXT));
    }

    private static List<String> lines(Element<?> view) {
        return ElementScreen.render(
                        view, CONTEXT.viewport().rows(), CONTEXT.viewport().cols())
                .lines();
    }

    /**
     * Routes one decoded event through the driver's current view and applies the message.
     */
    private static void press(
            ComponentDriver<KeyboardFocusDemo.Model, KeyboardFocusDemo.Msg, Never> driver, String bytes) {
        List<InputEvent> events;
        try {
            events = decodeAll(bytes);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
        for (InputEvent event : events) {
            // The runtime's two steps: route the event, then dispatch what the scene made of it.
            for (KeyboardFocusDemo.Msg message : io.github.dhoard.braxton.testing.Routing.route(
                    driver.component(), driver.model(), CONTEXT, event)) {
                deliver(driver, message);
            }
        }
    }

    /**
     * Sends a message and then delivers the dispatches it produced.
     *
     * <p>A driver leaves commands inert on purpose; a composed scene reaches a mounted child through
     * a dispatch, so the test delivers those the way the run loop does and nothing else.</p>
     */
    private static void deliver(
            ComponentDriver<KeyboardFocusDemo.Model, KeyboardFocusDemo.Msg, Never> driver,
            KeyboardFocusDemo.Msg message) {
        java.util.ArrayDeque<KeyboardFocusDemo.Msg> pending = new java.util.ArrayDeque<>();
        pending.add(message);
        while (!pending.isEmpty()) {
            inspect(driver.send(pending.removeFirst()).command(), pending);
        }
    }

    private static void inspect(
            Command<KeyboardFocusDemo.Msg> command, java.util.ArrayDeque<KeyboardFocusDemo.Msg> pending) {
        switch (command) {
            case Command.None<KeyboardFocusDemo.Msg> none -> {}
            case Command.Batch<KeyboardFocusDemo.Msg> batch -> batch.commands().forEach(item -> inspect(item, pending));
            case Command.Dispatch<KeyboardFocusDemo.Msg> dispatch -> pending.add(dispatch.message());
            case Command.Scoped<KeyboardFocusDemo.Msg> scoped -> inspect(scoped.command(), pending);
            default -> {}
        }
    }

    @Test
    void theDemoStartsWithNothingReportedAndItsFieldFocused() {
        var driver = demo();
        List<String> screen = lines(driver);
        assertThat(screen.get(0)).isEqualTo("caps=none event-types=off focus-report=on");
        assertThat(screen.get(1)).isEqualTo("last=none term=focused");
        assertThat(screen.get(2))
                .startsWith("editor=")
                .contains("field=focused")
                .endsWith("out=none");
        assertThat(driver.model().editor().focused())
                .as("model focus is its own fact")
                .isTrue();
        assertThat(String.join("\n", screen)).contains("? toggle help \u2022 f toggle field focus \u2022 q quit");
    }

    @Test
    void theDemoShowsTheFlagsTheTerminalReported() {
        var driver = demo();
        driver.send(new KeyboardFocusDemo.Msg.Enhancements(KeyboardEnhancements.of(REQUESTED)));
        assertThat(lines(driver).get(0)).isEqualTo("caps=" + REQUESTED + " event-types=on focus-report=on");
    }

    @Test
    void theDemoRecordsPressRepeatAndReleaseWithoutApplyingTheRelease() {
        var driver = demo();
        press(driver, "\u001B[97;1;97u");
        assertThat(driver.model().editor().text()).as("a press types").isEqualTo("a");
        assertThat(lines(driver).get(1)).isEqualTo("last=press:a term=focused");

        press(driver, "\u001B[97;1:2;97u");
        assertThat(driver.model().editor().text()).as("a repeat types too").isEqualTo("aa");
        assertThat(lines(driver).get(1)).isEqualTo("last=repeat:a term=focused");

        press(driver, "\u001B[97;1:3;97u");
        assertThat(driver.model().editor().text())
                .as("a release reports the text its press carried and inserts none of it")
                .isEqualTo("aa");
        assertThat(lines(driver).get(1)).isEqualTo("last=release:a term=focused");
    }

    @Test
    void theDemoDistinguishesEnterFromShiftEnterAndTabFromCtrlI() {
        var driver = demo();
        press(driver, "\u001B[13;2u");
        assertThat(lines(driver).get(1)).isEqualTo("last=press:shift+enter term=focused");
        press(driver, "\r");
        assertThat(lines(driver).get(1)).isEqualTo("last=press:enter term=focused");
        press(driver, "\u001B[105;5u");
        assertThat(lines(driver).get(1)).isEqualTo("last=press:ctrl+i term=focused");
        press(driver, "\t");
        assertThat(lines(driver).get(1)).isEqualTo("last=press:tab term=focused");
        assertThat(driver.model().editor().text())
                .as("none of the four gestures is text")
                .isEmpty();
    }

    @Test
    void theDemoReportsTerminalFocusSeparatelyFromFieldFocus() {
        var driver = demo();
        press(driver, "\u001B[O");
        assertThat(lines(driver).get(1)).isEqualTo("last=focus-lost term=blurred");
        assertThat(lines(driver).get(2)).as("the field is still focused").contains("field=focused");
        assertThat(driver.model().editor().focused()).isTrue();

        press(driver, "\u001B[I");
        assertThat(lines(driver).get(1)).isEqualTo("last=focus-gained term=focused");
        assertThat(driver.model().editor().focused())
                .as("and gaining focus changes nothing either")
                .isTrue();
    }

    @Test
    void theDemoStopsApplyingEditorActionsToAnUnfocusedField() {
        var driver = demo();
        // The scene claims its own keys, so the focused field never sees this one.
        assertThat(InteractionRouter.route(
                        driver.view(CONTEXT),
                        InputEvent.KeyInput.of(Key.of("f"), ""),
                        driver.component().claims(driver.model()),
                        driver.component().bindings(driver.model())))
                .contains(new KeyboardFocusDemo.Msg.ToggleField());
        driver.send(new KeyboardFocusDemo.Msg.ToggleField());
        assertThat(driver.model().editor().focused()).isFalse();

        press(driver, "a");
        assertThat(driver.model().editor().text())
                .as("an unfocused field takes no text")
                .isEmpty();
        assertThat(lines(driver).get(1)).as("the gesture is still reported").isEqualTo("last=press:a term=focused");
    }

    @Test
    void theDemoQuitsOnItsOwnActions() {
        var driver = demo();
        assertThat(InteractionRouter.route(
                        driver.view(CONTEXT),
                        InputEvent.KeyInput.of(Key.of("q"), ""),
                        driver.component().claims(driver.model()),
                        driver.component().bindings(driver.model())))
                .contains(new KeyboardFocusDemo.Msg.Quit());
        assertThat(InteractionRouter.route(
                        driver.view(CONTEXT),
                        InputEvent.KeyInput.of(Key.parse("ctrl+c"), ""),
                        driver.component().claims(driver.model()),
                        driver.component().bindings(driver.model())))
                .contains(new KeyboardFocusDemo.Msg.Quit());
        assertThat(driver.send(new KeyboardFocusDemo.Msg.Quit()).command()).isNotNull();
    }

    @Test
    void theDemoRendersTheSameScreenForTheSameModel() {
        var driver = demo();
        List<String> first = lines(driver);
        assertThat(lines(driver)).as("view is pure").isEqualTo(first);
        assertThat(driver.model().editor().text())
                .as("rendering changes no model value")
                .isEmpty();
    }

    // ── R-05: preserved behavior ────────────────────────────────────────────

    @Test
    void theEnhancedValuesAreImmutableAndRejectNulls() {
        assertThat(KeyboardEnhancements.NONE.flags()).isZero();
        assertThat(KeyboardEnhancements.NONE.any()).isFalse();
        KeyboardEnhancements all = KeyboardEnhancements.of(REQUESTED);
        assertThat(all.flags()).isEqualTo(REQUESTED);
        assertThat(all.withReportEventTypes(false).flags()).isEqualTo(REQUESTED & ~2);
        assertThat(all.withReportEventTypes(true))
                .as("an unchanged flag is the same value")
                .isSameAs(all);
        assertThat(KeyboardEnhancements.of(0xFF).flags())
                .as("higher flag bits are dropped, the five defined ones are kept")
                .isEqualTo(31);

        assertThat(new TerminalOptions(true, MouseMode.NONE, true, KeyboardEnhancements.NONE, false)
                        .keyboardEnhancements())
                .as("the legacy constructor asks for nothing")
                .isEqualTo(KeyboardEnhancements.NONE);
        assertThat(new TerminalOptions(true, MouseMode.NONE, true, KeyboardEnhancements.NONE, false).focusReporting())
                .isFalse();
        assertThat(TerminalOptions.DEFAULT.withFocusReporting(true).focusReporting())
                .isTrue();
        assertThat(TerminalOptions.DEFAULT.withKeyboardEnhancements(null).keyboardEnhancements())
                .isEqualTo(KeyboardEnhancements.NONE);
        assertThat(TerminalOptions.DEFAULT)
                .isEqualTo(new TerminalOptions(true, MouseMode.NONE, true, KeyboardEnhancements.NONE, false));

        assertThat(KeyEventType.fromCode(1)).isEqualTo(KeyEventType.PRESS);
        assertThat(KeyEventType.fromCode(2)).isEqualTo(KeyEventType.REPEAT);
        assertThat(KeyEventType.fromCode(3)).isEqualTo(KeyEventType.RELEASE);
        assertThat(KeyEventType.fromCode(4)).isNull();
        assertThat(KeyEventType.RELEASE.code()).isEqualTo(3);
    }

    @Test
    @Timeout(20)
    void aMalformedFloodStillTerminatesBounded() {
        // Bounded sequence length plus validated fields: hostile input decodes to events or nothing,
        // never to an unbounded buffer or an exception out of the decoder.
        StringBuilder flood = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            flood.append("\u001B[").append("9".repeat(i % 90)).append(i % 3 == 0 ? "u" : ";x");
        }
        try {
            // A clean byte first, so the assertion does not depend on where the flood ends.
            List<InputEvent> events = decodeAll("z" + flood);
            assertThat(((InputEvent.KeyInput) events.get(0)).text()).isEqualTo("z");
            assertThat(events.size())
                    .as("bounded: one event per terminated sequence at most")
                    .isLessThan(400);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
}

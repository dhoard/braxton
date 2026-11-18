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

import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.clipboard.ClipboardConfig;
import io.github.dhoard.braxton.clipboard.ClipboardPath;
import io.github.dhoard.braxton.clipboard.ClipboardReadResult;
import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.clipboard.ClipboardSelection;
import io.github.dhoard.braxton.clipboard.TmuxStrategy;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.TerminalReply;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.internal.clipboard.Osc52;
import io.github.dhoard.braxton.internal.clipboard.TerminalEnvironment;
import io.github.dhoard.braxton.internal.clipboard.TmuxClipboard;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.input.TestReaders;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.ScopeManager;
import io.github.dhoard.braxton.internal.runtime.TerminalQueries;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 19 acceptance: the terminal and primary clipboards, read and written on request.
 *
 * <p>The decode cases are byte-for-byte: a reply is the one place terminal bytes become text an
 * application may paste, so every malformed shape is proved to produce a typed refusal rather than
 * text. The correlation cases drive the shipped {@link TerminalQueries} engine with manual timers, so
 * a one-second reply deadline is tested in microseconds. The integration cases run the shipped
 * runtime over an owned stream terminal, so what is asserted is what an application sees: a message,
 * and the exact bytes on the wire.</p>
 */
@Timeout(20)
class ParityClipboardTest {

    private static final String SYSTEM_REQUEST = "\u001B]52;c;?\u0007";

    private static final String PRIMARY_REQUEST = "\u001B]52;p;?\u0007";

    /** The reply a terminal gives for the system clipboard, as the protocol writes it. */
    private static String reply(ClipboardSelection selection, String text) {
        return "\u001B]52;" + selection.code() + ";"
                + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "\u0007";
    }

    // ── decoding one reply ──────────────────────────────────────────────────

    @Test
    void aReplyIsDecodedOnlyForTheSelectionItNames() {
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;aGVsbG8=", 1_024))
                .isEqualTo(new Osc52.Decoded.Text("hello", 5));
        assertThat(Osc52.decode(ClipboardSelection.PRIMARY, "p;aGVsbG8=", 1_024))
                .as("the selection field is what attributes a reply")
                .isEqualTo(new Osc52.Decoded.Text("hello", 5));
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, ";aGVsbG8=", 1_024))
                .as("an omitted selection is the protocol's default, which is the system clipboard")
                .isEqualTo(new Osc52.Decoded.Text("hello", 5));
        assertThat(Osc52.decode(ClipboardSelection.PRIMARY, ";aGVsbG8=", 1_024))
                .as("and it does not answer a primary read")
                .isInstanceOf(Osc52.Decoded.Invalid.class);
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "p;aGVsbG8=", 1_024))
                .as("a reply for the other selection is not text this read may use")
                .isInstanceOf(Osc52.Decoded.Invalid.class);
    }

    @Test
    void aReplyThatNamesNoSelectionOrSeveralIsInvalid() {
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "aGVsbG8=", 1_024))
                .as("no selection marker at all")
                .isInstanceOf(Osc52.Decoded.Invalid.class);
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "cp;aGVsbG8=", 1_024))
                .as("two selection characters")
                .isInstanceOf(Osc52.Decoded.Invalid.class);
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "x;aGVsbG8=", 1_024))
                .as("a selection this library does not address")
                .isInstanceOf(Osc52.Decoded.Invalid.class);
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;c;aGVsbG8=", 1_024))
                .as("a second marker inside the payload is not base64")
                .isInstanceOf(Osc52.Decoded.Invalid.class);
    }

    @Test
    void anEmptySelectionIsASuccessfulReadOfNothingAndARefusalIsNot() {
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;", 1_024))
                .as("the terminal answered, and the selection is empty")
                .isEqualTo(new Osc52.Decoded.Text("", 0));
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;?", 1_024))
                .as("a terminal that declines says so, which is not an empty clipboard")
                .isEqualTo(new Osc52.Decoded.Refused());
    }

    @Test
    void aPayloadThatIsNotBase64OrNotUtf8IsInvalid() {
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;!!!!", 1_024)).isInstanceOf(Osc52.Decoded.Invalid.class);
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;aGVsbG8", 1_024))
                .as("unpadded base64 is not the encoding OSC 52 defines")
                .isInstanceOf(Osc52.Decoded.Invalid.class);
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;a GVsbG8=", 1_024))
                .isInstanceOf(Osc52.Decoded.Invalid.class);
        // 0xC3 0x28 is a malformed two-byte sequence; 0xED 0xA0 0x80 encodes a surrogate, which UTF-8
        // forbids outright. Neither may become text.
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;" + base64(0xC3, 0x28), 1_024))
                .isInstanceOf(Osc52.Decoded.Invalid.class);
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;" + base64(0xED, 0xA0, 0x80), 1_024))
                .isInstanceOf(Osc52.Decoded.Invalid.class);
    }

    @Test
    void aPayloadBeyondTheLimitIsOversizeRatherThanTruncated() {
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;aGVsbG8=", 4))
                .as("five decoded bytes do not fit a four-byte limit")
                .isEqualTo(new Osc52.Decoded.Oversize(4));
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;" + base64(new byte[64]), 8))
                .as("a payload far past the limit is refused before it is decoded")
                .isEqualTo(new Osc52.Decoded.Oversize(8));
        assertThat(Osc52.decode(ClipboardSelection.SYSTEM, "c;aGk=", 2))
                .as("a payload that just fits is still text")
                .isEqualTo(new Osc52.Decoded.Text("hi", 2));
    }

    @Test
    void theEncodedBoundIsOneWholeBase64GroupPerThreeBytes() {
        assertThat(Osc52.encodedLength(0)).isZero();
        assertThat(Osc52.encodedLength(1)).isEqualTo(4);
        assertThat(Osc52.encodedLength(3)).isEqualTo(4);
        assertThat(Osc52.encodedLength(4)).isEqualTo(8);
        assertThat(Osc52.encodedLength(1_048_576)).isEqualTo(1_398_104);
        assertThat(ClipboardConfig.defaults().maxReadEncodedCharacters())
                .as("the field carries the selection and its separator too")
                .isEqualTo(1_398_106);
    }

    @Test
    void aRequestNamesTheSelectionAndWritesNothingElse() {
        assertThat(Osc52.request(ClipboardSelection.SYSTEM)).isEqualTo(SYSTEM_REQUEST);
        assertThat(Osc52.request(ClipboardSelection.PRIMARY)).isEqualTo(PRIMARY_REQUEST);
        assertThat(Osc52.request(null))
                .as("a missing selection reads the system clipboard")
                .isEqualTo(SYSTEM_REQUEST);
        assertThat(Osc52.selectionField("c;aGVsbG8=")).isEqualTo("c");
        assertThat(Osc52.selectionField("nonsense")).isEmpty();
    }

    @Test
    void aWriteNamesTheSelectionItWasGiven() {
        assertThat(Osc52.encode(ClipboardSelection.SYSTEM, "hi", 1_024).sequence())
                .isEqualTo("\u001B]52;c;aGk=\u0007");
        assertThat(Osc52.encode(ClipboardSelection.PRIMARY, "hi", 1_024).sequence())
                .isEqualTo("\u001B]52;p;aGk=\u0007");
        assertThat(Osc52.encode((ClipboardSelection) null, "hi", 1_024).sequence())
                .as("a missing selection writes the system clipboard")
                .isEqualTo("\u001B]52;c;aGk=\u0007");
    }

    // ── framing one reply ───────────────────────────────────────────────────

    @Test
    void aClipboardReplyIsFramedWithEitherTerminatorAndEveryChunkBoundary() throws IOException {
        assertThat(frame(reply(ClipboardSelection.SYSTEM, "hello"), false))
                .isEqualTo(new TerminalReply.OperatingSystem(52, "c;aGVsbG8="));
        assertThat(frame("\u001B]52;c;aGVsbG8=\u001B\\", true))
                .as("ST terminates an OSC exactly as BEL does, even one byte at a time")
                .isEqualTo(new TerminalReply.OperatingSystem(52, "c;aGVsbG8="));
    }

    @Test
    void aClipboardReplyBeyondTheFramingBoundIsReportedRatherThanDropped() throws IOException {
        InputDecoder decoder = decoder(reply(ClipboardSelection.SYSTEM, "hello"), 8);
        assertThat(decoder.read().orElseThrow())
                .as("the payload is dropped, but the frame still says which selection it answered for")
                .isEqualTo(new InputEvent.TerminalReplyInput(new TerminalReply.Overlong(52, "c;aGVsbG", 8)));
        assertThat(decoder.read())
                .as("the sequence was consumed through its terminator, so nothing of it is left over")
                .isEmpty();
    }

    @Test
    void everyOtherReplyKeepsTheSmallerBound() throws IOException {
        StringBuilder overlong = new StringBuilder("\u001B]11;");
        overlong.append("0".repeat(InputDecoder.MAX_REPLY_CHARACTERS + 16));
        overlong.append("\u0007z");
        InputDecoder decoder = new InputDecoder(
                TestReaders.utf8(new ByteArrayInputStream(overlong.toString().getBytes(StandardCharsets.UTF_8))));
        InputEvent after = decoder.read().orElseThrow();
        assertThat(((InputEvent.KeyInput) after).text())
                .as("a colour report past the reply bound is consumed and dropped as before")
                .isEqualTo("z");
    }

    @Test
    void theHeadOfAnOverlongReplyIsWhatAttributesIt() throws IOException {
        InputDecoder decoder = decoder("\u001B]52;p;" + base64(new byte[64]) + "\u0007", 4);
        TerminalReply reply = ((InputEvent.TerminalReplyInput) decoder.read().orElseThrow()).reply();
        assertThat(reply).isEqualTo(new TerminalReply.Overlong(52, "p;AA", 4));
        assertThat(Osc52.selectionField(((TerminalReply.Overlong) reply).head()))
                .as("the head names the selection the dropped payload answered for")
                .isEqualTo("p");
    }

    // ── correlating replies ─────────────────────────────────────────────────

    /** An engine wired to an owned timer, a recorded output and a delivered-answer list. */
    private static final class Engine {
        final ManualTimers timers = new ManualTimers();
        final List<String> written = new CopyOnWriteArrayList<>();
        final List<TerminalQuery.Reply> delivered = new CopyOnWriteArrayList<>();
        final ScopeManager scopes = new ScopeManager();
        final TerminalQueries<TerminalQuery.Reply> queries;

        Engine() {
            this(1_024);
        }

        Engine(int maxClipboardReadBytes) {
            queries = new TerminalQueries<>(
                    written::add,
                    timers,
                    (reply, origin) -> delivered.add(reply),
                    () -> new TerminalQuery.Reply.Profile(ColorProfile.TRUECOLOR, false),
                    message -> {},
                    4,
                    Duration.ofSeconds(1),
                    maxClipboardReadBytes);
            queries.bindScopes(scopes);
        }

        void ask(String id, ClipboardSelection selection) {
            ask(id, new TerminalQuery.ClipboardRead(selection));
        }

        void ask(String id, TerminalQuery query) {
            queries.submit(EffectId.of(id), query, reply -> reply, ComponentPath.ROOT);
        }

        void advance(Duration elapsed) {
            timers.advance(elapsed.toNanos());
        }
    }

    @Test
    void aClipboardReadIsAnsweredWithTheTextTheTerminalReported() {
        Engine engine = new Engine();
        engine.ask("read", ClipboardSelection.SYSTEM);
        assertThat(engine.written).containsExactly(SYSTEM_REQUEST);

        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;aGVsbG8="));
        assertThat(engine.delivered)
                .containsExactly(new TerminalQuery.Reply.Clipboard(ClipboardSelection.SYSTEM, "hello", 5));
        assertThat(engine.queries.live()).isZero();
    }

    @Test
    void bothSelectionsAreOnTheWireAtOnceAndAttributedExactly() {
        Engine engine = new Engine();
        engine.ask("system", ClipboardSelection.SYSTEM);
        engine.ask("primary", ClipboardSelection.PRIMARY);
        assertThat(engine.written)
                .as("the two replies name different selections, so both questions can be outstanding")
                .containsExactly(SYSTEM_REQUEST, PRIMARY_REQUEST);

        engine.queries.accept(new TerminalReply.OperatingSystem(52, "p;cHJpbWFyeQ=="));
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;c3lzdGVt"));
        assertThat(engine.delivered)
                .containsExactly(
                        new TerminalQuery.Reply.Clipboard(ClipboardSelection.PRIMARY, "primary", 7),
                        new TerminalQuery.Reply.Clipboard(ClipboardSelection.SYSTEM, "system", 6));
    }

    @Test
    void twoReadsOfOneSelectionAreSerializedAndAnsweredInOrder() {
        Engine engine = new Engine();
        engine.ask("first", ClipboardSelection.SYSTEM);
        engine.ask("second", ClipboardSelection.SYSTEM);
        assertThat(engine.written)
                .as("two replies for one selection are indistinguishable, so the second question waits")
                .containsExactly(SYSTEM_REQUEST);

        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;b25l"));
        assertThat(engine.written).containsExactly(SYSTEM_REQUEST, SYSTEM_REQUEST);
        assertThat(engine.delivered)
                .containsExactly(new TerminalQuery.Reply.Clipboard(ClipboardSelection.SYSTEM, "one", 3));

        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;dHdv"));
        assertThat(engine.delivered).hasSize(2);
        assertThat(engine.delivered.get(1))
                .isEqualTo(new TerminalQuery.Reply.Clipboard(ClipboardSelection.SYSTEM, "two", 3));
    }

    @Test
    void aReplyForAnotherSelectionSatisfiesNothing() {
        Engine engine = new Engine();
        engine.ask("read", ClipboardSelection.SYSTEM);
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "p;aGk="));
        assertThat(engine.delivered)
                .as("a primary reply is not an answer to a system read")
                .isEmpty();

        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;aGk="));
        assertThat(engine.delivered)
                .containsExactly(new TerminalQuery.Reply.Clipboard(ClipboardSelection.SYSTEM, "hi", 2));
    }

    @Test
    void anUnrequestedClipboardReplyIsDiscarded() {
        Engine engine = new Engine();
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;aGk="));
        engine.queries.accept(new TerminalReply.Overlong(52, "c;AAAA", 4));
        assertThat(engine.delivered).isEmpty();
        assertThat(engine.written)
                .as("an unsolicited reply never made the engine write anything")
                .isEmpty();
    }

    @Test
    void aClipboardReadThatIsNeverAnsweredIsATimeout() {
        Engine engine = new Engine();
        engine.ask("read", ClipboardSelection.PRIMARY);
        engine.advance(Duration.ofSeconds(1));
        assertThat(engine.delivered)
                .containsExactly(
                        new TerminalQuery.Reply.Timeout(new TerminalQuery.ClipboardRead(ClipboardSelection.PRIMARY)));
        assertThat(engine.queries.live()).isZero();
    }

    @Test
    void aWithdrawnReadIsNotAnsweredByALateReply() {
        Engine engine = new Engine();
        engine.ask("read", ClipboardSelection.SYSTEM);
        engine.queries.cancel(EffectId.of("read"));
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;aGk="));
        assertThat(engine.delivered).isEmpty();
        assertThat(engine.queries.live()).isZero();
    }

    @Test
    void aPayloadThatCannotBeBelievedResolvesTheReadAsMalformed() {
        Engine engine = new Engine();
        engine.ask("read", ClipboardSelection.SYSTEM);
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;!!!!"));
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.delivered.get(0)).isInstanceOf(TerminalQuery.Reply.Malformed.class);

        engine.ask("again", ClipboardSelection.SYSTEM);
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "p;aGk="));
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;aGk="));
        assertThat(engine.delivered)
                .as("the query was resolved, so the read that followed it is answered separately")
                .hasSize(2);
    }

    @Test
    void aRefusedSelectionIsUnsupportedRatherThanEmptyText() {
        Engine engine = new Engine();
        engine.ask("read", ClipboardSelection.SYSTEM);
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;?"));
        assertThat(engine.delivered)
                .containsExactly(new TerminalQuery.Reply.Unsupported(
                        new TerminalQuery.ClipboardRead(ClipboardSelection.SYSTEM)));
    }

    @Test
    void aPayloadPastTheSessionLimitIsOversize() {
        Engine engine = new Engine(4);
        engine.ask("read", ClipboardSelection.SYSTEM);
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;aGVsbG8="));
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.delivered.get(0))
                .isEqualTo(new TerminalQuery.Reply.Oversize(
                        new TerminalQuery.ClipboardRead(ClipboardSelection.SYSTEM), 4));

        engine.ask("dropped", ClipboardSelection.SYSTEM);
        engine.queries.accept(new TerminalReply.Overlong(52, "c;AAAAAA", 4));
        assertThat(engine.delivered.get(1))
                .as("a reply the framing refused is the same answer, not a timeout")
                .isEqualTo(new TerminalQuery.Reply.Oversize(
                        new TerminalQuery.ClipboardRead(ClipboardSelection.SYSTEM), 4));
    }

    @Test
    void aPassthroughReadIsWrappedForTmuxAndEveryOtherTransportIsNot() {
        Engine engine = new Engine();
        engine.ask("plain", new TerminalQuery.ClipboardRead(ClipboardSelection.SYSTEM, ClipboardPath.TMUX_NATIVE));
        engine.ask(
                "wrapped", new TerminalQuery.ClipboardRead(ClipboardSelection.PRIMARY, ClipboardPath.TMUX_PASSTHROUGH));
        assertThat(engine.written).containsExactly(SYSTEM_REQUEST, TmuxClipboard.passthrough(PRIMARY_REQUEST));
    }

    @Test
    void aReadIsAKindOfItsOwnAndCarriesItsSelection() {
        TerminalQuery.ClipboardRead system = new TerminalQuery.ClipboardRead(ClipboardSelection.SYSTEM);
        TerminalQuery.ClipboardRead primary = new TerminalQuery.ClipboardRead(ClipboardSelection.PRIMARY);
        assertThat(system.kind()).isEqualTo("clipboard:c");
        assertThat(primary.kind()).isEqualTo("clipboard:p");
        assertThat(system.path())
                .as("a read written straight to the terminal is the default")
                .isEqualTo(ClipboardPath.OSC52_DIRECT);
        assertThatThrownBy(() -> new TerminalQuery.ClipboardRead(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalQuery.Reply.Oversize(null, 4))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── the per-session service ─────────────────────────────────────────────

    /** A service over an owned environment, with every write recorded. */
    private static final class Service {
        final List<String> written = new ArrayList<>();
        final ClipboardService clipboard;

        Service(ClipboardConfig config, Map<String, String> environment) {
            clipboard = new ClipboardService(config, new TerminalEnvironment(environment), written::add);
        }
    }

    private static ClipboardConfig direct() {
        return ClipboardConfig.builder().tmuxStrategy(TmuxStrategy.DIRECT).build();
    }

    @Test
    void aPrimaryCopyNamesThePrimarySelection() {
        Service service = new Service(direct(), Map.of("TERM", "xterm-256color"));
        ClipboardResult result = service.clipboard.copy(ClipboardSelection.PRIMARY, "hi");

        assertThat(service.written).containsExactly("\u001B]52;p;aGk=\u0007");
        assertThat(result.path()).isEqualTo(ClipboardPath.OSC52_DIRECT);
        assertThat(result.systemWriteRequested()).isTrue();
        assertThat(result.storedInternally()).isTrue();
        assertThat(service.clipboard.internalText())
                .as("both selections share one in-process buffer, which is what a paste reads")
                .isEqualTo("hi");
    }

    @Test
    void eachSelectionGetsItsOwnSequenceAndAnIdenticalOneIsDeduplicated() {
        Service service = new Service(direct(), Map.of("TERM", "xterm-256color"));
        service.clipboard.copy(ClipboardSelection.SYSTEM, "hi");
        service.clipboard.copy(ClipboardSelection.PRIMARY, "hi");
        assertThat(service.written)
                .as("the two selections are separate destinations, so neither write replaces the other")
                .containsExactly("\u001B]52;c;aGk=\u0007", "\u001B]52;p;aGk=\u0007");

        ClipboardResult repeat = service.clipboard.copy(ClipboardSelection.SYSTEM, "hi");
        assertThat(repeat.deduplicated())
                .as("the same selection with the same text is not written twice")
                .isTrue();
        assertThat(service.written).hasSize(2);
    }

    @Test
    void aPassthroughSessionWrapsAReadAndAWriteTheSameWay() {
        Service service = new Service(
                ClipboardConfig.builder()
                        .tmuxStrategy(TmuxStrategy.TMUX_PASSTHROUGH)
                        .build(),
                Map.of("TMUX", "/tmp/tmux"));
        service.clipboard.copy(ClipboardSelection.PRIMARY, "hi");
        assertThat(service.written).containsExactly(TmuxClipboard.passthrough("\u001B]52;p;aGk=\u0007"));
        assertThat(service.clipboard.readPath()).isEqualTo(ClipboardPath.TMUX_PASSTHROUGH);
    }

    @Test
    void aSessionThatCannotWriteCannotAsk() {
        Service off = new Service(ClipboardConfig.builder().osc52Enabled(false).build(), Map.of("TERM", "xterm"));
        assertThat(off.clipboard.readPath())
                .as("a read is a write of a question, so it needs the same transport")
                .isNull();
        assertThat(off.clipboard.copy(ClipboardSelection.SYSTEM, "hi").path()).isEqualTo(ClipboardPath.INTERNAL_ONLY);
        assertThat(off.written).isEmpty();

        Service dumb = new Service(direct(), Map.of("TERM", "dumb"));
        assertThat(dumb.clipboard.readPath()).isNull();

        Service strategyOff = new Service(
                ClipboardConfig.builder().tmuxStrategy(TmuxStrategy.OFF).build(), Map.of("TERM", "xterm"));
        assertThat(strategyOff.clipboard.readPath()).isNull();
    }

    @Test
    void readsCanBeTurnedOffWithoutTurningOffCopies() {
        Service service = new Service(
                ClipboardConfig.builder()
                        .terminalReadsEnabled(false)
                        .tmuxStrategy(TmuxStrategy.DIRECT)
                        .build(),
                Map.of("TERM", "xterm-256color"));
        assertThat(service.clipboard.readPath()).isNull();
        service.clipboard.copy(ClipboardSelection.SYSTEM, "hi");
        assertThat(service.written)
                .as("a deployment may allow writing the clipboard while refusing to read what is in it")
                .containsExactly("\u001B]52;c;aGk=\u0007");
    }

    @Test
    void aWriterThatFailsStillStoresTheText() {
        ClipboardService clipboard =
                new ClipboardService(direct(), new TerminalEnvironment(Map.of("TERM", "xterm-256color")), sequence -> {
                    throw new IllegalStateException("broken output");
                });
        ClipboardResult result = clipboard.copy(ClipboardSelection.SYSTEM, "hi");
        assertThat(result.path()).isEqualTo(ClipboardPath.INTERNAL_ONLY);
        assertThat(result.systemWriteRequested())
                .as("a write that failed is never reported as requested")
                .isFalse();
        assertThat(clipboard.internalText()).isEqualTo("hi");
    }

    // ── the mounted runtime ─────────────────────────────────────────────────

    /** A program that records every message the runtime delivers to it. */
    private static Component<List<Object>, Object, Never> recorder(List<Object> messages, Command<Object> onInit) {
        return new Component<>() {
            @Override
            public Update<List<Object>, Object, Never> init(InitContext context) {
                return Update.next(messages, onInit);
            }

            @Override
            public Update<List<Object>, Object, Never> update(List<Object> model, Object message) {
                model.add(message);
                if (message instanceof ClipboardReadResult || message instanceof ClipboardResult) {
                    return Update.next(model, Command.quit());
                }
                return Update.next(model);
            }

            @Override
            public View<Object> view(List<Object> model, ViewContext context) {
                return Views.text("ready");
            }
        };
    }

    private static RuntimeOptions clipboardOptions(ClipboardConfig config) {
        return RuntimeOptions.defaults().withClipboard(config);
    }

    @Test
    void aReadAskedByTheApplicationIsAnsweredThroughTheRuntime() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            fixture.onOutput(text -> {
                if (text.contains(SYSTEM_REQUEST)) {
                    fixture.feed(reply(ClipboardSelection.SYSTEM, "copied elsewhere"));
                }
            });
            TestTerminal.run(
                    recorder(
                            messages,
                            Command.readTerminalClipboard(
                                    EffectId.of("read"), ClipboardSelection.SYSTEM, result -> (Object) result)),
                    clipboardOptions(direct()),
                    fixture);

            assertThat(fixture.outputText()).contains(SYSTEM_REQUEST);
            assertThat(messages)
                    .containsExactly(new ClipboardReadResult.Text(
                            ClipboardSelection.SYSTEM, ClipboardPath.OSC52_DIRECT, "copied elsewhere", 16));
        }
    }

    @Test
    void thePrimarySelectionReachesTheRuntimeAsItsOwnQuestion() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            fixture.onOutput(text -> {
                if (text.contains(PRIMARY_REQUEST)) {
                    fixture.feed(reply(ClipboardSelection.PRIMARY, "selected text"));
                }
            });
            TestTerminal.run(
                    recorder(
                            messages,
                            Command.readTerminalClipboard(
                                    EffectId.of("read"), ClipboardSelection.PRIMARY, result -> (Object) result)),
                    clipboardOptions(direct()),
                    fixture);

            assertThat(fixture.outputText())
                    .as("a primary read is the primary selection on the wire, never the system one")
                    .contains(PRIMARY_REQUEST);
            assertThat(messages)
                    .containsExactly(new ClipboardReadResult.Text(
                            ClipboardSelection.PRIMARY, ClipboardPath.OSC52_DIRECT, "selected text", 13));
        }
    }

    @Test
    void aSessionThatCannotAskReportsDisabledAndWritesNoClipboardBytes() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            fixture.endInput();
            TestTerminal.run(
                    recorder(
                            messages,
                            Command.readTerminalClipboard(
                                    EffectId.of("read"), ClipboardSelection.SYSTEM, result -> (Object) result)),
                    clipboardOptions(
                            ClipboardConfig.builder().osc52Enabled(false).build()),
                    fixture);

            assertThat(fixture.outputText())
                    .as("the session has no way to ask, so it must not write a question either")
                    .doesNotContain("52;c;?");
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0)).isInstanceOf(ClipboardReadResult.Disabled.class);
        }
    }

    @Test
    void aReadThatIsNeverAnsweredIsReportedAsATimeout() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            // The reply deadline is a real one here, so the run ends on its own after a second.
            TestTerminal.run(
                    recorder(
                            messages,
                            Command.readTerminalClipboard(
                                    EffectId.of("read"), ClipboardSelection.SYSTEM, result -> (Object) result)),
                    clipboardOptions(direct()),
                    fixture,
                    io.github.dhoard.braxton.internal.runtime.RuntimeServices.system());

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0))
                    .isEqualTo(new ClipboardReadResult.Timeout(ClipboardSelection.SYSTEM, ClipboardPath.OSC52_DIRECT));
        }
    }

    @Test
    void aReplyNobodyAskedForNeverReachesTheApplication() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            fixture.feed(reply(ClipboardSelection.SYSTEM, "not asked for"));
            fixture.endInput();
            Component<List<Object>, Object, Never> program = recorder(messages, Command.none());
            TestTerminal.run(program, clipboardOptions(direct()), fixture);

            assertThat(messages).noneMatch(message -> message instanceof ClipboardReadResult);
            assertThat(fixture.outputText())
                    .as("an unsolicited reply neither answered anything nor asked anything")
                    .doesNotContain("52;");
        }
    }

    @Test
    void aCopyThroughTheRuntimeReportsItsSelectionAndWritesIt() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            fixture.endInput();
            TestTerminal.run(
                    recorder(messages, Command.copy(ClipboardSelection.PRIMARY, "hi", result -> (Object) result)),
                    clipboardOptions(direct()),
                    fixture);

            assertThat(fixture.outputText()).contains("\u001B]52;p;aGk=\u0007").doesNotContain("52;c;");
            assertThat(messages).hasSize(1);
            ClipboardResult result = (ClipboardResult) messages.get(0);
            assertThat(result.path()).isEqualTo(ClipboardPath.OSC52_DIRECT);
            assertThat(result.systemWriteRequested()).isTrue();
        }
    }

    @Test
    void theInProcessReadIsUnchangedByTheNewSelectionAwareCommands() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            Component<List<Object>, Object, Never> program = new Component<>() {
                @Override
                public Update<List<Object>, Object, Never> init(InitContext context) {
                    return Update.next(
                            messages,
                            Command.batch(List.of(
                                    Command.copy(ClipboardSelection.SYSTEM, "hi", result -> (Object) result),
                                    Command.readClipboard(text -> (Object) ("internal=" + text)))));
                }

                @Override
                public Update<List<Object>, Object, Never> update(List<Object> model, Object message) {
                    model.add(message);
                    if (model.size() >= 2) {
                        return Update.next(model, Command.quit());
                    }
                    return Update.next(model);
                }

                @Override
                public View<Object> view(List<Object> model, ViewContext context) {
                    return Views.text("ready");
                }
            };
            TestTerminal.run(program, clipboardOptions(direct()), fixture);

            assertThat(messages).contains("internal=hi");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** The base64 of raw bytes, which is how a malformed payload is built. */
    private static String base64(int... bytes) {
        byte[] raw = new byte[bytes.length];
        for (int index = 0; index < bytes.length; index++) {
            raw[index] = (byte) bytes[index];
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** One decoder over {@code bytes}, with an explicit clipboard framing bound. */
    private static InputDecoder decoder(String bytes, int maxClipboardReplyCharacters) {
        return new InputDecoder(
                TestReaders.utf8(new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8))),
                io.github.dhoard.braxton.input.InputOptions.DEFAULT,
                () -> false,
                maxClipboardReplyCharacters);
    }

    /** The one frame {@code bytes} produces, read from a stream that may deliver it a byte at a time. */
    private static TerminalReply frame(String bytes, boolean byteAtATime) throws IOException {
        InputStream input = byteAtATime
                ? new Drip(bytes.getBytes(StandardCharsets.UTF_8))
                : new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8));
        Optional<InputEvent> event = new InputDecoder(TestReaders.utf8(input)).read();
        assertThat(event).isPresent();
        assertThat(event.get()).isInstanceOf(InputEvent.TerminalReplyInput.class);
        return ((InputEvent.TerminalReplyInput) event.get()).reply();
    }

    /** A stream that hands out one byte per read, so framing cannot depend on a buffer boundary. */
    private static final class Drip extends InputStream {

        private final byte[] bytes;
        private int index;

        Drip(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            return index < bytes.length ? bytes[index++] & 0xFF : -1;
        }
    }
}

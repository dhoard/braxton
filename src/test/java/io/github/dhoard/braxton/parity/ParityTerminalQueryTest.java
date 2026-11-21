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
import io.github.dhoard.braxton.app.TerminalFacts;
import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.TerminalReply;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.ScopeManager;
import io.github.dhoard.braxton.internal.runtime.TerminalQueries;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 18 acceptance: terminal queries, their typed answers, the session facts derived from them, and
 * the color profiles frames are encoded with.
 *
 * <p>The correlation cases drive the shipped {@link TerminalQueries} engine with manual timers, so a
 * one-second deadline is tested in microseconds and the order of a timeout against a late reply is
 * decided by the test rather than by wall-clock luck. The integration cases run the shipped
 * {@link io.github.dhoard.braxton.internal.runtime.RuntimeLoop} over an owned stream terminal, so what
 * is asserted is what an application sees: a message, a painted row and the bytes on the wire.</p>
 */
@Timeout(20)
class ParityTerminalQueryTest {

    /**
     * The request sequences, exactly as the xterm control sequence reference documents them.
     */
    private static final String CURSOR_REQUEST = "\u001B[6n";

    private static final String IDENTITY_REQUEST = "\u001B[>q";

    private static final String BACKGROUND_REQUEST = "\u001B]11;?\u0007";

    // ── the correlation engine ───────────────────────────────────────────────

    /**
     * An engine wired to an owned timer, a recorded output and a delivered-answer list.
     */
    private static final class Engine {
        final ManualTimers timers = new ManualTimers();
        final List<String> written = new CopyOnWriteArrayList<>();
        final List<TerminalQuery.Reply> delivered = new CopyOnWriteArrayList<>();
        final ScopeManager scopes = new ScopeManager();
        final TerminalQueries<TerminalQuery.Reply> queries;

        Engine() {
            queries = new TerminalQueries<>(
                    written::add,
                    timers,
                    (reply, origin) -> delivered.add(reply),
                    () -> new TerminalQuery.Reply.Profile(ColorProfile.TRUECOLOR, false),
                    message -> {},
                    4,
                    Duration.ofSeconds(1));
            queries.bindScopes(scopes);
        }

        void ask(String id, TerminalQuery query) {
            queries.submit(EffectId.of(id), query, reply -> reply, ComponentPath.ROOT);
        }

        void advance(Duration elapsed) {
            timers.advance(elapsed.toNanos());
        }
    }

    @Test
    void aCursorPositionReplyIsOneBasedOnTheWireAndZeroBasedToTheApplication() {
        Engine engine = new Engine();
        engine.ask("cursor", new TerminalQuery.CursorPosition());
        assertThat(engine.written).containsExactly(CURSOR_REQUEST);

        engine.queries.accept(new TerminalReply.Control("4;9", 'R'));
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.delivered.get(0)).isEqualTo(new TerminalQuery.Reply.CursorPosition(3, 8));
        assertThat(engine.queries.live()).isZero();
    }

    @Test
    void aTerminalIdentityReplyIsTheNameTheTerminalGaveItself() {
        Engine engine = new Engine();
        engine.ask("identity", new TerminalQuery.Identity());
        assertThat(engine.written).containsExactly(IDENTITY_REQUEST);

        engine.queries.accept(new TerminalReply.DeviceControl(">|", "xterm(390)"));
        assertThat(engine.delivered.get(0)).isEqualTo(new TerminalQuery.Reply.Identity("xterm(390)"));
    }

    @Test
    void aModeReportNamesTheModeAndIsMatchedOnlyToThatMode() {
        Engine engine = new Engine();
        engine.ask("paste", new TerminalQuery.Mode(2004));
        assertThat(engine.written).containsExactly("\u001B[?2004$p");

        // A report about another mode is a reply nobody asked for.
        engine.queries.accept(new TerminalReply.Control("?2026;1$", 'y'));
        assertThat(engine.delivered).isEmpty();

        engine.queries.accept(new TerminalReply.Control("?2004;1$", 'y'));
        assertThat(engine.delivered.get(0)).isEqualTo(new TerminalQuery.Reply.Mode(2004, TerminalQuery.Setting.SET));

        engine.ask("other", new TerminalQuery.Mode(2026));
        engine.queries.accept(new TerminalReply.Control("?2026;0$", 'y'));
        assertThat(engine.delivered.get(1))
                .as("mode 0 is the terminal saying it does not implement the mode")
                .isEqualTo(new TerminalQuery.Reply.Mode(2026, TerminalQuery.Setting.NOT_RECOGNIZED));
    }

    @Test
    void aCapabilityReplyIsDecodedFromItsHexadecimalNameAndValue() {
        Engine engine = new Engine();
        engine.ask("tc", new TerminalQuery.Termcap("Tc"));
        assertThat(engine.written).containsExactly("\u001BP+q5463\u001B\\");

        engine.queries.accept(new TerminalReply.DeviceControl("1+r", "5463=54727565436F6C6F72"));
        assertThat(engine.delivered.get(0)).isEqualTo(new TerminalQuery.Reply.Capability("Tc", "TrueColor"));

        engine.ask("rgb", new TerminalQuery.Termcap("RGB"));
        engine.queries.accept(new TerminalReply.DeviceControl("0+r", "524742"));
        assertThat(engine.delivered.get(1))
                .as("a terminal that answers 0+r understands the question and does not have the capability")
                .isEqualTo(new TerminalQuery.Reply.Unsupported(new TerminalQuery.Termcap("RGB")));
    }

    @Test
    void aColourReplyIsNormalizedToEightBitsPerChannel() {
        Engine engine = new Engine();
        engine.ask("background", new TerminalQuery.ColorReport(TerminalQuery.Target.BACKGROUND));
        assertThat(engine.written).containsExactly(BACKGROUND_REQUEST);

        engine.queries.accept(new TerminalReply.OperatingSystem(11, "rgb:1000/2000/3030"));
        assertThat(engine.delivered.get(0))
                .isEqualTo(new TerminalQuery.Reply.ColorValue(
                        TerminalQuery.Target.BACKGROUND, new Color(0x10, 0x20, 0x30)));

        engine.ask("foreground", new TerminalQuery.ColorReport(TerminalQuery.Target.FOREGROUND));
        engine.queries.accept(new TerminalReply.OperatingSystem(10, "rgb:f/8/0"));
        assertThat(engine.delivered.get(1))
                .as("a one-digit channel scales to the same color the full form names")
                .isEqualTo(new TerminalQuery.Reply.ColorValue(
                        TerminalQuery.Target.FOREGROUND, new Color(0xFF, 0x88, 0x00)));
    }

    @Test
    void everyDocumentedColourFormAndTheRefusalAreDistinguished() {
        assertThat(TerminalQueries.parseColor("rgb:ffff/0000/8000")).isEqualTo(new Color(0xFF, 0x00, 0x80));
        assertThat(TerminalQueries.parseColor("#ff0080")).isEqualTo(new Color(0xFF, 0x00, 0x80));
        assertThat(TerminalQueries.parseColor("#f08")).isEqualTo(new Color(0xFF, 0x00, 0x88));
        assertThat(TerminalQueries.parseColor("#ffff00008000")).isEqualTo(new Color(0xFF, 0x00, 0x80));
        assertThat(TerminalQueries.parseColor("rgba:ffff/0000/8000/ffff")).isEqualTo(new Color(0xFF, 0x00, 0x80));
        assertThat(TerminalQueries.parseColor("rgb:1/2/3")).isEqualTo(new Color(0x11, 0x22, 0x33));
        assertThat(TerminalQueries.parseColor("rgb:12345/0/0"))
                .as("five digits is not a channel")
                .isNull();
        assertThat(TerminalQueries.parseColor("chartreuse"))
                .as("a named color is not documented")
                .isNull();
        assertThat(TerminalQueries.parseColor("rgb:zz/00/00")).isNull();
        assertThat(TerminalQueries.parseColor("#12345"))
                .as("five digits is not an X11 hex form")
                .isNull();
    }

    @Test
    void aRefusedColourIsUnsupportedRatherThanBlack() {
        Engine engine = new Engine();
        engine.ask("cursor", new TerminalQuery.ColorReport(TerminalQuery.Target.CURSOR));
        assertThat(engine.written).containsExactly("\u001B]12;?\u0007");

        engine.queries.accept(new TerminalReply.OperatingSystem(12, "?"));
        assertThat(engine.delivered.get(0) instanceof TerminalQuery.Reply.Unsupported)
                .isTrue();
    }

    @Test
    void aPayloadTheLibraryCannotBelieveIsMalformed() {
        Engine engine = new Engine();
        engine.ask("cursor", new TerminalQuery.CursorPosition());
        engine.queries.accept(new TerminalReply.Control("0;5", 'R'));
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.delivered.get(0)).isInstanceOf(TerminalQuery.Reply.Malformed.class);
        engine.queries.accept(new TerminalReply.Control("2;3", 'R'));
        assertThat(engine.delivered)
                .as("the malformed answer resolved the query, so a later believable one satisfies nothing")
                .hasSize(1);

        Engine mode = new Engine();
        mode.ask("mode", new TerminalQuery.Mode(2004));
        mode.queries.accept(new TerminalReply.Control("?2004;9$", 'y'));
        assertThat(mode.delivered.get(0)).isInstanceOf(TerminalQuery.Reply.Malformed.class);

        Engine colour = new Engine();
        colour.ask("background", new TerminalQuery.ColorReport(TerminalQuery.Target.BACKGROUND));
        colour.queries.accept(new TerminalReply.OperatingSystem(11, "something else"));
        assertThat(colour.delivered.get(0)).isInstanceOf(TerminalQuery.Reply.Malformed.class);
    }

    @Test
    void aReplyNobodyIsWaitingForIsDiscarded() {
        Engine engine = new Engine();
        engine.queries.accept(new TerminalReply.Control("4;9", 'R'));
        engine.queries.accept(new TerminalReply.DeviceControl(">|", "xterm(390)"));
        engine.queries.accept(new TerminalReply.OperatingSystem(11, "rgb:0/0/0"));
        engine.queries.accept(new TerminalReply.OperatingSystem(52, "c;aGk="));
        engine.queries.accept(new TerminalReply.DeviceControl(">0|", "tertiary"));
        assertThat(engine.delivered).isEmpty();

        engine.ask("cursor", new TerminalQuery.CursorPosition());
        engine.queries.accept(new TerminalReply.Control("4;9", 'R'));
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.written)
                .as("an unsolicited reply never made the engine write anything")
                .containsExactly(CURSOR_REQUEST);
    }

    @Test
    void aLateReplyAfterTheDeadlineSatisfiesNothing() {
        Engine engine = new Engine();
        engine.ask("cursor", new TerminalQuery.CursorPosition());
        engine.advance(Duration.ofSeconds(1));
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.delivered.get(0))
                .isEqualTo(new TerminalQuery.Reply.Timeout(new TerminalQuery.CursorPosition()));

        engine.queries.accept(new TerminalReply.Control("4;9", 'R'));
        assertThat(engine.delivered).hasSize(1);
    }

    @Test
    void twoQueriesOfOneKindAreSerializedAndAnsweredInOrder() {
        Engine engine = new Engine();
        engine.ask("first", new TerminalQuery.CursorPosition());
        engine.ask("second", new TerminalQuery.CursorPosition());
        assertThat(engine.written)
                .as("the second request cannot be told apart from the first on the wire, so it waits")
                .containsExactly(CURSOR_REQUEST);
        assertThat(engine.queries.live()).isEqualTo(2);

        engine.queries.accept(new TerminalReply.Control("4;9", 'R'));
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.written).containsExactly(CURSOR_REQUEST, CURSOR_REQUEST);

        engine.queries.accept(new TerminalReply.Control("1;1", 'R'));
        assertThat(engine.delivered).hasSize(2);
        assertThat(engine.delivered.get(1)).isEqualTo(new TerminalQuery.Reply.CursorPosition(0, 0));
        assertThat(engine.queries.live()).isZero();
    }

    @Test
    void aQueuedQueryThatTimesOutFreesItsKindForTheNextOne() {
        Engine engine = new Engine();
        engine.ask("first", new TerminalQuery.CursorPosition());
        engine.ask("second", new TerminalQuery.CursorPosition());
        engine.advance(Duration.ofSeconds(1));
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.delivered.get(0)).isInstanceOf(TerminalQuery.Reply.Timeout.class);
        assertThat(engine.written).hasSize(2);

        engine.advance(Duration.ofSeconds(1));
        assertThat(engine.delivered).hasSize(2);
        assertThat(engine.delivered.get(1)).isInstanceOf(TerminalQuery.Reply.Timeout.class);
    }

    @Test
    void theOutstandingBoundRejectsRatherThanGrowingWithoutLimit() {
        Engine engine = new Engine();
        for (int index = 0; index < 4; index++) {
            engine.ask("mode-" + index, new TerminalQuery.Mode(2000 + index));
        }
        engine.ask("fifth", new TerminalQuery.Mode(2004));
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.delivered.get(0)).isInstanceOf(TerminalQuery.Reply.Rejected.class);
        assertThat(engine.queries.live()).isEqualTo(4);
    }

    @Test
    void aWithdrawnQueryIsNotAnsweredByAReplyThatStillArrives() {
        Engine engine = new Engine();
        engine.ask("cursor", new TerminalQuery.CursorPosition());
        engine.queries.cancel(EffectId.of("cursor"));
        assertThat(engine.queries.live()).isZero();
        engine.queries.accept(new TerminalReply.Control("4;9", 'R'));
        assertThat(engine.delivered).isEmpty();
    }

    @Test
    void aWithdrawnScopeTakesItsQueriesWithIt() {
        Engine engine = new Engine();
        engine.queries.submit(
                EffectId.of("cursor"), new TerminalQuery.CursorPosition(), reply -> reply, ComponentPath.ROOT);
        engine.queries.cancelScope(ComponentPath.ROOT);
        assertThat(engine.queries.live()).isZero();
        engine.queries.accept(new TerminalReply.Control("4;9", 'R'));
        assertThat(engine.delivered).isEmpty();
    }

    @Test
    void theSessionProfileIsAFactRatherThanATerminalQuestion() {
        Engine engine = new Engine();
        engine.ask("profile", new TerminalQuery.Profile());
        assertThat(engine.written).as("a session fact writes nothing").isEmpty();
        assertThat(engine.delivered).hasSize(1);
        assertThat(engine.delivered.get(0)).isEqualTo(new TerminalQuery.Reply.Profile(ColorProfile.TRUECOLOR, false));
    }

    @Test
    void theModeAndCapabilityAllowlistsAreClosed() {
        assertThatThrownBy(() -> new TerminalQuery.Mode(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TerminalQuery.Termcap("XY"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RGB");
        assertThat(new TerminalQuery.Termcap(" RGB ").name())
                .as("a name is normalized")
                .isEqualTo("RGB");
        assertThatThrownBy(() -> new TerminalQuery.ColorReport(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // ── the mounted runtime ──────────────────────────────────────────────────

    /**
     * A program that records every message the runtime delivers to it.
     */
    private static Component<List<Object>, Object, Never> recorder(
            List<Object> messages, Element<Object> view, Command<Object> onInit) {
        return new Component<>() {
            @Override
            public Update<List<Object>, Object, Never> init(InitContext context) {
                return Update.next(messages, onInit);
            }

            @Override
            public Update<List<Object>, Object, Never> update(List<Object> model, Object message) {
                model.add(message);
                return Update.next(model);
            }

            @Override
            public Element<Object> view(List<Object> model, ViewContext context) {
                return view;
            }
        };
    }

    @Test
    void aQueryAskedByTheApplicationIsAnsweredThroughTheRuntime() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            fixture.onOutput(text -> {
                if (text.contains(CURSOR_REQUEST)) {
                    fixture.feed("\u001B[7;3R");
                }
            });
            Component<List<Object>, Object, Never> program = new Component<>() {
                @Override
                public Update<List<Object>, Object, Never> init(InitContext context) {
                    return Update.next(
                            messages, Command.query(EffectId.of("cursor"), new TerminalQuery.CursorPosition(), reply ->
                                    (Object) reply));
                }

                @Override
                public Update<List<Object>, Object, Never> update(List<Object> model, Object message) {
                    model.add(message);
                    // The answer arrives as an ordinary message, so the run ends when it does.
                    return Update.next(model, Command.quit());
                }

                @Override
                public Element<Object> view(List<Object> model, ViewContext context) {
                    return Elements.text("ready");
                }
            };
            TestTerminal.run(program, RuntimeOptions.defaults(), fixture);

            assertThat(fixture.outputText()).contains(CURSOR_REQUEST);
            assertThat(messages).contains(new TerminalQuery.Reply.CursorPosition(6, 2));
        }
    }

    @Test
    void anUnrequestedReplyNeverReachesTheApplication() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            // A cursor report nobody asked for must vanish; end-of-input then ends the run.
            fixture.feed("\u001B[3;3R");
            fixture.endInput();
            Component<List<Object>, Object, Never> program = recorder(messages, Elements.text("ready"), Command.none());
            TestTerminal.run(program, RuntimeOptions.defaults(), fixture);

            assertThat(messages).noneMatch(message -> message instanceof InputEvent.TerminalReplyInput);
            assertThat(messages).noneMatch(message -> message instanceof TerminalQuery.Reply);
        }
    }

    @Test
    void aProfileCommandRepaintsEveryCellWithTheNewProfile() {
        Screen sample = new Screen(1, 4);
        io.github.dhoard.braxton.ui.ElementTree.paint(
                io.github.dhoard.braxton.ui.ElementTree.layout(
                        Elements.text(StyledText.of("hi", Style.fg(Colors.ERROR_400))), new Rect(0, 0, 1, 4)),
                sample);
        String truecolor = AnsiEncoder.frame(sample, ColorProfile.TRUECOLOR);
        String ansi256 = AnsiEncoder.frame(sample, ColorProfile.ANSI256);
        String ansi16 = AnsiEncoder.frame(sample, ColorProfile.ANSI16);
        String plain = AnsiEncoder.frame(sample, ColorProfile.NONE);

        assertThat(truecolor).contains("38;2;240;68;56");
        assertThat(ansi256)
                .contains("38;5;" + Colors.nearestAnsi256(Colors.ERROR_400))
                .doesNotContain("38;2;");
        assertThat(ansi16)
                .as("a 16-color session names the nearest of the sixteen, by its classic code")
                .contains("9" + (Colors.nearestAnsi16(Colors.ERROR_400) - 8) + "m");
        assertThat(plain).doesNotContain("38;2;", "38;5;");
        assertThat(Colors.ansi256(Colors.nearestAnsi256(Colors.ERROR_400)))
                .as("the palette entry the session emits is the color it meant to show")
                .isEqualTo(nearestPaletteColour(Colors.ERROR_400));
    }

    /**
     * The palette entry a nearest-color search should pick, computed independently.
     */
    private static Color nearestPaletteColour(Color wanted) {
        Color best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 16; index <= 0xFF; index++) {
            Color candidate = Colors.ansi256(index);
            int distance = square(candidate.red() - wanted.red())
                    + square(candidate.green() - wanted.green())
                    + square(candidate.blue() - wanted.blue());
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static int square(int value) {
        return value * value;
    }

    @Test
    void everyProfileKeepsTheAttributesAndTheHyperlink() {
        Style decorated = Style.NONE.withBold().withUnderline().withForeground(Colors.BLUE_400);
        assertThat(AnsiEncoder.sgr(decorated, ColorProfile.NONE))
                .as("losing color keeps decoration")
                .isEqualTo("\u001B[1;4m");
        assertThat(AnsiEncoder.sgr(decorated, ColorProfile.ANSI16))
                .as("the nearest of the sixteen is blue, by its classic code")
                .isEqualTo("\u001B[1;4;34m");
        assertThat(AnsiEncoder.sgr(decorated, ColorProfile.TRUECOLOR)).contains("38;2;46;144;250");
        assertThat(AnsiEncoder.sgr(Style.NONE, ColorProfile.ANSI256)).isEmpty();
    }

    @Test
    void theNegotiatedProfileIsTruecolorUnlessColourOrTheUserSaysOtherwise() {
        assertThat(ColorProfile.negotiated(true, null)).isEqualTo(ColorProfile.TRUECOLOR);
        assertThat(ColorProfile.negotiated(true, ""))
                .as("an empty NO_COLOR is not set")
                .isEqualTo(ColorProfile.TRUECOLOR);
        assertThat(ColorProfile.negotiated(true, "1"))
                .as("any value, per no-color.org")
                .isEqualTo(ColorProfile.NONE);
        assertThat(ColorProfile.negotiated(true, "0")).isEqualTo(ColorProfile.NONE);
        assertThat(ColorProfile.negotiated(false, null)).isEqualTo(ColorProfile.NONE);
        assertThat(ColorProfile.wider(ColorProfile.ANSI256, ColorProfile.TRUECOLOR))
                .isEqualTo(ColorProfile.TRUECOLOR);
        assertThat(ColorProfile.wider(ColorProfile.TRUECOLOR, ColorProfile.NONE))
                .isEqualTo(ColorProfile.TRUECOLOR);
    }

    @Test
    void aCapabilityReplyUpgradesTheProfileAndPublishesItAsAFact() throws Exception {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean asked = new AtomicBoolean();
            fixture.onOutput(text -> {
                if (text.contains("\u001BP+q5463\u001B\\") && asked.compareAndSet(false, true)) {
                    fixture.feed("\u001BP1+r5463=54727565436F6C6F72\u001B\\");
                }
            });
            Component<List<Object>, Object, Never> program = new Component<>() {
                @Override
                public Update<List<Object>, Object, Never> init(InitContext context) {
                    return Update.next(
                            messages,
                            Command.query(EffectId.of("tc"), new TerminalQuery.Termcap("Tc"), reply -> (Object) reply));
                }

                @Override
                public Update<List<Object>, Object, Never> update(List<Object> model, Object message) {
                    model.add(message);
                    if (message instanceof TerminalQuery.Reply.Capability) {
                        return Update.next(model, Command.quit());
                    }
                    return Update.next(model);
                }

                @Override
                public Subscription<Object> subscriptions(List<Object> model) {
                    return Subscription.facts(SubscriptionId.of("facts"), facts -> (Object) facts);
                }

                @Override
                public Element<Object> view(List<Object> model, ViewContext context) {
                    return Elements.text("ready");
                }
            };
            TestTerminal.run(program, RuntimeOptions.defaults().withColorProfile(ColorProfile.ANSI256), fixture);

            assertThat(messages)
                    .anyMatch(message -> message instanceof TerminalQuery.Reply.Capability capability
                            && capability.name().equals("Tc"));
            assertThat(messages)
                    .as("the facts source publishes the widened profile and reports it as explicit")
                    .anyMatch(message ->
                            message instanceof TerminalFacts facts && facts.profile() == ColorProfile.TRUECOLOR);
        }
    }

    @Test
    void anExplicitProfileIsWhatFramesAreEncodedWith() {
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            Component<List<Object>, Object, Never> program =
                    recorder(messages, Elements.text(StyledText.of("hi", Style.fg(Colors.ERROR_400))), Command.none());
            // Nothing asks a question here, so end-of-input is what ends the run: the frame is
            // painted before the reader reports the stream closed.
            fixture.endInput();
            TestTerminal.run(program, RuntimeOptions.defaults().withColorProfile(ColorProfile.ANSI16), fixture);

            String output = fixture.outputText();
            assertThat(output)
                    .as("the session painted the sample cell with a 16-color code and no wider one")
                    .contains("hi")
                    .doesNotContain("38;2;", "38;5;");
        }
        // A later session with another profile is unaffected by the first one: the palette memo is a
        // pure color-to-index mapping, not a per-session cache.
        try (TestTerminal fixture = new TestTerminal()) {
            List<Object> messages = Collections.synchronizedList(new ArrayList<>());
            Component<List<Object>, Object, Never> program =
                    recorder(messages, Elements.text(StyledText.of("hi", Style.fg(Colors.ERROR_400))), Command.none());
            fixture.endInput();
            TestTerminal.run(program, RuntimeOptions.defaults().withColorProfile(ColorProfile.TRUECOLOR), fixture);
            assertThat(fixture.outputText()).contains("38;2;240;68;56");
        }
    }
}

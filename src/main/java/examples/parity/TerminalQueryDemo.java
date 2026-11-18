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

package examples.parity;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.TerminalFacts;
import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Help;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 18 parity demo: terminal queries, the facts they report, and colour profiles.
 *
 * <p>Every query is asked on request, never at startup, and each answer is painted on a status row
 * exactly as the terminal gave it - including the answers that are not facts. A terminal that does not
 * implement a query shows {@code unsupported}; one that stays silent shows {@code timeout} after the
 * reply deadline. Nothing is invented in between: the demo has no fallback that would make a missing
 * answer look like an answer.</p>
 *
 * <ul>
 *   <li>{@code c} asks where the cursor is, {@code i} which terminal is attached, {@code m} whether
 *       bracketed paste is set, and {@code t} whether the terminal claims 24-bit colour.</li>
 *   <li>{@code b}, {@code f} and {@code k} ask for the background, foreground and cursor colours.
 *       The background is also the session's theme, so it moves the {@code theme=} field.</li>
 *   <li>{@code p} reports the colour profile in force. It is a session fact, not a terminal
 *       question, so it never reaches the terminal and never times out.</li>
 *   <li>{@code 1} to {@code 4} switch the profile the runtime paints with: none, 16 colours, 256
 *       colours and truecolor. The sample row is styled text, so switching repaints it in the
 *       profile's own SGR and a verifier can read the difference out of the transcript - exactly the
 *       switch the phase asks for, with no escape sequence in any content string.</li>
 *   <li>{@code a} asks every terminal question at once, which is where a terminal that answers some
 *       and not others becomes visible in one screen.</li>
 * </ul>
 *
 * <p>The {@code facts=} field comes from the {@link Subscription#facts} source rather than from the
 * query the demo itself asked, which is what makes a capability upgrade visible: a terminal that
 * answers {@code Tc} widens the profile, and the demo learns it the same way any other component
 * would.</p>
 */
public final class TerminalQueryDemo {

    /** The bracketed-paste mode number, the one mode this demo reports on. */
    public static final int BRACKETED_PASTE_MODE = 2004;

    /** The status row carrying the colour profile and the sample's theme. */
    public static final int PROFILE_ROW = 0;

    /** The status row carrying the three reported colours. */
    public static final int COLOR_ROW = 1;

    /** The status row carrying the terminal's identity, the cursor and the terminal's claims. */
    public static final int FACTS_ROW = 2;

    /** The status row carrying the query tally and the last event. */
    public static final int TALLY_ROW = 3;

    /** The styled sample row, which is the row a profile change repaints. */
    public static final int SAMPLE_ROW = 4;

    private static final Style STATUS = Style.NONE.withBold();

    private static final String UNKNOWN = "-";

    private TerminalQueryDemo() {}

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A key asked the terminal one question.
         *
         * @param query the question to ask
         */
        record Ask(TerminalQuery query) implements Msg {

            public Ask {
                java.util.Objects.requireNonNull(query, "query");
            }
        }

        /** A key asked every terminal question at once. */
        record AskAll() implements Msg {}

        /**
         * A key chose the profile the session renders with.
         *
         * @param profile the profile to render with
         */
        record Profile(ColorProfile profile) implements Msg {

            public Profile {
                java.util.Objects.requireNonNull(profile, "profile");
            }
        }

        /**
         * One terminal answer.
         *
         * @param query the question that was asked
         * @param reply the terminal's answer
         */
        record Answer(TerminalQuery query, TerminalQuery.Reply reply) implements Msg {}

        /**
         * The session's facts changed.
         *
         * @param facts the facts in force
         */
        record Facts(TerminalFacts facts) implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene state.
     *
     * @param identity the reported terminal name, or {@link #UNKNOWN}
     * @param cursor the reported cursor cell, or {@link #UNKNOWN}
     * @param paste the reported bracketed-paste setting, or null when it has not been reported
     * @param capability the reported {@code Tc} capability, or null when it has not been reported
     * @param background the reported background colour, or null
     * @param foreground the reported foreground colour, or null
     * @param caret the reported cursor colour, or null
     * @param facts the session facts the runtime published
     * @param asked how many queries the scene has asked
     * @param answered how many were answered with a fact
     * @param failed how many were refused or never answered
     * @param last the last event, as one token
     */
    public record Model(
            String identity,
            String cursor,
            TerminalQuery.Setting paste,
            String capability,
            Color background,
            Color foreground,
            Color caret,
            TerminalFacts facts,
            int asked,
            int answered,
            int failed,
            String last) {

        /** Substitutes the starting state for missing values. */
        public Model {
            identity = identity == null ? UNKNOWN : identity;
            cursor = cursor == null ? UNKNOWN : cursor;
            facts = facts == null ? new TerminalFacts(ColorProfile.NONE, null, null) : facts;
            background = background == null ? facts.background() : background;
            asked = Math.max(0, asked);
            answered = Math.max(0, answered);
            failed = Math.max(0, failed);
            last = last == null ? "none" : last;
        }

        /** The theme the terminal's background names, or {@code unknown}. */
        String theme() {
            return facts.themeKnown() ? (facts.dark() ? "dark" : "light") : "unknown";
        }

        Model withReply(TerminalQuery query, TerminalQuery.Reply reply) {
            String token = describe(reply);
            boolean ok = isFact(reply);
            return switch (reply) {
                case TerminalQuery.Reply.CursorPosition position ->
                    new Model(
                            identity,
                            position.row() + "," + position.column(),
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered + 1,
                            failed,
                            token);
                case TerminalQuery.Reply.Identity reported ->
                    new Model(
                            reported.name(),
                            cursor,
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered + 1,
                            failed,
                            token);
                case TerminalQuery.Reply.Mode mode ->
                    new Model(
                            identity,
                            cursor,
                            mode.setting(),
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered + 1,
                            failed,
                            token);
                case TerminalQuery.Reply.Capability capability ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability.value(),
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered + 1,
                            failed,
                            token);
                case TerminalQuery.Reply.ColorValue color ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability,
                            color.target() == TerminalQuery.Target.BACKGROUND ? color.color() : background,
                            color.target() == TerminalQuery.Target.FOREGROUND ? color.color() : foreground,
                            color.target() == TerminalQuery.Target.CURSOR ? color.color() : caret,
                            facts,
                            asked + 1,
                            answered + 1,
                            failed,
                            token);
                case TerminalQuery.Reply.Profile profile ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered + 1,
                            failed,
                            token);
                case TerminalQuery.Reply.Clipboard clipboard ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered + 1,
                            failed,
                            token);
                case TerminalQuery.Reply.ProgramStatus support ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered + 1,
                            failed,
                            token);
                case TerminalQuery.Reply.Oversize oversize ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered,
                            failed + 1,
                            token);
                case TerminalQuery.Reply.Unsupported unsupported ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered,
                            failed + 1,
                            token);
                case TerminalQuery.Reply.Timeout timeout ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered,
                            failed + 1,
                            token);
                case TerminalQuery.Reply.Malformed malformed ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered,
                            failed + 1,
                            token);
                case TerminalQuery.Reply.Rejected rejected ->
                    new Model(
                            identity,
                            cursor,
                            paste,
                            capability,
                            background,
                            foreground,
                            caret,
                            facts,
                            asked + 1,
                            answered,
                            failed + 1,
                            token);
            };
        }

        Model withFacts(TerminalFacts value) {
            return new Model(
                    identity,
                    cursor,
                    paste,
                    capability,
                    background,
                    foreground,
                    caret,
                    value,
                    asked,
                    answered,
                    failed,
                    last);
        }

        Model withLast(String value) {
            return new Model(
                    identity,
                    cursor,
                    paste,
                    capability,
                    background,
                    foreground,
                    caret,
                    facts,
                    asked,
                    answered,
                    failed,
                    value);
        }

        /** Whether an answer carried a fact. */
        private static boolean isFact(TerminalQuery.Reply reply) {
            return switch (reply) {
                case TerminalQuery.Reply.Unsupported unsupported -> false;
                case TerminalQuery.Reply.Timeout timeout -> false;
                case TerminalQuery.Reply.Malformed malformed -> false;
                case TerminalQuery.Reply.Rejected rejected -> false;
                case TerminalQuery.Reply.Oversize oversize -> false;
                default -> true;
            };
        }

        /** One answer, as a single token a status row and a verifier can read. */
        static String describe(TerminalQuery.Reply reply) {
            return switch (reply) {
                case TerminalQuery.Reply.CursorPosition position ->
                    "cursor=" + position.row() + "," + position.column();
                case TerminalQuery.Reply.Identity reported -> "identity=" + reported.name();
                case TerminalQuery.Reply.Mode mode ->
                    "mode=" + mode.setting().name().toLowerCase(java.util.Locale.ROOT);
                case TerminalQuery.Reply.Capability capability ->
                    "tc=" + (capability.value().isEmpty() ? "empty" : capability.value());
                case TerminalQuery.Reply.ColorValue color ->
                    color.target().name().toLowerCase(java.util.Locale.ROOT) + "="
                            + color.color().hex();
                case TerminalQuery.Reply.Profile profile ->
                    "profile=" + profile.profile().token() + (profile.explicit() ? ",explicit" : ",negotiated");
                case TerminalQuery.Reply.Clipboard clipboard ->
                    "clipboard=" + (clipboard.text().isEmpty() ? "empty" : "bytes=" + clipboard.byteLength());
                case TerminalQuery.Reply.ProgramStatus support ->
                    "status=" + String.join(",", new java.util.TreeSet<>(support.states())) + "/"
                            + String.join(",", new java.util.TreeSet<>(support.kinds()));
                case TerminalQuery.Reply.Oversize oversize -> kind(oversize.query()) + "=oversize";
                case TerminalQuery.Reply.Unsupported unsupported -> kind(unsupported.query()) + "=unsupported";
                case TerminalQuery.Reply.Timeout timeout -> kind(timeout.query()) + "=timeout";
                case TerminalQuery.Reply.Malformed malformed ->
                    kind(malformed.query()) + "=malformed(" + malformed.detail() + ")";
                case TerminalQuery.Reply.Rejected rejected ->
                    kind(rejected.query()) + "=rejected(" + rejected.outstanding() + ")";
            };
        }

        /** The token a query's row and event use. */
        static String kind(TerminalQuery query) {
            return switch (query) {
                case TerminalQuery.CursorPosition cursor -> "cursor";
                case TerminalQuery.Identity identity -> "identity";
                case TerminalQuery.Mode mode -> "mode" + mode.mode();
                case TerminalQuery.Termcap termcap -> "tc";
                case TerminalQuery.ColorReport color -> color.target().name().toLowerCase(java.util.Locale.ROOT);
                case TerminalQuery.Profile profile -> "profile";
                case TerminalQuery.ClipboardRead clipboard ->
                    "clipboard-" + clipboard.selection().token();
                case TerminalQuery.ProgramStatus programStatus -> "status";
            };
        }
    }

    /**
     * The scene's actions for one state.
     *
     * @param model the scene state the actions are built for
     * @return the map, in help order
     */
    public static KeyMap<Msg> keys(Model model) {
        return KeyMap.of(
                KeyMap.group(
                        "Ask the terminal",
                        action("ask-cursor", new TerminalQuery.CursorPosition(), "c", "cursor position"),
                        action("ask-identity", new TerminalQuery.Identity(), "i", "terminal name"),
                        action("ask-mode", new TerminalQuery.Mode(BRACKETED_PASTE_MODE), "m", "paste mode"),
                        action("ask-capability", new TerminalQuery.Termcap("Tc"), "t", "truecolor claim")),
                KeyMap.group(
                        "Ask for colours",
                        action(
                                "ask-background",
                                new TerminalQuery.ColorReport(TerminalQuery.Target.BACKGROUND),
                                "b",
                                "background"),
                        action(
                                "ask-foreground",
                                new TerminalQuery.ColorReport(TerminalQuery.Target.FOREGROUND),
                                "f",
                                "foreground"),
                        action(
                                "ask-cursor-color",
                                new TerminalQuery.ColorReport(TerminalQuery.Target.CURSOR),
                                "k",
                                "caret colour")),
                KeyMap.group(
                        "Session",
                        action("ask-profile", new TerminalQuery.Profile(), "p", "session profile"),
                        KeyAction.<Msg>of("ask-all", new Msg.AskAll(), "a", "ask everything", "a"),
                        KeyAction.<Msg>of("color-none", new Msg.Profile(ColorProfile.NONE), "1", "no colour", "1"),
                        KeyAction.<Msg>of("color-16", new Msg.Profile(ColorProfile.ANSI16), "2", "16 colours", "2"),
                        KeyAction.<Msg>of("color-256", new Msg.Profile(ColorProfile.ANSI256), "3", "256 colours", "3"),
                        KeyAction.<Msg>of(
                                "color-truecolor", new Msg.Profile(ColorProfile.TRUECOLOR), "4", "truecolor", "4"),
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "esc", "quit", "escape", "ctrl+c")));
    }

    /** One query action: the key asks the question and the answer comes back as a message. */
    private static KeyAction<Msg> action(String id, TerminalQuery query, String key, String description) {
        return KeyAction.of(id, new Msg.Ask(query), key, description, key);
    }

    /** Runs the demo against the owned terminal. */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    /**
     * Runs the demo.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /** A scene whose whole model is what the terminal has said. */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Asks nothing: a session that queries at startup would be deciding for the application what
         * the terminal should be asked. The demo's first question is a keypress, and the one fact it
         * does not ask for - the profile - comes from the session facts it subscribes to.
         */
        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(new Model(null, null, null, null, null, null, null, null, 0, 0, 0, "none"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Ask ask -> Update.next(model, ask(ask.query()));
                case Msg.AskAll all -> Update.next(model, Command.batch(askAll()));
                case Msg.Answer answer -> Update.next(model.withReply(answer.query(), answer.reply()));
                case Msg.Facts facts -> Update.next(model.withFacts(facts.facts()));
                case Msg.Profile profile -> Update.next(model, Command.colorProfile(profile.profile()));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /** The session facts, so a profile change the scene did not ask for still reaches it. */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.facts(SubscriptionId.of("facts"), Msg.Facts::new);
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int cols = context.viewport().cols();
            KeyMap<Msg> keys = keys(model);
            List<View<Msg>> rows = new ArrayList<>(8);
            // One row per fact group, each pinned to a single row: a wrapped status row would push
            // the sample and the help down and move what a verifier reads.
            rows.add(Views.fixed(1, Views.text(profileRow(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(colorRow(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(factsRow(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(tallyRow(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(sample())));
            rows.add(Views.gap(1));
            rows.add(Help.view(keys, false, cols));
            return Views.onKeys(keys.bindings(), Views.column(rows));
        }

        /** The one query this update asks, with its effect identity and its answer mapper. */
        private static Command<Msg> ask(TerminalQuery query) {
            return Command.query(EffectId.of("ask-" + Model.kind(query)), query, reply -> new Msg.Answer(query, reply));
        }

        /** Every terminal question at once; the session profile is a fact, so it is not one. */
        private static List<Command<Msg>> askAll() {
            return List.of(
                    ask(new TerminalQuery.CursorPosition()),
                    ask(new TerminalQuery.Identity()),
                    ask(new TerminalQuery.Mode(BRACKETED_PASTE_MODE)),
                    ask(new TerminalQuery.Termcap("Tc")),
                    ask(new TerminalQuery.ColorReport(TerminalQuery.Target.BACKGROUND)),
                    ask(new TerminalQuery.ColorReport(TerminalQuery.Target.FOREGROUND)),
                    ask(new TerminalQuery.ColorReport(TerminalQuery.Target.CURSOR)));
        }

        /** The profile row: what the session paints with, and whether the scene chose it. */
        private static String profileRow(Model model) {
            return "profile=" + model.facts().profile().token()
                    + " color=" + (model.facts().color() ? "on" : "off")
                    + " theme=" + model.theme();
        }

        /** The colour row: the terminal's own three colours, as it reported them. */
        private static String colorRow(Model model) {
            return "bg=" + hex(model.background()) + " fg=" + hex(model.foreground()) + " caret=" + hex(model.caret());
        }

        /** The facts row: the identity, the cursor and the two terminal claims. */
        private static String factsRow(Model model) {
            return "identity=" + model.identity()
                    + " cursor=" + model.cursor()
                    + " paste="
                    + (model.paste() == null ? UNKNOWN : model.paste().name().toLowerCase(java.util.Locale.ROOT))
                    + " tc=" + (model.capability() == null ? UNKNOWN : model.capability());
        }

        /** The tally row: how many questions were answered, refused or never answered, and the last. */
        private static String tallyRow(Model model) {
            return "asked=" + model.asked() + " answered=" + model.answered() + " failed=" + model.failed() + " last="
                    + model.last();
        }

        /** A styled sample: one word per palette colour, so a profile change is visible in the bytes. */
        private static StyledText sample() {
            List<StyledText> parts = new ArrayList<>();
            parts.add(StyledText.plain("sample "));
            parts.add(StyledText.of("red ", Style.fg(Colors.ERROR_400)));
            parts.add(StyledText.of("amber ", Style.fg(Colors.WARNING_400)));
            parts.add(StyledText.of("green ", Style.fg(Colors.SUCCESS_400)));
            parts.add(StyledText.of("blue ", Style.fg(Colors.BLUE_400)));
            parts.add(StyledText.of("violet", Style.fg(Colors.VIOLET_400)));
            return StyledText.concat(parts.toArray(new StyledText[0]));
        }

        /** A colour as the report row spells it. */
        private static String hex(Color color) {
            return color == null ? "unknown" : color.hex();
        }
    }
}

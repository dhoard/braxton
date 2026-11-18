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
import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.TerminalPresentation;
import io.github.dhoard.braxton.view.TerminalPresentation.Progress;
import io.github.dhoard.braxton.view.TerminalPresentation.Status;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Help;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Phase 20 parity demo: declarative terminal presentation.
 *
 * <p>One scene declares the session's terminal presentation from its own model, so the whole phase is
 * visible on one screen: which screen buffer the frames are drawn on, whether the pointer, paste and
 * focus reports are asked for, the window title, the terminal's default colours, a taskbar progress
 * value and a program-status record. Every field starts at {@code default}, which means "the session's
 * own options", so the first frame asks the terminal for nothing beyond what the session opened
 * with.</p>
 *
 * <ul>
 *   <li>{@code a} cycles the screen buffer between the session default, the plain screen and the
 *       alternate screen. Leaving the alternate screen paints over the shell's own screen, which is
 *       exactly the difference the buffer makes; returning repaints everything, because the other
 *       buffer holds different cells.</li>
 *   <li>{@code m}, {@code p} and {@code f} cycle the pointer level, bracketed paste and focus
 *       reporting between the session default, on and off. The transcript shows the sequences that
 *       ask for and give back each mode.</li>
 *   <li>{@code t} sets a title, then one containing an injected {@code ESC ] 0 ; ...} sequence, then
 *       none. The unsafe title is stripped before it reaches the terminal, so the transcript never
 *       contains the sequence it tried to inject - and leaving the title out pops the one the session
 *       saved when it first set one.</li>
 *   <li>{@code c} sets the terminal's default text, background and cursor colours and then gives them
 *       back, which is the terminal's own configured palette rather than a captured value.</li>
 *   <li>{@code g} cycles the taskbar progress through none, normal, error, indeterminate and
 *       paused.</li>
 *   <li>{@code s} cycles the program status through working, blocked, done, error and an explicit
 *       clear. A final {@code done} or {@code error} is retained after the session ends, which is what
 *       the protocol asks for.</li>
 *   <li>{@code k} asks the terminal whether it keeps program-status records, and reports what it said
 *       it accepts: a terminal that implements nothing answers nothing, and the row says
 *       {@code support=timeout} rather than guessing.</li>
 * </ul>
 *
 * <p>Every gesture increments {@code gestures=}, which is what makes a repaint visible: the body row
 * changes on every key, so a verifier can tell a frame that was painted from one that was skipped.</p>
 */
public final class TerminalPresentationDemo {

    /** The status row carrying the four session modes. */
    public static final int MODE_ROW = 0;

    /** The status row carrying the title and the three default colours. */
    public static final int METADATA_ROW = 1;

    /** The status row carrying the progress and program-status intent. */
    public static final int PROGRESS_ROW = 2;

    /** The status row carrying whether the terminal keeps program-status records. */
    public static final int SUPPORT_ROW = 3;

    /** The status row carrying the state names the terminal accepts. */
    public static final int STATES_ROW = 4;

    /** The status row carrying the blocked-kind names the terminal accepts. */
    public static final int KINDS_ROW = 5;

    /** The status row carrying the last gesture. */
    public static final int LAST_ROW = 6;

    /** The body row, which changes on every gesture. */
    public static final int BODY_ROW = 8;

    /** The title the demo sets, and the one it tries to inject a sequence with. */
    public static final String TITLE = "braxton presentation";

    /** A title holding a control sequence, which must not reach the terminal. */
    public static final String UNSAFE_TITLE = "braxton\u001B]0;pwned\u0007";

    /** The three default colours the demo applies to the terminal. */
    public static final Color TEXT_COLOR = new Color(0xE0, 0xE6, 0xF0);

    /** The default background the demo applies to the terminal. */
    public static final Color BACKGROUND_COLOR = new Color(0x10, 0x14, 0x1C);

    /** The default cursor colour the demo applies to the terminal. */
    public static final Color CURSOR_COLOR = new Color(0x38, 0xA6, 0xFF);

    private static final Style STATUS = Style.NONE.withBold();
    private static final Style BODY = Style.NONE.withBold();

    private TerminalPresentationDemo() {}

    /** Every message the scene handles. */
    public sealed interface Msg {

        /** Cycles the screen buffer between the session default, plain and alternate. */
        record CycleScreen() implements Msg {}

        /** Cycles the pointer reporting level. */
        record CycleMouse() implements Msg {}

        /** Cycles bracketed paste between the session default, on and off. */
        record CyclePaste() implements Msg {}

        /** Cycles terminal focus reporting between the session default, on and off. */
        record CycleFocus() implements Msg {}

        /** Cycles the window title between none, a title and one that tries to inject a sequence. */
        record CycleTitle() implements Msg {}

        /** Cycles the terminal's default colours between none and the scene's own. */
        record CycleColors() implements Msg {}

        /** Cycles the taskbar progress value. */
        record CycleProgress() implements Msg {}

        /** Cycles the program-status record. */
        record CycleStatus() implements Msg {}

        /** Asks the terminal whether it keeps program-status records. */
        record AskSupport() implements Msg {}

        /**
         * The terminal's answer about program-status support.
         *
         * @param reply the typed answer
         */
        record Support(TerminalQuery.Reply reply) implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene state.
     *
     * @param alternateScreen the screen buffer the scene asks for, or null for the session default
     * @param mouseMode the pointer level the scene asks for, or null for the session default
     * @param bracketedPaste whether paste is bracketed, or null for the session default
     * @param focusReporting whether focus changes are reported, or null for the session default
     * @param title the window title, or null for none
     * @param colors whether the scene imposes the terminal's default colours
     * @param progress the taskbar progress, or null for none
     * @param status the program-status record, or null for none
     * @param support what the terminal said about program status, as one token
     * @param states the state names the terminal accepts, or {@code -}
     * @param kinds the blocked-kind names the terminal accepts, or {@code -}
     * @param gestures how many gestures the scene has handled
     * @param last the last gesture, as one token
     */
    public record Model(
            Boolean alternateScreen,
            MouseMode mouseMode,
            Boolean bracketedPaste,
            Boolean focusReporting,
            String title,
            boolean colors,
            Progress progress,
            Status status,
            String support,
            String states,
            String kinds,
            int gestures,
            String last) {

        /** Substitutes the starting values for anything missing. */
        public Model {
            support = support == null ? "unknown" : support;
            states = states == null ? "-" : states;
            kinds = kinds == null ? "-" : kinds;
            gestures = Math.max(0, gestures);
            last = last == null ? "none" : last;
        }

        /** The scene's starting state: nothing declared, so every field is the session's own. */
        public static Model start() {
            return new Model(null, null, null, null, null, false, null, null, "unknown", "-", "-", 0, "none");
        }

        /**
         * The presentation this model asks the terminal for.
         *
         * @return the intent the frame declares
         */
        public TerminalPresentation presentation() {
            TerminalPresentation presentation = TerminalPresentation.EMPTY
                    .withAlternateScreen(alternateScreen)
                    .withMouseMode(mouseMode)
                    .withBracketedPaste(bracketedPaste)
                    .withFocusReporting(focusReporting)
                    .withTitle(title)
                    .withProgress(progress)
                    .withStatus(status);
            return colors
                    ? presentation
                            .withForeground(TEXT_COLOR)
                            .withBackground(BACKGROUND_COLOR)
                            .withCursorColor(CURSOR_COLOR)
                    : presentation;
        }

        Model advanced(String token) {
            return new Model(
                    alternateScreen,
                    mouseMode,
                    bracketedPaste,
                    focusReporting,
                    title,
                    colors,
                    progress,
                    status,
                    support,
                    states,
                    kinds,
                    gestures + 1,
                    token);
        }

        Model withScreen(Boolean value) {
            return copy(value, mouseMode, bracketedPaste, focusReporting, title, colors, progress, status);
        }

        Model withMouse(MouseMode value) {
            return copy(alternateScreen, value, bracketedPaste, focusReporting, title, colors, progress, status);
        }

        Model withPaste(Boolean value) {
            return copy(alternateScreen, mouseMode, value, focusReporting, title, colors, progress, status);
        }

        Model withFocus(Boolean value) {
            return copy(alternateScreen, mouseMode, bracketedPaste, value, title, colors, progress, status);
        }

        Model withTitle(String value) {
            return copy(alternateScreen, mouseMode, bracketedPaste, focusReporting, value, colors, progress, status);
        }

        Model withColors(boolean value) {
            return copy(alternateScreen, mouseMode, bracketedPaste, focusReporting, title, value, progress, status);
        }

        Model withProgress(Progress value) {
            return copy(alternateScreen, mouseMode, bracketedPaste, focusReporting, title, colors, value, status);
        }

        Model withStatus(Status value) {
            return copy(alternateScreen, mouseMode, bracketedPaste, focusReporting, title, colors, progress, value);
        }

        Model withSupport(String token, String stateNames, String kindNames) {
            return new Model(
                    alternateScreen,
                    mouseMode,
                    bracketedPaste,
                    focusReporting,
                    title,
                    colors,
                    progress,
                    status,
                    token,
                    stateNames,
                    kindNames,
                    gestures,
                    last);
        }

        private Model copy(
                Boolean screen,
                MouseMode mouse,
                Boolean paste,
                Boolean focus,
                String windowTitle,
                boolean defaultColors,
                Progress taskbar,
                Status programStatus) {
            return new Model(
                    screen,
                    mouse,
                    paste,
                    focus,
                    windowTitle,
                    defaultColors,
                    taskbar,
                    programStatus,
                    support,
                    states,
                    kinds,
                    gestures,
                    last);
        }
    }

    /**
     * The scene's own actions.
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Modes",
                        KeyAction.<Msg>of("screen", new Msg.CycleScreen(), "a", "screen buffer", "a"),
                        KeyAction.<Msg>of("mouse", new Msg.CycleMouse(), "m", "pointer level", "m"),
                        KeyAction.<Msg>of("paste", new Msg.CyclePaste(), "p", "bracketed paste", "p"),
                        KeyAction.<Msg>of("focus", new Msg.CycleFocus(), "f", "focus reporting", "f")),
                KeyMap.group(
                        "Metadata",
                        KeyAction.<Msg>of("title", new Msg.CycleTitle(), "t", "window title", "t"),
                        KeyAction.<Msg>of("colors", new Msg.CycleColors(), "c", "default colours", "c"),
                        KeyAction.<Msg>of("progress", new Msg.CycleProgress(), "g", "taskbar progress", "g"),
                        KeyAction.<Msg>of("status", new Msg.CycleStatus(), "s", "program status", "s")),
                KeyMap.group(
                        "Terminal",
                        KeyAction.<Msg>of("support", new Msg.AskSupport(), "k", "ask support", "k"),
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "ctrl+c")));
    }

    /** Runs the demo against the owned terminal. */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /** The demo scene: one frame, five status rows and the help lines. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final KeyMap<Msg> keys = defaultKeys();

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(Model.start());
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.CycleScreen cycle -> {
                    Model next = model.withScreen(nextScreen(model.alternateScreen()));
                    yield Update.next(next.advanced("screen=" + screen(next)));
                }
                case Msg.CycleMouse cycle -> {
                    Model next = model.withMouse(nextMouse(model.mouseMode()));
                    yield Update.next(next.advanced("mouse=" + token(next.mouseMode())));
                }
                case Msg.CyclePaste cycle -> {
                    Model next = model.withPaste(nextSwitch(model.bracketedPaste()));
                    yield Update.next(next.advanced("paste=" + token(next.bracketedPaste())));
                }
                case Msg.CycleFocus cycle -> {
                    Model next = model.withFocus(nextSwitch(model.focusReporting()));
                    yield Update.next(next.advanced("focus=" + token(next.focusReporting())));
                }
                case Msg.CycleTitle cycle -> {
                    Model next = model.withTitle(nextTitle(model.title()));
                    yield Update.next(next.advanced("title=" + titleToken(next.title())));
                }
                case Msg.CycleColors cycle -> {
                    Model next = model.withColors(!model.colors());
                    yield Update.next(next.advanced("colors=" + (next.colors() ? "on" : "off")));
                }
                case Msg.CycleProgress cycle -> {
                    Model next = model.withProgress(nextProgress(model.progress()));
                    yield Update.next(next.advanced("progress=" + progressToken(next.progress())));
                }
                case Msg.CycleStatus cycle -> {
                    Model next = model.withStatus(nextStatus(model.status()));
                    yield Update.next(next.advanced("status=" + statusToken(next.status())));
                }
                case Msg.AskSupport ask ->
                    Update.next(
                            model.advanced("support-asked"),
                            Command.query(
                                    EffectId.of("program-status"),
                                    new TerminalQuery.ProgramStatus(),
                                    Msg.Support::new));
                case Msg.Support support -> Update.next(withSupport(model, support.reply()));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int width = context.viewport().cols();
            return Views.presentation(
                    model.presentation(),
                    Views.onKeys(
                            keys.bindings(),
                            Views.column(
                                    Views.text(modes(model), STATUS),
                                    Views.text(metadata(model), STATUS),
                                    Views.text(intent(model), STATUS),
                                    Views.text(support(model), STATUS),
                                    Views.text("states=" + model.states(), STATUS),
                                    Views.text("kinds=" + model.kinds(), STATUS),
                                    Views.text("last=" + model.last(), STATUS),
                                    Views.gap(1),
                                    Views.text("braxton terminal presentation gestures=" + model.gestures(), BODY),
                                    Views.gap(1),
                                    Help.view(keys, false, width))));
        }

        /** The four session modes, with {@code default} for one the scene leaves to the session. */
        private static String modes(Model model) {
            return "screen=" + screen(model)
                    + " mouse=" + token(model.mouseMode())
                    + " paste=" + token(model.bracketedPaste())
                    + " focus=" + token(model.focusReporting());
        }

        /** The title and the three terminal default colours. */
        private static String metadata(Model model) {
            return "title=" + titleToken(model.title())
                    + " fg=" + (model.colors() ? TEXT_COLOR.hex() : "none")
                    + " bg=" + (model.colors() ? BACKGROUND_COLOR.hex() : "none")
                    + " cursor=" + (model.colors() ? CURSOR_COLOR.hex() : "none");
        }

        /** The metadata that is not a session mode: progress and the program-status record. */
        private static String intent(Model model) {
            String record = model.status() == null
                    ? "none"
                    : statusToken(model.status())
                            + (model.status().id() == null
                                    ? ""
                                    : " id=" + model.status().id());
            return "progress=" + progressToken(model.progress()) + " status=" + record;
        }

        /**
         * Whether the terminal keeps program-status records, and how many names it listed.
         *
         * <p>The lists themselves are on the two rows below, so this row stays short enough for an
         * eighty-column terminal even when a later protocol revision lists more of them.</p>
         */
        private static String support(Model model) {
            return "support=" + model.support()
                    + " states="
                    + (model.states().equals("-") ? "0" : model.states().split(",").length)
                    + " kinds="
                    + (model.kinds().equals("-") ? "0" : model.kinds().split(",").length);
        }

        /** The scene after a support answer, with the terminal's own lists sorted so they are stable. */
        private static Model withSupport(Model model, TerminalQuery.Reply reply) {
            if (reply instanceof TerminalQuery.Reply.ProgramStatus supported) {
                return model.withSupport("yes", join(supported.states()), join(supported.kinds()))
                        .advanced("support=yes");
            }
            String token = switch (reply) {
                case TerminalQuery.Reply.Timeout timeout -> "timeout";
                case TerminalQuery.Reply.Unsupported unsupported -> "unsupported";
                case TerminalQuery.Reply.Malformed malformed -> "malformed";
                case TerminalQuery.Reply.Rejected rejected -> "rejected";
                case TerminalQuery.Reply.Oversize oversize -> "oversize";
                default -> "unexpected";
            };
            return model.withSupport(token, "-", "-").advanced("support=" + token);
        }

        private static String join(java.util.Set<String> names) {
            StringJoiner joined = new StringJoiner(",");
            for (String name : new TreeSet<>(names)) {
                joined.add(name);
            }
            return joined.length() == 0 ? "-" : joined.toString();
        }

        /** {@code default} for a value the scene leaves to the session, then the value itself. */
        private static String token(Boolean value) {
            return value == null ? "default" : value ? "on" : "off";
        }

        private static String token(MouseMode mode) {
            if (mode == null) {
                return "default";
            }
            return switch (mode) {
                case NONE -> "none";
                case CLICK -> "click";
                case CELL_MOTION -> "cell";
                case ALL_MOTION -> "all";
            };
        }

        private static String screen(Model model) {
            Boolean value = model.alternateScreen();
            return value == null ? "default" : value ? "alternate" : "plain";
        }

        private static String titleToken(String title) {
            if (title == null) {
                return "none";
            }
            return title.equals(TITLE) ? "set" : "unsafe";
        }

        private static String progressToken(Progress progress) {
            if (progress == null) {
                return "none";
            }
            return progress.state().name().toLowerCase(Locale.ROOT) + ":" + progress.value();
        }

        private static String statusToken(Status status) {
            return status.state().name().toLowerCase(Locale.ROOT);
        }

        private static Boolean nextSwitch(Boolean value) {
            return value == null ? Boolean.TRUE : value ? Boolean.FALSE : null;
        }

        private static Boolean nextScreen(Boolean value) {
            return value == null ? Boolean.FALSE : value ? null : Boolean.TRUE;
        }

        private static MouseMode nextMouse(MouseMode mode) {
            if (mode == null) {
                return MouseMode.NONE;
            }
            return switch (mode) {
                case NONE -> MouseMode.CLICK;
                case CLICK -> MouseMode.CELL_MOTION;
                case CELL_MOTION -> MouseMode.ALL_MOTION;
                case ALL_MOTION -> null;
            };
        }

        private static String nextTitle(String title) {
            if (title == null) {
                return TITLE;
            }
            return title.equals(TITLE) ? UNSAFE_TITLE : null;
        }

        /**
         * The next progress value, ending at none.
         *
         * <p>Dropping the intent is how a frame says "no progress": the terminal is then asked to
         * remove the indication it is showing, which is the same sequence an explicit
         * {@link Progress#none()} writes. Cycling back to none therefore leaves the field absent
         * rather than holding a value that means nothing.</p>
         */
        private static Progress nextProgress(Progress progress) {
            if (progress == null) {
                return Progress.normal(40);
            }
            return switch (progress.state()) {
                case NORMAL -> Progress.error(70);
                case ERROR -> Progress.indeterminate();
                case INDETERMINATE -> Progress.paused(90);
                case PAUSED, NONE -> null;
            };
        }

        private static Status nextStatus(Status status) {
            if (status == null) {
                return Status.working("braxton", "Presenting a frame");
            }
            return switch (status.state()) {
                case WORKING ->
                    Status.blocked(Status.Kind.PERMISSION, "braxton", "Let the frame go?")
                            .withId("build");
                case BLOCKED -> Status.of(Status.State.DONE).withApp("braxton").withMessage("Frame presented");
                case DONE -> Status.of(Status.State.ERROR).withApp("braxton").withMessage("Frame failed");
                case ERROR -> Status.of(Status.State.CLEAR).withId("build");
                case CLEAR -> null;
                case IDLE -> Status.working("braxton", "Presenting a frame");
            };
        }
    }
}

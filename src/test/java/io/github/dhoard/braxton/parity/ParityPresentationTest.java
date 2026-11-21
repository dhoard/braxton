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

import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.app.TerminalPresentation;
import io.github.dhoard.braxton.app.TerminalPresentation.Progress;
import io.github.dhoard.braxton.app.TerminalPresentation.Status;
import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.TerminalReply;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.runtime.ManualTimers;
import io.github.dhoard.braxton.internal.runtime.ScopeManager;
import io.github.dhoard.braxton.internal.runtime.TerminalPresenter;
import io.github.dhoard.braxton.internal.runtime.TerminalQueries;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.terminal.Capabilities;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Phase 20 acceptance: declarative terminal presentation.
 *
 * <p>Four things are asserted against observable behavior. The intent is immutable view data that no
 * terminal handle appears in, it is layout-transparent, and the frame's tree may declare exactly one
 * of them. Its text is bounded and stripped of control characters, so no title or status message can
 * end the sequence it is written into and start another. The encoder writes the documented wire forms
 * for the title, the terminal's default colors, progress and program status, and the session writes
 * only what changed. The exit path gives back everything the session changed, while a finished
 * {@code done} or {@code error} status stays, which is what the protocol asks for.</p>
 *
 * <p>The runtime cases drive a real {@code RuntimeLoop} over an owned stream terminal, so the evidence
 * is the byte transcript the session actually wrote; the query cases drive the shipped engine with
 * manual timers, so a deadline is decided by the test rather than by wall-clock luck.</p>
 */
@Timeout(20)
class ParityPresentationTest {

    private static final Viewport VIEWPORT = new Viewport(6, 32);

    /**
     * The scene's own text, so a painted frame is recognizable in the transcript.
     */
    private static final String SCENE_TEXT = "scene";

    private static final Color DEFAULT_TEXT = new Color(0x10, 0x20, 0x30);

    private static final Color DEFAULT_BACKDROP = new Color(0x0A, 0x0B, 0x0C);

    private static final Color DEFAULT_CARET = new Color(0x11, 0x22, 0x33);

    private static int count(String text, String token) {
        int count = 0;
        int at = text.indexOf(token);
        while (at >= 0) {
            count++;
            at = text.indexOf(token, at + token.length());
        }
        return count;
    }

    // ── R-01: the intent is immutable, root-owned view data ──────────────────

    @Test
    void aPresentationDescribesSessionStateWithoutATerminal() {
        TerminalPresentation presentation = TerminalPresentation.EMPTY
                .withAlternateScreen(false)
                .withMouseMode(MouseMode.CELL_MOTION)
                .withBracketedPaste(false)
                .withFocusReporting(true)
                .withTitle("braxton")
                .withForeground(DEFAULT_TEXT)
                .withBackground(DEFAULT_BACKDROP)
                .withCursorColor(DEFAULT_CARET)
                .withProgress(Progress.normal(40))
                .withStatus(Status.working("demo", "Working"));

        assertThat(presentation.alternateScreen()).isFalse();
        assertThat(presentation.mouseMode()).isEqualTo(MouseMode.CELL_MOTION);
        assertThat(presentation.bracketedPaste()).isFalse();
        assertThat(presentation.focusReporting()).isTrue();
        assertThat(presentation.title()).isEqualTo("braxton");
        assertThat(presentation.foreground()).isEqualTo(DEFAULT_TEXT);
        assertThat(presentation.background()).isEqualTo(DEFAULT_BACKDROP);
        assertThat(presentation.cursorColor()).isEqualTo(DEFAULT_CARET);
        assertThat(presentation.progress()).isEqualTo(Progress.normal(40));
        assertThat(presentation.status()).isEqualTo(Status.working("demo", "Working"));

        // Every modifier returns a new value: the intent is a value, not a builder.
        assertThat(TerminalPresentation.EMPTY.title()).isNull();
        assertThat(TerminalPresentation.EMPTY.progress()).isNull();
        assertThat(TerminalPresentation.title("only").title()).isEqualTo("only");
        assertThat(TerminalPresentation.EMPTY.withTitle("").title())
                .as("an empty title is no title")
                .isNull();
    }

    @Test
    void theFramesIntentIsTheRootComponentsAnswerAndOneIntentPerFrame() {
        // A frame's presentation is model data, so the root component is its only writer: there is
        // no second declaration anywhere in a tree that could win or lose by paint order.
        TerminalPresentation intent = TerminalPresentation.title("root");
        assertThat(TerminalPresentation.EMPTY.alternateScreen())
                .as("no intent leaves the session's own choice")
                .isNull();
        assertThat(intent.title()).isEqualTo("root");
        assertThat(intent).isNotEqualTo(TerminalPresentation.title("other"));
        assertThat(TerminalPresentation.title("same")).isEqualTo(TerminalPresentation.title("same"));
    }

    @Test
    void aMountedChildsPresentationIsLiftedThroughItsMount() {
        // The mount asks its child and maps the answer, so a parent that composes a scene still
        // presents the scene's own intent; two children that declared different intents would be
        // the parent's composition problem, not the tree's.
        TerminalPresentation child = TerminalPresentation.title("scene");
        Mount<Integer, String, Never, String, String, Integer> mount = Mount.of(
                ComponentId.of("scene"),
                new Component<String, String, Integer>() {
                    @Override
                    public Update<String, String, Integer> init(InitContext context) {
                        return Update.next("scene", Command.none());
                    }

                    @Override
                    public Update<String, String, Integer> update(String model, String message) {
                        return Update.next(model, Command.none());
                    }

                    @Override
                    public Element<String> view(String model, ViewContext context) {
                        return Elements.text(model);
                    }

                    @Override
                    public TerminalPresentation presentation(String model) {
                        return child;
                    }
                },
                ModelLens.of(model -> String.valueOf(model), (parent, next) -> next.length()),
                message -> message,
                io.github.dhoard.braxton.mvu.OutputRoute.toMessage(output -> String.valueOf(output)));

        assertThat(mount.presentation(0)).isEqualTo(child);
    }

    // ── R-02: bounded text that cannot inject a sequence ─────────────────────

    @Test
    void aTitleCannotEndItsOwnSequence() {
        String hostile = "braxton\u001B]0;pwned\u0007\u009B2J";
        TerminalPresentation presentation = TerminalPresentation.EMPTY.withTitle(hostile);
        assertThat(presentation.title()).isEqualTo("braxton]0;pwned2J");
        String encoded = AnsiEncoder.title(presentation.title());
        assertThat(encoded)
                .doesNotContain("\u001B]0;pwned")
                .doesNotContain("\u0007")
                .doesNotContain("\u009B");
        assertThat(encoded).isEqualTo("\u001B]2;braxton]0;pwned2J\u001B\\");
        assertThat(AnsiEncoder.title(hostile))
                .as("the encoder refuses an unsanitized payload even when it is handed one")
                .doesNotContain("\u001B]0;pwned");
    }

    @Test
    void aTitleIsBoundedOnAClusterBoundary() {
        String cluster = "e\u0301";
        String long_ = cluster.repeat(TerminalPresentation.MAX_TITLE_BYTES);
        String bounded = TerminalPresentation.EMPTY.withTitle(long_).title();
        assertThat(bounded.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(TerminalPresentation.MAX_TITLE_BYTES);
        assertThat(bounded.length() % cluster.length())
                .as("no cluster is split by the byte budget")
                .isZero();
        assertThat(long_).startsWith(bounded);
        assertThat(TerminalPresentation.EMPTY
                        .withTitle(long_.repeat(100))
                        .title()
                        .length())
                .as("a title far larger than the budget is still bounded")
                .isLessThanOrEqualTo(bounded.length());
        assertThat(TerminalPresentation.EMPTY.withTitle("short").title()).isEqualTo("short");
    }

    // ── R-03: progress and program status are values, not escape strings ─────

    @Test
    void progressCoversTheDocumentedStatesAndRejectsTheRest() {
        assertThat(Progress.none()).isEqualTo(new Progress(Progress.State.NONE, 0));
        assertThat(Progress.normal(40)).isEqualTo(new Progress(Progress.State.NORMAL, 40));
        assertThat(Progress.error(70).state()).isEqualTo(Progress.State.ERROR);
        assertThat(Progress.indeterminate()).isEqualTo(new Progress(Progress.State.INDETERMINATE, 0));
        assertThat(Progress.paused(90)).isEqualTo(new Progress(Progress.State.PAUSED, 90));
        assertThat(new Progress(Progress.State.NORMAL, 0).value()).isZero();
        assertThat(new Progress(Progress.State.NORMAL, 100).value()).isEqualTo(100);
        assertThat(new Progress(Progress.State.NONE, 55).value())
                .as("a state that shows no fraction normalizes it")
                .isZero();
        assertThat(Progress.paused(40).withState(Progress.State.NORMAL).value())
                .as("a percentage carries over when the state changes")
                .isEqualTo(40);

        assertThatThrownBy(() -> Progress.normal(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Progress.normal(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Progress(null, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusValidatesTheProtocolsFieldGrammars() {
        Status working = Status.working("demo", "Working");
        assertThat(working.state()).isEqualTo(Status.State.WORKING);
        assertThat(working.app()).isEqualTo("demo");
        assertThat(working.message()).isEqualTo("Working");
        assertThat(working.kind()).isNull();
        assertThat(working.progress()).isNull();
        assertThat(working.retained()).isFalse();

        assertThat(Status.blocked(Status.Kind.AUTH, "demo", "Password").kind()).isEqualTo(Status.Kind.AUTH);
        assertThat(Status.of(Status.State.DONE).retained()).isTrue();
        assertThat(Status.of(Status.State.ERROR).retained()).isTrue();
        assertThat(Status.of(Status.State.IDLE).retained()).isFalse();

        assertThat(Status.of(Status.State.WORKING)
                        .withId("build/test")
                        .withApp("cargo")
                        .withProgress(40)
                        .id())
                .isEqualTo("build/test");
        assertThat(Status.of(Status.State.WORKING)
                        .withApp("demo")
                        .withId("a/b/c/d/e/f/g/h")
                        .id())
                .as("the protocol allows eight segments")
                .isEqualTo("a/b/c/d/e/f/g/h");
        assertThat(new Status(Status.State.BLOCKED, null, Status.Kind.QUESTION, null, null, "?", null).kind())
                .isEqualTo(Status.Kind.QUESTION);
        assertThat(new Status(Status.State.DONE, null, Status.Kind.QUESTION, "demo", null, "done", 40).kind())
                .as("a kind means nothing outside blocked")
                .isNull();
        assertThat(new Status(Status.State.DONE, null, null, "demo", null, "done", 40).progress())
                .as("progress means nothing outside working and blocked")
                .isNull();
        assertThat(Status.of(Status.State.CLEAR)
                        .withId("us-east")
                        .withMessage("ignored")
                        .message())
                .as("a clear report carries nothing but its id")
                .isNull();
        assertThat(Status.of(Status.State.CLEAR).withId("us-east").id()).isEqualTo("us-east");

        assertThatThrownBy(() -> Status.working("not an app", null))
                .as("the app grammar is checked")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app");
        assertThatThrownBy(() -> Status.of(Status.State.WORKING).withId("a/b/c/d/e/f/g/h/i"))
                .as("too many segments")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("segments");
        assertThatThrownBy(() -> Status.of(Status.State.WORKING).withId("a".repeat(Status.MAX_ID_SEGMENT + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Status.of(Status.State.WORKING).withId("has space"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Status.of(Status.State.WORKING).withId("").id())
                .as("an empty id is the root record, which is what null means")
                .isNull();
        assertThatThrownBy(() -> Status.working(null, null).withProgress(101))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Status.blocked(null, null, null))
                .as("a blocked record needs a kind")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStatusMessageIsBoundedStrippedAndBase64Encoded() {
        String hostile = "Working\u001B]0;pwned\u0007\nnow";
        Status status = Status.working("demo", hostile);
        assertThat(status.message()).isEqualTo("Working]0;pwnednow");
        String encoded = AnsiEncoder.programStatus(status);
        assertThat(encoded).doesNotContain("\u001B]0;pwned").doesNotContain("\u0007");
        assertThat(encoded).isEqualTo("\u001B]7501;state=working:app=demo:msg=V29ya2luZ10wO3B3bmVkbm93\u001B\\");

        Status unicode = Status.working("demo", "\u00e9".repeat(4000));
        assertThat(unicode.message().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(Status.MAX_MESSAGE_BYTES);
        assertThat(unicode.message()).hasSize(Status.MAX_MESSAGE_BYTES / 2);

        Status title = Status.of(Status.State.WORKING).withTitle("t".repeat(500));
        assertThat(title.title().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(Status.MAX_STATUS_TITLE_BYTES);

        // The largest legal record stays inside the protocol's whole-sequence bound.
        Status largest = new Status(
                Status.State.BLOCKED,
                "a".repeat(Status.MAX_ID_SEGMENT) + "/" + "b".repeat(Status.MAX_ID_SEGMENT) + "/"
                        + "c".repeat(Status.MAX_ID_SEGMENT),
                Status.Kind.PERMISSION,
                "a".repeat(Status.MAX_APP_LENGTH),
                "t".repeat(192),
                "\u754c".repeat(Status.MAX_MESSAGE_BYTES / 3),
                100);
        assertThat(AnsiEncoder.programStatus(largest).getBytes(StandardCharsets.UTF_8).length)
                .as("a report is bounded by the protocol's 4096-byte sequence limit")
                .isLessThanOrEqualTo(4096);
    }

    // ── R-01/R-02: the sequences the encoder writes ──────────────────────────

    @Test
    void theEncoderWritesTheDocumentedProgressAndStatusForms() {
        assertThat(AnsiEncoder.progress(Progress.none())).isEqualTo("\u001B]9;4;0;0\u001B\\");
        assertThat(AnsiEncoder.progress(Progress.normal(40))).isEqualTo("\u001B]9;4;1;40\u001B\\");
        assertThat(AnsiEncoder.progress(Progress.error(70))).isEqualTo("\u001B]9;4;2;70\u001B\\");
        assertThat(AnsiEncoder.progress(Progress.indeterminate())).isEqualTo("\u001B]9;4;3;0\u001B\\");
        assertThat(AnsiEncoder.progress(Progress.paused(90))).isEqualTo("\u001B]9;4;4;90\u001B\\");

        assertThat(AnsiEncoder.programStatus(Status.of(Status.State.IDLE))).isEqualTo("\u001B]7501;state=idle\u001B\\");
        assertThat(AnsiEncoder.programStatus(
                        Status.blocked(Status.Kind.PERMISSION, "terraform", "Apply 3 to add, 1 to change")
                                .withId("eu-west")
                                .withTitle("EU West")
                                .withProgress(40)))
                // The example the protocol itself documents, key for key.
                .isEqualTo("\u001B]7501;state=blocked:id=eu-west:kind=permission:app=terraform:title="
                        + "RVUgV2VzdA==:progress=40:msg=QXBwbHkgMyB0byBhZGQsIDEgdG8gY2hhbmdl\u001B\\");
        assertThat(AnsiEncoder.programStatus(Status.of(Status.State.CLEAR)))
                .isEqualTo("\u001B]7501;state=clear\u001B\\");
        assertThat(AnsiEncoder.programStatus(Status.of(Status.State.CLEAR).withId("us-east")))
                .isEqualTo("\u001B]7501;state=clear:id=us-east\u001B\\");

        assertThat(AnsiEncoder.defaultColor(10, DEFAULT_TEXT)).isEqualTo("\u001B]10;rgb:10/20/30\u001B\\");
        assertThat(AnsiEncoder.defaultColor(11, DEFAULT_BACKDROP)).isEqualTo("\u001B]11;rgb:0a/0b/0c\u001B\\");
        assertThat(AnsiEncoder.defaultColor(12, DEFAULT_CARET)).isEqualTo("\u001B]12;rgb:11/22/33\u001B\\");
        assertThat(AnsiEncoder.cursorColor(DEFAULT_CARET))
                .as("the caret color and the default cursor color are one wire form")
                .isEqualTo(AnsiEncoder.defaultColor(12, DEFAULT_CARET));
        assertThat(AnsiEncoder.RESET_FOREGROUND).isEqualTo("\u001B]110\u001B\\");
        assertThat(AnsiEncoder.RESET_BACKGROUND).isEqualTo("\u001B]111\u001B\\");
        assertThat(AnsiEncoder.RESET_CURSOR_COLOR).isEqualTo("\u001B]112\u001B\\");
        assertThat(AnsiEncoder.PUSH_TITLE).isEqualTo("\u001B[22;0t");
        assertThat(AnsiEncoder.POP_TITLE).isEqualTo("\u001B[23;0t");
        assertThat(AnsiEncoder.QUERY_PROGRAM_STATUS).isEqualTo("\u001B]7501;?\u001B\\");
    }

    // ── R-01: the presenter writes only what changed, and only after a frame ─

    @Test
    void planIsPureAndCommitIsWhatRecordsTheChange() {
        TerminalPresenter presenter = new TerminalPresenter(
                TerminalOptions.DEFAULT, Capabilities.of(true, true, true, true, "xterm-256color"), true);
        TerminalPresentation intent = TerminalPresentation.title("once");

        TerminalPresenter.Transition first = presenter.plan(intent);
        assertThat(first.sequences()).contains(AnsiEncoder.PUSH_TITLE, "\u001B]2;once\u001B\\");
        assertThat(presenter.plan(intent).sequences())
                .as("planning does not record the transition, so the same frame plans again")
                .isEqualTo(first.sequences());

        presenter.commit(first);
        assertThat(presenter.plan(intent).sequences())
                .as("a committed frame that asks for the same thing writes nothing")
                .isEmpty();
        assertThat(presenter.plan(TerminalPresentation.EMPTY).sequences())
                .as("dropping the title pops the one the session pushed")
                .isEqualTo(AnsiEncoder.POP_TITLE);
    }

    @Test
    void theSessionDefaultsComeFromTheOptionsAndTheTerminal() {
        Capabilities caps = Capabilities.of(true, true, true, true, "xterm-256color");
        TerminalPresenter presenter = new TerminalPresenter(
                new TerminalOptions(
                        false, MouseMode.CLICK, false, io.github.dhoard.braxton.input.KeyboardEnhancements.NONE, false),
                caps,
                true);
        assertThat(presenter.open())
                .as("the options' own presentation is what the session opens with")
                .isEqualTo(AnsiEncoder.ENABLE_MOUSE);
        assertThat(presenter.plan(null).sequences())
                .as("a frame that declares nothing keeps the session default")
                .isEmpty();
        assertThat(presenter
                        .plan(TerminalPresentation.EMPTY.withMouseMode(MouseMode.NONE))
                        .sequences())
                .isEqualTo(AnsiEncoder.disableMouse(MouseMode.CLICK));
        presenter.commit(presenter.plan(TerminalPresentation.EMPTY.withMouseMode(MouseMode.NONE)));
        assertThat(presenter.restoreModes())
                .as("only what the session reports is given back")
                .isEmpty();

        TerminalPresenter plain = new TerminalPresenter(TerminalOptions.DEFAULT, Capabilities.PLAIN, true);
        assertThat(plain.open())
                .as("a terminal that offers nothing is asked for nothing")
                .isEmpty();
        assertThat(plain.plan(TerminalPresentation.EMPTY
                                .withAlternateScreen(true)
                                .withBracketedPaste(true)
                                .withMouseMode(MouseMode.ALL_MOTION))
                        .sequences())
                .as("an intent cannot ask for a mode the terminal never offered")
                .isEmpty();
        assertThat(plain.alternateScreenEntered()).isFalse();
    }

    @Test
    void metadataIsWrittenWhenItChangesAndGivenBackOnTheWayOut() {
        TerminalPresenter presenter = new TerminalPresenter(
                TerminalOptions.DEFAULT, Capabilities.of(true, true, true, true, "xterm-256color"), true);
        TerminalPresentation intent = TerminalPresentation.EMPTY
                .withTitle("braxton")
                .withForeground(DEFAULT_TEXT)
                .withBackground(DEFAULT_BACKDROP)
                .withCursorColor(DEFAULT_CARET)
                .withProgress(Progress.normal(40))
                .withStatus(Status.working("demo", "Working"));
        presenter.commit(presenter.plan(intent));

        assertThat(presenter.plan(intent).sequences()).as("nothing changed").isEmpty();
        assertThat(presenter.plan(intent.withProgress(Progress.normal(41))).sequences())
                .as("a changed value is written on its own")
                .isEqualTo("\u001B]9;4;1;41\u001B\\");
        TerminalPresenter.Transition dropped = presenter.plan(TerminalPresentation.EMPTY);
        assertThat(dropped.sequences())
                .as("dropping every field gives back everything the session set")
                .contains(AnsiEncoder.POP_TITLE)
                .contains(AnsiEncoder.RESET_FOREGROUND)
                .contains(AnsiEncoder.RESET_BACKGROUND)
                .contains(AnsiEncoder.RESET_CURSOR_COLOR)
                .contains("\u001B]9;4;0;0\u001B\\")
                .contains("\u001B]7501;state=clear\u001B\\");
        presenter.commit(dropped);
        assertThat(presenter.restoreMetadata())
                .as("a session that already gave its metadata back writes nothing more")
                .isEmpty();
    }

    @Test
    void aColourlessSessionNeverSetsAColourAndGivesBackTheOnesItHad() {
        TerminalPresenter presenter = new TerminalPresenter(
                TerminalOptions.DEFAULT, Capabilities.of(true, true, true, true, "xterm-256color"), true);
        TerminalPresentation intent =
                TerminalPresentation.EMPTY.withForeground(DEFAULT_TEXT).withBackground(DEFAULT_BACKDROP);
        presenter.commit(presenter.plan(intent));

        presenter.colorEnabled(false);
        assertThat(presenter.plan(intent).sequences())
                .as("losing color resets the defaults instead of leaving them applied")
                .isEqualTo(AnsiEncoder.RESET_FOREGROUND + AnsiEncoder.RESET_BACKGROUND);
        presenter.commit(presenter.plan(intent));

        TerminalPresenter colourless =
                new TerminalPresenter(TerminalOptions.DEFAULT, Capabilities.of(false, true, true, true, "dumb"), false);
        assertThat(colourless.plan(intent).sequences())
                .as("a session without color never sets a terminal default color")
                .isEmpty();
    }

    @Test
    void aFinalStatusIsRetainedWhileARunInProgressIsCleared() {
        TerminalPresenter presenter = new TerminalPresenter(
                TerminalOptions.DEFAULT, Capabilities.of(true, true, true, true, "xterm-256color"), true);
        presenter.commit(presenter.plan(TerminalPresentation.EMPTY.withStatus(Status.of(Status.State.DONE))));
        assertThat(presenter.restoreMetadata())
                .as("a finished result is what the protocol keeps for the user")
                .isEmpty();

        TerminalPresenter working = new TerminalPresenter(
                TerminalOptions.DEFAULT, Capabilities.of(true, true, true, true, "xterm-256color"), true);
        working.commit(working.plan(TerminalPresentation.EMPTY.withStatus(
                Status.working("demo", "Working").withId("build"))));
        assertThat(working.restoreMetadata())
                .as("a run in progress does not outlive the session that reported it")
                .isEqualTo("\u001B]7501;state=clear:id=build\u001B\\");

        TerminalPresenter cleared = new TerminalPresenter(
                TerminalOptions.DEFAULT, Capabilities.of(true, true, true, true, "xterm-256color"), true);
        cleared.commit(cleared.plan(TerminalPresentation.EMPTY.withStatus(
                Status.of(Status.State.CLEAR).withId("us-east"))));
        assertThat(cleared.restoreMetadata())
                .as("a record the application already removed is not removed twice")
                .isEmpty();
    }

    // ── R-01/R-04: the mounted session presents and restores ─────────────────

    /**
     * One message the scene handles.
     */
    private sealed interface Msg {

        /**
         * Advance to the next presentation the scene was given.
         */
        record Advance() implements Msg {}

        /**
         * End the session.
         */
        record Quit() implements Msg {}
    }

    /**
     * A scene that presents one intent per step, advancing on {@code a} and ending on {@code q}.
     *
     * @param steps the presentations to present, in order; the last one repeats
     * @return the program
     */
    private static Component<Integer, Msg, Never> scene(List<TerminalPresentation> steps) {
        return new Component<>() {
            @Override
            public Update<Integer, Msg, Never> init(InitContext context) {
                return Update.next(0);
            }

            @Override
            public Update<Integer, Msg, Never> update(Integer step, Msg message) {
                return switch (message) {
                    case Msg.Advance advance -> Update.next(Math.min(steps.size() - 1, step + 1));
                    case Msg.Quit quit -> Update.next(step, Command.quit());
                };
            }

            @Override
            public Element<Msg> view(Integer step, ViewContext context) {
                return Elements.text(SCENE_TEXT + step);
            }

            @Override
            public TerminalPresentation presentation(Integer step) {
                return steps.get(step);
            }

            @Override
            public java.util.List<Binding<Msg>> claims(Integer step) {
                return List.of(Binding.of("a", new Msg.Advance()), Binding.of("q", new Msg.Quit()));
            }
        };
    }

    /**
     * Runs one scene, answering each painted step with {@code a} and finishing with {@code q}.
     */
    private static ExitResult runSteps(TestTerminal fixture, List<TerminalPresentation> steps, RuntimeOptions options) {
        AtomicInteger fed = new AtomicInteger();
        fixture.onOutput(text -> {
            // A frame is the unit to answer, not the scene's text: after the first frame the session
            // writes only the cells that changed, so the text of a later step may arrive as one
            // corrected digit and never appear as a whole line again.
            if (text.contains("\u001B[?2026h")) {
                // Each frame is answered once, so every step is its own input turn and is presented.
                if (fed.incrementAndGet() < steps.size()) {
                    fixture.feed("a");
                } else {
                    fixture.feed("q");
                }
            }
        });
        return TestTerminal.run(scene(steps), options, fixture);
    }

    @Test
    void theFrameAsksForItsPresentationAndTheExitGivesItBack() {
        try (TestTerminal fixture = new TestTerminal()) {
            TerminalPresentation intent = TerminalPresentation.EMPTY
                    .withTitle("braxton")
                    .withForeground(DEFAULT_TEXT)
                    .withBackground(DEFAULT_BACKDROP)
                    .withCursorColor(DEFAULT_CARET)
                    .withProgress(Progress.normal(40))
                    .withStatus(Status.working("demo", "Working"));
            ExitResult result = runSteps(
                    fixture, List.of(intent), RuntimeOptions.defaults().withColorProfile(ColorProfile.TRUECOLOR));

            assertThat(result.failure()).isNull();
            String output = fixture.outputText();
            assertThat(output)
                    .as("the frame carries the metadata it declared")
                    .contains(AnsiEncoder.PUSH_TITLE)
                    .contains("\u001B]2;braxton\u001B\\")
                    .contains("\u001B]10;rgb:10/20/30\u001B\\")
                    .contains("\u001B]11;rgb:0a/0b/0c\u001B\\")
                    .contains("\u001B]12;rgb:11/22/33\u001B\\")
                    .contains("\u001B]9;4;1;40\u001B\\")
                    .contains("\u001B]7501;state=working:app=demo:msg=V29ya2luZw==\u001B\\");
            assertThat(output)
                    .as("the exit pops the title, resets the colors, removes the progress and clears the run")
                    .contains(AnsiEncoder.POP_TITLE)
                    .contains(AnsiEncoder.RESET_FOREGROUND)
                    .contains(AnsiEncoder.RESET_BACKGROUND)
                    .contains(AnsiEncoder.RESET_CURSOR_COLOR)
                    .contains("\u001B]9;4;0;0\u001B\\")
                    .contains("\u001B]7501;state=clear\u001B\\")
                    .contains(AnsiEncoder.ALT_SCREEN_OFF);
            assertThat(count(output, AnsiEncoder.RESET_FOREGROUND))
                    .as("a default that was set once is reset once")
                    .isEqualTo(1);
        }
    }

    @Test
    void aFrameThatDeclaresNothingWritesNoMetadataAtAll() {
        try (TestTerminal fixture = new TestTerminal()) {
            runSteps(
                    fixture,
                    List.of(TerminalPresentation.EMPTY, TerminalPresentation.EMPTY),
                    RuntimeOptions.defaults());
            String output = fixture.outputText();
            assertThat(output)
                    .doesNotContain(
                            "\u001B]2;",
                            "\u001B]10;",
                            "\u001B]11;",
                            "\u001B]12;",
                            "\u001B]9;4;",
                            "\u001B]7501;",
                            AnsiEncoder.PUSH_TITLE,
                            AnsiEncoder.POP_TITLE);
            assertThat(output).contains(AnsiEncoder.ALT_SCREEN_ON, AnsiEncoder.ALT_SCREEN_OFF);
        }
    }

    @Test
    void repeatedValuesAreNotWrittenTwice() {
        try (TestTerminal fixture = new TestTerminal()) {
            TerminalPresentation titled =
                    TerminalPresentation.EMPTY.withTitle("braxton").withProgress(Progress.normal(40));
            runSteps(fixture, List.of(titled, titled), RuntimeOptions.defaults());
            String output = fixture.outputText();
            assertThat(count(output, "\u001B]2;braxton\u001B\\")).isEqualTo(1);
            assertThat(count(output, "\u001B]9;4;1;40\u001B\\")).isEqualTo(1);
            assertThat(count(output, AnsiEncoder.PUSH_TITLE)).isEqualTo(1);
        }
    }

    @Test
    void leavingTheAlternateScreenRepaintsEverythingAndComingBackRepaintsAgain() {
        try (TestTerminal fixture = new TestTerminal()) {
            TerminalPresentation plain = TerminalPresentation.EMPTY.withAlternateScreen(false);
            runSteps(
                    fixture,
                    List.of(TerminalPresentation.EMPTY, plain, TerminalPresentation.EMPTY),
                    RuntimeOptions.defaults());
            String output = fixture.outputText();

            assertThat(output).contains(AnsiEncoder.ALT_SCREEN_ON, AnsiEncoder.ALT_SCREEN_OFF);
            int left = output.indexOf(AnsiEncoder.ALT_SCREEN_OFF);
            int back = output.indexOf(AnsiEncoder.ALT_SCREEN_ON, left);
            assertThat(left).as("the switch away from the alternate screen").isGreaterThan(0);
            assertThat(back).as("and the switch back to it").isGreaterThan(left);
            assertThat(output.substring(left, back))
                    .as("a switched buffer is repainted in full rather than diffed")
                    .contains(SCENE_TEXT);
            assertThat(output.substring(back))
                    .as("and so is the buffer switched back to")
                    .contains(SCENE_TEXT);
        }
    }

    @Test
    void aFrameChangeReachesTheTerminalAsOneWriteInsideOneSynchronizedUpdate() {
        try (TestTerminal fixture = new TestTerminal()) {
            runSteps(fixture, List.of(TerminalPresentation.title("braxton")), RuntimeOptions.defaults());
            boolean sawTitleWithContent = false;
            for (String write : fixture.writes()) {
                if (write.contains("\u001B]2;braxton\u001B\\")) {
                    assertThat(write)
                            .as("the metadata and the cells are one atomic frame")
                            .contains(SCENE_TEXT);
                    sawTitleWithContent = true;
                }
            }
            assertThat(sawTitleWithContent).isTrue();
        }
    }

    @Test
    void aFinishedStatusSurvivesTheExitAndARunInProgressDoesNot() {
        try (TestTerminal fixture = new TestTerminal()) {
            ExitResult result = runSteps(
                    fixture,
                    List.of(TerminalPresentation.EMPTY.withStatus(
                            Status.of(Status.State.DONE).withApp("demo"))),
                    RuntimeOptions.defaults());
            assertThat(result.failure()).isNull();
            assertThat(fixture.outputText())
                    .contains("\u001B]7501;state=done:app=demo\u001B\\")
                    .doesNotContain("state=clear");
        }
        try (TestTerminal fixture = new TestTerminal()) {
            runSteps(
                    fixture,
                    List.of(TerminalPresentation.EMPTY.withStatus(
                            Status.working("demo", "Working").withId("build"))),
                    RuntimeOptions.defaults());
            assertThat(fixture.outputText()).contains("\u001B]7501;state=clear:id=build\u001B\\");
        }
    }

    @Test
    void aFailedFrameNeverRecordsTheModeItCouldNotAnnounce() {
        // The ledger is committed only after the write, so a plan repeated before a commit is what a
        // failed frame looks like: the session never believes it entered the mode.
        TerminalPresenter presenter = new TerminalPresenter(
                TerminalOptions.DEFAULT, Capabilities.of(true, true, true, true, "xterm-256color"), true);
        TerminalPresenter.Transition transition = presenter.plan(TerminalPresentation.EMPTY.withAlternateScreen(false));
        assertThat(transition.bufferChanged()).isTrue();
        assertThat(transition.sequences()).isEqualTo(AnsiEncoder.ALT_SCREEN_OFF);
        assertThat(presenter.alternateScreenEntered())
                .as("an uncommitted transition changed nothing")
                .isTrue();
        assertThat(presenter.restoreModes())
                .as("so the exit gives back the session's own paste mode and not the mode it never entered")
                .isEqualTo(AnsiEncoder.DISABLE_BRACKETED_PASTE);
        assertThat(presenter.leaveAlternateScreen()).isEqualTo(AnsiEncoder.ALT_SCREEN_OFF);
    }

    @Test
    void aRunThatFailsStillGivesTheTerminalBack() {
        try (TestTerminal fixture = new TestTerminal()) {
            Component<Integer, Msg, Never> program = new Component<>() {
                @Override
                public Update<Integer, Msg, Never> init(InitContext context) {
                    return Update.next(0);
                }

                @Override
                public Update<Integer, Msg, Never> update(Integer frame, Msg message) {
                    return switch (message) {
                        case Msg.Advance advance -> Update.next(frame + 1);
                        case Msg.Quit quit -> Update.next(frame, Command.quit());
                    };
                }

                @Override
                public Element<Msg> view(Integer frame, ViewContext context) {
                    if (frame > 0) {
                        throw new IllegalStateException("the scene cannot paint");
                    }
                    return Elements.text(SCENE_TEXT + frame);
                }

                @Override
                public TerminalPresentation presentation(Integer frame) {
                    return TerminalPresentation.EMPTY.withTitle("braxton").withProgress(Progress.normal(40));
                }

                @Override
                public java.util.List<Binding<Msg>> claims(Integer frame) {
                    return List.of(new Binding<>(Key.of("a"), new Msg.Advance()));
                }
            };
            fixture.onOutput(text -> {
                if (text.contains(SCENE_TEXT)) {
                    fixture.feed("a");
                }
            });
            ExitResult result = TestTerminal.run(program, RuntimeOptions.defaults(), fixture);

            assertThat(result.failure()).isNotNull();
            assertThat(result.failure().getMessage()).contains("VIEW");
            String output = fixture.outputText();
            assertThat(output)
                    .as("the metadata the first frame applied is given back on the failure path")
                    .contains(AnsiEncoder.PUSH_TITLE, "\u001B]2;braxton\u001B\\", AnsiEncoder.POP_TITLE)
                    .contains("\u001B]9;4;1;40\u001B\\", "\u001B]9;4;0;0\u001B\\")
                    .contains(AnsiEncoder.ALT_SCREEN_OFF, AnsiEncoder.SHOW_CURSOR);
        }
    }

    // ── R-03: the program-status support query ──────────────────────────────

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

        void accept(TerminalReply reply) {
            queries.accept(reply);
        }

        void advance(Duration elapsed) {
            timers.advance(elapsed.toNanos());
        }
    }

    @Test
    void theSupportQueryAsksTheDocumentedQuestionAndReportsWhatTheTerminalLists() {
        Engine engine = new Engine();
        engine.ask("status", new TerminalQuery.ProgramStatus());
        assertThat(engine.written).containsExactly(AnsiEncoder.QUERY_PROGRAM_STATUS);

        engine.accept(new TerminalReply.OperatingSystem(
                AnsiEncoder.PROGRAM_STATUS_OSC, "?:states=idle,working,done:kindz=permission"));
        assertThat(engine.delivered)
                .as("an unknown key is ignored and a list the terminal left out is the default set")
                .containsExactly(new TerminalQuery.Reply.ProgramStatus(
                        Set.of("idle", "working", "done"), TerminalQuery.Reply.ProgramStatus.DEFAULT_KINDS));
        assertThat(engine.queries.live()).isZero();

        engine.ask("plain", new TerminalQuery.ProgramStatus());
        engine.accept(new TerminalReply.OperatingSystem(AnsiEncoder.PROGRAM_STATUS_OSC, "?"));
        assertThat(engine.delivered.get(1))
                .as("a reply that lists nothing means exactly the protocol's fixed sets")
                .isEqualTo(new TerminalQuery.Reply.ProgramStatus(null, null));
        TerminalQuery.Reply.ProgramStatus support = (TerminalQuery.Reply.ProgramStatus) engine.delivered.get(1);
        assertThat(support.supportsState("blocked")).isTrue();
        assertThat(support.supportsState("sleeping")).isFalse();
        assertThat(support.supportsKind("auth")).isTrue();
    }

    @Test
    void theSupportQuerySkipsMalformedPairsAndRefusesAnAnswerItCannotBelieve() {
        Engine engine = new Engine();
        engine.ask("status", new TerminalQuery.ProgramStatus());
        engine.accept(new TerminalReply.OperatingSystem(
                AnsiEncoder.PROGRAM_STATUS_OSC, "?:states=working,bad!!name,,done:kinds"));
        assertThat(engine.delivered)
                .as("only the names the protocol's character set allows are read")
                .containsExactly(new TerminalQuery.Reply.ProgramStatus(
                        Set.of("working", "done"), TerminalQuery.Reply.ProgramStatus.DEFAULT_KINDS));

        engine.ask("bad", new TerminalQuery.ProgramStatus());
        engine.accept(new TerminalReply.OperatingSystem(AnsiEncoder.PROGRAM_STATUS_OSC, "states=idle"));
        assertThat(engine.delivered.get(1))
                .as("a report is not a detection answer")
                .isInstanceOf(TerminalQuery.Reply.Malformed.class);

        engine.ask("other", new TerminalQuery.ProgramStatus());
        int delivered = engine.delivered.size();
        engine.accept(new TerminalReply.OperatingSystem(7502, "?"));
        assertThat(engine.delivered)
                .as("another command is another question, so it satisfies nothing")
                .hasSize(delivered);
        engine.advance(Duration.ofSeconds(1));
        assertThat(engine.delivered.get(delivered))
                .as("the unanswered query reports the silence")
                .isInstanceOf(TerminalQuery.Reply.Timeout.class);
    }

    @Test
    void aTerminalThatNeverAnswersTheSupportQueryIsReportedAsSilence() {
        Engine engine = new Engine();
        engine.ask("status", new TerminalQuery.ProgramStatus());
        engine.advance(Duration.ofSeconds(1));
        assertThat(engine.delivered)
                .containsExactly(new TerminalQuery.Reply.Timeout(new TerminalQuery.ProgramStatus()));

        engine.accept(new TerminalReply.OperatingSystem(AnsiEncoder.PROGRAM_STATUS_OSC, "?:states=idle"));
        assertThat(engine.delivered)
                .as("a reply after the deadline satisfies nothing")
                .hasSize(1);
    }

    @Test
    void anUnsolicitedStatusDetectionAnswerReachesNoApplication() {
        Engine engine = new Engine();
        engine.accept(new TerminalReply.OperatingSystem(AnsiEncoder.PROGRAM_STATUS_OSC, "?:states=idle"));
        assertThat(engine.delivered).isEmpty();
        assertThat(engine.written).as("nothing is asked at startup").isEmpty();
    }

    // ── R-05: bounds and preserved behavior ────────────────────────────────

    @Test
    void anEmptyViewportStillPresentsAndRestores() {
        try (TestTerminal fixture = new TestTerminal(new io.github.dhoard.braxton.layout.Size(2, 8))) {
            ExitResult result =
                    runSteps(fixture, List.of(TerminalPresentation.title("tiny")), RuntimeOptions.defaults());
            assertThat(result.failure()).isNull();
            assertThat(fixture.outputText()).contains("\u001B]2;tiny\u001B\\", AnsiEncoder.POP_TITLE);
        }
    }

    @Test
    void aTitleIsGraphemeSafeForEveryClusterTheTextMeasureKnows() {
        String text = "\uD83D\uDE00\uD83C\uDFFD" + "a\u0308" + "\u754c";
        assertThat(TerminalPresentation.EMPTY.withTitle(text).title()).isEqualTo(text);
        assertThat(TextMeasure.graphemes(
                        TerminalPresentation.EMPTY.withTitle(text).title()))
                .isEqualTo(TextMeasure.graphemes(text));
    }
}

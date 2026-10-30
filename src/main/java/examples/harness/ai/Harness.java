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

package examples.harness.ai;

import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.widget.Editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The AI Harness as one ordinary MVU program.
 *
 * <p>Transcript loading is a task command, response pacing is a delayed message, animation is a
 * timer subscription, and every interaction is a message. There is no worker mutating state, no
 * per-application executor, no global key registry, and no terminal access outside the runtime.</p>
 */
public final class Harness implements Component<Harness.Model, Harness.Msg, Never> {
    public record Model(
            Session.Model session,
            Editor.Model draft,
            List<String> pending,
            Active active,
            long generation,
            long nowNanos,
            long workStartedNanos,
            long escArmedUntilNanos,
            long quitArmedUntilNanos,
            Size viewport,
            String projectPath) {
        public static final int MAX_ENTRIES = 200;

        public record Active(HarnessTranscript.HarnessTurn turn, int blockIndex) {}

        private static final Session SESSION =
                new Session(() -> HarnessTranscript.empty(List.of()), List.of());

        public Model {
            Objects.requireNonNull(session, "session");
            draft = draft == null ? Editor.Model.empty() : draft;
            pending = List.copyOf(pending);
            escArmedUntilNanos = Math.max(0L, escArmedUntilNanos);
            quitArmedUntilNanos = Math.max(0L, quitArmedUntilNanos);
            viewport = viewport == null || viewport.isEmpty() ? new Size(24, 80) : viewport;
            projectPath = projectPath == null ? "" : projectPath;
        }

        public static Harness.Model initial(Size size, String path, List<String> warnings) {
            return new Harness.Model(
                    Session.initial(warnings),
                    Editor.Model.empty(),
                    List.of(),
                    null,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    size,
                    path);
        }

        public boolean working() {
            return active != null;
        }

        public int editorRows() {
            return Editor.visibleLineCount(draft);
        }

        public int outstandingPrompts() {
            return pending.size() + (working() ? 1 : 0);
        }

        public Session.Status status() {
            return session.status();
        }

        public List<HarnessEntry> entries() {
            return session.entries();
        }

        public HarnessTranscript.Profile profile() {
            return session.profile();
        }

        public HarnessTranscript.Usage usage() {
            return session.usage();
        }

        public int contextWindow() {
            return session.contextWindow();
        }

        public List<HarnessTranscript.HarnessTurn> turns() {
            return session.turns();
        }

        public int replayIndex() {
            return session.replayIndex();
        }

        public int scrollBack() {
            return session.scrollBack();
        }

        public String statusMessage() {
            return session.statusMessage();
        }

        public Harness.Model withSession(Session.Model value) {
            return new Harness.Model(
                    value,
                    draft,
                    pending,
                    active,
                    generation,
                    nowNanos,
                    workStartedNanos,
                    escArmedUntilNanos,
                    quitArmedUntilNanos,
                    viewport,
                    projectPath);
        }

        public Harness.Model withDraft(Editor.Model value) {
            return new Harness.Model(
                    session,
                    value,
                    pending,
                    active,
                    generation,
                    nowNanos,
                    workStartedNanos,
                    escArmedUntilNanos,
                    quitArmedUntilNanos,
                    viewport,
                    projectPath);
        }

        public Harness.Model withPending(List<String> value) {
            return new Harness.Model(
                    session,
                    draft,
                    value,
                    active,
                    generation,
                    nowNanos,
                    workStartedNanos,
                    escArmedUntilNanos,
                    quitArmedUntilNanos,
                    viewport,
                    projectPath);
        }

        public Harness.Model withActive(Active value) {
            return new Harness.Model(
                    session,
                    draft,
                    pending,
                    value,
                    generation,
                    nowNanos,
                    workStartedNanos,
                    escArmedUntilNanos,
                    quitArmedUntilNanos,
                    viewport,
                    projectPath);
        }

        public Harness.Model withGeneration(long value) {
            return new Harness.Model(
                    session,
                    draft,
                    pending,
                    active,
                    value,
                    nowNanos,
                    workStartedNanos,
                    escArmedUntilNanos,
                    quitArmedUntilNanos,
                    viewport,
                    projectPath);
        }

        public Harness.Model withNow(long value) {
            return new Harness.Model(
                    session,
                    draft,
                    pending,
                    active,
                    generation,
                    value,
                    workStartedNanos,
                    escArmedUntilNanos,
                    quitArmedUntilNanos,
                    viewport,
                    projectPath);
        }

        public Harness.Model withWorkStarted(long value) {
            return new Harness.Model(
                    session,
                    draft,
                    pending,
                    active,
                    generation,
                    nowNanos,
                    value,
                    escArmedUntilNanos,
                    quitArmedUntilNanos,
                    viewport,
                    projectPath);
        }

        public Harness.Model withEscArmed(long value) {
            return new Harness.Model(
                    session,
                    draft,
                    pending,
                    active,
                    generation,
                    nowNanos,
                    workStartedNanos,
                    value,
                    quitArmedUntilNanos,
                    viewport,
                    projectPath);
        }

        public Harness.Model withQuitArmed(long value) {
            return new Harness.Model(
                    session,
                    draft,
                    pending,
                    active,
                    generation,
                    nowNanos,
                    workStartedNanos,
                    escArmedUntilNanos,
                    value,
                    viewport,
                    projectPath);
        }

        public Harness.Model withViewport(Size value) {
            return new Harness.Model(
                    session,
                    draft,
                    pending,
                    active,
                    generation,
                    nowNanos,
                    workStartedNanos,
                    escArmedUntilNanos,
                    quitArmedUntilNanos,
                    value,
                    projectPath);
        }

        public Harness.Model withProjectPath(String value) {
            return new Harness.Model(
                    session,
                    draft,
                    pending,
                    active,
                    generation,
                    nowNanos,
                    workStartedNanos,
                    escArmedUntilNanos,
                    quitArmedUntilNanos,
                    viewport,
                    value);
        }

        public Harness.Model withStatus(Session.Status value) {
            return withSession(SESSION.update(session, new Session.Msg.StatusValue(value)).model());
        }

        public Harness.Model withEntries(List<HarnessEntry> value) {
            return withSession(SESSION.update(session, new Session.Msg.Entries(value)).model());
        }

        public Harness.Model withScroll(int value) {
            return withSession(SESSION.update(session, new Session.Msg.Scroll(value)).model());
        }

        public Harness.Model withReplayIndex(int value) {
            return withSession(SESSION.update(session, new Session.Msg.Replay(value)).model());
        }

        public Harness.Model withStatusMessage(String value) {
            return withSession(SESSION.update(session, new Session.Msg.Notice(value)).model());
        }

        public Harness.Model withTranscript(HarnessTranscript value) {
            return withSession(SESSION.update(session, new Session.Msg.Loaded(value)).model());
        }

        public Harness.Model withMetadata(
                HarnessTranscript.Profile profile, HarnessTranscript.Usage usage) {
            return withSession(
                    SESSION.update(session, new Session.Msg.Metadata(profile, usage)).model());
        }

        public Harness.Model disarmQuit() {
            return withQuitArmed(0L);
        }
    }

    public sealed interface Msg {

        record SessionMsg(Session.Msg message) implements Harness.Msg {}

        record ComposerOutput(Editor.Output output) implements Harness.Msg {}

        /** One editor gesture, mapped from the composer view. */
        public record ComposerMsg(Editor.Msg action) implements Harness.Msg {}

        /** A submission request (used by the editor intent, not by any widget callback). */
        public record Submit(String text) implements Harness.Msg {
            public Submit {
                text = text == null ? "" : text;
            }
        }

        /** Periodic time update; carries the runtime's monotonic clock. */
        public record Tick(long nowNanos) implements Harness.Msg {}

        /** Terminal geometry changed. */
        public record Resized(Size size) implements Harness.Msg {}

        public record ScrollUp() implements Harness.Msg {}

        public record ScrollDown() implements Harness.Msg {}

        public record PageUp() implements Harness.Msg {}

        public record PageDown() implements Harness.Msg {}

        public record ScrollBottom() implements Harness.Msg {}

        /** One Escape press, with its capture time so the window never depends on a tick. */
        public record Escape(long atNanos) implements Harness.Msg {}

        /** One Ctrl+C press with its capture time; two within two seconds exit. */
        public record QuitRequest(long atNanos) implements Harness.Msg {}

        /** A scheduled mock response block; only the matching generation and ordinal is accepted. */
        public record ResponseBlock(long generation, int ordinal) implements Harness.Msg {}

        /** The clipboard reported what it actually stored and wrote. */
        public record Copied(ClipboardResult result) implements Harness.Msg {}

        /** Copy the visible transcript as plain text (Alt+T). */
        public record CopyVisible() implements Harness.Msg {}

        /** Copy the most recent assistant reply (Alt+L). */
        public record CopyLastReply() implements Harness.Msg {}

        /** Tab asks for the composer to be focused. */
        public record FocusComposer() implements Harness.Msg {}

        /**
         * A key the composer did not consume. Routing changes state only through messages, so this is
         * what lets any other key cancel a pending time-based confirmation.
         */
        public record UnhandledKey() implements Harness.Msg {}

        /** A non-fatal notice, such as an overload rejection. */
        public record Notice(String text) implements Harness.Msg {}

        /** Request application exit. */
        public record Quit() implements Harness.Msg {}
    }

    /** Each recorded block (one transcript box) is delivered this far apart so the replay is
     * watchable rather than appearing all at once. */
    static final Duration RESPONSE_DELAY = Duration.ofSeconds(2);

    static final Duration TICK = Duration.ofMillis(50);
    static final long ESC_WINDOW_NANOS = 2_000_000_000L;

    /** A second Ctrl+C must arrive within this window for the first one to mean "exit". */
    static final long QUIT_WINDOW_NANOS = 2_000_000_000L;

    static final int MAX_OUTSTANDING = 64;
    static final int DRAFT_LIMIT = 4_096;
    static final int MAX_ENTRIES = Harness.Model.MAX_ENTRIES;

    /** The status-rule text that describes an armed Ctrl+C confirmation. */
    static final String QUIT_CONFIRMATION_HINT = "Press Ctrl+C again within 2 s to exit.";

    private static final EffectId RESPONSE_ID = EffectId.of("response");
    private static final SubscriptionId TICK_ID = SubscriptionId.of("tick");
    private static final SubscriptionId RESIZE_ID = SubscriptionId.of("resize");
    static final Mount<Harness.Model, Harness.Msg, Never, Session.Model, Session.Msg, Never>
            SESSION =
                    sessionMount(new Session(() -> HarnessTranscript.empty(List.of()), List.of()));

    static Mount<Harness.Model, Harness.Msg, Never, Session.Model, Session.Msg, Never> sessionMount(
            Session session) {
        return Mount.of(
                ComponentId.of("session"),
                session,
                ModelLens.of(Harness.Model::session, Harness.Model::withSession),
                Harness.Msg.SessionMsg::new,
                OutputRoute.toOutput(value -> value));
    }

    static Mount<Harness.Model, Harness.Msg, Never, Editor.Model, Editor.Msg, Editor.Output>
            composer(int visibleLines) {
        return Mount.of(
                ComponentId.of("composer"),
                new Editor(editorOptions().withVisibleLines(visibleLines)),
                ModelLens.of(Harness.Model::draft, Harness.Model::withDraft),
                Harness.Msg.ComposerMsg::new,
                OutputRoute.toMessage(Harness.Msg.ComposerOutput::new));
    }

    private static final Mount<
                    Harness.Model, Harness.Msg, Never, Editor.Model, Editor.Msg, Editor.Output>
            COMPOSER = composer(6);

    private final Supplier<HarnessTranscript> loader;
    private final String projectPath;
    private final List<String> startupWarnings;

    public Harness(
            Supplier<HarnessTranscript> loader, String projectPath, List<String> startupWarnings) {
        this.loader = loader == null ? () -> HarnessTranscript.empty(List.of()) : loader;
        this.projectPath = projectPath == null ? "" : projectPath;
        this.startupWarnings = List.copyOf(startupWarnings == null ? List.of() : startupWarnings);
    }

    static Editor.Config editorOptions() {
        return Editor.Config.defaults()
                .withMaxLength(DRAFT_LIMIT)
                .withPrompt("> ", HarnessTheme.TEXT)
                .withStyles(HarnessTheme.TEXT, HarnessTheme.TEXT, HarnessTheme.TEXT.withInvert());
    }

    @Override
    public Update<Harness.Model, Harness.Msg, Never> init(InitContext context) {
        Harness.Model state =
                Harness.Model.initial(context.viewport().size(), projectPath, startupWarnings);
        var session = sessionMount(new Session(loader, startupWarnings)).initialize(context);
        var editor = COMPOSER.initialize(context);
        return Update.next(
                state.withSession(session.model()).withDraft(editor.model()),
                Command.batch(session.command(), editor.command()));
    }

    @Override
    public View<Harness.Msg> view(Harness.Model model, ViewContext context) {
        Viewport viewport = context.viewport();
        Harness.Model sized =
                model.viewport().equals(viewport.size())
                        ? model
                        : model.withViewport(viewport.size());
        return HarnessView.view(sized);
    }

    @Override
    public Subscription<Harness.Msg> subscriptions(Harness.Model model) {
        Subscription<Harness.Msg> tick =
                model.working() || model.escArmedUntilNanos() > 0 || model.quitArmedUntilNanos() > 0
                        ? Subscription.interval(TICK_ID, TICK, Harness.Msg.Tick::new)
                        : Subscription.none();
        Subscription<Harness.Msg> resize = Subscription.resize(RESIZE_ID, Harness.Msg.Resized::new);
        return Subscription.batch(tick, resize);
    }

    @Override
    public Update<Harness.Model, Harness.Msg, Never> update(
            Harness.Model model, Harness.Msg message) {
        model = disarmQuitOnUserAction(model, message);
        return switch (message) {
            case Harness.Msg.SessionMsg session -> SESSION.update(model, session.message());
            case Harness.Msg.ComposerMsg editor -> COMPOSER.update(model, editor.action());
            case Harness.Msg.ComposerOutput output -> editorOutput(model, output.output());
            case Harness.Msg.Submit submit -> submit(model, submit.text());
            case Harness.Msg.Tick tick -> tick(model, tick.nowNanos());
            case Harness.Msg.Resized resized -> Update.next(model.withViewport(resized.size()));
            case Harness.Msg.ScrollUp up ->
                    Update.next(scrollBy(model, HarnessProjection.SCROLL_STEP));
            case Harness.Msg.ScrollDown down ->
                    Update.next(scrollBy(model, -HarnessProjection.SCROLL_STEP));
            case Harness.Msg.PageUp pageUp -> Update.next(scrollBy(model, pageRows(model)));
            case Harness.Msg.PageDown pageDown -> Update.next(scrollBy(model, -pageRows(model)));
            case Harness.Msg.ScrollBottom bottom -> Update.next(model.withScroll(0));
            case Harness.Msg.Escape escape -> escape(model, escape.atNanos());
            case Harness.Msg.ResponseBlock block -> responseBlock(model, block);
            case Harness.Msg.Copied copied ->
                    Update.next(model.withStatusMessage(clipboardStatus(copied.result())));
            case Harness.Msg.CopyVisible copy ->
                    Update.next(
                            model,
                            Command.copy(
                                    HarnessProjection.visiblePlainText(model),
                                    Harness.Msg.Copied::new));
            case Harness.Msg.CopyLastReply copy -> copyLastReply(model);
            case Harness.Msg.FocusComposer focus -> Update.next(model);
            case Harness.Msg.UnhandledKey ignored -> Update.next(model);
            case Harness.Msg.Notice notice -> Update.next(model.withStatusMessage(notice.text()));
            case Harness.Msg.QuitRequest request -> quitRequest(model, request.atNanos());
            case Harness.Msg.Quit quit -> Update.next(model, Command.quit());
        };
    }

    /**
     * A pending Ctrl+C confirmation survives only until another user action. Background events
     * (ticks, response blocks, clipboard reports, loading) do not cancel it.
     */
    private static Harness.Model disarmQuitOnUserAction(Harness.Model model, Harness.Msg message) {
        if (model.quitArmedUntilNanos() == 0L) {
            return model;
        }
        return isUserAction(message) ? endQuitConfirmation(model) : model;
    }

    /**
     * Ends an armed confirmation together with the hint that describes it. The hint is transient
     * feedback for exactly the window it announces, so a confirmation that is abandoned or has
     * expired must not leave the status rule claiming that a second Ctrl+C exits: the top rule
     * already drops its own hint, and the promise would be false. A status message some later
     * event put in its place is left alone.
     */
    private static Harness.Model endQuitConfirmation(Harness.Model model) {
        Harness.Model disarmed = model.disarmQuit();
        return QUIT_CONFIRMATION_HINT.equals(disarmed.statusMessage())
                ? disarmed.withStatusMessage("")
                : disarmed;
    }

    private static boolean isUserAction(Harness.Msg message) {
        return message instanceof Harness.Msg.ComposerMsg
                || message instanceof Harness.Msg.ComposerOutput
                || message instanceof Harness.Msg.UnhandledKey
                || message instanceof Harness.Msg.Submit
                || message instanceof Harness.Msg.ScrollUp
                || message instanceof Harness.Msg.ScrollDown
                || message instanceof Harness.Msg.PageUp
                || message instanceof Harness.Msg.PageDown
                || message instanceof Harness.Msg.ScrollBottom
                || message instanceof Harness.Msg.CopyVisible
                || message instanceof Harness.Msg.CopyLastReply
                || message instanceof Harness.Msg.Escape
                || message instanceof Harness.Msg.FocusComposer;
    }

    private Update<Harness.Model, Harness.Msg, Never> tick(Harness.Model model, long nowNanos) {
        Harness.Model next = model.withNow(nowNanos);
        if (next.working() && next.workStartedNanos() < 0L) {
            // Submission is a pure step with no clock, and an idle harness does not tick, so the
            // last tick can be far in the past. Start the elapsed label at the first observation
            // of the turn instead of counting the idle period before it.
            next = next.withWorkStarted(nowNanos);
        }
        // Clear expired deadlines (strictly after the boundary, which stays inclusive) so the tick
        // subscription stops once nothing is pending.
        if (next.escArmedUntilNanos() > 0L && nowNanos > next.escArmedUntilNanos()) {
            next = next.withEscArmed(0L);
        }
        if (next.quitArmedUntilNanos() > 0L && nowNanos > next.quitArmedUntilNanos()) {
            next = endQuitConfirmation(next);
        }
        return Update.next(next);
    }

    /**
     * The only exit gesture: a second Ctrl+C at or before the armed deadline. An expired or absent
     * deadline is discarded and the current press becomes the new first press.
     */
    private Update<Harness.Model, Harness.Msg, Never> quitRequest(
            Harness.Model model, long atNanos) {
        if (model.quitArmedUntilNanos() > 0L && atNanos <= model.quitArmedUntilNanos()) {
            return Update.next(model.withQuitArmed(0L), Command.quit());
        }
        return Update.next(
                model.withQuitArmed(atNanos + QUIT_WINDOW_NANOS)
                        .withStatusMessage(QUIT_CONFIRMATION_HINT));
    }

    private Update<Harness.Model, Harness.Msg, Never> editorOutput(
            Harness.Model model, Editor.Output output) {
        return switch (output) {
            case Editor.Output.Submit submit -> submit(model, submit.text());
            case Editor.Output.Copy copy ->
                    copy.text().isEmpty()
                            ? Update.next(model.withStatusMessage("Nothing to copy."))
                            : Update.next(
                                    model, Command.copy(copy.text(), Harness.Msg.Copied::new));
            case Editor.Output.PasteRequest request ->
                    Update.next(
                            model,
                            Command.readClipboard(
                                    text ->
                                            new Harness.Msg.ComposerMsg(
                                                    new Editor.Msg.Paste(text))));
        };
    }

    @Override
    public ComponentTree children(Harness.Model model) {
        return ComponentTree.of(SESSION.tree(model), COMPOSER.tree(model));
    }

    private Update<Harness.Model, Harness.Msg, Never> submit(Harness.Model model, String text) {
        String clean = HarnessRender.sanitize(text).strip();
        if (clean.isEmpty()) {
            return Update.next(model);
        }
        if (model.status() != Session.Status.READY) {
            return Update.next(model.withStatusMessage("Transcript is still loading."));
        }
        if (model.outstandingPrompts() >= MAX_OUTSTANDING) {
            return Update.next(
                    model.withStatusMessage(
                            "Queue full (" + MAX_OUTSTANDING + "); prompt not submitted."));
        }
        List<HarnessEntry> entries = new ArrayList<>(model.entries());
        entries.add(new HarnessEntry.User(clean));
        Harness.Model next =
                COMPOSER.update(model, new Editor.Msg.Clear())
                        .model()
                        .withEntries(entries)
                        .withScroll(0)
                        .withStatusMessage("");
        if (next.working()) {
            List<String> pending = new ArrayList<>(next.pending());
            pending.add(clean);
            return Update.next(next.withPending(pending));
        }
        return startTurn(next, clean);
    }

    private Update<Harness.Model, Harness.Msg, Never> startTurn(
            Harness.Model model, String prompt) {
        HarnessTranscript.HarnessTurn turn = HarnessMock.turnFor(model, prompt);
        long generation = model.generation() + 1;
        Harness.Model next =
                model.withActive(new Harness.Model.Active(turn, 0))
                        .withGeneration(generation)
                        .withWorkStarted(-1L)
                        .withEscArmed(0)
                        .withScroll(0);
        if (HarnessMock.hasRecordedTurn(model)) {
            // Each started prompt consumes one recorded turn; the fallback answers once they end.
            next = next.withReplayIndex(model.replayIndex() + 1);
        }
        return Update.next(next, responseDelay(next, 0));
    }

    private Command<Harness.Msg> responseDelay(Harness.Model model, int ordinal) {
        return Command.delay(
                RESPONSE_ID,
                RESPONSE_DELAY,
                new Harness.Msg.ResponseBlock(model.generation(), ordinal),
                new Harness.Msg.Notice("Mock scheduler rejected the next response block."));
    }

    // ── responses and cancellation ──────────────────────────────────────────

    private Update<Harness.Model, Harness.Msg, Never> responseBlock(
            Harness.Model model, Harness.Msg.ResponseBlock block) {
        if (!model.working()
                || block.generation() != model.generation()
                || model.active() == null
                || block.ordinal() != model.active().blockIndex()) {
            return Update.next(model);
        }
        HarnessTranscript.HarnessTurn turn = model.active().turn();
        if (block.ordinal() >= turn.responses().size()) {
            return finishTurn(model);
        }
        List<HarnessEntry> entries = new ArrayList<>(model.entries());
        entries.add(turn.responses().get(block.ordinal()));
        Harness.Model next =
                model.withEntries(entries)
                        .withActive(new Harness.Model.Active(turn, block.ordinal() + 1));
        if (block.ordinal() + 1 < turn.responses().size()) {
            return Update.next(next, responseDelay(next, block.ordinal() + 1));
        }
        return finishTurn(next);
    }

    private Update<Harness.Model, Harness.Msg, Never> finishTurn(Harness.Model model) {
        Harness.Model next = model;
        if (model.active() != null) {
            next =
                    next.withMetadata(
                            model.active().turn().profile(), model.active().turn().usage());
        }
        if (next.pending().isEmpty()) {
            return Update.next(next.withActive(null).withWorkStarted(0));
        }
        String prompt = next.pending().get(0);
        List<String> rest = new ArrayList<>(next.pending());
        rest.remove(0);
        Harness.Model started = next.withPending(rest);
        return startTurn(started, prompt);
    }

    private Update<Harness.Model, Harness.Msg, Never> escape(Harness.Model model, long atNanos) {
        if (!model.working()) {
            return Update.next(model.withEscArmed(0));
        }
        if (model.escArmedUntilNanos() > 0 && atNanos <= model.escArmedUntilNanos()) {
            // A queued prompt was accepted and is now dropped, so it is reported the way every
            // other submission that will not run is reported.
            int discarded = model.pending().size();
            List<HarnessEntry> entries = new ArrayList<>(model.entries());
            entries.add(new HarnessEntry.Notice("Stopped."));
            Harness.Model next =
                    model.withEntries(entries)
                            .withActive(null)
                            .withPending(List.of())
                            .withEscArmed(0)
                            .withGeneration(model.generation() + 1)
                            .withWorkStarted(0)
                            .withStatusMessage(
                                    discarded == 0
                                            ? ""
                                            : "Stopped; "
                                                    + discarded
                                                    + (discarded == 1
                                                            ? " queued prompt"
                                                            : " queued prompts")
                                                    + " discarded.");
            return Update.next(next, Command.cancelAll());
        }
        return Update.next(model.withEscArmed(atNanos + ESC_WINDOW_NANOS));
    }

    // ── scrolling and clipboard ─────────────────────────────────────────────

    private Harness.Model scrollBy(Harness.Model model, int delta) {
        int max = HarnessProjection.maxScroll(model);
        int target = Math.max(0, Math.min(max, model.scrollBack() + delta));
        return model.withScroll(target);
    }

    private int pageRows(Harness.Model model) {
        Size size = model.viewport();
        return Math.max(
                1, HarnessProjection.allocate(size.rows(), model.editorRows()).transcript());
    }

    private Update<Harness.Model, Harness.Msg, Never> copyLastReply(Harness.Model model) {
        String reply = "";
        List<HarnessEntry> entries = model.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof HarnessEntry.Assistant assistant) {
                reply = assistant.text();
                break;
            }
        }
        if (reply.isBlank()) {
            return Update.next(model.withStatusMessage("No assistant reply to copy."));
        }
        return Update.next(model, Command.copy(reply, Harness.Msg.Copied::new));
    }

    private static String clipboardStatus(ClipboardResult result) {
        if (result == null || result.text().isEmpty()) {
            return "Nothing to copy.";
        }
        if (result.deduplicated()) {
            return "Clipboard already holds this text.";
        }
        if (result.systemWriteRequested()) {
            return result.truncated()
                    ? "Copied (truncated) and wrote OSC 52."
                    : "Copied and wrote OSC 52.";
        }
        return result.truncated()
                ? "Copied internally (truncated); terminal clipboard not written."
                : "Copied to the in-process clipboard only.";
    }
}

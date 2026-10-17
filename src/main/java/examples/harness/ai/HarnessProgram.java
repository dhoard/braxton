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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import io.github.dhoard.braxton.app.InitContext;
import io.github.dhoard.braxton.app.Program;
import io.github.dhoard.braxton.app.Transition;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorAction;
import io.github.dhoard.braxton.widget.EditorIntent;
import io.github.dhoard.braxton.widget.EditorOptions;
import io.github.dhoard.braxton.widget.EditorResult;
import io.github.dhoard.braxton.widget.EditorState;
import io.github.dhoard.braxton.view.View;

/**
 * The AI Harness as one ordinary MVU program.
 *
 * <p>Transcript loading is a task command, response pacing is a delayed message, animation is a
 * timer subscription, and every interaction is a message. There is no worker mutating state, no
 * per-application executor, no global key registry, and no terminal access outside the runtime.</p>
 */
public final class HarnessProgram implements Program<HarnessState, HarnessMsg> {

    static final Duration RESPONSE_DELAY = Duration.ofSeconds(2);
    static final Duration TICK = Duration.ofMillis(50);
    static final long ESC_WINDOW_NANOS = 2_000_000_000L;
    /** A second Ctrl+C must arrive within this window for the first one to mean "exit". */
    static final long QUIT_WINDOW_NANOS = 2_000_000_000L;
    static final int MAX_OUTSTANDING = 64;
    static final int DRAFT_LIMIT = 4_096;
    static final int MAX_ENTRIES = HarnessState.MAX_ENTRIES;

    private static final EffectId TRANSCRIPT_ID = EffectId.of("harness/transcript");
    private static final EffectId RESPONSE_ID = EffectId.of("harness/response");
    private static final SubscriptionId TICK_ID = SubscriptionId.of("harness/tick");
    private static final SubscriptionId RESIZE_ID = SubscriptionId.of("harness/resize");

    private final Supplier<HarnessTranscript> loader;
    private final String projectPath;
    private final List<String> startupWarnings;

    public HarnessProgram(Supplier<HarnessTranscript> loader, String projectPath,
            List<String> startupWarnings) {
        this.loader = loader == null ? () -> HarnessTranscript.empty(List.of()) : loader;
        this.projectPath = projectPath == null ? "" : projectPath;
        this.startupWarnings = List.copyOf(startupWarnings == null ? List.of() : startupWarnings);
    }

    static EditorOptions editorOptions() {
        return EditorOptions.defaults()
                .withMaxLength(DRAFT_LIMIT)
                .withPrompt("> ", HarnessTheme.TEXT)
                .withStyles(HarnessTheme.TEXT, HarnessTheme.TEXT, HarnessTheme.TEXT.withInvert());
    }

    @Override
    public Transition<HarnessState, HarnessMsg> init(InitContext context) {
        HarnessState state = HarnessState.initial(context.viewport().size(), projectPath,
                startupWarnings);
        Command<HarnessMsg> load = Command.task(TRANSCRIPT_ID, loader::get,
                HarnessMsg.TranscriptLoaded::new, HarnessMsg.TranscriptFailed::new);
        return Transition.next(state, load);
    }

    @Override
    public View<HarnessMsg> view(HarnessState model, Viewport viewport) {
        HarnessState sized = model.viewport().equals(viewport.size())
                ? model : model.withViewport(viewport.size());
        return HarnessView.view(sized);
    }

    @Override
    public Subscription<HarnessMsg> subscriptions(HarnessState model) {
        Subscription<HarnessMsg> tick = model.working() || model.escArmedUntilNanos() > 0
                || model.quitArmedUntilNanos() > 0
                ? Subscription.interval(TICK_ID, TICK, HarnessMsg.Tick::new)
                : Subscription.none();
        Subscription<HarnessMsg> resize = Subscription.resize(RESIZE_ID, HarnessMsg.Resized::new);
        return Subscription.batch(tick, resize);
    }

    @Override
    public Transition<HarnessState, HarnessMsg> update(HarnessState model, HarnessMsg message) {
        model = disarmQuitOnUserAction(model, message);
        return switch (message) {
            case HarnessMsg.TranscriptLoaded loaded -> loadTranscript(model, loaded.transcript());
            case HarnessMsg.TranscriptFailed failed -> TranscriptionFailure(model, failed);
            case HarnessMsg.Editor editor -> applyEditor(model, editor.action());
            case HarnessMsg.Submit submit -> submit(model, submit.text());
            case HarnessMsg.Tick tick -> tick(model, tick.nowNanos());
            case HarnessMsg.Resized resized -> Transition.stay(model.withViewport(resized.size()));
            case HarnessMsg.ScrollUp up -> Transition.stay(scrollBy(model, HarnessProjection.SCROLL_STEP));
            case HarnessMsg.ScrollDown down -> Transition.stay(scrollBy(model, -HarnessProjection.SCROLL_STEP));
            case HarnessMsg.PageUp pageUp -> Transition.stay(scrollBy(model, pageRows(model)));
            case HarnessMsg.PageDown pageDown -> Transition.stay(scrollBy(model, -pageRows(model)));
            case HarnessMsg.ScrollBottom bottom -> Transition.stay(model.withScroll(0));
            case HarnessMsg.Escape escape -> escape(model, escape.atNanos());
            case HarnessMsg.ResponseBlock block -> responseBlock(model, block);
            case HarnessMsg.Copied copied -> Transition.stay(model.withStatusMessage(
                    clipboardStatus(copied.result())));
            case HarnessMsg.CopyVisible copy -> Transition.next(model,
                    Command.copy(HarnessProjection.visiblePlainText(model), HarnessMsg.Copied::new));
            case HarnessMsg.CopyLastReply copy -> copyLastReply(model);
            case HarnessMsg.FocusComposer focus -> Transition.stay(model);
            case HarnessMsg.Notice notice -> Transition.stay(model.withStatusMessage(notice.text()));
            case HarnessMsg.QuitRequest request -> quitRequest(model, request.atNanos());
            case HarnessMsg.Quit quit -> Transition.next(model, Command.quit());
        };
    }

    /**
     * A pending Ctrl+C confirmation survives only until another user action. Background events
     * (ticks, response blocks, clipboard reports, loading) do not cancel it.
     */
    private static HarnessState disarmQuitOnUserAction(HarnessState model, HarnessMsg message) {
        if (model.quitArmedUntilNanos() == 0L) {
            return model;
        }
        return isUserAction(message) ? model.disarmQuit() : model;
    }

    private static boolean isUserAction(HarnessMsg message) {
        return message instanceof HarnessMsg.Editor
                || message instanceof HarnessMsg.Submit
                || message instanceof HarnessMsg.ScrollUp
                || message instanceof HarnessMsg.ScrollDown
                || message instanceof HarnessMsg.PageUp
                || message instanceof HarnessMsg.PageDown
                || message instanceof HarnessMsg.ScrollBottom
                || message instanceof HarnessMsg.CopyVisible
                || message instanceof HarnessMsg.CopyLastReply
                || message instanceof HarnessMsg.Escape
                || message instanceof HarnessMsg.FocusComposer;
    }

    private Transition<HarnessState, HarnessMsg> tick(HarnessState model, long nowNanos) {
        HarnessState next = model.withNow(nowNanos);
        // Clear expired deadlines (strictly after the boundary, which stays inclusive) so the tick
        // subscription stops once nothing is pending.
        if (next.escArmedUntilNanos() > 0L && nowNanos > next.escArmedUntilNanos()) {
            next = next.withEscArmed(0L);
        }
        if (next.quitArmedUntilNanos() > 0L && nowNanos > next.quitArmedUntilNanos()) {
            next = next.withQuitArmed(0L);
        }
        return Transition.stay(next);
    }

    /**
     * The only exit gesture: a second Ctrl+C at or before the armed deadline. An expired or absent
     * deadline is discarded and the current press becomes the new first press.
     */
    private Transition<HarnessState, HarnessMsg> quitRequest(HarnessState model, long atNanos) {
        if (model.quitArmedUntilNanos() > 0L && atNanos <= model.quitArmedUntilNanos()) {
            return Transition.next(model.withQuitArmed(0L), Command.quit());
        }
        return Transition.stay(model.withQuitArmed(atNanos + QUIT_WINDOW_NANOS)
                .withStatusMessage("Press Ctrl+C again within 2 s to exit."));
    }

    // ── loading ──────────────────────────────────────────────────────────────

    private Transition<HarnessState, HarnessMsg> loadTranscript(HarnessState model,
            HarnessTranscript transcript) {
        HarnessTranscript source = transcript == null
                ? HarnessTranscript.empty(List.of("empty transcript")) : transcript;
        return Transition.stay(model.withTranscript(source));
    }

    private Transition<HarnessState, HarnessMsg> TranscriptionFailure(HarnessState model,
            HarnessMsg.TranscriptFailed failure) {
        String reason = failure.failure() == null
                ? "unknown error" : failure.failure().getClass().getSimpleName();
        List<HarnessEntry> entries = new ArrayList<>(model.entries());
        entries.add(new HarnessEntry.Failure(
                "Could not read the bundled example transcript (" + reason
                        + "); fallback responses are active."));
        return Transition.stay(model.withStatus(HarnessState.Status.READY)
                .withEntries(entries)
                .withStatusMessage("Transcript unavailable; mock fallback active."));
    }

    // ── editing and submission ───────────────────────────────────────────────

    private Transition<HarnessState, HarnessMsg> applyEditor(HarnessState model,
            EditorAction action) {
        EditorResult result = Editor.update(model.draft(), editorOptions(), action);
        HarnessState next = model.withDraft(result.state());
        return switch (result.intent()) {
            case EditorIntent.None none -> Transition.stay(next);
            case EditorIntent.Submit submit -> submit(next, submit.text());
            case EditorIntent.Copy copy -> Transition.next(next,
                    Command.copy(copy.text(), HarnessMsg.Copied::new));
            case EditorIntent.PasteRequest request -> Transition.next(next,
                    Command.readClipboard(text -> new HarnessMsg.Editor(new EditorAction.Paste(text))));
        };
    }

    private Transition<HarnessState, HarnessMsg> submit(HarnessState model, String text) {
        String clean = HarnessRender.sanitize(text).strip();
        if (clean.isEmpty()) {
            return Transition.stay(model);
        }
        if (model.status() != HarnessState.Status.READY) {
            return Transition.stay(model.withStatusMessage("Transcript is still loading."));
        }
        if (model.outstandingPrompts() >= MAX_OUTSTANDING) {
            return Transition.stay(model.withStatusMessage(
                    "Queue full (" + MAX_OUTSTANDING + "); prompt not submitted."));
        }
        List<HarnessEntry> entries = new ArrayList<>(model.entries());
        entries.add(new HarnessEntry.User(clean));
        HarnessState next = model.withEntries(entries)
                .withDraft(EditorState.empty())
                .withScroll(0)
                .withStatusMessage("");
        if (next.working()) {
            List<String> pending = new ArrayList<>(next.pending());
            pending.add(clean);
            return Transition.stay(next.withPending(pending));
        }
        return startTurn(next, clean);
    }

    private Transition<HarnessState, HarnessMsg> startTurn(HarnessState model, String prompt) {
        HarnessTranscript.HarnessTurn turn = HarnessMock.turnFor(model, prompt);
        long generation = model.generation() + 1;
        HarnessState next = model.withActive(new HarnessState.Active(turn, 0))
                .withGeneration(generation)
                .withWorkStarted(model.nowNanos())
                .withEscArmed(0)
                .withScroll(0);
        return Transition.next(next, responseDelay(next, 0));
    }

    private Command<HarnessMsg> responseDelay(HarnessState model, int ordinal) {
        return Command.delay(RESPONSE_ID, RESPONSE_DELAY,
                new HarnessMsg.ResponseBlock(model.generation(), ordinal),
                new HarnessMsg.Notice("Mock scheduler rejected the next response block."));
    }

    // ── responses and cancellation ──────────────────────────────────────────

    private Transition<HarnessState, HarnessMsg> responseBlock(HarnessState model,
            HarnessMsg.ResponseBlock block) {
        if (!model.working() || block.generation() != model.generation()
                || model.active() == null || block.ordinal() != model.active().blockIndex()) {
            return Transition.stay(model);
        }
        HarnessTranscript.HarnessTurn turn = model.active().turn();
        if (block.ordinal() >= turn.responses().size()) {
            return finishTurn(model);
        }
        List<HarnessEntry> entries = new ArrayList<>(model.entries());
        entries.add(turn.responses().get(block.ordinal()));
        HarnessState next = model.withEntries(entries)
                .withActive(new HarnessState.Active(turn, block.ordinal() + 1));
        if (block.ordinal() + 1 < turn.responses().size()) {
            return Transition.next(next, responseDelay(next, block.ordinal() + 1));
        }
        return finishTurn(next);
    }

    private Transition<HarnessState, HarnessMsg> finishTurn(HarnessState model) {
        HarnessState next = model;
        if (model.active() != null) {
            next = next.withMetadata(model.active().turn().profile(), model.active().turn().usage());
        }
        if (next.pending().isEmpty()) {
            return Transition.stay(next.withActive(null).withWorkStarted(0));
        }
        String prompt = next.pending().get(0);
        List<String> rest = new ArrayList<>(next.pending());
        rest.remove(0);
        HarnessState started = next.withPending(rest);
        return startTurn(started, prompt);
    }

    private Transition<HarnessState, HarnessMsg> escape(HarnessState model, long atNanos) {
        if (!model.working()) {
            return Transition.stay(model.withEscArmed(0));
        }
        if (model.escArmedUntilNanos() > 0 && atNanos <= model.escArmedUntilNanos()) {
            List<HarnessEntry> entries = new ArrayList<>(model.entries());
            entries.add(new HarnessEntry.Notice("Stopped."));
            HarnessState next = model.withEntries(entries)
                    .withActive(null)
                    .withPending(List.of())
                    .withEscArmed(0)
                    .withGeneration(model.generation() + 1)
                    .withWorkStarted(0)
                    .withStatusMessage("");
            return Transition.next(next, Command.cancelNamespace("harness"));
        }
        return Transition.stay(model.withEscArmed(atNanos + ESC_WINDOW_NANOS));
    }

    // ── scrolling and clipboard ─────────────────────────────────────────────

    private HarnessState scrollBy(HarnessState model, int delta) {
        int max = HarnessProjection.maxScroll(model);
        int target = Math.max(0, Math.min(max, model.scrollBack() + delta));
        return model.withScroll(target);
    }

    private int pageRows(HarnessState model) {
        Size size = model.viewport();
        return Math.max(1, HarnessProjection.allocate(size.rows(), model.editorRows()).transcript());
    }

    private Transition<HarnessState, HarnessMsg> copyLastReply(HarnessState model) {
        String reply = "";
        List<HarnessEntry> entries = model.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof HarnessEntry.Assistant assistant) {
                reply = assistant.text();
                break;
            }
        }
        if (reply.isBlank()) {
            return Transition.stay(model.withStatusMessage("No assistant reply to copy."));
        }
        return Transition.next(model, Command.copy(reply, HarnessMsg.Copied::new));
    }

    private static String clipboardStatus(io.github.dhoard.braxton.clipboard.ClipboardResult result) {
        if (result == null || result.text().isEmpty()) {
            return "Nothing to copy.";
        }
        if (result.deduplicated()) {
            return "Clipboard already holds this text.";
        }
        if (result.systemWriteRequested()) {
            return result.truncated()
                    ? "Copied (truncated) and wrote OSC 52." : "Copied and wrote OSC 52.";
        }
        return result.truncated()
                ? "Copied internally (truncated); terminal clipboard not written."
                : "Copied to the in-process clipboard only.";
    }
}

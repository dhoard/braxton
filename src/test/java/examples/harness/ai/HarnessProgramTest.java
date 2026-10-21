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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.app.InitContext;
import io.github.dhoard.braxton.app.Transition;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.terminal.TerminalCapabilities;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.widget.EditorAction;
import io.github.dhoard.braxton.widget.EditorState;

/** The harness program: seeding, submission, pacing, cancellation, limits, and intents. */
class HarnessProgramTest {

    private static final HarnessTranscript.Profile PROFILE =
            new HarnessTranscript.Profile("amanda", "m", "high");

    private static HarnessTranscript scripted() {
        return new HarnessTranscript(List.of(
                new HarnessTranscript.HarnessTurn("first",
                        List.of(new HarnessEntry.Assistant("one")),
                        HarnessTranscript.Usage.EMPTY, PROFILE),
                new HarnessTranscript.HarnessTurn("second",
                        List.of(new HarnessEntry.Assistant("two")),
                        HarnessTranscript.Usage.EMPTY, PROFILE)),
                PROFILE, HarnessTranscript.Usage.EMPTY, 1000, List.of());
    }

    private static HarnessProgram program() {
        return new HarnessProgram(HarnessProgramTest::scripted, "~/braxton", List.of());
    }

    private static HarnessState ready() {
        return HarnessState.initial(new Size(24, 80), "~/braxton", List.of())
                .withTranscript(scripted());
    }

    private static Transition<HarnessState, HarnessMsg> update(HarnessState state,
            HarnessMsg message) {
        return program().update(state, message);
    }

    @Test
    void initIsLoadingAndReturnsOneTaskCommand() {
        Transition<HarnessState, HarnessMsg> transition = program().init(
                new InitContext(new Viewport(24, 80), TerminalCapabilities.PLAIN, 0L, java.util.Map.of()));
        assertThat(transition.model().status()).isEqualTo(HarnessState.Status.LOADING);
        assertThat(transition.command()).isInstanceOf(Command.Task.class);
    }

    @Test
    void loadingSeedsTheFirstTurnExactlyOnce() {
        HarnessState state = update(HarnessState.initial(new Size(24, 80), "~/braxton", List.of()),
                new HarnessMsg.TranscriptLoaded(scripted())).model();
        assertThat(state.status()).isEqualTo(HarnessState.Status.READY);
        assertThat(state.replayIndex()).isEqualTo(1);
        assertThat(state.entries()).contains(new HarnessEntry.User("first"));
        assertThat(state.entries()).contains(new HarnessEntry.Assistant("one"));
        assertThat(state.entries().stream().filter(e -> e instanceof HarnessEntry.User)).hasSize(1);
    }

    @Test
    void loadingFailureFallsBackAndRemainsUsable() {
        HarnessState state = update(ready(), new HarnessMsg.TranscriptFailed(
                new IllegalStateException("missing"))).model();
        assertThat(state.status()).isEqualTo(HarnessState.Status.READY);
        assertThat(state.entries()).anySatisfy(entry ->
                assertThat(entry).isInstanceOf(HarnessEntry.Failure.class));
    }

    @Test
    void submissionAppendsThePromptClearsTheDraftAndStartsTheFirstBlock() {
        HarnessState state = ready().withDraft(EditorState.of("hello"));
        Transition<HarnessState, HarnessMsg> transition = update(state,
                new HarnessMsg.Editor(new EditorAction.Submit()));

        assertThat(transition.model().entries()).contains(new HarnessEntry.User("hello"));
        assertThat(transition.model().draft().text()).isEmpty();
        assertThat(transition.model().working()).isTrue();
        assertThat(transition.model().generation()).isEqualTo(1);
        assertThat(transition.command()).isInstanceOf(Command.Delay.class);
        Command.Delay<HarnessMsg> delay = (Command.Delay<HarnessMsg>) transition.command();
        assertThat(delay.id()).isEqualTo(io.github.dhoard.braxton.effect.EffectId.of("harness/response"));
        assertThat(delay.delay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(delay.message()).isEqualTo(new HarnessMsg.ResponseBlock(1, 0));
    }

    @Test
    void theWorkingElapsedStartsAtTheFirstTickAfterTheTurnBegins() {
        // An idle harness does not tick, so nowNanos is the last tick the runtime delivered.
        HarnessState idle = ready().withNow(1_000_000_000L);
        Transition<HarnessState, HarnessMsg> started = update(idle, new HarnessMsg.Submit("hi"));
        assertThat(HarnessProjection.topRule(started.model()).plainText())
                .contains("Working (0s)");
        HarnessState first = update(started.model(), new HarnessMsg.Tick(61_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(first).plainText())
                .contains("Working (0s)");
        HarnessState later = update(first, new HarnessMsg.Tick(66_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(later).plainText())
                .contains("Working (5s)");
    }

    @Test
    void aMatchingResponseBlockAppendsAndFinishesTheTurn() {
        Transition<HarnessState, HarnessMsg> started = update(ready(), new HarnessMsg.Submit("hi"));
        HarnessState state = update(started.model(),
                new HarnessMsg.ResponseBlock(started.model().generation(), 0)).model();
        assertThat(state.entries()).contains(new HarnessEntry.Assistant("two"));
        assertThat(state.working()).isFalse();
        assertThat(state.outstandingPrompts()).isZero();
    }

    @Test
    void successivePromptsAdvanceThroughTheRecordedTurnsThenFallBack() {
        Transition<HarnessState, HarnessMsg> first = update(ready(), new HarnessMsg.Submit("a"));
        HarnessState afterFirst = update(first.model(),
                new HarnessMsg.ResponseBlock(first.model().generation(), 0)).model();
        assertThat(afterFirst.entries()).contains(new HarnessEntry.Assistant("two"));
        assertThat(afterFirst.replayIndex()).isEqualTo(2);

        Transition<HarnessState, HarnessMsg> second = update(afterFirst, new HarnessMsg.Submit("b"));
        HarnessState afterSecond = second.model();
        while (afterSecond.working()) {
            afterSecond = update(afterSecond, new HarnessMsg.ResponseBlock(
                    afterSecond.generation(), afterSecond.active().blockIndex())).model();
        }
        String lastAssistant = "";
        for (HarnessEntry entry : afterSecond.entries()) {
            if (entry instanceof HarnessEntry.Assistant assistant) {
                lastAssistant = assistant.text();
            }
        }
        assertThat(lastAssistant).isNotEqualTo("two").contains("\"b\"");
    }

    @Test
    void staleOrDuplicateBlocksAreIgnored() {
        Transition<HarnessState, HarnessMsg> started = update(ready(), new HarnessMsg.Submit("hi"));
        long generation = started.model().generation();
        HarnessState finished = update(started.model(),
                new HarnessMsg.ResponseBlock(generation, 0)).model();
        HarnessState stale = update(finished, new HarnessMsg.ResponseBlock(generation, 0)).model();
        assertThat(stale.entries().stream()
                .filter(e -> e instanceof HarnessEntry.Assistant)).hasSize(2);
        HarnessState wrongGeneration = update(finished,
                new HarnessMsg.ResponseBlock(generation + 5, 0)).model();
        assertThat(wrongGeneration.entries()).isEqualTo(finished.entries());
    }

    @Test
    void rapidSubmitsQueueFifoAndAreDrainedOneTurnAtATime() {
        Transition<HarnessState, HarnessMsg> first = update(ready(), new HarnessMsg.Submit("one"));
        Transition<HarnessState, HarnessMsg> second = update(first.model(),
                new HarnessMsg.Submit("two"));
        assertThat(second.model().pending()).containsExactly("two");
        assertThat(second.model().outstandingPrompts()).isEqualTo(2);

        HarnessState afterFirst = update(second.model(),
                new HarnessMsg.ResponseBlock(second.model().generation(), 0)).model();
        assertThat(afterFirst.working()).isTrue();
        assertThat(afterFirst.pending()).isEmpty();
        assertThat(afterFirst.entries()).contains(new HarnessEntry.User("two"));
    }

    @Test
    void aQueuedTurnRestartsTheElapsedLabel() {
        Transition<HarnessState, HarnessMsg> first = update(ready().withNow(1_000_000_000L),
                new HarnessMsg.Submit("one"));
        HarnessState ticked = update(first.model(), new HarnessMsg.Tick(1_000_000_000L)).model();
        HarnessState queued = update(ticked, new HarnessMsg.Submit("two")).model();
        HarnessState later = update(queued, new HarnessMsg.Tick(61_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(later).plainText())
                .contains("Working (1m 0s \u00b7 1 queued)");

        // The last block of the running turn hands over to the queued one, which has not been
        // observed by a tick yet, so its label starts at zero rather than at the old start.
        HarnessState drained = update(later,
                new HarnessMsg.ResponseBlock(later.generation(), 0)).model();
        assertThat(drained.pending()).isEmpty();
        assertThat(drained.working()).isTrue();
        assertThat(drained.workStartedNanos()).isEqualTo(-1L);
        assertThat(HarnessProjection.topRule(drained).plainText()).contains("Working (0s)");

        HarnessState started = update(drained, new HarnessMsg.Tick(70_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(started).plainText()).contains("Working (0s)");
        HarnessState elapsed = update(started, new HarnessMsg.Tick(73_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(elapsed).plainText()).contains("Working (3s)");
    }

    @Test
    void emptyResponsePlansCompleteWithoutAnotherDelay() {
        HarnessTranscript empty = new HarnessTranscript(List.of(
                new HarnessTranscript.HarnessTurn("q", List.of(),
                        HarnessTranscript.Usage.EMPTY, PROFILE)), PROFILE,
                HarnessTranscript.Usage.EMPTY, 1000, List.of());
        HarnessState state = HarnessState.initial(new Size(24, 80), "~/braxton", List.of())
                .withTranscript(empty).withReplayIndex(0);
        Transition<HarnessState, HarnessMsg> started = update(state, new HarnessMsg.Submit("x"));
        Transition<HarnessState, HarnessMsg> finished = update(started.model(),
                new HarnessMsg.ResponseBlock(started.model().generation(), 0));
        assertThat(finished.model().working()).isFalse();
        assertThat(finished.command()).isInstanceOf(Command.None.class);
    }

    @Test
    void everyRecordedBlockWaitsTwoSecondsBeforeTheNextBox() {
        HarnessTranscript paced = new HarnessTranscript(List.of(
                new HarnessTranscript.HarnessTurn("q",
                        List.of(new HarnessEntry.Reasoning("one"),
                                new HarnessEntry.Assistant("two"),
                                new HarnessEntry.Assistant("three")),
                        HarnessTranscript.Usage.EMPTY, PROFILE)), PROFILE,
                HarnessTranscript.Usage.EMPTY, 1000, List.of());
        HarnessProgram program = new HarnessProgram(() -> paced, "~/braxton", List.of());
        HarnessState state = HarnessState.initial(new Size(24, 80), "~/braxton", List.of())
                .withTranscript(paced).withReplayIndex(0);

        Transition<HarnessState, HarnessMsg> started = program.update(state,
                new HarnessMsg.Submit("x"));
        assertThat(delayOf(started)).isEqualTo(HarnessProgram.RESPONSE_DELAY);
        Transition<HarnessState, HarnessMsg> second = program.update(started.model(),
                new HarnessMsg.ResponseBlock(started.model().generation(), 0));
        assertThat(second.model().entries()).contains(new HarnessEntry.Reasoning("one"));
        assertThat(delayOf(second)).isEqualTo(HarnessProgram.RESPONSE_DELAY);
    }

    @SuppressWarnings("unchecked")
    private static Duration delayOf(Transition<HarnessState, HarnessMsg> transition) {
        return ((Command.Delay<HarnessMsg>) transition.command()).delay();
    }

    @Test
    void escapeIsANoOpWhenIdle() {
        HarnessState state = ready();
        HarnessState next = update(state, new HarnessMsg.Escape(1_000L)).model();
        assertThat(next).isEqualTo(state.withEscArmed(0));
    }

    @Test
    void secondEscapeInsideTheWindowStopsAndKeepsTheDraft() {
        HarnessState state = ready().withDraft(EditorState.of("draft"));
        HarnessState started = update(state, new HarnessMsg.Submit("hello")).model();
        HarnessState armed = update(started, new HarnessMsg.Escape(1_000_000_000L)).model();
        assertThat(armed.escArmedUntilNanos()).isEqualTo(3_000_000_000L);
        Transition<HarnessState, HarnessMsg> stopped =
                update(armed, new HarnessMsg.Escape(2_000_000_000L));
        assertThat(stopped.model().working()).isFalse();
        assertThat(stopped.model().entries()).contains(new HarnessEntry.Notice("Stopped."));
        assertThat(stopped.model().pending()).isEmpty();
        assertThat(stopped.model().draft().text()).isEmpty();
        assertThat(stopped.command()).isInstanceOf(Command.CancelNamespace.class);
    }

    @Test
    void stoppingReportsTheQueuedPromptsItDiscarded() {
        HarnessState started = update(ready(), new HarnessMsg.Submit("one")).model();
        HarnessState queued = update(started, new HarnessMsg.Submit("two")).model();
        HarnessState twoQueued = update(queued, new HarnessMsg.Submit("three")).model();
        assertThat(twoQueued.pending()).hasSize(2);

        HarnessState armed = update(twoQueued, new HarnessMsg.Escape(1_000_000_000L)).model();
        HarnessState stopped = update(armed, new HarnessMsg.Escape(2_000_000_000L)).model();
        assertThat(stopped.pending()).isEmpty();
        assertThat(stopped.statusMessage()).contains("2 queued prompts");

        // Nothing queued means nothing to report, so the stop stays exactly as before.
        HarnessState alone = update(started, new HarnessMsg.Escape(1_000_000_000L)).model();
        HarnessState quiet = update(alone, new HarnessMsg.Escape(2_000_000_000L)).model();
        assertThat(quiet.statusMessage()).isEmpty();
        assertThat(quiet.entries()).contains(new HarnessEntry.Notice("Stopped."));

        HarnessState oneArmed = update(queued, new HarnessMsg.Escape(1_000_000_000L)).model();
        HarnessState oneStopped = update(oneArmed, new HarnessMsg.Escape(2_000_000_000L)).model();
        assertThat(oneStopped.statusMessage())
                .contains("1 queued prompt").doesNotContain("1 queued prompts");
    }

    @Test
    void escapeAfterTheWindowRearmsInsteadOfStopping() {
        HarnessState started = update(ready(), new HarnessMsg.Submit("hello")).model();
        HarnessState armed = update(started, new HarnessMsg.Escape(0L)).model();
        HarnessState late = update(armed,
                new HarnessMsg.Escape(armed.escArmedUntilNanos() + 1)).model();
        assertThat(late.working()).isTrue();
        assertThat(late.escArmedUntilNanos()).isGreaterThan(armed.escArmedUntilNanos());
    }

    @Test
    void theOutstandingPromptLimitRejectsWithoutLosingTheDraft() {
        HarnessState state = ready().withDraft(EditorState.of("keep me"));
        HarnessState working = update(state, new HarnessMsg.Submit("active")).model();
        for (int i = 0; i < HarnessProgram.MAX_OUTSTANDING - 1; i++) {
            working = update(working, new HarnessMsg.Submit("queued-" + i)).model();
        }
        assertThat(working.outstandingPrompts()).isEqualTo(HarnessProgram.MAX_OUTSTANDING);
        Transition<HarnessState, HarnessMsg> rejected =
                update(working.withDraft(EditorState.of("keep me")),
                        new HarnessMsg.Submit("overflow"));
        assertThat(rejected.model().statusMessage()).contains("Queue full");
        assertThat(rejected.model().draft().text()).isEqualTo("keep me");
        assertThat(rejected.model().entries()).doesNotContain(new HarnessEntry.User("overflow"));
    }

    @Test
    void blankSubmissionsAndLoadingSubmissionsAreIgnored() {
        Transition<HarnessState, HarnessMsg> blank = update(ready(), new HarnessMsg.Submit("   "));
        assertThat(blank.model().working()).isFalse();
        assertThat(blank.command()).isInstanceOf(Command.None.class);

        HarnessState loading = HarnessState.initial(new Size(24, 80), "~/braxton", List.of());
        Transition<HarnessState, HarnessMsg> rejected = update(loading, new HarnessMsg.Submit("hi"));
        assertThat(rejected.model().statusMessage()).contains("still loading");
    }

    @Test
    void aSingleCtrlCArmingDoesNotExit() {
        Transition<HarnessState, HarnessMsg> transition =
                update(ready(), new HarnessMsg.QuitRequest(1_000_000_000L));
        assertThat(transition.command()).isInstanceOf(Command.None.class);
        assertThat(transition.model().quitArmedUntilNanos()).isEqualTo(3_000_000_000L);
    }

    @Test
    void aSecondCtrlCWithinTwoSecondsExits() {
        HarnessState armed = update(ready(),
                new HarnessMsg.QuitRequest(1_000_000_000L)).model();
        Transition<HarnessState, HarnessMsg> confirmed =
                update(armed, new HarnessMsg.QuitRequest(3_000_000_000L));
        assertThat(confirmed.command()).isInstanceOf(Command.Quit.class);
        assertThat(confirmed.model().quitArmedUntilNanos()).isZero();
    }

    @Test
    void aCtrlCAfterTheWindowRearmsInsteadOfExiting() {
        HarnessState armed = update(ready(),
                new HarnessMsg.QuitRequest(1_000_000_000L)).model();
        Transition<HarnessState, HarnessMsg> again =
                update(armed, new HarnessMsg.QuitRequest(4_000_000_000L));
        assertThat(again.command()).isInstanceOf(Command.None.class);
        assertThat(again.model().quitArmedUntilNanos()).isEqualTo(6_000_000_000L);
    }

    @Test
    void anotherKeyCancelsThePendingCtrlC() {
        HarnessState armed = update(ready(),
                new HarnessMsg.QuitRequest(1_000_000_000L)).model();
        HarnessState typed = update(armed,
                new HarnessMsg.Editor(new EditorAction.Insert("x"))).model();
        assertThat(typed.quitArmedUntilNanos()).isZero();
        Transition<HarnessState, HarnessMsg> after =
                update(typed, new HarnessMsg.QuitRequest(1_500_000_000L));
        assertThat(after.command()).isInstanceOf(Command.None.class);
    }

    /**
     * The README says any other key cancels the pending confirmation, but the composer only ever
     * produced a message for the gestures it maps: a key it ignores produced nothing, so the
     * harness never saw the key and the confirmation stayed armed.
     */
    @Test
    void aKeyTheComposerIgnoresStillCancelsThePendingCtrlC() {
        HarnessState armed = ready().withQuitArmed(3_000_000_000L);
        View<HarnessMsg> view = HarnessView.view(armed);
        Optional<HarnessMsg> routed = InputRouter.route(view,
                InputEvent.KeyInput.of(Key.of("f1")));
        assertThat(routed).as("any other key must reach the harness").isPresent();
        HarnessMsg message = routed.orElseThrow();
        assertThat(message).isInstanceOf(HarnessMsg.UnhandledKey.class);
        HarnessState after = update(armed, message).model();
        assertThat(after.quitArmedUntilNanos()).isZero();
        assertThat(update(after, new HarnessMsg.QuitRequest(1_500_000_000L)).command())
                .isInstanceOf(Command.None.class);
    }

    @Test
    void backgroundEventsDoNotCancelThePendingCtrlC() {
        HarnessState armed = update(ready(),
                new HarnessMsg.QuitRequest(1_000_000_000L)).model();
        HarnessState ticked = update(armed, new HarnessMsg.Tick(1_500_000_000L)).model();
        assertThat(ticked.quitArmedUntilNanos()).isEqualTo(3_000_000_000L);
        HarnessState copied = update(ticked, new HarnessMsg.Copied(
                io.github.dhoard.braxton.clipboard.ClipboardResult.empty())).model();
        assertThat(copied.quitArmedUntilNanos()).isEqualTo(3_000_000_000L);
    }

    @Test
    void anExpiredConfirmationIsClearedByATick() {
        HarnessState armed = update(ready(),
                new HarnessMsg.QuitRequest(1_000_000_000L)).model();
        HarnessState expired = update(armed, new HarnessMsg.Tick(3_000_000_001L)).model();
        assertThat(expired.quitArmedUntilNanos()).isZero();
    }

    @Test
    void anArmedConfirmationKeepsTheTickSubscriptionAlive() {
        HarnessState armed = update(ready(),
                new HarnessMsg.QuitRequest(1_000_000_000L)).model();
        assertThat(Subscription.leaves(program().subscriptions(armed)))
                .anySatisfy(subscription ->
                        assertThat(subscription.id().value()).isEqualTo("harness/tick"));
    }

    @Test
    void anEmptyCopyIntentDoesNotTouchTheClipboard() {
        Transition<HarnessState, HarnessMsg> transition =
                update(ready(), new HarnessMsg.Editor(new EditorAction.Copy()));
        assertThat(transition.command()).isInstanceOf(Command.None.class);
        assertThat(transition.model().statusMessage()).isEqualTo("Nothing to copy.");
    }

    @Test
    void copyLastReplyWithoutAReplyIsANoticeNotACopy() {
        HarnessState blank = HarnessState.initial(new Size(24, 80), "~/braxton", List.of())
                .withTranscript(HarnessTranscript.empty(List.of()));
        Transition<HarnessState, HarnessMsg> transition =
                update(blank, new HarnessMsg.CopyLastReply());
        assertThat(transition.command()).isInstanceOf(Command.None.class);
        assertThat(transition.model().statusMessage()).contains("No assistant reply");
    }

    @Test
    void copyLastReplyCopiesTheMostRecentAssistantText() {
        Transition<HarnessState, HarnessMsg> transition =
                update(ready(), new HarnessMsg.CopyLastReply());
        assertThat(transition.command()).isInstanceOf(Command.Copy.class);
    }

    @Test
    void copyVisibleBuildsAPlainSnapshotOfTheViewport() {
        Transition<HarnessState, HarnessMsg> transition =
                update(ready(), new HarnessMsg.CopyVisible());
        assertThat(transition.command()).isInstanceOf(Command.Copy.class);
        Command.Copy<HarnessMsg> copy = (Command.Copy<HarnessMsg>) transition.command();
        assertThat(copy.text()).contains("AI Harness");
    }

    @Test
    void clipboardResultsBecomeTruthfulStatusMessages() {
        HarnessState internal = update(ready(), new HarnessMsg.Copied(
                new io.github.dhoard.braxton.clipboard.ClipboardResult("x", 1, false, true, false, false,
                        io.github.dhoard.braxton.clipboard.ClipboardPath.INTERNAL_ONLY))).model();
        assertThat(internal.statusMessage()).contains("in-process clipboard only");
        HarnessState truncated = update(ready(), new HarnessMsg.Copied(
                new io.github.dhoard.braxton.clipboard.ClipboardResult("x", 1, true, true, true, false,
                        io.github.dhoard.braxton.clipboard.ClipboardPath.OSC52_DIRECT))).model();
        assertThat(truncated.statusMessage()).contains("truncated").contains("OSC 52");
    }

    @Test
    void subscriptionsTickOnlyWhileWorkingAndAlwaysObserveResize() {
        HarnessProgram program = program();
        List<String> idle = Subscription.leaves(program.subscriptions(ready()))
                .stream().map(s -> s.id().value()).toList();
        assertThat(idle).containsExactly("harness/resize");
        HarnessState working = update(ready(), new HarnessMsg.Submit("hi")).model();
        List<String> busy = Subscription.leaves(program.subscriptions(working))
                .stream().map(s -> s.id().value()).toList();
        assertThat(busy).containsExactlyInAnyOrder("harness/tick", "harness/resize");
    }

    @Test
    void editorPasteRequestBecomesAReadClipboardCommand() {
        Transition<HarnessState, HarnessMsg> transition = update(ready(),
                new HarnessMsg.Editor(new EditorAction.PasteRequest()));
        assertThat(transition.command()).isInstanceOf(Command.ReadClipboard.class);
    }

    @Test
    void historyStaysBounded() {
        HarnessState state = ready();
        for (int i = 0; i < 120; i++) {
            state = update(state, new HarnessMsg.Submit("p" + i)).model();
        }
        assertThat(state.entries()).hasSizeLessThanOrEqualTo(HarnessState.MAX_ENTRIES);
    }
}

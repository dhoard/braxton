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

import io.github.dhoard.braxton.app.TerminalCapabilities;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.input.InputDecoder;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.widget.Editor;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** The harness program: seeding, submission, pacing, cancellation, limits, and intents. */
@org.junit.jupiter.api.extension.ExtendWith(io.github.dhoard.braxton.internal.input.TestReaders.class)
class HarnessTest {

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

    private static Harness program() {
        return new Harness(HarnessTest::scripted, "~/braxton", List.of());
    }

    private static Harness.Model ready() {
        return Harness.Model.initial(new Size(24, 80), "~/braxton", List.of())
                .withTranscript(scripted());
    }

    private static Update<Harness.Model, Harness.Msg, Never> update(
            Harness.Model state, Harness.Msg message) {
        var pending = new java.util.ArrayDeque<Harness.Msg>();
        pending.add(message);
        var commands = new java.util.ArrayList<Command<Harness.Msg>>();
        while (!pending.isEmpty()) {
            var result = program().update(state, pending.removeFirst());
            state = result.model();
            inspect(result.command(), pending, commands);
        }
        return Update.next(state, Command.batch(commands));
    }

    private static void inspect(
            Command<Harness.Msg> command,
            java.util.ArrayDeque<Harness.Msg> pending,
            java.util.List<Command<Harness.Msg>> commands) {
        switch (command) {
            case Command.None<Harness.Msg> none -> {}
            case Command.Batch<Harness.Msg> batch ->
                    batch.commands().forEach(item -> inspect(item, pending, commands));
            case Command.Dispatch<Harness.Msg> dispatch -> pending.add(dispatch.message());
            case Command.Scoped<Harness.Msg> scoped -> inspect(scoped.command(), pending, commands);
            default -> commands.add(command);
        }
    }

    @Test
    void initIsLoadingAndReturnsOneTaskCommand() {
        Update<Harness.Model, Harness.Msg, Never> transition =
                program().init(new InitContext(new Viewport(24, 80), TerminalCapabilities.PLAIN));
        assertThat(transition.model().status()).isEqualTo(Session.Status.LOADING);
        assertThat(transition.command())
                .isInstanceOfSatisfying(
                        Command.Scoped.class,
                        scoped -> {
                            assertThat(scoped.path())
                                    .isEqualTo(
                                            ComponentPath.ROOT.append(ComponentId.of("session")));
                            assertThat(scoped.command()).isInstanceOf(Command.Task.class);
                        });
    }

    @Test
    void loadingSeedsTheFirstTurnExactlyOnce() {
        Harness.Model state =
                update(
                                Harness.Model.initial(new Size(24, 80), "~/braxton", List.of()),
                                new Harness.Msg.SessionMsg(new Session.Msg.Loaded(scripted())))
                        .model();
        assertThat(state.status()).isEqualTo(Session.Status.READY);
        assertThat(state.replayIndex()).isEqualTo(1);
        assertThat(state.entries()).contains(new HarnessEntry.User("first"));
        assertThat(state.entries()).contains(new HarnessEntry.Assistant("one"));
        assertThat(state.entries().stream().filter(e -> e instanceof HarnessEntry.User)).hasSize(1);
    }

    @Test
    void loadingFailureFallsBackAndRemainsUsable() {
        Harness.Model state =
                update(
                                ready(),
                                new Harness.Msg.SessionMsg(
                                        new Session.Msg.Failed(
                                                new IllegalStateException("missing"))))
                        .model();
        assertThat(state.status()).isEqualTo(Session.Status.READY);
        assertThat(state.entries()).anySatisfy(entry ->
                assertThat(entry).isInstanceOf(HarnessEntry.Failure.class));
    }

    @Test
    void submissionAppendsThePromptClearsTheDraftAndStartsTheFirstBlock() {
        Harness.Model state = ready().withDraft(Editor.Model.of("hello"));
        Update<Harness.Model, Harness.Msg, Never> transition =
                update(state, new Harness.Msg.ComposerMsg(new Editor.Msg.Submit()));

        assertThat(transition.model().entries()).contains(new HarnessEntry.User("hello"));
        assertThat(transition.model().draft().text()).isEmpty();
        assertThat(transition.model().working()).isTrue();
        assertThat(transition.model().generation()).isEqualTo(1);
        assertThat(transition.command()).isInstanceOf(Command.Delay.class);
        Command.Delay<Harness.Msg> delay = (Command.Delay<Harness.Msg>) transition.command();
        assertThat(delay.id()).isEqualTo(io.github.dhoard.braxton.effect.EffectId.of("response"));
        assertThat(delay.delay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(delay.message()).isEqualTo(new Harness.Msg.ResponseBlock(1, 0));
    }

    @Test
    void theWorkingElapsedStartsAtTheFirstTickAfterTheTurnBegins() {
        // An idle harness does not tick, so nowNanos is the last tick the runtime delivered.
        Harness.Model idle = ready().withNow(1_000_000_000L);
        Update<Harness.Model, Harness.Msg, Never> started =
                update(idle, new Harness.Msg.Submit("hi"));
        assertThat(HarnessProjection.topRule(started.model()).plainText())
                .contains("Working (0s)");
        Harness.Model first =
                update(started.model(), new Harness.Msg.Tick(61_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(first).plainText())
                .contains("Working (0s)");
        Harness.Model later = update(first, new Harness.Msg.Tick(66_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(later).plainText())
                .contains("Working (5s)");
    }

    @Test
    void aMatchingResponseBlockAppendsAndFinishesTheTurn() {
        Update<Harness.Model, Harness.Msg, Never> started =
                update(ready(), new Harness.Msg.Submit("hi"));
        Harness.Model state =
                update(
                                started.model(),
                                new Harness.Msg.ResponseBlock(started.model().generation(), 0))
                        .model();
        assertThat(state.entries()).contains(new HarnessEntry.Assistant("two"));
        assertThat(state.working()).isFalse();
        assertThat(state.outstandingPrompts()).isZero();
    }

    @Test
    void successivePromptsAdvanceThroughTheRecordedTurnsThenFallBack() {
        Update<Harness.Model, Harness.Msg, Never> first =
                update(ready(), new Harness.Msg.Submit("a"));
        Harness.Model afterFirst =
                update(first.model(), new Harness.Msg.ResponseBlock(first.model().generation(), 0))
                        .model();
        assertThat(afterFirst.entries()).contains(new HarnessEntry.Assistant("two"));
        assertThat(afterFirst.replayIndex()).isEqualTo(2);

        Update<Harness.Model, Harness.Msg, Never> second =
                update(afterFirst, new Harness.Msg.Submit("b"));
        Harness.Model afterSecond = second.model();
        while (afterSecond.working()) {
            afterSecond =
                    update(
                                    afterSecond,
                                    new Harness.Msg.ResponseBlock(
                                            afterSecond.generation(),
                                            afterSecond.active().blockIndex()))
                            .model();
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
        Update<Harness.Model, Harness.Msg, Never> started =
                update(ready(), new Harness.Msg.Submit("hi"));
        long generation = started.model().generation();
        Harness.Model finished =
                update(started.model(), new Harness.Msg.ResponseBlock(generation, 0)).model();
        Harness.Model stale =
                update(finished, new Harness.Msg.ResponseBlock(generation, 0)).model();
        assertThat(stale.entries().stream()
                .filter(e -> e instanceof HarnessEntry.Assistant)).hasSize(2);
        Harness.Model wrongGeneration =
                update(finished, new Harness.Msg.ResponseBlock(generation + 5, 0)).model();
        assertThat(wrongGeneration.entries()).isEqualTo(finished.entries());
    }

    @Test
    void rapidSubmitsQueueFifoAndAreDrainedOneTurnAtATime() {
        Update<Harness.Model, Harness.Msg, Never> first =
                update(ready(), new Harness.Msg.Submit("one"));
        Update<Harness.Model, Harness.Msg, Never> second =
                update(first.model(), new Harness.Msg.Submit("two"));
        assertThat(second.model().pending()).containsExactly("two");
        assertThat(second.model().outstandingPrompts()).isEqualTo(2);

        Harness.Model afterFirst =
                update(
                                second.model(),
                                new Harness.Msg.ResponseBlock(second.model().generation(), 0))
                        .model();
        assertThat(afterFirst.working()).isTrue();
        assertThat(afterFirst.pending()).isEmpty();
        assertThat(afterFirst.entries()).contains(new HarnessEntry.User("two"));
    }

    @Test
    void aQueuedTurnRestartsTheElapsedLabel() {
        Update<Harness.Model, Harness.Msg, Never> first =
                update(ready().withNow(1_000_000_000L), new Harness.Msg.Submit("one"));
        Harness.Model ticked = update(first.model(), new Harness.Msg.Tick(1_000_000_000L)).model();
        Harness.Model queued = update(ticked, new Harness.Msg.Submit("two")).model();
        Harness.Model later = update(queued, new Harness.Msg.Tick(61_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(later).plainText())
                .contains("Working (1m 0s \u00b7 1 queued)");

        // The last block of the running turn hands over to the queued one, which has not been
        // observed by a tick yet, so its label starts at zero rather than at the old start.
        Harness.Model drained =
                update(later, new Harness.Msg.ResponseBlock(later.generation(), 0)).model();
        assertThat(drained.pending()).isEmpty();
        assertThat(drained.working()).isTrue();
        assertThat(drained.workStartedNanos()).isEqualTo(-1L);
        assertThat(HarnessProjection.topRule(drained).plainText()).contains("Working (0s)");

        Harness.Model started = update(drained, new Harness.Msg.Tick(70_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(started).plainText()).contains("Working (0s)");
        Harness.Model elapsed = update(started, new Harness.Msg.Tick(73_000_000_000L)).model();
        assertThat(HarnessProjection.topRule(elapsed).plainText()).contains("Working (3s)");
    }

    @Test
    void emptyResponsePlansCompleteWithoutAnotherDelay() {
        HarnessTranscript empty = new HarnessTranscript(List.of(
                new HarnessTranscript.HarnessTurn("q", List.of(),
                        HarnessTranscript.Usage.EMPTY, PROFILE)), PROFILE,
                HarnessTranscript.Usage.EMPTY, 1000, List.of());
        Harness.Model state =
                Harness.Model.initial(new Size(24, 80), "~/braxton", List.of())
                        .withTranscript(empty)
                        .withReplayIndex(0);
        Update<Harness.Model, Harness.Msg, Never> started =
                update(state, new Harness.Msg.Submit("x"));
        Update<Harness.Model, Harness.Msg, Never> finished =
                update(
                        started.model(),
                        new Harness.Msg.ResponseBlock(started.model().generation(), 0));
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
        Harness program = new Harness(() -> paced, "~/braxton", List.of());
        Harness.Model state =
                Harness.Model.initial(new Size(24, 80), "~/braxton", List.of())
                        .withTranscript(paced)
                        .withReplayIndex(0);

        Update<Harness.Model, Harness.Msg, Never> started =
                program.update(state, new Harness.Msg.Submit("x"));
        assertThat(delayOf(started)).isEqualTo(Harness.RESPONSE_DELAY);
        Update<Harness.Model, Harness.Msg, Never> second =
                program.update(
                        started.model(),
                        new Harness.Msg.ResponseBlock(started.model().generation(), 0));
        assertThat(second.model().entries()).contains(new HarnessEntry.Reasoning("one"));
        assertThat(delayOf(second)).isEqualTo(Harness.RESPONSE_DELAY);
    }

    @SuppressWarnings("unchecked")
    private static Duration delayOf(Update<Harness.Model, Harness.Msg, Never> transition) {
        return ((Command.Delay<Harness.Msg>) transition.command()).delay();
    }

    @Test
    void escapeIsANoOpWhenIdle() {
        Harness.Model state = ready();
        Harness.Model next = update(state, new Harness.Msg.Escape(1_000L)).model();
        assertThat(next).isEqualTo(state.withEscArmed(0));
    }

    @Test
    void secondEscapeInsideTheWindowStopsAndKeepsTheDraft() {
        Harness.Model state = ready().withDraft(Editor.Model.of("draft"));
        Harness.Model started = update(state, new Harness.Msg.Submit("hello")).model();
        Harness.Model armed = update(started, new Harness.Msg.Escape(1_000_000_000L)).model();
        assertThat(armed.escArmedUntilNanos()).isEqualTo(3_000_000_000L);
        Update<Harness.Model, Harness.Msg, Never> stopped =
                update(armed, new Harness.Msg.Escape(2_000_000_000L));
        assertThat(stopped.model().working()).isFalse();
        assertThat(stopped.model().entries()).contains(new HarnessEntry.Notice("Stopped."));
        assertThat(stopped.model().pending()).isEmpty();
        assertThat(stopped.model().draft().text()).isEmpty();
        assertThat(stopped.command()).isInstanceOf(Command.CancelAll.class);
    }

    /**
     * A terminal that sends ESC for the Alt key encodes Alt+Enter as the byte pair ESC CR, so two
     * Alt+Enter submissions used to arrive as two bare Escapes: neither Enter reached the composer
     * (the README promises "Type / Enter") and the second fabricated Escape landed inside the 2 s
     * window documented for Esc, stopping the running turn and discarding the queued prompts.
     */
    @Test
    void altEnterSubmissionsReachTheComposerAndNeverArmTheEscapeWindow() throws IOException {
        Harness.Model running = update(ready(), new Harness.Msg.Submit("hello")).model();
        assertThat(running.working()).isTrue();

        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteArrayInputStream(
                new byte[] {0x1B, 0x0D, 0x1B, 0x0D})));
        Harness.Model state = running;
        for (int press = 0; press < 2; press++) {
            state = state.withDraft(Editor.Model.of("press " + press));
            InputEvent event = decoder.read().orElseThrow();
            Harness.Msg message = InputRouter.route(HarnessView.view(state), event).orElseThrow();
            assertThat(message)
                    .as("press %d reaches the composer", press)
                    .isEqualTo(new Harness.Msg.ComposerMsg(new Editor.Msg.Submit()));
            state = update(state, message).model();
        }

        assertThat(state.working()).as("a key the user never pressed must not stop the turn")
                .isTrue();
        assertThat(state.pending()).containsExactly("press 0", "press 1");
        assertThat(state.entries()).doesNotContain(new HarnessEntry.Notice("Stopped."));
        assertThat(state.escArmedUntilNanos()).isZero();
        assertThat(decoder.read()).as("both keystroke pairs were consumed").isEmpty();
    }

    @Test
    void stoppingReportsTheQueuedPromptsItDiscarded() {
        Harness.Model started = update(ready(), new Harness.Msg.Submit("one")).model();
        Harness.Model queued = update(started, new Harness.Msg.Submit("two")).model();
        Harness.Model twoQueued = update(queued, new Harness.Msg.Submit("three")).model();
        assertThat(twoQueued.pending()).hasSize(2);

        Harness.Model armed = update(twoQueued, new Harness.Msg.Escape(1_000_000_000L)).model();
        Harness.Model stopped = update(armed, new Harness.Msg.Escape(2_000_000_000L)).model();
        assertThat(stopped.pending()).isEmpty();
        assertThat(stopped.statusMessage()).contains("2 queued prompts");

        // Nothing queued means nothing to report, so the stop stays exactly as before.
        Harness.Model alone = update(started, new Harness.Msg.Escape(1_000_000_000L)).model();
        Harness.Model quiet = update(alone, new Harness.Msg.Escape(2_000_000_000L)).model();
        assertThat(quiet.statusMessage()).isEmpty();
        assertThat(quiet.entries()).contains(new HarnessEntry.Notice("Stopped."));

        Harness.Model oneArmed = update(queued, new Harness.Msg.Escape(1_000_000_000L)).model();
        Harness.Model oneStopped = update(oneArmed, new Harness.Msg.Escape(2_000_000_000L)).model();
        assertThat(oneStopped.statusMessage())
                .contains("1 queued prompt").doesNotContain("1 queued prompts");
    }

    @Test
    void escapeAfterTheWindowRearmsInsteadOfStopping() {
        Harness.Model started = update(ready(), new Harness.Msg.Submit("hello")).model();
        Harness.Model armed = update(started, new Harness.Msg.Escape(0L)).model();
        Harness.Model late =
                update(armed, new Harness.Msg.Escape(armed.escArmedUntilNanos() + 1)).model();
        assertThat(late.working()).isTrue();
        assertThat(late.escArmedUntilNanos()).isGreaterThan(armed.escArmedUntilNanos());
    }

    @Test
    void theOutstandingPromptLimitRejectsWithoutLosingTheDraft() {
        Harness.Model state = ready().withDraft(Editor.Model.of("keep me"));
        Harness.Model working = update(state, new Harness.Msg.Submit("active")).model();
        for (int i = 0; i < Harness.MAX_OUTSTANDING - 1; i++) {
            working = update(working, new Harness.Msg.Submit("queued-" + i)).model();
        }
        assertThat(working.outstandingPrompts()).isEqualTo(Harness.MAX_OUTSTANDING);
        Update<Harness.Model, Harness.Msg, Never> rejected =
                update(
                        working.withDraft(Editor.Model.of("keep me")),
                        new Harness.Msg.Submit("overflow"));
        assertThat(rejected.model().statusMessage()).contains("Queue full");
        assertThat(rejected.model().draft().text()).isEqualTo("keep me");
        assertThat(rejected.model().entries()).doesNotContain(new HarnessEntry.User("overflow"));
    }

    @Test
    void blankSubmissionsAndLoadingSubmissionsAreIgnored() {
        Update<Harness.Model, Harness.Msg, Never> blank =
                update(ready(), new Harness.Msg.Submit("   "));
        assertThat(blank.model().working()).isFalse();
        assertThat(blank.command()).isInstanceOf(Command.None.class);

        Harness.Model loading = Harness.Model.initial(new Size(24, 80), "~/braxton", List.of());
        Update<Harness.Model, Harness.Msg, Never> rejected =
                update(loading, new Harness.Msg.Submit("hi"));
        assertThat(rejected.model().statusMessage()).contains("still loading");
    }

    @Test
    void aSingleCtrlCArmingDoesNotExit() {
        Update<Harness.Model, Harness.Msg, Never> transition =
                update(ready(), new Harness.Msg.QuitRequest(1_000_000_000L));
        assertThat(transition.command()).isInstanceOf(Command.None.class);
        assertThat(transition.model().quitArmedUntilNanos()).isEqualTo(3_000_000_000L);
    }

    @Test
    void aSecondCtrlCWithinTwoSecondsExits() {
        Harness.Model armed = update(ready(), new Harness.Msg.QuitRequest(1_000_000_000L)).model();
        Update<Harness.Model, Harness.Msg, Never> confirmed =
                update(armed, new Harness.Msg.QuitRequest(3_000_000_000L));
        assertThat(confirmed.command()).isInstanceOf(Command.Quit.class);
        assertThat(confirmed.model().quitArmedUntilNanos()).isZero();
    }

    @Test
    void aCtrlCAfterTheWindowRearmsInsteadOfExiting() {
        Harness.Model armed = update(ready(), new Harness.Msg.QuitRequest(1_000_000_000L)).model();
        Update<Harness.Model, Harness.Msg, Never> again =
                update(armed, new Harness.Msg.QuitRequest(4_000_000_000L));
        assertThat(again.command()).isInstanceOf(Command.None.class);
        assertThat(again.model().quitArmedUntilNanos()).isEqualTo(6_000_000_000L);
    }

    @Test
    void anotherKeyCancelsThePendingCtrlC() {
        Harness.Model armed = update(ready(), new Harness.Msg.QuitRequest(1_000_000_000L)).model();
        Harness.Model typed =
                update(armed, new Harness.Msg.ComposerMsg(new Editor.Msg.Insert("x"))).model();
        assertThat(typed.quitArmedUntilNanos()).isZero();
        Update<Harness.Model, Harness.Msg, Never> after =
                update(typed, new Harness.Msg.QuitRequest(1_500_000_000L));
        assertThat(after.command()).isInstanceOf(Command.None.class);
    }

    /**
     * The README says any other key cancels the pending confirmation, but the composer only ever
     * produced a message for the gestures it maps: a key it ignores produced nothing, so the
     * harness never saw the key and the confirmation stayed armed.
     */
    @Test
    void aKeyTheComposerIgnoresStillCancelsThePendingCtrlC() {
        Harness.Model armed = ready().withQuitArmed(3_000_000_000L);
        View<Harness.Msg> view = HarnessView.view(armed);
        Optional<Harness.Msg> routed =
                InputRouter.route(view, InputEvent.KeyInput.of(Key.of("f1")));
        assertThat(routed).as("any other key must reach the harness").isPresent();
        Harness.Msg message = routed.orElseThrow();
        assertThat(message).isInstanceOf(Harness.Msg.UnhandledKey.class);
        Harness.Model after = update(armed, message).model();
        assertThat(after.quitArmedUntilNanos()).isZero();
        assertThat(update(after, new Harness.Msg.QuitRequest(1_500_000_000L)).command())
                .isInstanceOf(Command.None.class);
    }

    @Test
    void backgroundEventsDoNotCancelThePendingCtrlC() {
        Harness.Model armed = update(ready(), new Harness.Msg.QuitRequest(1_000_000_000L)).model();
        Harness.Model ticked = update(armed, new Harness.Msg.Tick(1_500_000_000L)).model();
        assertThat(ticked.quitArmedUntilNanos()).isEqualTo(3_000_000_000L);
        Harness.Model copied =
                update(
                                ticked,
                                new Harness.Msg.Copied(
                                        io.github.dhoard.braxton.clipboard.ClipboardResult.empty()))
                        .model();
        assertThat(copied.quitArmedUntilNanos()).isEqualTo(3_000_000_000L);
    }

    @Test
    void anExpiredConfirmationIsClearedByATick() {
        Harness.Model armed = update(ready(), new Harness.Msg.QuitRequest(1_000_000_000L)).model();
        Harness.Model expired = update(armed, new Harness.Msg.Tick(3_000_000_001L)).model();
        assertThat(expired.quitArmedUntilNanos()).isZero();
    }

    /**
     * The README says the Ctrl+C confirmation is transient feedback on the rule above the footer
     * and that another key cancels the pending confirmation. The hint is a status message, so once
     * the confirmation is abandoned or has expired the rule must stop claiming that a second
     * Ctrl+C exits; otherwise the bottom rule disagrees with the top rule (which drops its own
     * hint) and keeps a false promise on screen indefinitely.
     */
    @Test
    void anAbandonedCtrlCConfirmationTakesItsHintOffTheStatusRule() {
        Update<Harness.Model, Harness.Msg, Never> armed =
                update(ready(), new Harness.Msg.QuitRequest(1_000_000_000L));
        assertThat(painted(armed.model())).contains("Press Ctrl+C again");

        Harness.Model abandoned = update(armed.model(), new Harness.Msg.UnhandledKey()).model();
        assertThat(abandoned.quitArmedUntilNanos()).isZero();
        assertThat(painted(abandoned)).as("an abandoned confirmation must not keep its hint")
                .doesNotContain("Press Ctrl+C again");

        Harness.Model expired = update(armed.model(), new Harness.Msg.Tick(3_000_000_001L)).model();
        assertThat(expired.quitArmedUntilNanos()).isZero();
        assertThat(painted(expired)).as("an expired confirmation must not keep its hint")
                .doesNotContain("Press Ctrl+C again");

        // Only the confirmation's own hint goes: feedback a background event put on the rule after
        // the confirmation was armed still belongs to that event.
        Harness.Model reported =
                update(
                                armed.model(),
                                new Harness.Msg.Notice("Queue full (64); prompt not submitted."))
                        .model();
        assertThat(reported.quitArmedUntilNanos()).isEqualTo(3_000_000_000L);
        Harness.Model disarmed = update(reported, new Harness.Msg.UnhandledKey()).model();
        assertThat(disarmed.quitArmedUntilNanos()).isZero();
        assertThat(painted(disarmed)).contains("Queue full (64)")
                .doesNotContain("Press Ctrl+C again");
    }

    /** Every painted row of the production view, joined, so assertions read the real screen. */
    private static String painted(Harness.Model state) {
        return String.join("\n", HarnessView.renderLines(state));
    }

    @Test
    void anArmedConfirmationKeepsTheTickSubscriptionAlive() {
        Harness.Model armed = update(ready(), new Harness.Msg.QuitRequest(1_000_000_000L)).model();
        assertThat(Subscription.leaves(program().subscriptions(armed)))
                .anySatisfy(
                        subscription -> assertThat(subscription.id().value()).isEqualTo("tick"));
    }

    @Test
    void anEmptyCopyIntentDoesNotTouchTheClipboard() {
        Update<Harness.Model, Harness.Msg, Never> transition =
                update(ready(), new Harness.Msg.ComposerMsg(new Editor.Msg.Copy()));
        assertThat(transition.command()).isInstanceOf(Command.None.class);
        assertThat(transition.model().statusMessage()).isEqualTo("Nothing to copy.");
    }

    @Test
    void copyLastReplyWithoutAReplyIsANoticeNotACopy() {
        Harness.Model blank =
                Harness.Model.initial(new Size(24, 80), "~/braxton", List.of())
                        .withTranscript(HarnessTranscript.empty(List.of()));
        Update<Harness.Model, Harness.Msg, Never> transition =
                update(blank, new Harness.Msg.CopyLastReply());
        assertThat(transition.command()).isInstanceOf(Command.None.class);
        assertThat(transition.model().statusMessage()).contains("No assistant reply");
    }

    @Test
    void copyLastReplyCopiesTheMostRecentAssistantText() {
        Update<Harness.Model, Harness.Msg, Never> transition =
                update(ready(), new Harness.Msg.CopyLastReply());
        assertThat(transition.command()).isInstanceOf(Command.Copy.class);
    }

    @Test
    void copyVisibleBuildsAPlainSnapshotOfTheViewport() {
        Update<Harness.Model, Harness.Msg, Never> transition =
                update(ready(), new Harness.Msg.CopyVisible());
        assertThat(transition.command()).isInstanceOf(Command.Copy.class);
        Command.Copy<Harness.Msg> copy = (Command.Copy<Harness.Msg>) transition.command();
        assertThat(copy.text()).contains("AI Harness");
    }

    @Test
    void clipboardResultsBecomeTruthfulStatusMessages() {
        Harness.Model internal =
                update(
                                ready(),
                                new Harness.Msg.Copied(
                                        new io.github.dhoard.braxton.clipboard.ClipboardResult(
                                                "x",
                                                1,
                                                false,
                                                true,
                                                false,
                                                false,
                                                io.github.dhoard.braxton.clipboard.ClipboardPath
                                                        .INTERNAL_ONLY)))
                        .model();
        assertThat(internal.statusMessage()).contains("in-process clipboard only");
        Harness.Model truncated =
                update(
                                ready(),
                                new Harness.Msg.Copied(
                                        new io.github.dhoard.braxton.clipboard.ClipboardResult(
                                                "x",
                                                1,
                                                true,
                                                true,
                                                true,
                                                false,
                                                io.github.dhoard.braxton.clipboard.ClipboardPath
                                                        .OSC52_DIRECT)))
                        .model();
        assertThat(truncated.statusMessage()).contains("truncated").contains("OSC 52");
    }

    @Test
    void subscriptionsTickOnlyWhileWorkingAndAlwaysObserveResize() {
        Harness program = program();
        List<String> idle = Subscription.leaves(program.subscriptions(ready()))
                .stream().map(s -> s.id().value()).toList();
        assertThat(idle).containsExactly("resize");
        Harness.Model working = update(ready(), new Harness.Msg.Submit("hi")).model();
        List<String> busy = Subscription.leaves(program.subscriptions(working))
                .stream().map(s -> s.id().value()).toList();
        assertThat(busy).containsExactlyInAnyOrder("tick", "resize");
    }

    @Test
    void editorPasteRequestBecomesAReadClipboardCommand() {
        Update<Harness.Model, Harness.Msg, Never> transition =
                update(ready(), new Harness.Msg.ComposerMsg(new Editor.Msg.PasteRequest()));
        assertThat(transition.command()).isInstanceOf(Command.ReadClipboard.class);
    }

    @Test
    void historyStaysBounded() {
        Harness.Model state = ready();
        for (int i = 0; i < 120; i++) {
            state = update(state, new Harness.Msg.Submit("p" + i)).model();
        }
        assertThat(state.entries()).hasSizeLessThanOrEqualTo(Harness.Model.MAX_ENTRIES);
    }
}

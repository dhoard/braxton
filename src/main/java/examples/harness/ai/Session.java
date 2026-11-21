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

package examples.harness.ai;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Transcript/session component. Owns loading, history, metadata, replay and scroll state.
 */
public final class Session implements Component<Session.Model, Session.Msg, Never> {

    /**
     * The identity of the session's one element subtree; a mount scopes it to its own path.
     */
    public enum Status {
        /**
         * The offline transcript is being loaded.
         */
        LOADING,
        /**
         * The offline transcript is available.
         */
        READY,
        /**
         * Loading the offline transcript failed.
         */
        FAILED
    }

    /**
     * The loaded offline transcript, metadata, replay position, and scroll position.
     *
     * @param status the process status or current application state
     * @param entries the transcript entries in display order
     * @param profile the recorded provider and model profile
     * @param usage the recorded token usage
     * @param contextWindow the model's context capacity in tokens
     * @param turns the transcript turns in source order
     * @param replayIndex the next replay turn index
     * @param scrollBack the number of transcript rows scrolled back from the end
     */
    public record Model(
            Status status,
            List<HarnessEntry> entries,
            HarnessTranscript.Profile profile,
            HarnessTranscript.Usage usage,
            int contextWindow,
            List<HarnessTranscript.HarnessTurn> turns,
            int replayIndex,
            int scrollBack) {
        /**
         * Creates a {@code Model} value.
         *
         * @param status the process status or current application state
         * @param entries the transcript entries in display order
         * @param profile the recorded provider and model profile
         * @param usage the recorded token usage
         * @param contextWindow the model's context capacity in tokens
         * @param turns the transcript turns in source order
         * @param replayIndex the next replay turn index
         * @param scrollBack the number of transcript rows scrolled back from the end
         */
        public Model {
            status = status == null ? Status.LOADING : status;
            entries = List.copyOf(
                    entries.size() > Harness.Model.MAX_ENTRIES
                            ? entries.subList(entries.size() - Harness.Model.MAX_ENTRIES, entries.size())
                            : entries);
            profile = profile == null ? HarnessTranscript.Profile.DEFAULT : profile;
            usage = usage == null ? HarnessTranscript.Usage.EMPTY : usage;
            contextWindow = contextWindow <= 0 ? HarnessTranscript.Profile.DEFAULT_CONTEXT_WINDOW : contextWindow;
            turns = List.copyOf(turns);
        }

        /**
         * Returns the same value with status replaced.
         *
         * @param value the requested status
         * @return the value with the requested status
         */
        public Model withStatus(Status value) {
            return new Model(value, entries, profile, usage, contextWindow, turns, replayIndex, scrollBack);
        }

        /**
         * Returns the same value with entries replaced.
         *
         * @param value the requested entries
         * @return the value with the requested entries
         */
        public Model withEntries(List<HarnessEntry> value) {
            return new Model(status, value, profile, usage, contextWindow, turns, replayIndex, scrollBack);
        }

        /**
         * Returns the same value with scroll replaced.
         *
         * @param value the requested scroll
         * @return the value with the requested scroll
         */
        public Model withScroll(int value) {
            return new Model(status, entries, profile, usage, contextWindow, turns, replayIndex, value);
        }

        /**
         * Returns the same value with replay index replaced.
         *
         * @param value the requested replay index
         * @return the value with the requested replay index
         */
        public Model withReplayIndex(int value) {
            return new Model(status, entries, profile, usage, contextWindow, turns, value, scrollBack);
        }

        /**
         * Returns the model seeded with the transcript, every entry stamped with the time it enters
         * the session.
         *
         * @param transcript the loaded transcript
         * @param nowMillis when the transcript entered this session, in Unix milliseconds
         * @return the value with the requested transcript
         */
        public Model withTranscript(HarnessTranscript transcript, long nowMillis) {
            List<HarnessEntry> seeded = new ArrayList<>(entries);
            int replay = 0;
            HarnessTranscript.Profile nextProfile = profile;
            HarnessTranscript.Usage nextUsage = usage;
            if (transcript.turns().isEmpty()) {
                seeded.add(new HarnessEntry.Notice(
                        "No example transcript is loaded. Fallback mock responses are active.", nowMillis));
            } else {
                HarnessTranscript.HarnessTurn first = transcript.turns().get(0);
                if (!first.userText().isBlank()) {
                    seeded.add(new HarnessEntry.User(first.userText(), nowMillis));
                }
                for (HarnessEntry response : first.responses()) {
                    seeded.add(response.at(nowMillis));
                }
                if (!HarnessTranscript.Usage.EMPTY.equals(first.usage())) {
                    nextUsage = first.usage();
                }
                if (first.profile() != null) {
                    nextProfile = first.profile();
                }
                replay = 1;
            }
            Model next = new Model(
                    Status.READY,
                    seeded,
                    nextProfile,
                    nextUsage,
                    transcript.contextWindow(),
                    transcript.turns(),
                    replay,
                    scrollBack);
            return next.withEntries(next.entries());
        }

        /**
         * Returns the same value with metadata replaced.
         *
         * @param nextProfile the next recorded model profile
         * @param nextUsage the accumulated token usage
         * @return the value with the requested metadata
         */
        public Model withMetadata(HarnessTranscript.Profile nextProfile, HarnessTranscript.Usage nextUsage) {
            return new Model(
                    status,
                    entries,
                    nextProfile == null ? profile : nextProfile,
                    nextUsage == null || HarnessTranscript.Usage.EMPTY.equals(nextUsage) ? usage : nextUsage,
                    contextWindow,
                    turns,
                    replayIndex,
                    scrollBack);
        }
    }

    /**
     * Typed offline transcript, metadata, replay, and scrolling updates.
     */
    public sealed interface Msg {
        /**
         * Carries the loaded value into the owning component update.
         *
         * @param transcript the immutable example transcript
         */
        record Loaded(HarnessTranscript transcript) implements Msg {}

        /**
         * Carries the failed value into the owning component update.
         *
         * @param failure the primary failure, or null for a successful outcome
         */
        record Failed(Throwable failure) implements Msg {}

        /**
         * Carries the status value into the owning component update.
         *
         * @param value the status the harness is showing
         */
        record StatusValue(Status value) implements Msg {}

        /**
         * Carries the entries value into the owning component update.
         *
         * @param value the entries to show, newest first
         */
        record Entries(List<HarnessEntry> value) implements Msg {}

        /**
         * Carries the scroll value into the owning component update.
         *
         * @param value the new scroll offset
         */
        record Scroll(int value) implements Msg {}

        /**
         * Carries the replay value into the owning component update.
         *
         * @param value the replay generation to accept
         */
        record Replay(int value) implements Msg {}

        /**
         * Carries the metadata update for the owning component.
         *
         * @param profile the recorded provider and model profile
         * @param usage the recorded token usage
         */
        record Metadata(HarnessTranscript.Profile profile, HarnessTranscript.Usage usage) implements Msg {}
    }

    private final Supplier<HarnessTranscript> loader;
    private final List<String> warnings;
    private final LongSupplier clock;

    Session(Supplier<HarnessTranscript> loader, List<String> warnings) {
        this(loader, warnings, System::currentTimeMillis);
    }

    Session(Supplier<HarnessTranscript> loader, List<String> warnings, LongSupplier clock) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.warnings = List.copyOf(warnings);
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    static Model initial(List<String> warnings) {
        return initial(warnings, 0L);
    }

    static Model initial(List<String> warnings, long nowMillis) {
        var entries = new ArrayList<HarnessEntry>();
        entries.add(
                new HarnessEntry.Notice("Bundled example transcript. Type a prompt; no model is called.", nowMillis));
        for (String warning : warnings.subList(0, Math.min(3, warnings.size())))
            entries.add(new HarnessEntry.Notice("Transcript warning: " + warning, nowMillis));
        return new Model(Status.LOADING, entries, null, null, 0, List.of(), 0, 0);
    }

    @Override
    public Update<Model, Msg, Never> init(InitContext context) {
        return Update.next(
                initial(warnings, now()),
                Command.task(EffectId.of("load"), loader::get, Msg.Loaded::new, Msg.Failed::new));
    }

    private long now() {
        return Math.max(0L, clock.getAsLong());
    }

    @Override
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        Model next = switch (message) {
            case Msg.Loaded loaded ->
                model.withTranscript(
                        loaded.transcript() == null
                                ? HarnessTranscript.empty(List.of("empty transcript"))
                                : loaded.transcript(),
                        now());
            case Msg.Failed failed -> failed(model, failed.failure());
            case Msg.Metadata metadata -> model.withMetadata(metadata.profile(), metadata.usage());
            case Msg.StatusValue value -> model.withStatus(value.value());
            case Msg.Entries value -> model.withEntries(value.value());
            case Msg.Scroll value -> model.withScroll(value.value());
            case Msg.Replay value -> model.withReplayIndex(value.value());
        };
        return Update.next(next);
    }

    private Model failed(Model model, Throwable failure) {
        var entries = new ArrayList<>(model.entries());
        entries.add(new HarnessEntry.Failure(
                "Could not read the bundled example transcript ("
                        + (failure == null
                                ? "unknown error"
                                : failure.getClass().getSimpleName())
                        + "); fallback responses are active.",
                now()));
        return model.withStatus(Status.READY).withEntries(entries);
    }

    @Override
    public Element<Msg> view(Model model, ViewContext context) {
        return scene(model, context);
    }

    /**
     * The session's window as one element tree.
     */
    private static Element<Msg> scene(Model model, ViewContext context) {
        return Elements.column(
                HarnessProjection.window(
                                model,
                                context.viewport().cols(),
                                context.viewport().rows())
                        .stream()
                        .map(Elements::<Msg>text)
                        .toList());
    }
}

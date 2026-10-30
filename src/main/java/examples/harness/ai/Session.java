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

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Transcript/session component. Owns loading, history, metadata, replay and scroll state. */
public final class Session implements Component<Session.Model, Session.Msg, Never> {
    public enum Status {
        LOADING,
        READY,
        FAILED
    }

    public record Model(
            Status status,
            List<HarnessEntry> entries,
            HarnessTranscript.Profile profile,
            HarnessTranscript.Usage usage,
            int contextWindow,
            List<HarnessTranscript.HarnessTurn> turns,
            int replayIndex,
            int scrollBack,
            String statusMessage) {
        public Model {
            status = status == null ? Status.LOADING : status;
            entries =
                    List.copyOf(
                            entries.size() > Harness.Model.MAX_ENTRIES
                                    ? entries.subList(
                                            entries.size() - Harness.Model.MAX_ENTRIES,
                                            entries.size())
                                    : entries);
            profile = profile == null ? HarnessTranscript.Profile.DEFAULT : profile;
            usage = usage == null ? HarnessTranscript.Usage.EMPTY : usage;
            contextWindow =
                    contextWindow <= 0
                            ? HarnessTranscript.Profile.DEFAULT_CONTEXT_WINDOW
                            : contextWindow;
            turns = List.copyOf(turns);
            statusMessage = statusMessage == null ? "" : statusMessage;
        }

        public Model withStatus(Status value) {
            return new Model(
                    value,
                    entries,
                    profile,
                    usage,
                    contextWindow,
                    turns,
                    replayIndex,
                    scrollBack,
                    statusMessage);
        }

        public Model withEntries(List<HarnessEntry> value) {
            return new Model(
                    status,
                    value,
                    profile,
                    usage,
                    contextWindow,
                    turns,
                    replayIndex,
                    scrollBack,
                    statusMessage);
        }

        public Model withScroll(int value) {
            return new Model(
                    status,
                    entries,
                    profile,
                    usage,
                    contextWindow,
                    turns,
                    replayIndex,
                    value,
                    statusMessage);
        }

        public Model withReplayIndex(int value) {
            return new Model(
                    status,
                    entries,
                    profile,
                    usage,
                    contextWindow,
                    turns,
                    value,
                    scrollBack,
                    statusMessage);
        }

        public Model withStatusMessage(String value) {
            return new Model(
                    status,
                    entries,
                    profile,
                    usage,
                    contextWindow,
                    turns,
                    replayIndex,
                    scrollBack,
                    value);
        }

        public Model withTranscript(HarnessTranscript transcript) {
            List<HarnessEntry> seeded = new ArrayList<>(entries);
            int replay = 0;
            HarnessTranscript.Profile nextProfile = profile;
            HarnessTranscript.Usage nextUsage = usage;
            if (transcript.turns().isEmpty()) {
                seeded.add(
                        new HarnessEntry.Notice(
                                "No example transcript is loaded. Fallback mock responses are active."));
            } else {
                HarnessTranscript.HarnessTurn first = transcript.turns().get(0);
                if (!first.userText().isBlank()) {
                    seeded.add(new HarnessEntry.User(first.userText()));
                }
                seeded.addAll(first.responses());
                if (!HarnessTranscript.Usage.EMPTY.equals(first.usage())) {
                    nextUsage = first.usage();
                }
                if (first.profile() != null) {
                    nextProfile = first.profile();
                }
                replay = 1;
            }
            Model next =
                    new Model(
                            Status.READY,
                            seeded,
                            nextProfile,
                            nextUsage,
                            transcript.contextWindow(),
                            transcript.turns(),
                            replay,
                            scrollBack,
                            statusMessage);
            return next.withEntries(next.entries());
        }

        public Model withMetadata(
                HarnessTranscript.Profile nextProfile, HarnessTranscript.Usage nextUsage) {
            return new Model(
                    status,
                    entries,
                    nextProfile == null ? profile : nextProfile,
                    nextUsage == null || HarnessTranscript.Usage.EMPTY.equals(nextUsage)
                            ? usage
                            : nextUsage,
                    contextWindow,
                    turns,
                    replayIndex,
                    scrollBack,
                    statusMessage);
        }
    }

    public sealed interface Msg {
        record Loaded(HarnessTranscript transcript) implements Msg {}

        record Failed(Throwable failure) implements Msg {}

        record StatusValue(Status value) implements Msg {}

        record Entries(List<HarnessEntry> value) implements Msg {}

        record Scroll(int value) implements Msg {}

        record Replay(int value) implements Msg {}

        record Notice(String value) implements Msg {}

        record Metadata(HarnessTranscript.Profile profile, HarnessTranscript.Usage usage)
                implements Msg {}
    }

    private final Supplier<HarnessTranscript> loader;
    private final List<String> warnings;

    Session(Supplier<HarnessTranscript> loader, List<String> warnings) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.warnings = List.copyOf(warnings);
    }

    static Model initial(List<String> warnings) {
        var entries = new ArrayList<HarnessEntry>();
        entries.add(
                new HarnessEntry.Notice(
                        "Bundled example transcript. Type a prompt; no model is called."));
        for (String warning : warnings.subList(0, Math.min(3, warnings.size())))
            entries.add(new HarnessEntry.Notice("Transcript warning: " + warning));
        return new Model(Status.LOADING, entries, null, null, 0, List.of(), 0, 0, "");
    }

    @Override
    public Update<Model, Msg, Never> init(InitContext context) {
        return Update.next(
                initial(warnings),
                Command.task(EffectId.of("load"), loader::get, Msg.Loaded::new, Msg.Failed::new));
    }

    @Override
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        Model next =
                switch (message) {
                    case Msg.Loaded loaded ->
                            model.withTranscript(
                                    loaded.transcript() == null
                                            ? HarnessTranscript.empty(List.of("empty transcript"))
                                            : loaded.transcript());
                    case Msg.Failed failed -> failed(model, failed.failure());
                    case Msg.Metadata metadata ->
                            model.withMetadata(metadata.profile(), metadata.usage());
                    case Msg.StatusValue value -> model.withStatus(value.value());
                    case Msg.Entries value -> model.withEntries(value.value());
                    case Msg.Scroll value -> model.withScroll(value.value());
                    case Msg.Replay value -> model.withReplayIndex(value.value());
                    case Msg.Notice value -> model.withStatusMessage(value.value());
                };
        return Update.next(next);
    }

    private Model failed(Model model, Throwable failure) {
        var entries = new ArrayList<>(model.entries());
        entries.add(
                new HarnessEntry.Failure(
                        "Could not read the bundled example transcript ("
                                + (failure == null
                                        ? "unknown error"
                                        : failure.getClass().getSimpleName())
                                + "); fallback responses are active."));
        return model.withStatus(Status.READY)
                .withEntries(entries)
                .withStatusMessage("Transcript unavailable; mock fallback active.");
    }

    @Override
    public View<Msg> view(Model model, ViewContext context) {
        return Views.column(
                HarnessProjection.window(
                                model, context.viewport().cols(), context.viewport().rows())
                        .stream()
                        .map(Views::<Msg>text)
                        .toList());
    }
}

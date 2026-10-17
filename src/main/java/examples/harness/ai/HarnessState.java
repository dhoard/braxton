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

import java.util.ArrayList;
import java.util.List;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.EditorState;

/**
 * The harness's complete observable state.
 *
 * <p>Everything here is immutable data. Threads, executors, streams, clipboard handles, and render
 * caches are runtime concerns and must never be stored in this record. History is bounded to
 * {@link #MAX_ENTRIES} on every append, including seeding.</p>
 *
 * <p>Two confirmations are time-based: {@code escArmedUntilNanos} for cancelling a turn and
 * {@code quitArmedUntilNanos} for confirming exit with a second Ctrl+C.</p>
 */
public record HarnessState(
        Status status,
        List<HarnessEntry> entries,
        HarnessTranscript.Profile profile,
        HarnessTranscript.Usage usage,
        int contextWindow,
        List<HarnessTranscript.HarnessTurn> turns,
        int replayIndex,
        EditorState draft,
        int scrollBack,
        List<String> pending,
        Active active,
        long generation,
        long nowNanos,
        long workStartedNanos,
        long escArmedUntilNanos,
        long quitArmedUntilNanos,
        Size viewport,
        String statusMessage,
        String projectPath) {

    /** Transcript availability. Editing is possible while loading; submission is not. */
    public enum Status {
        LOADING,
        READY,
        FAILED
    }

    /** One response sequence in flight: the selected turn and the next block to deliver. */
    public record Active(HarnessTranscript.HarnessTurn turn, int blockIndex) {
    }

    public static final int MAX_ENTRIES = 200;

    public HarnessState {
        status = status == null ? Status.LOADING : status;
        entries = List.copyOf(entries);
        profile = profile == null ? HarnessTranscript.Profile.DEFAULT : profile;
        usage = usage == null ? HarnessTranscript.Usage.EMPTY : usage;
        contextWindow = contextWindow <= 0
                ? HarnessTranscript.Profile.DEFAULT_CONTEXT_WINDOW : contextWindow;
        turns = List.copyOf(turns);
        draft = draft == null ? EditorState.empty() : draft;
        pending = List.copyOf(pending);
        escArmedUntilNanos = Math.max(0L, escArmedUntilNanos);
        quitArmedUntilNanos = Math.max(0L, quitArmedUntilNanos);
        viewport = viewport == null || viewport.isEmpty() ? new Size(24, 80) : viewport;
        statusMessage = statusMessage == null ? "" : statusMessage;
        projectPath = projectPath == null ? "" : projectPath;
    }

    public static HarnessState initial(Size viewport, String projectPath, List<String> warnings) {
        List<HarnessEntry> entries = new ArrayList<>();
        entries.add(new HarnessEntry.Notice(
                "Bundled example transcript. Type a prompt; no model is called."));
        for (int i = 0; i < Math.min(3, warnings.size()); i++) {
            entries.add(new HarnessEntry.Notice("Transcript warning: " + warnings.get(i)));
        }
        return new HarnessState(Status.LOADING, entries, HarnessTranscript.Profile.DEFAULT,
                HarnessTranscript.Usage.EMPTY, HarnessTranscript.Profile.DEFAULT_CONTEXT_WINDOW,
                List.of(), 0, EditorState.empty(), 0, List.of(), null, 0L, 0L, 0L, 0L, 0L,
                viewport, "", projectPath);
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

    public HarnessState withStatus(Status value) {
        return new HarnessState(value, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, active, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    public HarnessState withEntries(List<HarnessEntry> value) {
        List<HarnessEntry> trimmed = value;
        if (value.size() > MAX_ENTRIES) {
            trimmed = List.copyOf(value.subList(value.size() - MAX_ENTRIES, value.size()));
        }
        return new HarnessState(status, trimmed, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, active, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    public HarnessState withDraft(EditorState value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                value, scrollBack, pending, active, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    public HarnessState withScroll(int value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, value, pending, active, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    public HarnessState withPending(List<String> value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, value, active, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    public HarnessState withActive(Active value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, value, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    public HarnessState withGeneration(long value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, active, value, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    public HarnessState withNow(long value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, active, generation, value, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    public HarnessState withWorkStarted(long value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, active, generation, nowNanos, value,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    public HarnessState withEscArmed(long value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, active, generation, nowNanos, workStartedNanos,
                value, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }

    /** Arms or clears the two-press Ctrl+C exit confirmation. */
    public HarnessState withQuitArmed(long value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, active, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, value, viewport, statusMessage, projectPath);
    }

    /** Discards a pending Ctrl+C confirmation, e.g. because another key was pressed. */
    public HarnessState disarmQuit() {
        return withQuitArmed(0L);
    }

    public HarnessState withViewport(Size value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, active, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, value, statusMessage, projectPath);
    }

    public HarnessState withStatusMessage(String value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, replayIndex,
                draft, scrollBack, pending, active, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, value, projectPath);
    }

    public HarnessState withTranscript(HarnessTranscript transcript) {
        List<HarnessEntry> seeded = new ArrayList<>(entries);
        int replay = 0;
        HarnessTranscript.Profile nextProfile = profile;
        HarnessTranscript.Usage nextUsage = usage;
        if (transcript.turns().isEmpty()) {
            seeded.add(new HarnessEntry.Notice(
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
        HarnessState next = new HarnessState(Status.READY, seeded, nextProfile, nextUsage,
                transcript.contextWindow(), transcript.turns(), replay, draft, scrollBack, pending,
                active, generation, nowNanos, workStartedNanos, escArmedUntilNanos,
                quitArmedUntilNanos, viewport, statusMessage, projectPath);
        return next.withEntries(next.entries());
    }

    public HarnessState withMetadata(HarnessTranscript.Profile nextProfile,
            HarnessTranscript.Usage nextUsage) {
        return new HarnessState(status, entries,
                nextProfile == null ? profile : nextProfile,
                nextUsage == null || HarnessTranscript.Usage.EMPTY.equals(nextUsage) ? usage : nextUsage,
                contextWindow, turns, replayIndex, draft, scrollBack, pending, active, generation,
                nowNanos, workStartedNanos, escArmedUntilNanos, quitArmedUntilNanos, viewport,
                statusMessage, projectPath);
    }

    public HarnessState withReplayIndex(int value) {
        return new HarnessState(status, entries, profile, usage, contextWindow, turns, value,
                draft, scrollBack, pending, active, generation, nowNanos, workStartedNanos,
                escArmedUntilNanos, quitArmedUntilNanos, viewport, statusMessage, projectPath);
    }
}

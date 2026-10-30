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

import io.github.dhoard.braxton.app.TerminalCapabilities;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.widget.Editor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Randomized message sequences keep the harness model, its bounds, and its view consistent.
 *
 * <p>The harness is an example application, not a widget: its invariants (bounded history, bounded
 * queue, non-negative windows, a view that always builds) are the ones a real application relies on
 * when timers, responses, input, and resizes interleave arbitrarily.</p>
 */
class HarnessPropertyTest {

    private static final String[] TEXTS = {"", "hi", "hello world", "a\nb", "\u65e5\u672c", "  x  ",
            "\u0000bad", "line one\ntwo\nthree"};

    private static final String FIXTURE =
            "{\"kind\":\"event\",\"payload\":{\"type\":\"input.attached\",\"inputId\":\"i1\","
                    + "\"text\":\"q1\"}}\n"
                    + "{\"kind\":\"event\",\"payload\":{\"type\":\"turn.started\",\"turnId\":\"t1\","
                    + "\"inputId\":\"i1\"}}\n"
                    + "{\"kind\":\"event\",\"payload\":{\"type\":\"assistant.completed\","
                    + "\"turnId\":\"t1\",\"text\":\"a1\"}}\n"
                    + "{\"kind\":\"event\",\"payload\":{\"type\":\"turn.started\",\"turnId\":\"t2\","
                    + "\"inputId\":\"i2\"}}\n"
                    + "{\"kind\":\"event\",\"payload\":{\"type\":\"assistant.completed\","
                    + "\"turnId\":\"t2\",\"text\":\"a2\"}}\n";

    private static HarnessTranscript transcript() {
        try {
            return new TranscriptJsonlReader().read(new StringReader(FIXTURE));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Harness.Msg randomMessage(Random random) {
        return switch (random.nextInt(15)) {
            case 0 -> new Harness.Msg.Submit(TEXTS[random.nextInt(TEXTS.length)]);
            case 1 ->
                    new Harness.Msg.ComposerMsg(
                            new Editor.Msg.Insert(TEXTS[random.nextInt(TEXTS.length)]));
            case 2 -> new Harness.Msg.ComposerMsg(new Editor.Msg.Backspace());
            case 3 -> new Harness.Msg.Tick(random.nextInt(1_000_000_000));
            case 4 -> new Harness.Msg.Escape(random.nextInt(1_000_000_000));
            case 5 -> new Harness.Msg.QuitRequest(random.nextInt(1_000_000_000));
            case 6 -> new Harness.Msg.ResponseBlock(random.nextInt(4), random.nextInt(4));
            case 7 -> new Harness.Msg.ScrollUp();
            case 8 -> new Harness.Msg.ScrollDown();
            case 9 -> new Harness.Msg.PageUp();
            case 10 -> new Harness.Msg.PageDown();
            case 11 ->
                    new Harness.Msg.Resized(
                            new Size(1 + random.nextInt(30), 1 + random.nextInt(60)));
            case 12 -> new Harness.Msg.SessionMsg(new Session.Msg.Loaded(transcript()));
            case 13 -> new Harness.Msg.ComposerMsg(new Editor.Msg.Submit());
            default -> new Harness.Msg.CopyVisible();
        };
    }

    @Test
    void randomMessageSequencesKeepTheProgramConsistent() {
        Random random = new Random(303L);
        List<String> failures = new ArrayList<>();
        for (int iteration = 0; iteration < 300; iteration++) {
            Harness program =
                    new Harness(
                            HarnessPropertyTest::transcript,
                            "/tmp/project",
                            List.of("warning"));
            Harness.Model state =
                    program.init(new InitContext(new Viewport(12, 40), TerminalCapabilities.PLAIN))
                            .model();
            for (int step = 0; step < 40; step++) {
                Harness.Msg message = randomMessage(random);
                Update<Harness.Model, Harness.Msg, Never> transition;
                try {
                    transition = program.update(state, message);
                } catch (RuntimeException failure) {
                    failures.add("update threw " + failure + " for " + message);
                    break;
                }
                state = transition.model();
                if (state.entries().size() > Harness.Model.MAX_ENTRIES) {
                    failures.add("entries " + state.entries().size() + " exceed the bound");
                }
                if (state.pending().size() > Harness.MAX_OUTSTANDING) {
                    failures.add("pending " + state.pending().size() + " exceeds the bound");
                }
                if (state.escArmedUntilNanos() < 0 || state.quitArmedUntilNanos() < 0) {
                    failures.add("a confirmation window is negative");
                }
                if (state.scrollBack() < 0) {
                    failures.add("scroll is negative");
                }
                if (state.working() && state.active() == null) {
                    failures.add("working without an active turn");
                }
                if (state.active() == null && state.workStartedNanos() < 0) {
                    failures.add("an idle harness still waits for a work start");
                }
                try {
                    program.view(
                            state,
                            new ViewContext(
                                    new Viewport(
                                            state.viewport().rows(), state.viewport().cols())));
                } catch (RuntimeException failure) {
                    failures.add("view threw " + failure);
                }
            }
            if (failures.size() > 6) {
                break;
            }
        }
        if (!failures.isEmpty()) {
            throw new AssertionError(String.join("\n", failures));
        }
    }
}

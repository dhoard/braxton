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

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.layout.Size;

/** Deterministic replay selection and the offline fallback. */
class HarnessMockTest {

    private static HarnessState state(int replayIndex, List<HarnessTranscript.HarnessTurn> turns) {
        return new HarnessState(HarnessState.Status.READY, List.of(),
                HarnessTranscript.Profile.DEFAULT, HarnessTranscript.Usage.EMPTY, 1_000, turns,
                replayIndex, io.github.dhoard.braxton.widget.EditorState.empty(), 0, List.of(), null,
                0L, 0L, 0L, 0L, 0L, new Size(24, 80), "", "~/project");
    }

    @Test
    void aRecordedTurnIsReplayedUntilTheTurnsRunOut() {
        HarnessTranscript.HarnessTurn turn = new HarnessTranscript.HarnessTurn("",
                List.of(new HarnessEntry.Assistant("recorded")), HarnessTranscript.Usage.EMPTY,
                HarnessTranscript.Profile.DEFAULT);
        List<HarnessTranscript.HarnessTurn> turns = List.of(turn);
        assertThat(HarnessMock.hasRecordedTurn(state(0, turns))).isTrue();
        assertThat(HarnessMock.hasRecordedTurn(state(1, turns))).isFalse();
        assertThat(HarnessMock.turnFor(state(0, turns), "ignored")).isSameAs(turn);
        assertThat(HarnessMock.turnFor(state(1, turns), "prompt").responses().get(0))
                .isInstanceOf(HarnessEntry.Reasoning.class);
    }

    @Test
    void theFallbackIsStableForAGivenPrompt() {
        HarnessTranscript.HarnessTurn first = HarnessMock.fallback("summarize the loop",
                HarnessTranscript.Profile.DEFAULT);
        HarnessTranscript.HarnessTurn again = HarnessMock.fallback("summarize the loop",
                HarnessTranscript.Profile.DEFAULT);
        assertThat(first).isEqualTo(again);
        assertThat(first.responses()).hasSize(3);
        assertThat(first.responses().get(1)).isInstanceOf(HarnessEntry.Tool.class);
        assertThat(first.responses().get(2)).isInstanceOf(HarnessEntry.Assistant.class);
        assertThat(((HarnessEntry.Assistant) first.responses().get(2)).text())
                .contains("summarize the loop");
        assertThat(first.profile()).isEqualTo(HarnessTranscript.Profile.DEFAULT);
        assertThat(first.usage().inputTokens()).isPositive();
        assertThat(first.usage().contextTokens())
                .isEqualTo(first.usage().inputTokens() + first.usage().outputTokens());
    }
}

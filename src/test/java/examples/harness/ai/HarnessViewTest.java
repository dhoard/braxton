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

import java.util.ArrayList;
import java.util.List;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.view.View;

import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.widget.EditorState;

/** Harness layout and palette, measured through the real production view. */
class HarnessViewTest {

    private static final HarnessTranscript.Profile PROFILE =
            new HarnessTranscript.Profile("amanda", "qwen3.8-flash-next", "high");

    private static HarnessState state(String draft) {
        HarnessState base = HarnessState.initial(new Size(40, 120), "~/Development/braxton", List.of());
        List<HarnessEntry> entries = List.of(
                new HarnessEntry.Notice("Bundled example transcript. Type a prompt."),
                new HarnessEntry.User("u there?"),
                new HarnessEntry.Reasoning("The user is checking whether the harness is present."),
                new HarnessEntry.Tool("read README.md", "line one\nline two", false,
                        HarnessEntry.ToolStatus.SUCCESS),
                new HarnessEntry.Assistant("Yes \u2014 here. I'm ready."));
        HarnessTranscript.Usage usage = new HarnessTranscript.Usage(7091, 146, 0, 7091, 7237, 42.0);
        return new HarnessState(HarnessState.Status.READY, entries, PROFILE, usage,
                1_048_576, List.of(), 0, EditorState.of(draft), 0, List.of(), null, 0L,
                0L, 0L, 0L, 0L, new Size(40, 120), "", "~/Development/braxton");
    }

    private static List<String> lines(HarnessState state) {
        return HarnessView.renderLines(state);
    }

    @Test
    void aDraftTallerThanTheViewportStillShowsTheCaret() {
        StringBuilder draft = new StringBuilder();
        for (int line = 0; line < 8; line++) {
            draft.append(line > 0 ? "\n" : "").append("line ").append(line);
        }
        HarnessState shortViewport = HarnessState
                .initial(new Size(4, 40), "~/Development/braxton", List.of())
                .withDraft(EditorState.of(draft.toString()));
        List<String> rendered = lines(shortViewport);
        assertThat(rendered).hasSize(4);
        assertThat(rendered).anySatisfy(line ->
                assertThat(line).contains(io.github.dhoard.braxton.widget.Editor.CURSOR));
    }

    @Test
    void allocationKeepsTheDocumentedChrome() {
        HarnessProjection.Allocation one = HarnessProjection.allocate(40, 1);
        assertThat(one.transcript()).isEqualTo(33);
        assertThat(one.composer()).isEqualTo(1);
        assertThat(one.footer()).isEqualTo(3);
        assertThat(one.gap() + one.topRule() + one.bottomRule()).isEqualTo(3);

        assertThat(HarnessProjection.allocate(40, 2).transcript()).isEqualTo(32);
        assertThat(HarnessProjection.allocate(10, 1).transcript()).isEqualTo(3);
        HarnessProjection.Allocation tiny = HarnessProjection.allocate(4, 1);
        assertThat(tiny.transcript()).isZero();
        assertThat(tiny.composer()).isEqualTo(1);
        assertThat(tiny.footer()).isEqualTo(3);
        assertThat(HarnessProjection.allocate(0, 1).transcript()).isZero();
    }

    @Test
    void screenRendersHeaderTranscriptComposerAndFooter() {
        List<String> lines = lines(state("hello"));
        assertThat(lines).hasSize(40);
        assertThat(lines).allSatisfy(line ->
                assertThat(io.github.dhoard.braxton.style.TextMeasure.columns(line)).isLessThanOrEqualTo(120));
        String plain = String.join("\n", lines);
        assertThat(plain).contains("AI Harness");
        assertThat(plain).contains("mock transcript");
        assertThat(plain).contains("u there?");
        assertThat(plain).contains("The user is checking");
        assertThat(plain).contains("read README.md");
        assertThat(plain).contains("line one");
        assertThat(plain).contains("Yes \u2014 here.");
        assertThat(plain).contains("> hello");
        assertThat(plain).contains("amanda | qwen3.8-flash-next \u2022 high");
        assertThat(plain).contains("\u2191 7.1k");
        assertThat(plain).contains("\u2193 146");
        assertThat(plain).contains("R 2.1M");
        assertThat(plain).contains("CH 0.0%");
    }

    @Test
    void copyVisibleOmitsTheScrollbarColumn() {
        HarnessState overflowing = state("hello").withViewport(new Size(12, 40));
        assertThat(String.join("\n", lines(overflowing))).contains("\u2502");

        String copied = HarnessProjection.visiblePlainText(overflowing);
        assertThat(copied).doesNotContain("\u2502").doesNotContain("\u2503");
        assertThat(copied).contains("line one").contains("Yes \u2014 here.");
    }

    @Test
    void copyVisibleTrimsTheTrailingPaddingOfEachLine() {
        HarnessState overflowing = state("hello").withViewport(new Size(12, 40));
        String copied = HarnessProjection.visiblePlainText(overflowing);
        assertThat(copied.lines()).allSatisfy(line -> assertThat(line).doesNotEndWith(" "));
        assertThat(copied).contains("Yes \u2014 here.");
    }

    @Test
    void paletteIsCarriedByCellStylesRatherThanInlineAnsi() {
        Screen screen = new ScreenRenderer().screen(HarnessView.view(state("")),
                new Viewport(40, 120));
        boolean userBackground = false;
        boolean noticeColor = false;
        for (int row = 0; row < screen.rows(); row++) {
            for (int col = 0; col < screen.cols(); col++) {
                var cell = screen.cell(row, col);
                if (cell.style().background() != null
                        && cell.style().background().hex().equals("#343541")) {
                    userBackground = true;
                }
                if (cell.style().foreground() != null
                        && cell.style().foreground().hex().equals("#de935f")) {
                    noticeColor = true;
                }
                assertThat(cell.text()).doesNotContain("\u001B");
            }
        }
        assertThat(userBackground).isTrue();
        assertThat(noticeColor).isTrue();
    }

    @Test
    void composerRowAndCaretCarryTheInputAreaBackground() {
        HarnessState state = state("hi");
        Screen screen = new ScreenRenderer().screen(HarnessView.view(state),
                new Viewport(40, 120));
        int composerRow = HarnessProjection.allocate(40, state.editorRows()).transcript()
                + HarnessProjection.allocate(40, state.editorRows()).gap()
                + HarnessProjection.allocate(40, state.editorRows()).topRule();
        String row = screen.plainLine(composerRow);
        assertThat(row).contains("> hi").contains(io.github.dhoard.braxton.widget.Editor.CURSOR);
        for (int col = 0; col < screen.cols(); col++) {
            assertThat(screen.cell(composerRow, col).style().background())
                    .as("composer cell %d background in %s", col, row)
                    .isEqualTo(HarnessTheme.BACKGROUND_COLOR);
        }
    }

    @Test
    void userMessagesKeepTheirPaddingRows() {
        List<String> lines = lines(state(""));
        int message = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("u there?")) {
                message = i;
                break;
            }
        }
        assertThat(message).isPositive();
        assertThat(lines.get(message - 1).strip()).isEmpty();
        assertThat(lines.get(message + 1).strip()).isEmpty();
    }

    @Test
    void tinyViewportsStillRenderExactlyTheirHeight() {
        HarnessState small = state("").withViewport(new Size(8, 40));
        assertThat(HarnessView.renderLines(small)).hasSize(8);
        HarnessState tiny = state("").withViewport(new Size(3, 20));
        List<String> lines = HarnessView.renderLines(tiny);
        assertThat(lines).hasSize(3);
        assertThat(lines).allSatisfy(line ->
                assertThat(io.github.dhoard.braxton.style.TextMeasure.columns(line)).isLessThanOrEqualTo(20));
    }

    @Test
    void multilineComposerShrinksTheTranscriptAndKeepsTheFooter() {
        HarnessState multi = state("line1\nline2").withViewport(new Size(40, 120));
        List<String> lines = HarnessView.renderLines(multi);
        assertThat(lines).hasSize(40);
        String plain = String.join("\n", lines);
        assertThat(plain).contains("> line1");
        assertThat(plain).contains("line2");
        assertThat(plain).contains("amanda | qwen3.8-flash-next");
    }

    @Test
    void overflowAddsAScrollbarAndScrollBackChangesTheWindow() {
        List<HarnessEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(new HarnessEntry.User("user-" + i));
        }
        HarnessState base = new HarnessState(HarnessState.Status.READY, entries, PROFILE,
                HarnessTranscript.Usage.EMPTY, 1_048_576, List.of(), 0, EditorState.empty(), 0,
                List.of(), null, 0L, 0L, 0L, 0L, 0L, new Size(24, 80), "", "~/braxton");
        String bottom = String.join("\n", HarnessView.renderLines(base));
        assertThat(bottom).contains("user-49");
        assertThat(bottom).containsAnyOf("\u2502", "\u2503");

        HarnessState scrolled = new HarnessState(base.status(), base.entries(), base.profile(),
                base.usage(), base.contextWindow(), base.turns(), base.replayIndex(), base.draft(),
                200, base.pending(), base.active(), base.generation(), base.nowNanos(),
                base.workStartedNanos(), base.escArmedUntilNanos(), base.quitArmedUntilNanos(),
                base.viewport(),
                base.statusMessage(), base.projectPath());
        String earlier = String.join("\n", HarnessView.renderLines(scrolled));
        assertThat(earlier).doesNotContain("user-49");
        assertThat(earlier).contains("user-1");
    }

    @Test
    void maxScrollMatchesTheWindowedContent() {
        List<HarnessEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(new HarnessEntry.User("user-" + i));
        }
        HarnessState base = new HarnessState(HarnessState.Status.READY, entries, PROFILE,
                HarnessTranscript.Usage.EMPTY, 1_048_576, List.of(), 0, EditorState.empty(), 0,
                List.of(), null, 0L, 0L, 0L, 0L, 0L, new Size(24, 80), "", "~/braxton");
        assertThat(HarnessProjection.maxScroll(base)).isPositive();
        assertThat(HarnessProjection.maxScroll(base.withScroll(0))).isEqualTo(
                HarnessProjection.maxScroll(base));
    }

    @Test
    void workingRuleShowsSpinnerElapsedQueueAndHint() {
        HarnessState working = new HarnessState(HarnessState.Status.READY, List.of(), PROFILE,
                HarnessTranscript.Usage.EMPTY, 1_048_576, List.of(), 0, EditorState.empty(), 0,
                List.of("queued"), new HarnessState.Active(
                        new HarnessTranscript.HarnessTurn("", List.of(),
                                HarnessTranscript.Usage.EMPTY, PROFILE), 0),
                1L, 2_500_000_000L, 0L, 3_000_000_000L, 0L, new Size(24, 80), "", "~/braxton");
        String rule = HarnessProjection.topRule(working).plainText();
        assertThat(rule).contains("Working").contains("2s").contains("1 queued")
                .contains("esc again to stop").startsWith("\u2500 ");
    }

    @Test
    void footerUsesWarningAndErrorThresholds() {
        HarnessTranscript.Usage warningUsage =
                new HarnessTranscript.Usage(100, 50, 0, 100, 800_000, null);
        HarnessState warning = new HarnessState(HarnessState.Status.READY, List.of(), PROFILE,
                warningUsage, 1_048_576, List.of(), 0, EditorState.empty(), 0, List.of(), null, 0L,
                0L, 0L, 0L, 0L, new Size(24, 80), "", "~/braxton");
        Screen screen = new ScreenRenderer().screen(HarnessView.view(warning),
                new Viewport(24, 80));
        boolean yellow = false;
        boolean red = false;
        HarnessState error = new HarnessState(warning.status(), warning.entries(), PROFILE,
                new HarnessTranscript.Usage(100, 50, 0, 100, 1_000_000, null), 1_048_576,
                List.of(), 0, EditorState.empty(), 0, List.of(), null, 0L, 0L, 0L, 0L, 0L,
                new Size(24, 80), "", "~/braxton");
        Screen errorScreen = new ScreenRenderer().screen(HarnessView.view(error),
                new Viewport(24, 80));
        for (int row = 0; row < screen.rows(); row++) {
            for (int col = 0; col < screen.cols(); col++) {
                var color = screen.cell(row, col).style().foreground();
                yellow |= color != null && color.hex().equals("#ffff00");
                var errorColor = errorScreen.cell(row, col).style().foreground();
                red |= errorColor != null && errorColor.hex().equals("#cc6666");
            }
        }
        assertThat(yellow).isTrue();
        assertThat(red).isTrue();
    }

    @Test
    void idleRuleShowsTheCtrlCHintWhileArmed() {
        HarnessState armed = state("").withNow(0L).withQuitArmed(5_000_000_000L);
        assertThat(HarnessProjection.topRule(armed).plainText())
                .contains("ctrl+c again to exit");
    }

    @Test
    void ctrlCIsInterceptedWithItsCaptureTimeAndIsNotABinding() {
        View<HarnessMsg> view = HarnessView.view(state(""));
        Optional<HarnessMsg> routed = InputRouter.route(view,
                InputEvent.KeyInput.of(Key.of("c", Modifiers.CTRL), "", 42L));
        assertThat(routed).contains(new HarnessMsg.QuitRequest(42L));
        assertThat(HarnessView.bindings())
                .noneMatch(binding -> binding.key().equals(Key.of("c", Modifiers.CTRL)));
    }

    @Test
    void layoutIsDeterministic() {
        HarnessState state = state("hello");
        assertThat(lines(state)).isEqualTo(lines(state));
        LayoutEngine engine = new LayoutEngine();
        assertThat(engine.layout(HarnessView.view(state), new io.github.dhoard.braxton.layout.Rect(0, 0, 40, 120)))
                .isEqualTo(engine.layout(HarnessView.view(state),
                        new io.github.dhoard.braxton.layout.Rect(0, 0, 40, 120)));
    }
}

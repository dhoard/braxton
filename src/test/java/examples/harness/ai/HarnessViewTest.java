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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.RenderedScreen;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.widget.Editor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Harness frame, transcript rows, and palette, measured through the real production view. */
class HarnessViewTest {

    private static final HarnessTranscript.Profile PROFILE =
            new HarnessTranscript.Profile("local", "qwen3.8-flash-next", "high");

    private static Harness.Model state(String draft) {
        List<HarnessEntry> entries = List.of(
                new HarnessEntry.Notice("Bundled example transcript. Type a prompt."),
                new HarnessEntry.User("u there?"),
                new HarnessEntry.Reasoning("The user is checking whether the harness is present."),
                new HarnessEntry.Tool("$ read README.md", "line one\nline two", false,
                        HarnessEntry.ToolStatus.SUCCESS),
                new HarnessEntry.Assistant("Yes \u2014 here. I'm ready."));
        return model(entries, draft, new Size(40, 120), List.of());
    }

    private static Harness.Model model(
            List<HarnessEntry> entries, String draft, Size viewport, List<String> pending) {
        HarnessTranscript.Usage usage = new HarnessTranscript.Usage(7091, 146, 0, 7091, 7237, 42.0);
        return new Harness.Model(
                new Session.Model(
                        Session.Status.READY,
                        entries,
                        PROFILE,
                        usage,
                        1_048_576,
                        List.of(),
                        0,
                        0),
                Editor.Model.of(draft),
                pending,
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                viewport,
                "~/Development/braxton");
    }

    /** The state with a running turn and the supplied prompts queued behind it. */
    private static Harness.Model working(Harness.Model base, List<String> pending) {
        return new Harness.Model(
                base.session(),
                base.draft(),
                pending,
                new Harness.Model.Active(
                        new HarnessTranscript.HarnessTurn(
                                "", List.of(), HarnessTranscript.Usage.EMPTY, PROFILE),
                        0),
                1L,
                0L,
                0L,
                0L,
                0L,
                base.viewport(),
                base.projectPath());
    }

    private static List<String> lines(Harness.Model state) {
        return HarnessView.renderLines(state);
    }

    /** The index of the row whose padded text starts with {@code prefix}. */
    private static int rowStartingWith(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void aDraftTallerThanTheViewportStillShowsTheCaret() {
        StringBuilder draft = new StringBuilder();
        for (int line = 0; line < 8; line++) {
            draft.append(line > 0 ? "\n" : "").append("line ").append(line);
        }
        Harness.Model shortViewport =
                Harness.Model.initial(new Size(4, 40), "~/Development/braxton", List.of())
                        .withDraft(Editor.Model.of(draft.toString()));
        List<String> rendered = lines(shortViewport);
        assertThat(rendered).hasSize(4);
        assertThat(rendered).anySatisfy(line -> assertThat(line).contains(Editor.CURSOR));
    }

    @Test
    void theFullLayoutKeepsTheReferenceChrome() {
        HarnessProjection.Layout oneLine = HarnessProjection.layout(state("hello"));
        assertThat(oneLine.header()).isEqualTo(HarnessProjection.HEADER_HEIGHT);
        assertThat(oneLine.gap()).isEqualTo(HarnessProjection.INPUT_GAP_ROWS);
        assertThat(oneLine.statusRule()).isEqualTo(1);
        assertThat(oneLine.bottomRule()).isEqualTo(1);
        assertThat(oneLine.footer()).isEqualTo(HarnessProjection.FOOTER_HEIGHT);
        assertThat(oneLine.stats()).isZero();
        assertThat(oneLine.editor()).isEqualTo(1);
        assertThat(oneLine.body()).isEqualTo(30);
        assertThat(oneLine.rows()).isEqualTo(40);

        // The transcript yields to the composer and the footer, never the other way round.
        assertThat(HarnessProjection.layout(state("a\nb\nc")).body()).isEqualTo(28);
        assertThat(HarnessProjection.HEADER_HEIGHT + HarnessProjection.INPUT_GAP_ROWS
                + 2 + HarnessProjection.FOOTER_HEIGHT + 1)
                .isEqualTo(HarnessProjection.MIN_FULLSCREEN_ROWS);
    }

    @Test
    void theCompactLayoutKeepsTheIdentityTheComposerAndTheMetrics() {
        Harness.Model eight = state("draft").withViewport(new Size(8, 40));
        HarnessProjection.Layout compact = HarnessProjection.layout(eight);
        assertThat(compact.header()).isEqualTo(2);
        assertThat(compact.body()).isZero();
        assertThat(compact.statusRule()).isZero();
        assertThat(compact.editor()).isEqualTo(1);
        assertThat(compact.editorPad()).as("the draft sits against the metrics row").isEqualTo(4);
        assertThat(compact.stats()).isEqualTo(1);
        assertThat(compact.rows()).isEqualTo(8);

        HarnessProjection.Layout three = HarnessProjection.layout(state("draft").withViewport(new Size(3, 20)));
        assertThat(three.header()).isEqualTo(2);
        assertThat(three.editor()).isEqualTo(1);
        assertThat(three.stats()).isZero();
        assertThat(three.rows()).isEqualTo(3);

        HarnessProjection.Layout one = HarnessProjection.layout(state("draft").withViewport(new Size(1, 20)));
        assertThat(one.header()).isEqualTo(1);
        assertThat(one.rows()).isEqualTo(1);
    }

    @Test
    void screenRendersTheIdentityTranscriptComposerAndFooter() {
        List<String> lines = lines(state("hello"));
        assertThat(lines).hasSize(40);
        assertThat(lines).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(120));
        String plain = String.join("\n", lines);
        assertThat(lines.get(0)).startsWith("AI Harness v" + HarnessTranscript.EXAMPLE_VERSION);
        assertThat(lines.get(0)).endsWith(HarnessTranscript.EXAMPLE_SESSION_ID);
        assertThat(lines.get(1)).isEqualTo("\u2500".repeat(120));
        assertThat(plain).contains("SYSTEM").contains("USER").contains("THINKING")
                .contains("TOOL").contains("ASSISTANT");
        assertThat(plain).contains("u there?");
        assertThat(plain).contains("The user is checking");
        assertThat(plain).contains("$ read README.md");
        assertThat(plain).contains("line one");
        assertThat(plain).contains("Yes \u2014 here.");
        assertThat(plain).contains("hello");
        assertThat(plain).contains("~/Development/braxton");
        assertThat(plain).contains(HarnessProjection.TRANSCRIPT_SOURCE);
        int source = rowStartingWith(lines, HarnessProjection.TRANSCRIPT_SOURCE);
        assertThat(source).isPositive();
        assertThat(HarnessProjection.TRANSCRIPT_SOURCE).startsWith("/");
        assertThat(plain).contains("local | qwen3.8-flash-next \u2022 high");
        assertThat(plain).contains("\u2191 7.1k");
        assertThat(plain).contains("\u2193 146");
        assertThat(plain).contains("R -");
        assertThat(plain).contains("CH 0.0%");
        assertThat(plain).doesNotContain("> hello").doesNotContain("mock transcript");
    }

    @Test
    void transcriptHeadersCarryTheTimeTheMessageEnteredTheSession() {
        long time = java.time.Instant.parse("2026-10-17T12:14:03Z").toEpochMilli();
        String expected = HarnessMessage.timestamp(time);
        Harness.Model state = model(
                List.of(new HarnessEntry.User("hello", time), new HarnessEntry.Assistant("hi there")),
                "",
                new Size(24, 80),
                List.of());
        List<String> lines = lines(state);
        int user = rowStartingWith(lines, "USER " + expected);
        assertThat(user).isPositive();
        assertThat(lines.get(user)).startsWith("USER " + expected);

        RenderedScreen screen =
                new ScreenRenderer().screen(HarnessView.view(state), new Viewport(24, 80));
        assertThat(screen.cell(user, 0).style().foreground())
                .isEqualTo(HarnessTheme.USER_GUTTER_COLOR);
        assertThat(screen.cell(user, "USER ".length()).style().foreground())
                .isEqualTo(HarnessTheme.DIM_COLOR);

        int assistant = rowStartingWith(lines, "ASSISTANT");
        assertThat(lines.get(assistant).stripTrailing())
                .as("a message with no recorded time shows its label alone")
                .isEqualTo("ASSISTANT");

        // A header wider than the frame is cut at its right edge, never wrapped.
        List<String> narrow = lines(model(
                List.of(new HarnessEntry.User("hello", time)), "", new Size(24, 8), List.of()));
        int narrowHeader = rowStartingWith(narrow, "USER");
        assertThat(narrowHeader).isPositive();
        assertThat(narrow.get(narrowHeader)).startsWith("USER");
        assertThat(TextMeasure.columns(narrow.get(narrowHeader))).isLessThanOrEqualTo(8);
    }

    @Test
    void eachMessageIsAHeaderABlankRowAndItsBody() {
        List<String> lines = lines(state(""));
        int user = rowStartingWith(lines, "USER");
        assertThat(user).isPositive();
        assertThat(lines.get(user).stripTrailing()).isEqualTo("USER");
        assertThat(lines.get(user + 1).strip()).isEmpty();
        assertThat(lines.get(user + 2).stripTrailing()).isEqualTo("u there?");

        int assistant = rowStartingWith(lines, "ASSISTANT");
        assertThat(assistant).isPositive();
        assertThat(lines.get(assistant + 2).stripTrailing()).isEqualTo("Yes \u2014 here. I'm ready.");

        int system = rowStartingWith(lines, "SYSTEM");
        assertThat(system).isPositive();
        assertThat(lines.get(system + 2).stripTrailing())
                .isEqualTo("Bundled example transcript. Type a prompt.");
    }

    @Test
    void aMessageBodyWrapsToTheFrameWidthAtAnySize() {
        Harness.Model narrow = model(
                List.of(new HarnessEntry.User("u there, long enough to wrap several times?")),
                "",
                new Size(14, 8),
                List.of());
        List<String> lines = lines(narrow);
        assertThat(lines).hasSize(14).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(8));
        String plain = String.join("\n", lines);
        assertThat(plain).contains("enough").contains("wrap").contains("times?");

        Harness.Model selector = model(
                List.of(new HarnessEntry.User("a \uFE0FCD tail")), "", new Size(14, 6), List.of());
        List<String> marked = lines(selector);
        assertThat(String.join("\n", marked)).contains("CD");
        assertThat(marked.stream().filter(line -> line.contains("\u2026")).count())
                .as("a lost selector must not cost the body an ellipsis")
                .isEqualTo(lines(model(
                                List.of(new HarnessEntry.User("a CD tail")), "", new Size(14, 6), List.of()))
                        .stream().filter(line -> line.contains("\u2026")).count());
    }

    @Test
    void theToolMessageNamesWhatThePreviewHides() {
        Harness.Model tools = model(
                List.of(
                        new HarnessEntry.Tool(
                                "$ seq 1 10", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10", true,
                                HarnessEntry.ToolStatus.SUCCESS)),
                "",
                new Size(40, 120),
                List.of());
        String plain = String.join("\n", lines(tools));
        assertThat(plain).contains("$ seq 1 10");
        assertThat(plain).contains("6").contains("(4 more lines)").contains("(truncated)");
        assertThat(plain).doesNotContain("(4 more lines, 10 total)");
    }

    @Test
    void aFailedToolKeepsTheToolLabelAndShowsItsOutputInTheErrorColour() {
        Harness.Model failed = model(
                List.of(new HarnessEntry.Tool(
                        "$ false", "boom", false, HarnessEntry.ToolStatus.ERROR)),
                "",
                new Size(24, 80),
                List.of());
        RenderedScreen screen =
                new ScreenRenderer().screen(HarnessView.view(failed), new Viewport(24, 80));
        List<String> lines = lines(failed);
        int header = rowStartingWith(lines, "TOOL");
        assertThat(header).isPositive();
        assertThat(screen.cell(header, 0).style().foreground())
                .isEqualTo(HarnessTheme.ERROR_COLOR);
        int body = rowStartingWith(lines, "boom");
        assertThat(body).isPositive();
        assertThat(screen.cell(body, 0).style().foreground())
                .isEqualTo(HarnessTheme.ERROR_COLOR);
    }

    @Test
    void aMultiLineDraftStartsAtTheFirstColumnAndKeepsTheCaret() {
        Harness.Model multiLine = state("line1\nline2");
        List<String> lines = lines(multiLine);
        HarnessProjection.Layout layout = HarnessProjection.layout(multiLine);
        int composerRow = layout.header() + layout.body() + layout.queued() + layout.gap()
                + layout.statusRule() + layout.editorPad();
        assertThat(lines.get(composerRow)).startsWith("line1");
        assertThat(lines.get(composerRow + 1))
                .isEqualTo("line2" + Editor.CURSOR);
        assertThat(String.join("\n", lines)).doesNotContain("> ");
    }

    @Test
    void theStatusRuleCarriesTheWorkingLabel() {
        Harness.Model working = new Harness.Model(
                state("").session(),
                Editor.Model.empty(),
                List.of(),
                new Harness.Model.Active(
                        new HarnessTranscript.HarnessTurn(
                                "", List.of(), HarnessTranscript.Usage.EMPTY, PROFILE),
                        0),
                1L,
                2_500_000_000L,
                0L,
                0L,
                0L,
                new Size(24, 80),
                "~/braxton");
        String rule = HarnessProjection.statusRule(working).plainText();
        assertThat(rule).contains("Working").contains("2s").contains("- esc esc to stop")
                .startsWith("\u2500 ");
        assertThat(HarnessProjection.statusRule(state(""))).isEqualTo(
                HarnessProjection.rule(120));
    }

    @Test
    void theMetricsRowCarriesTheCtrlCHintWhileArmed() {
        Harness.Model armed = state("").withNow(0L).withQuitArmed(5_000_000_000L);
        String stats = HarnessProjection.statsRow(armed, 120).plainText();
        assertThat(stats).contains("ctrl+c again to exit");
        assertThat(stats).endsWith("ctrl+c again to exit");
        assertThat(TextMeasure.columns(stats)).isEqualTo(120);
        assertThat(HarnessProjection.footer(armed, 120).getLast().plainText())
                .endsWith("ctrl+c again to exit");

        RenderedScreen screen =
                new ScreenRenderer().screen(HarnessView.view(armed), new Viewport(40, 120));
        String last = screen.plainLine(39);
        assertThat(last).endsWith("ctrl+c again to exit");
    }

    @Test
    void queuedPromptsBehindTheMirroredOneRenderAboveTheInput() {
        Harness.Model queued = working(
                model(List.of(new HarnessEntry.Notice("notice")), "", new Size(40, 80), List.of()),
                List.of("first queued prompt", "second queued prompt"));
        HarnessProjection.Layout layout = HarnessProjection.layout(queued);
        assertThat(layout.queued()).isPositive();
        List<String> lines = lines(queued);
        int second = rowStartingWith(lines, "second queued prompt");
        int rule = layout.header() + layout.body() + layout.queued();
        assertThat(second).isPositive().isLessThan(rule);
        assertThat(lines.get(composerRowIndex(layout))).startsWith("first queued prompt");
        assertThat(String.join("\n", lines)).doesNotContain("2 queued");

        List<String> many = new ArrayList<>();
        for (int i = 0; i < Harness.MAX_OUTSTANDING; i++) {
            many.add("queued prompt " + i);
        }
        Harness.Model crowded = working(
                model(List.of(new HarnessEntry.Notice("notice")), "", new Size(16, 40), List.of()),
                many);
        List<String> crowdedLines = lines(crowded);
        assertThat(crowdedLines).hasSize(16);
        assertThat(String.join("\n", crowdedLines)).contains("more queued]");
    }

    private static int composerRowIndex(HarnessProjection.Layout layout) {
        return layout.header() + layout.body() + layout.queued() + layout.gap()
                + layout.statusRule() + layout.editorPad();
    }

    @Test
    void aQueuedPromptMirrorsIntoTheComposer() {
        Harness.Model queued = working(
                model(List.of(new HarnessEntry.Notice("notice")), "unused draft",
                        new Size(24, 80), List.of()),
                List.of("queued prompt"));
        HarnessProjection.Layout layout = HarnessProjection.layout(queued);
        List<String> lines = lines(queued);
        String composer = lines.get(composerRowIndex(layout));
        assertThat(composer).startsWith("queued prompt").doesNotContain(Editor.CURSOR);
        assertThat(String.join("\n", lines)).doesNotContain("unused draft");
        assertThat(String.join("\n", lines).split("queued prompt", -1)).hasSize(2);

        RenderedScreen screen =
                new ScreenRenderer().screen(HarnessView.view(queued), new Viewport(24, 80));
        assertThat(screen.cell(composerRowIndex(layout), 0).style().foreground())
                .isEqualTo(HarnessTheme.QUEUED_COLOR);

        // A queued prompt belongs to the queue, not to a draft, so edits are refused.
        Harness.Model edited = new Harness(
                        () -> HarnessTranscript.empty(List.of()), "~/braxton", List.of())
                .update(queued, new Harness.Msg.ComposerMsg(new Editor.Msg.Insert("x")))
                .model();
        assertThat(edited.draft()).isEqualTo(queued.draft());
        assertThat(edited.pending()).containsExactly("queued prompt");
    }

    @Test
    void messageHeadersPaintTheirTextOnTheHeaderBand() {
        RenderedScreen screen = new ScreenRenderer().screen(HarnessView.view(state("")),
                new Viewport(40, 120));
        List<String> lines = lines(state(""));
        for (String prefix : List.of("SYSTEM", "USER", "THINKING", "TOOL", "ASSISTANT")) {
            int header = rowStartingWith(lines, prefix);
            assertThat(header).as("%s header row", prefix).isPositive();
            for (int col : new int[] {0, prefix.length() - 1, 119}) {
                assertThat(screen.cell(header, col).style().background())
                        .as("%s header cell %d background", prefix, col)
                        .isEqualTo(HarnessTheme.TRANSCRIPT_HEADER_BG_COLOR);
            }
        }
    }

    @Test
    void theIdentityLineCarriesTheExampleVersion() {
        RenderedScreen screen = new ScreenRenderer().screen(HarnessView.view(state("")),
                new Viewport(40, 120));
        String line = screen.plainLine(0);
        assertThat(line).startsWith("AI Harness v" + HarnessTranscript.EXAMPLE_VERSION);
        assertThat(line).endsWith(HarnessTranscript.EXAMPLE_SESSION_ID);
        assertThat(screen.cell(0, 0).style().foreground())
                .isEqualTo(HarnessTheme.ACCENT_COLOR);
        assertThat(screen.cell(0, 0).style().bold()).isTrue();
        int version = "AI Harness v".length();
        assertThat(screen.cell(0, version).style().foreground())
                .isEqualTo(HarnessTheme.DIM_COLOR);
        assertThat(screen.cell(0, version).style().bold()).isFalse();

        // Narrow frames keep the identity and drop the session field whole.
        List<String> narrow = lines(state("").withViewport(new Size(24, 40)));
        assertThat(narrow.get(0)).isEqualTo("AI Harness v" + HarnessTranscript.EXAMPLE_VERSION);
    }

    @Test
    void paletteIsCarriedByCellStylesRatherThanInlineAnsi() {
        RenderedScreen screen = new ScreenRenderer().screen(HarnessView.view(state("")),
                new Viewport(40, 120));
        List<String> lines = lines(state(""));
        int user = rowStartingWith(lines, "USER");
        assertThat(screen.cell(user, 0).style().foreground())
                .isEqualTo(HarnessTheme.USER_GUTTER_COLOR);
        assertThat(screen.cell(user, 0).style().bold()).isTrue();
        assertThat(screen.cell(user, 119).style().background())
                .isEqualTo(HarnessTheme.TRANSCRIPT_HEADER_BG_COLOR);
        int system = rowStartingWith(lines, "SYSTEM");
        assertThat(screen.cell(system, 0).style().foreground())
                .isEqualTo(HarnessTheme.SYSTEM_GUTTER_COLOR);
        int assistant = rowStartingWith(lines, "ASSISTANT");
        assertThat(screen.cell(assistant, 0).style().foreground())
                .isEqualTo(HarnessTheme.ASSISTANT_GUTTER_COLOR);
        for (int row = 0; row < screen.rows(); row++) {
            for (int col = 0; col < screen.cols(); col++) {
                assertThat(screen.cell(row, col).text()).doesNotContain("\u001B");
            }
        }
    }

    @Test
    void reasoningBodyIsItalicAndMessagesKeepTheTextColour() {
        RenderedScreen screen = new ScreenRenderer().screen(HarnessView.view(state("")),
                new Viewport(40, 120));
        List<String> lines = lines(state(""));
        int thinking = rowStartingWith(lines, "THINKING");
        assertThat(screen.cell(thinking, 0).style().foreground())
                .isEqualTo(HarnessTheme.THINKING_GUTTER_COLOR);
        int body = rowStartingWith(lines, "The user is checking");
        assertThat(body).isPositive();
        assertThat(screen.cell(body, 0).style().italic()).isTrue();
        assertThat(screen.cell(body, 0).style().foreground())
                .isEqualTo(HarnessTheme.THINKING_TEXT_COLOR);
    }

    @Test
    void composerRowAndCaretCarryTheInputAreaBackground() {
        Harness.Model state = state("hi");
        RenderedScreen screen = new ScreenRenderer().screen(HarnessView.view(state),
                new Viewport(40, 120));
        HarnessProjection.Layout layout = HarnessProjection.layout(state);
        int composerRow = layout.header() + layout.body() + layout.queued() + layout.gap()
                + layout.statusRule() + layout.editorPad();
        String row = screen.plainLine(composerRow);
        assertThat(row).startsWith("hi").contains(Editor.CURSOR);
        for (int col = 0; col < screen.cols(); col++) {
            assertThat(screen.cell(composerRow, col).style().background())
                    .as("composer cell %d background in %s", col, row)
                    .isEqualTo(HarnessTheme.BACKGROUND_COLOR);
        }
    }

    @Test
    void noMessageCarriesABackgroundPanel() {
        RenderedScreen screen = new ScreenRenderer().screen(HarnessView.view(state("")),
                new Viewport(40, 120));
        List<String> lines = lines(state(""));
        for (String prefix : List.of("USER", "TOOL", "ASSISTANT")) {
            int row = rowStartingWith(lines, prefix);
            int body = row + 2;
            assertThat(screen.cell(body, 0).style().background())
                    .as("%s body background", prefix)
                    .isEqualTo(HarnessTheme.BACKGROUND_COLOR);
        }
    }

    @Test
    void copyVisibleOmitsTheFrameChrome() {
        Harness.Model overflowing = state("hello").withViewport(new Size(12, 40));
        String copied = HarnessProjection.visiblePlainText(overflowing);
        assertThat(copied).doesNotContain("AI Harness").doesNotContain("\u2502")
                .doesNotContain("\u2503");
        assertThat(copied.lines()).allSatisfy(line -> assertThat(line).doesNotEndWith(" "));
        assertThat(copied).contains("Yes \u2014 here.");
    }

    @Test
    void tinyViewportsStillRenderExactlyTheirHeight() {
        Harness.Model small = state("").withViewport(new Size(8, 40));
        assertThat(HarnessView.renderLines(small)).hasSize(8);
        Harness.Model tiny = state("").withViewport(new Size(3, 20));
        List<String> lines = HarnessView.renderLines(tiny);
        assertThat(lines).hasSize(3);
        assertThat(lines).allSatisfy(line ->
                assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(20));
    }

    @Test
    void multilineComposerShrinksTheTranscriptAndKeepsTheFooter() {
        Harness.Model multi = state("line1\nline2").withViewport(new Size(40, 120));
        List<String> lines = HarnessView.renderLines(multi);
        assertThat(lines).hasSize(40);
        String plain = String.join("\n", lines);
        assertThat(plain).contains("line1").contains("line2");
        assertThat(plain).contains("local | qwen3.8-flash-next");
    }

    @Test
    void overflowScrollsBackWithoutScrollChrome() {
        List<HarnessEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(new HarnessEntry.User("user-" + i));
        }
        Harness.Model base = model(entries, "", new Size(24, 80), List.of());
        String bottom = String.join("\n", HarnessView.renderLines(base));
        assertThat(bottom).contains("user-49").doesNotContain("\u2502").doesNotContain("\u2503");

        String earlier = String.join("\n", HarnessView.renderLines(base.withScroll(200)));
        assertThat(earlier).doesNotContain("user-49");
        assertThat(earlier).contains("user-1");
    }

    @Test
    void maxScrollMatchesTheWindowedContent() {
        List<HarnessEntry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(new HarnessEntry.User("user-" + i));
        }
        Harness.Model base = model(entries, "", new Size(24, 80), List.of());
        assertThat(HarnessProjection.maxScroll(base)).isPositive();
        assertThat(HarnessProjection.maxScroll(base.withScroll(0)))
                .isEqualTo(HarnessProjection.maxScroll(base));
        assertThat(HarnessProjection.window(
                        base.session(), 80, HarnessProjection.layout(base).body()))
                .hasSize(HarnessProjection.layout(base).body());
    }

    @Test
    void footerUsesWarningAndErrorThresholds() {
        Harness.Model warning = new Harness.Model(
                new Session.Model(
                        Session.Status.READY,
                        List.of(),
                        PROFILE,
                        new HarnessTranscript.Usage(100, 50, 0, 100, 800_000, null),
                        1_048_576,
                        List.of(),
                        0,
                        0),
                Editor.Model.empty(),
                List.of(),
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                new Size(24, 80),
                "~/braxton");
        Harness.Model error = new Harness.Model(
                new Session.Model(
                        Session.Status.READY,
                        List.of(),
                        PROFILE,
                        new HarnessTranscript.Usage(100, 50, 0, 100, 1_000_000, null),
                        1_048_576,
                        List.of(),
                        0,
                        0),
                Editor.Model.empty(),
                List.of(),
                null,
                0L,
                0L,
                0L,
                0L,
                0L,
                new Size(24, 80),
                "~/braxton");
        RenderedScreen screen =
                new ScreenRenderer().screen(HarnessView.view(warning), new Viewport(24, 80));
        RenderedScreen errorScreen =
                new ScreenRenderer().screen(HarnessView.view(error), new Viewport(24, 80));
        boolean yellow = false;
        boolean red = false;
        for (int row = 0; row < screen.rows(); row++) {
            for (int col = 0; col < screen.cols(); col++) {
                var color = screen.cell(row, col).style().foreground();
                yellow |= HarnessTheme.WARNING_COLOR.equals(color);
                var errorColor = errorScreen.cell(row, col).style().foreground();
                red |= HarnessTheme.ERROR_COLOR.equals(errorColor);
            }
        }
        assertThat(yellow).isTrue();
        assertThat(red).isTrue();
    }

    @Test
    void metricsLaneShowsRealTotalsAndPlaceholders() {
        Harness.Model state = state("").withMetadata(
                PROFILE, new HarnessTranscript.Usage(1_000, 20, 0, 900, 1_020, null));
        String stats = HarnessProjection.statsRow(state, 120).plainText();
        assertThat(stats).contains("\u2191 900").contains("\u2193 20").contains("R -")
                .contains("CH 0.0%").contains("1.0M (0.1%)").contains("- t/s");
        assertThat(HarnessProjection.statsRow(state, 120).plainText())
                .doesNotContain("2.1M");
    }

    @Test
    void thePaletteCarriesTheReferenceValues() {
        assertThat(HarnessTheme.ACCENT_COLOR.hex()).isEqualTo("#8abeb7");
        assertThat(HarnessTheme.ERROR_COLOR.hex()).isEqualTo("#cc6666");
        assertThat(HarnessTheme.WARNING_COLOR.hex()).isEqualTo("#ffff00");
        assertThat(HarnessTheme.NOTICE_COLOR.hex()).isEqualTo("#de935f");
        assertThat(HarnessTheme.QUEUED_COLOR.hex()).isEqualTo("#d183e8");
        assertThat(HarnessTheme.DIM_COLOR.hex()).isEqualTo("#666666");
        assertThat(HarnessTheme.TEXT_COLOR.hex()).isEqualTo("#d4d4d4");
        assertThat(HarnessTheme.BACKGROUND_COLOR.hex()).isEqualTo("#000000");
        assertThat(HarnessTheme.TRANSCRIPT_HEADER_BG_COLOR.hex()).isEqualTo("#1c1c1c");
        assertThat(HarnessTheme.THINKING_TEXT_COLOR.hex()).isEqualTo("#808080");
        assertThat(HarnessTheme.USER_GUTTER_COLOR.hex()).isEqualTo("#60a5fa");
        assertThat(HarnessTheme.THINKING_GUTTER_COLOR.hex()).isEqualTo("#a3a3a3");
        assertThat(HarnessTheme.ASSISTANT_GUTTER_COLOR.hex()).isEqualTo("#ffffff");
        assertThat(HarnessTheme.TOOL_GUTTER_COLOR.hex()).isEqualTo("#c4b5fd");
        assertThat(HarnessTheme.SYSTEM_GUTTER_COLOR.hex()).isEqualTo("#94a3b8");
        assertThat(HarnessTheme.ERROR_GUTTER_COLOR).isEqualTo(HarnessTheme.ERROR_COLOR);
    }

    @Test
    void ctrlCIsInterceptedWithItsCaptureTimeAndIsNotABinding() {
        View<Harness.Msg> view = HarnessView.view(state(""));
        Optional<Harness.Msg> routed =
                InputRouter.route(
                        view, InputEvent.KeyInput.of(Key.of("c", Modifiers.CTRL), "", 42L));
        assertThat(routed).contains(new Harness.Msg.QuitRequest(42L));
        assertThat(HarnessView.bindings())
                .noneMatch(binding -> binding.key().equals(Key.of("c", Modifiers.CTRL)));
    }

    @Test
    void everyDocumentedKeyReachesItsDocumentedAction() {
        // The README's key table is the harness contract; route each documented gesture through
        // the production view so a changed binding cannot drift from the documentation.
        View<Harness.Msg> view = HarnessView.view(state(""));
        record Case(Key key, String text, Class<?> messageType) {
        }
        List<Case> cases =
                List.of(
                        new Case(Key.ENTER, "\r", Harness.Msg.ComposerMsg.class),
                        new Case(Key.of("j", Modifiers.CTRL), "", Harness.Msg.ComposerMsg.class),
                        new Case(Key.UP, "", Harness.Msg.ScrollUp.class),
                        new Case(Key.WHEEL_UP, "", Harness.Msg.ScrollUp.class),
                        new Case(Key.DOWN, "", Harness.Msg.ScrollDown.class),
                        new Case(Key.WHEEL_DOWN, "", Harness.Msg.ScrollDown.class),
                        new Case(Key.PAGE_UP, "", Harness.Msg.PageUp.class),
                        new Case(Key.PAGE_DOWN, "", Harness.Msg.PageDown.class),
                        new Case(Key.END, "", Harness.Msg.ScrollBottom.class),
                        new Case(Key.TAB, "", Harness.Msg.FocusComposer.class),
                        new Case(Key.ESCAPE, "", Harness.Msg.Escape.class),
                        new Case(Key.of("c", Modifiers.CTRL), "", Harness.Msg.QuitRequest.class),
                        new Case(Key.of("c", Modifiers.ALT), "", Harness.Msg.ComposerMsg.class),
                        new Case(Key.of("v", Modifiers.CTRL), "", Harness.Msg.ComposerMsg.class),
                        new Case(Key.of("t", Modifiers.ALT), "", Harness.Msg.CopyVisible.class),
                        new Case(Key.of("l", Modifiers.ALT), "", Harness.Msg.CopyLastReply.class));
        assertThat(cases)
                .allSatisfy(
                        entry -> {
                            Optional<Harness.Msg> routed =
                                    InputRouter.route(
                                            view,
                                            new InputEvent.KeyInput(entry.key(), entry.text(), 0L));
                            assertThat(routed)
                                    .as("%s is documented but produces nothing", entry.key())
                                    .isPresent();
                            assertThat(routed.get())
                                    .as("%s routes to the documented message", entry.key())
                                    .isInstanceOf(entry.messageType());
                        });
        // A printable key edits the composer, and the two editor gestures keep their own actions.
        assertThat(InputRouter.route(view, new InputEvent.KeyInput(Key.of("a"), "a", 0L)))
                .contains(
                        new Harness.Msg.ComposerMsg(
                                new io.github.dhoard.braxton.widget.Editor.Msg.Insert("a")));
        assertThat(InputRouter.route(view, InputEvent.KeyInput.of(Key.of("j", Modifiers.CTRL))))
                .contains(
                        new Harness.Msg.ComposerMsg(
                                new io.github.dhoard.braxton.widget.Editor.Msg.Newline()));
        assertThat(InputRouter.route(view, InputEvent.KeyInput.of(Key.of("c", Modifiers.ALT))))
                .contains(
                        new Harness.Msg.ComposerMsg(
                                new io.github.dhoard.braxton.widget.Editor.Msg.Copy()));
        assertThat(InputRouter.route(view, InputEvent.KeyInput.of(Key.of("v", Modifiers.CTRL))))
                .contains(
                        new Harness.Msg.ComposerMsg(
                                new io.github.dhoard.braxton.widget.Editor.Msg.PasteRequest()));
        assertThat(InputRouter.route(view, InputEvent.KeyInput.of(Key.of("t", Modifiers.ALT))))
                .as("Alt+T copies the visible transcript, not the draft")
                .contains(new Harness.Msg.CopyVisible());
    }

    @Test
    void layoutIsDeterministic() {
        Harness.Model state = state("hello");
        assertThat(lines(state)).isEqualTo(lines(state));
        LayoutEngine engine = new LayoutEngine();
        assertThat(engine.layout(HarnessView.view(state), new io.github.dhoard.braxton.layout.Rect(0, 0, 40, 120)))
                .isEqualTo(engine.layout(HarnessView.view(state),
                        new io.github.dhoard.braxton.layout.Rect(0, 0, 40, 120)));
    }
}

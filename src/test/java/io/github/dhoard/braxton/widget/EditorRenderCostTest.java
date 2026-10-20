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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;

/**
 * The composer repaints on every keystroke, so the cost of one {@link Editor#render} is the cost of
 * a frame. Rendering a line used to rebuild its accumulated text for every cluster it painted, which
 * made the cost grow with the square of the draft: a 4096-character draft cost 8.6 MB per frame while
 * segmenting that same line costs 51 KB. The bounds below pin the per-frame cost; the reference
 * comparison pins the exact text, styles, cursor and masking the fast path has to reproduce.
 */
class EditorRenderCostTest {

    private static final String ASCII_UNIT = "the quick brown fox jumps over the lazy dog 0123456789 ";
    private static final String CJK_UNIT = "漢字テストの文章";

    private static volatile Object sink;

    private static long allocated(Runnable action) {
        Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported() && bean.isThreadAllocatedMemoryEnabled());
        for (int i = 0; i < 5; i++) {
            action.run();
        }
        long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        action.run();
        return bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
    }

    private static String draft(String unit, int chars) {
        return unit.repeat(chars / unit.length() + 1).substring(0, chars);
    }

    @Test
    void renderingALongDraftDoesNotRebuildItsGrowingPrefixPerCluster() {
        EditorState state = EditorState.of(draft(ASCII_UNIT, 4096));
        long bytes = allocated(
                () -> sink = Editor.render(state, EditorOptions.defaults(), true));
        assertThat(bytes)
                .as("painting one long line must not copy the text it has already accumulated for "
                        + "every cluster it adds")
                .isLessThan(1_000_000L);
        assertThat(Editor.render(state, EditorOptions.defaults(), true).plainText())
                .as("the whole draft is still painted")
                .isEqualTo(draft(ASCII_UNIT, 4096) + Editor.CURSOR);
    }

    @Test
    void renderingGrowsNearLinearlyWithTheDraft() {
        long small = allocated(() -> sink = Editor.render(
                EditorState.of(draft(ASCII_UNIT, 1024)), EditorOptions.defaults(), true));
        long large = allocated(() -> sink = Editor.render(
                EditorState.of(draft(ASCII_UNIT, 4096)), EditorOptions.defaults(), true));
        assertThat(large)
                .as("four times the draft must not cost far more than four times the allocation")
                .isLessThan(small * 8);
    }

    @Test
    void renderingAWideDraftStaysWithinTheAsciiBudget() {
        EditorState state = EditorState.of(draft(CJK_UNIT, 4096));
        long bytes = allocated(
                () -> sink = Editor.render(state, EditorOptions.defaults(), true));
        assertThat(bytes)
                .as("a wide draft is the same number of clusters, so it must not cost an order of "
                        + "magnitude more than an ASCII draft")
                .isLessThan(1_000_000L);
    }

    /**
     * A deliberately naive cluster-by-cluster reference: one span per cluster, merged only when the
     * style matches. It does not share the production accumulation strategy, so it keeps catching a
     * fast path that drops or reorders a cluster.
     */
    private static StyledText reference(EditorState state, EditorOptions opts, boolean focused) {
        int lines = state.lineCount();
        int visible = Math.min(opts.maxVisibleLines(), lines);
        int caretLine = state.lineAt(state.caret());
        int first = Math.max(0, Math.min(caretLine - visible + 1, lines - visible));
        int promptWidth = TextMeasure.columns(
                TabStops.expand(StyledText.plain(opts.prompt())).plainText());
        boolean masking = opts.masking() != EditorOptions.Masking.NONE;

        List<StyledText.Span> spans = new ArrayList<>();
        Style lastStyle = opts.textStyle();
        StringBuilder run = new StringBuilder();
        for (int line = first; line < first + visible; line++) {
            if (line > first) {
                run.append('\n');
            }
            if (!opts.prompt().isEmpty()) {
                Style promptStyle = opts.promptStyle();
                String prompt = line == first ? opts.prompt() : " ".repeat(promptWidth);
                if (promptStyle.equals(lastStyle)) {
                    run.append(prompt);
                } else {
                    if (lastStyle != null) {
                        spans.add(new StyledText.Span(run.toString(), lastStyle, null));
                        run.setLength(0);
                    }
                    run.append(prompt);
                    lastStyle = promptStyle;
                }
            }
            int start = state.lineStart(line);
            int end = state.lineEnd(line);
            int selectionStart = state.hasSelection() ? state.selectionStart() : -1;
            int selectionEnd = state.hasSelection() ? state.selectionEnd() : -1;
            int position = start;
            for (String cluster : TextMeasure.graphemes(state.text().substring(start, end))) {
                boolean selected = selectionStart >= 0
                        && position >= selectionStart && position < selectionEnd;
                String display = opts.masking() == EditorOptions.Masking.HIDDEN
                        ? "" : masking ? "\u2022" : cluster;
                if (focused && state.caret() == position) {
                    int cells = masking ? 1
                            : TextMeasure.clusterColumns(cluster, WidthPolicy.PINNED);
                    display = Editor.CURSOR.repeat(Math.max(1, cells));
                }
                Style style = selected ? opts.selectionStyle() : opts.textStyle();
                if (!display.isEmpty()) {
                    if (style.equals(lastStyle)) {
                        run.append(display);
                    } else {
                        if (run.length() > 0) {
                            spans.add(new StyledText.Span(run.toString(), lastStyle, null));
                            run.setLength(0);
                        }
                        run.append(display);
                        lastStyle = style;
                    }
                }
                position += cluster.length();
            }
            if (focused && state.caret() == end) {
                run.append(Editor.CURSOR);
            }
        }
        if (run.length() > 0) {
            spans.add(new StyledText.Span(run.toString(), lastStyle, null));
        }
        if (spans.isEmpty()) {
            return StyledText.empty();
        }
        StyledText[] pieces = spans.stream()
                .map(span -> StyledText.of(span.text(), span.style(), span.link()))
                .toArray(StyledText[]::new);
        return StyledText.concat(pieces);
    }

    @Test
    void theRenderedTextMatchesAClusterByClusterReference() {
        List<String> drafts = List.of(
                "", "a", "hello", "two\nlines", "lead\n\ntrail",
                "\ttabbed\tdraft", "漢字テスト hello 🙂", "é́x",
                "🙂\uFE0F and \uFE0E", "a\u0301\u0301b", "selection across a wide glyph 漢字",
                "line one\nline two\nline three\nline four");
        List<EditorOptions> optionSets = List.of(
                EditorOptions.defaults(),
                EditorOptions.defaults().withPrompt("> ", Style.NONE),
                EditorOptions.defaults().withMasking(EditorOptions.Masking.PASSWORD),
                EditorOptions.defaults().withMasking(EditorOptions.Masking.HIDDEN));
        for (String draft : drafts) {
            for (EditorOptions options : optionSets) {
                for (int caret : new int[]{0, Math.min(1, draft.length()),
                        draft.length() / 2, draft.length()}) {
                    for (boolean focused : new boolean[]{true, false}) {
                        EditorState state = EditorState.of(draft).withCaret(caret);
                        StyledText actual = Editor.render(state, options, focused);
                        StyledText expected = reference(state, options, focused);
                        String label = "draft=" + draft.replace("\n", "\\n")
                                + " mask=" + options.masking() + " caret=" + caret
                                + " focused=" + focused;
                        assertThat(actual.plainText()).as(label).isEqualTo(expected.plainText());
                        assertThat(actual.spans().stream().map(StyledText.Span::text)
                                .reduce("", String::concat))
                                .as(label + " spans cover the same text")
                                .isEqualTo(actual.plainText());
                    }
                }
            }
        }
    }
}

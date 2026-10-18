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

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Transcript records: turn assembly, tool lifecycle, and forward-compatible warnings. */
class TranscriptJsonlReaderTest {

    private static HarnessTranscript read(String jsonl) throws IOException {
        return new TranscriptJsonlReader().read(new StringReader(jsonl));
    }

    @Test
    void aCompactToolCallNeverSplitsASurrogatePair() {
        // The 80-unit budget falls inside the emoji, so a plain substring would leave a lone high
        // surrogate in the rendered call line.
        String arguments = "{\"q\":\"" + "a".repeat(72) + "\uD83D\uDE00\"}";
        String call = TranscriptJsonlReader.formatCall("mystery", arguments);
        assertThat(wellFormed(call)).as("call [%s]", call).isTrue();
        assertThat(call).endsWith("\u2026");
        assertThat(call).doesNotContain("\uD83D\uDE00");
    }

    @Test
    void theBundledTranscriptLoadsCleanAndExercisesTheTranscript() {
        HarnessTranscript transcript = TranscriptLoader.load();

        assertThat(transcript.warnings()).isEmpty();
        assertThat(transcript.turns()).hasSize(2);
        assertThat(transcript.turns().get(1).responses()).hasSizeGreaterThan(50);

        List<HarnessEntry.Tool> tools = transcript.turns().stream()
                .flatMap(turn -> turn.responses().stream())
                .filter(HarnessEntry.Tool.class::isInstance)
                .map(HarnessEntry.Tool.class::cast)
                .toList();
        // The fixture must stay rich enough to exercise the transcript: every tool kind the
        // showcase formats, and results long enough to need the "more lines" preview note.
        assertThat(tools).anySatisfy(tool -> assertThat(tool.call()).startsWith("$ "));
        assertThat(tools).anySatisfy(tool -> assertThat(tool.call()).startsWith("read "));
        assertThat(tools).anySatisfy(tool -> assertThat(tool.call()).startsWith("edit "));
        assertThat(tools).anySatisfy(tool -> assertThat(tool.result().lines().count())
                .isGreaterThan(HarnessProjection.TOOL_PREVIEW_LINES));
    }

    @Test
    void malformedLinesBecomeWarningsWhileANewerLogStaysReadable() throws IOException {
        HarnessTranscript transcript = read("""
                2026-10-17T00:00:00Z\tINFO\t{"kind":"event","payload":\
                {"type":"input.attached","inputId":"i1","text":"hello"}}
                not json at all
                {"kind":"event"}
                {"kind":"event","payload":{"type":"future.event","whatever":true}}
                {"kind":"event","payload":{"type":"turn.started","turnId":"t1","inputId":"i1"}}
                {"kind":"event","payload":{"type":"assistant.completed","turnId":"t1","text":"hi"}}
                {"kind":"event","payload":{"type":"turn.failed","turnId":"t1","text":"boom"}}
                """);
        // A raw session-log prefix is stripped, the two unreadable records are counted, and the
        // unknown event type is ignored without a warning.
        assertThat(transcript.warnings()).hasSize(2);
        assertThat(transcript.warnings().get(0)).startsWith("Line 2:");
        assertThat(transcript.warnings().get(1)).isEqualTo("Record without a payload object");
        assertThat(transcript.turns()).hasSize(1);
        assertThat(transcript.turns().get(0).userText()).isEqualTo("hello");
        assertThat(transcript.turns().get(0).responses())
                .filteredOn(HarnessEntry.Assistant.class::isInstance)
                .hasSize(1);
        assertThat(transcript.turns().get(0).responses())
                .filteredOn(HarnessEntry.Failure.class::isInstance)
                .singleElement()
                .satisfies(entry -> assertThat(((HarnessEntry.Failure) entry).text()).isEqualTo("boom"));
    }

    private static boolean wellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void anUnfinishedToolCallWithoutACallIdIsRenderedAsPending() throws IOException {
        HarnessTranscript transcript = read("""
                {"kind":"event","payload":{"type":"input.attached","inputId":"i1","text":"hello"}}
                {"kind":"event","payload":{"type":"turn.started","turnId":"t1","inputId":"i1"}}
                {"kind":"event","payload":{"type":"assistant.completed","turnId":"t1","tools":[\
                {"name":"read","arguments":"{\\"path\\":\\"a\\"}","status":"running"}]}}
                """);
        assertThat(transcript.turns()).hasSize(1);
        assertThat(transcript.turns().get(0).responses())
                .filteredOn(HarnessEntry.Tool.class::isInstance)
                .singleElement()
                .satisfies(entry -> {
                    HarnessEntry.Tool tool = (HarnessEntry.Tool) entry;
                    assertThat(tool.call()).contains("read");
                    assertThat(tool.status()).isEqualTo(HarnessEntry.ToolStatus.PENDING);
                });
    }
}

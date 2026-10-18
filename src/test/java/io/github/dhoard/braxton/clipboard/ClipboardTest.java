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

package io.github.dhoard.braxton.clipboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.internal.clipboard.ClipboardBuffer;
import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.internal.clipboard.Osc52;
import io.github.dhoard.braxton.internal.clipboard.TerminalEnvironment;
import io.github.dhoard.braxton.internal.clipboard.TmuxClipboard;

/** Clipboard and paste boundaries: bounded, sanitized, and truthful about transport. */
class ClipboardTest {

    private static ClipboardService service(String term, Map<String, String> extra,
            List<String> writes) {
        Map<String, String> environment = new java.util.LinkedHashMap<>(extra);
        environment.put("TERM", term);
        return new ClipboardService(ClipboardConfig.defaults(),
                new TerminalEnvironment(environment), writes::add);
    }

    @Test
    void pasteNormalizationPreservesIndentationAndRemovesArtifacts() {
        assertThat(PasteSanitizer.sanitize("  a  \r\n\tb  \r\n\r\n", PasteMode.NORMAL))
                .isEqualTo("  a\n\tb");
        assertThat(PasteSanitizer.sanitize("\u001B[31mred\u001B[0m\u0007", PasteMode.NORMAL))
                .isEqualTo("red");
    }

    @Test
    void aggressivePasteTrimsAndCollapsesBlankLines() {
        assertThat(PasteSanitizer.sanitize("  a  \n\n\n  b  ", PasteMode.AGGRESSIVE, true))
                .isEqualTo("a\n\nb");
        assertThat(PasteSanitizer.sanitize("a\n\n\nb", PasteMode.AGGRESSIVE, false))
                .isEqualTo("a\n\n\nb");
    }

    @Test
    void plainTextNormalizesLineEndingsWithoutTrimmingContent() {
        assertThat(PasteSanitizer.plainText("a\r\nb\rc")).isEqualTo("a\nb\nc");
    }

    @Test
    void osc52EncodesBase64AndTruncatesOnCodePointBoundaries() {
        Osc52.Encoded encoded = Osc52.encode("hello", 100);
        assertThat(encoded.sequence()).startsWith("\u001B]52;c;").endsWith("\u0007");
        assertThat(encoded.truncated()).isFalse();
        Osc52.Encoded truncated = Osc52.encode("\uD83D\uDE00\uD83D\uDE00", 4);
        assertThat(truncated.truncated()).isTrue();
        assertThat(truncated.text()).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void aNullPayloadEncodesNoSequenceAtAll() {
        // Osc52.encode(String) documents an empty string for null: there is nothing to write, so
        // no sequence may reach the terminal. The bounded overload still treats null as an empty
        // payload, as its own contract states.
        assertThat(Osc52.encode((String) null)).isEmpty();
        assertThat(Osc52.encode((String) null, 100).text()).isEmpty();
        assertThat(Osc52.encode((String) null, 100).sequence()).isNotEmpty();
    }

    @Test
    void osc52TruncationNeverSplitsAGraphemeCluster() {
        String family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67";
        Osc52.Encoded partial = Osc52.encode(family + "x", 5);
        assertThat(partial.truncated()).isTrue();
        assertThat(partial.text()).isEmpty();
        Osc52.Encoded accent = Osc52.encode("e\u0301x", 2);
        assertThat(accent.text()).isEmpty();
        assertThat(accent.truncated()).isTrue();
        Osc52.Encoded whole = Osc52.encode("e\u0301x", 3);
        assertThat(whole.text()).isEqualTo("e\u0301");
        assertThat(whole.truncated()).isTrue();
    }

    @Test
    void tmuxPassthroughWrapsOnceAndDoublesEscapes() {
        String wrapped = TmuxClipboard.passthrough("\u001B]52;c;AA==\u0007");
        assertThat(wrapped).startsWith("\u001BPtmux;").endsWith("\u001B\\");
        assertThat(TmuxClipboard.passthrough(wrapped)).isEqualTo(wrapped);
    }

    @Test
    void internalBufferTruncatesWithoutSplittingASurrogatePair() {
        ClipboardBuffer buffer = new ClipboardBuffer(1);
        assertThat(buffer.set("\uD83D\uDE00")).isEmpty();
        buffer = new ClipboardBuffer(2);
        assertThat(buffer.set("\uD83D\uDE00")).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void aDumbTerminalKeepsTheCopyInternalAndWritesNothing() {
        List<String> writes = new ArrayList<>();
        ClipboardResult result = service("dumb", Map.of(), writes).copy("hello");
        assertThat(result.storedInternally()).isTrue();
        assertThat(result.systemWriteRequested()).isFalse();
        assertThat(result.path()).isEqualTo(ClipboardPath.INTERNAL_ONLY);
        assertThat(writes).isEmpty();
    }

    @Test
    void aCapableTerminalWritesExactlyOneOsc52Sequence() {
        List<String> writes = new ArrayList<>();
        ClipboardResult result = service("xterm-256color", Map.of(), writes).copy("hello");
        assertThat(result.systemWriteRequested()).isTrue();
        assertThat(result.path()).isEqualTo(ClipboardPath.OSC52_DIRECT);
        assertThat(writes).hasSize(1);
    }

    @Test
    void identicalConsecutivePayloadsAreDeduplicated() {
        List<String> writes = new ArrayList<>();
        ClipboardService clipboard = service("xterm-256color", Map.of(), writes);
        clipboard.copy("same");
        ClipboardResult second = clipboard.copy("same");
        assertThat(second.deduplicated()).isTrue();
        assertThat(writes).hasSize(1);
    }

    @Test
    void tmuxAutoUsesNativeForwardingWithoutPassthroughWrapping() {
        List<String> writes = new ArrayList<>();
        ClipboardResult result = service("xterm-256color", Map.of("TMUX", "/tmp/tmux"), writes)
                .copy("hello");
        assertThat(result.path()).isEqualTo(ClipboardPath.TMUX_NATIVE);
        assertThat(writes.get(0)).doesNotContain("tmux;");
    }

    @Test
    void internalTextIsAvailableForApplicationPaste() {
        ClipboardService clipboard = service("dumb", Map.of(), new ArrayList<>());
        clipboard.copy("payload");
        assertThat(clipboard.internalText()).isEqualTo("payload");
    }
}

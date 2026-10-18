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
    void aPayloadThatTruncatesToNothingNeverClearsTheSystemClipboard() {
        // An empty OSC 52 payload clears the clipboard. A copy whose whole text does not fit the
        // configured payload limit therefore has nothing to write: writing the empty payload
        // would clear what the user already had instead of copying.
        List<String> writes = new ArrayList<>();
        ClipboardConfig config = ClipboardConfig.builder().maxPayloadBytes(2).build();
        ClipboardService clipboard = new ClipboardService(config,
                new TerminalEnvironment(Map.of("TERM", "xterm-256color")), writes::add);
        ClipboardResult result = clipboard.copy("\u6F22");
        assertThat(writes).isEmpty();
        assertThat(result.systemWriteRequested()).isFalse();
        assertThat(result.path()).isEqualTo(ClipboardPath.INTERNAL_ONLY);
        assertThat(result.truncated()).isTrue();
        assertThat(result.storedInternally()).isTrue();
        assertThat(clipboard.internalText()).isEqualTo("\u6F22");
        // A payload that keeps some text still writes what fits.
        List<String> partialWrites = new ArrayList<>();
        ClipboardService partial = new ClipboardService(config,
                new TerminalEnvironment(Map.of("TERM", "xterm-256color")), partialWrites::add);
        ClipboardResult partialResult = partial.copy("a\u6F22");
        assertThat(partialWrites).hasSize(1);
        assertThat(partialResult.systemWriteRequested()).isTrue();
        assertThat(partialResult.truncated()).isTrue();
    }

    @Test
    void tmuxAutoUsesNativeForwardingWithoutPassthroughWrapping() {
        List<String> writes = new ArrayList<>();
        ClipboardResult result = service("xterm-256color", Map.of("TMUX", "/tmp/tmux"), writes)
                .copy("hello");
        assertThat(result.path()).isEqualTo(ClipboardPath.TMUX_NATIVE);
        assertThat(writes.get(0)).doesNotContain("tmux;");
    }

    private static ClipboardService matrix(TmuxStrategy strategy, boolean osc52,
            Map<String, String> extra, List<String> writes) {
        Map<String, String> environment = new java.util.LinkedHashMap<>(extra);
        environment.put("TERM", "xterm-256color");
        ClipboardConfig config = ClipboardConfig.builder().osc52Enabled(osc52)
                .tmuxStrategy(strategy).build();
        return new ClipboardService(config, new TerminalEnvironment(environment), writes::add);
    }

    @Test
    void everyTmuxStrategySelectsTheDocumentedTransport() {
        Map<String, String> tmux = Map.of("TMUX", "/tmp/tmux-1000/default,1,0");
        assertThat(matrix(TmuxStrategy.AUTO, true, tmux, new ArrayList<>()).copy("a").path())
                .isEqualTo(ClipboardPath.TMUX_NATIVE);
        assertThat(matrix(TmuxStrategy.AUTO, true, Map.of(), new ArrayList<>()).copy("a").path())
                .isEqualTo(ClipboardPath.OSC52_DIRECT);
        assertThat(matrix(TmuxStrategy.DIRECT, true, tmux, new ArrayList<>()).copy("a").path())
                .isEqualTo(ClipboardPath.OSC52_DIRECT);
        assertThat(matrix(TmuxStrategy.TMUX_NATIVE, true, tmux, new ArrayList<>()).copy("a").path())
                .isEqualTo(ClipboardPath.TMUX_NATIVE);
        assertThat(matrix(TmuxStrategy.TMUX_NATIVE, true, Map.of(), new ArrayList<>())
                .copy("a").path()).isEqualTo(ClipboardPath.OSC52_DIRECT);
        assertThat(matrix(TmuxStrategy.TMUX_PASSTHROUGH, true, tmux, new ArrayList<>())
                .copy("a").path()).isEqualTo(ClipboardPath.TMUX_PASSTHROUGH);
        assertThat(matrix(TmuxStrategy.TMUX_PASSTHROUGH, true, Map.of(), new ArrayList<>())
                .copy("a").path()).isEqualTo(ClipboardPath.OSC52_DIRECT);
        assertThat(matrix(TmuxStrategy.OFF, true, tmux, new ArrayList<>()).copy("a").path())
                .isEqualTo(ClipboardPath.INTERNAL_ONLY);
        assertThat(matrix(TmuxStrategy.AUTO, false, tmux, new ArrayList<>()).copy("a").path())
                .isEqualTo(ClipboardPath.INTERNAL_ONLY);
    }

    @Test
    void tmuxPassthroughWrapsTheOsc52SequenceExactlyOnce() {
        List<String> writes = new ArrayList<>();
        ClipboardResult result = matrix(TmuxStrategy.TMUX_PASSTHROUGH, true,
                Map.of("TMUX", "/tmp/tmux-1000/default,1,0"), writes).copy("hi");
        assertThat(result.path()).isEqualTo(ClipboardPath.TMUX_PASSTHROUGH);
        assertThat(writes).hasSize(1);
        String written = writes.get(0);
        assertThat(written).startsWith("\u001BPtmux;").endsWith("\u001B\\");
        // The inner OSC 52 keeps its own ESC, doubled for the single unescape layer.
        assertThat(written).contains("\u001B\u001B]52;c;");
        assertThat(TmuxClipboard.passthrough(written)).isEqualTo(written);
    }

    @Test
    void aDumbTerminalNeverWritesEvenWhenAPassthroughIsConfigured() {
        List<String> writes = new ArrayList<>();
        Map<String, String> environment = Map.of("TERM", "dumb", "TMUX", "/tmp/tmux");
        ClipboardConfig config = ClipboardConfig.builder()
                .tmuxStrategy(TmuxStrategy.TMUX_PASSTHROUGH).build();
        ClipboardResult result = new ClipboardService(config,
                new TerminalEnvironment(environment), writes::add).copy("hi");
        assertThat(result.path()).isEqualTo(ClipboardPath.INTERNAL_ONLY);
        assertThat(writes).isEmpty();
    }

    @Test
    void identicalPayloadsAreDeduplicatedPerTransport() {
        List<String> writes = new ArrayList<>();
        ClipboardService clipboard = matrix(TmuxStrategy.TMUX_PASSTHROUGH, true,
                Map.of("TMUX", "/tmp/tmux"), writes);
        assertThat(clipboard.copy("same").deduplicated()).isFalse();
        assertThat(clipboard.copy("same").deduplicated()).isTrue();
        assertThat(writes).hasSize(1);
    }

    @Test
    void aFailedWriteKeepsTheTextAndRetriesTheNextTime() {
        List<String> writes = new ArrayList<>();
        boolean[] failing = {true};
        ClipboardService clipboard = new ClipboardService(ClipboardConfig.defaults(),
                new TerminalEnvironment(Map.of("TERM", "xterm-256color")), sequence -> {
                    if (failing[0]) {
                        failing[0] = false;
                        throw new IllegalStateException("output closed");
                    }
                    writes.add(sequence);
                });
        ClipboardResult failed = clipboard.copy("payload");
        assertThat(failed.path()).isEqualTo(ClipboardPath.INTERNAL_ONLY);
        assertThat(failed.systemWriteRequested()).isFalse();
        assertThat(failed.storedInternally()).isTrue();
        assertThat(clipboard.internalText()).isEqualTo("payload");
        ClipboardResult retried = clipboard.copy("payload");
        assertThat(retried.systemWriteRequested()).isTrue();
        assertThat(writes).hasSize(1);
    }

    @Test
    void clipboardConfigurationClampsItsLimitsAndParsesStrategyNames() {
        ClipboardConfig config = ClipboardConfig.builder().maxPayloadBytes(0).maxInternalChars(0)
                .tmuxStrategy(null).build();
        assertThat(config.maxPayloadBytes()).isEqualTo(1);
        assertThat(config.maxInternalChars()).isEqualTo(1);
        assertThat(config.tmuxStrategy()).isEqualTo(TmuxStrategy.AUTO);
        assertThat(config.osc52Enabled()).isTrue();
        assertThat(config.toBuilder().osc52Enabled(false).build().osc52Enabled()).isFalse();
        assertThat(TmuxStrategy.parse(" tmux_passthrough ", TmuxStrategy.AUTO))
                .isEqualTo(TmuxStrategy.TMUX_PASSTHROUGH);
        assertThat(TmuxStrategy.parse("TMUX_NATIVE", TmuxStrategy.AUTO))
                .isEqualTo(TmuxStrategy.TMUX_NATIVE);
        assertThat(TmuxStrategy.parse("nonsense", TmuxStrategy.OFF)).isEqualTo(TmuxStrategy.OFF);
        assertThat(TmuxStrategy.parse(null, TmuxStrategy.DIRECT)).isEqualTo(TmuxStrategy.DIRECT);
        assertThat(PasteMode.parse(" aggressive ", PasteMode.NORMAL))
                .isEqualTo(PasteMode.AGGRESSIVE);
        assertThat(PasteMode.parse("unknown", PasteMode.NORMAL)).isEqualTo(PasteMode.NORMAL);
    }

    @Test
    void aCopiedPayloadNeverCarriesTerminalEscapes() {
        // Copied content is sanitized before it is stored or encoded, so a hostile selection cannot
        // inject a sequence into the OSC 52 write or come back through the in-process paste.
        String hostile = "a\u001B]0;title\u0007b\u001B[31mc\u001B[0m\u0000\u009B1m"
                + "\u001BPdcs\u001B\\d";
        assertThat(PasteSanitizer.plainText(hostile)).isEqualTo("abcd");
        List<String> writes = new ArrayList<>();
        ClipboardService clipboard = service("xterm-256color", Map.of(), writes);
        ClipboardResult result = clipboard.copy(hostile);
        assertThat(result.text()).isEqualTo("abcd");
        assertThat(clipboard.internalText()).isEqualTo("abcd");
        assertThat(writes).hasSize(1);
        String encoded = java.util.Base64.getEncoder()
                .encodeToString("abcd".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(writes.get(0)).isEqualTo("\u001B]52;c;" + encoded + "\u0007");
    }

    @Test
    void internalTextIsAvailableForApplicationPaste() {
        ClipboardService clipboard = service("dumb", Map.of(), new ArrayList<>());
        clipboard.copy("payload");
        assertThat(clipboard.internalText()).isEqualTo("payload");
    }
}

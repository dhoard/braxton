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

import io.github.dhoard.braxton.internal.clipboard.ClipboardService;
import io.github.dhoard.braxton.internal.clipboard.TerminalEnvironment;

/** Only the transport changes with the strategy; an off switch writes no escape at all. */
class ClipboardTransportTest {

    private static ClipboardService service(TmuxStrategy strategy, boolean enabled, List<String> writes,
            Map<String, String> environment) {
        ClipboardConfig config = ClipboardConfig.builder().tmuxStrategy(strategy)
                .osc52Enabled(enabled).build();
        return new ClipboardService(config, new TerminalEnvironment(environment), writes::add);
    }

    @Test
    void theOffStrategyAndADisabledOscWriteNoEscapeButStillStore() {
        List<String> writes = new ArrayList<>();
        ClipboardResult off = service(TmuxStrategy.OFF, true, writes, Map.of("TERM", "xterm"))
                .copy("text");
        assertThat(writes).isEmpty();
        assertThat(off.storedInternally()).isTrue();
        assertThat(off.systemWriteRequested()).isFalse();
        assertThat(off.path()).isEqualTo(ClipboardPath.INTERNAL_ONLY);
        ClipboardResult disabled = service(TmuxStrategy.DIRECT, false, writes, Map.of())
                .copy("more");
        assertThat(writes).isEmpty();
        assertThat(disabled.systemWriteRequested()).isFalse();
    }

    @Test
    void aDirectWriteInsideTmuxStaysDirect() {
        List<String> writes = new ArrayList<>();
        ClipboardResult result = service(TmuxStrategy.DIRECT, true, writes, Map.of("TMUX", "/tmp/t"))
                .copy("hi");
        assertThat(result.path()).isEqualTo(ClipboardPath.OSC52_DIRECT);
        assertThat(writes).hasSize(1);
        assertThat(writes.get(0)).startsWith("\u001B]52;c;");
    }
}

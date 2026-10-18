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

package io.github.dhoard.braxton.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** Command values reject input the runtime cannot honour. */
class CommandTest {

    @Test
    void aDelayLongerThanItsNanosecondScheduleIsRejectedAtConstruction() {
        assertThatThrownBy(() -> Command.delay(EffectId.of("a"), Duration.ofDays(1_000_000),
                "message", "rejected"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nanoseconds");
    }

    @Test
    void aDelayThatFitsInNanosecondsIsAccepted() {
        Command<String> command = Command.delay(EffectId.of("a"), Duration.ofDays(100_000),
                "message", "rejected");
        assertThat(command).isInstanceOf(Command.Delay.class);
        assertThat(((Command.Delay<String>) command).delay()).isEqualTo(Duration.ofDays(100_000));
    }
}

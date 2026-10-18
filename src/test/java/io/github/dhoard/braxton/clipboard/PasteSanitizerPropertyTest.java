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

import java.util.Random;

import org.junit.jupiter.api.Test;

/** Sanitizing is idempotent and never leaves a terminal control character behind. */
class PasteSanitizerPropertyTest {

    @Test
    void everyModeIsIdempotentAndControlFree() {
        String[] atoms = {"a", "\n", "\r\n", "\t", "\u001B[31m", "\u001B]0;t\u0007", "\u009B",
                "\u009D", "\u0007", "\u0000", "\u007F", "\u00A0", "\u200B", "\u200D",
                "\uD83D\uDE00", "\u65E5"};
        Random random = new Random(17L);
        for (int iteration = 0; iteration < 600; iteration++) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < random.nextInt(10); i++) {
                text.append(atoms[random.nextInt(atoms.length)]);
            }
            for (PasteMode mode : PasteMode.values()) {
                String once = PasteSanitizer.sanitize(text.toString(), mode);
                assertThat(PasteSanitizer.sanitize(once, mode)).as("idempotent for %s", mode)
                        .isEqualTo(once);
                for (int i = 0; i < once.length(); i++) {
                    char c = once.charAt(i);
                    if (c != '\n' && c != '\t') {
                        assertThat(c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F))
                                .as("control 0x%x survived in [%s]", (int) c, text).isFalse();
                    }
                }
            }
            String plain = PasteSanitizer.plainText(text.toString());
            for (int i = 0; i < plain.length(); i++) {
                char c = plain.charAt(i);
                if (c != '\n' && c != '\t') {
                    assertThat(c < 0x20 || c == 0x7F || (c >= 0x80 && c <= 0x9F)).isFalse();
                }
            }
        }
    }
}

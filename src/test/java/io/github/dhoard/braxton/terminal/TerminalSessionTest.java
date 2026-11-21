/*
 * Copyright (c) 2026-present Douglas Hoard
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

package io.github.dhoard.braxton.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.layout.Size;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Public terminal API behavior with no native access and no MVU runtime.
 */
class TerminalSessionTest {

    @Test
    void decodesKeysAndReportsSizeChanges() throws Exception {
        AtomicReference<Size> size = new AtomicReference<>(new Size(6, 20));
        try (TerminalSession session = TerminalSession.of(new StringReader("ab"), new StringWriter(), size::get)) {
            session.enterRawMode();
            assertThat(session.size()).isEqualTo(new Size(6, 20));
            assertThat(session.capabilities().term()).isEqualTo("dumb");
            assertThat(session.capabilities().color()).isFalse();
            assertThat(session.capabilities().report().results()).hasSize(4);

            assertThat(((TerminalEvent.Input) session.read().orElseThrow()).value())
                    .isInstanceOf(InputEvent.KeyInput.class)
                    .extracting(value -> ((InputEvent.KeyInput) value).key().name())
                    .isEqualTo("a");

            size.set(new Size(4, 10));
            assertThat(session.read().orElseThrow()).isInstanceOf(TerminalEvent.Resize.class);
            assertThat(session.size()).isEqualTo(new Size(4, 10));

            assertThat(((TerminalEvent.Input) session.read().orElseThrow()).value())
                    .isInstanceOf(InputEvent.KeyInput.class)
                    .extracting(value -> ((InputEvent.KeyInput) value).key().name())
                    .isEqualTo("b");
            assertThat(session.read()).isEmpty();
        }
    }

    @Test
    void writesSafeTextAndApplicationOwnedEscapeSequences() throws Exception {
        StringWriter output = new StringWriter();
        try (TerminalSession session = TerminalSession.of(new StringReader(""), output, new Size(4, 12))) {
            session.writeText("hi\u0001");
            session.moveCursor(1, 2);
            session.clear();
            session.flush();
        }
        assertThat(output.toString()).isEqualTo("hi \u001b[2;3H\u001b[2J\u001b[H");
    }

    @Test
    void writeLineFlushesUntrustedText() throws Exception {
        StringWriter output = new StringWriter();
        try (TerminalSession session = TerminalSession.of(new StringReader(""), output, new Size(4, 12))) {
            session.writeLine("hello");
        }
        assertThat(output.toString()).isEqualTo("hello \n");
    }

    @Test
    void noPublicSignatureNamesAnInternalType() {
        for (Method method : TerminalSession.class.getMethods()) {
            for (Class<?> type : signatureTypes(method)) {
                assertThat(type.getName())
                        .as("%s parameter or return type", method)
                        .doesNotContain(".internal.");
            }
        }
        for (Class<?> nested : TerminalSession.class.getClasses()) {
            for (Method method : nested.getMethods()) {
                for (Class<?> type : signatureTypes(method)) {
                    assertThat(type.getName())
                            .as("%s parameter or return type", method)
                            .doesNotContain(".internal.");
                }
            }
        }
    }

    @Test
    void demoUsesOnlyThePublicTerminalApi() throws Exception {
        String source = Files.readString(Path.of("src/main/java/examples/terminal/TerminalApiDemo.java"));
        assertThat(source).doesNotContain("io.github.dhoard.braxton.mvu").doesNotContain("internal");
        assertThat(source).contains("TerminalSession.open()");
    }

    private static java.util.List<Class<?>> signatureTypes(Method method) {
        java.util.List<Class<?>> types = new java.util.ArrayList<>();
        types.add(method.getReturnType());
        types.addAll(java.util.List.of(method.getParameterTypes()));
        types.addAll(java.util.List.of(method.getExceptionTypes()));
        return types;
    }
}

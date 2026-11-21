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

package io.github.dhoard.braxton.app;

import io.github.dhoard.braxton.layout.Size;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Where one session's bytes come from and go to, what it may learn from the environment, and
 * whether it paints frames at all.
 *
 * <p>The default is the owned POSIX terminal: no stream is supplied, so the runtime opens the
 * process's own device, reads its input, paints its frames and gives it back on the way out. Supplying
 * a stream pair replaces that device with something the caller owns - a pipe, a socket, a byte array,
 * another process's terminal - and no native library is loaded and no device configuration is
 * changed. A supplied stream is not a VT: it negotiates no alternate screen, no pointer reporting, no
 * bracketed paste, no focus reporting and no color, so a stream session renders inline (see
 * {@link TerminalOptions#alternateScreen()}) and asks for anything else only through an explicit
 * {@link RuntimeOptions#withColorProfile(io.github.dhoard.braxton.style.ColorProfile)}.</p>
 *
 * <p>The streams belong to the caller. The runtime flushes what it writes and never closes, resets or
 * seeks either end, so an embedder can keep using them after the session ends; closing them is the
 * caller's decision, made after the run returns. Text crosses both ends as UTF-8.</p>
 *
 * <p>Two kinds of session need no device at all. A stream session's size cannot be measured with an
 * ioctl, so {@link #size()} states it, and only an explicit resize a host reports to the session
 * changes it. A renderer-disabled session
 * paints nothing, writes no escape sequence, and still runs its program: input decoding, effects,
 * sources, filters and the model are exactly what they are in a painted session. That is the mode a
 * piped or batch run uses, and its result is the final model, not a screen.</p>
 *
 * <p>{@link #diagnostics()} is the session's log. The library creates no log file and writes no
 * diagnostic to the terminal it is drawing on, because either would corrupt a frame; a host that
 * wants to record what the runtime observed supplies a sink here and owns its destination.</p>
 *
 * @param input the session's terminal input, or null for the owned terminal's own input
 * @param output the session's frame output, or null for the owned terminal's own output
 * @param environment environment overrides this session reads instead of the process's own
 * @param size the initial size a stream session lays out for; null for {@value #DEFAULT_ROWS} by
 *     {@value #DEFAULT_COLUMNS}
 * @param renderer whether the session paints frames; false writes no terminal output at all
 * @param diagnostics the sink the runtime reports diagnostics to, or null to report none
 */
public record IoOptions(
        Reader input,
        Writer output,
        Map<String, String> environment,
        Size size,
        boolean renderer,
        Consumer<String> diagnostics) {

    /**
     * The rows a stream session lays out for when no size is stated.
     */
    public static final int DEFAULT_ROWS = 24;

    /**
     * The columns a stream session lays out for when no size is stated.
     */
    public static final int DEFAULT_COLUMNS = 80;

    /**
     * Rejects half a stream pair, copies the environment and substitutes the default size.
     *
     * <p>Input and output are one decision: a session that reads somebody else's bytes must write
     * somewhere other than the device it is not using, and vice versa. A half pair would silently mix
     * a supplied reader with the owned terminal's output, which is the one combination no caller can
     * reason about.</p>
     */
    public IoOptions {
        if ((input == null) != (output == null)) {
            throw new IllegalArgumentException("a supplied input requires a supplied output");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        if (environment != null) {
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        environment = Map.copyOf(copy);
        size = size == null ? new Size(DEFAULT_ROWS, DEFAULT_COLUMNS) : size;
    }

    /**
     * The owned terminal, no overrides, frames painted, no diagnostics.
     *
     * @return the documented defaults
     */
    public static IoOptions defaults() {
        return new IoOptions(null, null, Map.of(), null, true, null);
    }

    /**
     * Whether the session reads and writes stream endpoints instead of the owned terminal.
     *
     * @return true when a stream pair is supplied
     */
    public boolean streamed() {
        return input != null;
    }

    /**
     * The environment value a session reads, from the overrides first and the process second.
     *
     * <p>An override that is present wins even when it is empty, which is how a caller turns a
     * variable off for one session without changing the process's own environment; an absent override
     * falls back to {@link System#getenv(String)}.</p>
     *
     * @param name the variable's name; required
     * @return the value, or null when neither the overrides nor the process define it
     */
    public String environment(String name) {
        Objects.requireNonNull(name, "name");
        String override = environment.get(name);
        return override != null ? override : System.getenv(name);
    }

    /**
     * The same options reading {@code reader} instead of the owned terminal.
     *
     * @param reader the input the session decodes; null keeps the owned terminal
     * @return the new options
     */
    public IoOptions withInput(Reader reader) {
        if (reader == null) {
            return output == null ? this : new IoOptions(null, null, environment, size, renderer, diagnostics);
        }
        return new IoOptions(
                reader, output == null ? Writer.nullWriter() : output, environment, size, renderer, diagnostics);
    }

    /**
     * The same options reading {@code input} instead of the owned terminal, decoded as UTF-8.
     *
     * @param input the byte stream the session decodes; null keeps the owned terminal
     * @return the new options
     */
    public IoOptions withInput(InputStream input) {
        return input == null
                ? withInput((Reader) null)
                : withInput(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    /**
     * The same options writing frames to {@code writer} instead of the owned terminal.
     *
     * @param writer the sink the session paints into; null keeps the owned terminal
     * @return the new options
     */
    public IoOptions withOutput(Writer writer) {
        if (writer == null) {
            return input == null ? this : new IoOptions(null, null, environment, size, renderer, diagnostics);
        }
        return new IoOptions(
                input == null ? Reader.nullReader() : input, writer, environment, size, renderer, diagnostics);
    }

    /**
     * The same options writing frames to {@code output} instead of the owned terminal, encoded as
     * UTF-8.
     *
     * @param output the byte sink the session paints into; null keeps the owned terminal
     * @return the new options
     */
    public IoOptions withOutput(OutputStream output) {
        return output == null
                ? withOutput((Writer) null)
                : withOutput(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    }

    /**
     * The same options over a supplied stream pair.
     *
     * @param reader the session's input; required
     * @param writer the session's output; required
     * @return the new options
     */
    public IoOptions withStreams(Reader reader, Writer writer) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(writer, "writer");
        return new IoOptions(reader, writer, environment, size, renderer, diagnostics);
    }

    /**
     * The same options with environment overrides.
     *
     * @param value the variables this session reads instead of the process's own; null for none
     * @return the new options
     */
    public IoOptions withEnvironment(Map<String, String> value) {
        return new IoOptions(input, output, value, size, renderer, diagnostics);
    }

    /**
     * The same options laying out for {@code value} until the size is reported again.
     *
     * @param value the size to lay out for; null restores the default
     * @return the new options
     */
    public IoOptions withSize(Size value) {
        return new IoOptions(input, output, environment, value, renderer, diagnostics);
    }

    /**
     * The same options laying out for {@code rows} by {@code columns}.
     *
     * @param rows the rows to lay out for
     * @param columns the columns to lay out for
     * @return the new options
     */
    public IoOptions withSize(int rows, int columns) {
        return withSize(new Size(rows, columns));
    }

    /**
     * The same options painting frames or not.
     *
     * <p>A renderer-disabled session writes no frame, no mode sequence and no synchronized-update
     * bracket: its output is only what the application itself prints. Everything else - input
     * decoding, commands, sources, the message filter and cleanup - runs unchanged, so the run ends
     * with the model it would have reached on a screen.</p>
     *
     * @param value whether the session paints frames
     * @return the new options
     */
    public IoOptions withRenderer(boolean value) {
        return new IoOptions(input, output, environment, size, value, diagnostics);
    }

    /**
     * The same options reporting diagnostics to {@code sink}.
     *
     * <p>The sink is the only place the runtime logs. A sink that throws cannot fail the run: the
     * failure is swallowed, because a diagnostic is a report about the session, not part of it.</p>
     *
     * @param sink the caller-owned sink, or null to report none
     * @return the new options
     */
    public IoOptions withDiagnostics(Consumer<String> sink) {
        return new IoOptions(input, output, environment, size, renderer, sink);
    }
}

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

package io.github.dhoard.braxton.effect;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable description of one child process to run while the session has given the terminal
 * back.
 *
 * <p>Every part of the request is a value, so the command a transition returns can be inspected,
 * compared and re-run without capturing live state: the program and its arguments, the environment
 * entries to add to the session's own, and the working directory. Nothing here is a shell: the argv
 * list is passed to the operating system as given, so a name with a space or a glob in it is one
 * argument rather than something a shell would split, and nothing the application did not write is
 * ever expanded.</p>
 *
 * @param command the program and its arguments; the first element is the program
 * @param environment environment entries to add to, or replace in, the session's environment
 * @param directory the working directory, or null for the session's own
 */
public record ProcessRequest(List<String> command, Map<String, String> environment, Path directory) {

    /**
     * Rejects a request the operating system could not be given.
     *
     * <p>The checks are the ones a process launch would perform later, moved to the value: an empty
     * or blank program, a null argument, or a NUL character in any string is a declaration error,
     * not something to discover after the terminal has already been handed over.</p>
     */
    public ProcessRequest {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("a process request needs a program");
        }
        // Validated before the copy: an immutable list rejects a null element itself, and the
        // declaration error it would report is less useful than this one.
        for (String argument : command) {
            if (argument == null) {
                throw new IllegalArgumentException("a process argument must not be null");
            }
            rejectNul(argument, "argument");
        }
        if (command.get(0).isBlank()) {
            throw new IllegalArgumentException("a process request needs a non-blank program");
        }
        command = List.copyOf(command);
        Map<String, String> entries = new LinkedHashMap<>();
        if (environment != null) {
            for (Map.Entry<String, String> entry : environment.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name == null || name.isBlank() || name.indexOf('=') >= 0) {
                    throw new IllegalArgumentException("an environment name must be non-blank and contain no '='");
                }
                if (value == null) {
                    throw new IllegalArgumentException("environment entry " + name + " has no value");
                }
                rejectNul(name, "environment name");
                rejectNul(value, "environment value");
                entries.put(name, value);
            }
        }
        environment = Map.copyOf(entries);
    }

    /**
     * A request that runs one program with its arguments.
     *
     * @param argv the program and its arguments; required
     * @return the request, with the session's environment and working directory
     */
    public static ProcessRequest of(String... argv) {
        Objects.requireNonNull(argv, "argv");
        List<String> command = new ArrayList<>(argv.length);
        for (String argument : argv) {
            command.add(argument);
        }
        return new ProcessRequest(command, Map.of(), null);
    }

    /**
     * The same request with environment entries added or replaced.
     *
     * @param entries the entries to add; null adds nothing
     * @return the new request
     */
    public ProcessRequest withEnvironment(Map<String, String> entries) {
        Map<String, String> merged = new LinkedHashMap<>(environment);
        if (entries != null) {
            merged.putAll(entries);
        }
        return new ProcessRequest(command, merged, directory);
    }

    /**
     * The same request with another working directory.
     *
     * @param value the working directory; null uses the session's own
     * @return the new request
     */
    public ProcessRequest withDirectory(Path value) {
        return new ProcessRequest(command, environment, value);
    }

    private static void rejectNul(String value, String what) {
        if (value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("a process " + what + " must not contain NUL");
        }
    }
}

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

package io.github.dhoard.braxton.internal.terminal;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.terminal.TerminalBackend;
import io.github.dhoard.braxton.terminal.TerminalOptions;
import io.github.dhoard.braxton.terminal.TerminalSession;

/**
 * Selects the platform backend and enforces one real terminal owner.
 *
 * <p>Session state is instance-owned: two concurrent real sessions cannot share saved console modes
 * or a temporary raw state. Unsupported operating systems and ABIs fail before any terminal mode is
 * changed rather than falling back to a guessed native layout.</p>
 */
public final class SystemTerminalBackend implements TerminalBackend {

    public static final SystemTerminalBackend INSTANCE = new SystemTerminalBackend();

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    private static final String ARCH = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    private static final boolean WINDOWS = OS.contains("win");
    private static final boolean LINUX = OS.contains("linux") || OS.contains("nux");

    private final AtomicBoolean owned = new AtomicBoolean();

    @Override
    public TerminalSession open(Size size, TerminalOptions options) {
        if (!owned.compareAndSet(false, true)) {
            throw new IllegalStateException("another Braxton terminal session already owns this console");
        }
        Runnable release = () -> owned.set(false);
        try {
            if (WINDOWS) {
                return new WindowsTerminalSession(options, size, release);
            }
            if (LINUX && (ARCH.contains("x86_64") || ARCH.contains("amd64"))) {
                return new LinuxTerminalSession(options, release);
            }
            throw new UnsupportedOperationException("unsupported terminal platform: " + OS + "/" + ARCH
                    + " (supported: Linux/glibc x86_64, Windows console)");
        } catch (RuntimeException | Error failure) {
            owned.set(false);
            throw failure;
        }
    }
}

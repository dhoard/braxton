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

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalOptions;

/**
 * Entry point for the native-Braxton AI Harness.
 *
 * <p>The whole application is {@link HarnessProgram}: one immutable model, one update function, one
 * view tree. This class only supplies launch-time data and translates the runtime's result into a
 * process status. It performs no terminal I/O of its own.</p>
 */
public final class AiHarness {

    private AiHarness() {
    }

    public static void main(String[] args) {
        ExitResult result = run();
        if (result.kind() == ExitResult.Kind.FAILURE) {
            System.err.println("AI Harness stopped: " + result);
            if (result.failure() != null) {
                result.failure().printStackTrace(System.err);
            }
        }
        if (result.status() != 0) {
            System.exit(result.status());
        }
    }

    /** Runs one harness session; exposed so tests and embedding code can drive it explicitly. */
    public static ExitResult run() {
        RuntimeOptions options = RuntimeOptions.defaults()
                .withTerminal(TerminalOptions.DEFAULT.withMouse(true));
        return Application.run(createProgram(), options);
    }

    /** The production harness program, used directly and as an embedded feature by the showcase. */
    public static HarnessProgram createProgram() {
        return new HarnessProgram(TranscriptLoader::load, projectPath(), List.of());
    }

    public static String projectPath() {
        String path = Path.of("").toAbsolutePath().normalize().toString();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && path.startsWith(home + File.separator)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }
}

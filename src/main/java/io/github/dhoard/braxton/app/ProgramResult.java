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

/**
 * The final state of one program run: the last committed model and how the run ended.
 *
 * <p>The model is the value the runtime committed last, not a copy of anything the program can still
 * change: models are immutable and the runtime has stopped, so this is the program's final state. A
 * run that ended before the program was initialized - the terminal could not be opened, or a stop
 * arrived that early - reports no model.</p>
 *
 * @param model the final committed model, or null when the run ended before {@code init} committed
 * @param exit how the run ended; never null
 * @param <M> the root model type
 */
public record ProgramResult<M>(M model, ExitResult exit) {

    /**
     * Rejects a missing exit result.
     */
    public ProgramResult {
        if (exit == null) throw new IllegalArgumentException("exit is required");
    }

    /**
     * Whether the run ended without a failure and with status 0.
     *
     * @return whether the run succeeded
     */
    public boolean ok() {
        return exit.ok();
    }

    /** Diagnostic summary; never includes model values or terminal escape output. */
    @Override
    public String toString() {
        return "ProgramResult[" + (model == null ? "no model" : "model") + ", " + exit + "]";
    }
}

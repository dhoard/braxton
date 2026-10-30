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

package io.github.dhoard.braxton.mvu;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Fail-fast path/phase diagnostic. Does not render model or message values.
 *
 * <p>The message names the component path, the failing phase and, when the framework rejected a
 * declaration rather than reporting a thrown exception (for example an exceeded tree bound or a
 * command scoped to an undeclared component), a short structural reason. That reason is duplicated
 * in the cause for callers that inspect it; it never contains model, message, or output values.
 * A {@link ComponentFailure} raised by a nested mount is re-wrapped with the outer path prefix, so
 * {@link #path()} is always the full structural path of the failing component.</p>
 */
public final class ComponentFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Phase {
        INIT,
        UPDATE,
        VIEW,
        SUBSCRIPTIONS,
        CHILDREN,
        OUTPUT_ROUTE
    }

    private final ComponentPath path;
    private final Phase phase;
    private final String detail;

    public ComponentFailure(ComponentPath path, Phase phase, Throwable cause) {
        this(path, phase, null, cause);
    }

    /**
     * Creates a failure whose message also states {@code detail}, a short structural reason.
     *
     * @param path full structural path of the failing component
     * @param phase phase that failed
     * @param detail structural reason appended to the message, or null for a thrown failure
     * @param cause the failure the component or runtime reported
     */
    public ComponentFailure(ComponentPath path, Phase phase, String detail, Throwable cause) {
        super(
                "component "
                        + Objects.requireNonNull(path, "path")
                        + " failed during "
                        + Objects.requireNonNull(phase, "phase")
                        + (detail == null || detail.isBlank() ? "" : ": " + detail),
                cause);
        this.path = path;
        this.phase = phase;
        this.detail = detail == null || detail.isBlank() ? null : detail;
    }

    public ComponentPath path() {
        return path;
    }

    public Phase phase() {
        return phase;
    }

    /**
     * The structural reason the framework reported, or null when a component threw.
     *
     * @return the short reason, or null
     */
    public String detail() {
        return detail;
    }

    /**
     * Runs one component phase call, adding this path and phase to any failure.
     *
     * <p>Errors (including {@link OutOfMemoryError} and {@link StackOverflowError}) are rethrown
     * unchanged: wrapping a fatal JVM condition would allocate while the JVM is already in
     * trouble and would hide the original type. The runtime still fails the run and restores the
     * terminal for them.</p>
     */
    public static <T> T call(ComponentPath path, Phase phase, Supplier<T> action) {
        try {
            return Objects.requireNonNull(action.get(), "component result");
        } catch (ComponentFailure failure) {
            throw new ComponentFailure(
                    failure.path.prepend(path), failure.phase, failure.detail, failure.getCause());
        } catch (Error fatal) {
            throw fatal;
        } catch (Throwable failure) {
            throw new ComponentFailure(path, phase, failure);
        }
    }
}

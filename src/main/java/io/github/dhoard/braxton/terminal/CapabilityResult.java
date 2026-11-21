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

/**
 * The decision for one optional terminal feature.
 *
 * @param capability the terminal feature being described
 * @param enabled whether the feature is enabled
 * @param source the source of the capability decision
 * @param detail the human-readable diagnostic detail
 */
public record CapabilityResult(Capability capability, boolean enabled, CapabilitySource source, String detail) {

    /**
     * Creates a {@code CapabilityResult} value.
     *
     * @param capability the terminal feature being described
     * @param enabled whether the feature is enabled
     * @param source the source of the capability decision
     * @param detail the human-readable diagnostic detail
     */
    public CapabilityResult {
        if (capability == null) throw new IllegalArgumentException("capability is required");
        if (source == null) throw new IllegalArgumentException("source is required");
        detail = detail == null ? "" : detail;
        if (enabled && source == CapabilitySource.FALLBACK) {
            throw new IllegalArgumentException("a fallback cannot enable a capability");
        }
    }

    /**
     * A disabled result with the reason it stayed off.
     *
     * @param capability the terminal feature being described
     * @param reason the reason the capability is disabled
     * @return a disabled result with the reason it stayed off
     */
    public static CapabilityResult fallback(Capability capability, String reason) {
        return new CapabilityResult(capability, false, CapabilitySource.FALLBACK, reason);
    }
}

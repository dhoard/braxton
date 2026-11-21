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

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Per-feature results for one terminal, including the evidence behind each decision.
 *
 * @param term the terminal identifier used for hints
 * @param results every capability and its decision
 */
public record CapabilityReport(String term, Map<Capability, CapabilityResult> results) {

    /**
     * Creates a {@code CapabilityReport} value.
     *
     * @param term the terminal identifier used for hints
     * @param results every capability and its decision
     */
    public CapabilityReport {
        term = term == null ? "" : term;
        Map<Capability, CapabilityResult> complete = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            CapabilityResult result = results.get(capability);
            if (result == null || result.capability() != capability) {
                throw new IllegalArgumentException("missing result for " + capability);
            }
            complete.put(capability, result);
        }
        results = Map.copyOf(complete);
    }

    /**
     * All features off, for a terminal with no usable identity.
     *
     * @return all features off, for a terminal with no usable identity
     */
    public static CapabilityReport plain() {
        Map<Capability, CapabilityResult> all = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            all.put(capability, CapabilityResult.fallback(capability, "no terminal identity"));
        }
        return new CapabilityReport("dumb", all);
    }

    /**
     * The decision for one feature.
     *
     * @param capability the terminal feature being described
     * @return the decision for one feature
     */
    public CapabilityResult result(Capability capability) {
        return results.get(capability);
    }

    /**
     * Whether one feature is enabled.
     *
     * @param capability the terminal feature being described
     * @return {@code true} if one feature is enabled; otherwise {@code false}
     */
    public boolean enabled(Capability capability) {
        return result(capability).enabled();
    }

    /**
     * The capabilities that are enabled, in declaration order.
     *
     * @return the capabilities that are enabled, in declaration order
     */
    public java.util.List<Capability> enabledCapabilities() {
        return Arrays.stream(Capability.values()).filter(this::enabled).toList();
    }
}

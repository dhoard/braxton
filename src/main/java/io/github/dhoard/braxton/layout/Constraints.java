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

package io.github.dhoard.braxton.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic one-axis constraint solver for a cell grid.
 */
public final class Constraints {

    private Constraints() {}

    /**
     * Resolves one axis for every child.
     *
     * <p>Definite rules (fixed, percent, fraction and a bare minimum) are resolved first. If they
     * ask for more than the available space, every definite size is scaled down with the largest
     * remainders going to the earliest children. Flex rules then share what is left by weight. A
     * maximum smaller than a minimum wins, so a conflicting pair has one documented answer; all
     * results are clipped to the available space.</p>
     *
     * @param available the space to divide, never negative in effect
     * @param specs one rule list per child, in child order
     * @return one resolved size per child, in the same order
     */
    public static int[] solve(int available, List<List<Constraint>> specs) {
        int space = Math.max(0, available);
        int count = specs.size();
        int[] result = new int[count];
        int[] weights = new int[count];
        int[] minimums = new int[count];
        int[] maximums = new int[count];
        int weightTotal = 0;
        int used = 0;

        for (int i = 0; i < count; i++) {
            int minimum = 0;
            int maximum = Integer.MAX_VALUE;
            int value = 0;
            boolean definite = false;
            int weight = 0;
            for (Constraint rule : specs.get(i)) {
                switch (rule) {
                    case Constraint.Fixed fixed -> {
                        value = fixed.cells();
                        definite = true;
                    }
                    case Constraint.Percent percent -> {
                        value = portion(space, percent.value(), 100);
                        definite = true;
                    }
                    case Constraint.Fractional fraction -> {
                        value = portion(space, fraction.numerator(), fraction.denominator());
                        definite = true;
                    }
                    case Constraint.Minimum min -> minimum = Math.max(minimum, min.cells());
                    case Constraint.Maximum max -> maximum = Math.min(maximum, max.cells());
                    case Constraint.Flex flex -> weight = flex.weight();
                }
            }
            if (!definite && weight == 0) value = minimum;
            // A flex child owns none of the definite space; its minimum is applied to the share it
            // receives later, not as a pre-allocation that starves the other flexible children.
            result[i] = weight > 0 ? 0 : clamp(value, minimum, maximum, Integer.MAX_VALUE);
            minimums[i] = minimum;
            maximums[i] = maximum;
            weights[i] = weight;
            weightTotal += weight;
            used += result[i];
        }

        if (used > space) {
            shrink(result, space);
            used = 0;
            for (int size : result) used += size;
        }
        int remaining = space - used;
        if (remaining > 0 && weightTotal > 0) {
            long assignedShares = 0;
            long[] shares = new long[count];
            long[] remainders = new long[count];
            List<Integer> weighted = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (weights[i] == 0) continue;
                long exact = (long) remaining * weights[i];
                shares[i] = exact / weightTotal;
                remainders[i] = exact % weightTotal;
                assignedShares += shares[i];
                weighted.add(i);
            }
            long left = remaining - assignedShares;
            weighted.sort((first, second) -> {
                int byRemainder = Long.compare(remainders[second], remainders[first]);
                return byRemainder != 0 ? byRemainder : Integer.compare(first, second);
            });
            for (int rank = 0; rank < left && rank < weighted.size(); rank++) shares[weighted.get(rank)]++;
            for (int i = 0; i < count; i++) {
                if (weights[i] == 0) continue;
                int share = clamp((int) shares[i], minimums[i], maximums[i], remaining);
                result[i] += share;
            }
        }
        return result;
    }

    /**
     * Half-up integer scaling that cannot overflow for non-negative cell counts.
     */
    private static int portion(int total, int numerator, int denominator) {
        if (denominator <= 0) return 0;
        long scaled = ((long) total * numerator + denominator / 2L) / denominator;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, scaled));
    }

    private static int clamp(int value, int minimum, int maximum, int space) {
        int bounded = Math.max(value, minimum);
        if (maximum < bounded) bounded = maximum;
        return Math.min(bounded, space);
    }

    /**
     * Proportional shrink with deterministic largest-remainder ties resolved by child order.
     */
    private static void shrink(int[] sizes, int space) {
        long total = 0;
        for (int size : sizes) total += size;
        if (total == 0) return;
        List<Integer> order = new ArrayList<>(sizes.length);
        long[] scaled = new long[sizes.length];
        long assigned = 0;
        for (int i = 0; i < sizes.length; i++) {
            long exact = (long) sizes[i] * space;
            scaled[i] = exact / total;
            assigned += scaled[i];
            order.add(i);
        }
        long left = space - assigned;
        final long span = total;
        order.sort((first, second) -> {
            long firstRemainder = (long) sizes[first] * space - scaled[first] * span;
            long secondRemainder = (long) sizes[second] * space - scaled[second] * span;
            int byRemainder = Long.compare(secondRemainder, firstRemainder);
            return byRemainder != 0 ? byRemainder : Integer.compare(first, second);
        });
        for (int rank = 0; rank < left && rank < order.size(); rank++) {
            scaled[order.get(rank)]++;
        }
        for (int i = 0; i < sizes.length; i++) sizes[i] = (int) scaled[i];
    }
}

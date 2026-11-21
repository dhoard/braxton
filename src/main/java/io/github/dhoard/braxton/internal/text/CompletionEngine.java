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

package io.github.dhoard.braxton.internal.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Shared completion strategies for editor and text-input widgets.
 */
public final class CompletionEngine {

    /**
     * How a candidate matches a draft.
     */
    public enum Mode {
        /**
         * Matches a contiguous prefix.
         */
        PREFIX,
        /**
         * Matches a contiguous substring.
         */
        SUBSTRING,
        /**
         * Matches an ordered subsequence and ranks its compactness.
         */
        FUZZY
    }

    private CompletionEngine() {}

    /**
     * Returns the best candidates for a prefix, in stability-preserving score order.
     *
     * @param prefix the query prefix to match
     * @param candidates the candidate values in source order
     * @param mode the requested behavior mode
     * @param limit the maximum number of admitted results
     * @return the best candidates for a prefix, in stability-preserving score order
     */
    public static List<String> complete(String prefix, List<String> candidates, Mode mode, int limit) {
        return complete(prefix, candidates, mode, true, limit);
    }

    /**
     * Completion with explicit case sensitivity; false preserves the original prefix contract.
     *
     * @param candidates the candidate values in source order
     * @param ignoreCase whether matching ignores case
     * @param limit the maximum number of admitted results
     * @param mode the requested behavior mode
     * @param prefix the query prefix to match
     * @return completion with explicit case sensitivity; false preserves the original prefix contract
     */
    public static List<String> complete(
            String prefix, List<String> candidates, Mode mode, boolean ignoreCase, int limit) {
        return filter(prefix, candidates, candidate -> candidate, mode, ignoreCase, limit);
    }

    /**
     * The candidates that match a needle, scored and ordered as completion scores them, keeping each
     * candidate itself rather than the text it was matched on.
     *
     * <p>It is what a widget that lists <em>items</em> filters with - a command palette over commands,
     * a picker over files - so the strategies stay in one place. Ties keep the caller's order, which
     * is why a palette with an empty needle should not come here at all: every candidate matches a
     * zero-character fuzzy needle, so the caller's own order is what an empty query should show. A
     * candidate whose text is null never matches.</p>
     *
     * @param <T> the candidate type
     * @param needle the text to match; null matches nothing
     * @param candidates the candidates to filter
     * @param text the text a candidate is matched on; required
     * @param mode how a candidate matches the needle; required
     * @param ignoreCase whether case is ignored
     * @param limit the largest number of results; non-positive returns none
     * @return the matching candidates, best first
     */
    public static <T> List<T> filter(
            String needle,
            List<T> candidates,
            Function<? super T, String> text,
            Mode mode,
            boolean ignoreCase,
            int limit) {
        if (needle == null || candidates == null || text == null || mode == null || limit <= 0) {
            return List.of();
        }
        String query = ignoreCase ? needle.toLowerCase(Locale.ROOT) : needle;
        if (mode == Mode.PREFIX) {
            List<T> prefixMatches = new ArrayList<>();
            for (T candidate : candidates) {
                if (candidate == null) continue;
                String value = text.apply(candidate);
                if (value == null) continue;
                String lower = ignoreCase ? value.toLowerCase(Locale.ROOT) : value;
                if (lower.startsWith(query)) prefixMatches.add(candidate);
            }
            return List.copyOf(prefixMatches.subList(0, Math.min(limit, prefixMatches.size())));
        }
        List<Scored<T>> scored = new ArrayList<>();
        int rank = 0;
        for (T candidate : candidates) {
            if (candidate == null) continue;
            String value = text.apply(candidate);
            if (value == null) continue;
            String lower = ignoreCase ? value.toLowerCase(Locale.ROOT) : value;
            int score = switch (mode) {
                case PREFIX -> -1;
                case SUBSTRING -> {
                    int at = lower.indexOf(query);
                    yield at < 0 ? -1 : at * 1000 + lower.length();
                }
                case FUZZY -> fuzzy(query, lower);
            };
            if (score >= 0) scored.add(new Scored<>(score, rank, candidate));
            rank++;
        }
        scored.sort(Comparator.comparingInt(Scored<T>::score).thenComparingInt(Scored<T>::rank));
        List<T> result = new ArrayList<>(Math.min(limit, scored.size()));
        for (int i = 0; i < limit && i < scored.size(); i++)
            result.add(scored.get(i).value());
        return List.copyOf(result);
    }

    /**
     * Contiguous runs of a subsequence, scored by spread and length.
     */
    private static int fuzzy(String needle, String haystack) {
        if (needle.isEmpty()) return haystack.length();
        int at = 0;
        int gaps = 0;
        for (int i = 0; i < needle.length(); i++) {
            int found = haystack.indexOf(needle.charAt(i), at);
            if (found < 0) return -1;
            gaps += found - at;
            at = found + 1;
        }
        return gaps * 10_000 + haystack.length();
    }

    private record Scored<T>(int score, int rank, T value) {}
}

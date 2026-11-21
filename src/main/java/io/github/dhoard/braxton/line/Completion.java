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

package io.github.dhoard.braxton.line;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The completion menu an editor is showing, as an immutable value.
 *
 * <p>A menu is state rather than a message: the editor that holds one is still showing the line the
 * user typed, and this says which part of it a candidate would replace, which candidates there are
 * and which one is highlighted. The line itself does not change until the user accepts a candidate,
 * so what is painted above the menu is always what the user typed.</p>
 *
 * <p>Any gesture that is not a completion gesture ends the menu and is then applied, so the menu
 * cannot be showing candidates for a line that has moved on. That is what lets {@link #start()} and
 * {@link #token()} be trusted by whoever accepts a candidate: they name the text the candidates were
 * computed for.</p>
 *
 * @param start the character offset of the token's first cluster, from zero
 * @param token the text being completed; never null and one row
 * @param candidates the candidates, in the order the menu shows them; never empty
 * @param selected the index of the highlighted candidate, clamped into the list
 */
public record Completion(int start, String token, List<String> candidates, int selected) {

    /**
     * Prepares the token, the candidates and the highlight.
     *
     * <p>Every candidate is flattened to one row and a blank one is dropped, so no candidate can
     * bring a second line or a control character into a painted cell, and a menu is never built
     * without something to offer. A missing highlight is the first candidate, and one that is out of
     * range is clamped into the list, so a host reading the menu never has to guard against one.</p>
     */
    public Completion {
        start = Math.max(0, start);
        token = LineText.singleRow(token);
        List<String> offered = new ArrayList<>();
        if (candidates != null) {
            for (String candidate : candidates) {
                String row = LineText.singleRow(candidate);
                if (!row.isBlank()) {
                    offered.add(row);
                }
            }
        }
        if (offered.isEmpty()) {
            throw new IllegalArgumentException("a completion needs at least one candidate");
        }
        candidates = List.copyOf(offered);
        selected = Math.max(0, Math.min(selected, candidates.size() - 1));
    }

    /**
     * A menu for the candidates, or empty when there is nothing to offer.
     *
     * <p>The one way a host builds a menu from a completer's answer: a null, empty or all-blank
     * answer is not a menu, and the canonical constructor prepares what is left. So a caller never
     * has to decide whether an empty answer should paint an empty menu.</p>
     *
     * @param start the token's character offset
     * @param token the text being completed
     * @param candidates the candidates, which may be null, empty or partly blank
     * @return the menu, or empty when every candidate was blank
     */
    public static Optional<Completion> of(int start, String token, List<String> candidates) {
        return offers(candidates) ? Optional.of(new Completion(start, token, candidates, 0)) : Optional.empty();
    }

    /**
     * Whether an answer holds a candidate a menu could show.
     */
    private static boolean offers(List<String> candidates) {
        if (candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (!LineText.singleRow(candidate).isBlank()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The character offset just past the token being completed.
     *
     * @return the character offset just past the token being completed
     */
    public int end() {
        return start + token.length();
    }

    /**
     * The candidate the highlight is on; never null.
     *
     * @return the candidate the highlight is on; never null
     */
    public String candidate() {
        return candidates.get(selected);
    }

    /**
     * The same menu with the highlight on the next candidate, wrapping at the end.
     *
     * @return the same instance when there is nothing to step to
     */
    public Completion next() {
        return candidates.size() < 2 ? this : new Completion(start, token, candidates, wrap(1));
    }

    /**
     * The same menu with the highlight on the previous candidate, wrapping at the start.
     *
     * @return the same instance when there is nothing to step to
     */
    public Completion previous() {
        return candidates.size() < 2 ? this : new Completion(start, token, candidates, wrap(-1));
    }

    private int wrap(int step) {
        int size = candidates.size();
        return Math.floorMod(selected + step, size);
    }
}

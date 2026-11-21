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

import java.util.List;

/**
 * The incremental history search, as a value a {@link LineEditor} holds.
 *
 * <p>Every step is a pure function of the history, the running search and the gesture: it answers
 * the search to hold next together with the line and caret the row shows afterward, and it never
 * touches an editor. That is what lets the editor keep the search in one field and apply an answer
 * in one place, and it is why this block is a collaborator rather than another hundred lines of the
 * editor: the editor's own job is what a gesture means, and walking a history is a different job.</p>
 *
 * <p>The rules are {@link LineEditor}'s and are documented there: a case-sensitive substring match,
 * an empty query that matches every entry, a failed step that keeps showing the last match while the
 * search records the failure, and the entry being the editor's text with the caret where the query
 * matched it.</p>
 */
final class LineSearch {

    /**
     * A running search and what it has to restore.
     *
     * <p>The line and caret the search started from are what {@link LineEditor.Msg.SearchCancel}
     * puts back, and the index of the entry being shown is where the next step continues from.
     * {@code index} is {@code -1} while nothing has matched.</p>
     *
     * @param search the search as a caller reads it
     * @param index the history index being shown, or {@code -1}
     * @param line the line the search started from
     * @param caret the caret the search started from
     */
    record Run(LineEditor.Search search, int index, String line, int caret) {}

    /**
     * What one step found.
     *
     * @param run the search the editor holds next
     * @param text the one row the editor shows now
     * @param caret the caret the editor shows now
     */
    record Step(Run run, String text, int caret) {}

    private LineSearch() {}

    /**
     * Starts a search, or steps the running one in a direction.
     *
     * <p>A fresh search walks from the end of the history the direction starts at. A search that has
     * not matched yet, or whose last step failed, walks from the same place, so repeating a failing
     * gesture fails again instead of walking nowhere.</p>
     *
     * @param history the lines to search; required
     * @param text the line the editor is showing, which a fresh search returns to when it matches
     *     nothing
     * @param caret the caret the editor is showing
     * @param running the search in progress, or null for a fresh one
     * @param direction the way to walk
     * @return the step
     */
    static Step find(History history, String text, int caret, Run running, LineEditor.Direction direction) {
        LineEditor.Search current = running == null ? new LineEditor.Search(direction, "", false) : running.search();
        int from = running == null || running.index() < 0 ? start(history, direction) : running.index();
        return walk(history, text, caret, running, new LineEditor.Search(direction, current.query(), false), from);
    }

    /**
     * Narrows the running search with more of the text being looked for.
     *
     * <p>A longer query is a different search, so the walk restarts at the end the direction starts
     * at rather than continuing from the entry the shorter query landed on.</p>
     *
     * @param history the lines to search; required
     * @param text the line the editor is showing
     * @param caret the caret the editor is showing
     * @param running the search in progress, or null for a fresh backward one
     * @param added the text to add; already one row and not empty
     * @return the step
     */
    static Step insert(History history, String text, int caret, Run running, String added) {
        LineEditor.Search current =
                running == null ? new LineEditor.Search(LineEditor.Direction.BACKWARD, "", false) : running.search();
        return walk(
                history,
                text,
                caret,
                running,
                new LineEditor.Search(current.direction(), current.query() + added, false),
                start(history, current.direction()));
    }

    /**
     * Takes one cluster back off the query and searches again.
     *
     * @param history the lines to search; required
     * @param text the line the editor is showing
     * @param caret the caret the editor is showing
     * @param running the search in progress; required, with a query of at least one cluster
     * @return the step
     */
    static Step erase(History history, String text, int caret, Run running) {
        String query = running.search().query();
        String next = query.substring(0, LineText.previous(query, query.length()));
        return walk(
                history,
                text,
                caret,
                running,
                new LineEditor.Search(running.search().direction(), next, false),
                start(history, running.search().direction()));
    }

    /**
     * Where a walk in a direction starts: past the newest entry, or before the oldest.
     *
     * @param history the lines to search; required
     * @param direction the way to walk
     * @return the starting index
     */
    static int start(History history, LineEditor.Direction direction) {
        return direction == LineEditor.Direction.BACKWARD ? history.size() : -1;
    }

    /**
     * Shows the next entry the query matches, or keeps what the row shows and records the failure.
     *
     * <p>A match shows the whole entry with the caret where the query matched it, because that is
     * what the found line is; a step that finds nothing keeps the last match on screen and marks the
     * search failed, so one mistyped character costs one backspace rather than the search.</p>
     */
    private static Step walk(History history, String text, int caret, Run running, LineEditor.Search wanted, int from) {
        String baseLine = running == null ? text : running.line();
        int baseCaret = running == null ? caret : running.caret();
        int found = match(history, wanted.query(), from, wanted.direction());
        if (found < 0) {
            Run failed = new Run(
                    new LineEditor.Search(wanted.direction(), wanted.query(), true),
                    running == null ? -1 : running.index(),
                    baseLine,
                    baseCaret);
            // The failed step keeps the row as it is; the run still remembers where to go back to.
            return new Step(failed, text, caret);
        }
        String entry = history.entries().get(found);
        int matched = entry.indexOf(wanted.query());
        Run showing =
                new Run(new LineEditor.Search(wanted.direction(), wanted.query(), false), found, baseLine, baseCaret);
        return new Step(showing, entry, LineText.boundaryBefore(entry, Math.max(0, matched)));
    }

    /**
     * The entry a step finds, or -1.
     *
     * <p>The match is a case-sensitive substring: a search that guessed at case would skip a line the
     * user can see matches. The empty query matches every entry, which is what makes a second press
     * of the same gesture walk the history before anything has been typed.</p>
     */
    private static int match(History history, String query, int from, LineEditor.Direction direction) {
        List<String> entries = history.entries();
        if (direction == LineEditor.Direction.BACKWARD) {
            for (int index = Math.min(from, entries.size()) - 1; index >= 0; index--) {
                if (entries.get(index).contains(query)) {
                    return index;
                }
            }
            return -1;
        }
        for (int index = Math.max(from, -1) + 1; index < entries.size(); index++) {
            if (entries.get(index).contains(query)) {
                return index;
            }
        }
        return -1;
    }
}

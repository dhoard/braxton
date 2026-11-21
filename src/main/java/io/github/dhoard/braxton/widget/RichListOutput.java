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

package io.github.dhoard.braxton.widget;

import io.github.dhoard.braxton.widget.RichList.Item;
import java.util.List;
import java.util.Objects;

/**
 * What the application should do about a choice.
 */
public sealed interface RichListOutput {

    /**
     * The user chose one item, or every item they marked.
     *
     * <p>A list with no marks chooses the selected item alone, so the single-choice contract is
     * the same message with one entry. With marks, the chosen items are the marked ones in the
     * list's own order, whether or not the query of the moment still shows them all: a choice
     * the user built is not silently narrowed by a filter they typed afterward.</p>
     *
     * @param items the chosen items, at least one
     */
    record Chosen(List<Item> items) implements RichListOutput {

        /**
         * Rejects an empty choice and prepares the list.
         */
        public Chosen {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            if (items.isEmpty()) {
                throw new IllegalArgumentException("a choice names at least one item");
            }
        }

        /**
         * The choice as one item.
         *
         * @return the first chosen item, which is the single one when nothing was marked
         */
        public Item item() {
            return items.get(0);
        }
    }

    /**
     * The user canceled a browsing list that had nothing left to cancel.
     */
    record Canceled() implements RichListOutput {}
}

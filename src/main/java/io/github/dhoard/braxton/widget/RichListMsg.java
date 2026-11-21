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

import io.github.dhoard.braxton.widget.RichList.FilterMode;
import io.github.dhoard.braxton.widget.RichList.Item;
import java.util.List;
import java.util.Objects;

/**
 * Every message the list handles.
 */
public sealed interface RichListMsg {

    /**
     * Moves the selection to the previous visible item.
     */
    record Up() implements RichListMsg {}

    /**
     * Moves the selection to the next visible item.
     */
    record Down() implements RichListMsg {}

    /**
     * Moves to the page before the one the selection is on.
     */
    record PreviousPage() implements RichListMsg {}

    /**
     * Moves to the page after the one the selection is on.
     */
    record NextPage() implements RichListMsg {}

    /**
     * Moves the selection to the first visible item.
     */
    record First() implements RichListMsg {}

    /**
     * Moves the selection to the last visible item.
     */
    record Last() implements RichListMsg {}

    /**
     * Starts editing the query.
     */
    record BeginFilter() implements RichListMsg {}

    /**
     * Applies the query being edited.
     */
    record AcceptFilter() implements RichListMsg {}

    /**
     * Cancels the query being edited, clears an applied query, or reports that there is nothing
     * left to cancel - in that order.
     */
    record Cancel() implements RichListMsg {}

    /**
     * Clears the query and the selection it narrowed.
     */
    record ClearFilter() implements RichListMsg {}

    /**
     * Switches the filter between its plain and subsequence modes.
     */
    record ToggleFilterMode() implements RichListMsg {}

    /**
     * Sets the filter mode.
     *
     * @param value the new mode
     */
    record SetFilterMode(FilterMode value) implements RichListMsg {

        /**
         * Rejects a missing mode.
         */
        public SetFilterMode {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * A message from the composed filter field.
     *
     * @param value the field's message
     */
    record FilterInput(TextInput.Msg value) implements RichListMsg {

        /**
         * Rejects a missing message.
         */
        public FilterInput {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Chooses the selected item, or every marked item when there is a mark.
     */
    record Choose() implements RichListMsg {}

    /**
     * Marks the selected item, or unmarks it when it is already marked, and moves down.
     *
     * <p>Moving after the mark is what lets a user build a multiple choice without pressing a
     * second gesture for every item; the selection stays on the last item when the mark was made
     * at the end of the list.</p>
     */
    record ToggleMark() implements RichListMsg {}

    /**
     * Marks every item the query shows, leaving marks that are already there in place.
     */
    record MarkAll() implements RichListMsg {}

    /**
     * Drops every mark.
     */
    record ClearMarks() implements RichListMsg {}

    /**
     * Selects one item, by id.
     *
     * <p>This is what a click on a row means. It selects rather than chooses because choosing is
     * the application's decision - a click that both moved the selection and asked the
     * application to act on it would take that decision away - so a second click on the row is
     * what asks for {@link Choose}, exactly as a second press of a double click does.</p>
     *
     * @param id the id of the item to select; null, or an id the query hides, leaves the
     *     selection where it is
     */
    record Select(String id) implements RichListMsg {

        /**
         * Rejects no id; a missing one names no item.
         */
        public Select {
            id = id == null ? "" : id;
        }
    }

    /**
     * Replaces every item.
     *
     * @param items the new items; null is empty
     */
    record SetItems(List<Item> items) implements RichListMsg {}

    /**
     * Inserts an item into the stored list.
     *
     * @param index the position in the stored list; past the end appends
     * @param item the item to insert
     */
    record InsertItem(int index, Item item) implements RichListMsg {

        /**
         * Rejects a missing item.
         */
        public InsertItem {
            Objects.requireNonNull(item, "item");
        }
    }

    /**
     * Replaces the item with the same id.
     *
     * @param item the item to store; an id the list does not hold is a no-op
     */
    record ReplaceItem(Item item) implements RichListMsg {

        /**
         * Rejects a missing item.
         */
        public ReplaceItem {
            Objects.requireNonNull(item, "item");
        }
    }

    /**
     * Removes one item by its stable id and selects the nearest surviving visible item.
     *
     * @param id the id to remove; an id the list does not hold is a no-op
     */
    record RemoveItem(String id) implements RichListMsg {}

    /**
     * Publishes a new height for the whole list, chrome included.
     *
     * @param rows the rows the list may paint
     */
    record RowsChanged(int rows) implements RichListMsg {}

    /**
     * Switches the title bar on or off.
     */
    record ToggleTitle() implements RichListMsg {}

    /**
     * Switches the status row on or off.
     */
    record ToggleStatus() implements RichListMsg {}

    /**
     * Switches the page indicator on or off.
     */
    record TogglePagination() implements RichListMsg {}

    /**
     * Switches help on or off.
     */
    record ToggleHelp() implements RichListMsg {}

    /**
     * Switches help between its compact line and its full grouped listing.
     */
    record ToggleExpandedHelp() implements RichListMsg {}

    /**
     * Switches the loading spinner on or off.
     */
    record ToggleLoading() implements RichListMsg {}

    /**
     * A message from the composed spinner.
     *
     * @param value the spinner's message
     */
    record SpinnerMessage(Spinner.Msg value) implements RichListMsg {

        /**
         * Rejects a missing message.
         */
        public SpinnerMessage {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Paints a transient message for the notice lifetime that starts now.
     *
     * @param text the message; null or blank clears the current one
     */
    record Notice(String text) implements RichListMsg {}

    /**
     * Clears the transient message with this epoch, if it is still the current one.
     *
     * @param epoch the notice the expiry was scheduled for
     */
    record NoticeExpired(long epoch) implements RichListMsg {}
}

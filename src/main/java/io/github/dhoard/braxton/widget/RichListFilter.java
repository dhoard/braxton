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

/**
 * The filter: the field a query is typed into, the query that was applied, and the mode.
 *
 * <p>The query is the field's own text, so there is exactly one place the current query lives
 * and a filter cannot disagree with the value the field paints. Editing is the field's focus.
 * Accepting keeps the live query and remembers it as the applied one; canceling restores the
 * applied query and the item that was selected when the edit began, which is why both are model
 * data rather than transient state.</p>
 *
 * @param field the composed field's state; never null
 * @param applied the query an accept last applied
 * @param mode how a query is matched
 * @param prefilterSelection the selected id when the current edit began, or empty
 */
public record RichListFilter(TextInput.Model field, String applied, FilterMode mode, String prefilterSelection) {

    /**
     * Substitutes an empty field and the default mode for a missing value.
     */
    public RichListFilter {
        // A filter starts blurred: whether a query is being edited is model data the caller
        // asks for, not something constructing a value can decide.
        field = field == null ? RichListFilter.blurred() : field;
        applied = applied == null ? "" : applied;
        mode = mode == null ? FilterMode.FUZZY : mode;
        prefilterSelection = prefilterSelection == null ? "" : prefilterSelection;
    }

    /**
     * No query, no applied query and no remembered selection.
     *
     * @param mode how a query is matched
     * @return the empty filter
     */
    public static RichListFilter of(FilterMode mode) {
        return new RichListFilter(blurred(), "", mode, "");
    }

    /**
     * An empty field that is not being typed into.
     */
    static TextInput.Model blurred() {
        return TextInput.Model.empty().withFocus(false);
    }

    /**
     * The query being edited or shown.
     *
     * @return the field's text
     */
    public String query() {
        return field.text();
    }

    /**
     * Whether a query is being typed.
     *
     * @return whether the field owns focus
     */
    public boolean editing() {
        return field.focused();
    }

    /**
     * The same filter with a different field.
     *
     * @param value the new field state; null is the empty field
     * @return the new filter
     */
    public RichListFilter withField(TextInput.Model value) {
        return new RichListFilter(value, applied, mode, prefilterSelection);
    }

    /**
     * The same filter with a different applied query.
     *
     * @param value the new applied query; null is empty
     * @return the new filter
     */
    public RichListFilter withApplied(String value) {
        return new RichListFilter(field, value, mode, prefilterSelection);
    }

    /**
     * The same filter with a different mode.
     *
     * @param value the new mode; null is {@link FilterMode#FUZZY}
     * @return the new filter
     */
    public RichListFilter withMode(FilterMode value) {
        return new RichListFilter(field, applied, value, prefilterSelection);
    }

    /**
     * The same filter with a different remembered selection.
     *
     * @param value the selected id to restore on cancel; null is none
     * @return the new filter
     */
    public RichListFilter withPrefilterSelection(String value) {
        return new RichListFilter(field, applied, mode, value);
    }

    /**
     * The same filter with the field focused or blurred, which starts and stops an edit.
     *
     * @param value whether a query is being typed
     * @return the new filter
     */
    public RichListFilter withEditing(boolean value) {
        return withField(field.withFocus(value));
    }
}

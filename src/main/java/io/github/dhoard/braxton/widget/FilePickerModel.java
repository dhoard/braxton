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

import static io.github.dhoard.braxton.widget.FilePicker.DEFAULT_ROWS;

import io.github.dhoard.braxton.widget.FilePicker.Entry;
import io.github.dhoard.braxton.widget.FilePicker.Request;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Picker state: a directory, its entries, the selection, the window and the request in flight.
 *
 * <p>The canonical constructor keeps the state paintable - the offsets and the geometry are
 * never negative and the pending values are never null - and the rest of the rules are applied
 * where they are read. A selection whose entry is not shown falls on the first shown entry, a
 * window that would hide the selection is moved the least it can be, and a message that names a
 * request the state is no longer waiting for changes nothing. A state built by hand therefore
 * paints and reduces exactly like one the picker produced.</p>
 *
 * @param directory the directory the entries were read from; the path row paints it
 * @param root the canonical root the picker enforces, or null when the caller set no boundary
 * @param entries every entry the last successful listing produced, hidden ones included
 * @param selected the selected entry's name, or empty when nothing is selected
 * @param top the index of the first shown entry the window paints
 * @param rows the rows the parent allocated the whole picker, chrome included
 * @param hidden whether hidden entries are shown
 * @param expanded whether help is painted expanded instead of as one compact line
 * @param focused whether the picker owns focus
 * @param epoch the request epoch; a result naming an older one is discarded
 * @param request what the picker is waiting for, or what it last failed to read
 * @param truncated whether the listing stopped at the configured maximum
 * @param notice the transient phrase the status row paints, or empty
 */
public record FilePickerModel(
        Path directory,
        Path root,
        List<Entry> entries,
        String selected,
        int top,
        int rows,
        boolean hidden,
        boolean expanded,
        boolean focused,
        long epoch,
        Request request,
        boolean truncated,
        String notice) {

    /**
     * Keeps the state representable and every value non-null where it must be.
     */
    public FilePickerModel {
        Objects.requireNonNull(directory, "directory");
        entries = entries == null ? List.of() : List.copyOf(entries);
        selected = selected == null ? "" : selected;
        top = Math.max(0, top);
        rows = Math.max(0, rows);
        epoch = Math.max(0L, epoch);
        request = request == null ? Request.idle() : request;
        notice = notice == null ? "" : notice;
    }

    /**
     * A picker over one directory with no entries loaded yet.
     *
     * @param directory the directory to show; required
     * @return the state
     */
    public static FilePickerModel of(Path directory) {
        return of(directory, List.of());
    }

    /**
     * A picker over one directory with the entries a caller already holds.
     *
     * <p>This is the state a test, a preview or an application that read the directory itself
     * builds: nothing here performs I/O, and no request is in flight.</p>
     *
     * @param directory the directory the entries belong to; required
     * @param entries the entries; null is empty
     * @return the state
     */
    public static FilePickerModel of(Path directory, List<Entry> entries) {
        List<Entry> values = entries == null ? List.of() : List.copyOf(entries);
        return new FilePickerModel(
                directory,
                null,
                values,
                values.isEmpty() ? "" : values.get(0).name(),
                0,
                DEFAULT_ROWS,
                false,
                false,
                true,
                0L,
                Request.idle(),
                false,
                "");
    }

    /**
     * The same state over a different directory.
     *
     * @param value the directory; required
     * @return the new state
     */
    public FilePickerModel withDirectory(Path value) {
        return new FilePickerModel(
                value, root, entries, selected, top, rows, hidden, expanded, focused, epoch, request, truncated,
                notice);
    }

    /**
     * The same state with a different canonical root.
     *
     * @param value the root, or null for no boundary
     * @return the new state
     */
    public FilePickerModel withRoot(Path value) {
        return new FilePickerModel(
                directory, value, entries, selected, top, rows, hidden, expanded, focused, epoch, request, truncated,
                notice);
    }

    /**
     * The same state over different entries. The selection is kept by name where it survives.
     *
     * @param value the entries; null is empty
     * @return the new state
     */
    public FilePickerModel withEntries(List<Entry> value) {
        return new FilePickerModel(
                directory, root, value, selected, top, rows, hidden, expanded, focused, epoch, request, truncated,
                notice);
    }

    /**
     * The same state with a different selection.
     *
     * @param value the entry name to select; null or absent falls on the first shown entry
     * @return the new state
     */
    public FilePickerModel withSelected(String value) {
        return new FilePickerModel(
                directory, root, entries, value, top, rows, hidden, expanded, focused, epoch, request, truncated,
                notice);
    }

    /**
     * The same state at a different window offset.
     *
     * @param value the index of the first shown entry; negative is zero
     * @return the new state
     */
    public FilePickerModel withTop(int value) {
        return new FilePickerModel(
                directory, root, entries, selected, value, rows, hidden, expanded, focused, epoch, request, truncated,
                notice);
    }

    /**
     * The same state with a different height.
     *
     * @param value the rows the parent allocated, chrome included; negative is zero
     * @return this instance when the height did not change
     */
    public FilePickerModel withRows(int value) {
        int height = Math.max(0, value);
        return height == rows
                ? this
                : new FilePickerModel(
                        directory, root, entries, selected, top, height, hidden, expanded, focused, epoch, request,
                        truncated, notice);
    }

    /**
     * The same state showing or hiding hidden entries.
     *
     * @param value whether hidden entries are shown
     * @return this instance when the flag did not change
     */
    public FilePickerModel withHidden(boolean value) {
        return value == hidden
                ? this
                : new FilePickerModel(
                        directory, root, entries, selected, top, rows, value, expanded, focused, epoch, request,
                        truncated, notice);
    }

    /**
     * The same state painting help compactly or expanded.
     *
     * @param value whether help is expanded
     * @return this instance when the flag did not change
     */
    public FilePickerModel withExpanded(boolean value) {
        return value == expanded
                ? this
                : new FilePickerModel(
                        directory, root, entries, selected, top, rows, hidden, value, focused, epoch, request,
                        truncated, notice);
    }

    /**
     * The same state with a different focus.
     *
     * <p>A reduction never changes focus: the application owns it, exactly as it does for
     * {@link DataTable} and {@link RichList}.</p>
     *
     * @param value whether the picker owns focus
     * @return this instance when the focus did not change
     */
    public FilePickerModel withFocus(boolean value) {
        return value == focused
                ? this
                : new FilePickerModel(
                        directory, root, entries, selected, top, rows, hidden, expanded, value, epoch, request,
                        truncated, notice);
    }

    /**
     * The same state at a different request epoch.
     *
     * @param value the epoch; negative is zero
     * @return the new state
     */
    public FilePickerModel withEpoch(long value) {
        return new FilePickerModel(
                directory, root, entries, selected, top, rows, hidden, expanded, focused, value, request, truncated,
                notice);
    }

    /**
     * The same state with a different request.
     *
     * @param value the request; null is {@link Request#idle()}
     * @return the new state
     */
    public FilePickerModel withRequest(Request value) {
        return new FilePickerModel(
                directory, root, entries, selected, top, rows, hidden, expanded, focused, epoch, value, truncated,
                notice);
    }

    /**
     * The same state with a different truncation flag.
     *
     * @param value whether the listing was truncated
     * @return this instance when the flag did not change
     */
    public FilePickerModel withTruncated(boolean value) {
        return value == truncated
                ? this
                : new FilePickerModel(
                        directory, root, entries, selected, top, rows, hidden, expanded, focused, epoch, request, value,
                        notice);
    }

    /**
     * The same state with a different transient phrase.
     *
     * @param value the notice; null or blank clears it
     * @return this instance when the notice did not change
     */
    public FilePickerModel withNotice(String value) {
        String text = value == null ? "" : value;
        return text.equals(notice)
                ? this
                : new FilePickerModel(
                        directory, root, entries, selected, top, rows, hidden, expanded, focused, epoch, request,
                        truncated, text);
    }
}

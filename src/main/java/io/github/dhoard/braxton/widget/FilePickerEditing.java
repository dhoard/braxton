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

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.widget.FilePicker.Entry;
import io.github.dhoard.braxton.widget.FilePicker.Kind;
import io.github.dhoard.braxton.widget.FilePicker.Msg;
import io.github.dhoard.braxton.widget.FilePicker.Output;
import io.github.dhoard.braxton.widget.FilePicker.Reason;
import io.github.dhoard.braxton.widget.FilePicker.Request;
import io.github.dhoard.braxton.widget.FilePicker.Result;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Pure file navigation and asynchronous result transitions.
 */
final class FilePickerEditing {
    private final FilePickerConfig config;

    FilePickerEditing(FilePickerConfig config) {
        this.config = config;
    }

    /**
     * Applies one message. Never performs I/O, never mutates the input state.
     */
    Result reduce(FilePickerModel state, Msg message) {
        return switch (message) {
            case Msg.Up up -> moved(state, -1);
            case Msg.Down down -> moved(state, 1);
            case Msg.PageUp page -> movedBy(state, -FilePicker.contentRows(state, config));
            case Msg.PageDown page -> movedBy(state, FilePicker.contentRows(state, config));
            case Msg.First first -> movedTo(state, 0);
            case Msg.Last last -> movedTo(state, FilePicker.shown(state).size() - 1);
            case Msg.Open open -> opened(state);
            case Msg.Parent parent -> parented(state);
            case Msg.Confirm confirm -> confirmed(state);
            case Msg.Select select -> selectedTo(state, select.name());
            case Msg.Cancel cancel -> new Result(state, new Output.Cancelled(), Command.none());
            case Msg.ToggleHidden hidden -> hiddenToggled(state);
            case Msg.ToggleHelp help -> new Result(state.withExpanded(!state.expanded()), null, Command.none());
            case Msg.Reload reload -> navigate(state, state.directory(), state.selected());
            case Msg.RowsChanged rows -> new Result(state.withRows(rows.rows()), null, Command.none());
            case Msg.Listed listed -> listed(state, listed);
            case Msg.Failed failed -> failed(state, failed);
            case Msg.Validated validated -> validated(state, validated);
        };
    }

    /**
     * The state with the selection moved by entries, clamped to what is shown.
     */
    Result moved(FilePickerModel state, int delta) {
        int index = FilePicker.selectedIndex(state);
        return index < 0 ? Result.next(state) : movedTo(state, index + delta);
    }

    /**
     * The state with the entry at an index selected, clamped to what is shown.
     */
    Result movedTo(FilePickerModel state, int index) {
        List<Entry> shown = FilePicker.shown(state);
        if (shown.isEmpty()) {
            return Result.next(state);
        }
        int target = Math.max(0, Math.min(index, shown.size() - 1));
        return Result.next(state.withSelected(shown.get(target).name()));
    }

    /**
     * The state selecting one shown entry by name, or the incoming state when it is not shown.
     */
    Result selectedTo(FilePickerModel state, String name) {
        for (Entry entry : FilePicker.shown(state)) {
            if (entry.name().equals(name)) {
                return Result.next(state.withSelected(name));
            }
        }
        return Result.next(state);
    }

    /**
     * The state with the selection and the window moved by a page, which is what a page does.
     */
    Result movedBy(FilePickerModel state, int rows) {
        List<Entry> shown = FilePicker.shown(state);
        if (shown.isEmpty()) {
            return Result.next(state);
        }
        int current = Math.max(0, FilePicker.selectedIndex(state));
        int content = FilePicker.contentRows(state, config);
        int maxTop = Math.max(0, shown.size() - content);
        int target = Math.max(0, Math.min(current + rows, shown.size() - 1));
        int top = Math.max(0, Math.min(revealedTop(state.top(), current, shown.size(), content) + rows, maxTop));
        return Result.next(state.withSelected(shown.get(target).name()).withTop(top));
    }

    /**
     * Enters the selected directory, or reports why it cannot.
     */
    Result opened(FilePickerModel state) {
        Optional<Entry> entry = FilePicker.selected(state);
        if (entry.isEmpty()) {
            return Result.next(state.withNotice("nothing selected"));
        }
        if (!entry.get().navigable()) {
            return Result.next(state.withNotice(entry.get().name() + ": not a directory"));
        }
        return navigate(state, entry.get().path(), "");
    }

    /**
     * Reads the parent directory, selecting the directory the reader leaves.
     */
    Result parented(FilePickerModel state) {
        Path parent = parent(state);
        if (parent == null) {
            return Result.next(state.withNotice("at the root"));
        }
        Path name = state.directory().getFileName();
        return navigate(state, parent, name == null ? "" : name.toString());
    }

    /**
     * Chooses the selection, enters a directory, or reports why the entry cannot be chosen.
     */
    Result confirmed(FilePickerModel state) {
        Optional<Entry> entry = FilePicker.selected(state);
        if (entry.isEmpty()) {
            return Result.next(state.withNotice("nothing selected"));
        }
        Entry chosen = entry.get();
        if (chosen.selectable()) {
            // The directory can change between the listing and the choice, so the entry is checked
            // where the filesystem can still be read: in a task, never in this reduction.
            return new Result(state, null, FilePickerIo.validation(chosen.path(), state.epoch(), config));
        }
        if (chosen.navigable()) {
            return navigate(state, chosen.path(), "");
        }
        Reason reason = rejected(chosen);
        return new Result(
                state.withNotice(chosen.name() + ": " + reason.text()),
                new Output.Rejected(chosen.path(), reason),
                Command.none());
    }

    /**
     * Shows or hides hidden entries, keeping the selection on the nearest entry that stays.
     */
    Result hiddenToggled(FilePickerModel state) {
        FilePickerModel flipped = state.withHidden(!state.hidden());
        return Result.next(flipped.withSelected(nearest(flipped, state.selected())));
    }

    /**
     * Publishes a listing if the state is still waiting for it, and discards it otherwise.
     */
    Result listed(FilePickerModel state, Msg.Listed message) {
        if (message.epoch() != state.epoch()) {
            return Result.next(state);
        }
        Request request = state.request();
        String wanted = request instanceof Request.Loading loading ? loading.select() : "";
        FilePickerModel loaded = state.withDirectory(message.directory())
                .withRoot(message.root())
                .withEntries(message.entries())
                .withTruncated(message.truncated())
                .withRequest(Request.idle())
                .withNotice("")
                .withTop(0)
                .withSelected("");
        // The entry the request asked for wins, then the entry the reader had, then the first one
        // the new listing shows: navigating up from a directory lands on that directory's name.
        String resolved = wanted;
        if (resolved.isEmpty() || !shows(loaded, resolved)) {
            resolved = shows(loaded, state.selected()) ? state.selected() : "";
        }
        List<Entry> visible = FilePicker.shown(loaded);
        if (resolved.isEmpty() && !visible.isEmpty()) {
            // A listing with nothing to prefer selects what it shows first, so the state's own
            // selection agrees with the row the reader sees marked.
            resolved = visible.get(0).name();
        }
        FilePickerModel ready = loaded.withSelected(resolved);
        return Result.next(ready.withTop(revealedTop(
                0,
                FilePicker.selectedIndex(ready),
                FilePicker.shown(ready).size(),
                FilePicker.contentRows(ready, config))));
    }

    /**
     * Reports a failed request, keeping the last successful listing on screen.
     */
    Result failed(FilePickerModel state, Msg.Failed message) {
        if (message.epoch() != state.epoch()) {
            return Result.next(state);
        }
        return Result.next(state.withNotice("").withRequest(new Request.Failed(message.target(), message.message())));
    }

    /**
     * Reports the outcome of a confirmation check.
     */
    Result validated(FilePickerModel state, Msg.Validated message) {
        if (message.epoch() != state.epoch()) {
            return Result.next(state);
        }
        if (message.rejected() == null) {
            return new Result(state.withNotice(""), new Output.Chosen(message.path()), Command.none());
        }
        return new Result(
                state.withNotice(FilePickerRendering.name(message.path()) + ": "
                        + message.rejected().text()),
                new Output.Rejected(message.path(), message.rejected()),
                Command.none());
    }

    /**
     * The state with a request started for one directory, and the effect that answers it.
     */
    Result navigate(FilePickerModel state, Path target, String select) {
        long epoch = state.epoch() + 1;
        FilePickerModel loading = state.withEpoch(epoch)
                .withRequest(new Request.Loading(target, select))
                .withNotice("");
        return new Result(loading, null, FilePickerIo.listing(target, epoch, config));
    }

    /**
     * The directory above the current one, or null when there is none inside the boundary.
     *
     * <p>The boundary is the canonical root the first listing published, so a caller that names a
     * root cannot be walked past by a parent gesture - the comparison is between two canonical
     * paths and does not depend on how the caller spelled the root.</p>
     */
    static Path parent(FilePickerModel state) {
        Path parent = state.directory().getParent();
        if (parent == null) {
            return null;
        }
        return state.root() != null && !parent.startsWith(state.root()) ? null : parent;
    }

    /**
     * Why one entry cannot be chosen, from the facts the listing resolved.
     */
    Reason rejected(Entry entry) {
        return switch (entry.kind()) {
            case MISSING -> Reason.MISSING;
            case FILE -> config.selects(Kind.FILE) ? Reason.EXTENSION : Reason.KIND;
            case DIRECTORY -> Reason.LINK;
            case OTHER -> Reason.KIND;
        };
    }

    /**
     * The nearest entry to a name that the state shows: the name itself when it survives.
     */
    static String nearest(FilePickerModel state, String name) {
        List<Entry> shown = FilePicker.shown(state);
        if (shown.isEmpty()) {
            return "";
        }
        if (shows(state, name)) {
            return name;
        }
        List<Entry> entries = state.entries();
        int index = indexOf(entries, name);
        for (int step = 1; index >= 0 && step <= entries.size(); step++) {
            int after = index + step;
            int before = index - step;
            if (after < entries.size() && visible(state, entries.get(after))) {
                return entries.get(after).name();
            }
            if (before >= 0 && visible(state, entries.get(before))) {
                return entries.get(before).name();
            }
        }
        return shown.get(0).name();
    }

    /**
     * Whether a state shows an entry with this name.
     */
    static boolean shows(FilePickerModel state, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (Entry entry : FilePicker.shown(state)) {
            if (entry.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a state would show one entry.
     */
    static boolean visible(FilePickerModel state, Entry entry) {
        return state.hidden() || !entry.name().startsWith(".");
    }

    /**
     * The index of an entry name in a list, or -1.
     */
    static int indexOf(List<Entry> entries, String name) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).name().equals(name)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The window offset that keeps a selection visible inside a content height.
     */
    static int revealedTop(int top, int selected, int count, int content) {
        if (count <= 0 || content <= 0) {
            return 0;
        }
        int max = Math.max(0, count - content);
        int offset = Math.max(0, Math.min(top, max));
        if (selected < 0) {
            return offset;
        }
        if (selected < offset) {
            return selected;
        }
        return selected >= offset + content ? Math.min(max, selected - content + 1) : offset;
    }
}

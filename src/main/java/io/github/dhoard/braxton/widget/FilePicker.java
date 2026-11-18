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
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A directory browser over application-owned paths: entries with their metadata, a bounded window,
 * directory navigation and one explicit choice.
 *
 * <p>The picker is a component with nested immutable {@link Model}, sealed {@link Msg}/{@link
 * Output} and immutable {@link Config}. Its state is the directory whose entries it shows, the
 * entries themselves, the selection and the window - never a handle, a stream or a directory
 * iterator. Reading a directory and re-checking a chosen entry are <em>task</em> effects: {@link
 * #init} and {@link #update} return an inert {@link Command} and perform no filesystem I/O and no
 * clock read, the work runs on the runtime's effect executor, and its result arrives as an ordinary
 * message. A view is a pure function of the model, so a headless test paints the same rows a
 * terminal does.</p>
 *
 * <p><b>One request at a time.</b> Every listing is registered under one replaceable {@link
 * EffectId} and carries the model's request epoch. Registering the next listing replaces the
 * previous registration, so a superseded result cannot reach the model, and the epoch is checked
 * again where the result arrives, so a message that was already queued when the request was
 * superseded is discarded too. Rapid navigation therefore cannot publish the directory the reader
 * has left.</p>
 *
 * <p><b>One directory on screen.</b> {@link Model#directory()} is the directory the entries belong
 * to and the path line paints it, so the path and the rows always describe the same listing. A
 * request in flight and a failed one are {@link Request} values: a failure keeps the last successful
 * listing on screen and reports a retryable error in the status row instead of replacing the
 * entries with an error page.</p>
 *
 * <p><b>What can be chosen.</b> An {@link Entry} carries whether it is {@linkplain
 * Entry#navigable() navigable} (a directory the picker may open) and whether it is {@linkplain
 * Entry#selectable() selectable} (an entry {@link Msg.Confirm} chooses). Both are decided where the
 * directory is read, from the configuration and from the canonical paths involved: following a
 * symlink is opt-out, an optional {@linkplain Config#root() root} rejects canonical escapes, and a
 * link that resolves to the directory itself or to one of its ancestors is a cycle and is not
 * navigable. A selectable entry is re-checked when it is confirmed, because a directory can change
 * between the listing and the choice; the check reads the entry's type and never its contents.</p>
 *
 * <p><b>What is painted.</b> The picker paints its own chrome - one path row, the entry rows, one
 * status row and the {@link Help} lines of {@link #keys(Model)} - inside the rows it was allocated,
 * so {@link Model#rows()} is what the parent gave it and {@link #contentRows} is what is left for
 * entries. Entries are painted one row each, in the marker-and-name style {@link RichList} uses, so
 * the two widgets read as one family; {@link Theme} decides the styles, and the selection is merged
 * over whatever a theme returns.</p>
 *
 * <p>Out of scope by design: filtering and searching (an application narrows the entries by
 * sending {@link Msg.Listed} with what it read, and narrows a directory by configuring {@link
 * Config#extensions()}), creating, renaming and deleting files, file contents, multi-select and
 * persisted state. The picker never writes, never creates a directory and never follows a link
 * outside the configured root.</p>
 *
 * <h2>Pointer input</h2>
 *
 * <p>A click selects the entry painted on that row ({@link Msg.Select}), and a second click acts on
 * it the way the confirm gesture does - entering a directory ({@link Msg.Open}) or choosing a file
 * ({@link Msg.Confirm}). The row is resolved through the same window and the same chrome the frame
 * was painted from, so a click on the path row, the status row or the help selects nothing, and the
 * wheel steps the selection.</p>
 */
public final class FilePicker implements Component<FilePicker.Model, FilePicker.Msg, FilePicker.Output> {

    /** The rows a picker paints before the terminal reports its own geometry. */
    public static final int DEFAULT_ROWS = 10;

    /** The entries one listing keeps before it reports that it was truncated. */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** Marks the selected entry; the same marker {@link RichList} paints. */
    public static final String MARKER = RichList.MARKER;

    /** Painted in place of the marker. */
    public static final String MARKER_GAP = RichList.MARKER_GAP;

    /** Separates the parts of the status row. */
    public static final String STATUS_SEPARATOR = RichList.STATUS_SEPARATOR;

    /** Ends a directory name, so a directory cannot be mistaken for a file. */
    public static final String DIRECTORY_SUFFIX = "/";

    /** Ends a path or a name cut by the available width. */
    public static final String ELLIPSIS = "\u2026";

    /** Two spaces between an entry's name and its metadata. */
    private static final String DETAIL_GAP = "  ";

    /** Shown when the directory holds nothing. */
    private static final String EMPTY = "empty directory";

    /** The style a theme's null answer is read as. */
    private static final Style NO_STYLE = Style.NONE;

    /** The width help is measured at: it truncates, so its row count never depends on the width. */
    private static final int HELP_WIDTH = 400;

    /** One replaceable registration: the directory being read. */
    private static final EffectId LISTING = EffectId.of("listing");

    /** One replaceable registration: the entry being re-checked before it is chosen. */
    private static final EffectId VALIDATION = EffectId.of("validation");

    private static final ElementId ELEMENT = ElementId.of("file-picker");

    private static final SubscriptionId SIZE = SubscriptionId.of("size");

    /** Directories first, then names case-insensitively, with the code points breaking a tie. */
    private static final Comparator<Entry> ORDER = Comparator.comparing((Entry entry) -> entry.kind() != Kind.DIRECTORY)
            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::name);

    /** What an entry is, as the picker resolves it by following a link. */
    public enum Kind {

        /** A directory: navigable, and selectable when the definition selects directories. */
        DIRECTORY,

        /** A regular file: selectable when the definition selects files and allows its extension. */
        FILE,

        /** Anything else - a fifo, a socket, a device: listed, never chosen. */
        OTHER,

        /** An entry the picker could not read: a dangling link, or a target it may not stat. */
        MISSING
    }

    /** Why a confirmation was refused. */
    public enum Reason {

        /** The file's extension is not one the definition allows. */
        EXTENSION("extension not allowed"),

        /** The link is not followed, or it points outside the root or back up the tree. */
        LINK("link not usable"),

        /** The entry is not a kind the definition selects. */
        KIND("not a selectable entry"),

        /** The entry was gone, or no longer readable, when the choice was checked. */
        MISSING("no longer there");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        /**
         * The reason as one status-row phrase.
         *
         * @return the phrase
         */
        public String text() {
            return text;
        }
    }

    /**
     * One entry of a directory, as the listing resolved it.
     *
     * <p>Everything here is data a listing already read: no accessor performs I/O, so a view, a
     * test and a parent all see the same facts. {@code size} is the target's size for a regular
     * file and -1 for anything else, {@code permissions} is the nine-character POSIX string or
     * empty where the filesystem has none, and {@code target} is a symlink's own text or empty.</p>
     *
     * @param path the entry's path: the directory it was listed from, resolved with its name
     * @param kind what the entry is, after following a link
     * @param size the size in bytes for a regular file, or -1 when it does not apply
     * @param permissions the POSIX permission string, or empty when the filesystem has none
     * @param target a symlink's target text, or empty when the entry is not a link
     * @param selectable whether {@link Msg.Confirm} chooses this entry
     * @param navigable whether {@link Msg.Open} enters this entry
     */
    public record Entry(
            Path path, Kind kind, long size, String permissions, String target, boolean selectable, boolean navigable) {

        /** Keeps the entry representable: only a directory is ever navigable. */
        public Entry {
            Objects.requireNonNull(path, "path");
            kind = kind == null ? Kind.OTHER : kind;
            size = Math.max(-1L, size);
            permissions = permissions == null ? "" : permissions;
            target = target == null ? "" : target;
            navigable = navigable && kind == Kind.DIRECTORY;
        }

        /**
         * The entry's name: the last segment of its path.
         *
         * @return the file name
         */
        public String name() {
            Path name = path.getFileName();
            return name == null ? path.toString() : name.toString();
        }

        /**
         * Whether the entry is a symlink.
         *
         * @return whether a target was read for it
         */
        public boolean link() {
            return !target.isEmpty();
        }

        /**
         * Whether the entry is a directory.
         *
         * @return whether the resolved kind is a directory
         */
        public boolean directory() {
            return kind == Kind.DIRECTORY;
        }

        /**
         * The metadata column one row paints for this entry.
         *
         * <p>It is the permission string, a size or a kind, and the link's target, in that order,
         * separated by two spaces: {@code rw-r--r--  1.4K  -> ../notes.txt}. A part that does not
         * apply is absent rather than blank, so an entry on a filesystem without permissions reads
         * {@code 1.4K} and a directory reads {@code dir}.</p>
         *
         * @return the metadata text, empty when the entry has no metadata to paint
         */
        public String detail() {
            List<String> parts = new ArrayList<>(3);
            if (!permissions.isEmpty()) {
                parts.add(permissions);
            }
            switch (kind) {
                case FILE -> parts.add(bytes(size));
                case DIRECTORY -> parts.add("dir");
                case MISSING -> parts.add("missing");
                case OTHER -> parts.add("special");
            }
            if (!target.isEmpty()) {
                parts.add("-> " + target);
            }
            return String.join(DETAIL_GAP, parts);
        }

        /**
         * A byte count as a short fixed-point size.
         *
         * <p>Below one kibibyte it is the count and {@code B}; above it, the count in the largest
         * unit that keeps it under 1024, with one decimal place. The rendering is locale-independent
         * by design: it is painted into a terminal that a test asserts against.</p>
         *
         * @param count the byte count; negative is empty
         * @return the rendered size
         */
        public static String bytes(long count) {
            if (count < 0) {
                return "";
            }
            if (count < 1024) {
                return count + "B";
            }
            String[] units = {"K", "M", "G", "T", "P", "E"};
            double value = count;
            int unit = -1;
            while (value >= 1024 && unit < units.length - 1) {
                value /= 1024;
                unit++;
            }
            return String.format(Locale.ROOT, "%.1f%s", value, units[unit]);
        }
    }

    /**
     * What the picker is waiting for, and what it last failed to read.
     *
     * <p>It is the picker's whole asynchronous state: {@link Idle} is a directory on screen with
     * nothing in flight, {@link Loading} is a directory being read, and {@link Failed} is a request
     * that ended in an error while the last successful listing stayed on screen.</p>
     */
    public sealed interface Request {

        /**
         * Nothing in flight, and no failure to report.
         *
         * @return the idle request
         */
        static Request idle() {
            return new Idle();
        }

        /** Nothing in flight. */
        record Idle() implements Request {}

        /**
         * A directory being read.
         *
         * @param target the directory the picker asked for
         * @param select the entry name to select when the listing arrives, or empty for the first
         */
        record Loading(Path target, String select) implements Request {

            /** Rejects a missing target. */
            public Loading {
                Objects.requireNonNull(target, "target");
                select = select == null ? "" : select;
            }
        }

        /**
         * A request that failed; the previous listing stayed on screen.
         *
         * @param target the directory the picker asked for
         * @param message what went wrong, as one status-row phrase
         */
        record Failed(Path target, String message) implements Request {

            /** Rejects a missing target and defaults a missing message. */
            public Failed {
                Objects.requireNonNull(target, "target");
                message = message == null || message.isBlank() ? "cannot read the directory" : message;
            }
        }
    }

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
    public record Model(
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

        /** Keeps the state representable and every value non-null where it must be. */
        public Model {
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
        public static Model of(Path directory) {
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
        public static Model of(Path directory, List<Entry> entries) {
            List<Entry> values = entries == null ? List.of() : List.copyOf(entries);
            return new Model(
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
        public Model withDirectory(Path value) {
            return new Model(
                    value, root, entries, selected, top, rows, hidden, expanded, focused, epoch, request, truncated,
                    notice);
        }

        /**
         * The same state with a different canonical root.
         *
         * @param value the root, or null for no boundary
         * @return the new state
         */
        public Model withRoot(Path value) {
            return new Model(
                    directory, value, entries, selected, top, rows, hidden, expanded, focused, epoch, request,
                    truncated, notice);
        }

        /**
         * The same state over different entries. The selection is kept by name where it survives.
         *
         * @param value the entries; null is empty
         * @return the new state
         */
        public Model withEntries(List<Entry> value) {
            return new Model(
                    directory, root, value, selected, top, rows, hidden, expanded, focused, epoch, request, truncated,
                    notice);
        }

        /**
         * The same state with a different selection.
         *
         * @param value the entry name to select; null or absent falls on the first shown entry
         * @return the new state
         */
        public Model withSelected(String value) {
            return new Model(
                    directory, root, entries, value, top, rows, hidden, expanded, focused, epoch, request, truncated,
                    notice);
        }

        /**
         * The same state at a different window offset.
         *
         * @param value the index of the first shown entry; negative is zero
         * @return the new state
         */
        public Model withTop(int value) {
            return new Model(
                    directory, root, entries, selected, value, rows, hidden, expanded, focused, epoch, request,
                    truncated, notice);
        }

        /**
         * The same state with a different height.
         *
         * @param value the rows the parent allocated, chrome included; negative is zero
         * @return this instance when the height did not change
         */
        public Model withRows(int value) {
            int height = Math.max(0, value);
            return height == rows
                    ? this
                    : new Model(
                            directory, root, entries, selected, top, height, hidden, expanded, focused, epoch, request,
                            truncated, notice);
        }

        /**
         * The same state showing or hiding hidden entries.
         *
         * @param value whether hidden entries are shown
         * @return this instance when the flag did not change
         */
        public Model withHidden(boolean value) {
            return value == hidden
                    ? this
                    : new Model(
                            directory, root, entries, selected, top, rows, value, expanded, focused, epoch, request,
                            truncated, notice);
        }

        /**
         * The same state painting help compactly or expanded.
         *
         * @param value whether help is expanded
         * @return this instance when the flag did not change
         */
        public Model withExpanded(boolean value) {
            return value == expanded
                    ? this
                    : new Model(
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
        public Model withFocus(boolean value) {
            return value == focused
                    ? this
                    : new Model(
                            directory, root, entries, selected, top, rows, hidden, expanded, value, epoch, request,
                            truncated, notice);
        }

        /**
         * The same state at a different request epoch.
         *
         * @param value the epoch; negative is zero
         * @return the new state
         */
        public Model withEpoch(long value) {
            return new Model(
                    directory, root, entries, selected, top, rows, hidden, expanded, focused, value, request, truncated,
                    notice);
        }

        /**
         * The same state with a different request.
         *
         * @param value the request; null is {@link Request#idle()}
         * @return the new state
         */
        public Model withRequest(Request value) {
            return new Model(
                    directory, root, entries, selected, top, rows, hidden, expanded, focused, epoch, value, truncated,
                    notice);
        }

        /**
         * The same state with a different truncation flag.
         *
         * @param value whether the listing was truncated
         * @return this instance when the flag did not change
         */
        public Model withTruncated(boolean value) {
            return value == truncated
                    ? this
                    : new Model(
                            directory, root, entries, selected, top, rows, hidden, expanded, focused, epoch, request,
                            value, notice);
        }

        /**
         * The same state with a different transient phrase.
         *
         * @param value the notice; null or blank clears it
         * @return this instance when the notice did not change
         */
        public Model withNotice(String value) {
            String text = value == null ? "" : value;
            return text.equals(notice)
                    ? this
                    : new Model(
                            directory, root, entries, selected, top, rows, hidden, expanded, focused, epoch, request,
                            truncated, text);
        }
    }

    /** One navigation, choice or listing message. */
    public sealed interface Msg {

        /** Selects the entry above, revealing it. */
        record Up() implements Msg {}

        /** Selects the entry below, revealing it. */
        record Down() implements Msg {}

        /** Selects the entry one window above, revealing it. */
        record PageUp() implements Msg {}

        /** Selects the entry one window below, revealing it. */
        record PageDown() implements Msg {}

        /** Selects the first shown entry. */
        record First() implements Msg {}

        /** Selects the last shown entry. */
        record Last() implements Msg {}

        /** Enters the selected entry when it is a navigable directory. */
        record Open() implements Msg {}

        /** Reads the parent of the current directory, selecting the directory it leaves. */
        record Parent() implements Msg {}

        /** Chooses the selected entry, enters it when it is a directory, or reports why it cannot. */
        record Confirm() implements Msg {}

        /**
         * Selects one entry, by name.
         *
         * <p>This is what a click on an entry row means. A name the listing does not show leaves the
         * selection where it is, so a stale click cannot select nothing.</p>
         *
         * @param name the entry's name; null names no entry
         */
        record Select(String name) implements Msg {

            /** Rejects no name; a missing one names no entry. */
            public Select {
                name = name == null ? "" : name;
            }
        }

        /** Reports that the reader left the picker without choosing. */
        record Cancel() implements Msg {}

        /** Shows or hides hidden entries, keeping the selection when it stays visible. */
        record ToggleHidden() implements Msg {}

        /** Switches help between its compact line and its grouped listing. */
        record ToggleHelp() implements Msg {}

        /** Re-reads the directory whose entries are shown. */
        record Reload() implements Msg {}

        /**
         * Publishes a new height for the whole picker.
         *
         * @param rows the rows the parent allocated, chrome included
         */
        record RowsChanged(int rows) implements Msg {}

        /**
         * Replaces the entries with a listing the application produced itself.
         *
         * <p>It is the message a listing task produces, and the message an application sends when
         * it already knows the directory's contents. The request epoch decides whether the state
         * is waiting for it.</p>
         *
         * @param epoch the request the listing answers
         * @param directory the canonical directory the entries belong to
         * @param root the canonical root enforced for that directory, or null when there is none
         * @param entries the entries; null is empty
         * @param truncated whether the listing stopped at its maximum
         */
        record Listed(long epoch, Path directory, Path root, List<Entry> entries, boolean truncated) implements Msg {

            /** Rejects a missing directory and keeps a null list empty. */
            public Listed {
                Objects.requireNonNull(directory, "directory");
                entries = entries == null ? List.of() : List.copyOf(entries);
            }
        }

        /**
         * Reports that a request failed.
         *
         * @param epoch the request that failed
         * @param target the directory the picker asked for
         * @param message what went wrong, as one status-row phrase
         */
        record Failed(long epoch, Path target, String message) implements Msg {

            /** Rejects a missing target and defaults an empty message. */
            public Failed {
                Objects.requireNonNull(target, "target");
                message = message == null || message.isBlank() ? "cannot read the directory" : message;
            }
        }

        /**
         * Reports the re-check of an entry that was confirmed.
         *
         * @param epoch the request the check belongs to
         * @param path the entry that was checked
         * @param rejected why it cannot be chosen, or null when the choice stands
         */
        record Validated(long epoch, Path path, Reason rejected) implements Msg {

            /** Rejects a missing path. */
            public Validated {
                Objects.requireNonNull(path, "path");
            }
        }
    }

    /** What the application should do about a choice. */
    public sealed interface Output {

        /**
         * The reader chose an entry, and the entry was still what it was listed as.
         *
         * @param path the chosen path
         */
        record Chosen(Path path) implements Output {

            /** Rejects a missing path. */
            public Chosen {
                Objects.requireNonNull(path, "path");
            }
        }

        /**
         * The reader confirmed an entry that cannot be chosen.
         *
         * @param path the entry's path
         * @param reason why it cannot be chosen
         */
        record Rejected(Path path, Reason reason) implements Output {

            /** Rejects a missing path or reason. */
            public Rejected {
                Objects.requireNonNull(path, "path");
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** The reader left the picker without choosing anything. */
        record Cancelled() implements Output {}
    }

    /**
     * How a picker paints: the styles of an entry's name and metadata, and of the picker's own rows.
     *
     * <p>A callback is a pure function of the facts it is given and runs only for what is painted:
     * {@link #name} and {@link #detail} once per shown entry row, never for a row outside the
     * window. The selection is merged over whatever a theme returns, so what is selected stays
     * visible whatever the theme says.</p>
     */
    public interface Theme {

        /**
         * The style of one entry's name.
         *
         * @param entry the entry being painted
         * @param selected whether it is the selection
         * @param focused whether the picker owns focus
         * @return the style; null or a plain style paints nothing extra
         */
        default Style name(Entry entry, boolean selected, boolean focused) {
            return NO_STYLE;
        }

        /**
         * The style of one entry's metadata column.
         *
         * @param entry the entry being painted
         * @param selected whether it is the selection
         * @param focused whether the picker owns focus
         * @return the style; null or a plain style paints nothing extra
         */
        default Style detail(Entry entry, boolean selected, boolean focused) {
            return NO_STYLE;
        }

        /**
         * The style of the path row.
         *
         * @return the style; null or a plain style paints nothing extra
         */
        default Style path() {
            return NO_STYLE;
        }

        /**
         * The style of the counts in the status row.
         *
         * @return the style; null or a plain style paints nothing extra
         */
        default Style status() {
            return NO_STYLE;
        }

        /**
         * The style of a transient phrase in the status row.
         *
         * @return the style; null or a plain style paints nothing extra
         */
        default Style notice() {
            return NO_STYLE;
        }

        /**
         * The style of a failed request in the status row.
         *
         * @return the style; null or a plain style paints nothing extra
         */
        default Style error() {
            return NO_STYLE;
        }

        /**
         * A theme that adds nothing.
         *
         * @return the plain theme
         */
        static Theme plain() {
            return new Theme() {};
        }

        /**
         * A theme from two fixed styles.
         *
         * @param name the entries' name style
         * @param detail the entries' metadata style
         * @return the theme
         */
        static Theme of(Style name, Style detail) {
            Style names = name == null ? NO_STYLE : name;
            Style details = detail == null ? NO_STYLE : detail;
            return new Theme() {
                @Override
                public Style name(Entry entry, boolean selected, boolean focused) {
                    return names;
                }

                @Override
                public Style detail(Entry entry, boolean selected, boolean focused) {
                    return details;
                }
            };
        }
    }

    /**
     * Immutable picker definition.
     *
     * <p>It holds what does not change while the picker runs: the directory it opens, the boundary
     * it enforces, what may be chosen and how it is painted. The default directory is the empty
     * path, which names the process's current directory; a picker is normally given an explicit
     * one.</p>
     *
     * @param directory the directory the picker opens; the empty path names the current directory
     * @param root the directory no navigation may leave, or null for no boundary
     * @param extensions the allowed file extensions, normalized to lower case with a leading dot; an
     *     empty set allows every regular file
     * @param selectable the kinds {@link Msg.Confirm} chooses
     * @param followLinks whether a symlink is followed; a link that is not followed is listed but
     *     neither navigable nor selectable
     * @param hidden whether hidden entries are shown at first
     * @param maxEntries the entries one listing keeps before it reports truncation; at least one
     * @param rows the rows used before the terminal's own geometry arrives
     * @param reservedRows the rows the application paints outside the picker
     * @param theme how entries and the picker's own rows are painted
     * @param activeStyle the style merged over the selection while the picker is focused
     * @param inactiveStyle the style merged over the selection while it is not
     */
    public record Config(
            Path directory,
            Path root,
            Set<String> extensions,
            Set<Kind> selectable,
            boolean followLinks,
            boolean hidden,
            int maxEntries,
            int rows,
            int reservedRows,
            Theme theme,
            Style activeStyle,
            Style inactiveStyle) {

        /** Keeps the definition usable: every value is normalized here, once. */
        public Config {
            directory = directory == null ? Path.of("") : directory;
            extensions = normalize(extensions);
            selectable = selectable == null || selectable.isEmpty() ? Set.of(Kind.FILE) : Set.copyOf(selectable);
            maxEntries = Math.max(1, maxEntries);
            rows = Math.max(0, rows);
            reservedRows = Math.max(0, reservedRows);
            theme = theme == null ? Theme.plain() : theme;
            activeStyle = activeStyle == null ? NO_STYLE : activeStyle;
            inactiveStyle = inactiveStyle == null ? NO_STYLE : inactiveStyle;
        }

        /**
         * A picker over the process's current directory that chooses any regular file.
         *
         * @return the default definition
         */
        public static Config defaults() {
            return new Config(
                    Path.of(""),
                    null,
                    Set.of(),
                    Set.of(Kind.FILE),
                    true,
                    false,
                    DEFAULT_MAX_ENTRIES,
                    DEFAULT_ROWS,
                    0,
                    Theme.plain(),
                    NO_STYLE.withInvert(),
                    NO_STYLE);
        }

        /**
         * The same definition opening a different directory.
         *
         * @param value the directory; null is the current directory
         * @return the new definition
         */
        public Config withDirectory(Path value) {
            return new Config(
                    value,
                    root,
                    extensions,
                    selectable,
                    followLinks,
                    hidden,
                    maxEntries,
                    rows,
                    reservedRows,
                    theme,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition with a different boundary.
         *
         * @param value the root no navigation may leave, or null for none
         * @return the new definition
         */
        public Config withRoot(Path value) {
            return new Config(
                    directory,
                    value,
                    extensions,
                    selectable,
                    followLinks,
                    hidden,
                    maxEntries,
                    rows,
                    reservedRows,
                    theme,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition allowing a different set of extensions.
         *
         * @param value the extensions, with or without a leading dot; an empty set allows every file
         * @return the new definition
         */
        public Config withExtensions(Set<String> value) {
            return new Config(
                    directory,
                    root,
                    value,
                    selectable,
                    followLinks,
                    hidden,
                    maxEntries,
                    rows,
                    reservedRows,
                    theme,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition allowing the given extensions.
         *
         * @param value the extensions, with or without a leading dot
         * @return the new definition
         */
        public Config withExtensions(String... value) {
            return withExtensions(value == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(value)));
        }

        /**
         * The same definition choosing a different set of kinds.
         *
         * @param value the kinds a confirmation chooses; empty selects regular files
         * @return the new definition
         */
        public Config withSelectable(Set<Kind> value) {
            return new Config(
                    directory,
                    root,
                    extensions,
                    value,
                    followLinks,
                    hidden,
                    maxEntries,
                    rows,
                    reservedRows,
                    theme,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition choosing the given kinds.
         *
         * @param value the kinds a confirmation chooses
         * @return the new definition
         */
        public Config withSelectable(Kind... value) {
            return withSelectable(value == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(value)));
        }

        /**
         * The same definition following, or not following, symlinks.
         *
         * @param value whether a symlink is followed
         * @return the new definition
         */
        public Config withFollowLinks(boolean value) {
            return new Config(
                    directory,
                    root,
                    extensions,
                    selectable,
                    value,
                    hidden,
                    maxEntries,
                    rows,
                    reservedRows,
                    theme,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition showing, or hiding, hidden entries at first.
         *
         * @param value whether hidden entries are shown
         * @return the new definition
         */
        public Config withHidden(boolean value) {
            return new Config(
                    directory,
                    root,
                    extensions,
                    selectable,
                    followLinks,
                    value,
                    maxEntries,
                    rows,
                    reservedRows,
                    theme,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition with a different listing bound.
         *
         * @param value the entries one listing keeps; at least one
         * @return the new definition
         */
        public Config withMaxEntries(int value) {
            return new Config(
                    directory,
                    root,
                    extensions,
                    selectable,
                    followLinks,
                    hidden,
                    value,
                    rows,
                    reservedRows,
                    theme,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition with a different fallback height.
         *
         * @param value the fallback rows
         * @return the new definition
         */
        public Config withRows(int value) {
            return new Config(
                    directory,
                    root,
                    extensions,
                    selectable,
                    followLinks,
                    hidden,
                    maxEntries,
                    value,
                    reservedRows,
                    theme,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition with a different number of reserved rows.
         *
         * @param value the rows the application paints outside the picker
         * @return the new definition
         */
        public Config withReservedRows(int value) {
            return new Config(
                    directory,
                    root,
                    extensions,
                    selectable,
                    followLinks,
                    hidden,
                    maxEntries,
                    rows,
                    value,
                    theme,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition with a different theme.
         *
         * @param value the theme; null is {@link Theme#plain()}
         * @return the new definition
         */
        public Config withTheme(Theme value) {
            return new Config(
                    directory,
                    root,
                    extensions,
                    selectable,
                    followLinks,
                    hidden,
                    maxEntries,
                    rows,
                    reservedRows,
                    value,
                    activeStyle,
                    inactiveStyle);
        }

        /**
         * The same definition with different selection styles.
         *
         * @param active the style merged over a focused picker's selection
         * @param inactive the style merged over an unfocused picker's selection
         * @return the new definition
         */
        public Config withSelectionStyles(Style active, Style inactive) {
            return new Config(
                    directory,
                    root,
                    extensions,
                    selectable,
                    followLinks,
                    hidden,
                    maxEntries,
                    rows,
                    reservedRows,
                    theme,
                    active,
                    inactive);
        }

        /**
         * Whether the definition allows a file called this.
         *
         * <p>The extension is the text after the last dot, compared case-insensitively. A name with
         * no dot in it, or whose only dot is its first character (a dot file), has no extension and
         * is allowed only when the definition allows every file.</p>
         *
         * @param fileName the file name to check; null has no extension
         * @return whether the name is allowed
         */
        public boolean allows(String fileName) {
            if (extensions.isEmpty()) {
                return true;
            }
            if (fileName == null) {
                return false;
            }
            int dot = fileName.lastIndexOf('.');
            return dot > 0
                    && dot < fileName.length() - 1
                    && extensions.contains(fileName.substring(dot).toLowerCase(Locale.ROOT));
        }

        /**
         * Whether the definition chooses entries of a kind.
         *
         * @param kind the kind to check
         * @return whether a confirmation may choose it
         */
        public boolean selects(Kind kind) {
            return selectable.contains(kind);
        }

        /** Normalizes extensions to lower case with a leading dot, dropping the empty ones. */
        private static Set<String> normalize(Set<String> extensions) {
            if (extensions == null || extensions.isEmpty()) {
                return Set.of();
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String extension : extensions) {
                if (extension == null || extension.isBlank()) {
                    continue;
                }
                String value = extension.trim().toLowerCase(Locale.ROOT);
                normalized.add(value.startsWith(".") ? value : "." + value);
            }
            return Set.copyOf(normalized);
        }
    }

    /** One reduction: the next state, the output it reports, and the effect it asks for. */
    private record Result(Model state, Output intent, Command<Msg> command) {

        /** A reduction that asks for nothing. */
        static Result next(Model state) {
            return new Result(state, null, Command.none());
        }
    }

    /** What one listing task produced. */
    private record Listing(long epoch, Path directory, Path root, List<Entry> entries, boolean truncated) {}

    private final Config config;

    /**
     * A picker from its definition.
     *
     * @param config the immutable definition; required
     */
    public FilePicker(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * The definition this picker was built from.
     *
     * @return the definition
     */
    public Config config() {
        return config;
    }

    /**
     * The gestures a picker defines, before its state is taken into account.
     *
     * <p>{@link #keys(Model)} is the map for one state - it drops {@code open} when the selection is
     * not a directory and {@code parent} when there is nowhere above the boundary to go - and it is
     * what the component routes and what its help renders.</p>
     *
     * @return the default gestures
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Move",
                        KeyAction.<Msg>of("up", new Msg.Up(), "\u2191/k", "up", "up", "k"),
                        KeyAction.<Msg>of("down", new Msg.Down(), "\u2193/j", "down", "down", "j")),
                KeyMap.group(
                        "Page",
                        // A compact line is what the reader sees without asking for help, so it keeps the
                        // gestures a file picker lives on: the move, the way in, the way out and the choice.
                        KeyAction.<Msg>of("page-up", new Msg.PageUp(), "b/pgup", "page up", "pageup", "b")
                                .withCompact(false),
                        KeyAction.<Msg>of("page-down", new Msg.PageDown(), "f/pgdn", "page down", "pagedown", "f")
                                .withCompact(false),
                        KeyAction.<Msg>of("first", new Msg.First(), "home", "first entry", "home")
                                .withCompact(false),
                        KeyAction.<Msg>of("last", new Msg.Last(), "end", "last entry", "end")
                                .withCompact(false)),
                KeyMap.group(
                        "Browse",
                        KeyAction.<Msg>of("open", new Msg.Open(), "\u2192/l", "open directory", "right", "l"),
                        KeyAction.<Msg>of(
                                "parent", new Msg.Parent(), "\u2190/h", "parent directory", "left", "h", "backspace")),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("confirm", new Msg.Confirm(), "enter", "choose", "enter"),
                        KeyAction.<Msg>of("hidden", new Msg.ToggleHidden(), ".", "hidden files", ".")
                                .withCompact(false),
                        KeyAction.<Msg>of("reload", new Msg.Reload(), "r", "reload", "r")
                                .withCompact(false)),
                KeyMap.group(
                        "View",
                        KeyAction.<Msg>of("help", new Msg.ToggleHelp(), "?", "full help", "?")
                                .withCompact(false)),
                KeyMap.group("General", KeyAction.<Msg>of("cancel", new Msg.Cancel(), "esc", "cancel", "escape")));
    }

    /**
     * The gestures one state handles.
     *
     * <p>Help and routing read the same map, so a gesture help shows is a gesture that fires: an
     * empty directory has nothing to select, the selected entry decides whether {@code open} means
     * anything, and {@code parent} disappears at the boundary.</p>
     *
     * @param state the state the gestures are built for; required
     * @return the gestures
     */
    public static KeyMap<Msg> keys(Model state) {
        Objects.requireNonNull(state, "state");
        List<Entry> shown = shown(state);
        boolean entries = !shown.isEmpty();
        Optional<Entry> selected = selected(state);
        boolean navigable = selected.map(Entry::navigable).orElse(false);
        return defaultKeys()
                .withEnabled("up", entries)
                .withEnabled("down", entries)
                .withEnabled("page-up", entries)
                .withEnabled("page-down", entries)
                .withEnabled("first", entries)
                .withEnabled("last", entries)
                .withEnabled("open", navigable)
                .withEnabled("parent", parent(state) != null)
                // Confirming is available for every selection, not only a legal one: an entry that
                // cannot be chosen has to be able to say why, and silence is not an answer.
                .withEnabled("confirm", entries);
    }

    @Override
    public Update<Model, Msg, Output> init(InitContext context) {
        // The startup geometry is the only size the picker knows before a resize arrives: what the
        // application paints outside it is taken out of the terminal's own geometry, and the
        // configured height is the fallback a headless test or a preview uses.
        int rows = context.viewport().rows() > 0
                ? Math.max(0, context.viewport().rows() - config.reservedRows())
                : config.rows();
        Path directory = config.directory();
        Model state = new Model(
                directory,
                null,
                List.of(),
                "",
                0,
                rows,
                config.hidden(),
                false,
                true,
                1L,
                new Request.Loading(directory, ""),
                false,
                "");
        return Update.next(state, listing(directory, state.epoch(), config));
    }

    @Override
    public Update<Model, Msg, Output> update(Model model, Msg message) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(message, "message");
        Result result = reduce(model, message);
        // A reduction owns its effect: a confirmation asks for a check of the entry it was pressed
        // on, which is not a property of the state it leaves behind, so the command is never
        // suppressed. Only the model follows the rule every bundled widget keeps - a message that
        // changed nothing returns the incoming instance, so the runtime's unchanged-reference
        // repaint rule stays meaningful.
        Model reduced = result.state().withFocus(model.focused());
        Model next = reduced.equals(model) ? model : reduced;
        return result.intent() == null
                ? Update.next(next, result.command())
                : new Update<>(next, result.command(), List.of(result.intent()));
    }

    @Override
    public Subscription<Msg> subscriptions(Model model) {
        Objects.requireNonNull(model, "model");
        // One source: the terminal's height. The picker owns no timer, and a listing in flight is a
        // one-shot task whose registration the runtime cancels with the component.
        return Subscription.resize(SIZE, size -> new Msg.RowsChanged(Math.max(0, size.rows() - config.reservedRows())));
    }

    /**
     * Describes the picker.
     *
     * <p>The context is deliberately unused: its viewport is what the picker's parent lays out for,
     * not the picker's own geometry. {@link Model#rows()} is what the parent reserved and reported,
     * and it is the only geometry a window may use.</p>
     */
    @Override
    public View<Msg> view(Model model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return Views.focus(
                ELEMENT,
                model.focused(),
                keys(model).bindings(),
                View.InputHandler.none(),
                pointer(model),
                Views.sized(size -> paint(model, size.cols())));
    }

    /**
     * The picker's pointer handler: a click selects the entry row it landed on, a second click opens
     * a directory or chooses a file, and the wheel steps the selection.
     *
     * <p>The rows are the ones {@link #lines} paints: the path row first, then the window's entries
     * from {@link #top}, then the status and help rows. A click on the chrome, or past the last
     * entry, selects nothing.</p>
     */
    private View.PointerHandler<Msg> pointer(Model model) {
        return (event, region) -> {
            if (event.isWheel()) {
                return Optional.of(
                        switch (event.button()) {
                            case WHEEL_UP, WHEEL_LEFT -> new Msg.Up();
                            default -> new Msg.Down();
                        });
            }
            if (!event.isLeftPress()) {
                return Optional.empty();
            }
            Entry entry = entryAt(model, config, event.row());
            if (entry == null) {
                return Optional.empty();
            }
            if (event.clickCount() > 1) {
                // A second click acts on the row it names: a directory is entered, and a file is
                // chosen the way the confirm gesture chooses one.
                return Optional.of(entry.navigable() ? new Msg.Open() : new Msg.Confirm());
            }
            return Optional.of(new Msg.Select(entry.name()));
        };
    }

    /** The shown entry painted on one row of the picker, or null for a chrome or blank row. */
    private static Entry entryAt(Model state, Config config, int row) {
        Config opts = config == null ? Config.defaults() : config;
        int body = row - PATH_ROWS;
        List<Entry> shown = shown(state);
        if (body < 0 || body >= contentRows(state, opts)) {
            return null;
        }
        int index = top(state, opts) + body;
        return index < shown.size() ? shown.get(index) : null;
    }

    /** The rows painted above the entries: the path row. */
    private static final int PATH_ROWS = 1;

    // ── projections ──────────────────────────────────────────────────────────

    /**
     * The entries the picker shows, in paint order.
     *
     * <p>The listing keeps every entry it read, hidden ones included, so showing them is a
     * projection and not a second read: the toggle is instant, and a directory the caller may not
     * read is never read twice.</p>
     *
     * @param state the state to resolve; required
     * @return the shown entries, never null
     */
    public static List<Entry> shown(Model state) {
        Objects.requireNonNull(state, "state");
        if (state.hidden()) {
            return state.entries();
        }
        List<Entry> visible = new ArrayList<>(state.entries().size());
        for (Entry entry : state.entries()) {
            if (!entry.name().startsWith(".")) {
                visible.add(entry);
            }
        }
        return List.copyOf(visible);
    }

    /**
     * The index of the selected entry among the shown entries.
     *
     * <p>A selection whose name is not shown - it was hidden, it was replaced, or the state was
     * built by hand - falls on the first shown entry, so the painted selection and {@link
     * #selected(Model)} never disagree.</p>
     *
     * @param state the state to resolve; required
     * @return the index, or -1 when nothing is shown
     */
    public static int selectedIndex(Model state) {
        Objects.requireNonNull(state, "state");
        List<Entry> shown = shown(state);
        for (int index = 0; index < shown.size(); index++) {
            if (shown.get(index).name().equals(state.selected())) {
                return index;
            }
        }
        return shown.isEmpty() ? -1 : 0;
    }

    /**
     * The selected entry.
     *
     * @param state the state to resolve; required
     * @return the entry the selection falls on, or empty when nothing is shown
     */
    public static Optional<Entry> selected(Model state) {
        Objects.requireNonNull(state, "state");
        int index = selectedIndex(state);
        return index < 0 ? Optional.empty() : Optional.of(shown(state).get(index));
    }

    /**
     * The entry rows the window holds: the allocated rows less the picker's own.
     *
     * <p>The path row and the status row are always painted, and the help rows follow the map the
     * state routes, so a picker that expanded its help has that many rows fewer for entries.</p>
     *
     * @param state the state to resolve; required
     * @param config the definition the help of the state is measured with; null is
     *     {@link Config#defaults()}
     * @return the entry rows the window holds, never negative
     */
    public static int contentRows(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        return Math.max(0, state.rows() - chromeRows(state, opts));
    }

    /**
     * The index of the first shown entry the window paints.
     *
     * <p>The stored offset is clamped to the entries and then moved the least it can to keep the
     * selection visible, so this is where the window really starts however the state was built.</p>
     *
     * @param state the state to resolve; required
     * @param config the definition the window's height is read from; null is
     *     {@link Config#defaults()}
     * @return the first shown entry the window paints, never negative
     */
    public static int top(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        return revealedTop(state.top(), selectedIndex(state), shown(state).size(), contentRows(state, config));
    }

    /**
     * The rows the picker paints, in paint order.
     *
     * <p>The path row is first, the entry rows follow it, then the status row and the help rows, so
     * the rows a caller sees are the rows the terminal receives - already cut to {@code width} and
     * every entry already one row. The list is exactly {@link Model#rows()} long for a given width,
     * so a parent can lay the picker out without measuring it.</p>
     *
     * @param state the current state; required
     * @param config the definition whose theme, styles and extensions apply; null is
     *     {@link Config#defaults()}
     * @param width the display columns the picker was allocated; zero or less paints blank rows
     * @return the painted rows, never null
     */
    public static List<StyledText> lines(Model state, Config config, int width) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        int cols = Math.max(0, width);
        List<Entry> shown = shown(state);
        int selected = selectedIndex(state);
        int top = top(state, opts);
        int content = contentRows(state, opts);
        List<StyledText> lines = new ArrayList<>(Math.max(0, state.rows()));
        if (state.rows() == 0) {
            return List.of();
        }
        if (cols == 0) {
            // No column fits anything, but the geometry still has to be the geometry that was
            // allocated: a parent laying the picker out measures the rows it paints.
            for (int row = 0; row < state.rows(); row++) {
                lines.add(StyledText.empty());
            }
            return List.copyOf(lines);
        }
        lines.add(oneRow(pathLine(state, opts, cols), cols));
        for (int offset = 0; offset < content; offset++) {
            int index = top + offset;
            if (index >= shown.size()) {
                lines.add(StyledText.empty());
                continue;
            }
            lines.add(oneRow(entryLine(state, opts, shown.get(index), index == selected, cols), cols));
        }
        if (lines.size() < state.rows()) {
            lines.add(oneRow(statusLine(state, opts, shown.size(), cols), cols));
        }
        // A picker too short for its own chrome keeps the path and the status row and gives up the
        // help rows, which is the last thing a two-row terminal needs. The count is capped here so
        // the painted rows are exactly the rows the parent allocated, whatever they were.
        List<StyledText> help = Help.render(keys(state), state.expanded(), cols).lines();
        for (int index = 0; index < help.size() && lines.size() < state.rows(); index++) {
            lines.add(oneRow(help.get(index), cols));
        }
        return List.copyOf(lines);
    }

    // ── reduction ────────────────────────────────────────────────────────────

    /** Applies one message. Never performs I/O, never mutates the input state. */
    private Result reduce(Model state, Msg message) {
        return switch (message) {
            case Msg.Up up -> moved(state, -1);
            case Msg.Down down -> moved(state, 1);
            case Msg.PageUp page -> movedBy(state, -contentRows(state, config));
            case Msg.PageDown page -> movedBy(state, contentRows(state, config));
            case Msg.First first -> movedTo(state, 0);
            case Msg.Last last -> movedTo(state, shown(state).size() - 1);
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

    /** The state with the selection moved by entries, clamped to what is shown. */
    private Result moved(Model state, int delta) {
        int index = selectedIndex(state);
        return index < 0 ? Result.next(state) : movedTo(state, index + delta);
    }

    /** The state with the entry at an index selected, clamped to what is shown. */
    private Result movedTo(Model state, int index) {
        List<Entry> shown = shown(state);
        if (shown.isEmpty()) {
            return Result.next(state);
        }
        int target = Math.max(0, Math.min(index, shown.size() - 1));
        return Result.next(state.withSelected(shown.get(target).name()));
    }

    /** The state selecting one shown entry by name, or the incoming state when it is not shown. */
    private Result selectedTo(Model state, String name) {
        for (Entry entry : shown(state)) {
            if (entry.name().equals(name)) {
                return Result.next(state.withSelected(name));
            }
        }
        return Result.next(state);
    }

    /** The state with the selection and the window moved by a page, which is what a page does. */
    private Result movedBy(Model state, int rows) {
        List<Entry> shown = shown(state);
        if (shown.isEmpty()) {
            return Result.next(state);
        }
        int current = Math.max(0, selectedIndex(state));
        int content = contentRows(state, config);
        int maxTop = Math.max(0, shown.size() - content);
        int target = Math.max(0, Math.min(current + rows, shown.size() - 1));
        int top = Math.max(0, Math.min(revealedTop(state.top(), current, shown.size(), content) + rows, maxTop));
        return Result.next(state.withSelected(shown.get(target).name()).withTop(top));
    }

    /** Enters the selected directory, or reports why it cannot. */
    private Result opened(Model state) {
        Optional<Entry> entry = selected(state);
        if (entry.isEmpty()) {
            return Result.next(state.withNotice("nothing selected"));
        }
        if (!entry.get().navigable()) {
            return Result.next(state.withNotice(entry.get().name() + ": not a directory"));
        }
        return navigate(state, entry.get().path(), "");
    }

    /** Reads the parent directory, selecting the directory the reader leaves. */
    private Result parented(Model state) {
        Path parent = parent(state);
        if (parent == null) {
            return Result.next(state.withNotice("at the root"));
        }
        Path name = state.directory().getFileName();
        return navigate(state, parent, name == null ? "" : name.toString());
    }

    /** Chooses the selection, enters a directory, or reports why the entry cannot be chosen. */
    private Result confirmed(Model state) {
        Optional<Entry> entry = selected(state);
        if (entry.isEmpty()) {
            return Result.next(state.withNotice("nothing selected"));
        }
        Entry chosen = entry.get();
        if (chosen.selectable()) {
            // The directory can change between the listing and the choice, so the entry is checked
            // where the filesystem can still be read: in a task, never in this reduction.
            return new Result(state, null, validation(chosen.path(), state.epoch(), config));
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

    /** Shows or hides hidden entries, keeping the selection on the nearest entry that stays. */
    private Result hiddenToggled(Model state) {
        Model flipped = state.withHidden(!state.hidden());
        return Result.next(flipped.withSelected(nearest(flipped, state.selected())));
    }

    /** Publishes a listing if the state is still waiting for it, and discards it otherwise. */
    private Result listed(Model state, Msg.Listed message) {
        if (message.epoch() != state.epoch()) {
            return Result.next(state);
        }
        Request request = state.request();
        String wanted = request instanceof Request.Loading loading ? loading.select() : "";
        Model loaded = state.withDirectory(message.directory())
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
        List<Entry> visible = shown(loaded);
        if (resolved.isEmpty() && !visible.isEmpty()) {
            // A listing with nothing to prefer selects what it shows first, so the state's own
            // selection agrees with the row the reader sees marked.
            resolved = visible.get(0).name();
        }
        Model ready = loaded.withSelected(resolved);
        return Result.next(
                ready.withTop(revealedTop(0, selectedIndex(ready), shown(ready).size(), contentRows(ready, config))));
    }

    /** Reports a failed request, keeping the last successful listing on screen. */
    private Result failed(Model state, Msg.Failed message) {
        if (message.epoch() != state.epoch()) {
            return Result.next(state);
        }
        return Result.next(state.withNotice("").withRequest(new Request.Failed(message.target(), message.message())));
    }

    /** Reports the outcome of a confirmation check. */
    private Result validated(Model state, Msg.Validated message) {
        if (message.epoch() != state.epoch()) {
            return Result.next(state);
        }
        if (message.rejected() == null) {
            return new Result(state.withNotice(""), new Output.Chosen(message.path()), Command.none());
        }
        return new Result(
                state.withNotice(
                        name(message.path()) + ": " + message.rejected().text()),
                new Output.Rejected(message.path(), message.rejected()),
                Command.none());
    }

    /** The state with a request started for one directory, and the effect that answers it. */
    private Result navigate(Model state, Path target, String select) {
        long epoch = state.epoch() + 1;
        Model loading = state.withEpoch(epoch)
                .withRequest(new Request.Loading(target, select))
                .withNotice("");
        return new Result(loading, null, listing(target, epoch, config));
    }

    /**
     * The directory above the current one, or null when there is none inside the boundary.
     *
     * <p>The boundary is the canonical root the first listing published, so a caller that names a
     * root cannot be walked past by a parent gesture - the comparison is between two canonical
     * paths and does not depend on how the caller spelled the root.</p>
     */
    private static Path parent(Model state) {
        Path parent = state.directory().getParent();
        if (parent == null) {
            return null;
        }
        return state.root() != null && !parent.startsWith(state.root()) ? null : parent;
    }

    /** Why one entry cannot be chosen, from the facts the listing resolved. */
    private Reason rejected(Entry entry) {
        return switch (entry.kind()) {
            case MISSING -> Reason.MISSING;
            case FILE -> config.selects(Kind.FILE) ? Reason.EXTENSION : Reason.KIND;
            case DIRECTORY -> Reason.LINK;
            case OTHER -> Reason.KIND;
        };
    }

    /** The nearest entry to a name that the state shows: the name itself when it survives. */
    private static String nearest(Model state, String name) {
        List<Entry> shown = shown(state);
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

    /** Whether a state shows an entry with this name. */
    private static boolean shows(Model state, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (Entry entry : shown(state)) {
            if (entry.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a state would show one entry. */
    private static boolean visible(Model state, Entry entry) {
        return state.hidden() || !entry.name().startsWith(".");
    }

    /** The index of an entry name in a list, or -1. */
    private static int indexOf(List<Entry> entries, String name) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).name().equals(name)) {
                return index;
            }
        }
        return -1;
    }

    /** The window offset that keeps a selection visible inside a content height. */
    private static int revealedTop(int top, int selected, int count, int content) {
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

    // ── effects ──────────────────────────────────────────────────────────────

    /**
     * The task that reads one directory.
     *
     * <p>The listing is registered under one id, so starting the next request replaces it, and the
     * result carries the epoch it answers so a superseded one is discarded even if it was already
     * on its way.</p>
     */
    private static Command<Msg> listing(Path directory, long epoch, Config config) {
        return Command.task(
                LISTING,
                () -> read(directory, epoch, config),
                listing -> new Msg.Listed(
                        listing.epoch(), listing.directory(), listing.root(), listing.entries(), listing.truncated()),
                failure -> new Msg.Failed(epoch, directory, message(failure)));
    }

    /** The task that re-checks one confirmed entry without reading its contents. */
    private static Command<Msg> validation(Path path, long epoch, Config config) {
        return Command.task(
                VALIDATION,
                () -> check(path, epoch, config),
                checked -> checked,
                failure -> new Msg.Validated(epoch, path, Reason.MISSING));
    }

    /**
     * Reads one directory into entries.
     *
     * <p>A listing never throws for an entry it cannot describe: an entry that cannot be read is
     * reported as {@link Kind#MISSING} and remains listed, because a directory that omits what the
     * reader cannot open is more confusing than one that marks it. The stream is closed here, the
     * loop stops at the configured maximum, and an interrupt - which is how the runtime cancels a
     * replaced task - ends the read before it can publish anything.</p>
     */
    private static Listing read(Path target, long epoch, Config config) throws IOException {
        Path real = target.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new NotDirectoryException(real.toString());
        }
        Path root = config.root() == null ? null : config.root().toRealPath();
        if (root != null && !real.startsWith(root)) {
            throw new IOException(real + " is outside the picker's root " + root);
        }
        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(real)) {
            for (Path child : stream) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("listing interrupted: " + real);
                }
                if (entries.size() >= config.maxEntries()) {
                    truncated = true;
                    break;
                }
                entries.add(entry(child, real, root, config));
            }
        }
        entries.sort(ORDER);
        return new Listing(epoch, real, root, List.copyOf(entries), truncated);
    }

    /** One entry, with the facts a listing can read without reading the entry itself. */
    private static Entry entry(Path path, Path directory, Path root, Config config) {
        boolean link = Files.isSymbolicLink(path);
        String target = link ? linkTarget(path) : "";
        BasicFileAttributes facts = facts(path);
        String permissions = permissions(path);
        if (facts == null) {
            return new Entry(path, Kind.MISSING, -1L, permissions, target, false, false);
        }
        Kind kind = facts.isDirectory() ? Kind.DIRECTORY : facts.isRegularFile() ? Kind.FILE : Kind.OTHER;
        boolean navigable =
                kind == Kind.DIRECTORY && (!link || (config.followLinks() && reachable(path, directory, root)));
        boolean selectable = switch (kind) {
            case FILE ->
                config.selects(Kind.FILE) && config.allows(path.getFileName().toString());
            case DIRECTORY -> navigable && config.selects(Kind.DIRECTORY);
            default -> false;
        };
        long size = kind == Kind.FILE ? facts.size() : -1L;
        return new Entry(path, kind, size, permissions, target, selectable, navigable);
    }

    /** A link's own text, or empty when it cannot be read. */
    private static String linkTarget(Path path) {
        try {
            return Files.readSymbolicLink(path).toString();
        } catch (IOException | UnsupportedOperationException notReadable) {
            return "";
        }
    }

    /** An entry's attributes with links followed, or null when the entry cannot be read. */
    private static BasicFileAttributes facts(Path path) {
        try {
            try {
                return Files.readAttributes(path, PosixFileAttributes.class);
            } catch (UnsupportedOperationException noPosix) {
                return Files.readAttributes(path, BasicFileAttributes.class);
            }
        } catch (IOException unreadable) {
            // A dangling link, or a target this process may not stat: listed, never chosen.
            return null;
        }
    }

    /** The nine-character permission string, or empty when the filesystem has none. */
    private static String permissions(Path path) {
        try {
            return render(Files.getPosixFilePermissions(path));
        } catch (IOException | UnsupportedOperationException unreadable) {
            try {
                return render(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
            } catch (IOException | UnsupportedOperationException alsoUnreadable) {
                return "";
            }
        }
    }

    /** The POSIX permissions as {@code rwxr-xr-x}. */
    private static String render(Set<PosixFilePermission> granted) {
        StringBuilder out = new StringBuilder(9);
        out.append(granted.contains(PosixFilePermission.OWNER_READ) ? 'r' : '-');
        out.append(granted.contains(PosixFilePermission.OWNER_WRITE) ? 'w' : '-');
        out.append(granted.contains(PosixFilePermission.OWNER_EXECUTE) ? 'x' : '-');
        out.append(granted.contains(PosixFilePermission.GROUP_READ) ? 'r' : '-');
        out.append(granted.contains(PosixFilePermission.GROUP_WRITE) ? 'w' : '-');
        out.append(granted.contains(PosixFilePermission.GROUP_EXECUTE) ? 'x' : '-');
        out.append(granted.contains(PosixFilePermission.OTHERS_READ) ? 'r' : '-');
        out.append(granted.contains(PosixFilePermission.OTHERS_WRITE) ? 'w' : '-');
        out.append(granted.contains(PosixFilePermission.OTHERS_EXECUTE) ? 'x' : '-');
        return out.toString();
    }

    /**
     * Whether a linked directory may be entered: it must stay inside the root and must not lead
     * back to the directory that holds it or to one of that directory's ancestors.
     */
    private static boolean reachable(Path path, Path directory, Path root) {
        try {
            Path real = path.toRealPath();
            if (root != null && !real.startsWith(root)) {
                return false;
            }
            return !directory.startsWith(real);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * The re-check of one confirmed entry: still there, still the kind the definition selects, still
     * inside the root, and still allowed by the extension policy.
     *
     * <p>It reads the entry's own attributes and never its contents, so checking a gigabyte and
     * checking a byte cost the same. Everything it decides is what the listing decided, taken again
     * from the filesystem as it is now - which is the only way a directory that changed under the
     * reader can be reported truthfully.</p>
     */
    private static Msg check(Path path, long epoch, Config config) {
        BasicFileAttributes facts = facts(path);
        if (facts == null) {
            return new Msg.Validated(epoch, path, Reason.MISSING);
        }
        if (config.root() != null && escapes(path, config)) {
            return new Msg.Validated(epoch, path, Reason.LINK);
        }
        if (facts.isDirectory()) {
            return new Msg.Validated(epoch, path, config.selects(Kind.DIRECTORY) ? null : Reason.KIND);
        }
        if (!facts.isRegularFile() || !config.selects(Kind.FILE)) {
            return new Msg.Validated(epoch, path, Reason.KIND);
        }
        return new Msg.Validated(epoch, path, config.allows(name(path)) ? null : Reason.EXTENSION);
    }

    /** Whether an entry now resolves outside the configured root. */
    private static boolean escapes(Path path, Config config) {
        try {
            return !path.toRealPath().startsWith(config.root().toRealPath());
        } catch (IOException unreadable) {
            return true;
        }
    }

    /** A failure as one status-row phrase. */
    private static String message(Throwable failure) {
        return switch (failure) {
            case AccessDeniedException denied -> "permission denied: " + denied.getFile();
            case NoSuchFileException missing -> "no such directory: " + missing.getFile();
            case NotDirectoryException notDirectory -> "not a directory: " + notDirectory.getFile();
            case InterruptedIOException interrupted -> "cancelled: " + interrupted.getMessage();
            case FileSystemException files -> {
                String reason = files.getReason();
                yield reason == null || reason.isBlank() ? files.getFile() + ": cannot be read" : reason;
            }
            default -> {
                String detail = failure.getMessage();
                yield detail == null || detail.isBlank() ? failure.getClass().getSimpleName() : detail;
            }
        };
    }

    // ── painting ─────────────────────────────────────────────────────────────

    /** Every row the picker paints, exactly {@link Model#rows()} of them. */
    private View<Msg> paint(Model model, int columns) {
        List<View<Msg>> rows = new ArrayList<>(Math.max(0, model.rows()));
        for (StyledText line : lines(model, config, columns)) {
            rows.add(Views.fixed(1, Views.text(line)));
        }
        return Views.column(rows);
    }

    /** The path row: the directory, cut at the start so its last segments stay readable. */
    private static StyledText pathLine(Model state, Config config, int cols) {
        if (cols <= 0) {
            return StyledText.empty();
        }
        String path = state.directory().toString();
        String shownPath = path.isEmpty() ? "." : path;
        return StyledText.of(
                headTruncated(shownPath, cols), style(config.theme().path()));
    }

    /**
     * One entry row: the marker, the name, and the metadata column aligned to the right edge.
     *
     * <p>The metadata is dropped before the name is cut, and the name takes the whole row when the
     * metadata no longer shares it: a picker is read by its names. Both are measured after a name
     * has been flattened to one row, so the columns the arithmetic counts are the columns the row
     * paints, and the row is measured once more as it is composed because a name may begin with a
     * cluster that joins the marker's trailing space and costs a column the sum does not count.</p>
     */
    private static StyledText entryLine(Model state, Config config, Entry entry, boolean selected, int cols) {
        if (cols <= 0) {
            return StyledText.empty();
        }
        Style selection = state.focused() ? config.activeStyle() : config.inactiveStyle();
        Style markStyle = merge(Style.NONE, selected ? selection : NO_STYLE);
        Style nameStyle = merge(config.theme().name(entry, selected, state.focused()), selection);
        Style detailStyle = merge(config.theme().detail(entry, selected, state.focused()), selection);
        String mark = TextMeasure.truncate(selected ? MARKER : MARKER_GAP, cols);
        int available = Math.max(0, cols - TextMeasure.columns(mark));
        if (available == 0) {
            return StyledText.of(mark, markStyle);
        }
        String name = LineBreaks.singleRow(entry.name()) + (entry.directory() ? DIRECTORY_SUFFIX : "");
        Parts parts = parts(name, available, entry.detail());
        int overflow = TextMeasure.columns(mark + parts.plain()) - cols;
        if (overflow > 0) {
            // A cluster that joined the marker cost a column: the name gives it back, and the
            // metadata is the first thing to go.
            parts = parts(TextMeasure.truncate(name, Math.max(0, TextMeasure.columns(name) - overflow)), available, "");
        }
        StyledText line = StyledText.of(mark, markStyle)
                .append(StyledText.of(parts.name(), nameStyle))
                .append(StyledText.of(parts.spaces(), nameStyle))
                .append(StyledText.of(parts.detail(), detailStyle));
        return TextMeasure.columns(line.plainText()) <= cols ? line : oneRow(line, cols);
    }

    /** The three runs of one entry row: the name, the spaces after it, and the metadata. */
    private record Parts(String name, String spaces, String detail) {

        /** The three runs as the row paints them. */
        String plain() {
            return name + spaces + detail;
        }
    }

    /** The runs of one row: the name cut to the room, then the metadata right-aligned when it fits. */
    private static Parts parts(String name, int available, String detail) {
        String metadata = detail == null ? "" : detail;
        int gap = available - TextMeasure.columns(name) - TextMeasure.columns(metadata);
        if (!metadata.isEmpty() && gap >= TextMeasure.columns(DETAIL_GAP)) {
            return new Parts(name, " ".repeat(gap), metadata);
        }
        return new Parts(TextMeasure.truncate(name, available), "", "");
    }

    /** The status row: what is shown, whether the listing was cut, and what the state is doing. */
    private static StyledText statusLine(Model state, Config config, int shown, int cols) {
        if (cols <= 0) {
            return StyledText.empty();
        }
        int total = state.entries().size();
        StyledText line =
                StyledText.of(counts(shown, total), style(config.theme().status()));
        if (state.truncated()) {
            line = line.append(StyledText.of(
                    STATUS_SEPARATOR + "truncated at " + config.maxEntries(),
                    style(config.theme().status())));
        }
        return switch (state.request()) {
            case Request.Loading loading ->
                line.append(StyledText.of(
                        STATUS_SEPARATOR + "loading " + loading.target(),
                        style(config.theme().status())));
            case Request.Failed failed ->
                line.append(StyledText.of(
                        STATUS_SEPARATOR + failed.message() + STATUS_SEPARATOR + "r to retry",
                        style(config.theme().error())));
            case Request.Idle idle ->
                state.notice().isEmpty()
                        ? line
                        : line.append(StyledText.of(
                                STATUS_SEPARATOR + state.notice(),
                                style(config.theme().notice())));
        };
    }

    /** The counts one paint shows: what is shown, and the total when hidden entries are not. */
    private static String counts(int shown, int total) {
        if (total == 0) {
            return EMPTY;
        }
        String entries = shown == 1 ? "1 entry" : shown + " entries";
        return shown == total ? entries : entries + " of " + total;
    }

    /** The rows the picker's own chrome occupies. */
    private static int chromeRows(Model state, Config config) {
        return 2
                + Help.render(keys(state), state.expanded(), HELP_WIDTH).lines().size();
    }

    /** A text cut at the start, so the end of a path survives a narrow terminal. */
    private static String headTruncated(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (TextMeasure.columns(text) <= width) {
            return text;
        }
        int available = width - TextMeasure.columns(ELLIPSIS);
        StringBuilder out = new StringBuilder();
        List<String> clusters = TextMeasure.graphemes(text);
        int used = 0;
        for (int index = clusters.size() - 1; index >= 0 && used < available; index--) {
            String cluster = clusters.get(index);
            int clusterWidth = TextMeasure.columns(cluster);
            if (used + clusterWidth > available) {
                break;
            }
            out.insert(0, cluster);
            used += clusterWidth;
        }
        return ELLIPSIS + out;
    }

    /** One painted row: the value's line breaks become spaces and it is cut to the width. */
    private static StyledText oneRow(StyledText value, int width) {
        if (TextMeasure.columns(value.plainText()) <= width) {
            return value;
        }
        StyledText row = StyledText.empty();
        int used = 0;
        for (StyledText.Span span : value.spans()) {
            String text = LineBreaks.singleRow(span.text());
            int columns = TextMeasure.columns(text);
            if (used + columns <= width) {
                row = row.append(StyledText.of(text, span.style(), span.link()));
                used += columns;
                continue;
            }
            row = row.append(
                    StyledText.of(TextMeasure.truncate(text, Math.max(0, width - used)), span.style(), span.link()));
            break;
        }
        return row;
    }

    /** A theme's answer as a style: null paints nothing extra. */
    private static Style style(Style value) {
        return value == null ? NO_STYLE : value;
    }

    /** A base style with an overlay merged over it, both nullable. */
    private static Style merge(Style base, Style overlay) {
        return style(base).merge(style(overlay));
    }

    /** The last segment of a path, for a status phrase. */
    private static String name(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }
}

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
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Interactive;
import io.github.dhoard.braxton.ui.Sizing;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * A directory browser over application-owned paths: entries with their metadata, a bounded window,
 * directory navigation and one explicit choice.
 *
 * <p>The picker is a component with nested immutable {@link FilePickerModel}, sealed {@link Msg}/{@link
 * Output} and immutable {@link FilePickerConfig}. Its state is the directory whose entries it shows, the
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
 * <p><b>One directory on screen.</b> {@link FilePickerModel#directory()} is the directory the entries belong
 * to and the path line paints it, so the path and the rows always describe the same listing. A
 * request in flight and a failed one are {@link Request} values: a failure keeps the last successful
 * listing on screen and reports a retryable error in the status row instead of replacing the
 * entries with an error page.</p>
 *
 * <p><b>What can be chosen.</b> An {@link Entry} carries whether it is {@linkplain
 * Entry#navigable() navigable} (a directory the picker may open) and whether it is {@linkplain
 * Entry#selectable() selectable} (an entry {@link Msg.Confirm} chooses). Both are decided where the
 * directory is read, from the configuration and from the canonical paths involved: following a
 * symlink is opt-out, an optional {@linkplain FilePickerConfig#root() root} rejects canonical escapes, and a
 * link that resolves to the directory itself or to one of its ancestors is a cycle and is not
 * navigable. A selectable entry is re-checked when it is confirmed, because a directory can change
 * between the listing and the choice; the check reads the entry's type and never its contents.</p>
 *
 * <p><b>What is painted.</b> The picker paints its own chrome - one path row, the entry rows, one
 * status row and the {@link Help} lines of {@link #keys(FilePickerModel)} - inside the rows it was allocated,
 * so {@link FilePickerModel#rows()} is what the parent gave it and {@link #contentRows} is what is left for
 * entries. Entries are painted one row each, in the marker-and-name style {@link RichList} uses, so
 * the two widgets read as one family; {@link Theme} decides the styles, and the selection is merged
 * over whatever a theme returns.</p>
 *
 * <p>Out of scope by design: filtering and searching (an application narrows the entries by
 * sending {@link Msg.Listed} with what it read, and narrows a directory by configuring {@link
 * FilePickerConfig#extensions()}), creating, renaming and deleting files, file contents, multi-select and
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
public final class FilePicker
        implements Component<FilePickerModel, FilePicker.Msg, FilePicker.Output>,
                Interactive<FilePickerModel, FilePicker.Msg> {

    /**
     * The rows a picker paints before the terminal reports its own geometry.
     */
    public static final int DEFAULT_ROWS = 10;

    /**
     * The entries one listing keeps before it reports that it was truncated.
     */
    public static final int DEFAULT_MAX_ENTRIES = 10_000;

    /**
     * Marks the selected entry; the same marker {@link RichList} paints.
     */
    public static final String MARKER = RichList.MARKER;

    /**
     * Painted in place of the marker.
     */
    public static final String MARKER_GAP = RichList.MARKER_GAP;

    /**
     * Separates the parts of the status row.
     */
    public static final String STATUS_SEPARATOR = RichList.STATUS_SEPARATOR;

    /**
     * Ends a directory name, so a directory cannot be mistaken for a file.
     */
    public static final String DIRECTORY_SUFFIX = "/";

    /**
     * Ends a path or a name cut by the available width.
     */
    public static final String ELLIPSIS = "\u2026";

    /**
     * Two spaces between an entry's name and its metadata.
     */
    static final String DETAIL_GAP = "  ";

    /**
     * Shown when the directory holds nothing.
     */
    static final String EMPTY = "empty directory";

    /**
     * The style a theme's null answer is read as.
     */
    static final Style NO_STYLE = Style.NONE;

    /**
     * The width help is measured at: it truncates, so its row count never depends on the width.
     */
    static final int HELP_WIDTH = 400;

    /**
     * One replaceable registration: the directory being read.
     */
    static final EffectId LISTING = EffectId.of("listing");

    /**
     * One replaceable registration: the entry being re-checked before it is chosen.
     */
    static final EffectId VALIDATION = EffectId.of("validation");

    static final SubscriptionId SIZE = SubscriptionId.of("size");

    /**
     * Directories first, then names case-insensitively, with the code points breaking a tie.
     */
    static final Comparator<Entry> ORDER = Comparator.comparing((Entry entry) -> entry.kind() != Kind.DIRECTORY)
            .thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::name);

    /**
     * What an entry is, as the picker resolves it by following a link.
     */
    public enum Kind {

        /**
         * A directory: navigable, and selectable when the definition selects directories.
         */
        DIRECTORY,

        /**
         * A regular file: selectable when the definition selects files and allows its extension.
         */
        FILE,

        /**
         * Anything else - a fifo, a socket, a device: listed, never chosen.
         */
        OTHER,

        /**
         * An entry the picker could not read: a dangling link, or a target it may not stat.
         */
        MISSING
    }

    /**
     * Why a confirmation was refused.
     */
    public enum Reason {

        /**
         * The file's extension is not one the definition allows.
         */
        EXTENSION("extension not allowed"),

        /**
         * The link is not followed, or it points outside the root or back up the tree.
         */
        LINK("link not usable"),

        /**
         * The entry is not a kind the definition selects.
         */
        KIND("not a selectable entry"),

        /**
         * The entry was gone, or no longer readable, when the choice was checked.
         */
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

        /**
         * Keeps the entry representable: only a directory is ever navigable.
         */
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

        /**
         * Nothing in flight.
         */
        record Idle() implements Request {}

        /**
         * A directory being read.
         *
         * @param target the directory the picker asked for
         * @param select the entry name to select when the listing arrives, or empty for the first
         */
        record Loading(Path target, String select) implements Request {

            /**
             * Rejects a missing target.
             */
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

            /**
             * Rejects a missing target and defaults a missing message.
             */
            public Failed {
                Objects.requireNonNull(target, "target");
                message = message == null || message.isBlank() ? "cannot read the directory" : message;
            }
        }
    }

    /**
     * One navigation, choice or listing message.
     */
    public sealed interface Msg {

        /**
         * Selects the entry above, revealing it.
         */
        record Up() implements Msg {}

        /**
         * Selects the entry below, revealing it.
         */
        record Down() implements Msg {}

        /**
         * Selects the entry one window above, revealing it.
         */
        record PageUp() implements Msg {}

        /**
         * Selects the entry one window below, revealing it.
         */
        record PageDown() implements Msg {}

        /**
         * Selects the first shown entry.
         */
        record First() implements Msg {}

        /**
         * Selects the last shown entry.
         */
        record Last() implements Msg {}

        /**
         * Enters the selected entry when it is a navigable directory.
         */
        record Open() implements Msg {}

        /**
         * Reads the parent of the current directory, selecting the directory it leaves.
         */
        record Parent() implements Msg {}

        /**
         * Chooses the selected entry, enters it when it is a directory, or reports why it cannot.
         */
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

            /**
             * Rejects no name; a missing one names no entry.
             */
            public Select {
                name = name == null ? "" : name;
            }
        }

        /**
         * Reports that the reader left the picker without choosing.
         */
        record Cancel() implements Msg {}

        /**
         * Shows or hides hidden entries, keeping the selection when it stays visible.
         */
        record ToggleHidden() implements Msg {}

        /**
         * Switches help between its compact line and its grouped listing.
         */
        record ToggleHelp() implements Msg {}

        /**
         * Re-reads the directory whose entries are shown.
         */
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

            /**
             * Rejects a missing directory and keeps a null list empty.
             */
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

            /**
             * Rejects a missing target and defaults an empty message.
             */
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

            /**
             * Rejects a missing path.
             */
            public Validated {
                Objects.requireNonNull(path, "path");
            }
        }
    }

    /**
     * What the application should do about a choice.
     */
    public sealed interface Output {

        /**
         * The reader chose an entry, and the entry was still what it was listed as.
         *
         * @param path the chosen path
         */
        record Chosen(Path path) implements Output {

            /**
             * Rejects a missing path.
             */
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

            /**
             * Rejects a missing path or reason.
             */
            public Rejected {
                Objects.requireNonNull(path, "path");
                Objects.requireNonNull(reason, "reason");
            }
        }

        /**
         * The reader left the picker without choosing anything.
         */
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
     * One reduction: the next state, the output it reports, and the effect it asks for.
     */
    record Result(FilePickerModel state, Output intent, Command<Msg> command) {

        /**
         * A reduction that asks for nothing.
         */
        static Result next(FilePickerModel state) {
            return new Result(state, null, Command.none());
        }
    }

    /**
     * What one listing task produced.
     */
    record Listing(long epoch, Path directory, Path root, List<Entry> entries, boolean truncated) {}

    private final FilePickerConfig config;
    private final FilePickerEditing filePickerEditing;

    /**
     * A picker from its definition.
     *
     * @param config the immutable definition; required
     */
    public FilePicker(FilePickerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.filePickerEditing = new FilePickerEditing(this.config);
    }

    /**
     * The definition this picker was built from.
     *
     * @return the definition
     */
    public FilePickerConfig config() {
        return config;
    }

    /**
     * The gestures a picker defines, before its state is taken into account.
     *
     * <p>{@link #keys(FilePickerModel)} is the map for one state - it drops {@code open} when the selection is
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
    public static KeyMap<Msg> keys(FilePickerModel state) {
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
                .withEnabled("parent", FilePickerEditing.parent(state) != null)
                // Confirming is available for every selection, not only a legal one: an entry that
                // cannot be chosen has to be able to say why, and silence is not an answer.
                .withEnabled("confirm", entries);
    }

    @Override
    public Update<FilePickerModel, Msg, Output> init(InitContext context) {
        // The startup geometry is the only size the picker knows before a resize arrives: what the
        // application paints outside it is taken out of the terminal's own geometry, and the
        // configured height is the fallback a headless test or a preview uses.
        int rows = context.viewport().rows() > 0
                ? Math.max(0, context.viewport().rows() - config.reservedRows())
                : config.rows();
        Path directory = config.directory();
        FilePickerModel state = new FilePickerModel(
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
        return Update.next(state, FilePickerIo.listing(directory, state.epoch(), config));
    }

    @Override
    public Update<FilePickerModel, Msg, Output> update(FilePickerModel model, Msg message) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(message, "message");
        Result result = reduce(model, message);
        // A reduction owns its effect: a confirmation asks for a check of the entry it was pressed
        // on, which is not a property of the state it leaves behind, so the command is never
        // suppressed. Only the model follows the rule every bundled widget keeps - a message that
        // changed nothing returns the incoming instance, so the runtime's unchanged-reference
        // repaint rule stays meaningful.
        FilePickerModel reduced = result.state().withFocus(model.focused());
        FilePickerModel next = reduced.equals(model) ? model : reduced;
        return result.intent() == null
                ? Update.next(next, result.command())
                : new Update<>(next, result.command(), List.of(result.intent()));
    }

    @Override
    public Subscription<Msg> subscriptions(FilePickerModel model) {
        Objects.requireNonNull(model, "model");
        // One source: the terminal's height. The picker owns no timer, and a listing in flight is a
        // one-shot task whose registration the runtime cancels with the component.
        return Subscription.resize(SIZE, size -> new Msg.RowsChanged(Math.max(0, size.rows() - config.reservedRows())));
    }

    /**
     * Describes the picker.
     *
     * <p>The context is deliberately unused: its viewport is what the picker's parent lays out for,
     * not the picker's own geometry. {@link FilePickerModel#rows()} is what the parent reserved and reported,
     * and it is the only geometry a window may use.</p>
     */
    @Override
    public Element<Msg> view(FilePickerModel model, ViewContext context) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(context, "context");
        return scene(model);
    }

    /**
     * The picker's keys, from the same map {@link Help} renders.
     */
    @Override
    public void onKey(InputEvent.KeyInput key, FilePickerModel model, Consumer<Msg> emit) {
        keys(model).actionFor(key.key()).map(KeyAction::message).ifPresent(emit);
    }

    /**
     * Focus is model data: the application sets it and the widget only reports it.
     */
    @Override
    public boolean focused(FilePickerModel model) {
        return model.focused();
    }

    /**
     * The picker's pointer handler: a click selects the entry row it landed on, a second click opens
     * a directory or chooses a file, and the wheel steps the selection.
     *
     * <p>The rows are the ones {@link #lines} paints: the path row first, then the window's entries
     * from {@link #top}, then the status and help rows. A click on the chrome, or past the last
     * entry, selects nothing.</p>
     */
    @Override
    public void onPointer(PointerEvent pointer, Rect bounds, FilePickerModel model, Consumer<Msg> emit) {
        pointer(pointer.localTo(bounds.row(), bounds.col()), model).ifPresent(emit);
    }

    /**
     * The picker's pointer handler: a click selects the entry row it landed on, a second click opens
     * a directory or chooses a file, and the wheel steps the selection.
     *
     * <p>The rows are the ones {@link #lines} paints: the path row first, then the window's entries
     * from {@link #top}, then the status and help rows. A click on the chrome, or past the last
     * entry, selects nothing.</p>
     */
    private Optional<Msg> pointer(PointerEvent event, FilePickerModel model) {
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
    }

    /**
     * The shown entry painted on one row of the picker, or null for a chrome or blank row.
     */
    static Entry entryAt(FilePickerModel state, FilePickerConfig config, int row) {
        FilePickerConfig opts = config == null ? FilePickerConfig.defaults() : config;
        int body = row - PATH_ROWS;
        List<Entry> shown = shown(state);
        if (body < 0 || body >= contentRows(state, opts)) {
            return null;
        }
        int index = top(state, opts) + body;
        return index < shown.size() ? shown.get(index) : null;
    }

    /**
     * The rows painted above the entries: the path row.
     */
    static final int PATH_ROWS = 1;

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
    public static List<Entry> shown(FilePickerModel state) {
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
     * #selected(FilePickerModel)} never disagree.</p>
     *
     * @param state the state to resolve; required
     * @return the index, or -1 when nothing is shown
     */
    public static int selectedIndex(FilePickerModel state) {
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
    public static Optional<Entry> selected(FilePickerModel state) {
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
     *     {@link FilePickerConfig#defaults()}
     * @return the entry rows the window holds, never negative
     */
    public static int contentRows(FilePickerModel state, FilePickerConfig config) {
        Objects.requireNonNull(state, "state");
        FilePickerConfig opts = config == null ? FilePickerConfig.defaults() : config;
        return Math.max(0, state.rows() - FilePickerRendering.chromeRows(state, opts));
    }

    /**
     * The index of the first shown entry the window paints.
     *
     * <p>The stored offset is clamped to the entries and then moved the least it can to keep the
     * selection visible, so this is where the window really starts however the state was built.</p>
     *
     * @param state the state to resolve; required
     * @param config the definition the window's height is read from; null is
     *     {@link FilePickerConfig#defaults()}
     * @return the first shown entry the window paints, never negative
     */
    public static int top(FilePickerModel state, FilePickerConfig config) {
        Objects.requireNonNull(state, "state");
        return FilePickerEditing.revealedTop(
                state.top(), selectedIndex(state), shown(state).size(), contentRows(state, config));
    }

    /**
     * The rows the picker paints, in paint order.
     *
     * <p>The path row is first, the entry rows follow it, then the status row and the help rows, so
     * the rows a caller sees are the rows the terminal receives - already cut to {@code width} and
     * every entry already one row. The list is exactly {@link FilePickerModel#rows()} long for a given width,
     * so a parent can lay the picker out without measuring it.</p>
     *
     * @param state the current state; required
     * @param config the definition whose theme, styles and extensions apply; null is
     *     {@link FilePickerConfig#defaults()}
     * @param width the display columns the picker was allocated; zero or less paints blank rows
     * @return the painted rows, never null
     */
    public static List<StyledText> lines(FilePickerModel state, FilePickerConfig config, int width) {
        Objects.requireNonNull(state, "state");
        FilePickerConfig opts = config == null ? FilePickerConfig.defaults() : config;
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
        lines.add(FilePickerRendering.oneRow(FilePickerRendering.pathLine(state, opts, cols), cols));
        for (int offset = 0; offset < content; offset++) {
            int index = top + offset;
            if (index >= shown.size()) {
                lines.add(StyledText.empty());
                continue;
            }
            lines.add(FilePickerRendering.oneRow(
                    FilePickerRendering.entryLine(state, opts, shown.get(index), index == selected, cols), cols));
        }
        if (lines.size() < state.rows()) {
            lines.add(
                    FilePickerRendering.oneRow(FilePickerRendering.statusLine(state, opts, shown.size(), cols), cols));
        }
        // A picker too short for its own chrome keeps the path and the status row and gives up the
        // help rows, which is the last thing a two-row terminal needs. The count is capped here so
        // the painted rows are exactly the rows the parent allocated, whatever they were.
        List<StyledText> help = Help.render(keys(state), state.expanded(), cols).lines();
        for (int index = 0; index < help.size() && lines.size() < state.rows(); index++) {
            lines.add(FilePickerRendering.oneRow(help.get(index), cols));
        }
        return List.copyOf(lines);
    }

    // ── reduction ────────────────────────────────────────────────────────────

    /**
     * Applies one message. Never performs I/O, never mutates the input state.
     */
    private Result reduce(FilePickerModel state, Msg message) {
        return filePickerEditing.reduce(state, message);
    }

    // ── effects ──────────────────────────────────────────────────────────────

    // ── painting ─────────────────────────────────────────────────────────────

    /**
     * Every row the picker paints, exactly {@link FilePickerModel#rows()} of them.
     *
     * <p>The rows are one computed block: the picker knows how many rows the parent reserved and
     * renders them for the columns it is given, so a listing that is narrow at first still paints
     * full rows when the terminal grows.</p>
     */
    private Element<Msg> scene(FilePickerModel model) {
        return Elements.stack(
                ComputedBlock.of(model.rows(), cols -> block(model, cols)),
                new Element.Leaf<>(this, model, Sizing.measured()));
    }

    /**
     * The picker's rows joined into one block, one line per row.
     */
    private StyledText block(FilePickerModel model, int columns) {
        StyledText block = null;
        for (StyledText line : lines(model, config, columns)) {
            block = block == null ? line : block.append(StyledText.plain("\n")).append(line);
        }
        return block == null ? StyledText.empty() : block;
    }
}

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

package examples.parity;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.widget.AnimatedProgress;
import io.github.dhoard.braxton.widget.Countdown;
import io.github.dhoard.braxton.widget.DataTable;
import io.github.dhoard.braxton.widget.DataTableContent;
import io.github.dhoard.braxton.widget.DataTableModel;
import io.github.dhoard.braxton.widget.DataTableMsg;
import io.github.dhoard.braxton.widget.FilePicker;
import io.github.dhoard.braxton.widget.FilePickerConfig;
import io.github.dhoard.braxton.widget.FilePickerModel;
import io.github.dhoard.braxton.widget.Markdown;
import io.github.dhoard.braxton.widget.ProgressBar;
import io.github.dhoard.braxton.widget.Tabs;
import io.github.dhoard.braxton.widget.TextInput;
import io.github.dhoard.braxton.widget.TextInputConfig;
import io.github.dhoard.braxton.widget.TextViewport;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 24: one composed workflow over the widgets the earlier phases deliver.
 *
 * <p>The catalog proves each phase in isolation; this scene proves they compose. Nothing here is a
 * second implementation of a widget - every part is the shipped component, mounted into its own slot
 * of this scene's model - and what the scene adds is the wiring between them:</p>
 *
 * <ul>
 *   <li><b>File selection drives the preview.</b> Choosing a Markdown entry in the mounted
 *       {@link FilePicker} replaces the source that the mounted {@link TextViewport} pages; a file
 *       the picker's extension policy refuses is reported, not opened.
 *   <li><b>Search is a query over the projection.</b> The {@link TextInput} publishes a query, and
 *       the scene finds its matches and publishes them as viewport ranges - find-as-you-type, with
 *       the first match selected.
 *   <li><b>Editing is validated.</b> The note field carries the scene's own policy, so a blank or
 *       over-long draft is refused by the field and reported without being submitted; a valid one
 *       is counted and clears the field. The draft itself survives a tab switch, because the page is
 *       kept alive rather than re-created.
 *   <li><b>Table updates feed the progress bar.</b> The task table's own {@code enter} output
 *       toggles the chosen row through one {@code Replace} message, and the settled target of the
 *       mounted {@link AnimatedProgress} follows the completed count.
 *   <li><b>Time is the widget's, not the model's.</b> The {@link Countdown} runs from the runtime's
 *       own clock readings, and its expiry is reported as a timeout.
 *   <li><b>One modal confirmation.</b> {@code ctrl+s} opens a dialog over the whole workspace;
 *       while it is open nothing under it is reachable, and confirming it re-projects the preview and
 *       lands the progress bar.
 * </ul>
 *
 * <p>The documents are the fixture set {@link SceneCatalog#DOCUMENTS} names: the picker browses the
 * owned directory so it lists and chooses real entries, and the scene maps the chosen <em>name</em>
 * to the fixture source instead of reading the file back. Nothing is fetched, and a test drives the
 * same path with no filesystem at all.</p>
 */
public final class WorkspaceDemo {

    /**
     * The id of the preview page.
     */
    public static final String PREVIEW_PAGE = "preview";

    /**
     * The id of the tasks page.
     */
    public static final String TASKS_PAGE = "tasks";

    /**
     * The id of the page that is painted but never reachable: a disabled tab.
     */
    public static final String ARCHIVE_PAGE = "archive";

    /**
     * The review timer's duration.
     */
    public static final Duration REVIEW = Duration.ofMinutes(2);

    /**
     * The longest draft the note field accepts.
     */
    public static final int NOTE_LIMIT = 40;

    /**
     * The rows the scene spends on its four status rows, the tab bar and its two help rows.
     */
    public static final int CHROME_ROWS = 7;

    /**
     * The columns of the file pane.
     */
    public static final int FILES_WIDTH = 34;

    /**
     * The columns of the note pane.
     */
    public static final int NOTE_WIDTH = 34;

    static final Style STATUS = Style.NONE.withBold();

    /**
     * The dialog's two gestures, matched before the scene's own map while it is open.
     */
    static final List<Binding<Msg>> DIALOG_BINDINGS =
            List.of(Binding.of("enter", new Msg.ConfirmSync()), Binding.of("esc", new Msg.CancelSync()));

    static final Style TITLE = Style.NONE.withBold();

    static final List<DataTable.Column> TASK_COLUMNS =
            List.of(DataTable.Column.of("task", "Task"), DataTable.Column.of("state", "State"));

    static final String DONE = "done";

    static final String OPEN = "open";

    /**
     * The scene's one resize source.
     */
    static final SubscriptionId SIZE = SubscriptionId.of("workspace-size");

    /**
     * The task fixture: three rows, one of them complete.
     */
    static final String[][] TASK_SEEDS = {
        {"t1", "write the intro", DONE}, {"t2", "review the notes", OPEN}, {"t3", "pack the lantern", OPEN},
    };

    private WorkspaceDemo() {}

    /**
     * The panes the keyboard can be in.
     */
    public enum Pane {

        /**
         * The mounted tab bar.
         */
        BAR,

        /**
         * The mounted file picker.
         */
        FILES,

        /**
         * The mounted preview pager.
         */
        PREVIEW,

        /**
         * The mounted find field.
         */
        FIND,

        /**
         * The mounted task table.
         */
        TABLE,

        /**
         * The mounted note field.
         */
        NOTE;

        /**
         * Whether this pane is painted on one page.
         *
         * @param page the page's id
         * @return whether the pane belongs to that page
         */
        public boolean on(String page) {
            return switch (this) {
                case BAR -> true;
                case FILES, PREVIEW, FIND -> PREVIEW_PAGE.equals(page);
                case TABLE, NOTE -> TASKS_PAGE.equals(page);
            };
        }

        /**
         * The pane a page opens on.
         *
         * @param page the page's id
         * @return the pane
         */
        public static Pane first(String page) {
            return TASKS_PAGE.equals(page) ? TABLE : FILES;
        }
    }

    /**
     * The workspace state.
     *
     * @param bar the mounted tab bar's own state
     * @param files the mounted picker's own state
     * @param preview the mounted pager's own state
     * @param find the mounted find field's own state
     * @param tasks the mounted task table's own state
     * @param note the mounted note field's own state
     * @param progress the mounted progress bar's own state
     * @param timer the mounted countdown's own state
     * @param document the current projection of {@link #source}
     * @param source the chosen document's file name, or empty when none was chosen
     * @param pane where the keyboard is
     * @param dialog whether the confirmation dialog is open
     * @param synced how many confirmations were applied
     * @param notes how many valid notes were submitted
     * @param refused how many entries or drafts the scene's policies refused
     * @param timeouts how many review timers expired
     * @param rows the terminal rows the scene was last told about
     * @param columns the terminal columns the scene was last told about
     * @param last what the scene last did
     */
    public record Model(
            Tabs.Model bar,
            FilePickerModel files,
            TextViewport.Model preview,
            TextInput.Model find,
            DataTableModel tasks,
            TextInput.Model note,
            AnimatedProgress.Model progress,
            Countdown.Model timer,
            Markdown.Document document,
            String source,
            Pane pane,
            boolean dialog,
            int synced,
            int notes,
            int refused,
            int timeouts,
            int rows,
            int columns,
            String last) {

        /**
         * Keeps every value representable and non-null.
         */
        public Model {
            source = source == null ? "" : source;
            pane = pane == null ? Pane.FILES : pane;
            rows = Math.max(CHROME_ROWS + 1, rows);
            columns = Math.max(1, columns);
            last = last == null ? "none" : last;
        }

        /**
         * The page the bar is showing.
         *
         * @return the shown page's id
         */
        public String page() {
            return bar.activeId() == null ? PREVIEW_PAGE : bar.activeId();
        }

        Model withBar(Tabs.Model value) {
            return new Model(
                    value, files, preview, find, tasks, note, progress, timer, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withFiles(FilePickerModel value) {
            return new Model(
                    bar, value, preview, find, tasks, note, progress, timer, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withPreview(TextViewport.Model value) {
            return new Model(
                    bar, files, value, find, tasks, note, progress, timer, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withFind(TextInput.Model value) {
            return new Model(
                    bar, files, preview, value, tasks, note, progress, timer, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withTasks(DataTableModel value) {
            return new Model(
                    bar, files, preview, find, value, note, progress, timer, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withNote(TextInput.Model value) {
            return new Model(
                    bar, files, preview, find, tasks, value, progress, timer, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withProgress(AnimatedProgress.Model value) {
            return new Model(
                    bar, files, preview, find, tasks, note, value, timer, document, source, pane, dialog, synced, notes,
                    refused, timeouts, rows, columns, last);
        }

        Model withTimer(Countdown.Model value) {
            return new Model(
                    bar, files, preview, find, tasks, note, progress, value, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withDocument(Markdown.Document value, String name) {
            return new Model(
                    bar, files, preview, find, tasks, note, progress, timer, value, name, pane, dialog, synced, notes,
                    refused, timeouts, rows, columns, last);
        }

        Model withPane(Pane value) {
            return new Model(
                    bar, files, preview, find, tasks, note, progress, timer, document, source, value, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withDialog(boolean value) {
            return new Model(
                    bar, files, preview, find, tasks, note, progress, timer, document, source, pane, value, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withCounts(int synced, int notes, int refused, int timeouts) {
            return new Model(
                    bar, files, preview, find, tasks, note, progress, timer, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withGeometry(int rows, int columns) {
            return new Model(
                    bar, files, preview, find, tasks, note, progress, timer, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, last);
        }

        Model withLast(String value) {
            return new Model(
                    bar, files, preview, find, tasks, note, progress, timer, document, source, pane, dialog, synced,
                    notes, refused, timeouts, rows, columns, value);
        }
    }

    /**
     * Every message the scene handles.
     */
    public sealed interface Msg {

        /**
         * Carries the bar value into the owning component update.
         *
         * @param value the tab message to forward
         */
        record Bar(Tabs.Msg value) implements Msg {}

        /**
         * Carries the bar output value into the owning component update.
         *
         * @param value the tab output to forward
         */
        record BarOutput(Tabs.Output value) implements Msg {}

        /**
         * Carries the files value into the owning component update.
         *
         * @param value the file picker message to forward
         */
        record Files(FilePicker.Msg value) implements Msg {}

        /**
         * Carries the file output value into the owning component update.
         *
         * @param value the file picker output to forward
         */
        record FileOutput(FilePicker.Output value) implements Msg {}

        /**
         * Carries the pager value into the owning component update.
         *
         * @param value the pager message to forward
         */
        record Pager(TextViewport.Msg value) implements Msg {}

        /**
         * Carries the find value into the owning component update.
         *
         * @param value the find field message to forward
         */
        record Find(TextInput.Msg value) implements Msg {}

        /**
         * Carries the find output value into the owning component update.
         *
         * @param value the find field output to forward
         */
        record FindOutput(TextInput.Output value) implements Msg {}

        /**
         * Carries the table value into the owning component update.
         *
         * @param value the table message to forward
         */
        record Table(DataTableMsg value) implements Msg {}

        /**
         * Carries the table output value into the owning component update.
         *
         * @param value the table output to forward
         */
        record TableOutput(DataTable.Output value) implements Msg {}

        /**
         * Carries the note value into the owning component update.
         *
         * @param value the note field message to forward
         */
        record Note(TextInput.Msg value) implements Msg {}

        /**
         * Carries the note output value into the owning component update.
         *
         * @param value the note field output to forward
         */
        record NoteOutput(TextInput.Output value) implements Msg {}

        /**
         * Carries the progress value into the owning component update.
         *
         * @param value the progress message to forward
         */
        record Progress(AnimatedProgress.Msg value) implements Msg {}

        /**
         * Carries the timer value into the owning component update.
         *
         * @param value the countdown message to forward
         */
        record Timer(Countdown.Msg value) implements Msg {}

        /**
         * Reports expiry of the review timer.
         */
        record TimedOut() implements Msg {}

        /**
         * Reports new terminal cell dimensions.
         *
         * @param rows the number of rows
         * @param columns the number of columns
         */
        record Resized(int rows, int columns) implements Msg {}

        /**
         * Moves the keyboard to the next painted pane.
         *
         * @param delta how many panes to move by, negative for the previous one
         */
        record FocusNext(int delta) implements Msg {}

        /**
         * Shows one page.
         *
         * @param id the page's id
         */
        record ShowTab(String id) implements Msg {}

        /**
         * Opens the confirmation dialog.
         */
        record Sync() implements Msg {}

        /**
         * Applies the confirmation dialog's decision.
         */
        record ConfirmSync() implements Msg {}

        /**
         * Dismisses the confirmation dialog.
         */
        record CancelSync() implements Msg {}

        /**
         * Starts, pauses or resumes the review timer.
         */
        record ToggleTimer() implements Msg {}

        /**
         * Appends a task to the table.
         */
        record AddTask() implements Msg {}

        /**
         * Removes the selected task from the table.
         */
        record RemoveTask() implements Msg {}

        /**
         * Asks the session to end.
         */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions.
     *
     * <p>They are bubble bindings, so the focused pane sees a key first: a field still types the
     * letters it owns, and a gesture it does not claim reaches the scene. The confirmation and the
     * page shortcuts are chords no field claims, so they work wherever the keyboard is.</p>
     *
     * @return the key map, in help order
     */
    public static KeyMap<Msg> keys() {
        return KeyMap.of(
                KeyMap.group(
                        "Pages",
                        KeyAction.<Msg>of("preview", new Msg.ShowTab(PREVIEW_PAGE), "alt+1", "preview page", "alt+1"),
                        KeyAction.<Msg>of("tasks", new Msg.ShowTab(TASKS_PAGE), "alt+2", "tasks page", "alt+2")),
                KeyMap.group(
                        "Workspace",
                        KeyAction.<Msg>of("focus-next", new Msg.FocusNext(1), "tab", "next pane", "tab"),
                        KeyAction.<Msg>of(
                                "focus-previous", new Msg.FocusNext(-1), "shift+tab", "previous pane", "shift+tab"),
                        KeyAction.<Msg>of("sync", new Msg.Sync(), "ctrl+s", "sync the workspace", "ctrl+s"),
                        KeyAction.<Msg>of(
                                "timer", new Msg.ToggleTimer(), "alt+t", "start/pause the review timer", "alt+t"),
                        KeyAction.<Msg>of("add", new Msg.AddTask(), "a", "add a task", "a"),
                        KeyAction.<Msg>of("remove", new Msg.RemoveTask(), "x", "remove the task", "x")),
                KeyMap.group("General", KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "ctrl+c")));
    }

    /**
     * The tab bar's pages: two live ones and one that is painted but never reachable.
     *
     * @return the pages
     */
    public static List<Tabs.Tab> pages() {
        return List.of(
                Tabs.Tab.of(PREVIEW_PAGE, "Preview"),
                Tabs.Tab.of(TASKS_PAGE, "Tasks"),
                Tabs.Tab.of(ARCHIVE_PAGE, "Archive", false));
    }

    /**
     * The bar's definition: no wrap-around, so the last enabled page is the end of the bar.
     *
     * @return the definition
     */
    public static Tabs.Config barConfig() {
        return Tabs.Config.defaults().withWrap(false);
    }

    /**
     * The picker's definition: only Markdown entries are selectable, and the browsable root is the
     * owned fixture itself.
     *
     * @param fixture the directory the picker browses
     * @return the definition
     */
    public static FilePickerConfig filesConfig(Path fixture) {
        return FilePickerConfig.defaults()
                .withDirectory(fixture)
                .withRoot(fixture)
                .withExtensions("md")
                .withMaxEntries(64);
    }

    /**
     * The find field's definition.
     *
     * @return the definition
     */
    public static TextInputConfig findConfig() {
        return TextInputConfig.defaults().withPrompt("find: ", TITLE).withCompletion(TextInput.Completion.disabled());
    }

    /**
     * The note field's definition: the scene's own policy over a blank and an over-long draft.
     *
     * @return the definition
     */
    public static TextInputConfig noteConfig() {
        return TextInputConfig.defaults()
                .withPrompt("note: ", TITLE)
                .withCompletion(TextInput.Completion.disabled())
                .withValidator(text -> {
                    if (text.isBlank()) {
                        return new TextInput.Validation.Invalid("a note needs text");
                    }
                    return text.length() > NOTE_LIMIT
                            ? new TextInput.Validation.Invalid("at most " + NOTE_LIMIT + " characters")
                            : new TextInput.Validation.Valid();
                });
    }

    /**
     * The progress bar's definition: the workspace's own width and a solid fill.
     *
     * @return the definition
     */
    public static AnimatedProgress.Config progressConfig() {
        return AnimatedProgress.Config.of(
                ProgressBar.of(24).icons("\u2588", "\u2591").showPercentage(true));
    }

    /**
     * The review countdown's definition.
     *
     * @return the definition
     */
    public static Countdown.Config timerConfig() {
        return Countdown.Config.of(REVIEW);
    }

    /**
     * The task table's content, built from the fixture.
     *
     * @return the content
     */
    public static DataTableContent taskContent() {
        List<DataTable.Row> rows = new ArrayList<>(TASK_SEEDS.length);
        for (String[] seed : TASK_SEEDS) {
            rows.add(DataTable.Row.of(seed[0], seed[1], seed[2]));
        }
        return DataTableContent.of(TASK_COLUMNS, rows);
    }

    /**
     * The ranges a query matches in one projection, in paint order.
     *
     * <p>The policy is the scene's own: every case-insensitive occurrence of the query on a line, in
     * the line's own UTF-16 offsets. The pager paints ranges; it does not compute them.</p>
     *
     * @param lines the projected lines
     * @param query the query; null or blank matches nothing
     * @return the ranges
     */
    public static List<TextViewport.Highlight> matches(List<StyledText> lines, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return List.of();
        }
        List<TextViewport.Highlight> found = new ArrayList<>();
        for (int line = 0; line < lines.size(); line++) {
            String text = lines.get(line).plainText().toLowerCase(Locale.ROOT);
            int offset = text.indexOf(needle);
            while (offset >= 0) {
                found.add(new TextViewport.Highlight(line, offset, offset + needle.length()));
                offset = text.indexOf(needle, offset + needle.length());
            }
        }
        return List.copyOf(found);
    }

    /**
     * Runs the workspace over an owned fixture directory the caller created and removes.
     *
     * @param fixture the directory the picker browses
     * @return how the run ended
     */
    public static ExitResult run(Path fixture) {
        return Application.run(new WorkspaceScene(fixture));
    }

    /**
     * Runs the workspace over a fixture it creates and removes.
     *
     * @return how the run ended
     */
    public static ExitResult run() {
        Path fixture;
        try {
            fixture = Showcase.fixture();
        } catch (IOException failure) {
            return ExitResult.failure(failure, List.of());
        }
        try {
            return run(fixture);
        } finally {
            Showcase.remove(fixture);
        }
    }

    /**
     * Runs the workspace.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }
}

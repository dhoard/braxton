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
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.AnimatedProgress;
import io.github.dhoard.braxton.widget.Countdown;
import io.github.dhoard.braxton.widget.DataTable;
import io.github.dhoard.braxton.widget.FilePicker;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Markdown;
import io.github.dhoard.braxton.widget.ProgressBar;
import io.github.dhoard.braxton.widget.Tabs;
import io.github.dhoard.braxton.widget.TextInput;
import io.github.dhoard.braxton.widget.TextViewport;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

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

    /** The id of the preview page. */
    public static final String PREVIEW_PAGE = "preview";

    /** The id of the tasks page. */
    public static final String TASKS_PAGE = "tasks";

    /** The id of the page that is painted but never reachable: a disabled tab. */
    public static final String ARCHIVE_PAGE = "archive";

    /** The review timer's duration. */
    public static final Duration REVIEW = Duration.ofMinutes(2);

    /** The longest draft the note field accepts. */
    public static final int NOTE_LIMIT = 40;

    /** The rows the scene spends on its four status rows, the tab bar and its two help rows. */
    public static final int CHROME_ROWS = 7;

    /** The columns of the file pane. */
    public static final int FILES_WIDTH = 34;

    /** The columns of the note pane. */
    public static final int NOTE_WIDTH = 34;

    private static final Style STATUS = Style.NONE.withBold();

    private static final Style TITLE = Style.NONE.withBold();

    private static final List<DataTable.Column> TASK_COLUMNS =
            List.of(DataTable.Column.of("task", "Task"), DataTable.Column.of("state", "State"));

    private static final String DONE = "done";

    private static final String OPEN = "open";

    /** The scene's one resize source. */
    private static final SubscriptionId SIZE = SubscriptionId.of("workspace-size");

    /** The task fixture: three rows, one of them complete. */
    private static final String[][] TASK_SEEDS = {
        {"t1", "write the intro", DONE}, {"t2", "review the notes", OPEN}, {"t3", "pack the lantern", OPEN},
    };

    private WorkspaceDemo() {}

    /** The panes the keyboard can be in. */
    public enum Pane {

        /** The mounted tab bar. */
        BAR,

        /** The mounted file picker. */
        FILES,

        /** The mounted preview pager. */
        PREVIEW,

        /** The mounted find field. */
        FIND,

        /** The mounted task table. */
        TABLE,

        /** The mounted note field. */
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
            FilePicker.Model files,
            TextViewport.Model preview,
            TextInput.Model find,
            DataTable.Model tasks,
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

        /** Keeps every value representable and non-null. */
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

        Model withFiles(FilePicker.Model value) {
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

        Model withTasks(DataTable.Model value) {
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

    /** Every message the scene handles. */
    public sealed interface Msg {

        record Bar(Tabs.Msg value) implements Msg {}

        record BarOutput(Tabs.Output value) implements Msg {}

        record Files(FilePicker.Msg value) implements Msg {}

        record FileOutput(FilePicker.Output value) implements Msg {}

        record Pager(TextViewport.Msg value) implements Msg {}

        record Find(TextInput.Msg value) implements Msg {}

        record FindOutput(TextInput.Output value) implements Msg {}

        record Table(DataTable.Msg value) implements Msg {}

        record TableOutput(DataTable.Output value) implements Msg {}

        record Note(TextInput.Msg value) implements Msg {}

        record NoteOutput(TextInput.Output value) implements Msg {}

        record Progress(AnimatedProgress.Msg value) implements Msg {}

        record Timer(Countdown.Msg value) implements Msg {}

        record TimedOut() implements Msg {}

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

        /** Opens the confirmation dialog. */
        record Sync() implements Msg {}

        /** Applies the confirmation dialog's decision. */
        record ConfirmSync() implements Msg {}

        /** Dismisses the confirmation dialog. */
        record CancelSync() implements Msg {}

        /** Starts, pauses or resumes the review timer. */
        record ToggleTimer() implements Msg {}

        /** Appends a task to the table. */
        record AddTask() implements Msg {}

        /** Removes the selected task from the table. */
        record RemoveTask() implements Msg {}

        /** Asks the session to end. */
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
    public static FilePicker.Config filesConfig(Path fixture) {
        return FilePicker.Config.defaults()
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
    public static TextInput.Config findConfig() {
        return TextInput.Config.defaults().withPrompt("find: ", TITLE).withCompletion(TextInput.Completion.disabled());
    }

    /**
     * The note field's definition: the scene's own policy over a blank and an over-long draft.
     *
     * @return the definition
     */
    public static TextInput.Config noteConfig() {
        return TextInput.Config.defaults()
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
    public static DataTable.Content taskContent() {
        List<DataTable.Row> rows = new ArrayList<>(TASK_SEEDS.length);
        for (String[] seed : TASK_SEEDS) {
            rows.add(DataTable.Row.of(seed[0], seed[1], seed[2]));
        }
        return DataTable.Content.of(TASK_COLUMNS, rows);
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
        return Application.run(new Scene(fixture));
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

    /** The composed workspace: six mounted widgets, two status rows and one dialog. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final Path fixture;

        private final KeyMap<Msg> keys = keys();

        private final Tabs bar = new Tabs(barConfig());

        private final FilePicker files;

        private final TextInput find = new TextInput(findConfig());

        private final TextInput note = new TextInput(noteConfig());

        private final AnimatedProgress progress = new AnimatedProgress(progressConfig());

        private final Countdown timer = new Countdown(timerConfig());

        private final Mount<Model, Msg, Never, Tabs.Model, Tabs.Msg, Tabs.Output> barMount = Mount.of(
                ComponentId.of("bar"),
                bar,
                ModelLens.of(Model::bar, Model::withBar),
                Msg.Bar::new,
                OutputRoute.toMessage(Msg.BarOutput::new));

        private final Mount<Model, Msg, Never, FilePicker.Model, FilePicker.Msg, FilePicker.Output> filesMount;

        private final Mount<Model, Msg, Never, TextInput.Model, TextInput.Msg, TextInput.Output> findMount = Mount.of(
                ComponentId.of("find"),
                find,
                ModelLens.of(Model::find, Model::withFind),
                Msg.Find::new,
                OutputRoute.toMessage(Msg.FindOutput::new));

        private final Mount<Model, Msg, Never, TextInput.Model, TextInput.Msg, TextInput.Output> noteMount = Mount.of(
                ComponentId.of("note"),
                note,
                ModelLens.of(Model::note, Model::withNote),
                Msg.Note::new,
                OutputRoute.toMessage(Msg.NoteOutput::new));

        private final Mount<Model, Msg, Never, AnimatedProgress.Model, AnimatedProgress.Msg, Never> progressMount =
                Mount.of(
                        ComponentId.of("progress"),
                        progress,
                        ModelLens.of(Model::progress, Model::withProgress),
                        Msg.Progress::new,
                        output -> {
                            throw new IllegalStateException("the bar reports no outputs");
                        });

        private final Mount<Model, Msg, Never, Countdown.Model, Countdown.Msg, Countdown.Output> timerMount = Mount.of(
                ComponentId.of("timer"),
                timer,
                ModelLens.of(Model::timer, Model::withTimer),
                Msg.Timer::new,
                OutputRoute.toMessage(output -> new Msg.TimedOut()));

        /**
         * A workspace over one owned fixture directory.
         *
         * @param fixture the directory the picker browses; required
         */
        public Scene(Path fixture) {
            this.fixture = Objects.requireNonNull(fixture, "fixture");
            this.files = new FilePicker(filesConfig(fixture));
            this.filesMount = Mount.of(
                    ComponentId.of("files"),
                    files,
                    ModelLens.of(Model::files, Model::withFiles),
                    Msg.Files::new,
                    OutputRoute.toMessage(Msg.FileOutput::new));
        }

        /**
         * The directory this workspace browses.
         *
         * @return the directory
         */
        public Path fixture() {
            return fixture;
        }

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            int rows = context.viewport().rows() > 0 ? context.viewport().rows() : 24;
            int columns = context.viewport().cols() > 0 ? context.viewport().cols() : 80;
            var started = filesMount.initialize(context);
            Model empty = new Model(
                    barMount.initialize(context).model().withTabs(pages()),
                    started.model(),
                    TextViewport.Model.of(TextViewport.Content.empty()),
                    find.validated(TextInput.Model.empty()),
                    tasksMount(0, 0).initialize(context).model(),
                    note.validated(TextInput.Model.empty()).withFocus(false),
                    progressMount.initialize(context).model(),
                    timerMount.initialize(context).model(),
                    Markdown.of().document(""),
                    "",
                    Pane.FILES,
                    false,
                    0,
                    0,
                    0,
                    0,
                    rows,
                    columns,
                    "started");
            // The picker is told its listing first, then the pager and the table their geometry:
            // each mounted component owns its own state, and the scene is what knows the split.
            Model sized = target(focused(published(geometry(empty, rows, columns))));
            return new Update<>(sized, started.command(), started.outputs());
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Bar value -> barMount.update(model, value.value());
                case Msg.BarOutput value -> Update.next(application(model, value.value()));
                case Msg.Files value -> filesMount.update(model, value.value());
                case Msg.FileOutput value -> reported(model, value.value());
                case Msg.Pager value -> pagerMount(model).update(model, value.value());
                case Msg.Find value -> typed(model, value.value());
                case Msg.FindOutput value -> found(model, value.value());
                case Msg.Table value -> tasksMount(0, 0).update(model, value.value());
                case Msg.TableOutput value -> changed(model, value.value());
                case Msg.Note value -> typedNote(model, value.value());
                case Msg.NoteOutput value -> noted(model, value.value());
                case Msg.Progress value -> progressMount.update(model, value.value());
                case Msg.Timer value -> timerMount.update(model, value.value());
                case Msg.TimedOut timeout ->
                    Update.next(model.withCounts(model.synced(), model.notes(), model.refused(), model.timeouts() + 1)
                            .withLast("timer-timeout"));
                case Msg.Resized resized ->
                    Update.next(published(geometry(model, resized.rows(), resized.columns()))
                            .withLast("resized"));
                case Msg.FocusNext focus ->
                    Update.next(focused(nextPane(model, focus.delta())).withLast("focus"));
                case Msg.ShowTab show -> shown(model, show.id());
                case Msg.Sync sync -> Update.next(model.withDialog(true).withLast("sync-ask"));
                case Msg.ConfirmSync confirm -> Update.next(published(confirmed(model)));
                case Msg.CancelSync cancel ->
                    Update.next(model.withDialog(false).withLast("sync-cancelled"));
                case Msg.ToggleTimer toggle ->
                    Update.next(timerMount
                            .update(model, new Countdown.Msg.Toggle())
                            .model()
                            .withLast("timer"));
                case Msg.AddTask add -> added(model);
                case Msg.RemoveTask remove -> removed(model);
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * One resize source for the whole scene.
         *
         * <p>The scene owns the split between its status rows, its pages and its help, so it is the
         * only thing that can tell the pager and the table what they were allocated.</p>
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.batch(
                    Subscription.resize(SIZE, size -> new Msg.Resized(size.rows(), size.cols())),
                    filesMount.subscriptions(model),
                    findMount.subscriptions(model),
                    noteMount.subscriptions(model),
                    progressMount.subscriptions(model),
                    timerMount.subscriptions(model),
                    barMount.subscriptions(model),
                    pagerMount(model).subscriptions(model),
                    tasksMount(0, 0).subscriptions(model));
        }

        @Override
        public ComponentTree children(Model model) {
            return ComponentTree.of(
                    barMount.tree(model),
                    filesMount.tree(model),
                    findMount.tree(model),
                    noteMount.tree(model),
                    progressMount.tree(model),
                    timerMount.tree(model),
                    pagerMount(model).tree(model),
                    tasksMount(0, 0).tree(model));
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int rows = context.viewport().rows();
            int cols = context.viewport().cols();
            int body = Math.max(0, rows - CHROME_ROWS);
            List<View<Msg>> sections = new ArrayList<>(CHROME_ROWS + 2);
            sections.add(Views.fixed(1, Views.text(status(model), STATUS)));
            sections.add(Views.fixed(1, Views.text(search(model), STATUS)));
            sections.add(Views.fixed(1, Views.text(counts(model), STATUS)));
            sections.add(Views.fixed(1, Views.text(log(model), STATUS)));
            sections.add(Views.fixed(1, barMount.view(model, context)));
            sections.add(Views.fixed(body, page(model, body, cols)));
            sections.add(Views.fixed(1, Views.text(Help.render(keys, false, cols))));
            sections.add(Views.fixed(1, Views.text(paneHelp(model, cols))));
            View<Msg> base = Views.keyed(List.of(), Views.column(sections), keys.bindings());
            if (!model.dialog()) {
                return base;
            }
            // The dialog is a route of its own: the scene's gestures still close it, and the page
            // under it is unreachable until it is answered.
            return Views.modal(base, Views.onKeys(keys.bindings(), dialog()));
        }

        // ── the pages ───────────────────────────────────────────────────────

        private View<Msg> page(Model model, int rows, int cols) {
            return PREVIEW_PAGE.equals(model.page()) ? previewPage(model, rows, cols) : tasksPage(model, rows, cols);
        }

        private View<Msg> previewPage(Model model, int rows, int cols) {
            int pagerRows = Math.max(0, rows - 1);
            int content = Math.max(1, cols - FILES_WIDTH - 1);
            View<Msg> right = Views.column(
                    Views.fixed(1, findMount.view(model, new ViewContext(new Viewport(1, content)))),
                    Views.fixed(
                            pagerRows,
                            pagerMount(model).view(model, new ViewContext(new Viewport(pagerRows, content)))));
            View<Msg> left = Views.column(
                    Views.fixed(1, Views.text("Files", TITLE)),
                    Views.fixed(
                            Math.max(0, rows - 1),
                            filesMount.view(model, new ViewContext(new Viewport(Math.max(0, rows - 1), FILES_WIDTH)))));
            return Views.row(
                    Views.fixed(FILES_WIDTH, left),
                    Views.fixed(1, Views.text(StyledText.plain("\u2502"))),
                    Views.grow(right));
        }

        private View<Msg> tasksPage(Model model, int rows, int cols) {
            int tableRows = Math.max(1, rows - 1);
            int tableCols = Math.max(1, cols - NOTE_WIDTH - 1);
            View<Msg> table =
                    tasksMount(tableRows, tableCols).view(model, new ViewContext(new Viewport(tableRows, tableCols)));
            List<View<Msg>> noteRows = new ArrayList<>(5);
            noteRows.add(Views.fixed(1, Views.text("Review", TITLE)));
            noteRows.add(Views.fixed(1, noteMount.view(model, new ViewContext(new Viewport(1, NOTE_WIDTH)))));
            noteRows.add(Views.fixed(1, Views.text("progress " + progressText(model))));
            noteRows.add(Views.fixed(1, Views.text("review " + timerText(model))));
            return Views.row(
                    Views.grow(Views.column(Views.fixed(1, Views.text("Tasks", TITLE)), table)),
                    Views.fixed(1, Views.text(StyledText.plain("\u2502"))),
                    Views.fixed(NOTE_WIDTH, Views.column(noteRows)));
        }

        private View<Msg> dialog() {
            List<Binding<Msg>> bindings =
                    List.of(Binding.of("enter", new Msg.ConfirmSync()), Binding.of("esc", new Msg.CancelSync()));
            return Views.focus(
                    ElementId.of("confirm"),
                    true,
                    bindings,
                    event -> Optional.empty(),
                    Views.border(
                            Border.ROUNDED,
                            StyledText.plain("Sync"),
                            Views.column(
                                    Views.text("Apply the workspace changes?"),
                                    Views.gap(1),
                                    Views.text("enter confirm \u2022 esc cancel", TITLE))));
        }

        // ── the reductions ──────────────────────────────────────────────────

        /** A page change: the keyboard moves to the new page's first pane, never a hidden one. */
        private Update<Model, Msg, Never> shown(Model model, String id) {
            Update<Model, Msg, Never> applied = barMount.update(model, new Tabs.Msg.Activate(id));
            return new Update<>(
                    application(
                            applied.model(),
                            new Tabs.Output.Activated(applied.model().page())),
                    applied.command(),
                    applied.outputs());
        }

        /** The bar decided which page is shown; the scene follows it with the keyboard. */
        private Model application(Model model, Tabs.Output output) {
            if (!(output instanceof Tabs.Output.Activated activated)) {
                return model.withLast("bar");
            }
            Pane pane = model.pane().on(activated.id()) ? model.pane() : Pane.first(activated.id());
            return focused(model.withPane(pane).withLast("tab-" + activated.id()));
        }

        /** One picker output: a chosen entry becomes the preview's source. */
        private Update<Model, Msg, Never> reported(Model model, FilePicker.Output output) {
            return switch (output) {
                case FilePicker.Output.Chosen chosen -> {
                    String name = chosen.path().getFileName().toString();
                    if (!SceneCatalog.DOCUMENTS.containsKey(name)) {
                        // The fixture is the only source: a file the demo was not given is reported
                        // rather than read, so the workspace never opens something it did not own.
                        yield Update.next(
                                model.withCounts(model.synced(), model.notes(), model.refused() + 1, model.timeouts())
                                        .withLast("refused-unknown-" + name));
                    }
                    Model opened = published(model.withDocument(
                                    document(name, Math.max(1, model.columns() - FILES_WIDTH - 1)), name)
                            .withPane(Pane.PREVIEW));
                    yield Update.next(focused(opened).withLast("chosen-" + name));
                }
                case FilePicker.Output.Rejected rejected ->
                    Update.next(model.withCounts(model.synced(), model.notes(), model.refused() + 1, model.timeouts())
                            .withLast("refused-" + rejected.path().getFileName() + "-"
                                    + rejected.reason().name().toLowerCase(Locale.ROOT)));
                case FilePicker.Output.Cancelled cancelled -> Update.next(model.withLast("picker-cancelled"));
            };
        }

        /** One find-field message: the query republishes the ranges it matches. */
        private Update<Model, Msg, Never> typed(Model model, TextInput.Msg message) {
            Update<Model, Msg, Never> applied = findMount.update(model, message);
            return stamped(applied, searched(applied.model(), true).withLast("typed"));
        }

        /**
         * One child reduction with the scene's own log line on its model.
         *
         * <p>The child's command and outputs are kept: a mounted widget reports its semantic output
         * through the mount's dispatch, and dropping the command here would silently lose it.</p>
         */
        private static Update<Model, Msg, Never> stamped(Update<Model, Msg, Never> applied, Model model) {
            return new Update<>(model, applied.command(), applied.outputs());
        }

        private Update<Model, Msg, Never> found(Model model, TextInput.Output output) {
            return Update.next(model.withLast("find-" + output(output)));
        }

        /** One note-field message: the field validates its own draft on every edit. */
        private Update<Model, Msg, Never> typedNote(Model model, TextInput.Msg message) {
            Update<Model, Msg, Never> applied = noteMount.update(model, message);
            return stamped(applied, applied.model().withLast("note-typed"));
        }

        private Update<Model, Msg, Never> noted(Model model, TextInput.Output output) {
            return switch (output) {
                case TextInput.Output.Submit submit -> {
                    // One reduction clears the field; the answer it publishes is the field's own.
                    Update<Model, Msg, Never> cleared = noteMount.update(model, new TextInput.Msg.Clear());
                    yield stamped(
                            cleared,
                            cleared.model()
                                    .withCounts(model.synced(), model.notes() + 1, model.refused(), model.timeouts())
                                    .withLast("note-submitted"));
                }
                case TextInput.Output.Rejected rejected ->
                    Update.next(model.withCounts(model.synced(), model.notes(), model.refused() + 1, model.timeouts())
                            .withLast("note-refused"));
                default -> Update.next(model.withLast("note-" + output(output)));
            };
        }

        /** One table output: the chosen task toggles, which moves the progress target. */
        private Update<Model, Msg, Never> changed(Model model, DataTable.Output output) {
            if (!(output instanceof DataTable.Output.Chosen chosen)) {
                return Update.next(model.withLast("table-cancelled"));
            }
            Optional<DataTable.Row> selected = model.tasks().content().rows().stream()
                    .filter(row -> row.id().equals(chosen.rowId()))
                    .findFirst();
            if (selected.isEmpty()) {
                return Update.next(model.withLast("table-empty"));
            }
            DataTable.Row row = selected.get();
            String state = DONE.equals(row.cell(1).plainText()) ? OPEN : DONE;
            DataTable.Row toggled = DataTable.Row.of(row.id(), row.cell(0).plainText(), state);
            return taskUpdate(model, new DataTable.Msg.Replace(toggled), "task-" + row.id() + "-" + state);
        }

        private Update<Model, Msg, Never> added(Model model) {
            DataTable.Content content = model.tasks().content();
            String id = "t" + (content.rowCount() + 1);
            DataTable.Row row = DataTable.Row.of(id, "task " + (content.rowCount() + 1), OPEN);
            return taskUpdate(model, new DataTable.Msg.Insert(content.rowCount(), row), "task-added-" + id);
        }

        private Update<Model, Msg, Never> removed(Model model) {
            return model.tasks()
                    .selectedRow()
                    .map(row -> taskUpdate(model, new DataTable.Msg.Remove(row.id()), "task-removed-" + row.id()))
                    .orElseGet(() -> Update.next(model.withLast("table-empty")));
        }

        /**
         * One table change, followed by the progress target it implies.
         *
         * <p>This is the composed step the catalog cannot show: the table's content is the bar's
         * input, and neither component knows about the other.</p>
         */
        private Update<Model, Msg, Never> taskUpdate(Model model, DataTable.Msg message, String last) {
            Update<Model, Msg, Never> applied = tasksMount(0, 0).update(model, message);
            Model settled = target(applied.model());
            return new Update<>(settled.withLast(last), applied.command(), applied.outputs());
        }

        /** The bar's settled target is the completed fraction of the table. */
        private Model target(Model model) {
            DataTable.Content content = model.tasks().content();
            double fraction = content.rowCount() == 0 ? 0.0 : (double) complete(content) / content.rowCount();
            return progressMount
                    .update(model, new AnimatedProgress.Msg.Set(fraction))
                    .model();
        }

        /** How many task rows are complete. */
        private static int complete(DataTable.Content content) {
            int count = 0;
            for (DataTable.Row row : content.rows()) {
                if (DONE.equals(row.cell(1).plainText())) {
                    count++;
                }
            }
            return count;
        }

        /** A confirmation: the workspace is applied, so the projection and the bar both move. */
        private Model confirmed(Model model) {
            Model landed = progressMount
                    .update(model.withDialog(false), new AnimatedProgress.Msg.Set(1.0))
                    .model();
            return published(landed.withCounts(model.synced() + 1, model.notes(), model.refused(), model.timeouts()))
                    .withLast("sync-confirmed");
        }

        // ── the projection ──────────────────────────────────────────────────

        /** Projects the chosen source at the pager's width and publishes it. */
        private Model published(Model model) {
            int width = Math.max(1, model.columns() - FILES_WIDTH - 1);
            Markdown.Document document = document(model.source(), width);
            Model projected = model.withDocument(document, model.source());
            Model published = pagerMount(projected)
                    .update(projected, new TextViewport.Msg.ContentChanged(document.content()))
                    .model();
            return searched(published, false);
        }

        /**
         * Publishes the ranges the current query matches in the projection on screen.
         *
         * <p>Publishing ranges clears the active one, because an index into the previous list names
         * nothing in this one. {@code select} is the scene's decision: a keystroke selects the first
         * match, while a re-projection selects nothing, so choosing a file or resizing never moves a
         * reader to a match they were not looking at.</p>
         */
        private Model searched(Model model, boolean select) {
            List<TextViewport.Highlight> found =
                    matches(model.document().lines(), model.find().text());
            Model withMatches = pagerMount(model)
                    .update(model, new TextViewport.Msg.Highlights(found))
                    .model();
            if (!select || found.isEmpty()) {
                return withMatches;
            }
            return pagerMount(withMatches)
                    .update(withMatches, new TextViewport.Msg.NextMatch())
                    .model();
        }

        /** The fixture source one name stands for, projected at one width. */
        private static Markdown.Document document(String name, int width) {
            String source = SceneCatalog.DOCUMENTS.getOrDefault(name, "");
            return Markdown.of(Markdown.Config.defaults().withWidth(Math.max(1, width)))
                    .document(source);
        }

        /** The geometry the scene's own layout gives its mounted pager, table and fields. */
        private Model geometry(Model model, int rows, int columns) {
            int body = Math.max(1, rows - CHROME_ROWS);
            int content = Math.max(1, columns - FILES_WIDTH - 1);
            Model sized = model.withGeometry(rows, columns);
            Model pager = pagerMount(sized)
                    .update(sized, new TextViewport.Msg.Resized(Math.max(0, body - 1), content))
                    .model();
            Model table = tasksMount(body, Math.max(1, columns - NOTE_WIDTH - 1))
                    .update(pager, new DataTable.Msg.Resized(body, Math.max(1, columns - NOTE_WIDTH - 1)))
                    .model();
            return table;
        }

        /** Every mounted widget's focus, from the one pane the scene says the keyboard is in. */
        private Model focused(Model model) {
            return model.withBar(model.bar().withFocus(model.pane() == Pane.BAR))
                    .withFiles(model.files().withFocus(model.pane() == Pane.FILES))
                    .withPreview(model.preview().withFocus(model.pane() == Pane.PREVIEW))
                    .withFind(model.find().withFocus(model.pane() == Pane.FIND))
                    .withTasks(model.tasks().withFocus(model.pane() == Pane.TABLE))
                    .withNote(model.note().withFocus(model.pane() == Pane.NOTE));
        }

        /**
         * The next painted pane, wrapping inside the shown page.
         *
         * <p>The bar comes last: it is where a reader goes on the way out of a page, not the first
         * thing the keyboard lands on, and putting it last also means the content panes are one
         * gesture apart.</p>
         */
        private static Model nextPane(Model model, int delta) {
            List<Pane> panes = new ArrayList<>(4);
            for (Pane pane : Pane.values()) {
                if (pane != Pane.BAR && pane.on(model.page())) {
                    panes.add(pane);
                }
            }
            panes.add(Pane.BAR);
            int at = panes.indexOf(model.pane());
            int next = Math.floorMod(at < 0 ? 0 : at + delta, panes.size());
            return model.withPane(panes.get(next));
        }

        // ── the mounts with geometry ────────────────────────────────────────

        private Mount<Model, Msg, Never, TextViewport.Model, TextViewport.Msg, Never> pagerMount(Model model) {
            int rows = Math.max(1, model.rows() - CHROME_ROWS - 1);
            int columns = Math.max(1, model.columns() - FILES_WIDTH - 1);
            return Mount.of(
                    ComponentId.of("preview"),
                    new TextViewport(pagerConfig(rows, columns)),
                    ModelLens.of(Model::preview, Model::withPreview),
                    Msg.Pager::new,
                    output -> {
                        throw new IllegalStateException("the pager reports no outputs");
                    });
        }

        private Mount<Model, Msg, Never, DataTable.Model, DataTable.Msg, DataTable.Output> tasksMount(
                int rows, int columns) {
            int height = rows > 0 ? rows : Math.max(1, 24 - CHROME_ROWS);
            int width = columns > 0 ? columns : 46;
            return Mount.of(
                    ComponentId.of("tasks"),
                    new DataTable(DataTable.Config.defaults()
                            .withContent(taskContent())
                            .withHeight(height)
                            .withWidth(width)),
                    ModelLens.of(Model::tasks, Model::withTasks),
                    Msg.Table::new,
                    OutputRoute.toMessage(Msg.TableOutput::new));
        }

        /** The pager's definition for one allocation: wrapping, no gutter, following off. */
        private static TextViewport.Config pagerConfig(int rows, int columns) {
            return TextViewport.Config.defaults()
                    .withMode(TextViewport.Mode.WRAP)
                    .withFollowEnd(false)
                    .withRows(rows)
                    .withColumns(columns);
        }

        // ── the status rows ─────────────────────────────────────────────────

        /** What the workspace is showing, in the terms the verifier asserts. */
        private static String status(Model model) {
            return "page=" + model.page()
                    + " focus=" + model.pane().name().toLowerCase(Locale.ROOT)
                    + " source=" + (model.source().isEmpty() ? "-" : model.source())
                    + " lines=" + model.document().lineCount()
                    + " limits=" + (model.document().failed() ? "exceeded" : "none");
        }

        /** What the query found in the projection on screen. */
        private static String search(Model model) {
            TextViewport.Model pager = model.preview();
            return "query="
                    + (model.find().text().isEmpty() ? "-" : model.find().text())
                    + " matches=" + pager.highlights().size()
                    + " active=" + (pager.active() + 1)
                    + " follow=" + (pager.followEnd() ? "on" : "off");
        }

        /** What the workspace changed, and what it is waiting for. */
        private static String counts(Model model) {
            return "tasks=" + model.tasks().content().rowCount()
                    + " done=" + complete(model.tasks().content())
                    + " target="
                    + String.format(Locale.ROOT, "%.2f", model.progress().target())
                    + " timer=" + timerText(model)
                    + " run=" + (model.timer().running() ? "on" : "off")
                    + " dialog=" + (model.dialog() ? "open" : "closed");
        }

        /** What the workspace has done, and what a test asserts about it. */
        private static String log(Model model) {
            return "synced=" + model.synced()
                    + " notes=" + model.notes()
                    + " refused=" + model.refused()
                    + " timeouts=" + model.timeouts()
                    + " last=" + model.last();
        }

        private static String progressText(Model model) {
            return AnimatedProgress.render(model.progress(), progressConfig()).plainText();
        }

        private static String timerText(Model model) {
            return Countdown.render(model.timer(), timerConfig()).plainText();
        }

        /** The focused pane's own actions, or the scene's when the scene owns the keyboard. */
        private String paneHelp(Model model, int cols) {
            KeyMap<?> map = switch (model.pane()) {
                case TABLE -> DataTable.keys(model.tasks());
                case PREVIEW -> TextViewport.keys(model.preview(), pagerConfig(1, 1));
                case FILES -> FilePicker.keys(model.files());
                default -> null;
            };
            return map == null ? "" : Help.render(map, false, cols).plainText();
        }

        private static String output(TextInput.Output output) {
            return switch (output) {
                case TextInput.Output.Submit submit -> "submitted";
                case TextInput.Output.Rejected rejected -> "rejected";
                case TextInput.Output.ValidationChanged changed -> "validated";
                case TextInput.Output.SuggestionsRequested requested -> "suggestions";
                case TextInput.Output.Copy copy -> "copy";
                case TextInput.Output.PasteRequest paste -> "paste";
            };
        }
    }
}

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
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
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
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.FilePicker;
import io.github.dhoard.braxton.widget.Help;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Phase 14 parity demo: a file picker over an owned fixture, with the application's own gestures
 * beside the picker's.
 *
 * <p>The scene owns the fixture, the two status rows and the counter that names a created file; a
 * mounted {@link FilePicker} owns the directory, its entries, the selection and the request in
 * flight. That split is the composition the widget is built for: the scene sends the picker its own
 * messages - a reload, a navigation - and reacts to the choice, the rejection or the cancellation it
 * reports. Nothing is fetched and nothing is persisted.</p>
 *
 * <p>The picker never opens the developer's home directory. Run without an argument, the demo
 * creates its own fixture under the system temporary directory and removes it when the session
 * ends; given a path, it browses that directory and stays inside it, because the demo sets the
 * picker's root to the directory it was given. The fixture holds two directories, four allowed
 * files, two files the picker refuses by extension, two hidden files and a link that points back at
 * the directory that holds it, so every policy the phase states can be watched on screen.</p>
 *
 * <p>The scene's own gestures are bubble bindings, so the focused picker sees every key first: the
 * arrows, the pages, {@code enter}, {@code esc} and the rest never reach this map, and {@code a},
 * {@code x} and {@code q} do. Deleting a file through the scene is the phase's "handles deletion"
 * case: the picker's listing still shows it, and confirming it reports that it is no longer
 * there.</p>
 */
public final class FilePickerDemo {

    /** The rows the scene paints above the picker: two status rows and its own help line. */
    public static final int STATUS_ROWS = 2;

    /** The rows the scene reserves outside the picker. */
    public static final int RESERVED_ROWS = STATUS_ROWS + 1;

    /** The extensions the demo's picker allows; every other file is listed and refused. */
    public static final List<String> EXTENSIONS = List.of(".txt", ".md");

    private static final Style STATUS = Style.NONE.withBold();

    private static final Style DETAIL = Style.NONE.withForeground(Colors.GRAY_400);

    private static final Style DIRECTORY = Style.NONE.withBold().withForeground(Colors.BLUE_400);

    private static final EffectId DELETE = EffectId.of("delete");

    private static final EffectId CREATE = EffectId.of("create");

    private FilePickerDemo() {}

    /**
     * The scene state.
     *
     * @param picker the mounted picker's own state
     * @param last what the scene or the picker last did
     * @param chosen how many entries the picker reported as chosen
     * @param rejected how many entries the picker refused
     * @param deleted how many files the scene removed
     * @param added how many files the scene created
     */
    public record Model(FilePicker.Model picker, String last, int chosen, int rejected, int deleted, int added) {

        /** Substitutes the picker's starting state for a missing one. */
        public Model {
            picker = picker == null ? FilePicker.Model.of(Path.of("")) : picker;
            last = last == null ? "none" : last;
        }

        Model withPicker(FilePicker.Model value) {
            return new Model(value, last, chosen, rejected, deleted, added);
        }

        Model withLast(String value) {
            return new Model(picker, value, chosen, rejected, deleted, added);
        }

        Model withChosen(int value) {
            return new Model(picker, last, value, rejected, deleted, added);
        }

        Model withRejected(int value) {
            return new Model(picker, last, chosen, value, deleted, added);
        }

        Model withDeleted(int value) {
            return new Model(picker, last, chosen, rejected, value, added);
        }

        Model withAdded(int value) {
            return new Model(picker, last, chosen, rejected, deleted, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A message from the mounted picker.
         *
         * @param value the picker's message
         */
        record Picker(FilePicker.Msg value) implements Msg {}

        /**
         * An output the mounted picker routed to the scene.
         *
         * @param value the picker's output
         */
        record PickerOutput(FilePicker.Output value) implements Msg {}

        /** Removes the selected file from the fixture. */
        record Delete() implements Msg {}

        /** Creates one file in the directory the picker shows. */
        record Add() implements Msg {}

        /**
         * Reports a file the scene removed.
         *
         * @param relative the removed file, relative to the fixture root
         */
        record Deleted(String relative) implements Msg {}

        /**
         * Reports a file the scene created.
         *
         * @param name the created file's name
         */
        record Added(String name) implements Msg {}

        /**
         * Reports a filesystem change the scene could not make.
         *
         * @param message what went wrong
         */
        record Failed(String message) implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions: the ones that change the fixture rather than the picker's window.
     *
     * @return the scene's actions, in help order
     */
    public static KeyMap<Msg> keys() {
        return KeyMap.of(
                KeyMap.group(
                        "Fixture",
                        KeyAction.<Msg>of("add", new Msg.Add(), "a", "add a file", "a"),
                        KeyAction.<Msg>of("delete", new Msg.Delete(), "x", "delete the file", "x")),
                KeyMap.group("Scene", KeyAction.<Msg>of("quit", new Msg.Quit(), "ctrl+c", "quit", "ctrl+c")));
    }

    /**
     * Runs the demo over a fixture it creates and removes.
     *
     * @return the session's exit result
     */
    public static ExitResult run() {
        Path root;
        try {
            root = fixture();
        } catch (IOException failure) {
            return ExitResult.failure(failure, List.of());
        }
        try {
            return run(root);
        } finally {
            remove(root);
        }
    }

    /**
     * Runs the demo over one directory.
     *
     * @param directory the directory to browse; required
     * @return the session's exit result
     */
    public static ExitResult run(Path directory) {
        return Application.run(new Scene(directory));
    }

    /**
     * Runs the demo.
     *
     * @param args the directory to browse; the demo's own fixture when absent
     */
    public static void main(String[] args) {
        ExitResult result = args.length > 0 ? run(Path.of(args[0])) : run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    /**
     * Creates the demo's own fixture under the system temporary directory.
     *
     * <p>Every name is fixed and the root is unpredictable, so a verifier can assert on the tree and
     * never on a path. The link is best-effort: a filesystem that refuses symlinks still gives a
     * working demo, it just has one entry fewer.</p>
     *
     * @return the fixture's root
     * @throws IOException when the fixture cannot be created
     */
    public static Path fixture() throws IOException {
        Path root = Files.createTempDirectory("braxton-file-picker-");
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("music"));
        Files.writeString(root.resolve("docs").resolve("guide.md"), "# Guide\n\nOne page.\n");
        Files.writeString(root.resolve("docs").resolve("notes.txt"), "notes\n");
        Files.writeString(root.resolve("readme.md"), "# Fixture\n");
        Files.writeString(root.resolve("draft.txt"), "a draft\n");
        Files.writeString(root.resolve("archive.bin"), "refused by extension\n");
        Files.writeString(root.resolve("photo.png"), "refused by extension\n");
        Files.writeString(root.resolve(".hidden.txt"), "hidden\n");
        Files.writeString(root.resolve(".config.txt"), "hidden\n");
        try {
            Files.createSymbolicLink(root.resolve("loop"), Path.of("."));
        } catch (IOException | UnsupportedOperationException noLinks) {
            // A filesystem without symlinks: the demo is still complete, one entry shorter.
        }
        return root.toRealPath();
    }

    /**
     * Removes one directory tree, and nothing else.
     *
     * @param root the tree to remove
     */
    public static void remove(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> all = new ArrayList<>(paths.toList());
            all.sort(Comparator.reverseOrder());
            for (Path path : all) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The fixture lives in the system temporary directory; a leftover is not a failure.
        }
    }

    // ── definitions ──────────────────────────────────────────────────────────

    /**
     * The picker's definition for a directory the demo was given.
     *
     * <p>The directory is also the root, so the demo cannot be walked out of the directory it was
     * asked to show, and the extensions are the demo's own policy: everything is listed, and only a
     * text file or a Markdown file can be chosen.</p>
     *
     * @param directory the directory the picker opens; required
     * @return the picker's definition
     */
    public static FilePicker.Config pickerConfig(Path directory) {
        return FilePicker.Config.defaults()
                .withDirectory(directory)
                .withRoot(directory)
                .withExtensions(EXTENSIONS.toArray(String[]::new))
                .withTheme(Theme.INSTANCE)
                .withReservedRows(RESERVED_ROWS);
    }

    /** The demo's colours: directories stand out, metadata recedes. */
    private enum Theme implements FilePicker.Theme {
        INSTANCE;

        @Override
        public Style name(FilePicker.Entry entry, boolean selected, boolean focused) {
            return entry.directory() ? DIRECTORY : Style.NONE;
        }

        @Override
        public Style detail(FilePicker.Entry entry, boolean selected, boolean focused) {
            return entry.selectable() ? DETAIL : DETAIL.withItalic();
        }

        @Override
        public Style path() {
            return STATUS;
        }

        @Override
        public Style status() {
            return DETAIL;
        }

        @Override
        public Style notice() {
            return Style.NONE.withBold();
        }

        @Override
        public Style error() {
            return Style.NONE.withBold().withForeground(Colors.ERROR_400);
        }
    }

    // ── scene ────────────────────────────────────────────────────────────────

    /** Two status rows, the mounted picker and the scene's own help line. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final Path directory;

        /**
         * A scene over one directory.
         *
         * @param directory the directory the picker opens; required
         */
        public Scene(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
        }

        /**
         * The directory this scene browses.
         *
         * @return the directory
         */
        public Path directory() {
            return directory;
        }

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            Model empty = new Model(FilePicker.Model.of(directory), "none", 0, 0, 0, 0);
            // The picker is initialized through its own mount, so its definition and its state
            // agree, and the command that reads the directory is the picker's own scoped effect.
            var started = mount(empty).initialize(context);
            return new Update<>(empty.withPicker(started.model()).withLast("started"), started.command(), List.of());
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                // The mounted picker reduces its own message into the scene's slot.
                case Msg.Picker picker -> {
                    Update<Model, Msg, Never> applied = mount(model).update(model, picker.value());
                    yield Update.next(applied.model().withLast(last(picker.value(), model.last())), applied.command());
                }
                case Msg.PickerOutput output -> reported(model, output.value());
                case Msg.Delete delete -> deleting(model);
                case Msg.Add add -> adding(model);
                case Msg.Deleted deleted -> {
                    Model removed = model.withDeleted(model.deleted() + 1).withLast("deleted-" + deleted.relative());
                    // The listing the picker holds is now stale on purpose: the reader sees the
                    // entry until a reload, and confirming it reports that it is gone.
                    yield Update.next(removed);
                }
                case Msg.Added added ->
                    Update.next(model.withAdded(model.added() + 1).withLast("added-" + added.name()));
                case Msg.Failed failed -> Update.next(model.withLast("failed-" + failed.message()));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /** The picker's own size subscription, lifted through the mount. */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return mount(model).subscriptions(model);
        }

        @Override
        public ComponentTree children(Model model) {
            return ComponentTree.of(mount(model).tree(model));
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int cols = context.viewport().cols();
            List<View<Msg>> rows = new ArrayList<>(4);
            rows.add(Views.fixed(1, Views.text(StyledText.of(status(model), STATUS))));
            rows.add(Views.fixed(1, Views.text(StyledText.of(counts(model), STATUS))));
            rows.add(mount(model).view(model, context));
            rows.add(Views.fixed(1, Views.text(Help.render(keys(), false, cols))));
            return Views.keyed(List.of(), Views.column(rows), keys().bindings());
        }

        /** The picker's own state, as one verifier-friendly row. */
        private String status(Model model) {
            FilePicker.Model picker = model.picker();
            return "dir=" + relative(picker.directory())
                    + " entries=" + picker.entries().size()
                    + " shown=" + FilePicker.shown(picker).size()
                    + " selected="
                    + orDash(FilePicker.selected(picker)
                            .map(FilePicker.Entry::name)
                            .orElse(""))
                    + " hidden=" + onOff(picker.hidden())
                    + " state=" + state(picker);
        }

        /** What the scene changed, and what the picker reported. */
        private static String counts(Model model) {
            return "last=" + model.last()
                    + " chosen=" + model.chosen()
                    + " rejected=" + model.rejected()
                    + " deleted=" + model.deleted()
                    + " added=" + model.added();
        }

        /** The object the mounted picker writes into the scene's model. */
        private Mount<Model, Msg, Never, FilePicker.Model, FilePicker.Msg, FilePicker.Output> mount(Model model) {
            return Mount.of(
                    ComponentId.of("picker"),
                    new FilePicker(pickerConfig(directory)),
                    ModelLens.of(Model::picker, (Model state, FilePicker.Model child) -> state.withPicker(child)),
                    Msg.Picker::new,
                    OutputRoute.toMessage(Msg.PickerOutput::new));
        }

        /** What the picker asked the scene to do. */
        private Update<Model, Msg, Never> reported(Model model, FilePicker.Output output) {
            return switch (output) {
                case FilePicker.Output.Chosen chosen ->
                    Update.next(model.withChosen(model.chosen() + 1).withLast("chose-" + relative(chosen.path())));
                case FilePicker.Output.Rejected rejected ->
                    Update.next(model.withRejected(model.rejected() + 1)
                            .withLast("rejected-" + relative(rejected.path()) + "-"
                                    + rejected.reason().name().toLowerCase(Locale.ROOT)));
                case FilePicker.Output.Cancelled cancelled -> Update.next(model.withLast("cancelled"), Command.quit());
            };
        }

        /** Removes the selected file, through a task rather than in this reduction. */
        private Update<Model, Msg, Never> deleting(Model model) {
            FilePicker.Entry entry = FilePicker.selected(model.picker()).orElse(null);
            if (entry == null || entry.directory()) {
                return Update.next(model.withLast("nothing-to-delete"));
            }
            Path path = entry.path();
            return Update.next(
                    model,
                    Command.<Msg, String>task(
                            DELETE,
                            () -> {
                                Files.deleteIfExists(path);
                                return relative(path);
                            },
                            Msg.Deleted::new,
                            failure -> new Msg.Failed(describe(failure))));
        }

        /** Creates one file in the directory the picker shows, then asks the picker to re-read it. */
        private Update<Model, Msg, Never> adding(Model model) {
            Path name = model.picker().directory().resolve("added-" + (model.added() + 1) + ".txt");
            return Update.next(
                    model,
                    Command.<Msg, String>task(
                            CREATE,
                            () -> {
                                Files.writeString(name, "added by the demo\n");
                                return name.getFileName().toString();
                            },
                            Msg.Added::new,
                            failure -> new Msg.Failed(describe(failure))));
        }

        /** The phrase one picker message contributes to the scene's own status row. */
        private static String last(FilePicker.Msg message, String current) {
            return switch (message) {
                case FilePicker.Msg.Up up -> "up";
                case FilePicker.Msg.Down down -> "down";
                case FilePicker.Msg.PageUp page -> "page-up";
                case FilePicker.Msg.PageDown page -> "page-down";
                case FilePicker.Msg.First first -> "first";
                case FilePicker.Msg.Last last -> "last";
                case FilePicker.Msg.Open open -> "open";
                case FilePicker.Msg.Parent parent -> "parent";
                case FilePicker.Msg.Confirm confirm -> "confirm";
                case FilePicker.Msg.Cancel cancel -> "cancel";
                case FilePicker.Msg.ToggleHidden hidden -> "hidden";
                case FilePicker.Msg.ToggleHelp help -> "help";
                case FilePicker.Msg.Reload reload -> "reload";
                default -> current;
            };
        }

        /** The picker's request as one word. */
        private static String state(FilePicker.Model picker) {
            return switch (picker.request()) {
                case FilePicker.Request.Idle idle -> "idle";
                case FilePicker.Request.Loading loading -> "loading";
                case FilePicker.Request.Failed failed -> "failed";
            };
        }

        /**
         * A path relative to the demo's root, so the status rows stay short and assertable.
         *
         * <p>The path is used as it was listed and never resolved again: a link inside the fixture
         * has to be reported as itself, not as the directory it points at.</p>
         */
        private String relative(Path path) {
            if (path.equals(directory)) {
                return "/";
            }
            return path.startsWith(directory) ? "/" + directory.relativize(path) : path.toString();
        }

        private static String describe(Throwable failure) {
            String message = failure.getMessage();
            return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
        }

        private static String onOff(boolean value) {
            return value ? "on" : "off";
        }

        private static String orDash(String value) {
            return value.isEmpty() ? "-" : value;
        }
    }
}

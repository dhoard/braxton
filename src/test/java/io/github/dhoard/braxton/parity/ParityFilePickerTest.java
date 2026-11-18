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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;

import examples.parity.FilePickerDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.internal.input.InputRouter;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.ScreenRenderer;
import io.github.dhoard.braxton.widget.FilePicker;
import io.github.dhoard.braxton.widget.Help;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 14 acceptance: entries and their metadata, listing through scoped effects, navigation and
 * rendering, and the demo scene.
 *
 * <p>Every assertion is against public behaviour: the state a message produces, the request and the
 * effect it asks for, the rows a width paints, and what a routed key does to the demo scene. The
 * fixtures are owned temporary directories, and the listing and validation tasks are run from the
 * command an update returned, so the tests exercise the shipped I/O path rather than a second copy
 * of it. No test reads a real home directory, a real account or the network.</p>
 */
class ParityFilePickerTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final InitContext START = new InitContext(new Viewport(24, 80), null);

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLS = 80;

    @TempDir
    Path temp;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static FilePicker.Config config(Path directory) {
        return FilePicker.Config.defaults().withDirectory(directory).withRows(10);
    }

    private static FilePicker picker(Path directory) {
        return new FilePicker(config(directory));
    }

    private static FilePicker picker(FilePicker.Config config) {
        return new FilePicker(config);
    }

    private static FilePicker.Entry file(String name, boolean selectable) {
        return new FilePicker.Entry(
                Path.of("/fixture").resolve(name), FilePicker.Kind.FILE, 10L, "rw-r--r--", "", selectable, false);
    }

    private static FilePicker.Entry directory(String name) {
        return new FilePicker.Entry(
                Path.of("/fixture").resolve(name), FilePicker.Kind.DIRECTORY, -1L, "rwxr-xr-x", "", false, true);
    }

    private static List<FilePicker.Entry> entries(int count) {
        List<FilePicker.Entry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(file("file-" + (index < 10 ? "0" + index : String.valueOf(index)) + ".txt", true));
        }
        return List.copyOf(entries);
    }

    private static FilePicker.Model model(FilePicker.Entry... entries) {
        return FilePicker.Model.of(Path.of("/fixture"), List.of(entries));
    }

    private static FilePicker.Model model(List<FilePicker.Entry> entries) {
        return FilePicker.Model.of(Path.of("/fixture"), entries);
    }

    /** A hand-built state with an explicit height, so a window's arithmetic is its own. */
    private static FilePicker.Model model(int rows, List<FilePicker.Entry> entries) {
        return FilePicker.Model.of(Path.of("/fixture"), entries).withRows(rows);
    }

    private static Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> send(
            FilePicker component, FilePicker.Model state, FilePicker.Msg message) {
        return component.update(state, message);
    }

    private static FilePicker.Model apply(FilePicker component, FilePicker.Model state, FilePicker.Msg message) {
        return send(component, state, message).model();
    }

    private static FilePicker.Model apply(FilePicker.Model state, FilePicker.Msg message) {
        return apply(picker(Path.of("/fixture")), state, message);
    }

    /**
     * Runs the tasks an update asked for and returns the messages they produced.
     *
     * <p>The picker's I/O lives in task bodies, and a test that skipped them would assert against a
     * stand-in for the very thing the phase is about. Running them here is running the shipped
     * listing and validation code; nothing else is interpreted, and no executor is started.</p>
     */
    private static <Msg> List<Msg> run(Command<Msg> command) {
        List<Msg> messages = new ArrayList<>();
        collect(command, messages);
        return messages;
    }

    private static <Msg> void collect(Command<Msg> command, List<Msg> messages) {
        switch (command) {
            case Command.None<Msg> none -> {}
            case Command.Batch<Msg> batch -> {
                for (Command<Msg> item : batch.commands()) {
                    collect(item, messages);
                }
            }
            case Command.Scoped<Msg> scoped -> collect(scoped.command(), messages);
            case Command.Dispatch<Msg> dispatch -> messages.add(dispatch.message());
            case Command.Task<Msg> task -> {
                try {
                    messages.add(task.onSuccess().apply(task.work().call()));
                } catch (Throwable failure) {
                    messages.add(task.onFailure().apply(failure));
                }
            }
            default -> {}
        }
    }

    /** The one message a single update asked for. */
    private static FilePicker.Msg ran(FilePicker component, FilePicker.Model state, FilePicker.Msg message) {
        List<FilePicker.Msg> messages = run(component.update(state, message).command());
        assertThat(messages).as("one effect message").hasSize(1);
        return messages.get(0);
    }

    /** The state the picker reaches once the request one message started has answered. */
    private static FilePicker.Model answered(FilePicker component, FilePicker.Model state, FilePicker.Msg message) {
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> update = component.update(state, message);
        FilePicker.Model next = update.model();
        for (FilePicker.Msg answer : run(update.command())) {
            next = component.update(next, answer).model();
        }
        return next;
    }

    /** The state the picker is in before its first listing arrives. */
    private static FilePicker.Model started(FilePicker component) {
        return component.init(START).model();
    }

    /** The state after the picker's own listing of the directory its definition names. */
    private static FilePicker.Model loaded(FilePicker component) {
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> init = component.init(START);
        FilePicker.Model next = init.model();
        for (FilePicker.Msg message : run(init.command())) {
            next = component.update(next, message).model();
        }
        return next;
    }

    private static FilePicker.Entry entry(FilePicker.Model state, String name) {
        return FilePicker.shown(state).stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry " + name + " in " + names(FilePicker.shown(state))));
    }

    private static List<String> names(List<FilePicker.Entry> entries) {
        return entries.stream().map(FilePicker.Entry::name).toList();
    }

    private static List<String> painted(FilePicker component, FilePicker.Model state) {
        return painted(component, state, SCREEN_ROWS, SCREEN_COLS);
    }

    private static List<String> painted(FilePicker component, FilePicker.Model state, int rows, int cols) {
        return new ScreenRenderer()
                .plainLines(component.view(state, new ViewContext(new Viewport(rows, cols))), new Viewport(rows, cols));
    }

    private static List<StyledText> lines(FilePicker component, FilePicker.Model state, int width) {
        return FilePicker.lines(state, component.config(), width);
    }

    private static String row(FilePicker component, FilePicker.Model state, int index) {
        return painted(component, state).get(index).stripTrailing();
    }

    private static InputEvent.KeyInput key(String spec) {
        Key parsed = Key.parse(spec);
        return InputEvent.KeyInput.of(parsed, parsed.name());
    }

    /** The first row of the picker's own screen that holds a phrase, or -1. */
    private static int indexOf(FilePicker component, FilePicker.Model state, String phrase) {
        List<String> rows = painted(component, state);
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).contains(phrase)) {
                return index;
            }
        }
        return -1;
    }

    private static Optional<FilePicker.Msg> route(FilePicker component, FilePicker.Model state, String spec) {
        return InputRouter.route(component.view(state, CONTEXT), key(spec));
    }

    // ── R-01: entries, ordering, filters and links ──────────────────────────

    @Test
    void aListingSortsDirectoriesFirstThenNamesIgnoringCase() throws IOException {
        Files.createDirectories(temp.resolve("beta"));
        Files.createDirectories(temp.resolve("Alpha"));
        Files.writeString(temp.resolve("cherry.md"), "c");
        Files.writeString(temp.resolve("apple.txt"), "a");
        Files.writeString(temp.resolve("Banana.txt"), "b");
        FilePicker component = picker(temp);
        FilePicker.Model state = loaded(component);
        assertThat(names(FilePicker.shown(state)))
                .containsExactly("Alpha", "beta", "apple.txt", "Banana.txt", "cherry.md");
        assertThat(state.request()).isInstanceOf(FilePicker.Request.Idle.class);
        assertThat(state.directory()).isEqualTo(temp.toRealPath());
        assertThat(state.selected()).isEqualTo("Alpha");
    }

    @Test
    void theFirstListingIsTheOneThePickerAsksForWhenItStarts() throws IOException {
        Files.writeString(temp.resolve("notes.txt"), "n");
        FilePicker component = picker(temp);
        FilePicker.Model before = started(component);
        assertThat(before.request()).isInstanceOf(FilePicker.Request.Loading.class);
        assertThat(before.entries()).isEmpty();
        assertThat(before.directory()).isEqualTo(temp);
        FilePicker.Model after = loaded(component);
        assertThat(after.epoch()).isEqualTo(before.epoch());
        assertThat(names(FilePicker.shown(after))).containsExactly("notes.txt");
    }

    @Test
    void hiddenEntriesAreListedButOnlyShownWhenTheToggleIsOn() throws IOException {
        Files.writeString(temp.resolve(".secret.txt"), "s");
        Files.writeString(temp.resolve("visible.txt"), "v");
        FilePicker component = picker(temp);
        FilePicker.Model state = loaded(component);
        assertThat(names(state.entries())).containsExactly(".secret.txt", "visible.txt");
        assertThat(names(FilePicker.shown(state))).containsExactly("visible.txt");
        FilePicker.Model shown = apply(component, state, new FilePicker.Msg.ToggleHidden());
        assertThat(shown.hidden()).isTrue();
        assertThat(names(FilePicker.shown(shown))).containsExactly(".secret.txt", "visible.txt");
        assertThat(apply(component, shown, new FilePicker.Msg.ToggleHidden()).hidden())
                .isFalse();
    }

    @Test
    void hidingTheSelectedEntrySelectsTheNearestEntryThatStaysVisible() throws IOException {
        Files.writeString(temp.resolve(".secret.txt"), "s");
        Files.writeString(temp.resolve("a.txt"), "a");
        Files.writeString(temp.resolve("b.txt"), "b");
        FilePicker component = picker(temp);
        FilePicker.Model state = apply(component, loaded(component), new FilePicker.Msg.ToggleHidden());
        FilePicker.Model hidden = apply(component, state, new FilePicker.Msg.First());
        assertThat(hidden.selected()).isEqualTo(".secret.txt");
        FilePicker.Model visible = apply(component, hidden, new FilePicker.Msg.ToggleHidden());
        assertThat(visible.selected()).isEqualTo("a.txt");
        assertThat(FilePicker.selected(visible).map(FilePicker.Entry::name)).contains("a.txt");
    }

    @Test
    void anAllowedExtensionDecidesWhichFilesCanBeChosen() throws IOException {
        Files.writeString(temp.resolve("notes.txt"), "n");
        Files.writeString(temp.resolve("archive.bin"), "x");
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withExtensions(".txt", "md"));
        FilePicker.Model state = loaded(component);
        assertThat(entry(state, "notes.txt").selectable()).isTrue();
        assertThat(entry(state, "archive.bin").selectable())
                .as("a disallowed extension is listed and refused")
                .isFalse();
        assertThat(component.config().allows("notes.txt")).isTrue();
        assertThat(component.config().allows("NOTES.TXT"))
                .as("matching is case-insensitive")
                .isTrue();
        assertThat(component.config().allows("archive.bin")).isFalse();
        assertThat(component.config().allows(".txt"))
                .as("a dot file has no extension")
                .isFalse();
        assertThat(component.config().allows("Makefile")).isFalse();
        assertThat(FilePicker.Config.defaults().allows("anything.bin"))
                .as("an empty set allows every regular file")
                .isTrue();
    }

    @Test
    void confirmingADisabledFileIsRejectedWithoutChoosingIt() throws IOException {
        Files.writeString(temp.resolve("archive.bin"), "x");
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withExtensions("txt"));
        FilePicker.Model state = loaded(component);
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> update =
                component.update(state, new FilePicker.Msg.Confirm());
        assertThat(update.outputs())
                .containsExactly(new FilePicker.Output.Rejected(
                        temp.toRealPath().resolve("archive.bin"), FilePicker.Reason.EXTENSION));
        assertThat(update.command())
                .as("a refused entry asks the filesystem nothing")
                .isEqualTo(Command.none());
        assertThat(update.model().notice()).isEqualTo("archive.bin: extension not allowed");
    }

    @Test
    void metadataPaintsSizePermissionsAndLinkTarget() {
        FilePicker.Entry folder =
                new FilePicker.Entry(Path.of("/f/docs"), FilePicker.Kind.DIRECTORY, -1L, "rwxr-xr-x", "", false, true);
        FilePicker.Entry notes = new FilePicker.Entry(
                Path.of("/f/notes.txt"), FilePicker.Kind.FILE, 1536L, "rw-r--r--", "", true, false);
        FilePicker.Entry link = new FilePicker.Entry(
                Path.of("/f/readme.md"), FilePicker.Kind.FILE, 1536L, "rw-r--r--", "../readme.md", true, false);
        FilePicker.Entry gone = new FilePicker.Entry(
                Path.of("/f/gone.txt"), FilePicker.Kind.MISSING, -1L, "", "gone.txt", false, false);
        assertThat(folder.detail()).isEqualTo("rwxr-xr-x  dir");
        assertThat(notes.detail()).isEqualTo("rw-r--r--  1.5K");
        assertThat(link.detail()).isEqualTo("rw-r--r--  1.5K  -> ../readme.md");
        assertThat(gone.detail()).isEqualTo("missing  -> gone.txt");
        assertThat(FilePicker.Entry.bytes(0)).isEqualTo("0B");
        assertThat(FilePicker.Entry.bytes(1023)).isEqualTo("1023B");
        assertThat(FilePicker.Entry.bytes(1024)).isEqualTo("1.0K");
        assertThat(FilePicker.Entry.bytes(1024L * 1024 * 3)).isEqualTo("3.0M");
        assertThat(FilePicker.Entry.bytes(-1)).isEmpty();
        assertThat(notes.link()).isFalse();
        assertThat(link.link()).isTrue();
        assertThat(folder.directory()).isTrue();
        assertThat(notes.name()).isEqualTo("notes.txt");
    }

    @Test
    void anEntryOnAFileSystemWithoutPermissionsPaintsNoPermissionString() {
        FilePicker.Entry notes =
                new FilePicker.Entry(Path.of("/f/notes.txt"), FilePicker.Kind.FILE, 12L, "", "", true, false);
        assertThat(notes.detail()).isEqualTo("12B");
    }

    @Test
    void aSymlinkedDirectoryIsNavigableUnlessItLoopsOrLeavesTheRoot() throws IOException {
        Path root = Files.createDirectory(temp.resolve("root"));
        Files.createDirectory(root.resolve("real"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("loop"), Path.of("."));
        Files.createSymbolicLink(root.resolve("escape"), outside);
        Files.createSymbolicLink(root.resolve("inside"), root.resolve("real"));
        FilePicker component = picker(FilePicker.Config.defaults()
                .withDirectory(root)
                .withRoot(root)
                .withSelectable(FilePicker.Kind.FILE, FilePicker.Kind.DIRECTORY));
        FilePicker.Model state = loaded(component);
        assertThat(entry(state, "inside").navigable()).isTrue();
        assertThat(entry(state, "inside").selectable())
                .as("a directory policy selects it")
                .isTrue();
        assertThat(entry(state, "loop").navigable())
                .as("a link to its own directory loops")
                .isFalse();
        assertThat(entry(state, "loop").selectable()).isFalse();
        assertThat(entry(state, "escape").navigable())
                .as("a link outside the root escapes")
                .isFalse();
        assertThat(entry(state, "escape").selectable()).isFalse();
        assertThat(entry(state, "escape").target()).isEqualTo(outside.toString());
        assertThat(state.root()).isEqualTo(root.toRealPath());
    }

    @Test
    void refusingLinksListsThemAndSelectsNothing() throws IOException {
        Path root = Files.createDirectory(temp.resolve("root"));
        Files.createDirectory(root.resolve("real"));
        Files.createSymbolicLink(root.resolve("docs"), root.resolve("real"));
        FilePicker component = picker(
                FilePicker.Config.defaults().withDirectory(root).withRoot(root).withFollowLinks(false));
        FilePicker.Model state = loaded(component);
        assertThat(entry(state, "docs").link()).isTrue();
        assertThat(entry(state, "docs").kind()).isEqualTo(FilePicker.Kind.DIRECTORY);
        assertThat(entry(state, "docs").navigable()).isFalse();
        assertThat(entry(state, "docs").selectable()).isFalse();
    }

    @Test
    void aDanglingLinkIsListedAsMissingAndIsNeverChosen() throws IOException {
        Files.createSymbolicLink(temp.resolve("gone.txt"), temp.resolve("nothing-here.txt"));
        FilePicker component = picker(temp);
        FilePicker.Model state = loaded(component);
        FilePicker.Entry gone = entry(state, "gone.txt");
        assertThat(gone.kind()).isEqualTo(FilePicker.Kind.MISSING);
        assertThat(gone.selectable()).isFalse();
        assertThat(gone.navigable()).isFalse();
        assertThat(gone.link()).isTrue();
        assertThat(apply(component, state, new FilePicker.Msg.Confirm()).notice())
                .isEqualTo("gone.txt: no longer there");
    }

    @Test
    void aDirectoryPolicyMakesDirectoriesChoosableAndStillNavigable() throws IOException {
        Files.createDirectory(temp.resolve("docs"));
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withSelectable(FilePicker.Kind.DIRECTORY));
        FilePicker.Model state = loaded(component);
        assertThat(entry(state, "docs").selectable()).isTrue();
        assertThat(entry(state, "docs").navigable()).isTrue();
        assertThat(FilePicker.keys(state).action("open").orElseThrow().enabled())
                .as("a choosable directory is still a directory")
                .isTrue();
        FilePicker.Msg checked = ran(component, state, new FilePicker.Msg.Confirm());
        assertThat(checked)
                .isEqualTo(new FilePicker.Msg.Validated(
                        state.epoch(), temp.toRealPath().resolve("docs"), null));
        assertThat(component.update(state, checked).outputs())
                .containsExactly(new FilePicker.Output.Chosen(temp.toRealPath().resolve("docs")));
    }

    @Test
    void unicodeNamesSortAndPaintAsThemselves() throws IOException {
        Files.writeString(temp.resolve("caf\u00e9.txt"), "c");
        Files.writeString(temp.resolve("\u00e4rger.txt"), "a");
        Files.writeString(temp.resolve("zebra.txt"), "z");
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withExtensions("txt"));
        FilePicker.Model state = loaded(component);
        // Ordering is case-insensitive by code point and never by locale collation, so a name that
        // ends in an umlaut sorts where its character does.
        assertThat(names(FilePicker.shown(state))).containsExactly("caf\u00e9.txt", "zebra.txt", "\u00e4rger.txt");
        assertThat(indexOf(component, state, "caf\u00e9.txt")).isEqualTo(1);
    }

    @Test
    void aNameThatCarriesTerminalControlsIsPaintedAsText() throws IOException {
        // A file name may hold anything but a slash and a NUL, including the bytes that begin a
        // terminal command. The path row and the entry row are the picker's own strings, so this is
        // where "display the path safely" is proven rather than assumed.
        Files.writeString(temp.resolve("red\u001b[31m.txt"), "x");
        FilePicker component = picker(temp);
        FilePicker.Model state = loaded(component);
        String painted = String.join("\n", painted(component, state));
        assertThat(painted).doesNotContain("\u001b");
        assertThat(painted).contains("red[31m.txt");
        assertThat(FilePicker.shown(state).get(0).name()).isEqualTo("red\u001b[31m.txt");
    }

    @Test
    void aConfigurationRejectsAnEmptyMaximumAndNormalizesExtensions() {
        FilePicker.Config config =
                FilePicker.Config.defaults().withMaxEntries(0).withExtensions("TXT", ".Md", " ", null);
        assertThat(config.maxEntries()).isEqualTo(1);
        assertThat(config.extensions()).containsExactlyInAnyOrder(".txt", ".md");
        assertThat(config.selectable()).containsExactly(FilePicker.Kind.FILE);
        assertThat(FilePicker.Config.defaults().directory()).isEqualTo(Path.of(""));
    }

    // ── R-02: requests, epochs and revalidation ─────────────────────────────

    @Test
    void aStaleListingIsDiscarded() throws IOException {
        Files.createDirectory(temp.resolve("one"));
        Files.createDirectory(temp.resolve("two"));
        FilePicker component = picker(temp);
        FilePicker.Model state = loaded(component);
        long epoch = state.epoch();
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> stale = component.update(
                state,
                new FilePicker.Msg.Listed(
                        epoch - 1, temp.resolve("two"), null, List.of(file("stale.txt", true)), false));
        assertThat(stale.model()).isSameAs(state);
        assertThat(stale.command()).isEqualTo(Command.none());
        FilePicker.Model fresh = component
                .update(state, new FilePicker.Msg.Listed(epoch, temp, temp, List.of(file("fresh.txt", true)), false))
                .model();
        assertThat(names(FilePicker.shown(fresh))).containsExactly("fresh.txt");
    }

    @Test
    void rapidNavigationKeepsOnlyTheLastRequest() throws IOException {
        Files.createDirectory(temp.resolve("one"));
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withRoot(temp));
        FilePicker.Model state = loaded(component);
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> opening =
                component.update(state, new FilePicker.Msg.Open());
        FilePicker.Model openingState = opening.model();
        assertThat(openingState.epoch()).isEqualTo(state.epoch() + 1);
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> again =
                component.update(openingState, new FilePicker.Msg.Reload());
        FilePicker.Model current = again.model();
        assertThat(current.epoch()).isEqualTo(openingState.epoch() + 1);
        // The first request answers late: its epoch is no longer the state's, so it changes nothing.
        FilePicker.Msg late = ran(component, state, new FilePicker.Msg.Open());
        assertThat(component.update(current, late).model()).isSameAs(current);
        FilePicker.Msg right = ran(component, openingState, new FilePicker.Msg.Reload());
        assertThat(component.update(current, right).model().request()).isInstanceOf(FilePicker.Request.Idle.class);
    }

    @Test
    void everyRequestRegistersUnderOneReplaceableTaskId() throws IOException {
        Files.writeString(temp.resolve("notes.txt"), "n");
        FilePicker component = picker(temp);
        FilePicker.Model state = loaded(component);
        assertThat(component.update(state, new FilePicker.Msg.Reload()).command())
                .isInstanceOfSatisfying(
                        Command.Task.class, task -> assertThat(task.id()).isEqualTo(EffectId.of("listing")));
        assertThat(component.update(state, new FilePicker.Msg.Confirm()).command())
                .as("a check is a different registration")
                .isInstanceOfSatisfying(
                        Command.Task.class, task -> assertThat(task.id()).isEqualTo(EffectId.of("validation")));
    }

    @Test
    void aFailedListingKeepsTheLastListingAndCanBeRetried() throws IOException {
        Files.writeString(temp.resolve("kept.txt"), "k");
        FilePicker component = picker(temp);
        FilePicker.Model state = loaded(component);
        FilePicker.Model failed = component
                .update(state, new FilePicker.Msg.Failed(state.epoch(), temp, "permission denied: " + temp))
                .model();
        assertThat(names(FilePicker.shown(failed))).containsExactly("kept.txt");
        assertThat(failed.directory()).isEqualTo(state.directory());
        assertThat(failed.request()).isInstanceOf(FilePicker.Request.Failed.class);
        // Leave room for the host's temporary path as well as the error and retry hint.
        int columns = SCREEN_COLS + TextMeasure.columns(temp.toString());
        assertThat(painted(component, failed, SCREEN_ROWS, columns))
                .anySatisfy(line -> assertThat(line).contains("permission denied: " + temp, "r to retry"));
        FilePicker.Model retried = answered(component, failed, new FilePicker.Msg.Reload());
        assertThat(retried.request()).isInstanceOf(FilePicker.Request.Idle.class);
        assertThat(retried.notice()).isEmpty();
    }

    @Test
    void anUnreadableDirectoryIsReportedAsAFailure() throws IOException {
        Path locked = Files.createDirectory(temp.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            FilePicker component = picker(locked);
            FilePicker.Msg message = ran(component, started(component), new FilePicker.Msg.Reload());
            assertThat(message).isInstanceOf(FilePicker.Msg.Failed.class);
            assertThat(((FilePicker.Msg.Failed) message).message()).contains("permission denied");
            assertThat(((FilePicker.Msg.Failed) message).epoch())
                    .isEqualTo(started(component).epoch() + 1);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void aVanishedEntryIsRejectedWhenTheChoiceIsChecked() throws IOException {
        Path notes = Files.writeString(temp.resolve("notes.txt"), "n");
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withExtensions("txt"));
        FilePicker.Model state = loaded(component);
        Files.delete(notes);
        FilePicker.Msg checked = ran(component, state, new FilePicker.Msg.Confirm());
        assertThat(checked)
                .isEqualTo(new FilePicker.Msg.Validated(
                        state.epoch(), state.directory().resolve("notes.txt"), FilePicker.Reason.MISSING));
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> update = component.update(state, checked);
        assertThat(update.outputs())
                .containsExactly(new FilePicker.Output.Rejected(
                        temp.toRealPath().resolve("notes.txt"), FilePicker.Reason.MISSING));
        assertThat(update.model().notice()).isEqualTo("notes.txt: no longer there");
    }

    @Test
    void anEntryReplacedByADirectoryIsRejectedWhenTheChoiceIsChecked() throws IOException {
        Path notes = Files.writeString(temp.resolve("notes.txt"), "n");
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withExtensions("txt"));
        FilePicker.Model state = loaded(component);
        Files.delete(notes);
        Files.createDirectory(notes);
        assertThat(ran(component, state, new FilePicker.Msg.Confirm()))
                .isEqualTo(new FilePicker.Msg.Validated(
                        state.epoch(), state.directory().resolve("notes.txt"), FilePicker.Reason.KIND));
    }

    @Test
    void anEntryReplacedUnderTheSameNameIsChosen() throws IOException {
        Path notes = Files.writeString(temp.resolve("notes.txt"), "first");
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withExtensions("txt"));
        FilePicker.Model state = loaded(component);
        Files.delete(notes);
        Files.writeString(notes, "a different body, same name");
        FilePicker.Msg checked = ran(component, state, new FilePicker.Msg.Confirm());
        assertThat(checked)
                .isEqualTo(new FilePicker.Msg.Validated(
                        state.epoch(), state.directory().resolve("notes.txt"), null));
        assertThat(component.update(state, checked).outputs())
                .containsExactly(new FilePicker.Output.Chosen(temp.toRealPath().resolve("notes.txt")));
    }

    @Test
    void aCheckThatAnswersForAnOldRequestChangesNothing() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(8));
        FilePicker.Model state = model(file("notes.txt", true)).withEpoch(4L);
        FilePicker.Msg stale =
                new FilePicker.Msg.Validated(3L, state.directory().resolve("notes.txt"), null);
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> update = component.update(state, stale);
        assertThat(update.model()).isSameAs(state);
        assertThat(update.outputs()).isEmpty();
    }

    @Test
    void tooManyEntriesAreTruncatedAndReported() throws IOException {
        for (int index = 0; index < 5; index++) {
            Files.writeString(temp.resolve("file-" + index + ".txt"), "x");
        }
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withMaxEntries(3));
        FilePicker.Model state = loaded(component);
        assertThat(state.entries()).hasSize(3);
        assertThat(state.truncated()).isTrue();
        assertThat(indexOf(component, state, "truncated at 3")).isGreaterThanOrEqualTo(0);
    }

    @Test
    void aListingCanBeReadTwiceAndTheSecondReadIsClean() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "a");
        FilePicker component = picker(temp);
        FilePicker.Model state = loaded(component);
        FilePicker.Model again = answered(component, state, new FilePicker.Msg.Reload());
        assertThat(again.request()).isInstanceOf(FilePicker.Request.Idle.class);
        assertThat(names(FilePicker.shown(again))).containsExactly("a.txt");
        assertThat(again.selected()).isEqualTo("a.txt");
    }

    @Test
    void aListingInterruptedBeforeItStartsPublishesNothing() throws IOException {
        Files.writeString(temp.resolve("a.txt"), "a");
        FilePicker component = picker(temp);
        FilePicker.Model state = loaded(component);
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> reload =
                component.update(state, new FilePicker.Msg.Reload());
        FilePicker.Model loading = reload.model();
        Thread.currentThread().interrupt();
        List<FilePicker.Msg> messages;
        try {
            messages = run(reload.command());
        } finally {
            Thread.interrupted();
        }
        assertThat(messages).hasSize(1);
        FilePicker.Msg.Failed failed = (FilePicker.Msg.Failed) messages.get(0);
        assertThat(failed.message())
                .isEqualTo(
                        "cancelled: listing interrupted: " + state.directory().toRealPath());
        // The interrupted request is the one this state asked for, so a reader still waiting sees
        // the failure; a state that has moved on has a newer epoch and this changes nothing.
        assertThat(component.update(loading, failed).model().request()).isInstanceOf(FilePicker.Request.Failed.class);
        FilePicker.Model newer = loading.withEpoch(loading.epoch() + 1);
        assertThat(component.update(newer, failed).model()).isSameAs(newer);
    }

    // ── R-03: navigation, selection and rendering ───────────────────────────

    @Test
    void movingTheSelectionKeepsItInsideTheWindow() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(6));
        FilePicker.Model state = model(6, entries(20));
        FilePicker.Model last = state;
        for (int index = 0; index < 20; index++) {
            last = apply(component, last, new FilePicker.Msg.Down());
            int top = FilePicker.top(last, component.config());
            int selected = FilePicker.selectedIndex(last);
            int content = FilePicker.contentRows(last, component.config());
            assertThat(selected).isBetween(top, Math.max(top, top + content - 1));
        }
        assertThat(FilePicker.selectedIndex(last)).isEqualTo(19);
        assertThat(FilePicker.top(last, component.config()))
                .isEqualTo(20 - FilePicker.contentRows(last, component.config()));
        assertThat(FilePicker.selectedIndex(apply(component, last, new FilePicker.Msg.First())))
                .isEqualTo(0);
        assertThat(FilePicker.selectedIndex(apply(component, last, new FilePicker.Msg.Last())))
                .isEqualTo(19);
    }

    @Test
    void aPageMovesTheSelectionAndTheWindowByOneWindow() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(6));
        FilePicker.Model state = model(6, entries(20));
        int content = FilePicker.contentRows(state, component.config());
        assertThat(content).isEqualTo(3);
        FilePicker.Model page = apply(component, state, new FilePicker.Msg.PageDown());
        assertThat(FilePicker.selectedIndex(page)).isEqualTo(content);
        assertThat(FilePicker.top(page, component.config())).isEqualTo(content);
        FilePicker.Model back = apply(component, page, new FilePicker.Msg.PageUp());
        assertThat(FilePicker.selectedIndex(back)).isEqualTo(0);
        assertThat(FilePicker.top(back, component.config())).isEqualTo(0);
    }

    @Test
    void openingADirectoryReadsItAndGoingToTheParentSelectsIt() throws IOException {
        Files.createDirectory(temp.resolve("docs"));
        Files.writeString(temp.resolve("notes.txt"), "n");
        FilePicker component =
                picker(FilePicker.Config.defaults().withDirectory(temp).withRoot(temp));
        FilePicker.Model root = apply(component, loaded(component), new FilePicker.Msg.First());
        assertThat(root.selected()).isEqualTo("docs");
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> opened =
                component.update(root, new FilePicker.Msg.Open());
        assertThat(((FilePicker.Request.Loading) opened.model().request()).target())
                .isEqualTo(root.directory().resolve("docs"));
        FilePicker.Model inside =
                component.update(opened.model(), run(opened.command()).get(0)).model();
        assertThat(inside.directory()).isEqualTo(root.directory().resolve("docs"));
        assertThat(inside.entries()).isEmpty();
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> up =
                component.update(inside, new FilePicker.Msg.Parent());
        assertThat(((FilePicker.Request.Loading) up.model().request()).target()).isEqualTo(root.directory());
        FilePicker.Model back =
                component.update(up.model(), run(up.command()).get(0)).model();
        assertThat(back.directory()).isEqualTo(root.directory());
        assertThat(back.selected()).isEqualTo("docs");
        assertThat(FilePicker.selected(back).map(FilePicker.Entry::name)).contains("docs");
    }

    @Test
    void theParentGestureStopsAtTheConfiguredRoot() throws IOException {
        Path root = Files.createDirectory(temp.resolve("root"));
        Files.createDirectory(root.resolve("docs"));
        FilePicker fromRoot =
                picker(FilePicker.Config.defaults().withDirectory(root).withRoot(root));
        FilePicker.Model rootState = loaded(fromRoot);
        assertThat(FilePicker.keys(rootState).action("parent").orElseThrow().enabled())
                .as("the configured root has no parent inside the boundary")
                .isFalse();
        FilePicker.Model blocked = apply(fromRoot, rootState, new FilePicker.Msg.Parent());
        assertThat(blocked.request()).as("no request leaves the root").isInstanceOf(FilePicker.Request.Idle.class);
        assertThat(blocked.notice()).isEqualTo("at the root");
        FilePicker fromDocs = picker(
                FilePicker.Config.defaults().withDirectory(root.resolve("docs")).withRoot(root));
        FilePicker.Model docs = loaded(fromDocs);
        assertThat(FilePicker.keys(docs).action("parent").orElseThrow().enabled())
                .as("a directory above the root is reachable")
                .isTrue();
    }

    @Test
    void confirmingADirectoryEntersItWhenOnlyFilesAreSelectable() {
        FilePicker component = picker(FilePicker.Config.defaults().withDirectory(Path.of("/fixture")));
        FilePicker.Model state = model(directory("docs"), file("notes.txt", true));
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> update =
                component.update(state, new FilePicker.Msg.Confirm());
        assertThat(update.outputs()).isEmpty();
        assertThat(update.model().request()).isInstanceOf(FilePicker.Request.Loading.class);
    }

    @Test
    void anEmptyDirectoryHasNothingSelectedAndRefusesToChoose() {
        FilePicker component = picker(FilePicker.Config.defaults());
        FilePicker.Model state = model();
        assertThat(FilePicker.selected(state)).isEmpty();
        assertThat(FilePicker.selectedIndex(state)).isEqualTo(-1);
        assertThat(FilePicker.keys(state).action("confirm").orElseThrow().enabled())
                .isFalse();
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> update =
                component.update(state, new FilePicker.Msg.Confirm());
        assertThat(update.outputs()).isEmpty();
        assertThat(update.command()).isEqualTo(Command.none());
        assertThat(update.model().notice()).isEqualTo("nothing selected");
    }

    @Test
    void anEmptyDirectoryPaintsItsEmptyState() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(6));
        FilePicker.Model state = model(6, List.of());
        List<String> rows = lines(component, state, 40).stream()
                .map(StyledText::plainText)
                .map(String::stripTrailing)
                .toList();
        assertThat(rows).hasSize(6);
        assertThat(rows.get(0)).isEqualTo("/fixture");
        assertThat(rows.get(1)).as("the entry rows are blank").isEmpty();
        assertThat(rows.get(4)).isEqualTo("empty directory");
        assertThat(rows.get(5)).as("help is the last row").contains("parent directory");
    }

    @Test
    void thePathRowKeepsTheEndOfALongPath() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(1));
        Path deep = Path.of("/home/reader/projects/braxton/src/main/java/widget");
        FilePicker.Model state = FilePicker.Model.of(deep, List.of());
        String narrow = lines(component, state, 20).get(0).plainText();
        assertThat(TextMeasure.columns(narrow)).isLessThanOrEqualTo(20);
        assertThat(narrow).startsWith(FilePicker.ELLIPSIS);
        assertThat(narrow).endsWith("java/widget");
        assertThat(lines(component, state, 200).get(0).plainText()).isEqualTo(deep.toString());
    }

    @Test
    void everyRowStaysInsideTheWidthItIsGiven() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(8));
        FilePicker.Model state = model(
                8,
                List.of(
                        directory("a directory with a very long name indeed"),
                        file("a-file-with-a-long-name.txt", true),
                        file("\u65e5\u672c\u8a9e\u306e\u30d5\u30a1\u30a4\u30eb.txt", true)));
        for (int width = 0; width <= 40; width++) {
            List<StyledText> painted = lines(component, state, width);
            assertThat(painted).as("width " + width).hasSize(8);
            for (StyledText line : painted) {
                assertThat(TextMeasure.columns(line.plainText()))
                        .as("width " + width + ": " + line.plainText())
                        .isLessThanOrEqualTo(Math.max(0, width));
            }
        }
    }

    @Test
    void aPickerShorterThanItsOwnChromePaintsExactlyTheRowsItWasGiven() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(4));
        FilePicker.Model state = model(List.of(file("notes.txt", true), file("other.txt", true)));
        for (int rows = 0; rows <= 5; rows++) {
            List<StyledText> painted = lines(component, state.withRows(rows), 40);
            assertThat(painted).as("rows " + rows).hasSize(rows);
        }
        assertThat(lines(component, state.withRows(1), 40).get(0).plainText()).isEqualTo("/fixture");
        assertThat(lines(component, state.withRows(2), 40).get(1).plainText())
                .as("the status row survives the help rows")
                .isEqualTo("2 entries");
    }

    @Test
    void aZeroHeightPickerPaintsNothing() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(4));
        assertThat(lines(component, model(file("a.txt", true)).withRows(0), 80)).isEmpty();
        assertThat(painted(component, model(file("a.txt", true)).withRows(0), 4, 80))
                .hasSizeLessThanOrEqualTo(4);
    }

    @Test
    void helpShowsTheGesturesThisStateHandles() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(8));
        FilePicker.Model state = model(directory("docs"), file("notes.txt", true));
        String compact = Help.render(FilePicker.keys(state), false, 200).plainText();
        assertThat(compact).contains("open directory").contains("choose").contains("cancel");
        assertThat(Help.render(FilePicker.keys(state), true, 400).plainText()).contains("hidden files");
        String nothing = Help.render(FilePicker.keys(model()), false, 200).plainText();
        assertThat(nothing).doesNotContain("open directory");
        assertThat(nothing).doesNotContain("page up");
        FilePicker.Model expanded = apply(component, state, new FilePicker.Msg.ToggleHelp());
        assertThat(Help.render(FilePicker.keys(expanded), true, 400).lines()).hasSizeGreaterThan(1);
        assertThat(FilePicker.contentRows(expanded, component.config()))
                .isLessThan(FilePicker.contentRows(state, component.config()));
    }

    @Test
    void theStatusRowReportsTheRequestInFlightAndTheNoticeOfTheLastGesture() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(6));
        FilePicker.Model loading = new FilePicker.Model(
                Path.of("/fixture"),
                null,
                List.of(file("notes.txt", true)),
                "notes.txt",
                0,
                6,
                false,
                false,
                true,
                2L,
                new FilePicker.Request.Loading(Path.of("/fixture/docs"), ""),
                false,
                "");
        assertThat(indexOf(component, loading, "loading /fixture/docs")).isEqualTo(4);
        FilePicker.Model top = apply(
                component,
                model(6, List.of(file("notes.txt", true))).withDirectory(Path.of("/")),
                new FilePicker.Msg.Parent());
        assertThat(indexOf(component, top, "at the root")).isEqualTo(4);
        FilePicker.Model hidden = model(6, List.of(file(".dot.txt", true))).withHidden(false);
        assertThat(indexOf(component, hidden, "0 entries of 1"))
                .as("hidden entries are counted")
                .isEqualTo(4);
    }

    @Test
    void theSelectionIsPaintedWithTheMarkerAndTheTheme() {
        FilePicker component = picker(FilePicker.Config.defaults()
                .withRows(5)
                .withTheme(FilePicker.Theme.of(Style.NONE.withBold(), Style.NONE.withItalic())));
        FilePicker.Model state = model(5, List.of(directory("docs"), file("notes.txt", true)));
        List<String> rows = lines(component, state, 40).stream()
                .map(StyledText::plainText)
                .map(String::stripTrailing)
                .toList();
        assertThat(rows.get(0)).isEqualTo("/fixture");
        assertThat(rows.get(1)).startsWith(FilePicker.MARKER + "docs/").endsWith("rwxr-xr-x  dir");
        assertThat(rows.get(2)).startsWith(FilePicker.MARKER_GAP + "notes.txt").endsWith("rw-r--r--  10B");
        assertThat(rows.get(3)).isEqualTo("2 entries");
    }

    @Test
    void aDisabledEntryIsPaintedLikeEveryOtherEntry() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(5));
        FilePicker.Model state = model(file("archive.bin", false));
        assertThat(lines(component, state, 40).get(1).plainText())
                .startsWith(FilePicker.MARKER + "archive.bin")
                .endsWith("rw-r--r--  10B");
    }

    @Test
    void aNarrowTerminalDropsTheMetadataBeforeItCutsTheName() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(4));
        FilePicker.Model state = model(file("notes.txt", true));
        assertThat(lines(component, state, 40).get(1).plainText())
                .isEqualTo(FilePicker.MARKER + "notes.txt" + " ".repeat(15) + "rw-r--r--  10B");
        assertThat(lines(component, state, 12).get(1).plainText()).isEqualTo(FilePicker.MARKER + "notes.txt");
        assertThat(lines(component, state, 1).get(1).plainText()).isEqualTo(FilePicker.MARKER.substring(0, 1));
    }

    @Test
    void anUnfocusedPickerStillMarksItsSelectionAndRoutesNothing() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(5));
        FilePicker.Model state = model(file("notes.txt", true)).withFocus(false);
        assertThat(row(component, state, 1)).startsWith(FilePicker.MARKER);
        assertThat(route(component, state, "down")).isEmpty();
        assertThat(FilePicker.keys(state).action("down").orElseThrow().enabled())
                .as("help still describes the gestures")
                .isTrue();
    }

    @Test
    void thePickerRoutesItsOwnKeysAndNothingElse() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(8));
        FilePicker.Model state = model(directory("docs"), file("notes.txt", true));
        assertThat(route(component, state, "down")).contains(new FilePicker.Msg.Down());
        assertThat(route(component, state, "enter")).contains(new FilePicker.Msg.Confirm());
        assertThat(route(component, state, "escape")).contains(new FilePicker.Msg.Cancel());
        assertThat(route(component, state, ".")).contains(new FilePicker.Msg.ToggleHidden());
        assertThat(route(component, state, "right")).contains(new FilePicker.Msg.Open());
        assertThat(route(component, state, "left")).contains(new FilePicker.Msg.Parent());
        assertThat(route(component, state, "q"))
                .as("a gesture the picker does not own bubbles to the application")
                .isEmpty();
        assertThat(route(component, model(file("notes.txt", true)), "right"))
                .as("a file is not something to open")
                .isEmpty();
    }

    @Test
    void theViewIsAPureFunctionOfTheModel() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(8));
        FilePicker.Model state = model(directory("docs"), file("notes.txt", true));
        List<String> first = painted(component, state);
        assertThat(painted(component, state)).isEqualTo(first);
        assertThat(state).isEqualTo(model(directory("docs"), file("notes.txt", true)));
        assertThat(state.selected()).isEqualTo("docs");
    }

    @Test
    void aMessageThatChangesNothingKeepsTheIncomingInstance() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(8));
        FilePicker.Model state = model(directory("docs"), file("notes.txt", true));
        assertThat(apply(component, state, new FilePicker.Msg.Up())).isSameAs(state);
        assertThat(apply(component, state, new FilePicker.Msg.First())).isSameAs(state);
        assertThat(apply(component, state, new FilePicker.Msg.RowsChanged(state.rows())))
                .isSameAs(state);
        assertThat(apply(component, state, new FilePicker.Msg.Failed(state.epoch() - 1, Path.of("/x"), "stale")))
                .isSameAs(state);
        assertThat(apply(component, state, new FilePicker.Msg.ToggleHidden())).isNotSameAs(state);
        assertThat(apply(component, state, new FilePicker.Msg.ToggleHelp()).expanded())
                .isTrue();
    }

    @Test
    void thePickerReportsCancellationWithoutChangingTheDirectory() {
        FilePicker component = picker(FilePicker.Config.defaults().withRows(8));
        FilePicker.Model state = model(file("notes.txt", true));
        Update<FilePicker.Model, FilePicker.Msg, FilePicker.Output> update =
                component.update(state, new FilePicker.Msg.Cancel());
        assertThat(update.outputs()).containsExactly(new FilePicker.Output.Cancelled());
        assertThat(update.model()).isSameAs(state);
        assertThat(update.command()).isEqualTo(Command.none());
    }

    // ── R-04: the demo scene ────────────────────────────────────────────────

    private static FilePickerDemo.Scene demo(Path root) {
        return new FilePickerDemo.Scene(root);
    }

    /** The demo initialized against an owned fixture, with its picker's first listing applied. */
    private static FilePickerDemo.Model started(FilePickerDemo.Scene scene) {
        Update<FilePickerDemo.Model, FilePickerDemo.Msg, Never> init = scene.init(START);
        return pump(scene, init.model(), run(init.command()));
    }

    private static FilePickerDemo.Model apply(
            FilePickerDemo.Scene scene, FilePickerDemo.Model state, FilePickerDemo.Msg message) {
        return pump(scene, state, List.of(message));
    }

    /**
     * Applies a queue of messages, running the effects each one asks for.
     *
     * <p>It is the smallest runtime that matters here: a message is reduced, its command's tasks are
     * run and its dispatched messages are delivered, which is exactly how a mount routes the
     * picker's own output - a choice, a rejection, a cancellation - back to this scene.</p>
     */
    private static FilePickerDemo.Model pump(
            FilePickerDemo.Scene scene, FilePickerDemo.Model state, List<FilePickerDemo.Msg> queue) {
        FilePickerDemo.Model model = state;
        List<FilePickerDemo.Msg> pending = new ArrayList<>(queue);
        for (int step = 0; !pending.isEmpty() && step < 64; step++) {
            Update<FilePickerDemo.Model, FilePickerDemo.Msg, Never> update = scene.update(model, pending.remove(0));
            model = update.model();
            pending.addAll(run(update.command()));
        }
        return model;
    }

    private static FilePickerDemo.Model send(FilePickerDemo.Scene scene, FilePickerDemo.Model state, String spec) {
        Optional<FilePickerDemo.Msg> message = InputRouter.route(scene.view(state, CONTEXT), key(spec));
        return apply(scene, state, message.orElseThrow(() -> new AssertionError("no message for " + spec)));
    }

    private static List<String> demoRows(FilePickerDemo.Scene scene, FilePickerDemo.Model state) {
        return new ScreenRenderer().plainLines(scene.view(state, CONTEXT), CONTEXT.viewport());
    }

    private static String demoRow(FilePickerDemo.Scene scene, FilePickerDemo.Model state, int index) {
        return demoRows(scene, state).get(index).stripTrailing();
    }

    @Test
    void theDemoStartsWithTheWholeFixtureAndItsRoot() throws IOException {
        Path root = FilePickerDemo.fixture();
        try {
            FilePickerDemo.Scene scene = demo(root);
            Update<FilePickerDemo.Model, FilePickerDemo.Msg, Never> init = scene.init(START);
            assertThat(init.command())
                    .isInstanceOfSatisfying(
                            Command.Scoped.class,
                            scoped -> assertThat(scoped.path())
                                    .isEqualTo(ComponentPath.ROOT.append(ComponentId.of("picker"))));
            FilePickerDemo.Model model = started(scene);
            assertThat(demoRow(scene, model, 0))
                    .isEqualTo("dir=/ entries=9 shown=7 selected=docs hidden=off state=idle");
            assertThat(demoRow(scene, model, 1)).isEqualTo("last=started chosen=0 rejected=0 deleted=0 added=0");
            assertThat(names(FilePicker.shown(model.picker())))
                    .containsExactly("docs", "loop", "music", "archive.bin", "draft.txt", "photo.png", "readme.md");
            assertThat(model.picker().root()).isEqualTo(root.toRealPath());
            String path = root.toRealPath().toString();
            Viewport viewport = new Viewport(SCREEN_ROWS, Math.max(SCREEN_COLS, TextMeasure.columns(path)));
            assertThat(new ScreenRenderer()
                            .plainLines(scene.view(model, new ViewContext(viewport)), viewport)
                            .get(2)
                            .stripTrailing())
                    .isEqualTo(path);
            assertThat(demoRow(scene, model, 3)).startsWith(FilePicker.MARKER + "docs/");
            assertThat(demoRow(scene, model, 23))
                    .as("the scene's own help is the last row")
                    .contains("add a file");
        } finally {
            FilePickerDemo.remove(root);
        }
    }

    @Test
    void theDemoEntersADirectoryChoosesAFileAndComesBack() throws IOException {
        Path root = FilePickerDemo.fixture();
        try {
            FilePickerDemo.Scene scene = demo(root);
            FilePickerDemo.Model model = send(scene, started(scene), "right");
            assertThat(model.picker().directory()).isEqualTo(root.toRealPath().resolve("docs"));
            assertThat(model.last()).isEqualTo("open");
            assertThat(names(FilePicker.shown(model.picker()))).containsExactly("guide.md", "notes.txt");
            FilePickerDemo.Model chosen = send(scene, model, "enter");
            assertThat(chosen.last()).isEqualTo("chose-/docs/guide.md");
            assertThat(chosen.chosen()).isEqualTo(1);
            FilePickerDemo.Model back = send(scene, chosen, "left");
            assertThat(back.picker().directory()).isEqualTo(root.toRealPath());
            assertThat(back.last()).isEqualTo("parent");
            assertThat(FilePicker.selected(back.picker()).map(FilePicker.Entry::name))
                    .contains("docs");
        } finally {
            FilePickerDemo.remove(root);
        }
    }

    @Test
    void theDemoRefusesADisabledExtensionAndALinkOutsideItsPolicy() throws IOException {
        Path root = FilePickerDemo.fixture();
        try {
            FilePickerDemo.Scene scene = demo(root);
            FilePickerDemo.Model model = send(scene, started(scene), "end");
            assertThat(FilePicker.selected(model.picker()).map(FilePicker.Entry::name))
                    .contains("readme.md");
            FilePickerDemo.Model onBin = send(scene, send(scene, model, "home"), "down");
            FilePickerDemo.Model selected = onBin;
            while (!FilePicker.selected(selected.picker())
                    .map(entry -> entry.name().equals("archive.bin"))
                    .orElse(false)) {
                selected = send(scene, selected, "down");
            }
            FilePickerDemo.Model rejected = send(scene, selected, "enter");
            assertThat(rejected.last()).isEqualTo("rejected-/archive.bin-extension");
            assertThat(rejected.rejected()).isEqualTo(1);
            assertThat(rejected.chosen()).isZero();
            FilePickerDemo.Model loop = send(scene, send(scene, onBin, "home"), "down");
            while (!FilePicker.selected(loop.picker())
                    .map(entry -> entry.name().equals("loop"))
                    .orElse(false)) {
                loop = send(scene, loop, "down");
            }
            assertThat(send(scene, loop, "enter").last()).isEqualTo("rejected-/loop-link");
            assertThat(InputRouter.route(scene.view(loop, CONTEXT), key("right")))
                    .as("a looping link offers no way into it")
                    .isEmpty();
            assertThat(loop.picker().directory()).isEqualTo(root.toRealPath());
        } finally {
            FilePickerDemo.remove(root);
        }
    }

    @Test
    void theDemoDeletesAFileAndThenRefusesTheStaleEntry() throws IOException {
        Path root = FilePickerDemo.fixture();
        try {
            FilePickerDemo.Scene scene = demo(root);
            FilePickerDemo.Model model = started(scene);
            FilePickerDemo.Model onDraft = model;
            while (!FilePicker.selected(onDraft.picker())
                    .map(entry -> entry.name().equals("draft.txt"))
                    .orElse(false)) {
                onDraft = send(scene, onDraft, "down");
            }
            FilePickerDemo.Model deleted = send(scene, onDraft, "x");
            assertThat(deleted.last()).isEqualTo("deleted-/draft.txt");
            assertThat(deleted.deleted()).isEqualTo(1);
            assertThat(Files.exists(root.resolve("draft.txt"))).isFalse();
            assertThat(names(FilePicker.shown(deleted.picker())))
                    .as("the listing is stale until the reader reloads")
                    .contains("draft.txt");
            FilePickerDemo.Model rejected = send(scene, deleted, "enter");
            assertThat(rejected.last()).isEqualTo("rejected-/draft.txt-missing");
            assertThat(rejected.rejected()).isEqualTo(1);
            FilePickerDemo.Model reloaded = send(scene, rejected, "r");
            assertThat(names(FilePicker.shown(reloaded.picker()))).doesNotContain("draft.txt");
        } finally {
            FilePickerDemo.remove(root);
        }
    }

    @Test
    void theDemoCreatesAFileAndShowsItAfterAReload() throws IOException {
        Path root = FilePickerDemo.fixture();
        try {
            FilePickerDemo.Scene scene = demo(root);
            FilePickerDemo.Model added = send(scene, started(scene), "a");
            assertThat(added.last()).isEqualTo("added-added-1.txt");
            assertThat(added.added()).isEqualTo(1);
            assertThat(Files.exists(root.resolve("added-1.txt"))).isTrue();
            assertThat(names(FilePicker.shown(send(scene, added, "r").picker())))
                    .contains("added-1.txt");
        } finally {
            FilePickerDemo.remove(root);
        }
    }

    @Test
    void theDemoTogglesHiddenFilesAndCancelsTheSession() throws IOException {
        Path root = FilePickerDemo.fixture();
        try {
            FilePickerDemo.Scene scene = demo(root);
            FilePickerDemo.Model model = started(scene);
            FilePickerDemo.Model shown = send(scene, model, ".");
            assertThat(shown.picker().hidden()).isTrue();
            assertThat(names(FilePicker.shown(shown.picker()))).contains(".config.txt", ".hidden.txt");
            assertThat(demoRow(scene, shown, 0)).contains("entries=9 shown=9").contains("hidden=on");
            FilePickerDemo.Model cancelled = send(scene, shown, "escape");
            assertThat(cancelled.last()).isEqualTo("cancelled");
        } finally {
            FilePickerDemo.remove(root);
        }
    }

    @Test
    void theDemoFixtureIsItsOwnAndIsRemovedAgain() throws IOException {
        Path root = FilePickerDemo.fixture();
        assertThat(Files.isDirectory(root)).isTrue();
        assertThat(names(FilePicker.shown(loaded(picker(FilePickerDemo.pickerConfig(root))))))
                .hasSizeGreaterThan(4);
        FilePickerDemo.remove(root);
        assertThat(Files.exists(root)).isFalse();
    }
}

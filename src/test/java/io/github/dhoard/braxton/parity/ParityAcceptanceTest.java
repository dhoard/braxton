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

import examples.parity.CountdownDemo;
import examples.parity.KeymapHelpDemo;
import examples.parity.MouseDemo;
import examples.parity.SceneCatalog;
import examples.parity.Showcase;
import examples.parity.WorkspaceDemo;
import examples.parity.WorkspaceScene;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.widget.Countdown;
import io.github.dhoard.braxton.widget.DataTable;
import io.github.dhoard.braxton.widget.FilePicker;
import io.github.dhoard.braxton.widget.FilePickerModel;
import io.github.dhoard.braxton.widget.TextInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 24 acceptance: the integrated showcase, the composed workspace, and the parity record.
 *
 * <p>The catalog is asserted on its own public surface - what it lists, what it mounts, what it
 * paints and which scene a routed gesture reaches - so a defect in the composition is caught here
 * rather than only in a terminal run. The composed workspace is driven end to end, because the point
 * of that scene is that separate widgets affect each other.</p>
 */
class ParityAcceptanceTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    @TempDir
    Path fixture;

    private Path root;

    @BeforeEach
    void createFixture() throws IOException {
        root = Showcase.fixture();
    }

    @AfterEach
    void removeFixture() {
        Showcase.remove(root);
    }

    private ComponentDriver<Showcase.Model, Showcase.Msg, io.github.dhoard.braxton.mvu.Never> catalog() {
        return ComponentDriver.initialize(new Showcase.Scene(root), new InitContext(new Viewport(24, 80), null));
    }

    /**
     * One event for the workspace scene, routed the way the runtime routes it.
     */
    private static java.util.Optional<WorkspaceDemo.Msg> workspaceRoute(
            io.github.dhoard.braxton.testing.ComponentDriver<
                            WorkspaceDemo.Model, WorkspaceDemo.Msg, io.github.dhoard.braxton.mvu.Never>
                    driver,
            InputEvent event) {
        return io.github.dhoard.braxton.testing.Routing.route(driver.component(), driver.model(), CONTEXT, event)
                .stream()
                .findFirst();
    }

    private static java.util.Optional<Showcase.Msg> route(
            io.github.dhoard.braxton.testing.ComponentDriver<
                            Showcase.Model, Showcase.Msg, io.github.dhoard.braxton.mvu.Never>
                    driver,
            InputEvent event) {
        return io.github.dhoard.braxton.testing.Routing.route(driver.component(), driver.model(), CONTEXT, event)
                .stream()
                .findFirst();
    }

    private static List<String> lines(ComponentDriver<Showcase.Model, Showcase.Msg, ?> driver) {
        return ElementScreen.render(
                        driver.view(CONTEXT),
                        CONTEXT.viewport().rows(),
                        CONTEXT.viewport().cols())
                .lines();
    }

    private static InputEvent gesture(String spec) {
        return InputEvent.KeyInput.of(Key.parse(spec));
    }

    private static int indexOf(Showcase.Scene scene, String id) {
        List<SceneCatalog.Entry> entries = scene.entries();
        for (int at = 0; at < entries.size(); at++) {
            if (entries.get(at).id().equals(id)) {
                return at;
            }
        }
        throw new IllegalArgumentException("no entry " + id);
    }

    // ── R-01: the catalog ───────────────────────────────────────────────────

    @Test
    void theCatalogListsEveryImplementedPhaseAndTheWorkspace() {
        Showcase.Scene scene = new Showcase.Scene(root);
        List<String> ids = scene.entries().stream().map(SceneCatalog.Entry::id).toList();
        assertThat(ids).hasSize(27);
        assertThat(ids.get(0)).isEqualTo("01");
        assertThat(ids).contains("10", "17", "23", "24");
        assertThat(ids).isSorted();
        assertThat(scene.entries())
                .allSatisfy(entry -> assertThat(entry.title()).isNotBlank());

        List<String> screen = lines(catalog());
        assertThat(screen.get(0)).isEqualTo("catalog=27 open=none selected=01 top=0 help=compact last=none");
        assertThat(screen.get(1)).isEqualTo(Showcase.HEADING);
        assertThat(screen).anySatisfy(line -> assertThat(line).startsWith("\u276F 01 Keymaps and help"));
        // The window shows what fits; the last entry is what scrolling reaches.
        assertThat(String.join("\n", screen)).doesNotContain("24 Composed task workspace");
        var driver = catalog();
        driver.send(new Showcase.Msg.Last());
        assertThat(String.join("\n", lines(driver))).contains("24 Composed task workspace");
    }

    @Test
    void everyEntryMountsPaintsAndReturnsToTheCatalog() {
        for (int index = 0; index < new Showcase.Scene(root).entries().size(); index++) {
            var driver = catalog();
            String id = new Showcase.Scene(root).entries().get(index).id();
            assertThat(driver.send(new Showcase.Msg.Open(index)).model().open()).isEqualTo(id);
            assertThat(driver.model().inScene()).isTrue();

            // The mounted scene paints inside the showcase's own chrome, and the chrome names it.
            List<String> screen = lines(driver);
            assertThat(screen.get(0)).startsWith("open=" + id + " scene=");
            assertThat(screen).anySatisfy(line -> assertThat(line).contains("ctrl+c back to catalog"));
            assertThat(screen.subList(1, screen.size() - 1))
                    .as("the mounted phase %s paints something", id)
                    .anySatisfy(line -> assertThat(line.trim()).isNotEmpty());

            // Leaving is the unmount, and the catalog is exactly where it was.
            Update<Showcase.Model, Showcase.Msg, io.github.dhoard.braxton.mvu.Never> back =
                    driver.send(new Showcase.Msg.Back());
            assertThat(back.model().inScene()).isFalse();
            assertThat(back.model().sceneModel()).isNull();
            assertThat(lines(driver).get(0)).contains("open=none").contains("last=back");
        }
    }

    @Test
    void openingASceneReplacesTheOneBeforeItAndStaleMessagesAreDropped() {
        var driver = catalog();
        Showcase.Scene scene = new Showcase.Scene(root);
        int first = indexOf(scene, "01");
        int second = indexOf(scene, "02");

        driver.send(new Showcase.Msg.Open(first));
        assertThat(driver.model().open()).isEqualTo("01");
        driver.send(new Showcase.Msg.SceneMessage("01", new KeymapHelpDemo.Msg.ToggleHelp()));
        assertThat(((KeymapHelpDemo.Model) driver.model().sceneModel()).expanded())
                .as("the mounted scene reduced its own message")
                .isTrue();

        driver.send(new Showcase.Msg.Open(second));
        assertThat(driver.model().open()).isEqualTo("02");
        // A message lifted from the scene that is no longer mounted names nothing.
        driver.send(new Showcase.Msg.SceneMessage("01", new KeymapHelpDemo.Msg.ToggleHelp()));
        assertThat(driver.model().last()).isEqualTo("stale:01");
        assertThat(driver.model().open()).isEqualTo("02");
    }

    @Test
    void onlyTheMountedSceneReceivesTheKeysItOwns() {
        var driver = catalog();
        Showcase.Scene scene = new Showcase.Scene(root);
        driver.send(new Showcase.Msg.Open(indexOf(scene, "01")));

        // Phase 01's selectors route down while they hold focus, and the catalog does not.
        Element<Showcase.Msg> view = driver.view(CONTEXT);
        assertThat(InteractionRouter.message(view, gesture("down")))
                .get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(Showcase.Msg.SceneMessage.class))
                .extracting(Showcase.Msg.SceneMessage::id)
                .isEqualTo("01");

        // In the catalog the same gesture moves the highlight instead.
        driver.send(new Showcase.Msg.Back());
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, gesture("down"))
                        .stream()
                        .findFirst())
                .contains(new Showcase.Msg.Move(1));
        assertThat(driver.send(new Showcase.Msg.Move(1)).model().selected()).isEqualTo(1);
    }

    @Test
    void mountingPreservesEveryScenesOwnKeyRoutingEvenWithoutAFocusedWidget() {
        var driver = catalog();
        List<SceneCatalog.Slot> slots = SceneCatalog.slots(root);
        ViewContext body = new ViewContext(new Viewport(22, 80));
        List<String> gestures = List.of(
                "up",
                "down",
                "left",
                "right",
                "home",
                "end",
                "pageup",
                "pagedown",
                "enter",
                "escape",
                "space",
                "tab",
                "shift+tab",
                "?",
                "q",
                "s",
                "r",
                "c",
                "x",
                "f",
                "g",
                "m",
                "p",
                "t",
                "n",
                "a",
                "i",
                "v",
                "1",
                "2",
                "alt+1",
                "alt+2",
                "f2",
                "ctrl+s");
        List<InputEvent> events = new ArrayList<>();
        for (String spec : gestures) {
            for (KeyEventType type : KeyEventType.values()) {
                events.add(InputEvent.KeyInput.of(Key.parse(spec), "", type));
            }
        }
        events.add(InputEvent.KeyInput.of(Key.of("q"), "q"));
        events.add(new InputEvent.PasteInput("q?"));
        events.add(new InputEvent.FocusInput(true));
        events.add(new InputEvent.FocusInput(false));
        for (int index = 0; index < slots.size(); index++) {
            driver.send(new Showcase.Msg.Open(index));
            Element<Showcase.Msg> standalone = slots.get(index).view(driver.model(), body);
            Element<Showcase.Msg> mounted = driver.view(CONTEXT);
            for (InputEvent event : events) {
                assertThat(InteractionRouter.message(mounted, event))
                        .as("scene %s, event %s", slots.get(index).entry().id(), event)
                        .isEqualTo(InteractionRouter.message(standalone, event));
            }
        }
    }

    @Test
    void catalogReturnKeysReachEverySceneAndModalSelection() {
        var driver = catalog();
        for (int index = 0; index < new Showcase.Scene(root).entries().size(); index++) {
            driver.send(new Showcase.Msg.Open(index));
            assertCatalogReturnKeys(driver);
        }

        driver.send(new Showcase.Msg.Open(16));
        driver.send(new Showcase.Msg.SceneMessage("17", new MouseDemo.Msg.ToggleModal()));
        assertCatalogReturnKeys(driver);

        driver.send(new Showcase.Msg.Open(23));
        driver.send(new Showcase.Msg.SceneMessage("24", new WorkspaceDemo.Msg.Sync()));
        assertCatalogReturnKeys(driver);
    }

    private static void assertCatalogReturnKeys(ComponentDriver<Showcase.Model, Showcase.Msg, ?> driver) {
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, gesture("ctrl+c"))
                        .stream()
                        .findFirst())
                .as("scene %s returns on Ctrl+C", driver.model().open())
                .contains(new Showcase.Msg.Back());
    }

    @Test
    void closingASceneCancelsTheSubscriptionsItStarted() {
        var driver = catalog();
        Showcase.Scene scene = new Showcase.Scene(root);
        driver.send(new Showcase.Msg.Open(indexOf(scene, "09")));

        // A running countdown declares a time source; the mount is what lifts and scopes it.
        driver.send(new Showcase.Msg.SceneMessage("09", new CountdownDemo.Msg.Start()));
        assertThat(driver.model().sceneModel()).isInstanceOf(CountdownDemo.Model.class);
        // The catalog's own size source, plus the running countdown's time source.
        assertThat(Subscription.leaves(driver.subscriptions())).hasSize(2);

        driver.send(new Showcase.Msg.Back());
        // Only the catalog's own source is left: the scene's went with its mount.
        assertThat(Subscription.leaves(driver.subscriptions())).hasSize(1);
    }

    @Test
    void leavingASceneIsOneGestureAndQuittingIsTheCatalogs() {
        var driver = catalog();
        Showcase.Scene scene = new Showcase.Scene(root);
        driver.send(new Showcase.Msg.Open(indexOf(scene, "01")));

        // A scene keeps every letter it might type; the catalog owns Ctrl+C for returning.
        assertThat(route(driver, gesture("f2"))).isEmpty();
        assertThat(route(driver, gesture("ctrl+c"))).contains(new Showcase.Msg.Back());
        assertThat(route(driver, gesture("q")))
                .get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(Showcase.Msg.SceneMessage.class))
                .extracting(Showcase.Msg.SceneMessage::id)
                .isEqualTo("01");

        Update<Showcase.Model, Showcase.Msg, io.github.dhoard.braxton.mvu.Never> left =
                driver.send(new Showcase.Msg.Back());
        assertThat(left.model().inScene()).isFalse();
        assertThat(left.command()).isEqualTo(Command.none());

        // Quitting is the catalog's own gesture, and it is a quit command like any other.
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, gesture("q"))
                        .stream()
                        .findFirst())
                .contains(new Showcase.Msg.Quit());
        assertThat(driver.send(new Showcase.Msg.Quit()).command()).isEqualTo(Command.quit());
    }

    @Test
    void theCatalogMovesOpensAndScrollsWithTheKeyboardAndThePointer() {
        var driver = catalog();
        driver.send(new Showcase.Msg.Last());
        assertThat(driver.model().selected()).isEqualTo(26);
        assertThat(driver.model().top()).isGreaterThan(0);

        driver.send(new Showcase.Msg.First());
        assertThat(driver.model().selected()).isZero();
        assertThat(driver.model().top()).isZero();

        // A press selects; a repeated press on the same row is the pointer's open.
        Showcase.Model pressed = driver.send(new Showcase.Msg.Press(3, 1)).model();
        assertThat(pressed.selected()).isEqualTo(3);
        assertThat(pressed.open()).isEmpty();
        assertThat(driver.send(new Showcase.Msg.Press(3, 2)).model().open()).isEqualTo("04");

        // A wheel moves the highlight by one entry, and it is clamped at the ends.
        driver.send(new Showcase.Msg.Back());
        assertThat(driver.send(new Showcase.Msg.Wheel(1)).model().selected()).isEqualTo(4);
        driver.send(new Showcase.Msg.First());
        assertThat(driver.send(new Showcase.Msg.Wheel(-1)).model().selected()).isZero();
    }

    @Test
    void theCatalogReflowsItsWindowWhenTheTerminalShrinks() {
        var driver = catalog();
        driver.send(new Showcase.Msg.Last());
        assertThat(driver.model().selected()).isEqualTo(26);

        // A shorter terminal shows fewer entries, so the window follows the highlight down.
        Showcase.Model shrunk = driver.send(new Showcase.Msg.Resized(10, 80)).model();
        assertThat(shrunk.rows()).isEqualTo(10);
        assertThat(shrunk.top()).isEqualTo(26 - shrunk.visible() + 1);
        assertThat(lines(driver).get(0)).contains("top=" + shrunk.top());
        assertThat(String.join("\n", lines(driver))).contains("31 Debug overlay");

        // Growing back keeps the highlight where the reader left it.
        Showcase.Model grown = driver.send(new Showcase.Msg.Resized(24, 80)).model();
        assertThat(grown.selected()).isEqualTo(26);
        assertThat(grown.visible()).isEqualTo(24 - Showcase.CHROME_ROWS);
    }

    @Test
    void theHelpSwitchesBetweenTheListAndTheGroupedActions() {
        var driver = catalog();
        assertThat(String.join("\n", lines(driver))).contains("enter open the scene");

        driver.send(new Showcase.Msg.ToggleHelp());
        List<String> help = lines(driver);
        assertThat(help.get(0)).contains("help=full");
        assertThat(String.join("\n", help)).contains("up").contains("open the scene");

        driver.send(new Showcase.Msg.ToggleHelp());
        assertThat(String.join("\n", lines(driver))).contains("01 Keymaps and help");
    }

    /**
     * One workspace message and everything the runtime would deliver after it.
     *
     * <p>A mounted child's output is lifted into a dispatch command rather than delivered inside
     * the parent's update, so a headless run has to drain those commands the way the runtime does.
     * That is what makes a chosen file, a chosen task row and a field's submit reach the scene's own
     * update in this test at all.</p>
     */
    private static void deliver(
            ComponentDriver<WorkspaceDemo.Model, WorkspaceDemo.Msg, io.github.dhoard.braxton.mvu.Never> driver,
            WorkspaceDemo.Msg message) {
        ArrayDeque<WorkspaceDemo.Msg> pending = new ArrayDeque<>();
        pending.add(message);
        while (!pending.isEmpty()) {
            drain(driver.send(pending.removeFirst()).command(), pending);
        }
    }

    private static void drain(Command<WorkspaceDemo.Msg> command, ArrayDeque<WorkspaceDemo.Msg> pending) {
        switch (command) {
            case Command.None<WorkspaceDemo.Msg> none -> {}
            case Command.Batch<WorkspaceDemo.Msg> batch -> batch.commands().forEach(item -> drain(item, pending));
            case Command.Scoped<WorkspaceDemo.Msg> scoped -> drain(scoped.command(), pending);
            case Command.Dispatch<WorkspaceDemo.Msg> dispatch -> pending.add(dispatch.message());
            default -> {}
        }
    }

    // ── R-02: the composed workspace ───────────────────────────────────────

    private ComponentDriver<WorkspaceDemo.Model, WorkspaceDemo.Msg, io.github.dhoard.braxton.mvu.Never> workspace() {
        return ComponentDriver.initialize(new WorkspaceScene(root), new InitContext(new Viewport(24, 80), null));
    }

    private static List<String> workspaceLines(ComponentDriver<WorkspaceDemo.Model, WorkspaceDemo.Msg, ?> driver) {
        return ElementScreen.render(
                        driver.view(CONTEXT),
                        CONTEXT.viewport().rows(),
                        CONTEXT.viewport().cols())
                .lines();
    }

    private static String workspaceStatus(ComponentDriver<WorkspaceDemo.Model, WorkspaceDemo.Msg, ?> driver) {
        return workspaceLines(driver).get(0);
    }

    private static String workspaceCounts(ComponentDriver<WorkspaceDemo.Model, WorkspaceDemo.Msg, ?> driver) {
        return workspaceLines(driver).get(2);
    }

    private static String workspaceLog(ComponentDriver<WorkspaceDemo.Model, WorkspaceDemo.Msg, ?> driver) {
        return workspaceLines(driver).get(3);
    }

    /**
     * A token that is asserted on whichever of the two rows carries it.
     */
    private static String workspaceRows(ComponentDriver<WorkspaceDemo.Model, WorkspaceDemo.Msg, ?> driver) {
        return workspaceCounts(driver) + " " + workspaceLog(driver);
    }

    @Test
    void theWorkspaceOpensOnThePreviewPageWithNothingChosen() {
        var driver = workspace();
        assertThat(workspaceStatus(driver)).isEqualTo("page=preview focus=files source=- lines=0 limits=none");
        assertThat(workspaceLines(driver).get(1)).startsWith("query=- matches=0 active=0");
        assertThat(workspaceCounts(driver))
                .contains("tasks=3")
                .contains("done=1")
                .contains("target=0.33")
                .contains("run=off")
                .contains("dialog=closed");
        assertThat(workspaceLog(driver)).contains("last=started");
    }

    @Test
    void choosingAFileProjectsItAndSearchingPublishesTheMatches() {
        var driver = workspace();
        choose(driver, "notes.md");

        assertThat(driver.model().source()).isEqualTo("notes.md");
        assertThat(driver.model().pane()).isEqualTo(WorkspaceDemo.Pane.PREVIEW);
        assertThat(workspaceStatus(driver)).contains("source=notes.md").contains("focus=preview");
        assertThat(driver.model().document().lineCount()).isGreaterThan(1);
        // The preview is painted from the projection, not from the file.
        assertThat(String.join("\n", workspaceLines(driver))).contains("Release notes");

        deliver(driver, new WorkspaceDemo.Msg.Find(new TextInput.Msg.Insert("lantern")));
        assertThat(workspaceLines(driver).get(1))
                .contains("query=lantern")
                .contains("matches=2")
                .contains("active=1");
        assertThat(driver.model().preview().highlights()).hasSize(2);

        // A query that matches nothing publishes no ranges and selects none.
        deliver(driver, new WorkspaceDemo.Msg.Find(new TextInput.Msg.Clear()));
        deliver(driver, new WorkspaceDemo.Msg.Find(new TextInput.Msg.Insert("nothing here")));
        assertThat(workspaceLines(driver).get(1)).contains("matches=0").contains("active=0");
    }

    @Test
    void aFileThePickerRefusesIsReportedAndDoesNotReplaceThePreview() {
        var driver = workspace();
        choose(driver, "notes.md");
        String before = workspaceStatus(driver);

        // The extension policy is the picker's: a confirmed binary is reported, not opened.
        deliver(driver, new WorkspaceDemo.Msg.Files(new FilePicker.Msg.Select("archive.bin")));
        deliver(driver, new WorkspaceDemo.Msg.Files(new FilePicker.Msg.Confirm()));

        assertThat(driver.model().source()).isEqualTo("notes.md");
        assertThat(workspaceStatus(driver)).isEqualTo(before);
        assertThat(workspaceLog(driver)).contains("refused=1").contains("last=refused-archive.bin-extension");
    }

    @Test
    void theTableDrivesTheProgressBarAndItsOwnRowUpdates() {
        var driver = workspace();
        deliver(driver, new WorkspaceDemo.Msg.ShowTab(WorkspaceDemo.TASKS_PAGE));
        assertThat(driver.model().page()).isEqualTo(WorkspaceDemo.TASKS_PAGE);
        assertThat(driver.model().pane()).isEqualTo(WorkspaceDemo.Pane.TABLE);

        // The table's own choose output is what the scene reacts to.
        deliver(
                driver,
                new WorkspaceDemo.Msg.TableOutput(new DataTable.Output.Chosen("t2", 1, "", -1, StyledText.empty())));
        assertThat(workspaceCounts(driver)).contains("done=2").contains("target=0.67");
        assertThat(workspaceLog(driver)).contains("last=task-t2-done");

        deliver(driver, new WorkspaceDemo.Msg.AddTask());
        assertThat(driver.model().tasks().content().rowCount()).isEqualTo(4);
        assertThat(workspaceCounts(driver)).contains("tasks=4");
        assertThat(workspaceLog(driver)).contains("last=task-added-t4");

        deliver(driver, new WorkspaceDemo.Msg.RemoveTask());
        assertThat(driver.model().tasks().content().rowCount()).isEqualTo(3);
    }

    @Test
    void aDraftIsValidatedAndSurvivesATabSwitch() {
        var driver = workspace();
        deliver(driver, new WorkspaceDemo.Msg.ShowTab(WorkspaceDemo.TASKS_PAGE));
        deliver(driver, new WorkspaceDemo.Msg.FocusNext(1));
        assertThat(driver.model().pane()).isEqualTo(WorkspaceDemo.Pane.NOTE);

        deliver(driver, new WorkspaceDemo.Msg.Note(new TextInput.Msg.Insert("First draft")));
        assertThat(driver.model().note().text()).isEqualTo("First draft");
        assertThat(workspaceLines(driver).get(0)).contains("page=tasks").contains("focus=note");

        // Switching pages keeps the draft: the page is kept alive, not re-created.
        deliver(driver, new WorkspaceDemo.Msg.ShowTab(WorkspaceDemo.PREVIEW_PAGE));
        deliver(driver, new WorkspaceDemo.Msg.ShowTab(WorkspaceDemo.TASKS_PAGE));
        assertThat(driver.model().note().text()).isEqualTo("First draft");

        // A valid draft submits and clears; a blank one is refused by the field's own policy.
        deliver(driver, new WorkspaceDemo.Msg.Note(new TextInput.Msg.Submit()));
        assertThat(workspaceLog(driver)).contains("notes=1");
        assertThat(driver.model().note().text()).isEmpty();
        deliver(driver, new WorkspaceDemo.Msg.Note(new TextInput.Msg.Submit()));
        assertThat(workspaceLog(driver)).contains("refused=1").contains("last=note-refused");

        // An over-long draft is refused for the reason the policy names.
        deliver(driver, new WorkspaceDemo.Msg.Note(new TextInput.Msg.Insert("x".repeat(WorkspaceDemo.NOTE_LIMIT + 1))));
        assertThat(driver.model().note().validation()).isInstanceOf(TextInput.Validation.Invalid.class);
        deliver(driver, new WorkspaceDemo.Msg.Note(new TextInput.Msg.Submit()));
        assertThat(workspaceLog(driver)).contains("refused=2").contains("notes=1");
    }

    @Test
    void theReviewTimerExpiresFromTheRuntimesOwnClock() {
        var driver = workspace();
        deliver(driver, new WorkspaceDemo.Msg.ToggleTimer());
        assertThat(driver.model().timer().running()).isTrue();
        assertThat(workspaceCounts(driver)).contains("run=on");

        // The first reading arms the deadline; a later reading of the same run measures the distance.
        long epoch = driver.model().timer().epoch();
        deliver(driver, new WorkspaceDemo.Msg.Timer(new Countdown.Msg.Reading(epoch, 1_000L)));
        long deadline = driver.model().timer().deadlineNanos();
        deliver(driver, new WorkspaceDemo.Msg.Timer(new Countdown.Msg.Reading(epoch, deadline + 1)));

        assertThat(workspaceCounts(driver)).contains("run=off");
        assertThat(workspaceLog(driver)).contains("timeouts=1").contains("last=timer-timeout");
        // A reading that names an older run is ignored rather than restarting the clock.
        deliver(driver, new WorkspaceDemo.Msg.Timer(new Countdown.Msg.Reading(epoch - 1, deadline + 2)));
        assertThat(workspaceLog(driver)).contains("timeouts=1");
    }

    @Test
    void theConfirmationIsModalAndApplyingItMovesTheWorkspace() {
        var driver = workspace();
        choose(driver, "notes.md");
        // The projection the workspace chose owns the page's own gestures.
        assertThat(io.github.dhoard.braxton.testing.Routing.route(
                                driver.component(), driver.model(), CONTEXT, gesture("down"))
                        .stream()
                        .findFirst())
                .isPresent();

        deliver(driver, new WorkspaceDemo.Msg.Sync());
        assertThat(workspaceCounts(driver)).contains("dialog=open");
        assertThat(workspaceLog(driver)).contains("last=sync-ask");

        // While the dialog is open the page under it is unreachable, and its own keys still are.
        assertThat(String.join("\n", workspaceLines(driver))).contains("Apply the workspace changes?");
        assertThat(workspaceRoute(driver, gesture("down"))).isEmpty();
        assertThat(workspaceRoute(driver, gesture("esc"))).contains(new WorkspaceDemo.Msg.CancelSync());
        assertThat(workspaceRoute(driver, gesture("enter"))).contains(new WorkspaceDemo.Msg.ConfirmSync());

        // Declining leaves everything as it was; confirming lands the bar and re-projects.
        deliver(driver, new WorkspaceDemo.Msg.CancelSync());
        assertThat(workspaceCounts(driver)).contains("dialog=closed");
        assertThat(workspaceLog(driver)).contains("last=sync-canceled");
        deliver(driver, new WorkspaceDemo.Msg.Sync());
        deliver(driver, new WorkspaceDemo.Msg.ConfirmSync());
        assertThat(workspaceCounts(driver)).contains("dialog=closed");
        assertThat(workspaceLog(driver)).contains("synced=1").contains("last=sync-confirmed");
        assertThat(workspaceCounts(driver)).contains("target=1.00");
    }

    @Test
    void quittingIsTheScenesLastDecision() {
        var driver = workspace();
        WorkspaceDemo.Model before = driver.model();
        Update<WorkspaceDemo.Model, WorkspaceDemo.Msg, io.github.dhoard.braxton.mvu.Never> quit =
                driver.send(new WorkspaceDemo.Msg.Quit());
        assertThat(quit.command()).isEqualTo(Command.quit());
        assertThat(quit.outputs()).isEmpty();
        assertThat(quit.model()).isEqualTo(before);
    }

    /**
     * Chooses one fixture entry the way the runtime does: list, select, confirm, then the task's reply.
     *
     * <p>The picker reads its directory in a task, so a headless run supplies the listing and the
     * check result the runtime's effects would have delivered. Nothing about the scene changes: the
     * same messages arrive in the same order.</p>
     */
    private static void choose(
            ComponentDriver<WorkspaceDemo.Model, WorkspaceDemo.Msg, io.github.dhoard.braxton.mvu.Never> driver,
            String name) {
        FilePickerModel files = driver.model().files();
        List<FilePicker.Entry> entries = new ArrayList<>();
        for (String entry : List.of("notes.md", "guide.md", "archive.bin")) {
            entries.add(new FilePicker.Entry(
                    files.directory().resolve(entry), FilePicker.Kind.FILE, 0L, "", "", entry.endsWith(".md"), false));
        }
        deliver(
                driver,
                new WorkspaceDemo.Msg.Files(new FilePicker.Msg.Listed(
                        files.epoch(), files.directory(), files.directory(), entries, false)));
        deliver(driver, new WorkspaceDemo.Msg.Files(new FilePicker.Msg.Select(name)));
        deliver(driver, new WorkspaceDemo.Msg.Files(new FilePicker.Msg.Confirm()));
        // The picker checks the entry in a task, so the reply is what reports the choice; a test
        // supplies the answer the runtime's effect would have delivered.
        deliver(
                driver,
                new WorkspaceDemo.Msg.Files(new FilePicker.Msg.Validated(
                        driver.model().files().epoch(),
                        driver.model().files().directory().resolve(name),
                        null)));
    }

    // ── R-03: the parity record ────────────────────────────────────────────

    private static final Path RECORD = Path.of("examples", "parity", "PARITY.md");

    private static final Path VERIFIER = Path.of("tools", "terminal", "verify_terminal.py");

    private static final Path PROBE = Path.of("tools", "terminal", "ParityProbe.java");

    @Test
    void theParityRecordNamesAnImplementedDemoTestAndScenarioForEveryRow() throws Exception {
        List<String> rows = Files.readAllLines(RECORD).stream()
                .filter(line -> line.startsWith("| `"))
                .toList();
        assertThat(rows).as("reference rows in the parity matrix").hasSizeGreaterThanOrEqualTo(60);

        String verifier = Files.readString(VERIFIER);
        String probe = Files.readString(PROBE);
        Set<String> phases = new TreeSet<>();
        for (String row : rows) {
            List<String> cells = cells(row);
            assertThat(cells).as("columns of %s", row).hasSize(6);
            String reference = cells.get(0).replace("`", "");
            String kind = cells.get(1);
            String phase = cells.get(2);
            assertThat(kind).as("kind of %s", reference).isIn("component", "runtime", "application", "unsupported");
            if ("unsupported".equals(kind)) {
                assertThat(phase)
                        .as("an unsupported row names no phase: %s", reference)
                        .isEqualTo("-");
                assertThat(Files.readString(RECORD))
                        .as("the policy behind %s", reference)
                        .contains("`" + reference + "`");
                continue;
            }
            assertThat(phase).as("phase of %s", reference).matches("\\d\\d");
            phases.add(phase);

            String demo = cells.get(3).replace("`", "");
            String test = cells.get(4).replace("`", "");
            String scenario = cells.get(5).replace("`", "");
            assertThat(scenario).as("scenario of %s", reference).isEqualTo(phase);

            // The demo exists and is what the catalog and the packaged launch name.
            assertThat(Class.forName(demo)).as("demo %s of %s", demo, reference).isNotNull();
            assertThat(demo.startsWith("examples.parity.")).isTrue();
            assertThat(Path.of("src", "main", "java", demo.replace('.', '/') + ".java"))
                    .as("source of %s", demo)
                    .exists();

            // The deterministic regression exists.
            Path source = Path.of("src", "test", "java", "io", "github", "dhoard", "braxton", "parity", test + ".java");
            assertThat(source).as("test %s of %s", test, reference).exists();

            // The phase is dispatched by the verifier and by the probe, and its demo is launched.
            assertThat(verifier).as("scenario %s", phase).contains("\"" + phase + "\": check_");
            // The packaged launch is the phase's own demo, except for phase 24: the showcase is
            // the one packaged entry point, and the workspace is what it mounts.
            String launch = "24".equals(phase) ? "examples.parity.Showcase" : demo;
            assertThat(verifier).as("packaged launch for %s", phase).contains("\"" + phase + "\": (\"" + launch + "\"");
            assertThat(probe).as("probe dispatch for %s", phase).contains("case \"" + phase + "\"");
        }
        assertThat(phases).as("every implementation phase has a row").hasSize(28);
    }

    @Test
    void theVerifierCanRunEveryScenarioTogether() throws IOException {
        String verifier = Files.readString(VERIFIER);
        assertThat(verifier).contains("--parity-all");
        assertThat(verifier).contains("PARITY_SCENARIOS");
        assertThat(Files.readString(PROBE)).contains("\"24\"");
    }

    @Test
    void oneLauncherRunsTheShowcaseFromThePackagedJar() throws IOException {
        String launcher = Files.readString(Path.of("run-demo.sh"));
        assertThat(launcher)
                .contains("examples.parity.Showcase")
                .contains("--enable-native-access=ALL-UNNAMED")
                .contains("target/showcase-lib");
        assertThat(Files.readString(Path.of("examples", "parity", "README.md"))).contains("examples.parity.Showcase");
    }

    private static List<String> cells(String row) {
        List<String> cells = new ArrayList<>();
        for (String cell : row.substring(1, row.length() - 1).split("\\|", -1)) {
            cells.add(cell.trim());
        }
        return cells;
    }
}

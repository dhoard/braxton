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
import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.RuntimeOptions;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.app.TerminalPresentation;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Widget;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Phase 24: the integrated showcase - one executable catalog over every completed phase.
 *
 * <p>The catalog is the root component. It lists the implemented phases by their stable ids, mounts
 * the selected phase's own scene component as its child, and returns to the list when the scene is
 * left. Three things follow from that shape and are what this class is for:</p>
 *
 * <ul>
 *   <li><b>One scene is alive at a time.</b> {@link Scene#children} declares the open scene's node and
 *       nothing else, so closing a scene is the documented unmount: its scoped subscriptions and
 *       commands are canceled, its focus elements are released, and a later open starts that scene
 *       from its own {@code init} again. A closed scene cannot receive a later message from a timer
 *       it started.
 *   <li><b>Only the active surface routes.</b> In the catalog the scene's own actions are capture
 *       bindings and the child is not mounted; in a scene the only captured gesture is {@code ctrl+c},
 *       so a field still types every letter it owns.
 *   <li><b>Leaving is one gesture.</b> {@code ctrl+c} returns to the catalog from inside a scene,
 *       and {@code q} or {@code ctrl+c} quit from the catalog. The return is a capture binding in
 *       each active scope, including modal dialogs. A demo's own quit gesture ends the session,
 *       because that is what its own {@code Command.quit()} means and
 *       the runtime does not let an application turn a quit request into a message.
 * </ul>
 *
 * <p>The status rows name the state the verifier asserts - {@code catalog=27 open=none selected=01
 * ...} - so a terminal scenario reads the catalog's own decision instead of decoding styling, and
 * the phase demos keep their own status rows while they are mounted.</p>
 */
public final class Showcase {

    /**
     * The heading the catalog paints above its list.
     */
    public static final String HEADING = "braxton parity showcase";

    /**
     * The rows the catalog spends on its status row, its heading and its compact help.
     */
    public static final int CHROME_ROWS = 3;

    private static final Style STATUS = Style.NONE.withBold();

    /**
     * The identity of the showcase's one element subtree.
     */
    private static final Style SELECTED = Style.NONE.withInvert();

    private static final String RETURN = "ctrl+c";

    /**
     * The catalog's one size source.
     */
    private static final SubscriptionId SIZE = SubscriptionId.of("catalog-size");

    private Showcase() {}

    /**
     * The catalog's state.
     *
     * @param selected the index of the highlighted entry
     * @param top the index of the first entry the list window shows
     * @param rows the terminal's rows, as the last size report stated them
     * @param open the id of the mounted scene, or empty in the catalog
     * @param sceneModel the mounted scene's own model, or null in the catalog
     * @param help whether the grouped help replaces the list
     * @param last the last catalog gesture, for the status row
     */
    public record Model(int selected, int top, int rows, String open, Object sceneModel, boolean help, String last) {

        /**
         * Keeps every value representable.
         */
        public Model {
            selected = Math.max(0, selected);
            top = Math.max(0, top);
            rows = Math.max(1, rows);
            open = open == null ? "" : open;
            last = last == null ? "none" : last;
            if (open.isEmpty()) {
                sceneModel = null;
            }
        }

        /**
         * A catalog over one list size, with nothing mounted.
         *
         * @param rows the terminal's rows
         * @return the state
         */
        public static Model of(int rows) {
            return new Model(0, 0, rows, "", null, false, "none");
        }

        /**
         * Whether a scene is mounted.
         *
         * @return true while a scene owns the screen
         */
        public boolean inScene() {
            return !open.isEmpty();
        }

        /**
         * The rows the entry list may paint.
         *
         * @return the window's height, never below one
         */
        public int visible() {
            return Math.max(1, rows - CHROME_ROWS);
        }

        Model withSelected(int value) {
            return new Model(value, top, rows, open, sceneModel, help, last);
        }

        Model withTop(int value) {
            return new Model(selected, value, rows, open, sceneModel, help, last);
        }

        Model withRows(int value) {
            return new Model(selected, top, value, open, sceneModel, help, last);
        }

        /**
         * The same state mounting a scene, or with nothing mounted when the id is empty.
         *
         * @param id the scene's id, or empty
         * @param model the scene's own model
         * @return the new state
         */
        public Model withScene(String id, Object model) {
            return new Model(selected, top, rows, id, model, false, last);
        }

        Model withHelp(boolean value) {
            return new Model(selected, top, rows, open, sceneModel, value, last);
        }

        Model withLast(String value) {
            return new Model(selected, top, rows, open, sceneModel, help, value);
        }

        /**
         * Bounded form: the mounted scene's model is not this value's business.
         */
        @Override
        public String toString() {
            return "Showcase.Model[selected=" + selected + ", top=" + top + ", rows=" + rows + ", open=" + open
                    + ", sceneModel="
                    + (sceneModel == null ? "none" : sceneModel.getClass().getSimpleName()) + ", help="
                    + help + ", last=" + last + "]";
        }
    }

    /**
     * Every message the catalog handles.
     */
    public sealed interface Msg {

        /**
         * Moves the highlight by a number of entries.
         *
         * @param delta how many entries to move by, negative for up
         */
        record Move(int delta) implements Msg {}

        /**
         * Highlights the first entry.
         */
        record First() implements Msg {}

        /**
         * Highlights the last entry.
         */
        record Last() implements Msg {}

        /**
         * A pointer press on one entry.
         *
         * @param index the entry under the pointer
         * @param clickCount the press this is: a second press on the same cell opens the entry
         */
        record Press(int index, int clickCount) implements Msg {}

        /**
         * A wheel gesture over the list.
         *
         * @param delta how many entries to move by, negative for up
         */
        record Wheel(int delta) implements Msg {}

        /**
         * Opens one entry.
         *
         * <p>A negative index means the highlighted entry, which is what the {@code enter} action
         * carries: the map is built once and the highlight lives in the model.</p>
         *
         * @param index the entry to open, or a negative value for the highlighted one
         */
        record Open(int index) implements Msg {}

        /**
         * Leaves the mounted scene, or does nothing in the catalog.
         */
        record Back() implements Msg {}

        /**
         * Switches between the list and the grouped help.
         */
        record ToggleHelp() implements Msg {}

        /**
         * Reports the terminal's new geometry.
         *
         * @param rows the reported rows
         * @param columns the reported columns
         */
        record Resized(int rows, int columns) implements Msg {}

        /**
         * Asks the session to end.
         */
        record Quit() implements Msg {}

        /**
         * One message of the mounted scene, lifted through its mount.
         *
         * @param id the scene the message belongs to
         * @param message the scene's own message
         */
        record SceneMessage(String id, Object message) implements Msg {
            /**
             * Creates a {@code SceneMessage} value.
             *
             * @param id the scene the message belongs to
             * @param message the scene's own message
             */
            public SceneMessage {
                Objects.requireNonNull(id, "id");
                Objects.requireNonNull(message, "message");
            }
        }
    }

    /**
     * The catalog's own actions.
     *
     * @return the key map, in help order
     */
    public static KeyMap<Msg> keys() {
        return KeyMap.of(
                KeyMap.group(
                        "Catalog",
                        KeyAction.<Msg>of("up", new Msg.Move(-1), "\u2191/k", "up", "up", "k"),
                        KeyAction.<Msg>of("down", new Msg.Move(1), "\u2193/j", "down", "down", "j"),
                        KeyAction.<Msg>of("first", new Msg.First(), "home", "first", "home"),
                        KeyAction.<Msg>of("last", new Msg.Last(), "end", "last", "end")),
                KeyMap.group(
                        "Scene",
                        KeyAction.<Msg>of("open", new Msg.Open(-1), "enter", "open the scene", "enter"),
                        KeyAction.<Msg>of("help", new Msg.ToggleHelp(), "?", "toggle help", "?"),
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q", "ctrl+c")));
    }

    /**
     * The gesture a mounted scene leaves to the catalog.
     *
     * <p>{@code ctrl+c} is the escape hatch a terminal user reaches for. Every letter a scene might
     * type stays the scene's, so the catalog never eats a character out of a draft.</p>
     *
     * @return the key map
     */
    public static KeyMap<Msg> sceneKeys() {
        return KeyMap.of(
                KeyMap.group("Catalog", KeyAction.<Msg>of("back", new Msg.Back(), "", "back to catalog", RETURN)));
    }

    /**
     * Runs the showcase against the owned terminal.
     *
     * <p>The fixture directory is created here and removed when the run ends, so the picker and the
     * workspace never browse the process's own working directory.</p>
     *
     * @return how the run ended
     */
    public static ExitResult run() {
        Path fixture;
        try {
            fixture = fixture();
        } catch (IOException failure) {
            return ExitResult.failure(failure, List.of());
        }
        try {
            RuntimeOptions options = RuntimeOptions.defaults()
                    .withTerminal(TerminalOptions.DEFAULT.withMouseMode(MouseMode.CELL_MOTION));
            return Application.run(new Scene(fixture), options);
        } finally {
            remove(fixture);
        }
    }

    /**
     * Runs the showcase.
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

    /**
     * Creates the owned fixture directory the picker and the workspace browse.
     *
     * <p>The documents are the workspace's own {@link SceneCatalog#DOCUMENTS}, written out so a
     * picker can list and choose them, plus one file the picker's extension policy refuses. Nothing
     * outside this directory is read or written.</p>
     *
     * @return the directory
     * @throws IOException when the fixture cannot be created
     */
    public static Path fixture() throws IOException {
        Path root = Files.createTempDirectory("braxton-showcase-");
        for (var document : SceneCatalog.DOCUMENTS.entrySet()) {
            Files.writeString(root.resolve(document.getKey()), document.getValue(), StandardCharsets.UTF_8);
        }
        Files.writeString(root.resolve("archive.bin"), "not a document\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(
                root.resolve("docs/reference.md"),
                "# Reference\n\nThe lantern is listed here.\n",
                StandardCharsets.UTF_8);
        return root;
    }

    /**
     * Removes a fixture directory and everything in it.
     *
     * @param root the directory to remove
     */
    public static void remove(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // The fixture is temporary and owned; a leftover directory is the filesystem's business,
            // not the session's, and nothing here reports through the terminal it just gave back.
        }
    }

    /**
     * The catalog itself: one list, one mounted scene, and the frames around them.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final List<SceneCatalog.Slot> slots;

        private final KeyMap<Msg> catalogKeys = keys();

        private final KeyMap<Msg> mountedKeys = sceneKeys();

        /**
         * The startup facts the runtime gave {@code init}.
         *
         * <p>A scene opened later has to initialize from the same facts, and a pure update is
         * handed no geometry of its own: the terminal a session started on is the one it still has,
         * and its size arrives as a resize message into the model.</p>
         */
        private InitContext startup = new InitContext(null, null);

        /**
         * A catalog over an owned fixture directory.
         *
         * @param fixture the directory the mounted picker and workspace browse
         */
        public Scene(Path fixture) {
            this.slots = SceneCatalog.slots(Objects.requireNonNull(fixture, "fixture"));
        }

        /**
         * The catalog's entries, in catalog order.
         *
         * @return the entries
         */
        public List<SceneCatalog.Entry> entries() {
            return slots.stream().map(SceneCatalog.Slot::entry).toList();
        }

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            startup = context;
            int rows = context.viewport().rows() > 0 ? context.viewport().rows() : 24;
            return Update.next(Model.of(rows));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Move move -> move(model, move.delta());
                case Msg.First first -> highlight(model, 0);
                case Msg.Last last -> highlight(model, slots.size() - 1);
                case Msg.Wheel wheel -> move(model, wheel.delta());
                case Msg.Press press -> pressed(model, press);
                case Msg.Open open -> opened(model, open.index());
                case Msg.Back back -> back(model);
                case Msg.ToggleHelp help ->
                    Update.next(model.withHelp(!model.help()).withLast("help"));
                case Msg.Resized resized ->
                    Update.next(revealed(model.withRows(resized.rows()).withLast("resized")));
                case Msg.Quit quit -> Update.next(model, Command.quit());
                case Msg.SceneMessage scene -> scene(model, scene);
            };
        }

        /**
         * The catalog's own size source, plus the mounted scene's lifted through its mount.
         *
         * <p>The catalog owns how many entries its window may show, so it is the one that has to
         * hear about a resize. A mounted scene declares its own sources - including its own size
         * source when it has one - and the mount is what scopes them.</p>
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            Subscription<Msg> size = Subscription.resize(SIZE, value -> new Msg.Resized(value.rows(), value.cols()));
            return model.inScene()
                    ? Subscription.batch(size, slots.get(index(model.open())).subscriptions(model))
                    : size;
        }

        @Override
        public ComponentTree children(Model model) {
            return model.inScene()
                    ? ComponentTree.of(slots.get(index(model.open())).tree(model))
                    : ComponentTree.empty();
        }

        @Override
        public Element<Msg> view(Model model, ViewContext context) {
            return scene(model, context);
        }

        /**
         * The gestures the frame keeps for itself: the catalog's map while the list is up, and the
         * back key plus whatever the open scene claims while a scene is.
         *
         * <p>Only claims are listed here. A scene's ordinary bindings are its own keys - the letters
         * its fields take - so they belong to the last tier, not to the tier that beats the focused
         * widget.</p>
         */
        @Override
        public List<Binding<Msg>> claims(Model model) {
            if (!model.inScene()) {
                return catalogKeys.bindings();
            }
            List<Binding<Msg>> routes = new ArrayList<>(mountedKeys.bindings());
            routes.addAll(slots.get(index(model.open())).claims(model));
            return List.copyOf(routes);
        }

        /**
         * The open scene's own keys, answered once its widgets have declined them.
         */
        @Override
        public List<Binding<Msg>> bindings(Model model) {
            if (!model.inScene()) {
                return List.of();
            }
            List<Binding<Msg>> routes = new ArrayList<>(mountedKeys.bindings());
            routes.addAll(slots.get(index(model.open())).bindings(model));
            return List.copyOf(routes);
        }

        /**
         * What the open scene asks the frame to present, if it asks for anything.
         */
        @Override
        public TerminalPresentation presentation(Model model) {
            return model.inScene() ? slots.get(index(model.open())).presentation(model) : null;
        }

        /**
         * The catalog, or the open scene framed by the showcase's own chrome.
         */
        private Element<Msg> scene(Model model, ViewContext context) {
            int rows = Math.max(1, context.viewport().rows());
            int cols = Math.max(1, context.viewport().cols());
            return model.inScene() ? sceneView(model, rows, cols) : catalogView(model, rows, cols);
        }

        // ── the catalog ─────────────────────────────────────────────────────

        /**
         * What the catalog is showing, in the terms the verifier asserts.
         */
        private String status(Model model) {
            return "catalog=" + slots.size()
                    + " open=" + (model.inScene() ? model.open() : "none")
                    + " selected=" + entry(model.selected()).id()
                    + " top=" + model.top()
                    + " help=" + (model.help() ? "full" : "compact")
                    + " last=" + model.last();
        }

        private Element<Msg> catalogView(Model model, int rows, int cols) {
            int visible = Math.max(1, rows - CHROME_ROWS);
            List<Element<Msg>> sections = new ArrayList<>(4);
            sections.add(Elements.fixed(1, Elements.text(status(model), STATUS)));
            sections.add(Elements.fixed(1, Elements.text(HEADING, STATUS)));
            sections.add(Elements.fixed(
                    visible,
                    model.help()
                            ? Help.element(catalogKeys, Help.Config.defaults(), true, cols)
                            : list(model, visible, cols)));
            sections.add(Elements.fixed(1, Elements.text(Help.render(catalogKeys, false, cols))));
            return Elements.column(sections);
        }

        /**
         * The window of entries the state asks for, each one a pointer target of its own.
         */
        private Element<Msg> list(Model model, int visible, int cols) {
            int first = first(model, visible);
            List<Element<Msg>> painted = new ArrayList<>(visible);
            for (int at = first; at < Math.min(slots.size(), first + visible); at++) {
                int index = at;
                painted.add(new Element.Leaf<Entry.Model, Msg>(
                        new Entry(),
                        new Entry.Model(
                                index, index == model.selected(), row(index, index == model.selected(), cols))));
            }
            return Elements.column(painted);
        }

        /**
         * One catalog row as a leaf: it paints the line and answers the pointer that lands on it.
         *
         * <p>The press is the row's own, so a click selects the entry it landed on without the
         * showcase having to work out which line a cell belongs to.</p>
         */
        private static final class Entry implements Widget<Entry.Model, Msg> {

            /**
             * One painted row: which entry it is, whether it is selected, and the line itself.
             */
            private record Model(int index, boolean selected, StyledText line) {}

            @Override
            public Size measure(Size max, Model model) {
                return new Size(
                        1, Math.min(max.cols(), TextMeasure.columns(model.line().plainText())));
            }

            @Override
            public void paint(Surface surface, Rect bounds, Model model) {
                surface.writeLine(bounds.row(), bounds.col(), bounds.cols(), model.line(), WidthPolicy.PINNED);
            }

            @Override
            public boolean canFocus(Model model) {
                return true;
            }

            @Override
            public boolean focused(Model model) {
                return model.selected();
            }

            @Override
            public void onPointer(PointerEvent pointer, Rect bounds, Model model, Consumer<Msg> emit) {
                pointer(model.index(), pointer).ifPresent(emit);
            }
        }

        private StyledText row(int index, boolean selected, int cols) {
            String marker = selected ? "\u276F " : "  ";
            String text = TextMeasure.truncate(marker + entry(index).line(), Math.max(1, cols));
            return StyledText.of(text, selected ? SELECTED : Style.NONE);
        }

        /**
         * One pointer report on one entry: a press selects it, a repeated press opens it.
         */
        private static Optional<Msg> pointer(int index, PointerEvent event) {
            return switch (event.action()) {
                case PRESS ->
                    event.button() == PointerButton.LEFT
                            ? Optional.of(new Msg.Press(index, event.clickCount()))
                            : Optional.empty();
                case WHEEL -> Optional.of(new Msg.Wheel(event.button() == PointerButton.WHEEL_UP ? -1 : 1));
                case RELEASE, MOTION -> Optional.empty();
            };
        }

        /**
         * The first entry the window shows: the state's top, clamped to a window that fits.
         */
        private int first(Model model, int visible) {
            return Math.max(0, Math.min(model.top(), Math.max(0, slots.size() - visible)));
        }

        // ── the mounted scene ───────────────────────────────────────────────

        private String sceneStatus(Model model) {
            SceneCatalog.Entry entry = entry(index(model.open()));
            return "open=" + entry.id() + " scene=" + entry.title() + " " + RETURN + "=back last=" + model.last();
        }

        private Element<Msg> sceneView(Model model, int rows, int cols) {
            int body = Math.max(0, rows - 2);
            Element<Msg> child = slots.get(index(model.open())).view(model, new ViewContext(new Viewport(body, cols)));
            return sceneFrame(model, child, body, cols);
        }

        /**
         * The showcase's own chrome: one status row, the scene, and the back key.
         */
        private Element<Msg> sceneFrame(Model model, Element<Msg> child, int body, int cols) {
            List<Element<Msg>> sections = new ArrayList<>(3);
            sections.add(Elements.fixed(1, Elements.text(sceneStatus(model), STATUS)));
            sections.add(Elements.fixed(body, child));
            sections.add(Elements.fixed(1, Elements.text(Help.render(mountedKeys, false, cols))));
            return Elements.column(sections);
        }

        // ── the reductions ──────────────────────────────────────────────────

        private Update<Model, Msg, Never> move(Model model, int delta) {
            if (delta == 0) {
                return Update.next(model);
            }
            return highlight(model, model.selected() + delta);
        }

        private Update<Model, Msg, Never> pressed(Model model, Msg.Press press) {
            if (model.inScene()) {
                return Update.next(model);
            }
            int at = clamp(press.index());
            Model picked = revealed(
                    model.withSelected(at).withLast("press:" + entry(at).id()));
            // A first press only moves the highlight; a repeated press on the same row is the
            // pointer's "open", which is what makes the catalog usable without a keyboard.
            return press.clickCount() > 1 ? opened(picked, at) : Update.next(picked);
        }

        private Update<Model, Msg, Never> highlight(Model model, int index) {
            if (model.inScene()) {
                return Update.next(model);
            }
            return Update.next(revealed(model.withSelected(clamp(index)).withLast("move")));
        }

        /**
         * Opens one entry, replacing whatever was mounted.
         *
         * <p>Entering a second scene in one step is the same transition as leaving the first and
         * opening the next: the children this model declares are the new scene's alone, so the old
         * scene's sources and commands are canceled when the transition commits.</p>
         */
        private Update<Model, Msg, Never> opened(Model model, int index) {
            int at = index < 0 ? clamp(model.selected()) : clamp(index);
            SceneCatalog.Slot slot = slots.get(at);
            Update<Model, Msg, Never> started = slot.enter(
                    startup,
                    revealed(model.withSelected(at)).withScene(slot.entry().id(), null));
            return new Update<>(
                    started.model().withLast("open:" + slot.entry().id()),
                    model.open().equals("31")
                            ? Command.sequence(Command.debugOverlay(false), started.command())
                            : started.command(),
                    started.outputs());
        }

        private Update<Model, Msg, Never> back(Model model) {
            if (!model.inScene()) {
                return Update.next(model.withLast("catalog"));
            }
            // Declaring no child is the unmount: the scene's subscriptions and commands end here.
            // The diagnostics demo explicitly owns its session-level overlay preference.
            return Update.next(
                    model.withScene("", null).withLast("back"),
                    model.open().equals("31") ? Command.debugOverlay(false) : Command.none());
        }

        private Update<Model, Msg, Never> scene(Model model, Msg.SceneMessage message) {
            if (!model.open().equals(message.id())) {
                // A message from a scene that is no longer mounted names nothing: dropping it is
                // what keeps a stale timer from painting over the catalog.
                return Update.next(model.withLast("stale:" + message.id()));
            }
            return slots.get(index(message.id())).update(model, message.message());
        }

        /**
         * The same state with the window moved so the highlighted entry is inside it.
         */
        private Model revealed(Model model) {
            int visible = model.visible();
            int top = model.top();
            if (model.selected() < top) {
                top = model.selected();
            } else if (model.selected() >= top + visible) {
                top = model.selected() - visible + 1;
            }
            return model.withTop(Math.max(0, Math.min(top, Math.max(0, slots.size() - visible))));
        }

        private int clamp(int index) {
            return Math.max(0, Math.min(index, slots.size() - 1));
        }

        private SceneCatalog.Entry entry(int index) {
            return slots.get(clamp(index)).entry();
        }

        /**
         * The slot index of one entry id.
         */
        private int index(String id) {
            for (int at = 0; at < slots.size(); at++) {
                if (slots.get(at).entry().id().equals(id)) {
                    return at;
                }
            }
            throw new IllegalArgumentException("no catalog entry named " + id);
        }
    }
}

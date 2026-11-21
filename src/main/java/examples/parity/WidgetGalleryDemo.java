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
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.BarChart;
import io.github.dhoard.braxton.widget.Calendar;
import io.github.dhoard.braxton.widget.Checkbox;
import io.github.dhoard.braxton.widget.CommandPalette;
import io.github.dhoard.braxton.widget.Dialog;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Form;
import io.github.dhoard.braxton.widget.Gauge;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.LineChart;
import io.github.dhoard.braxton.widget.LogViewer;
import io.github.dhoard.braxton.widget.Menu;
import io.github.dhoard.braxton.widget.MultiSelect;
import io.github.dhoard.braxton.widget.NotificationCenter;
import io.github.dhoard.braxton.widget.RadioGroup;
import io.github.dhoard.braxton.widget.Scrollbar;
import io.github.dhoard.braxton.widget.Select;
import io.github.dhoard.braxton.widget.Severity;
import io.github.dhoard.braxton.widget.Sparkline;
import io.github.dhoard.braxton.widget.SplitPane;
import io.github.dhoard.braxton.widget.TextInput;
import io.github.dhoard.braxton.widget.TextInputConfig;
import io.github.dhoard.braxton.widget.Toast;
import io.github.dhoard.braxton.widget.Tree;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Phase 28 parity demo: the widget gallery.
 *
 * <p>R-10 asks for a milestone of new widgets that each answer the keyboard, the pointer, a live
 * theme and a narrow pane. The widget suites prove that one widget at a time; this scene is the
 * other half of the claim - every milestone-4 widget on one screen, sharing one model, one key path
 * and one theme store, so what a reader sees is the shipped set composed rather than nineteen
 * separate test fixtures.</p>
 *
 * <p>Nineteen widgets do not fit on a twenty-four row terminal at a size worth reading, so the
 * gallery is four pages - choice controls, navigation, message surfaces and data views - of which
 * exactly one is painted at a time. The page is application state ({@code alt+1} through {@code
 * alt+4}), and so is focus: {@code tab} walks the page's own specimens and writes the focus flag
 * into each widget's model, because focus lives in the model rather than in the tree (D-14).</p>
 *
 * <p>Three rules the scene is written to, and they are the point of the demo as much as of the
 * widgets:</p>
 *
 * <ul>
 *   <li><b>A widget instance outlives a frame.</b> Every widget is held by the scene, not built by
 *       the painter: a pointer capture, a drag and a double press belong to the widget that started
 *       them, which is the rule WP-4.1 settled and every later widget follows.</li>
 *   <li><b>A widget's output is the scene's message.</b> Every specimen reports typed outputs -
 *       {@code Chosen}, {@code Inspected}, {@code Submitted}, {@code Expired} - and the gallery
 *       records the kind on its status row rather than reaching into the widget again. Nothing here
 *       reads a widget's state twice.</li>
 *   <li><b>A scrollbar scrolls something.</b> The standalone {@link Scrollbar} is not decoration: its
 *       window is admitted over the log viewer's own window, and a bar gesture becomes the log line
 *       it lands on, so the bar and the log cannot disagree about where the reader is.</li>
 * </ul>
 *
 * <p>The status rows are what a terminal verifier asserts, so no assertion decodes a style: the
 * page, its specimens, whose turn it is, the scene's log of its own gestures and the published state
 * of each widget on the current page are all plain text.</p>
 */
public final class WidgetGalleryDemo {

    /**
     * The title row, and what a packaged launch looks for to prove the scene started.
     */
    public static final String TITLE = "braxton widget gallery \u2014 milestones 4.1 to 4.9";

    /**
     * The rows the notification center paints.
     */
    public static final int NOTICE_ROWS = 5;

    /**
     * The rows the log viewer paints.
     */
    public static final int LOG_ROWS = 6;

    /**
     * The lines the log starts with.
     */
    public static final int LOG_LINES = 20;

    /**
     * The rows the command palette paints, its query row included.
     */
    public static final int PALETTE_ROWS = 5;

    /**
     * The rows the menu paints.
     */
    public static final int MENU_ROWS = 4;

    /**
     * The rows the tree paints.
     */
    public static final int TREE_ROWS = 7;

    /**
     * The rows the bar chart paints.
     */
    public static final int BAR_ROWS = 4;

    /**
     * The rows the line chart paints.
     */
    public static final int LINE_ROWS = 6;

    /**
     * The rows the split's region has.
     */
    public static final int SPLIT_ROWS = 9;

    static final Style TITLE_STYLE = Style.NONE.withBold();

    static final Style FIELD = Style.NONE;

    /**
     * The scene's one resize source.
     */
    private static final SubscriptionId SIZE = SubscriptionId.of("gallery-size");

    private WidgetGalleryDemo() {}

    /**
     * One gallery page: what it is called, and the specimens it paints in focus order.
     *
     * <p>The names are the gallery's own identities rather than the widget class names, because a
     * page can hold two of one widget and because a status row is text a reader - or a verifier -
     * reads. {@link #widgets()} is the mapping back to the widget each name demonstrates, which is
     * what makes "the gallery covers every milestone-4 widget" a testable statement.</p>
     */
    public enum Page {

        /**
         * The choice controls: fields that pick one value, several, or a boolean.
         */
        CHOICE("choice", List.of("select", "tags", "remember", "density", "form")),

        /**
         * The navigation widgets: a hierarchy, a menu, a palette and the panes around them.
         */
        NAVIGATE("navigate", List.of("tree", "bar", "split", "menu", "palette")),

        /**
         * The message surfaces: notifications, a log and the transient toast.
         */
        MESSAGE("message", List.of("notices", "log")),

        /**
         * The data views: three charts, an indicator and a date picker.
         */
        DATA("data", List.of("line", "bars", "spark", "calendar"));

        private final String id;

        private final List<String> names;

        Page(String id, List<String> names) {
            this.id = id;
            this.names = names;
        }

        /**
         * The page's short identity, as the status row prints it.
         *
         * @return the id
         */
        public String id() {
            return id;
        }

        /**
         * The specimens the page paints, in the order {@code tab} walks them.
         *
         * @return the names
         */
        public List<String> names() {
            return names;
        }
    }

    /**
     * Every milestone-4 widget the gallery places, by the gallery's own name for it.
     *
     * <p>The map is the demo's coverage claim in a form a test can check: each entry names the widget
     * type the page paints, so a widget that is added to the milestone without a specimen here - or a
     * specimen whose page stopped painting it - is a failing assertion rather than a quiet omission.
     * Three entries are not on a page's focus walk: the toast and the dialog are layers the scene
     * opens over the page rather than specimens the keyboard walks to, and the gauge owns no keys
     * because an indicator is not a control.</p>
     *
     * @return the widget each name demonstrates, in page order
     */
    public static Map<String, Class<?>> widgets() {
        Map<String, Class<?>> widgets = new LinkedHashMap<>();
        widgets.put("select", Select.class);
        widgets.put("tags", MultiSelect.class);
        widgets.put("remember", Checkbox.class);
        widgets.put("density", RadioGroup.class);
        widgets.put("form", Form.class);
        widgets.put("tree", Tree.class);
        widgets.put("bar", Scrollbar.class);
        widgets.put("split", SplitPane.class);
        widgets.put("menu", Menu.class);
        widgets.put("palette", CommandPalette.class);
        widgets.put("notices", NotificationCenter.class);
        widgets.put("log", LogViewer.class);
        widgets.put("toast", Toast.class);
        widgets.put("dialog", Dialog.class);
        widgets.put("line", LineChart.class);
        widgets.put("bars", BarChart.class);
        widgets.put("spark", Sparkline.class);
        widgets.put("gauge", Gauge.class);
        widgets.put("calendar", Calendar.class);
        return Map.copyOf(widgets);
    }

    /**
     * Every message the gallery handles: one per specimen, one per editor and the scene's own.
     */
    public sealed interface Msg {

        /**
         * The user asked for a page.
         *
         * @param index the page index
         */
        record PageChosen(int index) implements Msg {}

        /**
         * The keyboard moves to another specimen on the page.
         *
         * @param delta the specimens to step
         */
        record FocusNext(int delta) implements Msg {}

        /**
         * The select's own message.
         *
         * @param value the message
         */
        record Revision(Select.Msg value) implements Msg {}

        /**
         * The multi-select's own message.
         *
         * @param value the message
         */
        record Tags(MultiSelect.Msg value) implements Msg {}

        /**
         * The checkbox's own message.
         *
         * @param value the message
         */
        record Remember(Checkbox.Msg value) implements Msg {}

        /**
         * The radio group's own message.
         *
         * @param value the message
         */
        record Density(RadioGroup.Msg value) implements Msg {}

        /**
         * The form's own message.
         *
         * @param value the message
         */
        record FormMessage(Form.Msg value) implements Msg {}

        /**
         * The name editor's own message.
         *
         * @param value the message
         */
        record NameField(TextInput.Msg value) implements Msg {}

        /**
         * The email editor's own message.
         *
         * @param value the message
         */
        record EmailField(TextInput.Msg value) implements Msg {}

        /**
         * The tree's own message.
         *
         * @param value the message
         */
        record TreeMessage(Tree.Msg value) implements Msg {}

        /**
         * The menu's own message.
         *
         * @param value the message
         */
        record MenuMessage(Menu.Msg value) implements Msg {}

        /**
         * The command palette's own message.
         *
         * @param value the message
         */
        record PaletteMessage(CommandPalette.Msg value) implements Msg {}

        /**
         * The split's own message.
         *
         * @param value the message
         */
        record SplitMessage(SplitPane.Msg value) implements Msg {}

        /**
         * The scrollbar's own message.
         *
         * @param value the message
         */
        record ScrollMessage(Scrollbar.Msg value) implements Msg {}

        /**
         * The notification center's own message.
         *
         * @param value the message
         */
        record NoticesMessage(NotificationCenter.Msg value) implements Msg {}

        /**
         * The log viewer's own message.
         *
         * @param value the message
         */
        record LogMessage(LogViewer.Msg value) implements Msg {}

        /**
         * The toast's own message, including the runtime's time readings.
         *
         * @param value the message
         */
        record ToastMessage(Toast.Msg value) implements Msg {}

        /**
         * The dialog's own message.
         *
         * @param value the message
         */
        record DialogMessage(Dialog.Msg value) implements Msg {}

        /**
         * The calendar's own message.
         *
         * @param value the message
         */
        record DateMessage(Calendar.Msg value) implements Msg {}

        /**
         * The line chart's own message.
         *
         * @param value the message
         */
        record LineMessage(LineChart.Msg value) implements Msg {}

        /**
         * The bar chart's own message.
         *
         * @param value the message
         */
        record BarsMessage(BarChart.Msg value) implements Msg {}

        /**
         * The sparkline's own message.
         *
         * @param value the message
         */
        record SparkMessage(Sparkline.Msg value) implements Msg {}

        /**
         * Shows a toast, which is the scene's gesture rather than a widget's.
         */
        record ShowToast() implements Msg {}

        /**
         * Opens the confirmation dialog.
         */
        record OpenDialog() implements Msg {}

        /**
         * Submits the form with the values the editors hold.
         */
        record SubmitForm() implements Msg {}

        /**
         * The terminal was resized.
         *
         * @param size the new terminal size
         */
        record Resized(Size size) implements Msg {}

        /**
         * Ends the session.
         */
        record Quit() implements Msg {}
    }

    /**
     * The gallery's own actions: pages, focus, the two overlays, submission and quitting.
     *
     * <p>These are the last tier of the one key path, so every gesture a focused specimen answers
     * arrives there first: {@code escape} closes an open select or dialog before it reaches the quit
     * below, and a chart's arrows still move its reader rather than the page.</p>
     *
     * @return the map, in help order
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Pages",
                        KeyAction.<Msg>of("choice", new Msg.PageChosen(0), "alt+1", "choice controls", "alt+1"),
                        KeyAction.<Msg>of("navigate", new Msg.PageChosen(1), "alt+2", "navigation", "alt+2"),
                        KeyAction.<Msg>of("message", new Msg.PageChosen(2), "alt+3", "message surfaces", "alt+3"),
                        KeyAction.<Msg>of("data", new Msg.PageChosen(3), "alt+4", "data views", "alt+4")),
                KeyMap.group(
                        "Scene",
                        KeyAction.<Msg>of("focus", new Msg.FocusNext(1), "tab", "next specimen", "tab"),
                        KeyAction.<Msg>of("back", new Msg.FocusNext(-1), "shift+tab", "previous specimen", "shift+tab"),
                        KeyAction.<Msg>of("toast", new Msg.ShowToast(), "ctrl+g", "toast", "ctrl+g"),
                        KeyAction.<Msg>of("dialog", new Msg.OpenDialog(), "ctrl+d", "dialog", "ctrl+d"),
                        KeyAction.<Msg>of("submit", new Msg.SubmitForm(), "ctrl+s", "submit the form", "ctrl+s")),
                KeyMap.group(
                        "Session",
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "q", "quit", "q"),
                        KeyAction.<Msg>of("escape", new Msg.Quit(), "esc", "quit", "escape")));
    }

    /**
     * Runs the demo against the owned terminal.
     *
     * <p>The gallery asks for pointer reports because every specimen on it is a pointer target: a
     * press selects a log line, chooses a menu item, moves a split and picks a calendar day, and the
     * level asked for is the one those gestures need.</p>
     *
     * @return the exit result
     */
    public static ExitResult run() {
        return Application.run(
                new Scene(),
                RuntimeOptions.defaults().withTerminal(TerminalOptions.DEFAULT.withMouseMode(MouseMode.CLICK)));
    }

    /**
     * Runs the demo.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /**
     * The gallery: four pages of specimens, one model and one key path.
     */
    public static final class Scene implements Component<GalleryState, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        private final KeyMap<Msg> keys = defaultKeys();
        final Select revision =
                new Select(Select.Config.of(GalleryFixtures.REVISIONS).withRows(2));

        final MultiSelect tags =
                new MultiSelect(MultiSelect.Config.of(GalleryFixtures.TAGS).withRows(2));

        final Checkbox remember = new Checkbox(StyledText.plain("remember this page"));

        final RadioGroup density = new RadioGroup(GalleryFixtures.DENSITIES);

        final Form form = new Form(new Form.Config(GalleryFixtures.FIELDS, GalleryFixtures.VALIDATOR));

        final TextInput name = new TextInput(TextInputConfig.defaults().withPrompt("name: ", TITLE_STYLE));

        final TextInput email = new TextInput(TextInputConfig.defaults().withPrompt("email: ", TITLE_STYLE));

        final Tree tree = new Tree(
                GalleryFixtures::childrenOf,
                Tree.Config.of(GalleryFixtures.TREE_NODES).withRows(TREE_ROWS));

        final Menu menu = new Menu(new Menu.Config(GalleryFixtures.MENU_ENTRIES, MENU_ROWS));

        final CommandPalette palette =
                new CommandPalette(new CommandPalette.Config(GalleryFixtures.COMMANDS, PALETTE_ROWS));

        final SplitPane split = new SplitPane();

        final Scrollbar bar = new Scrollbar();

        final NotificationCenter notices = new NotificationCenter(new NotificationCenter.Config(8, NOTICE_ROWS));

        final LogViewer log = new LogViewer(new LogViewer.Config(LOG_LINES, LOG_ROWS));

        final Toast toast = new Toast();

        final Dialog dialog = new Dialog(
                Dialog.Config.of(StyledText.plain("apply the gallery change?"), GalleryFixtures.DIALOG_ACTIONS));

        final Calendar calendar = new Calendar(Calendar.Config.defaults().withFirstDayOfWeek(DayOfWeek.MONDAY));

        final LineChart line = new LineChart();

        final BarChart bars = new BarChart(BarChart.Config.defaults().withRows(BAR_ROWS));

        final Sparkline spark = new Sparkline();

        final Gauge<Msg> gauge = new Gauge<>(Gauge.Config.of("budget"));

        @Override
        public Update<GalleryState, Msg, Never> init(InitContext context) {
            return Update.next(GalleryState.starting());
        }

        @Override
        public Update<GalleryState, Msg, Never> update(GalleryState model, Msg message) {
            return switch (message) {
                case Msg.PageChosen chosen -> Update.next(page(model, chosen.index()));
                case Msg.FocusNext next -> Update.next(focused(model, next.delta()));
                case Msg.Revision value -> // The select's own model, reduced by the select itself.
                    keep(model, revision.update(model.revision(), value.value()), "select", GalleryState::withRevision);
                case Msg.Tags value ->
                    keep(model, tags.update(model.tags(), value.value()), "tags", GalleryState::withTags);
                case Msg.Remember value ->
                    keep(
                            model,
                            remember.update(model.remember(), value.value()),
                            "remember",
                            GalleryState::withRemember);
                case Msg.Density value ->
                    keep(model, density.update(model.density(), value.value()), "density", GalleryState::withDensity);
                case Msg.FormMessage value -> {
                    Update<Form.Model, Form.Msg, Form.Output> reduced = form.update(model.form(), value.value());
                    GalleryState next = model.withForm(reduced.model())
                            .withLast(kind("form", reduced.outputs()))
                            .reported();
                    // The form's focus names one of its fields, so a form reduction is the one
                    // gesture that can move the keyboard between two widgets: the editors take
                    // their turn from the form's answer, not from the message that arrived.
                    yield Update.next(GalleryFocus.focusOn(next, next.focus()));
                }
                case Msg.NameField value ->
                    keep(model, name.update(model.name(), value.value()), "name", GalleryState::withName);
                case Msg.EmailField value ->
                    keep(model, email.update(model.email(), value.value()), "email", GalleryState::withEmail);
                case Msg.TreeMessage value ->
                    keep(model, tree.update(model.tree(), value.value()), "tree", GalleryState::withTree);
                case Msg.MenuMessage value ->
                    keep(model, menu.update(model.menu(), value.value()), "menu", GalleryState::withMenu);
                case Msg.PaletteMessage value ->
                    keep(model, palette.update(model.palette(), value.value()), "palette", GalleryState::withPalette);
                case Msg.SplitMessage value ->
                    value.value() instanceof SplitPane.Msg.Moved moved
                            ? Update.next(model.withSplitFirst(moved.first())
                                    .withLast("split-" + moved.first())
                                    .reported())
                            : Update.next(model);
                case Msg.ScrollMessage value ->
                    value.value() instanceof Scrollbar.Msg.Moved moved
                            ? Update.next(scrolled(model, moved.first()))
                            : Update.next(model);
                case Msg.NoticesMessage value ->
                    keep(model, notices.update(model.notices(), value.value()), "notices", GalleryState::withNotices);
                case Msg.LogMessage value ->
                    keep(model, log.update(model.log(), value.value()), "log", GalleryState::withLog);
                case Msg.ToastMessage value -> shown(model, value.value());
                case Msg.DialogMessage value -> reduced(model, dialog.update(model.dialog(), value.value()));
                case Msg.DateMessage value ->
                    keep(model, calendar.update(model.date(), value.value()), "calendar", GalleryState::withDate);
                case Msg.LineMessage value ->
                    keep(model, line.update(model.line(), value.value()), "line", GalleryState::withLine);
                case Msg.BarsMessage value ->
                    keep(model, bars.update(model.bars(), value.value()), "bars", GalleryState::withBars);
                case Msg.SparkMessage value ->
                    keep(model, spark.update(model.spark(), value.value()), "spark", GalleryState::withSpark);
                case Msg.ShowToast ignored -> shown(model, Toast.Msg.Show.of(Severity.SUCCESS, "gallery: a toast"));
                case Msg.OpenDialog ignored -> Update.next(opened(model));
                case Msg.SubmitForm ignored -> submitted(model);
                case Msg.Resized ignored -> Update.next(model);
                case Msg.Quit ignored -> Update.next(model, io.github.dhoard.braxton.effect.Command.quit());
            };
        }

        /**
         * The page's own specimens, plus the two overlays when they are up.
         *
         * <p>A dialog is a modal layer rather than a page: it centers itself over the page that is
         * already painted, so the gallery composes the page once and stacks the dialog's layer over
         * it only while the model says it is open.</p>
         */
        @Override
        public Element<Msg> view(GalleryState model, ViewContext context) {
            int cols = Math.max(1, context.viewport().cols());
            List<Element<Msg>> rows = new ArrayList<>(6);
            rows.add(Elements.fixed(1, Elements.text(TITLE, TITLE_STYLE)));
            rows.add(Elements.fixed(1, Elements.text(status(model), FIELD)));
            rows.add(Elements.fixed(1, Elements.text(turn(model), FIELD)));
            rows.add(Elements.fixed(1, Elements.text(published(model), FIELD)));
            rows.add(GalleryPages.specimens(this, model, cols));
            rows.add(Elements.fixed(1, Elements.text(Help.render(keys, false, cols))));
            Element<Msg> scene = Elements.column(rows);
            return model.dialogOpen()
                    ? Elements.stack(scene, Elements.modal(GalleryPages.dialogLayer(this, model)))
                    : scene;
        }

        /**
         * The scene's turn of the key path: the gallery's own gestures, matched after the focused
         * specimen has declined them.
         */
        @Override
        public List<Binding<Msg>> bindings(GalleryState model) {
            return keys.bindings();
        }

        /**
         * One frame presentation, which is the scene's rather than a child's: the terminal has one
         * title per frame, so the gallery's model owns the answer.
         */
        @Override
        public TerminalPresentation presentation(GalleryState model) {
            return TerminalPresentation.title("Braxton widget gallery");
        }

        /**
         * The size source and the toast's own timer.
         *
         * <p>The toast is the one specimen with a subscription of its own - an interval while it
         * shows - and the scene composes it rather than mounting it, so the results come back as the
         * scene's messages.</p>
         */
        @Override
        public Subscription<Msg> subscriptions(GalleryState model) {
            return Subscription.batch(
                    Subscription.resize(SIZE, value -> new Msg.Resized(value)),
                    toast.subscriptions(model.toast()).map(Msg.ToastMessage::new));
        }

        // ── the scene's own reductions ──────────────────────────────────────

        /**
         * Shows the page at {@code index}, focusing its first specimen.
         */
        private static GalleryState page(GalleryState model, int index) {
            Page target = Page.values()[Math.max(0, Math.min(index, Page.values().length - 1))];
            return GalleryFocus.focusOn(
                            model.withPage(target.ordinal()), target.names().getFirst())
                    .withLast("page-" + target.id())
                    .reported();
        }

        /**
         * Steps the keyboard by {@code delta} specimens of the shown page, wrapping at both ends.
         */
        private static GalleryState focused(GalleryState model, int delta) {
            List<String> names = model.shown().names();
            int at = Math.max(0, names.indexOf(model.focus()));
            String next = names.get(Math.floorMod(at + delta, names.size()));
            return GalleryFocus.focusOn(model, next).withLast("focus-" + next).reported();
        }

        /**
         * The log line the bar landed on, which is how the standalone bar scrolls this scene.
         *
         * <p>The bar reports a position rather than a widget, so the gallery turns it into the log's
         * own selection message: the window is admitted over the selection, which is why one gesture
         * moves both the bar and the log to the same line.</p>
         */
        private GalleryState scrolled(GalleryState model, int first) {
            List<LogViewer.Entry> entries = model.log().entries();
            if (entries.isEmpty()) {
                return model.withLast("bar-empty").reported();
            }
            int index = Math.max(0, Math.min(first, entries.size() - 1));
            Update<LogViewer.Model, LogViewer.Msg, LogViewer.Output> reduced = log.update(
                    model.log(), new LogViewer.Msg.Select(entries.get(index).id()));
            return model.withLog(reduced.model()).withLast("bar-" + index).reported();
        }

        /**
         * The toast's reduction, command included.
         *
         * <p>A shown toast asks for one monotonic reading through its command, so this is the one
         * specimen whose command the scene forwards rather than dropping: the interval it subscribes
         * while it shows is what expires it, and the command is what names its deadline.</p>
         */
        private Update<GalleryState, Msg, Never> shown(GalleryState model, Toast.Msg value) {
            Update<Toast.Model, Toast.Msg, Toast.Output> reduced = toast.update(model.toast(), value);
            String what = value instanceof Toast.Msg.Show ? "toast-shown" : kind("toast", reduced.outputs());
            return new Update<>(
                    model.withToast(reduced.model()).withLast(what).reported(),
                    reduced.command().map(Msg.ToastMessage::new),
                    List.of());
        }

        /**
         * Opens the dialog as a modal layer and gives it the keyboard.
         */
        private static GalleryState opened(GalleryState model) {
            Dialog.Model state = Dialog.Model.of(GalleryFixtures.DIALOG_ACTIONS);
            return model.withDialog(state, true)
                    .withFocus("dialog")
                    .withLast("dialog-open")
                    .reported();
        }

        /**
         * The dialog's reduction: an answer closes the layer and gives the keyboard back.
         *
         * <p>A dialog reports {@code Activated} or {@code Canceled} and never closes itself, because
         * which gesture dismisses it is the application's decision; the gallery's decision is that
         * either answer ends it, and the keyboard returns to the page rather than to a layer that is
         * no longer there.</p>
         */
        private Update<GalleryState, Msg, Never> reduced(
                GalleryState model, Update<Dialog.Model, Dialog.Msg, Dialog.Output> value) {
            if (value.outputs().isEmpty()) {
                return Update.next(
                        model.withDialog(value.model(), true).withLast("dialog").reported());
            }
            GalleryState closed = model.withDialog(new Dialog.Model(GalleryFixtures.DIALOG_ACTIONS, "", false), false)
                    .withLast(kind("dialog", value.outputs()))
                    .reported();
            return Update.next(
                    GalleryFocus.focusOn(closed, closed.shown().names().getFirst()));
        }

        /**
         * Submits the form with what the two editors hold, then reports what the form decided.
         */
        private Update<GalleryState, Msg, Never> submitted(GalleryState model) {
            Form.Values values = Form.Values.of(
                    Map.of("name", model.name().text(), "email", model.email().text()));
            Update<Form.Model, Form.Msg, Form.Output> reduced = form.update(model.form(), new Form.Msg.Submit(values));
            return Update.next(model.withForm(reduced.model())
                    .withLast(kind("form", reduced.outputs()))
                    .reported());
        }

        /**
         * A reduction that reported an output, as the model the scene keeps and the row it prints.
         *
         * <p>Every specimen but the toast issues no command of its own, so the command is not
         * forwarded here; {@link #shown} is the one place it is, and its Javadoc says why. The last
         * thing the scene did is the output's kind, which is what a reader needs to see and what a
         * verifier asserts instead of decoding a style.</p>
         */
        private static <W, O> Update<GalleryState, Msg, Never> keep(
                GalleryState model,
                Update<W, ?, O> reduced,
                String what,
                BiFunction<GalleryState, W, GalleryState> write) {
            return Update.next(write.apply(model, reduced.model())
                    .withLast(kind(what, reduced.outputs()))
                    .reported());
        }

        /**
         * The status text for one reduction: what happened, and the kind of output it reported.
         */
        private static String kind(String what, List<?> outputs) {
            return outputs.isEmpty()
                    ? what
                    : what + "-" + outputs.getFirst().getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The status row: the page, its specimens and how many of them there are.
     *
     * @param model the gallery's state
     * @return the row
     */
    public static String status(GalleryState model) {
        Page page = model.shown();
        return "gallery page=" + (model.page() + 1) + "/" + Page.values().length + " " + page.id() + " widgets="
                + page.names().size() + " names=" + String.join(",", page.names());
    }

    /**
     * The turn row: whose keyboard it is, and what the scene last did.
     *
     * @param model the gallery's state
     * @return the row
     */
    public static String turn(GalleryState model) {
        return "focus=" + model.focus() + " last=" + model.last() + " reports=" + model.reports();
    }

    /**
     * The published state of the shown page's specimens - what a verifier asserts.
     *
     * @param model the gallery's state
     * @return the row
     */
    public static String published(GalleryState model) {
        return switch (model.shown()) {
            case CHOICE ->
                "select=" + model.revision().selected() + " tags="
                        + joined(model.tags().chosen())
                        + " remember=" + (model.remember().checked() ? "on" : "off") + " density="
                        + model.density().selected() + " form=" + model.form().focused();
            case NAVIGATE ->
                "tree=" + value(model.tree().selected()) + " bar=" + model.log().offset() + "/"
                        + Math.max(0, model.log().entries().size() - LOG_ROWS) + " menu="
                        + value(model.menu().highlighted())
                        + " palette="
                        + (model.palette().query().isEmpty()
                                ? "-"
                                : model.palette().query()) + " split="
                        + model.splitFirst();
            case MESSAGE ->
                "notices="
                        + index(model.notices().notifications(), model.notices().selected()) + "/"
                        + model.notices().notifications().size() + " unread=" + unread(model.notices()) + " log="
                        + value(model.log().selected()) + " at=" + model.log().offset() + " lines="
                        + model.log().entries().size() + " toast="
                        + (model.toast().showing() ? "shown" : "hidden")
                        + " dialog=" + (model.dialogOpen() ? "open" : "closed");
            case DATA ->
                "line=" + (model.line().selected() + 1) + "/" + model.line().size() + " bars="
                        + value(model.bars().selected()) + " spark="
                        + (model.spark().selected() + 1) + "/"
                        + model.spark().values().size() + " gauge="
                        + String.format(Locale.ROOT, "%.2f", model.gauge().value()) + " date="
                        + (model.date().selected() == null ? "-" : model.date().selected());
        };
    }

    private static String joined(List<String> values) {
        return values == null || values.isEmpty() ? "-" : String.join(",", values);
    }

    private static String value(String text) {
        return text == null || text.isBlank() ? "-" : text;
    }

    private static int index(List<?> values, String id) {
        for (int at = 0; at < values.size(); at++) {
            if (values.get(at) instanceof NotificationCenter.Notification notice
                    && notice.id().equals(id)) {
                return at + 1;
            }
        }
        return 0;
    }

    private static long unread(NotificationCenter.Model model) {
        return model.notifications().stream().filter(notice -> !notice.read()).count();
    }
}

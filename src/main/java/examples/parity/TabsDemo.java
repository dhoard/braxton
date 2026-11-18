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
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Countdown;
import io.github.dhoard.braxton.widget.Editor;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Paginator;
import io.github.dhoard.braxton.widget.RichList;
import io.github.dhoard.braxton.widget.Tabs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 16 parity demo: one tab bar over three live pages.
 *
 * <p>The scene owns four mounted children - the bar and one page each for an editor, a list and a
 * timer - and paints the bar over the page its active tab selects. Every page is mounted and
 * declared for the whole session, so switching tabs changes what is painted and nothing else: the
 * draft, the list selection and the running countdown are still there when the user comes back.
 * That is the composition {@link Tabs} is built for, and the reason the widget owns no page of its
 * own.</p>
 *
 * <p>Focus is one fact the scene owns: the keyboard is in the bar or in the page. The bar declares
 * its own focus element and key map, so it receives a gesture only while it holds focus; the page
 * declares its own, so typing reaches the editor and the gestures of a list reach the list. The
 * scene's own bindings are bubble bindings: a focused page sees every key first, which is what keeps
 * a completed field, a typed character and a list gesture where they belong.</p>
 *
 * <ul>
 *   <li>{@code tab} and {@code shift+tab} move focus between the bar and the page. They are not part
 *       of the bar's key map, so a field that completes on {@code tab} still completes.</li>
 *   <li>{@code alt+1}, {@code alt+2} and {@code alt+3} show a page directly. Focus follows: the
 *       page that has just become visible is the one that holds the keyboard.</li>
 *   <li>{@code left}, {@code right}, {@code home} and {@code end} navigate the bar while it holds
 *       focus, skipping the disabled {@code Archive} tab and stopping at either end.</li>
 *   <li>{@code space} and {@code r} drive the countdown, and only while the timer page holds the
 *       keyboard; on the other pages those keys belong to the page.</li>
 *   <li>{@code ?} toggles full help and {@code ctrl+c} quits. {@code ?} reaches the scene only where
 *       the focused page does not consume it, so it types a character in the editor.</li>
 * </ul>
 *
 * <p>Two status rows above the bar report the scene without decoding styling, and two help rows
 * below the frame report the actions that actually fire: the focused region's, then the scene's.
 * The page row changes with the page, so help always describes what the visible page does.</p>
 */
public final class TabsDemo {

    /** The editor page's tab id. */
    public static final String NOTES_PAGE = "notes";

    /** The list page's tab id. */
    public static final String INBOX_PAGE = "inbox";

    /** The timer page's tab id. */
    public static final String TIMER_PAGE = "timer";

    /** The tab id of the disabled page, which the keyboard can never reach. */
    public static final String ARCHIVE_PAGE = "archive";

    /** The tabs, in paint order: three reachable pages and one disabled one. */
    public static final List<Tabs.Tab> TABS = List.of(
            Tabs.Tab.of(NOTES_PAGE, "Notes"),
            Tabs.Tab.of(INBOX_PAGE, "Inbox"),
            Tabs.Tab.of(TIMER_PAGE, "Timer"),
            Tabs.Tab.of(ARCHIVE_PAGE, "Archive", false));

    /** The id of the page the demo starts on. */
    public static final String START_PAGE = NOTES_PAGE;

    /** The draft the editor page starts with: several wrapped rows, so a resize is visible. */
    public static final String DRAFT = "A tab bar is a claim about identity: the page you were "
            + "looking at is still there when you come back. Keep typing here, walk to another tab, "
            + "and the draft, the caret and the wrap of this page survive the trip.";

    /** The countdown the timer page runs. */
    public static final Duration TIMER_DURATION = Duration.ofMinutes(2);

    /** The rows the scene paints above the bar. */
    public static final int STATUS_ROWS = 2;

    /** The rows the scene paints below the frame: the region's help and the scene's help. */
    public static final int HELP_ROWS = 2;

    /** The rows the bar's own cells occupy. */
    public static final int BAR_ROWS = 2;

    /** The rows the frame spends on its top and bottom edges. */
    public static final int FRAME_ROWS = 2;

    /**
     * The rows one mounted page is asked to leave to the rest of the scene.
     *
     * <p>A list sizes itself from the terminal it starts in minus what the application paints around
     * it, so the scene has to say how many rows are not the page's: the status rows, the bar, the
     * frame's two edges and the two help rows.</p>
     */
    public static final int RESERVED_ROWS = STATUS_ROWS + BAR_ROWS + FRAME_ROWS + HELP_ROWS;

    /** The page rows a mounted page falls back to when it is initialized without a terminal. */
    public static final int PAGE_ROWS = 24 - RESERVED_ROWS;

    private static final Style STATUS = Style.NONE.withBold();

    private static final List<String> TITLES = List.of(
            "Paper lantern",
            "Sourdough starter",
            "Wool socks",
            "Mending kit",
            "Green tea",
            "Field notes",
            "Postcards",
            "Beeswax wraps",
            "Mechanical pencil",
            "Loose leaf tea",
            "Bicycle bell",
            "Seed packets");

    private static final List<String> DESCRIPTIONS = List.of(
            "Hangs above the market stall",
            "Named after a friend",
            "Thicker than they look",
            "Needle, thread and scissors",
            "The leaves open slowly",
            "Waterproof cover",
            "From the coast road",
            "Washable, mostly",
            "An old favourite",
            "Smells of jasmine",
            "Brass, and loud",
            "Sow them in spring");

    /** The list page's items: a small fixture, so nothing is read or fetched. */
    public static final List<RichList.Item> ITEMS = items();

    private TabsDemo() {}

    /**
     * Where the keyboard is.
     *
     * <p>One fact, not one flag per child: the models of the bar and of the visible page are derived
     * from it, so two elements can never both claim focus and a page that becomes visible always has
     * a valid focus target.</p>
     */
    public enum Pane {

        /** The bar holds the keyboard and its own gestures navigate between tabs. */
        BAR,

        /** The visible page holds the keyboard. */
        PAGE
    }

    /**
     * The scene state.
     *
     * <p>The focus of the bar and of the pages is derived from {@code pane} and the active tab rather
     * than stored beside them, so every change to either one leaves exactly one element focused.</p>
     *
     * @param bar the mounted bar's own state
     * @param notes the mounted editor page's state
     * @param inbox the mounted list page's state
     * @param timer the mounted countdown's state
     * @param pane where the keyboard is
     * @param expandedHelp whether help lists every group
     * @param switched how many times the shown page changed
     * @param last what the scene last did
     */
    public record Model(
            Tabs.Model bar,
            Editor.Model notes,
            RichList.Model inbox,
            Countdown.Model timer,
            Pane pane,
            boolean expandedHelp,
            int switched,
            String last) {

        /** Substitutes an initialized default for a missing value and derives every focus flag. */
        public Model {
            pane = pane == null ? Pane.PAGE : pane;
            last = last == null ? "none" : last;
            bar = (bar == null ? new Tabs.Model(TABS, START_PAGE, true) : bar).withFocus(pane == Pane.BAR);
            String active = bar.activeId();
            notes = (notes == null ? Editor.Model.of(DRAFT) : notes)
                    .withFocus(pane == Pane.PAGE && NOTES_PAGE.equals(active));
            inbox = (inbox == null ? RichList.Model.of(ITEMS, PAGE_ROWS) : inbox)
                    .withFocus(pane == Pane.PAGE && INBOX_PAGE.equals(active));
            timer = timer == null ? Countdown.Model.stopped(TIMER_DURATION) : timer;
            switched = Math.max(0, switched);
        }

        /**
         * The shown page's id.
         *
         * @return the id, or null when no tab is enabled
         */
        public String activePage() {
            return bar.activeId();
        }

        /**
         * Whether the keyboard is in the bar.
         *
         * @return whether the bar holds focus
         */
        public boolean barFocused() {
            return pane == Pane.BAR;
        }

        /**
         * Whether the visible page holds the keyboard.
         *
         * @return whether the page holds focus
         */
        public boolean pageFocused() {
            return pane == Pane.PAGE;
        }

        /**
         * The time the timer page has left, formatted for the status row.
         *
         * @return the remaining time as {@code mm:ss}
         */
        public String remaining() {
            return Countdown.clock(timer.remaining());
        }

        Model withBar(Tabs.Model value) {
            return new Model(value, notes, inbox, timer, pane, expandedHelp, switched, last);
        }

        Model withNotes(Editor.Model value) {
            return new Model(bar, value, inbox, timer, pane, expandedHelp, switched, last);
        }

        Model withInbox(RichList.Model value) {
            return new Model(bar, notes, value, timer, pane, expandedHelp, switched, last);
        }

        Model withTimer(Countdown.Model value) {
            return new Model(bar, notes, inbox, value, pane, expandedHelp, switched, last);
        }

        Model withPane(Pane value) {
            return new Model(bar, notes, inbox, timer, value, expandedHelp, switched, last);
        }

        Model withExpandedHelp(boolean value) {
            return new Model(bar, notes, inbox, timer, pane, value, switched, last);
        }

        Model withSwitched(int value) {
            return new Model(bar, notes, inbox, timer, pane, expandedHelp, value, last);
        }

        Model withLast(String value) {
            return new Model(bar, notes, inbox, timer, pane, expandedHelp, switched, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A message from the mounted bar.
         *
         * @param value the bar's message
         */
        record Bar(Tabs.Msg value) implements Msg {}

        /**
         * An output the mounted bar routed to the scene.
         *
         * @param value the bar's output
         */
        record BarOutput(Tabs.Output value) implements Msg {}

        /**
         * A message from the mounted editor.
         *
         * @param value the editor's message
         */
        record Notes(Editor.Msg value) implements Msg {}

        /**
         * An output the mounted editor routed to the scene.
         *
         * @param value the editor's output
         */
        record NotesOutput(Editor.Output value) implements Msg {}

        /**
         * A message from the mounted list.
         *
         * @param value the list's message
         */
        record Inbox(RichList.Msg value) implements Msg {}

        /**
         * An output the mounted list routed to the scene.
         *
         * @param value the list's output
         */
        record InboxOutput(RichList.Output value) implements Msg {}

        /**
         * A message from the mounted countdown: a gesture the scene sent it, or one runtime reading.
         *
         * @param value the countdown's message
         */
        record Timer(Countdown.Msg value) implements Msg {}

        /**
         * An output the mounted countdown reported.
         *
         * @param value the countdown's output
         */
        record TimedOut(Countdown.Output value) implements Msg {}

        /** Moves the keyboard between the bar and the visible page. */
        record TogglePane() implements Msg {}

        /**
         * Shows a page by tab id, keeping the keyboard where it is.
         *
         * @param id the tab id to show
         */
        record Show(String id) implements Msg {}

        /** Starts, pauses or resumes the countdown, whichever its state allows. */
        record ToggleTimer() implements Msg {}

        /** Puts the countdown back at its configured duration, stopped. */
        record ResetTimer() implements Msg {}

        /** Toggles full help. */
        record ToggleHelp() implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /** Runs the demo against the owned terminal. */
    public static ExitResult run() {
        return Application.run(new Scene());
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
     * The bar definition the demo paints.
     *
     * <p>Wrap-around is off, which is the widget's default: the first and last enabled tab are the
     * ends of the bar, so a gesture that cannot move changes nothing rather than jumping to the
     * other end.</p>
     *
     * @return the bar definition
     */
    public static Tabs.Config barConfig() {
        return Tabs.Config.defaults();
    }

    /**
     * The editor page's editing policy.
     *
     * <p>The wrap width is not declared: the editor lays its rows out for the cells it is painted in,
     * so a resize re-wraps the draft without the scene re-projecting anything.</p>
     *
     * @return the policy
     */
    public static Editor.Config editorConfig() {
        return Editor.Config.defaults();
    }

    /**
     * The list page's definition.
     *
     * <p>The page is a complete list: its title, its status row and its page indicator are its own.
     * Its own help row is the one thing the scene turns off when it mounts the page, because the
     * scene paints the visible page's actions for every page; {@code h} brings the list's row back.</p>
     *
     * @return the definition
     */
    public static RichList.Config listConfig() {
        return RichList.Config.defaults()
                .withItems(ITEMS)
                .withTitle("Inbox")
                .withMode(RichList.FilterMode.FUZZY)
                .withPagination(Paginator.Appearance.dots())
                .withRows(PAGE_ROWS)
                .withReservedRows(RESERVED_ROWS);
    }

    /**
     * The countdown's definition.
     *
     * @return the definition
     */
    public static Countdown.Config timerConfig() {
        return Countdown.Config.of(TIMER_DURATION);
    }

    /**
     * The timer page's own actions.
     *
     * <p>The scene routes this map only while the timer page holds the keyboard, and the page's help
     * row is rendered from the same map, so a gesture that is painted is a gesture that fires.</p>
     *
     * @return the map
     */
    public static KeyMap<Msg> timerKeys() {
        return new KeyMap<>(List.of(KeyMap.group(
                "Timer",
                KeyAction.<Msg>of("toggle", new Msg.ToggleTimer(), "space", "start/pause", "space"),
                KeyAction.<Msg>of("reset", new Msg.ResetTimer(), "r", "reset", "r"))));
    }

    /**
     * The scene's own actions: the ones that are not the bar's and not the visible page's.
     *
     * <p>They are bubble bindings, so the focused bar or page consumes a gesture first. The timer's
     * actions join the map only while the timer page holds the keyboard, which is what keeps
     * {@code space} a typed space in the editor and a start/pause on the timer.</p>
     *
     * @param model the scene state
     * @return the map, in help order
     */
    public static KeyMap<Msg> keys(Model model) {
        List<KeyMap.Group<Msg>> groups = new ArrayList<>(4);
        groups.add(KeyMap.group(
                "Focus",
                KeyAction.<Msg>of("pane", new Msg.TogglePane(), "tab", "focus bar/page", "tab"),
                KeyAction.<Msg>of("pane-back", new Msg.TogglePane(), "shift+tab", "focus bar/page", "shift+tab")));
        groups.add(KeyMap.group(
                "Pages",
                KeyAction.<Msg>of("notes", new Msg.Show(NOTES_PAGE), "alt+1", "notes", "alt+1"),
                KeyAction.<Msg>of("inbox", new Msg.Show(INBOX_PAGE), "alt+2", "inbox", "alt+2"),
                KeyAction.<Msg>of("timer", new Msg.Show(TIMER_PAGE), "alt+3", "timer", "alt+3")));
        if (timerFocused(model)) {
            groups.addAll(timerKeys().groups());
        }
        groups.add(KeyMap.group(
                "Scene",
                KeyAction.<Msg>of("help", new Msg.ToggleHelp(), "?", "full help", "?"),
                KeyAction.<Msg>of("quit", new Msg.Quit(), "ctrl+c", "quit", "ctrl+c")));
        return new KeyMap<>(groups);
    }

    /**
     * The actions of the region that holds the keyboard: the bar's, or the visible page's.
     *
     * <p>Both maps are the ones the region routes, so the help row cannot describe a gesture that
     * does not fire. Each page's map is its own widget's - the editor's, the list's or the timer's -
     * because a page that is mounted is a page that answers keys.</p>
     *
     * @param model the scene state
     * @return the map whose compact help is painted
     */
    public static KeyMap<?> regionKeys(Model model) {
        if (model.barFocused()) {
            return Tabs.defaultKeys();
        }
        return switch (model.activePage() == null ? "" : model.activePage()) {
            case NOTES_PAGE -> Editor.defaultKeys(editorConfig());
            case INBOX_PAGE -> RichList.keys(model.inbox());
            case TIMER_PAGE -> timerKeys();
            default -> new KeyMap<>(List.of());
        };
    }

    /**
     * The help row for the region that holds the keyboard.
     *
     * @param model the scene state
     * @param cols the columns the row may paint
     * @return the rendered row, or rows when help is expanded
     */
    public static StyledText regionHelp(Model model, int cols) {
        return Help.render(regionKeys(model), model.expandedHelp(), cols);
    }

    /**
     * The help row for the scene's own actions.
     *
     * @param model the scene state
     * @param cols the columns the row may paint
     * @return the rendered row, or rows when help is expanded
     */
    public static StyledText sceneHelp(Model model, int cols) {
        return Help.render(keys(model), model.expandedHelp(), cols);
    }

    /**
     * Every help row, in paint order: the region's, then the scene's.
     *
     * <p>Compact help is one row each, which is the two rows {@link #HELP_ROWS} reserves. Expanded
     * help is one row a group, so the frame above it gives up the rows instead of the last group
     * being cut away.</p>
     *
     * @param model the scene state
     * @param cols the columns a row may paint
     * @return the rows
     */
    public static List<StyledText> helpLines(Model model, int cols) {
        List<StyledText> lines = new ArrayList<>(regionHelp(model, cols).lines());
        lines.addAll(sceneHelp(model, cols).lines());
        return List.copyOf(lines);
    }

    /**
     * The one-row report of the scene: the shown page, where the keyboard is, and what last moved.
     *
     * @param model the scene state
     * @return the row
     */
    public static String status(Model model) {
        return "tab=" + (model.activePage() == null ? "none" : model.activePage())
                + " focus=" + model.pane().name().toLowerCase(Locale.ROOT)
                + " pages=" + model.bar().enabledCount()
                + " switched=" + model.switched()
                + " last=" + model.last();
    }

    /**
     * The one-row report of every page: the state each mounted page kept while it was hidden.
     *
     * @param model the scene state
     * @return the row
     */
    public static String pages(Model model) {
        return "notes=" + model.notes().text().length()
                + " inbox="
                + (model.inbox().selectedId().isEmpty() ? "none" : model.inbox().selectedId())
                + " timer=" + model.remaining()
                + " run=" + (model.timer().running() ? "on" : "off")
                + " help=" + (model.expandedHelp() ? "full" : "compact");
    }

    /** The fixture: twelve items, so the list pages and pages through them. */
    private static List<RichList.Item> items() {
        List<RichList.Item> items = new ArrayList<>(TITLES.size());
        for (int index = 0; index < TITLES.size(); index++) {
            String title = TITLES.get(index);
            items.add(new RichList.Item(
                    String.format(Locale.ROOT, "item-%02d", index + 1), title, DESCRIPTIONS.get(index), title));
        }
        return List.copyOf(items);
    }

    private static boolean timerFocused(Model model) {
        return model.pageFocused() && TIMER_PAGE.equals(model.activePage());
    }

    /** What the editor asked for, as one token a verifier can assert. */
    private static String output(Editor.Output output) {
        return switch (output) {
            case Editor.Output.Submit submit -> "submit:" + submit.text().length();
            case Editor.Output.Copy copy -> "copy:" + copy.text().length();
            case Editor.Output.PasteRequest request -> "paste";
        };
    }

    /** What the list asked for, as one token. */
    private static String output(RichList.Output output) {
        return switch (output) {
            case RichList.Output.Chosen chosen -> "chose:" + chosen.item().id();
            case RichList.Output.Cancelled cancelled -> "cancelled";
        };
    }

    /** The scene: four mounted children, two status rows, the composition and two help rows. */
    public static final class Scene implements Component<Model, Msg, Never> {

        private final Tabs bar = new Tabs(barConfig());
        private final Editor notes = Editor.textarea(editorConfig());
        private final RichList inbox = new RichList(listConfig());
        private final Countdown timer = new Countdown(timerConfig());
        private final Mount<Model, Msg, Never, Tabs.Model, Tabs.Msg, Tabs.Output> barMount = Mount.of(
                ComponentId.of("bar"),
                bar,
                ModelLens.of(Model::bar, Model::withBar),
                Msg.Bar::new,
                OutputRoute.toMessage(Msg.BarOutput::new));
        private final Mount<Model, Msg, Never, Editor.Model, Editor.Msg, Editor.Output> notesMount = Mount.of(
                ComponentId.of(NOTES_PAGE),
                notes,
                ModelLens.of(Model::notes, Model::withNotes),
                Msg.Notes::new,
                OutputRoute.toMessage(Msg.NotesOutput::new));
        private final Mount<Model, Msg, Never, RichList.Model, RichList.Msg, RichList.Output> inboxMount = Mount.of(
                ComponentId.of(INBOX_PAGE),
                inbox,
                ModelLens.of(Model::inbox, Model::withInbox),
                Msg.Inbox::new,
                OutputRoute.toMessage(Msg.InboxOutput::new));
        private final Mount<Model, Msg, Never, Countdown.Model, Countdown.Msg, Countdown.Output> timerMount = Mount.of(
                ComponentId.of(TIMER_PAGE),
                timer,
                ModelLens.of(Model::timer, Model::withTimer),
                Msg.Timer::new,
                OutputRoute.toMessage(Msg.TimedOut::new));

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            // Every page is mounted at its own start state; the demo's own fixture - the draft and
            // the tab list - is then published into the state the mounts initialized.
            Model model = new Model(
                    barMount.initialize(context).model().withTabs(TABS),
                    notesMount.initialize(context).model().withText(DRAFT),
                    // The page row below the frame lists the visible page's actions, so the list's
                    // own help row is off: the demo paints that help once, for every page. `h`
                    // still brings the list's own row back.
                    inboxMount
                            .initialize(context)
                            .model()
                            .withChrome(RichList.Chrome.defaults().withHelp(false)),
                    timerMount.initialize(context).model(),
                    Pane.PAGE,
                    false,
                    0,
                    "none");
            return Update.next(model);
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Bar value -> barMount.update(model, value.value());
                case Msg.BarOutput value -> shown(model, value.value());
                case Msg.Notes value -> notesMount.update(model, value.value());
                case Msg.NotesOutput value -> Update.next(model.withLast("notes-" + output(value.value())));
                case Msg.Inbox value -> inboxMount.update(model, value.value());
                case Msg.InboxOutput value -> Update.next(model.withLast("inbox-" + output(value.value())));
                case Msg.Timer value -> timerMount.update(model, value.value());
                case Msg.TimedOut value -> Update.next(model.withLast("timer-timeout"));
                case Msg.TogglePane toggle ->
                    Update.next(model.withPane(model.barFocused() ? Pane.PAGE : Pane.BAR)
                            .withLast("focus"));
                case Msg.Show show -> barMount.update(model, new Tabs.Msg.Activate(show.id()));
                case Msg.ToggleTimer toggle -> gesture(model, new Countdown.Msg.Toggle(), "timer-toggle");
                case Msg.ResetTimer reset -> gesture(model, new Countdown.Msg.Reset(), "timer-reset");
                case Msg.ToggleHelp help ->
                    Update.next(model.withExpandedHelp(!model.expandedHelp()).withLast("help"));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /** Every mounted child declares its sources, including the pages that are not painted. */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.batch(
                    barMount.subscriptions(model),
                    notesMount.subscriptions(model),
                    inboxMount.subscriptions(model),
                    timerMount.subscriptions(model));
        }

        /**
         * Every mounted child, painted or not.
         *
         * <p>This is the keep-alive policy: the node is declared for the whole session, so a page
         * keeps its model, its scoped element ids and its subscriptions while another page is shown.
         * Omitting the node of a page would be the deliberate unmount - that page's scoped work would
         * be cancelled and its state dropped.</p>
         */
        @Override
        public ComponentTree children(Model model) {
            return ComponentTree.of(
                    barMount.tree(model), notesMount.tree(model), inboxMount.tree(model), timerMount.tree(model));
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int cols = Math.max(1, context.viewport().cols());
            // Only the shown page is painted; every page stays mounted and subscribed.
            View<Msg> page = switch (model.activePage() == null ? "" : model.activePage()) {
                case NOTES_PAGE -> notesMount.view(model, context);
                case INBOX_PAGE -> inboxMount.view(model, context);
                case TIMER_PAGE ->
                    Views.column(Views.fixed(1, timerMount.view(model, context)), Views.grow(Views.blank()));
                default -> Views.blank();
            };
            List<View<Msg>> rows = new ArrayList<>(STATUS_ROWS + 1 + HELP_ROWS);
            rows.add(Views.fixed(1, Views.text(status(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(pages(model), STATUS)));
            rows.add(Views.grow(bar.view(model.bar(), page, Msg.Bar::new)));
            for (StyledText line : helpLines(model, cols)) {
                rows.add(Views.fixed(1, Views.text(line)));
            }
            return Views.onKeys(keys(model).bindings(), Views.column(rows));
        }

        /** The countdown's own gesture, dispatched through its mount so its command is the mount's. */
        private Update<Model, Msg, Never> gesture(Model model, Countdown.Msg message, String last) {
            Update<Model, Msg, Never> applied = timerMount.update(model, message);
            return Update.next(applied.model().withLast(last), applied.command());
        }

        /** What the bar reported: a changed page, which leaves the keyboard where it was. */
        private Update<Model, Msg, Never> shown(Model model, Tabs.Output output) {
            return switch (output) {
                case Tabs.Output.Activated activated ->
                    Update.next(model.withSwitched(model.switched() + 1).withLast("tab-" + activated.id()));
            };
        }
    }
}

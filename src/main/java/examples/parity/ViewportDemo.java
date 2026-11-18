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
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.OutputRoute;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.TextInput;
import io.github.dhoard.braxton.widget.TextViewport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 12 parity demo: a searchable pager over an original field-guide document.
 *
 * <p>Two {@link TextViewport}s are mounted over the same scene: the pager itself, which wraps or
 * pans the document and shows the ranges the search found, and an outline pane over the document's
 * section headings, which scrolls on its own anchor. That split is the composition the widget is
 * built for - the scene owns the document, the search and which pane has focus, and each pane owns
 * one logical anchor, its own geometry and its own highlights.</p>
 *
 * <p>The document is written for this demo. It is long enough to page, it holds lines wider than the
 * terminal so panning has something to pan, it holds wide glyphs so the column mapping is visible,
 * and it repeats the word {@code artichoke} so the search has matches to walk.</p>
 *
 * <p>The scene is the only owner of geometry: one resize source tells both panes the rows and
 * columns this layout allocates them, which is the contract a composed pager needs when the panes
 * are not the whole screen.</p>
 *
 * <p>It is also the only owner of the projection lifecycle. A content version is published once, the
 * ranges the query matches in that version are published after it, and an active range is chosen
 * after those; that order is why a keystroke republishes content and ranges together while an
 * appended note republishes ranges without selecting one.</p>
 */
public final class ViewportDemo {

    /** The rows the scene paints above the pager: two status rows and the find field. */
    public static final int HEADER_ROWS = 3;

    /** The rows the scene paints between the pager and the outline: one status row. */
    public static final int OUTLINE_HEADER_ROWS = 1;

    /** The rows the scene paints below both panes: one help line per key map. */
    public static final int HELP_ROWS = 2;

    /** The tallest the outline pane may become. */
    public static final int OUTLINE_MAX_ROWS = 4;

    /** The cells the line-number gutter occupies, separator included. */
    public static final int GUTTER_COLUMNS = 6;

    /** The rows the scene keeps for its two help lines and the outline before the pager. */
    private static final int FIXED_ROWS = HEADER_ROWS + OUTLINE_HEADER_ROWS + HELP_ROWS;

    private static final Style STATUS = Style.NONE.withBold();

    private static final Style HEADING = Style.NONE.withBold();

    private static final Style QUOTE = Style.NONE.withItalic();

    private static final Style LINK = Style.NONE.withUnderline();

    private static final Style GUTTER = Style.NONE.withItalic();

    /** The word the demo's search finds, and the fixture repeats. */
    public static final String KEYWORD = "artichoke";

    /** The document's source: one entry a logical line, styled by its prefix. */
    private static final String[] SOURCE = {
        "# A pocket guide to the artichoke",
        "",
        "## What it is",
        "The artichoke is the bud of a thistle, harvested before it opens. What",
        "looks like a vegetable is a flower that never happened, and what looks",
        "like a choke is the flower it was going to be.",
        "",
        "## A short history",
        "The artichoke grows wild around the Mediterranean, where it has been",
        "eaten, argued over and drawn for a very long time. It reached northern",
        "Europe as a curiosity and stayed as a delicacy.",
        "",
        "> An artichoke is a lesson in patience: the good part is behind the",
        "> thorns, and it is smaller than you hoped.",
        "",
        "## In the kitchen",
        "1. Trim the stem, then the tips, and rub the cut faces with lemon.",
        "2. Steam it standing up, so the water never touches the choke.",
        "3. Pull the leaves, dip them, and work inwards; the heart is the reward.",
        "4. Keep the cooking water. It is a stock nobody expects.",
        "",
        "## One long line",
        "A single row that is deliberately wider than a terminal so the pager has"
                + " something to pan: the artichoke, the cardoon, the wild thistle, the field, the"
                + " market, the kitchen, the salt, the lemon, the oil, the plate, the company, the"
                + " conversation, the second helping and the artichoke again.",
        "",
        "## Names around the world",
        "artichoke \u671d\u9c9c\u84df alcachofa carciofo \u03b1\u03b3\u03ba\u03b9\u03bd\u03ac\u03c1\u03b1",
        "",
        "## Growing them",
        "They want sun, drainage and room. Give them a metre and they will give",
        "you a season of buds; crowd them and they will give you a season of",
        "disappointment. Cut them back in autumn and they will come again, which",
        "is most of what a gardener asks for.",
        "",
        "## The glossary",
        "Choke, heart, bract, cardoon - see the [glossary] for the words this page",
        "uses.",
        "",
        "## A note on patience",
        "An artichoke takes its time, and a field guide should too.",
    };

    /** The document: logical lines, with headings, a quotation, a link and wide glyphs. */
    public static final List<StyledText> LINES = document();

    /** The outline's lines: one entry a section, numbered by the line it starts on. */
    public static final List<StyledText> OUTLINE = outline();

    /** Which mounted component owns focus. */
    public enum Focus {

        /** The pager: its own gestures scroll it. */
        MAIN,

        /** The outline pane: its own gestures scroll it, independently of the pager. */
        OUTLINE,

        /** The find field: printable keys are a query and the panes scroll nothing. */
        FIELD
    }

    private ViewportDemo() {}

    /**
     * The scene state.
     *
     * @param lines the document's logical lines, which the scene owns
     * @param main the pager's own state
     * @param outline the outline pane's own state
     * @param search the find field's own state
     * @param mode how the pager treats a line wider than the content column
     * @param numbers whether the panes paint line numbers
     * @param focus which mounted component owns focus
     * @param added how many notes the scene has appended
     * @param last what the scene last did
     */
    public record Model(
            List<StyledText> lines,
            TextViewport.Model main,
            TextViewport.Model outline,
            TextInput.Model search,
            TextViewport.Mode mode,
            boolean numbers,
            Focus focus,
            int added,
            String last) {

        /** Substitutes the defaults for a missing value. */
        public Model {
            lines = lines == null ? LINES : List.copyOf(lines);
            main = main == null ? TextViewport.Model.of(TextViewport.Content.empty()) : main;
            outline = outline == null ? TextViewport.Model.of(TextViewport.Content.empty()) : outline;
            search = search == null ? TextInput.Model.empty() : search;
            mode = mode == null ? TextViewport.Mode.WRAP : mode;
            focus = focus == null ? Focus.MAIN : focus;
            last = last == null ? "none" : last;
        }

        Model withMain(TextViewport.Model value) {
            return new Model(lines, value, outline, search, mode, numbers, focus, added, last);
        }

        Model withOutline(TextViewport.Model value) {
            return new Model(lines, main, value, search, mode, numbers, focus, added, last);
        }

        Model withSearch(TextInput.Model value) {
            return new Model(lines, main, outline, value, mode, numbers, focus, added, last);
        }

        Model withLines(List<StyledText> value) {
            return new Model(value, main, outline, search, mode, numbers, focus, added, last);
        }

        Model withMode(TextViewport.Mode value) {
            return new Model(lines, main, outline, search, value, numbers, focus, added, last);
        }

        Model withNumbers(boolean value) {
            return new Model(lines, main, outline, search, mode, value, focus, added, last);
        }

        Model withFocus(Focus value) {
            return new Model(
                    lines,
                    main.withFocus(value == Focus.MAIN),
                    outline.withFocus(value == Focus.OUTLINE),
                    search.withFocus(value == Focus.FIELD),
                    mode,
                    numbers,
                    value,
                    added,
                    last);
        }

        Model withAdded(int value) {
            return new Model(lines, main, outline, search, mode, numbers, focus, value, last);
        }

        Model withLast(String value) {
            return new Model(lines, main, outline, search, mode, numbers, focus, added, value);
        }
    }

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A message from one of the mounted panes.
         *
         * @param pane 0 for the pager, 1 for the outline
         * @param value the pane's message
         */
        record Pane(int pane, TextViewport.Msg value) implements Msg {}

        /**
         * A message from the mounted find field.
         *
         * @param value the field's message
         */
        record Field(TextInput.Msg value) implements Msg {}

        /**
         * An output the mounted find field routed to the scene.
         *
         * @param value the field's output
         */
        record FieldOutput(TextInput.Output value) implements Msg {}

        /**
         * Reports the terminal's geometry, which the scene splits between its components.
         *
         * @param rows the terminal's rows
         * @param columns the terminal's columns
         */
        record Resized(int rows, int columns) implements Msg {}

        /** Replaces the pager's mode with the other one. */
        record ToggleMode() implements Msg {}

        /** Shows or hides the panes' line numbers. */
        record ToggleNumbers() implements Msg {}

        /** Appends one note to the end of the document. */
        record Append() implements Msg {}

        /** Moves focus to the other pane. */
        record NextPane() implements Msg {}

        /** Moves focus to the find field. */
        record Search() implements Msg {}

        /** Moves focus to the pager. */
        record Find() implements Msg {}

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions: the ones that change the document or what is painted around it.
     *
     * <p>They are bubble bindings, so a focused component sees every key first: the gestures a pane
     * owns never reach this map, and a focused field turns printable keys into a query.</p>
     *
     * @return the scene's actions, in help order
     */
    public static KeyMap<Msg> keys() {
        return KeyMap.of(
                KeyMap.group(
                        "Search",
                        KeyAction.<Msg>of("find", new Msg.Search(), "/", "find", "/"),
                        KeyAction.<Msg>of("back", new Msg.Find(), "esc", "back to text", "escape")),
                KeyMap.group(
                        "View",
                        KeyAction.<Msg>of("mode", new Msg.ToggleMode(), "w", "wrap/pan", "w"),
                        KeyAction.<Msg>of("numbers", new Msg.ToggleNumbers(), "g", "line numbers", "g"),
                        KeyAction.<Msg>of("append", new Msg.Append(), "a", "append a note", "a")),
                KeyMap.group(
                        "Scene",
                        KeyAction.<Msg>of("pane", new Msg.NextPane(), "tab", "next pane", "tab"),
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "ctrl+c", "quit", "ctrl+c")));
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

    // ── definitions ──────────────────────────────────────────────────────────

    /**
     * The pager's definition for one scene state.
     *
     * @param model the scene state
     * @param rows the rows this scene allocates the pager
     * @param columns the columns this scene allocates the pager
     * @return the pager's definition
     */
    public static TextViewport.Config mainConfig(Model model, int rows, int columns) {
        return TextViewport.Config.defaults()
                .withMode(model.mode())
                .withGutter(model.numbers() ? gutter() : null)
                .withStyles(TextViewport.Config.Styles.defaults())
                .withRows(rows)
                .withColumns(columns);
    }

    /**
     * The outline pane's definition for one scene state: always panned, always numbered.
     *
     * @param model the scene state
     * @param rows the rows this scene allocates the outline
     * @param columns the columns this scene allocates the outline
     * @return the outline's definition
     */
    public static TextViewport.Config outlineConfig(Model model, int rows, int columns) {
        return TextViewport.Config.defaults()
                .withMode(TextViewport.Mode.PAN)
                .withGutter(model.numbers() ? gutter() : null)
                .withStyles(TextViewport.Config.Styles.defaults())
                .withRows(rows)
                .withColumns(columns)
                .withFollowEnd(false);
    }

    /**
     * The find field's definition.
     *
     * @return the field's definition
     */
    public static TextInput.Config searchConfig() {
        return TextInput.Config.defaults()
                .withPrompt("find: ", Style.NONE.withBold())
                .withCompletion(TextInput.Completion.disabled());
    }

    /** The line-number gutter both panes paint. */
    public static TextViewport.Gutter gutter() {
        return TextViewport.Gutter.lineNumbers(GUTTER_COLUMNS, GUTTER);
    }

    /**
     * The rows the scene allocates the pager at one terminal height.
     *
     * @param rows the terminal's rows
     * @return the pager's rows, never negative
     */
    public static int mainRows(int rows) {
        return Math.max(0, Math.max(0, rows) - FIXED_ROWS - outlineRows(rows));
    }

    /**
     * The rows the scene allocates the outline at one terminal height.
     *
     * @param rows the terminal's rows
     * @return the outline's rows, never negative
     */
    public static int outlineRows(int rows) {
        return Math.min(
                OUTLINE_MAX_ROWS, Math.max(0, Math.max(0, rows) - HEADER_ROWS - OUTLINE_HEADER_ROWS - HELP_ROWS - 2));
    }

    /**
     * The document's logical lines.
     *
     * @return the lines
     */
    public static TextViewport.Content content() {
        return TextViewport.Content.of(LINES);
    }

    /**
     * The outline's content.
     *
     * @return the outline's lines
     */
    public static TextViewport.Content outlineContent() {
        return TextViewport.Content.of(OUTLINE);
    }

    /**
     * The ranges a query matches in one document, in paint order.
     *
     * <p>The policy is the demo's own, and it is the one the scene publishes: every case-insensitive
     * occurrence of the query on a line, narrowed to nothing when the query is empty. Matching walks
     * code points with the simple lower-case mapping, so folding a character can never move an
     * offset inside the value the range is later applied to, and a range is widened to whole
     * clusters so a highlight never splits a grapheme.</p>
     *
     * @param lines the document to search
     * @param query the query; null or blank matches nothing
     * @return the ranges, in line then offset order
     */
    public static List<TextViewport.Highlight> matches(List<StyledText> lines, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || lines == null) {
            return List.of();
        }
        int[] pattern = needle.codePoints().toArray();
        List<TextViewport.Highlight> found = new ArrayList<>();
        for (int line = 0; line < lines.size(); line++) {
            String text = lines.get(line).plainText();
            int offset = 0;
            while (offset < text.length()) {
                int length = matchAt(text, offset, pattern);
                if (length > 0) {
                    int start = TextMeasure.boundaryBefore(text, offset);
                    int end = Math.min(text.length(), TextMeasure.boundaryAfter(text, offset + length - 1));
                    found.add(new TextViewport.Highlight(line, start, Math.max(start, end)));
                    offset += length;
                } else {
                    offset += Character.charCount(text.codePointAt(offset));
                }
            }
        }
        return List.copyOf(found);
    }

    /** The length in UTF-16 units of the pattern matched at one offset, or zero. */
    private static int matchAt(String text, int offset, int[] pattern) {
        int position = offset;
        for (int expected : pattern) {
            if (position >= text.length()) {
                return 0;
            }
            int code = text.codePointAt(position);
            if (Character.toLowerCase(code) != expected) {
                return 0;
            }
            position += Character.charCount(code);
        }
        return position - offset;
    }

    /** The document, built from source lines whose prefix decides their style. */
    private static List<StyledText> document() {
        List<StyledText> lines = new ArrayList<>(SOURCE.length);
        for (String line : SOURCE) {
            lines.add(styled(line));
        }
        return List.copyOf(lines);
    }

    /** One source line, styled by its prefix, with its own link target. */
    private static StyledText styled(String line) {
        if (line.startsWith("## ")) {
            return StyledText.of(line.substring(3), HEADING);
        }
        if (line.startsWith("# ")) {
            return StyledText.of(line.substring(2), HEADING);
        }
        if (line.startsWith("> ")) {
            return StyledText.of(line.substring(2), QUOTE);
        }
        int link = line.indexOf("[glossary]");
        if (link < 0) {
            return StyledText.plain(line);
        }
        return StyledText.concat(
                StyledText.plain(line.substring(0, link)),
                StyledText.of("[glossary]", LINK, "https://example.invalid/glossary"),
                StyledText.plain(line.substring(link + "[glossary]".length())));
    }

    /** The outline: every heading and quotation, numbered by the line it starts on. */
    private static List<StyledText> outline() {
        List<StyledText> entries = new ArrayList<>();
        for (int line = 0; line < SOURCE.length; line++) {
            String text = SOURCE[line];
            if (text.startsWith("# ") || text.startsWith("## ") || text.startsWith("> ")) {
                int marker = text.startsWith("## ") ? 3 : 2;
                entries.add(StyledText.plain(String.format(Locale.ROOT, "%3d  %s", line + 1, text.substring(marker))));
            }
        }
        return List.copyOf(entries);
    }

    // ── the scene ────────────────────────────────────────────────────────────

    /** The status rows, the find field, both panes and the two help lines. */
    public static final class Scene implements Component<Model, Msg, Never> {

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            int rows = context.viewport().rows() > 0 ? context.viewport().rows() : 24;
            int columns = context.viewport().cols() > 0 ? context.viewport().cols() : 80;
            Model empty = new Model(
                    LINES,
                    TextViewport.Model.of(content()),
                    TextViewport.Model.of(outlineContent()),
                    new TextInput(searchConfig()).validated(TextInput.Model.empty()),
                    TextViewport.Mode.WRAP,
                    true,
                    Focus.MAIN,
                    0,
                    "none");
            // Each pane is initialized through its own mount, so the definition the scene advertises
            // is the one its state was built from; nothing about the mounts changes afterwards.
            Model started = empty.withMain(
                            mainMount(empty, 0, 0).initialize(context).model())
                    .withOutline(outlineMount(empty, 0, 0).initialize(context).model())
                    .withFocus(Focus.MAIN);
            // The first publication is what gives both panes their document. The pager then moves to
            // its first line - a pager opens at the top, not at the end - which releases the
            // follow-end its definition starts with; End arms it again.
            Model filled = publishedOutline(geometry(started, rows, columns), OUTLINE);
            Model published = published(filled, filled.lines(), false);
            Update<Model, Msg, Never> opened = mainMount(published, 0, 0).update(published, new TextViewport.Msg.Top());
            return Update.next(opened.model().withLast("started"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Pane pane -> paneUpdate(model, pane);
                case Msg.Field field -> typed(model, field.value());
                case Msg.FieldOutput output -> Update.next(model.withLast(described(output.value())));
                case Msg.Resized resized ->
                    Update.next(
                            geometry(model, resized.rows(), resized.columns()).withLast("resized"));
                case Msg.ToggleMode toggle -> {
                    TextViewport.Mode mode =
                            model.mode() == TextViewport.Mode.WRAP ? TextViewport.Mode.PAN : TextViewport.Mode.WRAP;
                    yield Update.next(model.withMode(mode).withLast("mode-" + name(mode)));
                }
                case Msg.ToggleNumbers toggle ->
                    Update.next(model.withNumbers(!model.numbers()).withLast("numbers-" + onOff(!model.numbers())));
                case Msg.Append append -> appended(model);
                case Msg.NextPane next -> {
                    Focus focus = model.focus() == Focus.MAIN ? Focus.OUTLINE : Focus.MAIN;
                    yield Update.next(model.withFocus(focus).withLast("focus-" + name(focus)));
                }
                case Msg.Search search ->
                    Update.next(model.withFocus(Focus.FIELD).withLast("focus-field"));
                case Msg.Find find -> Update.next(model.withFocus(Focus.MAIN).withLast("focus-main"));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * One resize source for the whole scene.
         *
         * <p>The mounted components declare nothing: this layout is the only thing that knows how
         * the terminal's rows are split between the panes, so it is the only thing that can report
         * each pane's geometry.</p>
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.resize(SIZE, size -> new Msg.Resized(size.rows(), size.cols()));
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int rows = context.viewport().rows();
            int cols = context.viewport().cols();
            int mainRows = mainRows(rows);
            int outlineRows = outlineRows(rows);
            List<View<Msg>> sections = new ArrayList<>(8);
            sections.add(Views.fixed(1, Views.text(status(model), STATUS)));
            sections.add(Views.fixed(1, Views.text(found(model), STATUS)));
            sections.add(Views.fixed(1, fieldMount(model).view(model, context)));
            sections.add(Views.fixed(mainRows, mainMount(model, mainRows, cols).view(model, context)));
            sections.add(Views.fixed(1, Views.text(outlineStatus(model), STATUS)));
            sections.add(Views.fixed(
                    outlineRows, outlineMount(model, outlineRows, cols).view(model, context)));
            sections.add(Views.fixed(1, Views.text(Help.render(mainKeys(model), false, cols))));
            sections.add(Views.fixed(1, Views.text(Help.render(keys(), false, cols))));
            return Views.keyed(List.of(), Views.column(sections), keys().bindings());
        }

        /** The pager's own gestures, for the mode its definition is in. */
        private static KeyMap<TextViewport.Msg> mainKeys(Model model) {
            return TextViewport.keys(model.main(), mainConfig(model, 0, 0));
        }

        /**
         * One pane, mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the definition the scene's state asks for on every use.
         * Nothing else about the mount changes: its identity, its lens and its routes are the same
         * values every time.</p>
         */
        private Mount<Model, Msg, Never, TextViewport.Model, TextViewport.Msg, Never> mainMount(
                Model model, int rows, int columns) {
            return Mount.of(
                    ComponentId.of("main"),
                    new TextViewport(mainConfig(model, rows, columns)),
                    ModelLens.of(Model::main, (Model state, TextViewport.Model child) -> state.withMain(child)),
                    (TextViewport.Msg value) -> new Msg.Pane(0, value),
                    OutputRoute.toOutput(value -> value));
        }

        private Mount<Model, Msg, Never, TextViewport.Model, TextViewport.Msg, Never> outlineMount(
                Model model, int rows, int columns) {
            return Mount.of(
                    ComponentId.of("outline"),
                    new TextViewport(outlineConfig(model, rows, columns)),
                    ModelLens.of(Model::outline, (Model state, TextViewport.Model child) -> state.withOutline(child)),
                    (TextViewport.Msg value) -> new Msg.Pane(1, value),
                    OutputRoute.toOutput(value -> value));
        }

        private Mount<Model, Msg, Never, TextInput.Model, TextInput.Msg, TextInput.Output> fieldMount(Model model) {
            return Mount.of(
                    ComponentId.of("find"),
                    new TextInput(searchConfig()),
                    ModelLens.of(Model::search, (Model state, TextInput.Model child) -> state.withSearch(child)),
                    Msg.Field::new,
                    OutputRoute.toMessage(Msg.FieldOutput::new));
        }

        /** One pane message, applied through the mount that owns it. */
        private Update<Model, Msg, Never> paneUpdate(Model model, Msg.Pane message) {
            Mount<Model, Msg, Never, TextViewport.Model, TextViewport.Msg, Never> mount =
                    message.pane() == 0 ? mainMount(model, 0, 0) : outlineMount(model, 0, 0);
            Update<Model, Msg, Never> applied = mount.update(model, message.value());
            String prefix = message.pane() == 0 ? "main-" : "outline-";
            return Update.next(applied.model().withLast(prefix + action(message.value())), applied.command());
        }

        /** One field message, applied through the field's mount and then published to the pager. */
        private Update<Model, Msg, Never> typed(Model model, TextInput.Msg message) {
            Update<Model, Msg, Never> applied = fieldMount(model).update(model, message);
            // The ranges are the scene's search policy applied to the field's own text, so a
            // keystroke republishes them; the first match then becomes active, which is what makes
            // find-as-you-type jump to the first hit.
            return Update.next(
                    published(applied.model(), applied.model().lines(), true).withLast("typed"));
        }

        /** The document gains one note, and both panes are told the content changed. */
        private Update<Model, Msg, Never> appended(Model model) {
            int number = model.added() + 1;
            List<StyledText> lines = new ArrayList<>(model.lines());
            lines.add(StyledText.plain("Note " + number + ": appended by the demo, an artichoke note."));
            Model grown = model.withAdded(number).withLines(List.copyOf(lines));
            // A note is not a new query: the ranges are published again for the new content version,
            // but no match is selected, so an append never moves a reader who was following.
            return Update.next(published(grown, grown.lines(), false).withLast("appended-" + number));
        }

        /**
         * Publishes one content snapshot and the ranges the current query matches in it.
         *
         * <p>Publishing content clears the pane's ranges, because they described the version being
         * replaced, so the scene publishes them again from the same snapshot. {@code select} is the
         * scene's decision, not the pane's: a keystroke selects the first match, which is what makes
         * find-as-you-type useful, and an appended note selects nothing, so appending while
         * following keeps the window on the end instead of jumping to the first match.</p>
         */
        private Model published(Model model, List<StyledText> lines, boolean select) {
            Model content = model.withLines(lines);
            Update<Model, Msg, Never> replaced = mainMount(content, 0, 0)
                    .update(content, new TextViewport.Msg.ContentChanged(TextViewport.Content.of(lines)));
            Model withContent = replaced.model();
            List<TextViewport.Highlight> found = matches(lines, model.search().text());
            Update<Model, Msg, Never> highlighted =
                    mainMount(withContent, 0, 0).update(withContent, new TextViewport.Msg.Highlights(found));
            Model withMatches = highlighted.model();
            if (!select || found.isEmpty()) {
                return withMatches;
            }
            return mainMount(withMatches, 0, 0)
                    .update(withMatches, new TextViewport.Msg.NextMatch())
                    .model();
        }

        /** Publishes the outline's own content, which has no matches and follows nothing. */
        private Model publishedOutline(Model model, List<StyledText> lines) {
            Update<Model, Msg, Never> replaced = outlineMount(model, 0, 0)
                    .update(model, new TextViewport.Msg.ContentChanged(TextViewport.Content.of(lines)));
            return replaced.model();
        }

        /** The geometry the scene reports to both panes for one terminal size. */
        private Model geometry(Model model, int rows, int columns) {
            int cols = Math.max(0, columns);
            Update<Model, Msg, Never> main =
                    mainMount(model, 0, 0).update(model, new TextViewport.Msg.Resized(mainRows(rows), cols));
            Update<Model, Msg, Never> outline = outlineMount(main.model(), 0, 0)
                    .update(main.model(), new TextViewport.Msg.Resized(outlineRows(rows), cols));
            return outline.model();
        }

        /** The pager's own state, as one verifier-friendly row. */
        private static String status(Model model) {
            TextViewport.Config config = mainConfig(model, 0, 0);
            TextViewport.Model main = model.main();
            return "mode=" + name(model.mode())
                    + " numbers=" + onOff(model.numbers())
                    + " follow=" + onOff(main.followEnd())
                    + " line=" + (main.topLine() + 1) + "/" + main.lineCount()
                    + " total=" + TextViewport.totalRows(main, config)
                    + " vscroll=" + percent(TextViewport.verticalPercent(main, config))
                    + " hscroll=" + percent(TextViewport.horizontalPercent(main, config));
        }

        /** What the search found, what is active, and which component owns focus. */
        private static String found(Model model) {
            return "matches=" + model.main().highlights().size()
                    + " active=" + (model.main().active() + 1)
                    + " query="
                    + (model.search().text().isEmpty() ? "-" : model.search().text())
                    + " focus=" + name(model.focus())
                    + " added=" + model.added()
                    + " last=" + model.last();
        }

        /** The outline's own state, which moves independently of the pager. */
        private static String outlineStatus(Model model) {
            TextViewport.Config config = outlineConfig(model, 0, 0);
            TextViewport.Model outline = model.outline();
            return "outline=pan line=" + (outline.topLine() + 1)
                    + "/" + outline.lineCount()
                    + " rows=" + outline.rows()
                    + " vscroll=" + percent(TextViewport.verticalPercent(outline, config));
        }

        private static String percent(double value) {
            return Math.round(value * 100) + "%";
        }

        private static String described(TextInput.Output output) {
            return switch (output) {
                case TextInput.Output.Submit submit -> "submit";
                case TextInput.Output.Rejected rejected -> "rejected";
                case TextInput.Output.ValidationChanged changed -> "validated";
                case TextInput.Output.SuggestionsRequested requested -> "suggestions";
                case TextInput.Output.Copy copy -> "copy";
                case TextInput.Output.PasteRequest paste -> "paste";
            };
        }

        private static String action(TextViewport.Msg message) {
            return switch (message) {
                case TextViewport.Msg.Down down -> "down";
                case TextViewport.Msg.Up up -> "up";
                case TextViewport.Msg.PageDown pageDown -> "page-down";
                case TextViewport.Msg.PageUp pageUp -> "page-up";
                case TextViewport.Msg.HalfPageDown half -> "half-page-down";
                case TextViewport.Msg.HalfPageUp half -> "half-page-up";
                case TextViewport.Msg.Top top -> "top";
                case TextViewport.Msg.Bottom bottom -> "bottom";
                case TextViewport.Msg.Left left -> "left";
                case TextViewport.Msg.Right right -> "right";
                case TextViewport.Msg.ContentChanged changed -> "content";
                case TextViewport.Msg.Resized resized -> "resized";
                case TextViewport.Msg.Highlights highlights -> "highlights";
                case TextViewport.Msg.NextMatch next -> "next-match";
                case TextViewport.Msg.PreviousMatch previous -> "previous-match";
            };
        }

        private static String name(TextViewport.Mode mode) {
            return mode == TextViewport.Mode.WRAP ? "wrap" : "pan";
        }

        private static String name(Focus focus) {
            return focus == Focus.MAIN ? "main" : focus == Focus.OUTLINE ? "outline" : "field";
        }

        private static String onOff(boolean value) {
            return value ? "on" : "off";
        }
    }

    /** The terminal size, which is the one number the scene splits between its panes. */
    private static final SubscriptionId SIZE = SubscriptionId.of("size");
}

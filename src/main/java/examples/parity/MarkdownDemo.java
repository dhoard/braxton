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
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Markdown;
import io.github.dhoard.braxton.widget.MarkdownConfig;
import io.github.dhoard.braxton.widget.TextInput;
import io.github.dhoard.braxton.widget.TextInputConfig;
import io.github.dhoard.braxton.widget.TextViewport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 15 parity demo: a complete markdown pager over a checked-in original document.
 *
 * <p>The scene owns the source, the projection, the theme and the width the projection was made at;
 * a {@link TextViewport} mounted over the projection owns the window, the anchor and the matches,
 * and a {@link TextInput} mounted beside it owns the query. Nothing here parses markdown itself: the
 * scene re-projects with {@link Markdown} and publishes the result, which is what makes the theme,
 * width and limits switches the demo's own state rather than a widget's.</p>
 *
 * <p>The document is the one the phase ships as a resource: it holds every construct the dialect
 * supports - six heading levels, emphasis, strong and strikethrough, inline code, links, autolinks,
 * an image's alt text, escaped markers, a hard break, tight and loose lists, a task list, nested and
 * double-nested quotations, fenced code in three languages and in one unknown language, an indented
 * block, a GFM table with three alignments, a thematic break, raw HTML written as text, and wide
 * glyphs - and it repeats the word {@code lantern} so the find field has matches to walk.</p>
 *
 * <p>Like the other parity demos it is a normal Braxton program: a public {@link #main(String[])} for
 * the packaged launch and a reusable {@link Scene} for tests, with no dependency beyond Braxton's
 * own runtime dependencies.</p>
 */
public final class MarkdownDemo {

    /**
     * The status rows the scene paints above the find field.
     */
    public static final int HEADER_ROWS = 2;

    /**
     * The rows the find field occupies.
     */
    public static final int FIELD_ROWS = 1;

    /**
     * The rows the scene paints below the pager: one help line per key map.
     */
    public static final int HELP_ROWS = 2;

    /**
     * The cells the line-number gutter occupies, separator included.
     */
    public static final int GUTTER_COLUMNS = 6;

    /**
     * The width a panned projection is made at, so panning has something to pan.
     */
    public static final int PAN_WIDTH = 200;

    /**
     * The cells a strict-limit projection may paint, which this document does not fit.
     */
    public static final long STRICT_CELLS = 200;

    /**
     * The word the document repeats and the demo's search finds.
     */
    public static final String KEYWORD = "lantern";

    /**
     * The document's source, read from the resource the jar packages beside the class.
     */
    public static final String SOURCE = source();

    /**
     * The resource path the document is packaged at.
     */
    private static final String RESOURCE = "/examples/parity/markdown.md";

    private static final Style STATUS = Style.NONE.withBold();

    private static final Style GUTTER = Style.NONE.withItalic();

    /**
     * The pager's rows at one terminal height.
     */
    private static final int FIXED_ROWS = HEADER_ROWS + FIELD_ROWS + HELP_ROWS;

    private MarkdownDemo() {}

    /**
     * The scene state.
     *
     * @param source the markdown source the scene projects
     * @param document the projection of {@code source} at the current theme, width and limits
     * @param pager the mounted pager's own state
     * @param search the mounted find field's own state
     * @param dark whether the dark theme and its highlighter are in use
     * @param mode how the pager treats a row wider than the pane
     * @param numbers whether the pager paints line numbers
     * @param strict whether deliberately small limits are in use, which this document exceeds
     * @param last what the scene last did
     */
    public record Model(
            String source,
            Markdown.Document document,
            TextViewport.Model pager,
            TextInput.Model search,
            boolean dark,
            TextViewport.Mode mode,
            boolean numbers,
            boolean strict,
            String last) {

        /**
         * Substitutes the demo's own values for a missing one.
         */
        public Model {
            source = source == null ? SOURCE : source;
            document = document == null ? Markdown.of().document(source) : document;
            pager = pager == null ? TextViewport.Model.of(TextViewport.Content.empty()) : pager;
            search = search == null ? TextInput.Model.empty() : search;
            mode = mode == null ? TextViewport.Mode.WRAP : mode;
            last = last == null ? "none" : last;
        }

        Model withDocument(Markdown.Document value) {
            return new Model(source, value, pager, search, dark, mode, numbers, strict, last);
        }

        Model withPager(TextViewport.Model value) {
            return new Model(source, document, value, search, dark, mode, numbers, strict, last);
        }

        Model withSearch(TextInput.Model value) {
            return new Model(source, document, pager, value, dark, mode, numbers, strict, last);
        }

        Model withDark(boolean value) {
            return new Model(source, document, pager, search, value, mode, numbers, strict, last);
        }

        Model withMode(TextViewport.Mode value) {
            return new Model(source, document, pager, search, dark, value, numbers, strict, last);
        }

        Model withNumbers(boolean value) {
            return new Model(source, document, pager, search, dark, mode, value, strict, last);
        }

        Model withStrict(boolean value) {
            return new Model(source, document, pager, search, dark, mode, numbers, value, last);
        }

        Model withLast(String value) {
            return new Model(source, document, pager, search, dark, mode, numbers, strict, value);
        }

        /**
         * The same state with focus on the field or on the pager.
         *
         * <p>Focus is model data in both components, so the scene is the only owner of which one is
         * focused; a reduction in either never moves it.</p>
         *
         * @param field whether the find field owns focus
         * @return the new state
         */
        Model withFocus(boolean field) {
            return new Model(
                    source,
                    document,
                    pager.withFocus(!field),
                    search.withFocus(field),
                    dark,
                    mode,
                    numbers,
                    strict,
                    last);
        }
    }

    /**
     * Every message the scene handles.
     */
    public sealed interface Msg {

        /**
         * A message from the mounted pager.
         *
         * @param value the pager's message
         */
        record Pane(TextViewport.Msg value) implements Msg {}

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
         * Reports the terminal's geometry, which the scene turns into the pager's allocation and the
         * projection's width.
         *
         * @param rows the terminal's rows
         * @param columns the terminal's columns
         */
        record Resized(int rows, int columns) implements Msg {}

        /**
         * Switches between the light and the dark theme.
         */
        record ToggleTheme() implements Msg {}

        /**
         * Switches the pager between wrapping and panning, and the projection with it.
         */
        record ToggleMode() implements Msg {}

        /**
         * Shows or hides the pager's line numbers, which takes cells off the projection.
         */
        record ToggleNumbers() implements Msg {}

        /**
         * Switches between the default bounds and limits this document does not fit.
         */
        record ToggleLimits() implements Msg {}

        /**
         * Moves focus to the find field.
         */
        record Search() implements Msg {}

        /**
         * Moves focus back to the pager.
         */
        record Find() implements Msg {}

        /**
         * Ends the session.
         */
        record Quit() implements Msg {}
    }

    /**
     * The scene's own actions: the ones that change the projection or what is painted around it.
     *
     * <p>They are bubble bindings, so a focused component sees every key first: the gestures the
     * pager owns never reach this map, and a focused field turns printable keys into a query.</p>
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
                        "Render",
                        KeyAction.<Msg>of("theme", new Msg.ToggleTheme(), "t", "dark/light", "t"),
                        KeyAction.<Msg>of("mode", new Msg.ToggleMode(), "w", "wrap/pan", "w"),
                        KeyAction.<Msg>of("numbers", new Msg.ToggleNumbers(), "g", "line numbers", "g"),
                        KeyAction.<Msg>of("limits", new Msg.ToggleLimits(), "p", "strict limits", "p")),
                KeyMap.group("Scene", KeyAction.<Msg>of("quit", new Msg.Quit(), "ctrl+c", "quit", "ctrl+c")));
    }

    /**
     * Runs the demo against the owned terminal.
     *
     * @return the structured session exit outcome
     */
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
     * The document's source, read once from the packaged resource.
     *
     * @return the source
     */
    public static String source() {
        try (InputStream stream = MarkdownDemo.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the demo's document is not on the classpath: " + RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("the demo's document could not be read: " + RESOURCE, failure);
        }
    }

    /**
     * The renderer definition one scene state asks for.
     *
     * @param model the scene state
     * @return the definition, at the width this state's pager shows
     */
    public static MarkdownConfig config(Model model) {
        MarkdownConfig config = model.dark() ? MarkdownConfig.dark() : MarkdownConfig.defaults();
        return config.withWidth(projectionWidth(model)).withLimits(limits(model));
    }

    /**
     * The bounds one scene state asks for.
     *
     * <p>The strict preset lowers the cell budget to a value this document does not fit, which is
     * how the demo shows a typed diagnostic, its painted row and the pager over it without shipping
     * a second, hostile document.</p>
     *
     * @param model the scene state
     * @return the bounds
     */
    public static Markdown.Limits limits(Model model) {
        Markdown.Limits defaults = Markdown.Limits.defaults();
        return model.strict()
                ? new Markdown.Limits(defaults.inputBytes(), defaults.nodes(), defaults.depth(), STRICT_CELLS)
                : defaults;
    }

    /**
     * The width this state's projection is made at.
     *
     * <p>A wrapping pager gets the cells its content column has, so a paragraph is wrapped at the
     * word boundary the reader sees. A panning pager gets a wide projection instead, because its
     * job is to scroll a row the author wrote long, and the single offset it applies would otherwise
     * pan rows that were already broken to fit.</p>
     *
     * @param model the scene state
     * @return the cells one projected row may occupy, at least one
     */
    public static int projectionWidth(Model model) {
        int columns = model.pager().columns() > 0 ? model.pager().columns() : TextViewport.DEFAULT_COLUMNS;
        if (model.mode() == TextViewport.Mode.PAN) {
            return Math.max(columns, PAN_WIDTH);
        }
        int content = columns - (model.numbers() ? GUTTER_COLUMNS : 0);
        return Math.max(1, content);
    }

    /**
     * The pager's definition for one scene state.
     *
     * @param model the scene state
     * @param rows the rows this scene allocates the pager
     * @param columns the columns this scene allocates the pager
     * @return the pager's definition
     */
    public static TextViewport.Config pagerConfig(Model model, int rows, int columns) {
        return TextViewport.Config.defaults()
                .withMode(model.mode())
                .withFollowEnd(false)
                .withGutter(model.numbers() ? gutter() : null)
                .withStyles(TextViewport.Config.Styles.defaults())
                .withRows(rows)
                .withColumns(columns);
    }

    /**
     * The find field's definition.
     *
     * @return the field's definition
     */
    public static TextInputConfig searchConfig() {
        return TextInputConfig.defaults()
                .withPrompt("find: ", Style.NONE.withBold())
                .withCompletion(TextInput.Completion.disabled());
    }

    /**
     * The line-number gutter the pager paints.
     *
     * @return the line-number gutter the pager paints
     */
    public static TextViewport.Gutter gutter() {
        return TextViewport.Gutter.lineNumbers(GUTTER_COLUMNS, GUTTER);
    }

    /**
     * The rows the pager is allocated at one terminal height.
     *
     * @param rows the terminal's rows
     * @return the pager's rows, never negative
     */
    public static int pagerRows(int rows) {
        return Math.max(0, Math.max(0, rows) - FIXED_ROWS);
    }

    /**
     * The ranges a query matches in one projection, in paint order.
     *
     * <p>The policy is the demo's own: every case-insensitive occurrence of the query on a line, in
     * the line's own UTF-16 offsets and widened to whole clusters so a highlight never splits a
     * grapheme. The renderer computes no matches, and neither does the pager.</p>
     *
     * @param lines the projected lines to search
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

    /**
     * The length in UTF-16 units of the pattern matched at one offset, or zero.
     */
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

    // ── the scene ────────────────────────────────────────────────────────────

    /**
     * The status rows, the find field, the pager and the two help lines.
     */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Creates the scene with its default state.
         */
        public Scene() {}

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            int rows = context.viewport().rows() > 0 ? context.viewport().rows() : 24;
            int columns = context.viewport().cols() > 0 ? context.viewport().cols() : 80;
            Model empty = new Model(
                    SOURCE,
                    null,
                    null,
                    new TextInput(searchConfig()).validated(TextInput.Model.empty()),
                    false,
                    TextViewport.Mode.WRAP,
                    true,
                    false,
                    "none");
            // The pager starts focused and the field does not: the demo opens on the document.
            Model started = empty.withSearch(empty.search().withFocus(false))
                    .withPager(new TextViewport(pagerConfig(empty, 0, 0))
                            .init(context)
                            .model());
            Model opened = geometry(started, rows, columns);
            // The first publication is what gives the pager its document; the projection is made at
            // the width the pager was just told it has.
            return Update.next(published(opened, false).withLast("started"));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Pane pane -> paneUpdate(model, pane);
                case Msg.Field field -> typed(model, field.value());
                case Msg.FieldOutput output -> Update.next(model.withLast(described(output.value())));
                case Msg.Resized resized -> resized(model, resized.rows(), resized.columns());
                case Msg.ToggleTheme toggle -> {
                    boolean dark = !model.dark();
                    yield Update.next(
                            published(model.withDark(dark), false).withLast("theme-" + (dark ? "dark" : "light")));
                }
                case Msg.ToggleMode toggle -> {
                    TextViewport.Mode mode =
                            model.mode() == TextViewport.Mode.WRAP ? TextViewport.Mode.PAN : TextViewport.Mode.WRAP;
                    yield Update.next(published(model.withMode(mode), false).withLast("mode-" + name(mode)));
                }
                case Msg.ToggleNumbers toggle -> {
                    boolean numbers = !model.numbers();
                    yield Update.next(
                            published(model.withNumbers(numbers), false).withLast("numbers-" + onOff(numbers)));
                }
                case Msg.ToggleLimits toggle -> {
                    boolean strict = !model.strict();
                    yield Update.next(published(model.withStrict(strict), false).withLast("limits-" + name(strict)));
                }
                case Msg.Search search -> Update.next(model.withFocus(true).withLast("focus-field"));
                case Msg.Find find -> Update.next(model.withFocus(false).withLast("focus-pager"));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        /**
         * One resize source for the whole scene.
         *
         * <p>The scene is the only owner of the terminal's geometry: it decides how many rows the
         * pager gets and how many cells the projection may use, which is why the mounted components
         * declare no resize source of their own.</p>
         */
        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.resize(SIZE, size -> new Msg.Resized(size.rows(), size.cols()));
        }

        @Override
        public Element<Msg> view(Model model, ViewContext context) {
            return scene(model, context);
        }

        /**
         * The gestures this scene answers once no widget has taken them.
         */
        @Override
        public List<Binding<Msg>> bindings(Model model) {
            return keys().bindings();
        }

        /**
         * The scene as one element tree.
         */
        private Element<Msg> scene(Model model, ViewContext context) {

            int rows = context.viewport().rows();
            int cols = context.viewport().cols();
            int pagerRows = pagerRows(rows);
            List<Element<Msg>> sections = new ArrayList<>(6);
            sections.add(Elements.fixed(1, Elements.text(status(model), STATUS)));
            sections.add(Elements.fixed(1, Elements.text(found(model), STATUS)));
            sections.add(Elements.fixed(1, fieldMount(model).view(model, context)));
            sections.add(
                    Elements.fixed(pagerRows, pagerMount(model, pagerRows, cols).view(model, context)));
            sections.add(Elements.fixed(1, Elements.text(Help.render(pagerKeys(model), false, cols))));
            sections.add(Elements.fixed(1, Elements.text(Help.render(keys(), false, cols))));
            return Elements.column(sections);
        }

        /**
         * The pager's own gestures, for the mode its definition is in.
         */
        private static KeyMap<TextViewport.Msg> pagerKeys(Model model) {
            return TextViewport.keys(model.pager(), pagerConfig(model, 0, 0));
        }

        /**
         * The pager mounted into its own slot of the scene's model.
         *
         * <p>The component is built from the definition this state asks for on every use; nothing
         * else about the mount changes, so its identity, lens and routes are the same values every
         * time.</p>
         */
        private Mount<Model, Msg, Never, TextViewport.Model, TextViewport.Msg, Never> pagerMount(
                Model model, int rows, int columns) {
            return Mount.of(
                    ComponentId.of("pager"),
                    new TextViewport(pagerConfig(model, rows, columns)),
                    ModelLens.of(Model::pager, (Model state, TextViewport.Model child) -> state.withPager(child)),
                    Msg.Pane::new,
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

        /**
         * One pager message, applied through the mount that owns it.
         */
        private Update<Model, Msg, Never> paneUpdate(Model model, Msg.Pane message) {
            Update<Model, Msg, Never> applied = pagerMount(model, 0, 0).update(model, message.value());
            return Update.next(applied.model().withLast("pager-" + action(message.value())), applied.command());
        }

        /**
         * One field message, applied through the field's mount and then published to the pager.
         */
        private Update<Model, Msg, Never> typed(Model model, TextInput.Msg message) {
            Update<Model, Msg, Never> applied = fieldMount(model).update(model, message);
            // The ranges are this scene's search policy applied to the projection that is on screen,
            // so a keystroke republishes them and the first match becomes active: find-as-you-type.
            return Update.next(searched(applied.model(), true).withLast("typed"));
        }

        /**
         * A resize: the pager is told its new allocation, then the document is projected again at the
         * width the new allocation leaves.
         *
         * <p>The projection is a function of the width, so a resize is a new projection rather than a
         * second wrapping rule in the pager. Publishing it as new content keeps the pager's logical
         * anchor, which is what lets a reader who was on a paragraph stay on it.</p>
         */
        private Update<Model, Msg, Never> resized(Model model, int rows, int columns) {
            Model sized = geometry(model, rows, columns);
            return Update.next(published(sized, false).withLast("resized"));
        }

        /**
         * Projects the scene's source at what this state asks for and publishes it to the pager.
         *
         * <p>Publishing content clears the ranges that described the previous projection, so the
         * scene publishes them again from the new one. {@code select} is the scene's decision: a
         * keystroke selects the first match, and a re-projection selects nothing, so a resize or a
         * theme switch never moves a reader to a match they were not looking at.</p>
         */
        private Model published(Model model, boolean select) {
            Markdown.Document document = Markdown.of(config(model)).document(model.source());
            Model rendered = model.withDocument(document);
            Update<Model, Msg, Never> replaced = pagerMount(rendered, 0, 0)
                    .update(rendered, new TextViewport.Msg.ContentChanged(document.content()));
            return searched(replaced.model(), select);
        }

        /**
         * Publishes the ranges the current query matches in the current projection.
         */
        private Model searched(Model model, boolean select) {
            List<TextViewport.Highlight> found =
                    matches(model.document().lines(), model.search().text());
            Update<Model, Msg, Never> highlighted =
                    pagerMount(model, 0, 0).update(model, new TextViewport.Msg.Highlights(found));
            Model withMatches = highlighted.model();
            if (!select || found.isEmpty()) {
                return withMatches;
            }
            return pagerMount(withMatches, 0, 0)
                    .update(withMatches, new TextViewport.Msg.NextMatch())
                    .model();
        }

        /**
         * The pager's allocation for one terminal size.
         */
        private Model geometry(Model model, int rows, int columns) {
            int cols = Math.max(0, columns);
            Update<Model, Msg, Never> resized =
                    pagerMount(model, 0, 0).update(model, new TextViewport.Msg.Resized(pagerRows(rows), cols));
            return resized.model();
        }

        /**
         * What the renderer was asked for, so a verifier can assert it without reading cells.
         */
        private static String status(Model model) {
            return "theme=" + (model.dark() ? "dark" : "light")
                    + " mode=" + name(model.mode())
                    + " numbers=" + onOff(model.numbers())
                    + " rows=" + model.pager().rows()
                    + " width=" + projectionWidth(model)
                    + " lines=" + model.document().lineCount()
                    + " limits=" + (model.strict() ? "strict" : "default");
        }

        /**
         * What the projection answered, what the search found, and what the scene last did.
         */
        private static String found(Model model) {
            TextViewport.Model pager = model.pager();
            return "diagnostics=" + diagnostics(model.document())
                    + " matches=" + pager.highlights().size()
                    + " active=" + (pager.active() + 1)
                    + " query="
                    + (model.search().text().isEmpty() ? "-" : model.search().text())
                    + " focus=" + (model.search().focused() ? "field" : "pager")
                    + " last=" + model.last();
        }

        /**
         * The diagnostics a projection published, or {@code none}.
         */
        private static String diagnostics(Markdown.Document document) {
            if (!document.failed()) {
                return "none";
            }
            return name(document.diagnostics().get(0));
        }

        private static String name(Markdown.Diagnostic diagnostic) {
            return switch (diagnostic) {
                case Markdown.Diagnostic.InputTooLarge tooLarge -> "input-too-large";
                case Markdown.Diagnostic.TooManyNodes nodes -> "too-many-nodes";
                case Markdown.Diagnostic.NestingTooDeep deep -> "nesting-too-deep";
                case Markdown.Diagnostic.TooManyCells cells -> "too-many-cells";
            };
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

        private static String name(boolean strict) {
            return strict ? "strict" : "default";
        }

        private static String onOff(boolean value) {
            return value ? "on" : "off";
        }
    }

    /**
     * The terminal size, which is the one number the scene splits between its components.
     */
    private static final SubscriptionId SIZE = SubscriptionId.of("size");
}

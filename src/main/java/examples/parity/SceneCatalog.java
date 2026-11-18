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

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.ComponentId;
import io.github.dhoard.braxton.mvu.ComponentTree;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ModelLens;
import io.github.dhoard.braxton.mvu.Mount;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.view.View;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The parity catalog: one entry per implemented phase, plus the composed workspace.
 *
 * <p>Entries are data, not a menu of launches. Each one names a completed scene component that the
 * showcase mounts as its own child, so opening a phase is a mount and returning to the catalog is
 * the unmount - which is what makes the child's commands, subscriptions, focus elements and
 * pointer captures end with it. Nothing here forks a demo implementation, and no phase is listed
 * before it exists.</p>
 *
 * <p>The ids are the phase numbers, in the two-digit form the plans and the terminal verifier use,
 * so a catalog id, a verifier scenario, an implementation plan and a regression test all name the
 * same thing. A scene is typed - each demo has its own model and message types - while a catalog is
 * a list of them, so this class is the one place where the heterogeneity is erased: a slot holds its
 * scene's model inside the showcase's model as a value and gets it back through the scene's own
 * {@code Class} token, which is a checked cast rather than an unchecked one.</p>
 */
public final class SceneCatalog {

    /** The fixture document set the file picker and the workspace browse, by file name. */
    public static final Map<String, String> DOCUMENTS;

    static {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("notes.md", """
                # Release notes

                The lantern is packed.

                - parser
                - renderer
                - lantern
                """);
        documents.put("guide.md", """
                # Field guide

                ## Lantern care

                Trim the wick, fill the font and keep the lantern dry.

                ## Packing

                One lantern per crate.
                """);
        DOCUMENTS = Map.copyOf(documents);
    }

    private SceneCatalog() {}

    /**
     * One catalog entry.
     *
     * @param id the stable identity, which is the phase number
     * @param title what the phase is about
     * @param summary one line the catalog paints beside the title
     */
    public record Entry(String id, String title, String summary) {

        /** Rejects an incomplete entry. */
        public Entry {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id is required");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("title is required");
            }
            summary = summary == null ? "" : summary;
        }

        /**
         * The entry as one catalog row, before the width is applied.
         *
         * @return the row's text
         */
        public String line() {
            return id + " " + title + " \u2014 " + summary;
        }
    }

    /**
     * One mountable scene, as the showcase sees it.
     *
     * <p>The child's message arrives as a value because a slot's message type belongs to its scene:
     * {@link #update} casts it back with the scene's own token before the mount sees it, so the
     * erased boundary is one checked cast inside one class rather than a raw type anywhere
     * else.</p>
     */
    public interface Slot {

        /**
         * The catalog entry this slot mounts.
         *
         * @return the entry
         */
        Entry entry();

        /**
         * Initializes the scene and writes its first model into the parent's slot.
         *
         * @param context the startup facts the runtime gave the root
         * @param model the parent model the scene is being opened over
         * @return the parent's next model and the scene's scoped command
         */
        Update<Showcase.Model, Showcase.Msg, Never> enter(InitContext context, Showcase.Model model);

        /**
         * Reduces one message of this scene.
         *
         * @param model the parent model holding this scene
         * @param message the scene's own message, as the mount lifted it
         * @return the parent's next model and the scene's scoped command
         */
        Update<Showcase.Model, Showcase.Msg, Never> update(Showcase.Model model, Object message);

        /**
         * Paints the scene.
         *
         * @param model the parent model holding this scene
         * @param context the geometry the showcase allocated to it
         * @return the scene's view, mapped to the showcase's message type
         */
        View<Showcase.Msg> view(Showcase.Model model, ViewContext context);

        /**
         * The scene's own subscriptions, scoped to its mount path.
         *
         * @param model the parent model holding this scene
         * @return the lifted subscriptions
         */
        Subscription<Showcase.Msg> subscriptions(Showcase.Model model);

        /**
         * The scene's own children, scoped to its mount path.
         *
         * @param model the parent model holding this scene
         * @return the lifted tree node
         */
        ComponentTree.Node tree(Showcase.Model model);
    }

    /**
     * Every implemented phase, in phase order, plus the composed workspace.
     *
     * <p>A scene is built from an owned fixture directory rather than from the process's own working
     * directory, so browsing and writing stay inside state the caller owns and removes.</p>
     *
     * @param fixture the directory the file picker and the workspace browse; required
     * @return the catalog, in catalog order
     */
    public static List<Slot> slots(Path fixture) {
        Objects.requireNonNull(fixture, "fixture");
        return List.of(
                slot(
                        entry("01", "Keymaps and help", "one action definition drives routing and help"),
                        new KeymapHelpDemo.Scene(),
                        KeymapHelpDemo.Model.class,
                        KeymapHelpDemo.Msg.class),
                slot(
                        entry("02", "Enhanced keyboard and terminal focus", "key event types and focus reporting"),
                        new KeyboardFocusDemo.Scene(),
                        KeyboardFocusDemo.Model.class,
                        KeyboardFocusDemo.Msg.class),
                slot(
                        entry(
                                "03",
                                "Native and virtual cursors",
                                "the caret the terminal draws and the one the view does"),
                        new CursorDemo.Scene(),
                        CursorDemo.Model.class,
                        CursorDemo.Msg.class),
                slot(
                        entry("04", "Complete multiline editor", "wrapping, selection and the textarea frame"),
                        new EditorDemo.Scene(),
                        EditorDemo.Model.class,
                        EditorDemo.Msg.class),
                slot(
                        entry("05", "Single-line text input", "validation, completion and submission"),
                        new TextInputDemo.Scene(),
                        TextInputDemo.Model.class,
                        TextInputDemo.Msg.class),
                slot(
                        entry("06", "Pagination state and indicators", "pages, bounds and the page indicator"),
                        new PaginatorDemo.Scene(),
                        PaginatorDemo.Model.class,
                        PaginatorDemo.Msg.class),
                slot(
                        entry("07", "Configurable spinners", "presets, cadence and a still frame"),
                        new SpinnerDemo.Scene(),
                        SpinnerDemo.Model.class,
                        SpinnerDemo.Msg.class),
                slot(
                        entry("08", "Animated progress", "an eased fill and a blended bar"),
                        new ProgressDemo.Scene(),
                        ProgressDemo.Model.class,
                        ProgressDemo.Msg.class),
                slot(
                        entry("09", "Countdown timer", "start, pause, reset and expiry"),
                        new CountdownDemo.Scene(CountdownDemo.DEFAULT_DURATION),
                        CountdownDemo.Model.class,
                        CountdownDemo.Msg.class),
                slot(
                        entry("10", "Stopwatch", "two independent runs and their elapsed time"),
                        new StopwatchDemo.Scene(),
                        StopwatchDemo.Model.class,
                        StopwatchDemo.Msg.class),
                slot(
                        entry("11", "Rich filterable lists", "fuzzy filtering, paging and item renderers"),
                        new RichListDemo.Scene(),
                        RichListDemo.Model.class,
                        RichListDemo.Msg.class),
                slot(
                        entry("12", "Rich text viewport and pager", "wrapping, panning, matches and gutters"),
                        new ViewportDemo.Scene(),
                        ViewportDemo.Model.class,
                        ViewportDemo.Msg.class),
                slot(
                        entry("13", "Scrollable interactive tables", "row and cell selection over wide content"),
                        new TableDemo.Scene(),
                        TableDemo.Model.class,
                        TableDemo.Msg.class),
                slot(
                        entry("14", "File picker", "browse, check and choose an entry"),
                        new FilePickerDemo.Scene(fixture),
                        FilePickerDemo.Model.class,
                        FilePickerDemo.Msg.class),
                slot(
                        entry("15", "Markdown rendering", "a themed projection with search"),
                        new MarkdownDemo.Scene(),
                        MarkdownDemo.Model.class,
                        MarkdownDemo.Msg.class),
                slot(
                        entry("16", "Tabs and composed views", "three kept-alive pages behind one bar"),
                        new TabsDemo.Scene(),
                        TabsDemo.Model.class,
                        TabsDemo.Msg.class),
                slot(
                        entry("17", "Mouse events and hit testing", "clicks, drags and a modal dialog"),
                        new MouseDemo.Scene(),
                        MouseDemo.Model.class,
                        MouseDemo.Msg.class),
                slot(
                        entry(
                                "18",
                                "Terminal queries and colour profiles",
                                "questions the terminal answers, and its refusals"),
                        new TerminalQueryDemo.Scene(),
                        TerminalQueryDemo.Model.class,
                        TerminalQueryDemo.Msg.class),
                slot(
                        entry("19", "Terminal and primary clipboard", "copy transports and clipboard reads"),
                        new ClipboardDemo.Scene(),
                        ClipboardDemo.Model.class,
                        ClipboardDemo.Msg.class),
                slot(
                        entry("20", "Declarative terminal presentation", "screen, modes, title, colours and progress"),
                        new TerminalPresentationDemo.Scene(),
                        TerminalPresentationDemo.Model.class,
                        TerminalPresentationDemo.Msg.class),
                slot(
                        entry(
                                "21",
                                "Process handoff and suspend/resume",
                                "the terminal lent to a child and taken back"),
                        new ProcessHandoffDemo.Scene(),
                        ProcessHandoffDemo.Model.class,
                        ProcessHandoffDemo.Msg.class),
                slot(
                        entry(
                                "22",
                                "Command ordering and application control",
                                "a sequence, an after-update step and a guarded quit"),
                        new ApplicationControlDemo.Scene(),
                        ApplicationControlDemo.Model.class,
                        ApplicationControlDemo.Msg.class),
                slot(
                        entry("23", "Inline output and streaming", "durable lines, piped input and headless runs"),
                        new InlineIoDemo.Scene(),
                        InlineIoDemo.Model.class,
                        InlineIoDemo.Msg.class),
                slot(
                        entry(
                                "24",
                                "Composed task workspace",
                                "files, preview, drafts, tasks, progress and a confirmation"),
                        new WorkspaceDemo.Scene(fixture),
                        WorkspaceDemo.Model.class,
                        WorkspaceDemo.Msg.class));
    }

    /**
     * One entry.
     *
     * @param id the phase number
     * @param title the phase's title
     * @param summary the one-line summary
     * @return the entry
     */
    public static Entry entry(String id, String title, String summary) {
        return new Entry(id, title, summary);
    }

    /**
     * One typed scene as a slot the showcase can drive.
     *
     * @param <M> the scene's model type
     * @param <G> the scene's message type
     * @param entry the catalog entry
     * @param component the scene component
     * @param modelType the scene's model token, which makes the erased read a checked cast
     * @param messageType the scene's message token, for the same reason
     * @return the slot
     */
    private static <M, G> Slot slot(
            Entry entry, Component<M, G, Never> component, Class<M> modelType, Class<G> messageType) {
        Mount<Showcase.Model, Showcase.Msg, Never, M, G, Never> mount = Mount.of(
                ComponentId.of(entry.id()),
                component,
                ModelLens.of(
                        model -> modelType.cast(model.sceneModel()),
                        (model, child) -> model.withScene(entry.id(), child)),
                message -> new Showcase.Msg.SceneMessage(entry.id(), message),
                output -> {
                    // Never is uninhabited: a scene with semantic outputs would need a route of its
                    // own, so the catalog refuses one here rather than silently dropping it.
                    throw new IllegalStateException("a catalog scene reports no outputs");
                });
        return new Slot() {
            @Override
            public Entry entry() {
                return entry;
            }

            @Override
            public Update<Showcase.Model, Showcase.Msg, Never> enter(InitContext context, Showcase.Model model) {
                var started = mount.initialize(context);
                return new Update<>(model.withScene(entry.id(), started.model()), started.command(), started.outputs());
            }

            @Override
            public Update<Showcase.Model, Showcase.Msg, Never> update(Showcase.Model model, Object message) {
                return mount.update(model, messageType.cast(message));
            }

            @Override
            public View<Showcase.Msg> view(Showcase.Model model, ViewContext context) {
                return mount.view(model, context);
            }

            @Override
            public Subscription<Showcase.Msg> subscriptions(Showcase.Model model) {
                return mount.subscriptions(model);
            }

            @Override
            public ComponentTree.Node tree(Showcase.Model model) {
                return mount.tree(model);
            }
        };
    }
}

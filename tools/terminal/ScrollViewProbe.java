/* Development-only child: exercises ScrollView under an owned PTY/console with pyte. */
package io.github.dhoard.braxton.tools;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.effect.SubscriptionId;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.layout.Size;
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
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny two-pane application used only by the opt-in terminal verifier.
 *
 * <p>It mounts two independent {@link ScrollView}s side by side over deterministic content and
 * reports which one is focused on the last row, so a pyte terminal can assert the rows that reached
 * the screen after real key presses, a real resize and a real process teardown. Everything here is
 * the public MVU API: the application owns the content, the parent owns the allocation and the
 * focus decision, and each pane is a mount with its own state.</p>
 */
public final class ScrollViewProbe {

    private static final int CONTENT_ROWS = 40;
    private static final int PANE_COLS = 6;

    private ScrollViewProbe() {
    }

    record Model(ScrollView.Model log, ScrollView.Model trace, boolean logFocused, Size size) {}

    sealed interface Msg {
        record Log(ScrollView.Msg value) implements Msg {}

        record Trace(ScrollView.Msg value) implements Msg {}

        record ToggleFocus() implements Msg {}

        record Resized(Size size) implements Msg {}

        record Quit() implements Msg {}
    }

    /** Two panes, one allocation function, one focus decision. */
    private static final class TwoPanes implements Component<Model, Msg, Never> {

        private final Mount<Model, Msg, Never, ScrollView.Model, ScrollView.Msg, Never> log =
                Mount.of(
                        ComponentId.of("log"),
                        new ScrollView(new ScrollView.Config(1, false)),
                        ModelLens.of(Model::log,
                                (model, child) ->
                                        new Model(child, model.trace(), model.logFocused(),
                                                model.size())),
                        Msg.Log::new,
                        OutputRoute.toOutput(value -> value));

        private final Mount<Model, Msg, Never, ScrollView.Model, ScrollView.Msg, Never> trace =
                Mount.of(
                        ComponentId.of("trace"),
                        new ScrollView(new ScrollView.Config(1, false)),
                        ModelLens.of(Model::trace,
                                (model, child) ->
                                        new Model(model.log(), child, model.logFocused(),
                                                model.size())),
                        Msg.Trace::new,
                        OutputRoute.toOutput(value -> value));

        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            Size size = new Size(context.viewport().rows(), context.viewport().cols());
            Model model = new Model(log.initialize(context).model(),
                    trace.initialize(context).model(), true, size);
            model = log.update(model, new ScrollView.Msg.ContentChanged(rows("L"))).model();
            model = trace.update(model, new ScrollView.Msg.ContentChanged(rows("T"))).model();
            return Update.next(allocate(model));
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Log value ->
                        Update.next(allocate(log.update(model, value.value()).model()));
                case Msg.Trace value ->
                        Update.next(allocate(trace.update(model, value.value()).model()));
                case Msg.ToggleFocus toggle -> {
                    boolean focusLog = !model.logFocused();
                    yield Update.next(
                            new Model(model.log().withFocus(focusLog),
                                    model.trace().withFocus(!focusLog), focusLog,
                                    model.size()));
                }
                case Msg.Resized resized ->
                        Update.next(allocate(
                                new Model(model.log(), model.trace(), model.logFocused(),
                                        resized.size())));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            return Views.keyed(
                    List.of(Binding.of("tab", new Msg.ToggleFocus()),
                            Binding.of("ctrl+c", new Msg.Quit())),
                    Views.column(
                            Views.row(
                                    Views.fixed(PANE_COLS, log.view(model, context)),
                                    Views.fixed(PANE_COLS, trace.view(model, context))),
                            Views.text(focusRow(model))),
                    List.of());
        }

        @Override
        public ComponentTree children(Model model) {
            return ComponentTree.of(log.tree(model), trace.tree(model));
        }

        @Override
        public Subscription<Msg> subscriptions(Model model) {
            return Subscription.resize(SubscriptionId.of("resize"), Msg.Resized::new);
        }

        /** One allocation: both panes share the rows above the focus row. */
        private Model allocate(Model model) {
            int rows = paneRows(model.size());
            Model next = log.update(model, new ScrollView.Msg.ViewportChanged(rows)).model();
            return trace.update(next, new ScrollView.Msg.ViewportChanged(rows)).model();
        }

        private static int paneRows(Size size) {
            return Math.max(1, (size.rows() - 1) / 2);
        }

        private static String focusRow(Model model) {
            return (model.logFocused() ? "focus log " : "focus trace ")
                    + model.size().rows() + "x" + model.size().cols()
                    + " rows " + model.log().visibleRows();
        }
    }

    private static ScrollView.Content rows(String prefix) {
        List<StyledText> lines = new ArrayList<>(CONTENT_ROWS);
        for (int index = 0; index < CONTENT_ROWS; index++) {
            lines.add(StyledText.plain(prefix + String.format("%02d", index)));
        }
        return ScrollView.Content.of(lines);
    }

    public static void main(String[] args) {
        var result = Application.run(new TwoPanes());
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }
}

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

import io.github.dhoard.braxton.widget.BarChart;
import io.github.dhoard.braxton.widget.CommandPalette;
import io.github.dhoard.braxton.widget.Form;
import io.github.dhoard.braxton.widget.LogViewer;
import io.github.dhoard.braxton.widget.Menu;
import io.github.dhoard.braxton.widget.NotificationCenter;
import io.github.dhoard.braxton.widget.Option;
import io.github.dhoard.braxton.widget.Severity;
import io.github.dhoard.braxton.widget.Tree;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The gallery's fixtures: the content each specimen is shown with.
 *
 * <p>The data is here rather than in the scene because a fixture is read, not decided: an option
 * list, a menu, a tree, a command set, three notices, a log and the chart samples are literals that
 * describe what a reader sees, and the scene's own rules - which page, whose keyboard, what the last
 * gesture was - are the code that acts on them. Keeping the two apart is what lets the scene stay
 * inside the source-size contract without splitting its behavior.</p>
 *
 * <p>Every fixture is immutable and every list is immutable, so a widget cannot change the content of
 * another specimen's demonstration by holding on to it.</p>
 */
final class GalleryFixtures {

    static final List<Option> REVISIONS =
            List.of(Option.of("standard", "Standard"), Option.of("rich", "Rich"), Option.of("plain", "Plain"));

    static final List<Option> TAGS = List.of(
            Option.of("design", "Design"),
            Option.of("runtime", "Runtime"),
            Option.of("docs", "Docs"),
            Option.of("tests", "Tests"));

    static final List<Option> DENSITIES =
            List.of(Option.of("compact", "Compact"), Option.of("comfortable", "Comfortable"));

    static final List<Form.Field> FIELDS = List.of(Form.Field.of("name", "Name"), Form.Field.of("email", "Email"));

    static final Form.Validator VALIDATOR = values -> {
        List<Form.Error> errors = new ArrayList<>(2);
        if (values.text("name").isBlank()) {
            errors.add(new Form.Error("name", "a name is required"));
        }
        if (!values.text("email").contains("@")) {
            errors.add(new Form.Error("email", "an address needs an @"));
        }
        return List.copyOf(errors);
    };

    static final List<Tree.Node> TREE_NODES = List.of(
            Tree.Node.branch("ui", "UI kernel"),
            Tree.Node.branch("widgets", "Widgets"),
            Tree.Node.leaf("terminal", "Terminal"));

    static final Map<String, List<Tree.Node>> TREE_CHILDREN = children();

    static final List<Menu.Entry> MENU_ENTRIES = List.of(
            Menu.Item.of("open", "Open", "enter"),
            Menu.Item.of("rename", "Rename", "f2"),
            new Menu.Separator("gap"),
            Menu.Item.of("delete", "Delete", "del"));

    static final List<CommandPalette.Command> COMMANDS = List.of(
            CommandPalette.Command.of("theme", "Switch theme", "cycle the palette"),
            CommandPalette.Command.of("sort", "Sort items", "by name"),
            CommandPalette.Command.of("export", "Export", "write the file"));

    static final List<NotificationCenter.Notification> NOTICES = List.of(
            NotificationCenter.Notification.of("n1", Severity.SUCCESS, "every widget painted"),
            NotificationCenter.Notification.of("n2", Severity.WARNING, "the log is growing"),
            NotificationCenter.Notification.of("n3", Severity.INFO, "the gallery started"));

    static final List<Option> DIALOG_ACTIONS = List.of(Option.of("apply", "Apply"), Option.of("cancel", "Cancel"));

    static final List<Double> LINE_VALUES = List.of(3.0, 5.0, 4.0, 7.0, 6.0, 9.0, 8.0, 11.0);

    static final List<Double> RUNTIME_VALUES = List.of(2.0, 3.0, 3.0, 5.0, 4.0, 6.0, 7.0, 7.0);

    static final List<Double> SPARK_VALUES = List.of(1.0, 2.0, 1.5, 3.0, 2.5, 4.0, 3.5, 5.0);

    static final List<BarChart.Category> BARS = List.of(
            BarChart.Category.of("plan", "plan", 4.0),
            BarChart.Category.of("review", "review", 2.0),
            BarChart.Category.of("ship", "ship", 6.0));

    static final LocalDate DATE = LocalDate.of(2026, 3, 14);

    private GalleryFixtures() {}

    private static Map<String, List<Tree.Node>> children() {
        Map<String, List<Tree.Node>> children = new LinkedHashMap<>();
        children.put(
                "ui",
                List.of(
                        Tree.Node.leaf("ui.element", "Element kernel"),
                        Tree.Node.leaf("ui.focus", "Focus and routing")));
        children.put(
                "widgets",
                List.of(Tree.Node.leaf("widgets.charts", "Charts"), Tree.Node.leaf("widgets.forms", "Forms")));
        return Map.copyOf(children);
    }

    static List<Tree.Node> childrenOf(String id) {
        return TREE_CHILDREN.getOrDefault(id == null ? "" : id, List.of());
    }

    /**
     * The log's fixture: twenty lines, oldest first, on a cycle of the four severities.
     */
    static List<LogViewer.Entry> logLines() {
        Severity[] cycle = {Severity.INFO, Severity.SUCCESS, Severity.WARNING, Severity.ERROR};
        List<LogViewer.Entry> lines = new ArrayList<>(WidgetGalleryDemo.LOG_LINES);
        for (int line = 1; line <= WidgetGalleryDemo.LOG_LINES; line++) {
            Severity severity = cycle[(line - 1) % cycle.length];
            lines.add(LogViewer.Entry.of(
                    "l" + line,
                    severity,
                    "gallery line " + line + " " + severity.name().toLowerCase(Locale.ROOT)));
        }
        return List.copyOf(lines);
    }
}

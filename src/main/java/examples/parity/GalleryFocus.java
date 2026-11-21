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
import io.github.dhoard.braxton.widget.Calendar;
import io.github.dhoard.braxton.widget.Checkbox;
import io.github.dhoard.braxton.widget.CommandPalette;
import io.github.dhoard.braxton.widget.LineChart;
import io.github.dhoard.braxton.widget.LogViewer;
import io.github.dhoard.braxton.widget.Menu;
import io.github.dhoard.braxton.widget.MultiSelect;
import io.github.dhoard.braxton.widget.NotificationCenter;
import io.github.dhoard.braxton.widget.RadioGroup;
import io.github.dhoard.braxton.widget.Select;
import io.github.dhoard.braxton.widget.Sparkline;
import io.github.dhoard.braxton.widget.Tree;

/**
 * Focus, written where the kernel reads it: in each widget's own model.
 *
 * <p>D-14 and D-19 put focus in the models rather than in the tree - a widget answers a gesture only
 * when its own state says it is focused - so moving focus is one value change per widget and no tree
 * surgery at all. This is that one place. Each {@code move} overload returns the incoming instance
 * when the flag already matches, which keeps the runtime's unchanged-reference repaint rule
 * meaningful: a focus gesture that lands on the widget that already had it repaints nothing.</p>
 *
 * <p>Two specimens are absent on purpose. The scrollbar's and the split pane's state is derived at
 * paint time from the log's admitted window and the terminal's width, so their focus flags are part
 * of that derivation rather than of a stored model.</p>
 */
final class GalleryFocus {

    private GalleryFocus() {}

    /**
     * The model with focus written into every widget's own state.
     *
     * <p>Focus is model data rather than tree data (D-14, D-19): a widget answers a gesture only
     * when its own model says it is focused, so moving focus is one value change here and no
     * tree surgery. The scrollbar and the split pane are the two exceptions - their state is
     * derived from the log's window and the terminal's width, so their focus flags are part of
     * that derivation in the painter.</p>
     */
    static GalleryState focusOn(GalleryState model, String id) {
        return model.withFocus(id)
                .withRevision(move(model.revision(), "select".equals(id)))
                .withTags(move(model.tags(), "tags".equals(id)))
                .withRemember(move(model.remember(), "remember".equals(id)))
                .withDensity(move(model.density(), "density".equals(id)))
                .withName(model.name()
                        .withFocus(
                                "form".equals(id) && "name".equals(model.form().focused())))
                .withEmail(model.email()
                        .withFocus(
                                "form".equals(id) && "email".equals(model.form().focused())))
                .withTree(move(model.tree(), "tree".equals(id)))
                .withMenu(move(model.menu(), "menu".equals(id)))
                .withPalette(move(model.palette(), "palette".equals(id)))
                .withNotices(move(model.notices(), "notices".equals(id)))
                .withLog(move(model.log(), "log".equals(id)))
                .withDate(move(model.date(), "calendar".equals(id)))
                .withLine(move(model.line(), "line".equals(id)))
                .withBars(move(model.bars(), "bars".equals(id)))
                .withSpark(move(model.spark(), "spark".equals(id)));
    }

    private static Select.Model move(Select.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new Select.Model(
                        value.options(),
                        value.selected(),
                        value.open(),
                        value.highlighted(),
                        focused,
                        value.offset(),
                        value.rows());
    }

    private static MultiSelect.Model move(MultiSelect.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new MultiSelect.Model(
                        value.options(),
                        value.chosen(),
                        value.open(),
                        value.highlighted(),
                        focused,
                        value.offset(),
                        value.rows());
    }

    private static Checkbox.Model move(Checkbox.Model value, boolean focused) {
        return value.focused() == focused ? value : new Checkbox.Model(value.checked(), focused);
    }

    private static RadioGroup.Model move(RadioGroup.Model value, boolean focused) {
        return value.focused() == focused ? value : new RadioGroup.Model(value.options(), value.selected(), focused);
    }

    private static Tree.Model move(Tree.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new Tree.Model(
                        value.nodes(),
                        value.expanded(),
                        value.loaded(),
                        value.selected(),
                        focused,
                        value.offset(),
                        value.rows());
    }

    private static Menu.Model move(Menu.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new Menu.Model(value.entries(), value.highlighted(), focused, value.offset(), value.rows());
    }

    private static CommandPalette.Model move(CommandPalette.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new CommandPalette.Model(
                        value.commands(), value.query(), value.highlighted(), focused, value.offset(), value.rows());
    }

    private static NotificationCenter.Model move(NotificationCenter.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new NotificationCenter.Model(
                        value.notifications(), value.selected(), focused, value.offset(), value.rows());
    }

    private static LogViewer.Model move(LogViewer.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new LogViewer.Model(value.entries(), value.selected(), focused, value.offset(), value.rows());
    }

    private static Calendar.Model move(Calendar.Model value, boolean focused) {
        return value.focused() == focused ? value : new Calendar.Model(value.selected(), focused);
    }

    private static LineChart.Model move(LineChart.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new LineChart.Model(value.series(), value.range(), value.selected(), focused);
    }

    private static BarChart.Model move(BarChart.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new BarChart.Model(
                        value.categories(), value.range(), value.selected(), focused, value.offset(), value.rows());
    }

    private static Sparkline.Model move(Sparkline.Model value, boolean focused) {
        return value.focused() == focused
                ? value
                : new Sparkline.Model(value.values(), value.range(), value.selected(), focused);
    }
}

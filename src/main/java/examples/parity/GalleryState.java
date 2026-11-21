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
import io.github.dhoard.braxton.widget.Dialog;
import io.github.dhoard.braxton.widget.Form;
import io.github.dhoard.braxton.widget.Gauge;
import io.github.dhoard.braxton.widget.LineChart;
import io.github.dhoard.braxton.widget.LogViewer;
import io.github.dhoard.braxton.widget.Menu;
import io.github.dhoard.braxton.widget.MultiSelect;
import io.github.dhoard.braxton.widget.NotificationCenter;
import io.github.dhoard.braxton.widget.RadioGroup;
import io.github.dhoard.braxton.widget.Select;
import io.github.dhoard.braxton.widget.Sparkline;
import io.github.dhoard.braxton.widget.TextInput;
import io.github.dhoard.braxton.widget.Toast;
import io.github.dhoard.braxton.widget.Tree;
import java.util.List;
import java.util.Set;

/**
 * The gallery's state.
 *
 * <p>Every widget's model is a value here, which is what makes the scene a pure function of its
 * state: a page is painted from these models, a gesture arrives as that widget's own message and
 * the reduced model replaces the old one. {@code page}, {@code focus} and {@code last} are the
 * application's own facts - which page is shown, whose turn it is, and what the scene last did -
 * and {@code reports} counts the gestures the scene handled, so a repaint can be told from a
 * skipped frame.</p>
 *
 * One gallery state: the scene's own facts and every specimen's model.
 *
 * <p>It is a record rather than a mutable holder for the reason the whole runtime is: a view is a
 * pure function of the state, so a gesture's answer is a new value and the runtime's
 * unchanged-reference rule stays meaningful. The withers below exist because a record has no field
 * assignment; each returns a new state with one specimen replaced.</p>
 *
 * @param page the shown page, as an index into {@link WidgetGalleryDemo.Page}
 * @param focus the gallery name of the specimen that holds the keyboard
 * @param last what the scene last did, as its status row prints it
 * @param reports how many gestures the scene handled
 * @param splitFirst the cells the split's first pane takes
 * @param revision the select's state
 * @param tags the multi-select's state
 * @param remember the checkbox's state
 * @param density the radio group's state
 * @param form the form's state
 * @param name the form's name editor
 * @param email the form's email editor
 * @param tree the tree's state
 * @param menu the menu's state
 * @param palette the command palette's state
 * @param notices the notification center's state
 * @param log the log viewer's state
 * @param toast the toast's state
 * @param dialog the dialog's state
 * @param dialogOpen whether the dialog is on screen
 * @param date the calendar's state
 * @param line the line chart's state
 * @param bars the bar chart's state
 * @param spark the sparkline's state
 * @param gauge the gauge's state
 */
public record GalleryState(
        int page,
        String focus,
        String last,
        int reports,
        int splitFirst,
        Select.Model revision,
        MultiSelect.Model tags,
        Checkbox.Model remember,
        RadioGroup.Model density,
        Form.Model form,
        TextInput.Model name,
        TextInput.Model email,
        Tree.Model tree,
        Menu.Model menu,
        CommandPalette.Model palette,
        NotificationCenter.Model notices,
        LogViewer.Model log,
        Toast.Model toast,
        Dialog.Model dialog,
        boolean dialogOpen,
        Calendar.Model date,
        LineChart.Model line,
        BarChart.Model bars,
        Sparkline.Model spark,
        Gauge.Model gauge) {

    /**
     * Substitutes the defaults and clamps what a stale value could not hold.
     */
    public GalleryState {
        page = Math.max(0, Math.min(page, WidgetGalleryDemo.Page.values().length - 1));
        focus = focus == null || focus.isBlank()
                ? WidgetGalleryDemo.Page.values()[page].names().getFirst()
                : focus;
        last = last == null ? "none" : last;
        reports = Math.max(0, reports);
        splitFirst = Math.max(1, splitFirst);
    }

    /**
     * The gallery at its starting state.
     *
     * <p>Each widget starts where its own documentation says a fresh widget starts, except the
     * ones the gallery pre-fills so a reader sees content rather than an empty frame: a checked
     * tag, three commands, three notices, twenty log lines and a date.</p>
     *
     * @return the model
     */
    public static GalleryState starting() {
        GalleryState fresh = new GalleryState(
                0,
                "select",
                "none",
                0,
                26,
                new Select.Model(GalleryFixtures.REVISIONS, "standard", false, "standard", true, 0, 2),
                new MultiSelect.Model(GalleryFixtures.TAGS, List.of("design"), false, "design", true, 0, 2),
                new Checkbox.Model(false, true),
                new RadioGroup.Model(GalleryFixtures.DENSITIES, "", true),
                new Form.Model(GalleryFixtures.FIELDS, "name", List.of()),
                TextInput.Model.of("Ada"),
                TextInput.Model.of("ada.example"),
                new Tree.Model(
                        GalleryFixtures.TREE_NODES,
                        Set.of("ui"),
                        GalleryFixtures.TREE_CHILDREN,
                        "",
                        true,
                        0,
                        WidgetGalleryDemo.TREE_ROWS),
                new Menu.Model(GalleryFixtures.MENU_ENTRIES, "", true, 0, WidgetGalleryDemo.MENU_ROWS),
                new CommandPalette.Model(GalleryFixtures.COMMANDS, "", "", true, 0, WidgetGalleryDemo.PALETTE_ROWS),
                new NotificationCenter.Model(GalleryFixtures.NOTICES, "", true, 0, WidgetGalleryDemo.NOTICE_ROWS),
                new LogViewer.Model(GalleryFixtures.logLines(), "", true, 0, WidgetGalleryDemo.LOG_ROWS),
                Toast.Model.hidden(),
                new Dialog.Model(GalleryFixtures.DIALOG_ACTIONS, "", false),
                false,
                Calendar.Model.of(GalleryFixtures.DATE),
                new LineChart.Model(
                        List.of(
                                LineChart.Series.of("requests", GalleryFixtures.LINE_VALUES),
                                LineChart.Series.of("runtime", GalleryFixtures.RUNTIME_VALUES)),
                        null,
                        GalleryFixtures.LINE_VALUES.size() - 1,
                        true),
                new BarChart.Model(GalleryFixtures.BARS, null, "", true, 0, WidgetGalleryDemo.BAR_ROWS),
                new Sparkline.Model(GalleryFixtures.SPARK_VALUES, null, GalleryFixtures.SPARK_VALUES.size() - 1, true),
                Gauge.Model.of(0.62));
        // Every widget's model starts focused on its own terms, and the kernel routes a gesture to
        // one leaf: the gallery says once whose turn it is rather than leaving it to paint order.
        return GalleryFocus.focusOn(fresh, "select");
    }

    /**
     * The page this model shows.
     *
     * @return the page
     */
    public WidgetGalleryDemo.Page shown() {
        return WidgetGalleryDemo.Page.values()[page];
    }

    GalleryState withPage(int value) {
        return new GalleryState(
                value,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withFocus(String value) {
        return new GalleryState(
                page,
                value,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withLast(String value) {
        return new GalleryState(
                page,
                focus,
                value,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    /**
     * This model with the gesture count one higher.
     */
    GalleryState reported() {
        return new GalleryState(
                page,
                focus,
                last,
                reports + 1,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withSplitFirst(int value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                value,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withRevision(Select.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                value,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withTags(MultiSelect.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                value,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withRemember(Checkbox.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                value,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withDensity(RadioGroup.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                value,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withForm(Form.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                value,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withName(TextInput.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                value,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withEmail(TextInput.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                value,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withTree(Tree.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                value,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withMenu(Menu.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                value,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withPalette(CommandPalette.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                value,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withNotices(NotificationCenter.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                value,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withLog(LogViewer.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                value,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withToast(Toast.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                value,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withDialog(Dialog.Model value, boolean open) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                value,
                open,
                date,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withDate(Calendar.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                value,
                line,
                bars,
                spark,
                gauge);
    }

    GalleryState withLine(LineChart.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                value,
                bars,
                spark,
                gauge);
    }

    GalleryState withBars(BarChart.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                value,
                spark,
                gauge);
    }

    GalleryState withSpark(Sparkline.Model value) {
        return new GalleryState(
                page,
                focus,
                last,
                reports,
                splitFirst,
                revision,
                tags,
                remember,
                density,
                form,
                name,
                email,
                tree,
                menu,
                palette,
                notices,
                log,
                toast,
                dialog,
                dialogOpen,
                date,
                line,
                bars,
                value,
                gauge);
    }
}

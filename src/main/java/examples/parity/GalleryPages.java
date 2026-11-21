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

import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Scrollbar;
import io.github.dhoard.braxton.widget.SplitPane;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The gallery's four pages, as the element trees the kernel lays out.
 *
 * <p>A page is a pure function of the model and the widget instances the scene holds: it places the
 * specimens the page names, gives each one the room its own layout needs, and maps its messages into
 * the gallery's. Nothing here decides anything - no page reads a widget's state twice, and no page
 * keeps state of its own - which is why the painting can be split out of the scene without changing
 * a cell of what it paints.</p>
 *
 * <p>The widgets are taken from the scene rather than built here for the reason WP-4.1 settled: a
 * pointer capture, a drag and a double press belong to the widget instance that started them, so a
 * per-frame instance would lose the gesture between the press and the release.</p>
 */
final class GalleryPages {

    private GalleryPages() {}

    // ── the painted pages ──────────────────────────────────────────────

    /**
     * The shown page's specimens, as the elements the kernel lays out.
     */
    static Element<WidgetGalleryDemo.Msg> specimens(WidgetGalleryDemo.Scene scene, GalleryState model, int cols) {
        return switch (model.shown()) {
            case CHOICE -> choices(scene, model, cols);
            case NAVIGATE -> navigation(scene, model, cols);
            case MESSAGE -> messages(scene, model, cols);
            case DATA -> data(scene, model, cols);
        };
    }

    /**
     * The choice controls: three fields, a checkbox, a radio group and a form.
     *
     * <p>The form's editors are the scene's own {@link io.github.dhoard.braxton.widget.TextInput}s, placed by the form rather
     * than held by it - the kernel has one focused leaf, so a form that composes independently
     * focusable editors cannot also own the keyboard. The gallery owns it instead and gives it to
     * the editor the form's own focus names.</p>
     */
    private static Element<WidgetGalleryDemo.Msg> choices(WidgetGalleryDemo.Scene scene, GalleryState model, int cols) {
        int editorCols = Math.max(8, cols - 12);
        Map<String, Element<WidgetGalleryDemo.Msg>> fields = new LinkedHashMap<>();
        fields.put(
                "name",
                scene.name
                        .view(model.name(), new ViewContext(new Viewport(1, editorCols)))
                        .map(WidgetGalleryDemo.Msg.NameField::new));
        fields.put(
                "email",
                scene.email
                        .view(model.email(), new ViewContext(new Viewport(1, editorCols)))
                        .map(WidgetGalleryDemo.Msg.EmailField::new));
        Element<WidgetGalleryDemo.Msg> select =
                Elements.select(scene.revision, model.revision(), WidgetGalleryDemo.Msg.Revision::new);
        Element<WidgetGalleryDemo.Msg> tags =
                Elements.multiSelect(scene.tags, model.tags(), WidgetGalleryDemo.Msg.Tags::new);
        Element<WidgetGalleryDemo.Msg> remember =
                Elements.checkbox(scene.remember, model.remember(), WidgetGalleryDemo.Msg.Remember::new);
        Element<WidgetGalleryDemo.Msg> density =
                Elements.radioGroup(scene.density, model.density(), WidgetGalleryDemo.Msg.Density::new);
        Element<WidgetGalleryDemo.Msg> form =
                Elements.form(scene.form, model.form(), fields, WidgetGalleryDemo.Msg.FormMessage::new);
        return Elements.column(
                labeled("select", select, cols),
                labeled("multi-select", tags, cols),
                labeled("checkbox", remember, cols),
                caption("radio group"),
                Elements.fixed(GalleryFixtures.DENSITIES.size(), density),
                caption("form (ctrl+s submits)"),
                Elements.fixed(
                        GalleryFixtures.FIELDS.size() * 2
                                + model.form().errors().size(),
                        form));
    }

    /**
     * The navigation page: a split of a tree (with its scrollbar) and a menu, over a palette.
     *
     * <p>The bar's window is the log viewer's own admitted window, so the bar is a real
     * scrollbar for content that is not on this page: its position and its gesture are about the
     * log, and the log's selection is what the gesture becomes.</p>
     */
    private static Element<WidgetGalleryDemo.Msg> navigation(
            WidgetGalleryDemo.Scene scene, GalleryState model, int cols) {
        int total = Math.max(3, cols);
        Element<WidgetGalleryDemo.Msg> treePane = Elements.column(
                caption("tree + scrollbar"),
                Elements.fixed(
                        WidgetGalleryDemo.TREE_ROWS,
                        Elements.<WidgetGalleryDemo.Msg>row(
                                Elements.grow(Elements.tree(
                                        scene.tree, model.tree(), WidgetGalleryDemo.Msg.TreeMessage::new)),
                                Elements.scrollbar(scene.bar, barState(model))
                                        .map(WidgetGalleryDemo.Msg.ScrollMessage::new))));
        Element<WidgetGalleryDemo.Msg> menuPane = Elements.column(
                caption("menu"),
                Elements.fixed(
                        WidgetGalleryDemo.MENU_ROWS,
                        Elements.menu(scene.menu, model.menu(), WidgetGalleryDemo.Msg.MenuMessage::new)));
        SplitPane.Model splitState = new SplitPane.Model(
                SplitPane.Direction.ROW, model.splitFirst(), total, 8, "split".equals(model.focus()));
        Element<WidgetGalleryDemo.Msg> panes = Elements.splitPane(
                scene.split, splitState, treePane, menuPane, WidgetGalleryDemo.Msg.SplitMessage::new);
        return Elements.column(
                Elements.fixed(WidgetGalleryDemo.SPLIT_ROWS, panes),
                caption("command palette (type to filter)"),
                Elements.fixed(
                        WidgetGalleryDemo.PALETTE_ROWS,
                        Elements.commandPalette(
                                scene.palette, model.palette(), WidgetGalleryDemo.Msg.PaletteMessage::new)));
    }

    /**
     * The message surfaces: a notification center, a log with the bar, and a toast.
     */
    private static Element<WidgetGalleryDemo.Msg> messages(
            WidgetGalleryDemo.Scene scene, GalleryState model, int cols) {
        Element<WidgetGalleryDemo.Msg> notices =
                Elements.notificationCenter(scene.notices, model.notices(), WidgetGalleryDemo.Msg.NoticesMessage::new);
        Element<WidgetGalleryDemo.Msg> log =
                Elements.logViewer(scene.log, model.log(), WidgetGalleryDemo.Msg.LogMessage::new);
        return Elements.column(
                caption("notification center"),
                Elements.fixed(WidgetGalleryDemo.NOTICE_ROWS, notices),
                caption("log viewer"),
                Elements.fixed(WidgetGalleryDemo.LOG_ROWS, log),
                Elements.fixed(
                        1, Elements.toast(scene.toast, model.toast()).map(WidgetGalleryDemo.Msg.ToastMessage::new)));
    }

    /**
     * The data views: two charts, a sparkline and a gauge beside a date picker.
     */
    private static Element<WidgetGalleryDemo.Msg> data(WidgetGalleryDemo.Scene scene, GalleryState model, int cols) {
        int calendarCols = Math.min(22, Math.max(14, cols / 3));
        Element<WidgetGalleryDemo.Msg> charts = Elements.column(
                caption("line chart"),
                Elements.fixed(
                        WidgetGalleryDemo.LINE_ROWS,
                        Elements.lineChart(scene.line, model.line(), WidgetGalleryDemo.Msg.LineMessage::new)),
                caption("sparkline"),
                Elements.fixed(
                        1, Elements.sparkline(scene.spark, model.spark(), WidgetGalleryDemo.Msg.SparkMessage::new)),
                caption("gauge"),
                Elements.fixed(1, Elements.gauge(scene.gauge, model.gauge())));
        Element<WidgetGalleryDemo.Msg> picker = Elements.column(
                caption("calendar"),
                Elements.fixed(
                        8, Elements.calendar(scene.calendar, model.date(), WidgetGalleryDemo.Msg.DateMessage::new)));
        return Elements.column(
                Elements.<WidgetGalleryDemo.Msg>row(Elements.grow(charts), Elements.fixed(calendarCols, picker)),
                caption("bar chart"),
                Elements.fixed(
                        WidgetGalleryDemo.BAR_ROWS,
                        Elements.barChart(scene.bars, model.bars(), WidgetGalleryDemo.Msg.BarsMessage::new)));
    }

    /**
     * The dialog's layer: the box, its two buttons, and one row of body text.
     */
    static Element<WidgetGalleryDemo.Msg> dialogLayer(WidgetGalleryDemo.Scene scene, GalleryState model) {
        Element<WidgetGalleryDemo.Msg> content =
                Elements.text("the gallery applies the page's change", WidgetGalleryDemo.FIELD);
        return Elements.dialog(scene.dialog, model.dialog(), content, WidgetGalleryDemo.Msg.DialogMessage::new);
    }

    /**
     * The standalone bar's state, derived from the log it scrolls.
     *
     * <p>Both facts are the log's - which line is at the top of its window, how many lines there
     * are - so the bar cannot report a position the log is not at.</p>
     */
    private static Scrollbar.Model barState(GalleryState model) {
        return new Scrollbar.Model(
                model.log().offset(),
                WidgetGalleryDemo.LOG_ROWS,
                Math.max(WidgetGalleryDemo.LOG_ROWS, model.log().entries().size()),
                "bar".equals(model.focus()));
    }

    // ── the painted rows ───────────────────────────────────────────────

    private static Element<WidgetGalleryDemo.Msg> caption(String text) {
        return Elements.fixed(1, Elements.text(text, WidgetGalleryDemo.TITLE_STYLE));
    }

    /**
     * A specimen named at its left, taking the width that is left.
     *
     * <p>The label is a fixed column and the specimen grows into the rest: a row gives every child
     * a width of its own, so a specimen placed with {@code fixed} would be clipped to that width -
     * a field one column wide is not a field.</p>
     */
    private static Element<WidgetGalleryDemo.Msg> labeled(
            String name, Element<WidgetGalleryDemo.Msg> specimen, int cols) {
        return Elements.<WidgetGalleryDemo.Msg>row(
                Elements.fixed(Math.min(12, Math.max(4, cols / 6)), Elements.text(name, WidgetGalleryDemo.TITLE_STYLE)),
                Elements.grow(specimen));
    }
}

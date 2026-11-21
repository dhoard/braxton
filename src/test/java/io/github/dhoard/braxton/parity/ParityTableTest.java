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

package io.github.dhoard.braxton.parity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import examples.parity.TableDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.layout.Border;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.widget.DataTable;
import io.github.dhoard.braxton.widget.DataTableConfig;
import io.github.dhoard.braxton.widget.DataTableContent;
import io.github.dhoard.braxton.widget.DataTableModel;
import io.github.dhoard.braxton.widget.DataTableMsg;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Table;
import io.github.dhoard.braxton.widget.TableSelector;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Phase 13 acceptance: scrollable interactive tables, the static table's width budget and the demo.
 *
 * <p>Every assertion is against public behavior: the lines a window paints, the model a gesture
 * produces, the widths a schema resolves and what a routed key does to the demo scene. The component
 * a test drives is the shipped {@code DataTable}, and the scene a verifier runs is the demo's own, so
 * a mounted composition is the one an application writes rather than a second copy of it.</p>
 */
class ParityTableTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLS = 80;

    /**
     * The row the header occupies on the demo's 24-row screen: the two status rows come first.
     */
    private static final int DEMO_HEADER_ROW = TableDemo.STATUS_ROWS;

    /**
     * The cells one CJK glyph occupies under the pinned width policy.
     */
    private static final String WIDE = "\u671d";

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * The schema most tests use: a fixed id, an automatic name, a right-aligned count and a note.
     */
    private static List<DataTable.Column> columns() {
        return List.of(
                DataTable.Column.of("id", "id", 4),
                DataTable.Column.of("name", "name"),
                DataTable.Column.of("sown", "sown", 5).withAlignment(DataTable.Alignment.RIGHT),
                DataTable.Column.of("note", "note"));
    }

    /**
     * Content over {@link #columns()}, one {@code {id, name, sown, note}} row per argument.
     */
    private static DataTableContent content(String[]... rows) {
        List<DataTable.Row> values = new ArrayList<>(rows.length);
        for (String[] row : rows) {
            values.add(DataTable.Row.of(row[0], row));
        }
        return DataTableContent.of(columns(), values);
    }

    /**
     * Count generated rows named {@code row-0 ... row-(count-1)}.
     */
    private static DataTableContent content(int count) {
        String[][] rows = new String[count][];
        for (int index = 0; index < count; index++) {
            rows[index] = new String[] {"A-" + index, "row-" + index, Integer.toString(index), "note " + index};
        }
        return content(rows);
    }

    /**
     * The three-row content most tests use: widths [4, 7, 5, 6] and a 26-cell row.
     */
    private static DataTableContent three() {
        return content(
                new String[] {"A-1", "alpha", "1", "first"},
                new String[] {"B-2", "bravo", "22", "second"},
                new String[] {"C-3", "charlie", "333", "third"});
    }

    private static DataTableConfig config(DataTableContent content, int height, int width) {
        return DataTableConfig.defaults()
                .withContent(content)
                .withHeight(height)
                .withWidth(width);
    }

    private static DataTable table(DataTableConfig config) {
        return new DataTable(config);
    }

    private static DataTableModel model(DataTableConfig config) {
        return table(config).init(new InitContext(new Viewport(0, 0), null)).model();
    }

    private static DataTableModel apply(DataTable view, DataTableModel state, DataTableMsg message) {
        return view.update(state, message).model();
    }

    /**
     * The lines one window paints, as plain text.
     */
    private static List<String> painted(DataTableModel state, DataTableConfig config) {
        return DataTable.lines(state, config).stream()
                .map(line -> line.text().plainText())
                .toList();
    }

    /**
     * The body lines only, with the header left off.
     */
    private static List<String> body(DataTableModel state, DataTableConfig config) {
        List<String> lines = new ArrayList<>();
        for (DataTable.Line line : DataTable.lines(state, config)) {
            if (!line.header()) {
                lines.add(line.text().plainText());
            }
        }
        return List.copyOf(lines);
    }

    private static List<String> screen(DataTable view, DataTableModel state, int rows, int cols) {
        return rendered(view, state, rows, cols).lines();
    }

    private static ElementScreen rendered(DataTable view, DataTableModel state, int rows, int cols) {
        return ElementScreen.render(view.view(state, new ViewContext(new Viewport(rows, cols))), rows, cols);
    }

    private static InputEvent.KeyInput key(String spec) {
        return InputEvent.KeyInput.of(Key.parse(spec));
    }

    // ── R-01: schema, identity and selection modes ──────────────────────────

    @Test
    void anAutomaticColumnIsAsWideAsItsWidestHeaderOrCell() {
        DataTableConfig config = config(three(), 8, 80);

        // id is fixed at four; name is as wide as "charlie"; sown is fixed at five; note is as wide
        // as "second".
        assertThat(config.content().widths()).containsExactly(4, 7, 5, 6);
        assertThat(config.content().rowWidth()).isEqualTo(4 + 1 + 7 + 1 + 5 + 1 + 6 + 1);
        assertThat(DataTable.rowWidth(model(config))).isEqualTo(26);
        assertThat(config.content().columnStart(0)).isZero();
        assertThat(config.content().columnStart(1)).isEqualTo(5);
        assertThat(config.content().columnStart(2)).isEqualTo(13);
        assertThat(config.content().columnStart(3)).isEqualTo(19);
    }

    @Test
    void aFixedColumnIsExactlyAsWideAsItSays() {
        DataTableContent content = DataTableContent.of(
                List.of(DataTable.Column.of("narrow", "a long heading", 3)), List.of(DataTable.Row.of("r", "content")));
        DataTableConfig config = config(content, 4, 40);

        assertThat(content.widths()).containsExactly(3);
        assertThat(painted(model(config), config)).containsExactly("a l ", "con ");
    }

    @Test
    void aBoundedColumnObeysItsFloorItsPreferredWidthAndItsCap() {
        DataTable.Width bounded = DataTable.Width.of(4, 8, 12);
        DataTableContent content = DataTableContent.of(
                List.of(DataTable.Column.of("v", "v").withWidth(bounded)),
                List.of(DataTable.Row.of("short", "ab"), DataTable.Row.of("long", "x".repeat(40))));

        assertThat(DataTable.Width.of(6, 0, 20).resolve(2))
                .as("the floor of an automatic column")
                .isEqualTo(6);
        assertThat(bounded.resolve(2)).as("the preferred width is the floor").isEqualTo(8);
        assertThat(bounded.resolve(8)).as("the preferred width").isEqualTo(8);
        assertThat(bounded.resolve(40)).as("a cap").isEqualTo(12);
        assertThat(content.widths()).containsExactly(12);
    }

    @Test
    void anImpossibleWidthPolicyIsRejected() {
        assertThatThrownBy(() -> DataTable.Width.of(-1, 2, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum");
        assertThatThrownBy(() -> DataTable.Width.of(6, 2, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");
        assertThatThrownBy(() -> DataTable.Width.of(1, 9, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preferred");
    }

    @Test
    void twoColumnsMayNotShareAKeyAndTwoRowsMayNotShareAnId() {
        assertThatThrownBy(() -> DataTableContent.of(
                        List.of(DataTable.Column.of("k", "a"), DataTable.Column.of("k", "b")), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate column key: k");
        assertThatThrownBy(() ->
                        DataTableContent.of(columns(), List.of(DataTable.Row.of("r", "a"), DataTable.Row.of("r", "b"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate row id: r");
        DataTableContent content = three();
        assertThatThrownBy(() -> content.inserted(0, DataTable.Row.of("A-1", "again")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate row id: A-1");
    }

    @Test
    void aRowAndAColumnNeedAnIdentity() {
        assertThatThrownBy(() -> DataTable.Column.of("", "header"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column key");
        assertThatThrownBy(() -> DataTable.Row.of(" ", "cell"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("row id");
    }

    @Test
    void aRaggedRowPaintsEmptyValuesAndIgnoresExtraOnes() {
        DataTableContent content = DataTableContent.of(
                List.of(DataTable.Column.of("a", "a", 3), DataTable.Column.of("b", "b", 3)),
                List.of(DataTable.Row.of("short", "x"), DataTable.Row.of("long", "y", "z", "ignored")));
        DataTableConfig config = config(content, 4, 40);

        assertThat(painted(model(config), config)).containsExactly("a   b   ", "x       ", "y   z   ");
        assertThat(content.cell(0, 1)).as("a value the row does not hold").isEqualTo(StyledText.empty());
        assertThat(content.cell(1, 2)).as("a value the schema does not have").isEqualTo(StyledText.empty());
    }

    @Test
    void anEmptyTableHasNothingSelectedAndChoosesNothing() {
        DataTableConfig config = config(DataTableContent.empty(), 4, 40);
        DataTable view = table(config);
        DataTableModel empty = model(config);

        assertThat(empty.selection().present()).isFalse();
        assertThat(empty.selectedRow()).isEmpty();
        assertThat(empty.selectedColumn()).isEmpty();
        assertThat(empty.selectedValue()).isEmpty();
        assertThat(painted(empty, config)).isEmpty();
        assertThat(view.update(empty, new DataTableMsg.Choose()).outputs()).isEmpty();
        assertThat(apply(view, empty, new DataTableMsg.Down())).isSameAs(empty);
        assertThat(apply(view, empty, new DataTableMsg.Bottom())).isSameAs(empty);
    }

    @Test
    void aSelectionModeIsStateAndKeepsTheRowAndTheColumn() {
        DataTableConfig config = config(three(), 4, 80);
        DataTable view = table(config);
        DataTableModel state = apply(view, model(config), new DataTableMsg.Down());
        state = apply(view, state, new DataTableMsg.Right());

        assertThat(state.selection().mode())
                .as("a row selection has no column to move")
                .isEqualTo(DataTable.Mode.ROW);
        assertThat(state.selection().row()).isEqualTo(1);
        assertThat(state.selection().column()).isZero();

        state = apply(view, state, new DataTableMsg.ToggleMode());
        assertThat(state.selection().mode()).isEqualTo(DataTable.Mode.CELL);
        assertThat(state.selection().row()).as("the row the reader was on").isEqualTo(1);
        assertThat(state.selection().column())
                .as("the column a cell selection starts on")
                .isZero();

        state = apply(view, state, new DataTableMsg.Right());
        assertThat(state.selection().column()).isEqualTo(1);
        state = apply(view, state, new DataTableMsg.ToggleMode());
        assertThat(state.selection().mode()).isEqualTo(DataTable.Mode.ROW);
        assertThat(state.selection().column())
                .as("the column is kept for the next switch")
                .isEqualTo(1);
        assertThat(apply(view, state, new DataTableMsg.SetMode(DataTable.Mode.ROW)))
                .as("setting the mode it already has")
                .isSameAs(state);
    }

    @Test
    void aRowSelectionNamesTheRowAndACellSelectionNamesTheCell() {
        DataTableConfig config = config(three(), 4, 80);
        DataTable view = table(config);
        DataTableModel state = apply(view, model(config), new DataTableMsg.Down());

        assertThat(view.update(state, new DataTableMsg.Choose()).outputs())
                .containsExactly(new DataTable.Output.Chosen("B-2", 1, "", -1, StyledText.empty()));

        DataTableModel cell = apply(view, state, new DataTableMsg.SetMode(DataTable.Mode.CELL));
        cell = apply(view, cell, new DataTableMsg.Right());
        assertThat(view.update(cell, new DataTableMsg.Choose()).outputs())
                .containsExactly(new DataTable.Output.Chosen("B-2", 1, "name", 1, StyledText.plain("bravo")));
        assertThat(view.update(cell, new DataTableMsg.Cancel()).outputs())
                .containsExactly(new DataTable.Output.Cancelled());
        assertThat(view.update(cell, new DataTableMsg.Cancel()).model())
                .as("a report changes nothing")
                .isSameAs(cell);
    }

    @Test
    void theColumnGesturesDoNothingInARowSelection() {
        DataTableConfig config = config(three(), 4, 20);
        DataTable view = table(config);
        DataTableModel state = model(config);

        assertThat(apply(view, state, new DataTableMsg.Right())).isSameAs(state);
        assertThat(apply(view, state, new DataTableMsg.Left())).isSameAs(state);
        assertThat(DataTable.keys(state).action("right"))
                .hasValueSatisfying(action -> assertThat(action.enabled()).isFalse());
        assertThat(Help.render(DataTable.keys(state), true, 120).plainText())
                .doesNotContain("left a column")
                .contains("m row/cell")
                .contains("h pan left");
    }

    // ── R-02: the window and its navigation ─────────────────────────────────

    @Test
    void theHeaderStaysWhileTheBodyScrolls() {
        DataTableConfig config = config(content(20), 6, 80);
        DataTable view = table(config);
        DataTableModel state = model(config);
        String header = painted(state, config).get(0);

        DataTableModel scrolled = apply(view, state, new DataTableMsg.PageDown());

        assertThat(painted(scrolled, config).get(0))
                .as("the header after a page")
                .isEqualTo(header);
        assertThat(header).startsWith("id ").contains("name").contains("sown").contains("note");
        assertThat(body(state, config)).first().asString().startsWith("A-0 ");
        assertThat(body(scrolled, config)).first().asString().startsWith("A-5 ");
        assertThat(DataTable.top(scrolled, config)).isEqualTo(5);
    }

    @Test
    void aPageMovesExactlyOneWindow() {
        DataTableConfig config = config(content(20), 6, 80);
        DataTable view = table(config);
        DataTableModel state = model(config);

        assertThat(DataTable.bodyHeight(state, config)).isEqualTo(5);
        DataTableModel paged = apply(view, state, new DataTableMsg.PageDown());
        assertThat(paged.selection().row()).isEqualTo(5);
        assertThat(DataTable.top(paged, config)).isEqualTo(5);
        assertThat(apply(view, paged, new DataTableMsg.PageUp()).selection().row())
                .isZero();
    }

    @Test
    void aHalfPageMovesHalfAWindow() {
        DataTableConfig config = config(content(20), 7, 80);
        DataTable view = table(config);
        DataTableModel state = model(config);

        assertThat(DataTable.bodyHeight(state, config)).isEqualTo(6);
        assertThat(apply(view, state, new DataTableMsg.HalfPageDown())
                        .selection()
                        .row())
                .isEqualTo(3);
        assertThat(apply(view, state, new DataTableMsg.HalfPageUp()).selection().row())
                .isZero();
    }

    @Test
    void homeAndEndSelectTheFirstAndLastRowAndRevealThem() {
        DataTableConfig config = config(content(20), 6, 80);
        DataTable view = table(config);

        DataTableModel end = apply(view, model(config), new DataTableMsg.Bottom());
        assertThat(end.selection().row()).isEqualTo(19);
        assertThat(DataTable.top(end, config)).isEqualTo(15);
        assertThat(body(end, config)).last().asString().startsWith("A-19 ");

        DataTableModel home = apply(view, end, new DataTableMsg.Top());
        assertThat(home.selection().row()).isZero();
        assertThat(DataTable.top(home, config)).isZero();
    }

    @Test
    void aSelectionIsNeverLeftOutsideTheWindow() {
        DataTableConfig config = config(content(30), 5, 80);
        DataTable view = table(config);
        DataTableModel state = model(config);

        for (DataTableMsg message : List.of(
                new DataTableMsg.Up(),
                new DataTableMsg.Down(),
                new DataTableMsg.PageUp(),
                new DataTableMsg.PageDown(),
                new DataTableMsg.HalfPageUp(),
                new DataTableMsg.HalfPageDown(),
                new DataTableMsg.Top(),
                new DataTableMsg.Bottom())) {
            for (int step = 0; step < 7; step++) {
                state = apply(view, state, message);
                int top = DataTable.top(state, config);
                int body = DataTable.bodyHeight(state, config);
                assertThat(state.selection().row()).as("row after %s", message).isBetween(top, top + body - 1);
                assertThat(body(state, config)).as("window after %s", message).hasSize(body);
            }
        }
    }

    @Test
    void aMoveThatChangesNothingKeepsTheInstance() {
        DataTableConfig config = config(content(4), 6, 80);
        DataTable view = table(config);
        DataTableModel top = model(config);

        assertThat(apply(view, top, new DataTableMsg.Up())).isSameAs(top);
        assertThat(apply(view, top, new DataTableMsg.PanLeft(4))).isSameAs(top);
        assertThat(apply(view, top, new DataTableMsg.Resized(6, 80))).isSameAs(top);

        DataTableModel end = apply(view, top, new DataTableMsg.Bottom());
        assertThat(apply(view, end, new DataTableMsg.Down())).isSameAs(end);
        assertThat(apply(view, end, new DataTableMsg.Bottom())).isSameAs(end);
    }

    @Test
    void panningRevealsTheColumnsTheWindowClips() {
        DataTableConfig config = config(three(), 4, 20);
        DataTable view = table(config);
        DataTableModel state = model(config);

        assertThat(DataTable.rowWidth(state)).isEqualTo(26);
        assertThat(DataTable.firstColumn(state, config)).isZero();
        assertThat(painted(state, config).get(1)).isEqualTo("A-1  alpha       1 f");

        DataTableModel cell = apply(view, state, new DataTableMsg.SetMode(DataTable.Mode.CELL));
        for (int step = 0; step < 3; step++) {
            cell = apply(view, cell, new DataTableMsg.Right());
        }

        assertThat(cell.selection().column()).isEqualTo(3);
        assertThat(DataTable.firstColumn(cell, config))
                .as("the selected cell was panned into view")
                .isEqualTo(6);
        assertThat(painted(cell, config).get(1)).isEqualTo("lpha       1 first  ");
    }

    @Test
    void aCellSelectionStaysVisibleWhilePanning() {
        DataTableConfig config = config(three(), 4, 20);
        DataTable view = table(config);
        DataTableModel state = apply(view, model(config), new DataTableMsg.SetMode(DataTable.Mode.CELL));

        // The selection is the first column, so panning right can never take it off the window.
        DataTableModel panned = apply(view, state, new DataTableMsg.PanRight(100));
        assertThat(DataTable.firstColumn(panned, config)).isZero();
        assertThat(painted(panned, config).get(1)).startsWith("A-1");

        DataTableModel rowed = apply(view, state, new DataTableMsg.SetMode(DataTable.Mode.ROW));
        assertThat(DataTable.firstColumn(apply(view, rowed, new DataTableMsg.PanRight(100)), config))
                .as("a row selection may pan the whole row")
                .isEqualTo(6);
    }

    @Test
    void aResizeRecomputesTheWindowAndKeepsTheSelection() {
        DataTableConfig config = config(content(20), 6, 80);
        DataTable view = table(config);
        DataTableModel state = apply(view, model(config), new DataTableMsg.Bottom());

        DataTableModel smaller = apply(view, state, new DataTableMsg.Resized(4, 30));

        assertThat(DataTable.bodyHeight(smaller, config)).isEqualTo(3);
        assertThat(smaller.selection().row()).isEqualTo(19);
        assertThat(DataTable.top(smaller, config)).isEqualTo(17);
        assertThat(body(smaller, config)).hasSize(3).last().asString().startsWith("A-19 ");
        assertThat(painted(smaller, config))
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(30));
    }

    @Test
    void aHugePanCountClamps() {
        DataTableConfig config = config(three(), 4, 20);
        DataTable view = table(config);
        DataTableModel right = apply(view, model(config), new DataTableMsg.PanRight(Integer.MAX_VALUE));

        assertThat(DataTable.firstColumn(right, config)).isEqualTo(6);
        assertThat(DataTable.firstColumn(apply(view, right, new DataTableMsg.PanLeft(Integer.MAX_VALUE)), config))
                .isZero();
        DataTableModel state = model(config);
        assertThat(apply(view, state, new DataTableMsg.PanRight(0)))
                .as("a zero pan changes nothing")
                .isSameAs(state);
    }

    @Test
    void aZeroSizedTablePaintsAndScrollsNothing() {
        DataTableConfig config = config(three(), 0, 0);
        DataTable view = table(config);
        DataTableModel state = model(config);

        assertThat(painted(state, config)).isEmpty();
        assertThat(DataTable.bodyHeight(state, config)).isZero();
        assertThat(apply(view, state, new DataTableMsg.PageDown())).isSameAs(state);
        assertThat(screen(view, state, 4, 20))
                .allSatisfy(line -> assertThat(line).isBlank());
    }

    // ── R-03: widths, alignment, presentation and cost ──────────────────────

    @Test
    void aValueWiderThanItsColumnIsTruncatedOnAClusterBoundary() {
        DataTableContent content = DataTableContent.of(
                List.of(DataTable.Column.of("w", "w", 3)),
                List.of(DataTable.Row.of("wide", "a" + WIDE + "b"), DataTable.Row.of("only", WIDE + WIDE)));
        DataTableConfig config = config(content, 4, 20);

        assertThat(painted(model(config), config))
                .as("a wide glyph is dropped whole rather than half painted")
                .containsExactly("w   ", "a" + WIDE + " ", WIDE + "  ");
    }

    @Test
    void alignmentPlacesThePaddingOnTheNamedSide() {
        DataTableContent content = DataTableContent.of(
                List.of(
                        DataTable.Column.of("l", "l", 5),
                        DataTable.Column.of("c", "c", 5).withAlignment(DataTable.Alignment.CENTER),
                        DataTable.Column.of("r", "r", 5).withAlignment(DataTable.Alignment.RIGHT)),
                List.of(DataTable.Row.of("row", "ab", "ab", "ab")));
        DataTableConfig config = config(content, 4, 40);
        List<String> lines = painted(model(config), config);

        assertThat(lines.get(1).indexOf("ab")).as("left").isZero();
        assertThat(lines.get(1).indexOf("ab", 1)).as("centered").isEqualTo(7);
        assertThat(lines.get(1).indexOf("ab", 8)).as("right").isEqualTo(15);
        assertThat(lines.get(0).indexOf("c")).isEqualTo(8);
        assertThat(lines.get(0).indexOf("r")).isEqualTo(16);
    }

    @Test
    void theHeaderAndTheBodyShareOneGrid() {
        DataTableConfig config = config(three(), 6, 80);
        DataTableModel state = apply(table(config), model(config), new DataTableMsg.Down());
        String header = DataTable.lines(state, config).get(0).text().plainText();

        assertThat(header.indexOf("name")).isEqualTo(DataTable.columnStart(state, 1));
        assertThat(header.indexOf("note")).isEqualTo(DataTable.columnStart(state, 3));
        assertThat(header.indexOf("sown"))
                .as("a right-aligned heading ends on its column's last cell")
                .isEqualTo(DataTable.columnStart(state, 2) + 1);
        List<String> body = body(state, config);
        assertThat(body.get(0).substring(5, 12))
                .as("the name column of [%s]", body.get(0))
                .isEqualTo("alpha  ");
        assertThat(body.get(1).substring(5, 12))
                .as("the name column of [%s]", body.get(1))
                .isEqualTo("bravo  ");
        assertThat(body.get(2).substring(5, 12))
                .as("the name column of [%s]", body.get(2))
                .isEqualTo("charlie");
        for (String line : body) {
            assertThat(line.substring(DataTable.columnStart(state, 2) + 4, DataTable.columnStart(state, 2) + 5))
                    .as("the right-aligned count of [%s]", line)
                    .isNotEqualTo(" ");
        }
        assertThat(painted(state, config))
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isEqualTo(26));
    }

    @Test
    void aTabStopExpandsTheWayTheStaticTableExpandsIt() {
        DataTableContent content = DataTableContent.of(
                List.of(DataTable.Column.of("v", "v", 6)),
                List.of(DataTable.Row.of("tab", "a\tb"), DataTable.Row.of("next", "x")));
        DataTableConfig config = config(content, 4, 20);

        assertThat(painted(model(config), config)).containsExactly("v      ", "a   b  ", "x      ");
    }

    @Test
    void aBreakInsideACellBecomesOneSpaceAndKeepsItsSpans() {
        Style bold = Style.NONE.withBold();
        DataTableContent content = DataTableContent.of(
                List.of(DataTable.Column.of("v", "v", 8)),
                List.of(
                        DataTable.Row.of("lf", "one\ntwo"),
                        DataTable.Row.of("crlf", "one\r\ntwo"),
                        DataTable.Row.of(
                                "styled",
                                List.of(StyledText.concat(
                                        StyledText.of("one\n", bold), StyledText.of("two", Style.NONE))))));
        DataTableConfig config = config(content, 5, 20);

        // A bare CR never reaches a cell: StyledText drops an unrepresentable control character where
        // the span is made, so an application that reads raw text prepares it with LineBreaks first.
        assertThat(StyledText.plain("one\rtwo").plainText()).isEqualTo("onetwo");
        assertThat(painted(model(config), config)).containsExactly("v        ", "one two  ", "one two  ", "one two  ");
        StyledText styled = DataTable.lines(model(config), config).get(3).text();
        assertThat(styled.spans().get(0).style())
                .as("the styled run keeps its style")
                .isEqualTo(bold);
        assertThat(styled.plainText()).startsWith("one two");
    }

    @Test
    void aThemeStylesOnlyPaintedCells() {
        AtomicInteger cells = new AtomicInteger();
        AtomicInteger headers = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        DataTable.Theme theme = new DataTable.Theme() {
            @Override
            public Style header(DataTable.Column column) {
                headers.incrementAndGet();
                return Style.NONE.withBold();
            }

            @Override
            public Style row(DataTable.Row row, int index) {
                rows.incrementAndGet();
                return Style.NONE;
            }

            @Override
            public Style cell(DataTable.Cell cell) {
                cells.incrementAndGet();
                return Style.NONE.withItalic();
            }
        };
        DataTableConfig config = config(content(2000), 6, 80).withTheme(theme);
        DataTableModel state = model(config);

        List<DataTable.Line> lines = DataTable.lines(state, config);

        assertThat(headers).as("one call per painted header cell").hasValue(4);
        assertThat(rows).as("one call per painted body row").hasValue(DataTable.bodyHeight(state, config));
        assertThat(cells)
                .as("one call per painted body cell, not per cell in the content")
                .hasValue(DataTable.bodyHeight(state, config) * 4);
        assertThat(lines.get(0).text().spans().get(0).style().bold()).isTrue();
        assertThat(lines.get(1).text().spans().get(0).style().italic()).isTrue();
    }

    @Test
    void theSelectionIsMergedOverWhatTheThemePaints() {
        DataTableConfig config =
                config(three(), 6, 80).withTheme(DataTable.Theme.of(null, null, Style.NONE.withBold()));
        DataTableModel state = apply(table(config), model(config), new DataTableMsg.Down());

        List<DataTable.Line> lines = DataTable.lines(state, config);
        Style selected = lines.get(2).text().spans().get(0).style();
        Style plain = lines.get(1).text().spans().get(0).style();

        assertThat(selected.bold()).as("the theme's own style is kept").isTrue();
        assertThat(selected.invert()).as("the selection is merged over it").isTrue();
        assertThat(plain.bold()).isTrue();
        assertThat(plain.invert()).isFalse();
        assertThat(DataTable.lines(state.withFocus(false), config)
                        .get(2)
                        .text()
                        .spans()
                        .get(0)
                        .style()
                        .invert())
                .as("an unfocused table paints no selection")
                .isFalse();
    }

    @Test
    void aBaselessClusterAtTheHeadOfACellDoesNotMoveTheColumns() {
        DataTableContent content = DataTableContent.of(
                List.of(DataTable.Column.of("a", "a", 4), DataTable.Column.of("b", "b", 4)),
                List.of(
                        DataTable.Row.of("plain", "x", "y"),
                        DataTable.Row.of("baseless", "\u0301x", "y"),
                        DataTable.Row.of("widened", "\uFE0Fx", "y")));
        DataTableConfig config = config(content, 5, 40);
        List<String> lines = painted(model(config), config);

        assertThat(lines)
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isEqualTo(10));
        for (String line : lines.subList(1, lines.size())) {
            assertThat(line.indexOf('y')).as("the second column of [%s]", line).isEqualTo(5);
        }
    }

    @Test
    void largeContentIsMeasuredOnceAndPaintsOnlyTheWindow() {
        DataTableConfig config = config(content(5000), 8, 80);
        DataTable view = table(config);
        DataTableModel state = model(config);

        DataTableModel moved = state;
        for (int step = 0; step < 200; step++) {
            moved = apply(view, moved, new DataTableMsg.Down());
        }

        assertThat(moved.content())
                .as("a selection move keeps the content, and the widths it resolved")
                .isSameAs(state.content());
        assertThat(DataTable.lines(moved, config)).hasSize(DataTable.bodyHeight(moved, config) + 1);
    }

    @Test
    void theHeaderCanBeTurnedOff() {
        DataTableConfig config = config(three(), 6, 80).withHeader(false);
        DataTableModel state = model(config);

        assertThat(DataTable.bodyHeight(state, config)).isEqualTo(6);
        assertThat(DataTable.lines(state, config)).hasSize(3);
        assertThat(painted(state, config)).noneMatch(line -> line.startsWith("id "));
    }

    // ── R-03: the static table's width budget ───────────────────────────────

    @Test
    void aTableFitsItsColumnsIntoABudget() {
        Table table = Table.of()
                .buffer(1)
                .row(StyledText.plain("alpha"), StyledText.plain("bravo"), StyledText.plain("charlie"))
                .row(StyledText.plain("a"), StyledText.plain("b"), StyledText.plain("c"))
                .width(18);
        List<StyledText> lines = table.renderLines();

        assertThat(lines)
                .allSatisfy(line ->
                        assertThat(TextMeasure.columns(line.plainText())).isLessThanOrEqualTo(18));
        assertThat(lines.stream()
                        .map(line -> TextMeasure.columns(line.plainText()))
                        .distinct()
                        .toList())
                .as("one grid, whatever the budget")
                .hasSize(1);
        assertThat(table.columnWidths()).hasSize(3);
        assertThat(table.columnWidths()[2])
                .as("the widest column gave the cells up")
                .isLessThan(7);
    }

    @Test
    void aFittedCellIsTruncatedOnAClusterBoundary() {
        Table table = Table.of().buffer(0).row(StyledText.plain(WIDE.repeat(3))).width(5);
        String painted = table.renderLines().get(0).plainText();

        assertThat(TextMeasure.columns(painted)).isLessThanOrEqualTo(5);
        assertThat(painted.stripTrailing()).as("no half a wide glyph").isEqualTo(WIDE + WIDE);
    }

    @Test
    void aBorderedTableCountsItsFrameInTheBudget() {
        Table table = Table.of()
                .buffer(1)
                .border(Border.SINGLE)
                .row(StyledText.plain("alpha"), StyledText.plain("bravo"))
                .row(StyledText.plain("a"), StyledText.plain("b"))
                .width(14);
        List<String> lines = table.render().plainText().lines().toList();

        assertThat(lines).hasSize(4);
        assertThat(lines)
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(14));
        assertThat(lines.stream().map(TextMeasure::columns).distinct().toList())
                .as("the frame's own rows share one width")
                .hasSize(1);
    }

    @Test
    void aBudgetTooSmallForTheFrameIsTruncated() {
        Table table = Table.of()
                .buffer(1)
                .border(Border.SINGLE)
                .row(StyledText.plain("alpha"))
                .row(StyledText.plain("b"))
                .width(1);

        assertThat(table.render().plainText().lines().toList())
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(1));
    }

    @Test
    void aTableWithoutABudgetRendersExactlyAsBefore() {
        Table unbudgeted = Table.of()
                .buffer(2)
                .border(Border.SINGLE)
                .row(StyledText.plain("alpha"), StyledText.plain("bravo"))
                .row(StyledText.plain("a"), StyledText.plain("b"));
        Table zero = Table.of()
                .buffer(2)
                .border(Border.SINGLE)
                .row(StyledText.plain("alpha"), StyledText.plain("bravo"))
                .row(StyledText.plain("a"), StyledText.plain("b"))
                .width(0);

        assertThat(zero.columnWidths()).isEqualTo(unbudgeted.columnWidths());
        assertThat(zero.render().plainText()).isEqualTo(unbudgeted.render().plainText());
        assertThat(unbudgeted.columnWidths()).as("unchanged measurement").containsExactly(7, 7);
    }

    // ── R-04: the demo scene ────────────────────────────────────────────────

    private static ComponentDriver<TableDemo.Model, TableDemo.Msg, Never> demo() {
        return ComponentDriver.initialize(
                new TableDemo.Scene(), new InitContext(new Viewport(SCREEN_ROWS, SCREEN_COLS), null));
    }

    private static List<String> lines(ComponentDriver<TableDemo.Model, TableDemo.Msg, Never> driver) {
        return lines(driver, SCREEN_ROWS, SCREEN_COLS);
    }

    private static List<String> lines(
            ComponentDriver<TableDemo.Model, TableDemo.Msg, Never> driver, int rows, int cols) {
        return driver.screen(new ViewContext(new Viewport(rows, cols))).lines();
    }

    private static List<String> statuses(ComponentDriver<TableDemo.Model, TableDemo.Msg, Never> driver) {
        return lines(driver).subList(0, TableDemo.STATUS_ROWS);
    }

    private static Optional<TableDemo.Msg> route(
            ComponentDriver<TableDemo.Model, TableDemo.Msg, Never> driver, String keySpec) {
        return io.github.dhoard.braxton.testing.Routing.route(driver.component(), driver.model(), CONTEXT, key(keySpec))
                .stream()
                .findFirst();
    }

    /**
     * Presses one key through the scene's own view, then delivers the messages it dispatches.
     */
    private static void press(ComponentDriver<TableDemo.Model, TableDemo.Msg, Never> driver, String keySpec) {
        TableDemo.Msg message =
                route(driver, keySpec).orElseThrow(() -> new AssertionError("no message for " + keySpec));
        driver.send(message);
        for (TableDemo.Msg dispatched : dispatched(driver.latest().command())) {
            driver.send(dispatched);
        }
    }

    /**
     * The messages a command dispatches, in order, which is how a routed output reaches its parent.
     */
    private static List<TableDemo.Msg> dispatched(Command<TableDemo.Msg> command) {
        List<TableDemo.Msg> messages = new ArrayList<>();
        collect(command, messages);
        return messages;
    }

    private static void collect(Command<TableDemo.Msg> command, List<TableDemo.Msg> messages) {
        switch (command) {
            case Command.Dispatch<TableDemo.Msg> dispatch -> messages.add(dispatch.message());
            case Command.Batch<TableDemo.Msg> batch -> batch.commands().forEach(each -> collect(each, messages));
            case Command.Scoped<TableDemo.Msg> scoped -> collect(scoped.command(), messages);
            default -> {
                // Nothing else dispatches a message.
            }
        }
    }

    @Test
    void theDemoStartsOnItsFirstRowWithAFixedHeader() {
        var driver = demo();
        List<String> screen = lines(driver);

        assertThat(statuses(driver).get(0)).isEqualTo("mode=row row=1/18 cell=- top=0 pan=0 last=started");
        assertThat(statuses(driver).get(1))
                .as("the scene reports the window the table really has")
                .startsWith("edits=0 added=0 removed=0 rows=18 body=15")
                .endsWith("chosen=-");
        assertThat(screen.get(DEMO_HEADER_ROW)).startsWith("id   name        family      sown note");
        assertThat(screen.get(DEMO_HEADER_ROW + 1))
                .as("the body's id column lines up with the header")
                .startsWith("S-01 ");
        assertThat(TableDemo.tableRows(SCREEN_ROWS)).as("the table's own rows").isEqualTo(16);
    }

    @Test
    void theDemoScrollsAndReportsItsWindow() {
        var driver = demo();
        press(driver, "down");
        press(driver, "down");

        assertThat(statuses(driver).get(0)).startsWith("mode=row row=3/18 cell=- top=0 pan=0 last=down");

        press(driver, "end");
        assertThat(statuses(driver).get(0)).startsWith("mode=row row=18/18 cell=- top=3");
        assertThat(lines(driver).get(DEMO_HEADER_ROW + 15))
                .as("the last row is on screen under a header that has not moved")
                .startsWith("S-18 ");

        press(driver, "home");
        assertThat(statuses(driver).get(0)).startsWith("mode=row row=1/18 cell=- top=0 pan=0 last=top");
    }

    @Test
    void theDemoTogglesTheSelectionModeAndPans() {
        var driver = demo();
        press(driver, "down");
        press(driver, "m");

        assertThat(statuses(driver).get(0)).startsWith("mode=cell row=2/18 cell=id top=0 pan=0");
        press(driver, "right");
        assertThat(statuses(driver).get(0)).startsWith("mode=cell row=2/18 cell=name");
        press(driver, "right");
        press(driver, "right");
        press(driver, "right");
        assertThat(statuses(driver).get(0))
                .as("a cell selection pans the window its column does not fit in")
                .contains("cell=note")
                .doesNotContain(" pan=0 ");

        press(driver, "m");
        press(driver, "l");
        press(driver, "l");
        assertThat(statuses(driver).get(0))
                .as("a row selection may pan the whole row")
                .contains("mode=row")
                .contains("cell=-")
                .doesNotContain(" pan=0 ");
    }

    @Test
    void theDemoEditsAddsAndRemovesRowsByIdentity() {
        var driver = demo();
        press(driver, "down");
        press(driver, "e");

        assertThat(statuses(driver).get(1)).startsWith("edits=1 added=0 removed=0");
        assertThat(statuses(driver).get(0)).contains("row=2/18").contains("last=edited-S-02");
        assertThat(lines(driver).get(DEMO_HEADER_ROW + 2))
                .as("the edited row is painted where the selection is")
                .startsWith("S-02 cardoon*");

        press(driver, "a");
        assertThat(statuses(driver).get(0)).contains("row=2/19").contains("last=added-1");
        assertThat(statuses(driver).get(1)).contains("added=1").contains("rows=19");
        assertThat(lines(driver).get(DEMO_HEADER_ROW + 3))
                .as("the new row's id")
                .startsWith("S-91 ");

        press(driver, "x");
        assertThat(statuses(driver).get(0)).contains("row=2/18").contains("last=removed-S-02");
        assertThat(statuses(driver).get(1)).contains("removed=1").contains("rows=18");

        // Every row removed leaves a table with nothing selected and nothing to edit.
        for (int step = 0; step < 18; step++) {
            press(driver, "x");
        }
        assertThat(statuses(driver).get(0)).startsWith("mode=row row=0/0 cell=-");
        assertThat(statuses(driver).get(1)).contains("removed=19").contains("rows=0");
        press(driver, "e");
        assertThat(statuses(driver).get(0)).contains("last=empty");
    }

    @Test
    void theDemoChoosesTheSelectionAndReportsIt() {
        var driver = demo();
        press(driver, "down");
        press(driver, "enter");

        assertThat(statuses(driver).get(1)).endsWith("chosen=S-02");

        press(driver, "m");
        press(driver, "right");
        press(driver, "enter");
        assertThat(statuses(driver).get(1))
                .as("a cell selection names its column and value")
                .endsWith("chosen=S-02/name=cardoon");
    }

    @Test
    void theDemoResizesAndKeepsItsAlignment() {
        var driver = demo();
        lines(driver);
        driver.send(new TableDemo.Msg.Resized(16, 60));
        List<String> smaller = lines(driver, 16, 60);

        assertThat(statuses(driver).get(0)).startsWith("mode=row row=1/18");
        assertThat(statuses(driver).get(1))
                .as("the window loses rows, not data")
                .contains("rows=18 body=7");
        assertThat(smaller.get(DEMO_HEADER_ROW)).startsWith("id   name        family      sown note");
        assertThat(smaller.get(DEMO_HEADER_ROW + 1)).startsWith("S-01 ");
        assertThat(smaller.get(DEMO_HEADER_ROW + 8))
                .as("the summary follows the table")
                .startsWith("\u250c");
        assertThat(smaller)
                .allSatisfy(line -> assertThat(TextMeasure.columns(line)).isLessThanOrEqualTo(60));
    }

    @Test
    void theDemoRegistersItsOwnKeysAndHelp() {
        var driver = demo();
        List<String> screen = lines(driver);

        assertThat(Help.render(TableDemo.keys(), false, SCREEN_COLS).plainText())
                .contains("a add a row")
                .contains("e edit the name")
                .contains("x delete the row");
        assertThat(Help.render(DataTable.keys(driver.model().table()), true, 120)
                        .plainText())
                .contains("row/cell")
                .contains("choose");
        assertThat(screen.get(screen.size() - 2))
                .contains("up a row")
                .contains("page down")
                .contains("pan left");
        assertThat(screen.get(screen.size() - 1)).contains("a add a row").contains("ctrl+c quit");
        assertThat(route(driver, "q")).isEmpty();
        assertThat(route(driver, "ctrl+c")).contains(new TableDemo.Msg.Quit());
    }

    @Test
    void thePackagedDemoHasAPublicMain() throws Exception {
        Method main = TableDemo.class.getMethod("main", String[].class);

        assertThat(Modifier.isPublic(main.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
        assertThat(TableDemo.run()).isNotNull();
    }

    // ── R-05: preserved behavior, purity and repaired state ────────────────

    @Test
    void paintingIsPureAndRepeatable() {
        DataTableConfig config = config(three(), 6, 20);
        DataTable view = table(config);
        DataTableModel state = apply(view, model(config), new DataTableMsg.Bottom());

        List<DataTable.Line> first = DataTable.lines(state, config);
        List<DataTable.Line> second = DataTable.lines(state, config);

        assertThat(second).isEqualTo(first);
        assertThat(view.view(state, CONTEXT)).isNotNull();
        assertThat(apply(view, state, new DataTableMsg.Cancel()))
                .as("a report is a no-op")
                .isSameAs(state);
    }

    @Test
    void theModelRepairsAnImpossibleState() {
        DataTableModel state =
                new DataTableModel(three(), new DataTable.Selection(DataTable.Mode.CELL, 99, 99), -4, -4, -1, -1, true);

        assertThat(state.selection().row()).isEqualTo(2);
        assertThat(state.selection().column()).isEqualTo(3);
        assertThat(state.top()).isZero();
        assertThat(state.firstColumn()).isZero();
        assertThat(state.height()).isZero();
        assertThat(state.width()).isZero();
        assertThat(new DataTableModel(null, null, 0, 0, 0, 0, true).content())
                .as("a missing content is empty content")
                .isEqualTo(DataTableContent.empty());
    }

    @Test
    void theLegacyTableAndSelectorStillBehave() {
        Table legacy = Table.of().buffer(2);
        legacy.row(StyledText.plain("a"), StyledText.plain("b"));
        legacy.row(StyledText.plain("cc"), StyledText.plain("d"));
        assertThat(legacy.columnWidths()).containsExactly(4, 3);
        assertThat(legacy.renderLines().stream().map(StyledText::plainText).toList())
                .containsExactly("a   b  ", "cc  d  ");

        TableSelector selector = new TableSelector(TableSelector.Config.defaults());
        TableSelector.Model state = TableSelector.Model.of(List.of("a", "b"), List.of(List.of("1", "2")));
        Update<TableSelector.Model, TableSelector.Msg, TableSelector.Output> chosen =
                selector.update(state, new TableSelector.Msg.Confirm());
        assertThat(chosen.outputs()).containsExactly(new TableSelector.Output.Chosen(0, 0, "1"));
        assertThat(selector.keys().bindings()).isNotEmpty();
    }

    @Test
    void theRenderedScreenIsTheWindowItDescribes() {
        DataTableConfig config = config(three(), 4, 20);
        DataTable view = table(config);
        DataTableModel state = apply(view, model(config), new DataTableMsg.Down());
        List<String> painted = painted(state, config);
        List<String> screen = screen(view, state, 6, 20);

        assertThat(painted).hasSize(4);
        assertThat(screen.get(0)).isEqualTo(painted.get(0));
        assertThat(screen.get(1)).isEqualTo(painted.get(1));
        assertThat(rendered(view, state, 6, 20).lines()).hasSize(6);
        assertThat(DataTable.keys(state).bindings())
                .isNotEmpty()
                .extracting(Binding::key)
                .isNotEmpty();
    }
}

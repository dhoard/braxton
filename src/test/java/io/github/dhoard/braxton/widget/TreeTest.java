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

package io.github.dhoard.braxton.widget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.effect.Subscription;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The tree: its lazy children, its rows, its keys, its pointer gestures and its window.
 */
class TreeTest {

    private static final List<Tree.Node> NODES =
            List.of(Tree.Node.branch("src", "src"), Tree.Node.leaf("readme", "README"));

    private static final Map<String, List<Tree.Node>> CHILDREN = children();

    private static final Tree.Appearance APPEARANCE = Tree.Appearance.defaults();

    private final RecordingLoader loader = new RecordingLoader(CHILDREN);

    private final Tree tree = new Tree(loader, Tree.Config.of(NODES).withRows(6));

    @Test
    void aBranchShowsItsChildrenOnlyWhileItIsOpen() {
        Tree.Model closed =
                tree.init(new InitContext(new Viewport(6, 20), null)).model();

        assertThat(Tree.visibleRows(closed).stream().map(row -> row.node().id()))
                .containsExactly("src", "readme");
        assertThat(ElementScreen.render(tree.element(closed), 3, 20).lines())
                .containsExactly("\u25B8 src", "  README", "");

        Tree.Model opened = apply(tree, closed, new Tree.Msg.Toggle("src"));

        assertThat(Tree.visibleRows(opened).stream().map(Tree.Row::depth)).containsExactly(0, 1, 1, 0);
        assertThat(ElementScreen.render(tree.element(opened), 4, 20).lines())
                .containsExactly("\u25BE src", "  \u25B8 main", "    test", "  README");
        assertThat(loader.asked()).containsExactly("src");

        // Closing it again hides the rows and keeps the children the loader already gave.
        Tree.Model closedAgain = apply(tree, opened, new Tree.Msg.Toggle("src"));
        assertThat(loader.asked()).containsExactly("src");
        assertThat(Tree.visibleRows(closedAgain).stream().map(row -> row.node().id()))
                .containsExactly("src", "readme");
    }

    @Test
    void aBranchOpenedTwiceAsksForItsChildrenOnce() {
        Tree.Model model = tree.init(new InitContext(new Viewport(6, 20), null)).model();

        model = apply(tree, model, new Tree.Msg.Expand());
        model = apply(tree, model, new Tree.Msg.Collapse());
        model = apply(tree, model, new Tree.Msg.Expand());

        assertThat(loader.asked()).containsExactly("src");
        assertThat(model.isExpanded("src")).isTrue();
    }

    @Test
    void aBranchThatStartsOpenIsLoadedDuringInitialization() {
        Tree tree =
                new Tree(loader, Tree.Config.of(NODES).withRows(6).withExpandedIds(java.util.Set.of("src", "main")));

        Tree.Model model = tree.init(new InitContext(new Viewport(6, 20), null)).model();

        // The configured branches are loaded up front, so the rows they hold are painted without a
        // gesture - and the branch inside `src` is loaded by the same walk.
        assertThat(loader.asked()).containsExactly("src", "main");
        assertThat(Tree.visibleRows(model).stream().map(row -> row.node().id()))
                .containsExactly("src", "main", "java", "test", "readme");
    }

    @Test
    void theSelectionWalksTheVisibleRowsAndSkipsTheHiddenOnes() {
        Tree.Model model = open(tree, "src", "main");

        assertThat(apply(tree, model, new Tree.Msg.Down()).selected()).isEqualTo("main");
        assertThat(apply(tree, model, new Tree.Msg.End()).selected()).isEqualTo("readme");
        assertThat(apply(tree, model, new Tree.Msg.Home()).selected()).isEqualTo("src");

        // One step from `src` reaches `main`, because the hidden rows of a closed branch are not
        // steps: the selection moves through what is painted.
        Tree.Model closed = apply(tree, model, new Tree.Msg.Toggle("src"));
        assertThat(apply(tree, closed, new Tree.Msg.Down()).selected()).isEqualTo("readme");

        // A clamped step is still an answer, so the application's own binding never runs underneath.
        Tree.Model first = apply(tree, closed, new Tree.Msg.Home());
        assertThat(apply(tree, first, new Tree.Msg.Up())).isEqualTo(first);
    }

    @Test
    void collapsingABranchHandsTheSelectionToTheInnermostVisibleBranch() {
        Tree.Model model = open(tree, "src", "main");
        model = apply(tree, model, new Tree.Msg.Select("java"));

        assertThat(model.selected()).isEqualTo("java");

        Tree.Model collapsedSrc = apply(tree, model, new Tree.Msg.Toggle("src"));
        assertThat(collapsedSrc.selected()).isEqualTo("src");
        assertThat(Tree.visibleRows(collapsedSrc).stream().map(row -> row.node().id()))
                .containsExactly("src", "readme");

        Tree.Model collapsedMain = apply(tree, model, new Tree.Msg.Toggle("main"));
        assertThat(collapsedMain.selected()).isEqualTo("main");
    }

    @Test
    void rightStepsIntoAnOpenBranchAndLeftStepsOutOfALeaf() {
        Tree.Model closed =
                tree.init(new InitContext(new Viewport(6, 20), null)).model();

        // Right opens a closed branch and leaves the selection on it.
        Tree.Model opened = apply(tree, closed, new Tree.Msg.Expand());
        assertThat(opened.selected()).isEqualTo("src");
        assertThat(opened.isExpanded("src")).isTrue();

        // Right again steps into it.
        Tree.Model inside = apply(tree, opened, new Tree.Msg.Expand());
        assertThat(inside.selected()).isEqualTo("main");

        // Left on a branch that is not open steps out to its parent, and left on a leaf does the
        // same; left on an open branch closes it and leaves the selection where it is.
        assertThat(apply(tree, inside, new Tree.Msg.Collapse()).selected()).isEqualTo("src");
        Tree.Model leaf = apply(tree, inside, new Tree.Msg.Down());
        assertThat(leaf.selected()).isEqualTo("test");
        assertThat(apply(tree, leaf, new Tree.Msg.Collapse()).selected()).isEqualTo("src");
        Tree.Model closedAgain = apply(tree, opened, new Tree.Msg.Collapse());
        assertThat(closedAgain.selected()).isEqualTo("src");
        assertThat(closedAgain.isExpanded("src")).isFalse();
    }

    @Test
    void enterTogglesABranchAndChoosesALeaf() {
        Tree.Model model = tree.init(new InitContext(new Viewport(6, 20), null)).model();

        Update<Tree.Model, Tree.Msg, Tree.Output> toggled = tree.update(model, new Tree.Msg.Activate());
        assertThat(toggled.model().isExpanded("src")).isTrue();
        assertThat(toggled.outputs()).isEmpty();

        Tree.Model onLeaf = apply(tree, toggled.model(), new Tree.Msg.Select("readme"));
        Update<Tree.Model, Tree.Msg, Tree.Output> chosen = tree.update(onLeaf, new Tree.Msg.Activate());

        assertThat(chosen.outputs())
                .containsExactly(new Tree.Output.Chosen("readme", Tree.Node.leaf("readme", "README")));
    }

    @Test
    void aPressSelectsARowAMarkerTogglesAndASecondPressActivates() {
        Tree.Model model = open(tree, "src");

        // Row 2 is `test`, a leaf: a press selects it.
        assertThat(pointer(tree, model, press(2, 4))).containsExactly(new Tree.Msg.Select("test"));

        // Row 1 is `main`, a branch: a press on its marker column toggles it.
        assertThat(pointer(tree, model, press(1, 2))).containsExactly(new Tree.Msg.Toggle("main"));
        // A press on the same row outside the marker column selects it instead.
        assertThat(pointer(tree, model, press(1, 8))).containsExactly(new Tree.Msg.Select("main"));

        // A second press on the selected row is an activation.
        Tree.Model onTest = apply(tree, model, new Tree.Msg.Select("test"));
        assertThat(pointer(tree, onTest, press(2, 4).withClickCount(2))).containsExactly(new Tree.Msg.Activate());

        // A press below the last row selects nothing.
        assertThat(pointer(tree, model, press(4, 0))).isEmpty();
    }

    @Test
    void theWheelStepsTheSelection() {
        Tree.Model model = open(tree, "src");

        assertThat(pointer(tree, model, wheel(PointerButton.WHEEL_DOWN))).containsExactly(new Tree.Msg.Down());
        assertThat(pointer(tree, model, wheel(PointerButton.WHEEL_UP))).containsExactly(new Tree.Msg.Up());
        assertThat(pointer(tree, model, wheel(PointerButton.WHEEL_LEFT))).isEmpty();
        assertThat(pointer(tree, model, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 1, 0)))
                .isEmpty();
    }

    @Test
    void theWindowFollowsTheSelectionAndAResize() {
        List<Tree.Node> many = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            many.add(Tree.Node.leaf("n" + index, "node " + index));
        }
        Tree tall = new Tree(Tree.Children.none(), Tree.Config.of(many));
        Tree.Model model = tall.init(new InitContext(new Viewport(4, 20), null)).model();

        assertThat(model.rows()).isEqualTo(4);
        assertThat(model.offset()).isZero();

        // A page key moves by the window, and the window follows so the row stays painted.
        Tree.Model page = apply(tall, model, new Tree.Msg.PageDown());
        assertThat(page.selected()).isEqualTo("n4");
        assertThat(page.offset()).isEqualTo(1);
        assertThat(ElementScreen.render(tall.element(page), 4, 20).line(0)).isEqualTo("  node 1");

        // A resize admits the same selection and clamps the window into the rows it has.
        Tree.Model resized = apply(tall, page, new Tree.Msg.RowsChanged(12));
        assertThat(resized.offset()).isZero();
        assertThat(resized.selected()).isEqualTo("n4");

        // A window of no rows paints nothing and still names a selection.
        Tree.Model empty = apply(tall, resized, new Tree.Msg.RowsChanged(0));
        assertThat(ElementScreen.render(tall.element(empty), 2, 20).lines()).containsExactly("", "");
        assertThat(empty.selected()).isEqualTo("n4");
        assertThat(apply(tall, empty, new Tree.Msg.PageDown()).selected()).isEqualTo("n5");
    }

    @Test
    void rowsArePaintedInTheThemesStylesAndAFocusedSelectionCarriesTheFocusStyle() {
        Tree.Model selected = apply(tree, open(tree, "src"), new Tree.Msg.Select("test"));

        ElementScreen dark = ElementScreen.render(tree.element(selected), 4, 12, Themes.DARK);
        ElementScreen light = ElementScreen.render(tree.element(selected), 4, 12, Themes.LIGHT);

        // The marker column of the branch and the label of a plain row are the theme's own styles.
        assertThat(dark.styles(0).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(3).get(0)).isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(3).get(2)).isEqualTo(Themes.DARK.style(ThemeToken.TEXT));
        assertThat(light.styles(3).get(2)).isEqualTo(Themes.LIGHT.style(ThemeToken.TEXT));

        // The selected row is a whole-row background, and focus colors what it paints on top.
        Style selectedBackground = Themes.DARK.style(ThemeToken.SELECTED);
        Style focused =
                selectedBackground.merge(Themes.DARK.style(ThemeToken.TEXT).merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(2).get(8))
                .as("a selection is the whole row, not only the cells its label covers")
                .isEqualTo(selectedBackground);
        assertThat(dark.styles(2).get(4))
                .as("focus colors what sits on the selection")
                .isEqualTo(focused);
        assertThat(dark.styles(2).get(1))
                .as("the marker carries the focus style over the selection's background")
                .isEqualTo(selectedBackground.merge(
                        Themes.DARK.style(ThemeToken.MUTED).merge(Themes.DARK.style(ThemeToken.FOCUS))));

        // An unfocused tree still shows what it selected, without the focus style.
        ElementScreen unfocused = ElementScreen.render(tree.element(selected.withFocus(false)), 4, 12, Themes.DARK);
        assertThat(unfocused.styles(2).get(8))
                .as("the selection is shown without the focus style")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
    }

    @Test
    void aNarrowTreeClipsItsRowsAndAFlatOnePaintsNothing() {
        Tree.Model model = open(tree, "src", "main");

        // Six columns hold the indent and the marker but only part of the label.
        assertThat(ElementScreen.render(tree.element(model), 4, 6).lines())
                .containsExactly("\u25BE src", "  \u25BE ma", "", "    te");
        assertThat(ElementScreen.render(tree.element(model), 0, 0).lines()).isEmpty();
        assertThat(ElementScreen.render(tree.element(model), 2, 1).lines()).containsExactly("\u25BE", "");

        // An empty tree paints no row and offers no focus stop, and its keys still answer.
        Tree.Model nothing = Tree.Model.of(List.of());
        assertThat(ElementScreen.render(tree.element(nothing), 3, 10).lines()).containsExactly("", "", "");
        assertThat(tree.canFocus(nothing)).isFalse();
        assertThat(tree.canFocus(model)).isTrue();
        assertThat(apply(tree, nothing, new Tree.Msg.Down())).isEqualTo(nothing);
    }

    @Test
    void aLabelWithABreakStaysOnOneRow() {
        Tree tree = Tree.of(List.of(Tree.Node.leaf("a", "first\nsecond")));

        assertThat(ElementScreen.render(
                                tree.element(Tree.Model.of(List.of(Tree.Node.leaf("a", "first\nsecond")))), 2, 20)
                        .line(0))
                .isEqualTo("  first second");
    }

    @Test
    void selectRevealsThroughLoadedBranchesAndRevealWalksAPath() {
        Tree.Model model = open(tree, "src", "main");

        // A node in a closed branch is revealed by opening the branches above it.
        Tree.Model closed = apply(tree, model, new Tree.Msg.Toggle("src"));
        assertThat(apply(tree, closed, new Tree.Msg.Select("java")).selected()).isEqualTo("java");
        assertThat(apply(tree, closed, new Tree.Msg.Select("java")).isExpanded("src"))
                .isTrue();

        // A node the tree has not loaded is not reachable by id alone, but a path loads it.
        RecordingLoader lazy = new RecordingLoader(CHILDREN);
        Tree tree = new Tree(lazy, Tree.Config.of(NODES).withRows(6));
        Tree.Model root = tree.init(new InitContext(new Viewport(6, 20), null)).model();
        assertThat(apply(tree, root, new Tree.Msg.Select("java"))).isEqualTo(root);
        Tree.Model opened = apply(tree, root, new Tree.Msg.Reveal(List.of("src", "main", "java")));
        assertThat(opened.selected()).isEqualTo("java");
        assertThat(opened.isExpanded("main")).isTrue();
        assertThat(lazy.asked()).containsExactly("src", "main");

        // A path the tree cannot follow changes nothing at all.
        assertThat(apply(tree, root, new Tree.Msg.Reveal(List.of("src", "nowhere"))))
                .isEqualTo(root);
        assertThat(apply(tree, root, new Tree.Msg.Reveal(List.of("readme", "src"))))
                .isEqualTo(root);
        assertThat(apply(tree, root, new Tree.Msg.Reveal(List.of()))).isEqualTo(root);
    }

    @Test
    void theContentCanBeReplacedOrRefreshedOneBranchAtATime() {
        Tree.Model model = open(tree, "src");

        Tree.Model replaced = apply(tree, model, new Tree.Msg.Replace(List.of(Tree.Node.leaf("other", "other"))));
        assertThat(Tree.visibleRows(replaced).stream().map(row -> row.node().id()))
                .containsExactly("other");
        assertThat(replaced.selected()).isEqualTo("other");

        Tree.Model refreshed =
                apply(tree, model, new Tree.Msg.ChildrenChanged("src", List.of(Tree.Node.leaf("new", "new"))));
        assertThat(Tree.visibleRows(refreshed).stream().map(row -> row.node().id()))
                .containsExactly("src", "new", "readme");
        assertThat(apply(tree, refreshed, new Tree.Msg.ChildrenChanged("nope", List.of())))
                .isEqualTo(refreshed);
    }

    @Test
    void theTreeIsAFocusStopOnlyWhileItHasARowAndAnswersItsOwnKeys() {
        Tree.Model model = open(tree, "src");
        Element<Tree.Msg> root = Elements.tree(tree, model, message -> message);

        assertThat(tree.focused(model)).isTrue();
        assertThat(tree.focused(model.withFocus(false))).isFalse();
        assertThat(ElementScreen.render(root, 4, 10).focusables()).containsExactly(new Rect(0, 0, 4, 10));

        List<Tree.Msg> produced = new ArrayList<>(1);
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.DOWN), produced::add))
                .isTrue();
        assertThat(produced).containsExactly(new Tree.Msg.Down());

        produced.clear();
        assertThat(InteractionRouter.key(root, InputEvent.KeyInput.of(Key.of("x")), produced::add))
                .isFalse();
        assertThat(produced).isEmpty();
    }

    @Test
    void theKeysAreTheMapHelpRenders() {
        assertThat(tree.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("up", "down", "pageup", "pagedown", "home", "end", "expand", "collapse", "activate");
        assertThat(tree.keys().actionFor(Key.LEFT)).map(KeyAction::message).contains(new Tree.Msg.Collapse());
        assertThat(tree.keys().actionFor(Key.ENTER)).map(KeyAction::message).contains(new Tree.Msg.Activate());
    }

    @Test
    void aRepeatedIdIsRejected() {
        assertThatThrownBy(() -> Tree.Model.of(List.of(Tree.Node.leaf("a", "a"), Tree.Node.leaf("a", "again"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate node id: a");

        Tree conflicting = new Tree(
                Tree.Children.from(Map.of("src", List.of(Tree.Node.leaf("readme", "again")))), Tree.Config.of(NODES));
        Tree.Model model =
                conflicting.init(new InitContext(new Viewport(4, 20), null)).model();
        assertThatThrownBy(() -> conflicting.update(model, new Tree.Msg.Toggle("src")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate node id: readme");
    }

    @Test
    void theWindowStartsFromTheTerminalLessWhatTheApplicationPaintsAndFollowsAResize() {
        Tree tree = new Tree(loader, Tree.Config.of(NODES).withReservedRows(3).withRows(4));

        assertThat(tree.init(new InitContext(new Viewport(10, 20), null))
                        .model()
                        .rows())
                .isEqualTo(7);
        assertThat(tree.init(new InitContext(null, null)).model().rows())
                .as("a headless start uses the configured height")
                .isEqualTo(4);
        assertThat(Subscription.leaves(tree.subscriptions(Tree.Model.of(NODES))))
                .as("one source: the terminal's size")
                .hasSize(1);
        assertThat(Tree.Model.of(NODES).withRows(-2).rows()).isZero();
    }

    @Test
    void aGestureTheModelCannotActOnChangesNothing() {
        Tree.Model model = open(tree, "src");

        // An unknown id, a leaf, and an activation of an empty tree are answers that change nothing,
        // so the model keeps its instance and the runtime's unchanged-reference rule stays honest.
        assertThat(apply(tree, model, new Tree.Msg.Select("nowhere"))).isSameAs(model);
        assertThat(apply(tree, model, new Tree.Msg.Select(null))).isSameAs(model);
        assertThat(apply(tree, model, new Tree.Msg.Select(" "))).isSameAs(model);
        assertThat(apply(tree, model, new Tree.Msg.Toggle("test"))).isSameAs(model);
        assertThat(apply(tree, model, new Tree.Msg.Toggle(null))).isSameAs(model);
        assertThat(apply(tree, model, new Tree.Msg.Toggle("nowhere"))).isSameAs(model);
        assertThat(apply(tree, model, new Tree.Msg.Replace(null)).nodes())
                .as("a replaced content is the content given")
                .isEmpty();

        Tree.Model nothing = Tree.Model.of(List.of());
        assertThat(tree.update(nothing, new Tree.Msg.Activate()).outputs()).isEmpty();
        assertThat(apply(tree, nothing, new Tree.Msg.Expand())).isSameAs(nothing);
        assertThat(apply(tree, nothing, new Tree.Msg.Collapse())).isSameAs(nothing);
        assertThat(apply(tree, nothing, new Tree.Msg.Home())).isSameAs(nothing);
    }

    @Test
    void aPressIsResolvedThroughTheWindowAndTheTreesOwnMessagesTravel() {
        List<Tree.Node> many = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            many.add(Tree.Node.leaf("n" + index, "node " + index));
        }
        Tree tree = new Tree(Tree.Children.none(), Tree.Config.of(many).withRows(4));
        Tree.Model model = tree.init(new InitContext(new Viewport(4, 20), null)).model();
        model = apply(tree, model, new Tree.Msg.PageDown());

        // The window starts at row 1, so the first painted row is `n1`.
        assertThat(model.offset()).isEqualTo(1);
        assertThat(pointer(tree, model, press(0, 0))).containsExactly(new Tree.Msg.Select("n1"));

        Element<String> mapped = Elements.tree(tree, model, message -> "tree:" + message);
        List<String> produced = new ArrayList<>(1);
        InteractionRouter.pointer(
                ElementTree.layout(mapped, new Rect(0, 0, 4, 20)),
                InteractionRouter.Interaction.NONE,
                press(3, 0),
                produced::add);

        assertThat(produced).containsExactly("tree:" + new Tree.Msg.Select("n4"));
    }

    @Test
    void aConfiguredTreeAnswersForItsOwnDefaults() {
        Tree empty = new Tree(Tree.Children.none());
        Tree configured = new Tree(Tree.Children.from(null), Tree.Config.of(null));

        assertThat(empty.config().nodes()).isEmpty();
        assertThat(empty.config().rows()).isEqualTo(Tree.DEFAULT_ROWS);
        assertThat(configured.config().appearance()).isEqualTo(Tree.Appearance.defaults());
        assertThat(empty.children()).isNotNull();
        assertThat(Tree.Children.none().children("anything")).isEmpty();
        assertThat(Tree.of(NODES).config().nodes()).containsExactlyElementsOf(NODES);
        assertThat(Tree.Config.of(NODES).withNodes(List.of()).nodes()).isEmpty();
        assertThat(Tree.Config.of(NODES).withRows(3).rows()).isEqualTo(3);
        assertThat(Tree.Config.of(NODES)
                        .withExpandedIds(java.util.Set.of("src"))
                        .expandedIds())
                .containsExactly("src");
        assertThat(Tree.Config.of(NODES).withAppearance(null).appearance()).isEqualTo(Tree.Appearance.defaults());
        assertThat(Tree.Model.of(NODES)
                        .withNodes(List.of(Tree.Node.leaf("x", "x")))
                        .selected())
                .isEqualTo("x");
    }

    @Test
    void aNodeWithoutAnIdOrAGlyphThatIsNotOneCellIsRejected() {
        assertThatThrownBy(() -> Tree.Node.leaf(" ", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("node id is required");
        assertThatThrownBy(() -> new Tree.Appearance("\uD83D\uDE00", null, null, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one column");
        assertThatThrownBy(() -> new Tree.Appearance(null, null, null, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("indent");
        assertThatThrownBy(() -> new Tree.Config(List.of(), -1, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
        assertThatThrownBy(() -> new Tree.Config(List.of(), 0, -1, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservedRows");
        assertThat(Tree.Model.of(List.of()).withRows(-1).rows())
                .as("a window is a value to clamp, not a definition to reject")
                .isZero();
        assertThatThrownBy(() -> new Tree.Row(Tree.Node.leaf("a", "a"), -1, false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void theAppearanceChoosesTheGlyphsTheIndentAndTheLabelColumn() {
        Tree custom = new Tree(
                Tree.Children.from(CHILDREN),
                Tree.Config.of(NODES)
                        .withRows(6)
                        .withAppearance(Tree.Appearance.defaults()
                                .withExpanded("+")
                                .withCollapsed("-")
                                .withLeaf("")
                                .withIndent(3)));
        Tree.Model model = apply(
                custom, custom.init(new InitContext(new Viewport(6, 20), null)).model(), new Tree.Msg.Toggle("src"));

        assertThat(ElementScreen.render(custom.element(model), 3, 20).lines())
                .containsExactly("+ src", "   - main", "    test");
        assertThat(Tree.visibleRows(model).get(1).labelColumn(custom.config().appearance()))
                .isEqualTo(5);
        assertThat(Tree.Appearance.defaults().withLeaf("\u00b7").leaf()).isEqualTo("\u00b7");
        assertThat(new Tree.Appearance(null, null, null, Tree.INDENT)).isEqualTo(Tree.Appearance.defaults());
        assertThat(Tree.Node.branch("a", "a").withExpandable(false).expandable())
                .isFalse();
        assertThat(Tree.Node.leaf("a", StyledText.plain("a"))
                        .withLabel(null)
                        .label()
                        .isEmpty())
                .isTrue();
    }

    @Test
    void aFrameOfTheTreeIsItsRowsWithTheSelectedRowMarked() {
        Tree.Model model = open(tree, "src", "main");
        model = apply(tree, model, new Tree.Msg.Select("java"));

        // The snapshot a test asserts: one row per node, the selection where the user left it.
        assertThat(ElementScreen.render(tree.element(model), 5, 14).lines())
                .containsExactly("\u25BE src", "  \u25BE main", "      App.java", "    test", "  README");
        assertThat(Tree.visibleRows(model))
                .containsExactly(
                        new Tree.Row(Tree.Node.branch("src", "src"), 0, true, false, true),
                        new Tree.Row(Tree.Node.branch("main", "main"), 1, true, false, true),
                        new Tree.Row(Tree.Node.leaf("java", "App.java"), 2, false, true, true),
                        new Tree.Row(Tree.Node.leaf("test", "test"), 1, false, false, true),
                        new Tree.Row(Tree.Node.leaf("readme", "README"), 0, false, false, true));
        assertThat(Tree.visibleRows(model).get(2).labelColumn(APPEARANCE)).isEqualTo(6);
        assertThat(Tree.Model.of(NODES).node("src")).contains(Tree.Node.branch("src", "src"));
        assertThat(Tree.Model.of(NODES).node(null)).isEmpty();
    }

    @Test
    void theTreePlacedWithItsSiblingsKeepsItsOwnGesture() {
        Tree left = new Tree(loader, Tree.Config.of(NODES).withRows(4));
        Tree right = new Tree(loader, Tree.Config.of(NODES).withRows(8));
        Element<String> root = Elements.row(
                Elements.grow(Elements.tree(left, Tree.Model.of(NODES), message -> "left:" + message)),
                Elements.grow(Elements.tree(right, Tree.Model.of(NODES), message -> "right:" + message)));
        ElementTree.Placement<String> placement = ElementTree.layout(root, new Rect(0, 0, 8, 20));
        List<String> produced = new ArrayList<>(1);

        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, press(1, 11), produced::add);

        // The press landed in the right half, so it is that tree's row - the two placements do not
        // share a row space.
        assertThat(produced).containsExactly("right:" + new Tree.Msg.Select("readme"));
    }

    private static Tree.Model open(Tree tree, String... path) {
        Tree.Model model = tree.init(new InitContext(new Viewport(6, 20), null)).model();
        for (String id : path) {
            model = apply(tree, model, new Tree.Msg.Toggle(id));
        }
        return model;
    }

    private static Tree.Model apply(Tree tree, Tree.Model model, Tree.Msg message) {
        return tree.update(model, message).model();
    }

    private static List<Tree.Msg> pointer(Tree tree, Tree.Model model, PointerEvent report) {
        List<Tree.Msg> produced = new ArrayList<>(1);
        tree.onPointer(report, new Rect(0, 0, 6, 20), model, produced::add);
        return produced;
    }

    private static PointerEvent press(int row, int col) {
        return PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col);
    }

    private static PointerEvent wheel(PointerButton direction) {
        return PointerEvent.wheel(direction, Modifiers.NONE, 0, 0);
    }

    private static Map<String, List<Tree.Node>> children() {
        Map<String, List<Tree.Node>> children = new LinkedHashMap<>();
        children.put("src", List.of(Tree.Node.branch("main", "main"), Tree.Node.leaf("test", "test")));
        children.put("main", List.of(Tree.Node.leaf("java", "App.java")));
        return Map.copyOf(children);
    }

    /**
     * A loader that records which branches were asked for, so laziness is observable.
     */
    private static final class RecordingLoader implements Tree.Children {

        private final Map<String, List<Tree.Node>> children;
        private final List<String> asked = new ArrayList<>();

        RecordingLoader(Map<String, List<Tree.Node>> children) {
            this.children = children;
        }

        @Override
        public List<Tree.Node> children(String id) {
            asked.add(id);
            return children.getOrDefault(id, List.of());
        }

        List<String> asked() {
            return List.copyOf(asked);
        }
    }
}

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
import static org.junit.jupiter.api.Assertions.assertTimeout;

import examples.parity.PaginatorDemo;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.layout.Viewport;
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
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.widget.Elements;
import io.github.dhoard.braxton.widget.Help;
import io.github.dhoard.braxton.widget.Paginator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 06 acceptance: page state, slicing bounds, dot and numeric indicators, and a paged list.
 *
 * <p>Every assertion is against public behavior: the model a message produces, the bounds it
 * reports, the indicator a width paints, and what a routed key does to the demo scene. The
 * installed paginator is the demo's own component, so the mounted integration is the shipped
 * composition rather than a second copy of it.</p>
 */
class ParityPaginatorTest {

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(24, 80));

    private static final int SCREEN_ROWS = 24;

    private static final int SCREEN_COLS = 80;

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Paginator pager() {
        return new Paginator(Paginator.Config.defaults());
    }

    private static Paginator.Config wideDots(int maxDots) {
        return Paginator.Config.defaults()
                .withAppearance(Paginator.Appearance.dots().withMaxDots(maxDots));
    }

    private static Paginator.Model after(Paginator.Model state, Paginator.Msg message) {
        return pager().update(state, message).model();
    }

    private static Paginator.Model after(Paginator.Model state, Paginator.Config config, Paginator.Msg message) {
        return new Paginator(config).update(state, message).model();
    }

    private static String indicator(Paginator.Model state, int width) {
        return Paginator.render(state, Paginator.Config.defaults(), width).plainText();
    }

    private static String indicator(Paginator.Model state, Paginator.Config config, int width) {
        return Paginator.render(state, config, width).plainText();
    }

    private static InputEvent.KeyInput key(String spec) {
        Key key = Key.parse(spec);
        return InputEvent.KeyInput.of(key, key.name());
    }

    private static List<String> painted(Element<?> view) {
        return ElementScreen.render(view, SCREEN_ROWS, SCREEN_COLS).lines();
    }

    private static List<String> painted(Element<?> view, int rows, int cols) {
        return ElementScreen.render(view, rows, cols).lines();
    }

    /**
     * The expanded help of a key map: one line per group, holding every action in the map.
     */
    private static List<String> help(KeyMap<?> keys) {
        return painted(Help.element(keys, Help.Config.defaults(), true, 200), 8, 200).stream()
                .filter(line -> !line.isBlank())
                .toList();
    }

    /**
     * The demo the packaged launch runs, driven as its own component.
     */
    private static PaginatorDemo.Scene demo() {
        return new PaginatorDemo.Scene();
    }

    private static PaginatorDemo.Model start() {
        return demo().init(new InitContext(null, null)).model();
    }

    private static PaginatorDemo.Model apply(PaginatorDemo.Model model, PaginatorDemo.Msg message) {
        return demo().update(model, message).model();
    }

    /**
     * What one key does to the demo's screen, as the scene's own message.
     */
    private static Optional<PaginatorDemo.Msg> route(PaginatorDemo.Model model, String keyName) {
        return io.github.dhoard.braxton.testing.Routing.route(demo(), model, CONTEXT, key(keyName)).stream()
                .findFirst();
    }

    private static PaginatorDemo.Model send(PaginatorDemo.Model model, String keyName) {
        return apply(model, route(model, keyName).orElseThrow(() -> new AssertionError("no message for " + keyName)));
    }

    /**
     * The status rows the demo paints, so a test can assert the scene without decoding styles.
     */
    private static String headline(PaginatorDemo.Model model) {
        return painted(demo().view(model, CONTEXT)).get(0).strip();
    }

    // ── R-01: page state and navigation ─────────────────────────────────────

    @Test
    void emptyContentHasNoPagesAndAnEmptyRange() {
        Paginator.Model state = Paginator.Model.of(0, 10);
        assertThat(state.totalPages()).isZero();
        assertThat(state.hasPages()).isFalse();
        assertThat(state.page()).isZero();
        assertThat(state.bounds()).isEqualTo(new Paginator.Bounds(0, 0));
        assertThat(state.bounds().isEmpty()).isTrue();
        assertThat(state.isFirstPage()).as("one position, no pages").isTrue();
        assertThat(state.isLastPage()).isTrue();
        assertThat(state.hasNext()).isFalse();
        assertThat(state.hasPrevious()).isFalse();
    }

    @Test
    void anExactMultipleHasNoPartiallyFilledPage() {
        Paginator.Model state = Paginator.Model.of(20, 10);
        assertThat(state.totalPages()).isEqualTo(2);
        assertThat(state.bounds()).isEqualTo(new Paginator.Bounds(0, 10));
        Paginator.Model second = after(state, new Paginator.Msg.Next());
        assertThat(second.page()).isEqualTo(1);
        assertThat(second.bounds()).isEqualTo(new Paginator.Bounds(10, 20));
        assertThat(second.bounds().length()).isEqualTo(10);
        assertThat(second.isLastPage()).isTrue();
    }

    @Test
    void aFinalShortPageCoversTheRemainder() {
        Paginator.Model state = after(Paginator.Model.of(25, 10), new Paginator.Msg.Last());
        assertThat(state.totalPages()).isEqualTo(3);
        assertThat(state.page()).isEqualTo(2);
        assertThat(state.bounds()).isEqualTo(new Paginator.Bounds(20, 25));
        assertThat(state.bounds().length()).isEqualTo(5);
    }

    @Test
    void aPageSizeMustBePositiveWhereverItIsMade() {
        assertThatThrownBy(() -> new Paginator.Config(0, false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("perPage must be positive");
        assertThatThrownBy(() -> new Paginator.Config(-1, false, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Paginator.Model(0, 10, 0, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("perPage must be positive");
        assertThatThrownBy(() -> new Paginator.Msg.PageSizeChanged(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("perPage must be positive");
    }

    @Test
    void aNegativeCountIsNoItems() {
        Paginator.Model state = new Paginator.Model(3, -7, 10, true);
        assertThat(state.itemCount()).isZero();
        assertThat(state.page()).isZero();
        assertThat(state.bounds().isEmpty()).isTrue();
    }

    @Test
    void aCountChangeClampsThePage() {
        Paginator.Model state = after(Paginator.Model.of(25, 10), new Paginator.Msg.Last());
        assertThat(state.totalPages()).isEqualTo(3);

        Paginator.Model smaller = after(state, new Paginator.Msg.ItemCountChanged(15));
        assertThat(smaller.totalPages()).isEqualTo(2);
        assertThat(smaller.page()).as("the third page no longer exists").isEqualTo(1);
        assertThat(smaller.bounds()).isEqualTo(new Paginator.Bounds(10, 15));

        Paginator.Model empty = after(smaller, new Paginator.Msg.ItemCountChanged(0));
        assertThat(empty.totalPages()).isZero();
        assertThat(empty.page()).isZero();
        assertThat(empty.bounds()).isEqualTo(new Paginator.Bounds(0, 0));
    }

    @Test
    void aPageSizeChangeClampsThePage() {
        Paginator.Model state = after(Paginator.Model.of(25, 10), new Paginator.Msg.Last());
        Paginator.Model onePage = after(state, new Paginator.Msg.PageSizeChanged(30));
        assertThat(onePage.totalPages()).isEqualTo(1);
        assertThat(onePage.page()).isZero();
        assertThat(onePage.bounds()).isEqualTo(new Paginator.Bounds(0, 25));

        Paginator.Model smaller = after(Paginator.Model.of(30, 10), new Paginator.Msg.PageSizeChanged(5));
        assertThat(smaller.totalPages()).isEqualTo(6);
        assertThat(smaller.bounds()).isEqualTo(new Paginator.Bounds(0, 5));
    }

    @Test
    void theLargestCountsDoNotOverflow() {
        Paginator.Model last = new Paginator.Model(Integer.MAX_VALUE, Integer.MAX_VALUE, 10, true);
        assertThat(last.totalPages()).isEqualTo(214748365);
        assertThat(last.page()).isEqualTo(214748364);
        assertThat(last.bounds().start()).isEqualTo(2147483640);
        assertThat(last.bounds().end()).isEqualTo(Integer.MAX_VALUE);
        assertThat(last.bounds().length()).isEqualTo(7);

        Paginator.Model onePage = Paginator.Model.of(Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertThat(onePage.totalPages()).isEqualTo(1);
        assertThat(onePage.bounds()).isEqualTo(new Paginator.Bounds(0, Integer.MAX_VALUE));

        Paginator.Model perItem =
                after(Paginator.Model.of(Integer.MAX_VALUE, 1), new Paginator.Msg.GoTo(Integer.MAX_VALUE));
        assertThat(perItem.page()).isEqualTo(Integer.MAX_VALUE - 1);
        assertThat(perItem.bounds()).isEqualTo(new Paginator.Bounds(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));

        // A page index no int can hold is the first page, not a wrapped negative.
        assertThat(new Paginator.Model(Integer.MIN_VALUE, 10, 3, true).page()).isZero();
    }

    @Test
    void theDefaultDefinitionIsTenItemsAPageWithoutWrap() {
        Paginator.Config defaults = Paginator.Config.defaults();
        assertThat(defaults.perPage()).isEqualTo(10);
        assertThat(defaults.wrap()).isFalse();
        assertThat(defaults.appearance().mode()).isEqualTo(Paginator.Mode.DOTS);
        assertThat(defaults.appearance().maxDots()).isEqualTo(7);
        assertThat(defaults.appearance().activeDot()).isEqualTo(Paginator.DEFAULT_ACTIVE_DOT);
        assertThat(defaults.appearance().inactiveDot()).isEqualTo(Paginator.DEFAULT_INACTIVE_DOT);
        assertThat(Paginator.defaultKeys().actions()).hasSize(4);
        assertThat(Paginator.defaultKeys().bindings()).hasSize(8);
    }

    @Test
    void everyDefinitionAndMessageIsAValueAndNeverMutatesItsInput() {
        Paginator.Config config = Paginator.Config.defaults();
        assertThat(config.withWrap(true).wrap()).isTrue();
        assertThat(config.withPerPage(5).perPage()).isEqualTo(5);
        assertThat(config.withAppearance(Paginator.Appearance.numeric())
                        .appearance()
                        .mode())
                .isEqualTo(Paginator.Mode.NUMERIC);
        assertThat(config.wrap()).as("the original definition is untouched").isFalse();
        assertThat(config.perPage()).isEqualTo(Paginator.DEFAULT_PER_PAGE);
        assertThat(config.appearance().mode()).isEqualTo(Paginator.Mode.DOTS);

        Paginator.Model state = Paginator.Model.of(90, 10);
        StyledText first = Paginator.render(state, config, 40);
        StyledText second = Paginator.render(state, config, 40);
        assertThat(second)
                .as("a pure render of the same arguments is the same value")
                .isEqualTo(first);

        Paginator.Model moved = after(state, new Paginator.Msg.Next());
        assertThat(moved.page()).isEqualTo(1);
        assertThat(state.page()).as("the incoming state is untouched").isZero();
        assertThat(state.bounds()).isEqualTo(new Paginator.Bounds(0, 10));
        assertThat(moved.bounds()).isEqualTo(new Paginator.Bounds(10, 20));
        assertThat(config.perPage()).isEqualTo(state.perPage());
    }

    // ── R-02: slicing and boundary facts ────────────────────────────────────

    @Test
    void everyPagePartitionsTheItemsExactlyOnce() {
        for (int count : new int[] {0, 1, 9, 10, 11, 25, 99}) {
            for (int perPage : new int[] {1, 3, 10}) {
                Paginator.Model state = Paginator.Model.of(count, perPage);
                List<Paginator.Bounds> pages = new ArrayList<>();
                int seen = 0;
                while (true) {
                    Paginator.Bounds bounds = state.bounds();
                    assertThat(bounds.start())
                            .as("count %d, perPage %d", count, perPage)
                            .isEqualTo(seen);
                    assertThat(bounds.end()).isLessThanOrEqualTo(count);
                    assertThat(bounds.length()).isLessThanOrEqualTo(perPage);
                    assertThat(bounds.contains(bounds.start())).isEqualTo(!bounds.isEmpty());
                    assertThat(bounds.contains(bounds.end())).isFalse();
                    pages.add(bounds);
                    seen = bounds.end();
                    if (!state.hasNext()) {
                        break;
                    }
                    state = after(state, new Paginator.Msg.Next());
                    assertThat(state.page()).isLessThan(state.totalPages());
                }
                assertThat(seen)
                        .as("every item once, count %d, perPage %d", count, perPage)
                        .isEqualTo(count);
                assertThat(pages).hasSize(Math.max(1, state.totalPages()));
                assertThat(state.isLastPage()).isTrue();
            }
        }
    }

    @Test
    void shrinkingTheCountFromTheLastPageClampsThePage() {
        Paginator.Model state = after(Paginator.Model.of(25, 10), new Paginator.Msg.Last());
        Paginator.Model shrunk = after(state, new Paginator.Msg.ItemCountChanged(15));
        assertThat(shrunk.page()).isEqualTo(1);
        assertThat(shrunk.isLastPage()).isTrue();
        assertThat(shrunk.bounds()).isEqualTo(new Paginator.Bounds(10, 15));
    }

    @Test
    void aMessageThatChangesNothingKeepsTheIncomingInstance() {
        Paginator.Model last = after(Paginator.Model.of(25, 10), new Paginator.Msg.Last());
        assertThat(after(last, new Paginator.Msg.Next())).as("next at the end").isSameAs(last);
        assertThat(after(last, new Paginator.Msg.Last())).as("last at the end").isSameAs(last);

        Paginator.Model first = Paginator.Model.of(25, 10);
        assertThat(after(first, new Paginator.Msg.Previous()))
                .as("previous at the start")
                .isSameAs(first);
        assertThat(after(first, new Paginator.Msg.First()))
                .as("first at the start")
                .isSameAs(first);
        assertThat(after(first, new Paginator.Msg.GoTo(0)))
                .as("go-to the current page")
                .isSameAs(first);
        assertThat(after(first, new Paginator.Msg.ItemCountChanged(25))).isSameAs(first);
        assertThat(after(first, new Paginator.Msg.PageSizeChanged(10))).isSameAs(first);

        Paginator.Model empty = Paginator.Model.of(0, 10);
        assertThat(after(empty, new Paginator.Msg.Next()))
                .as("no pages to move through")
                .isSameAs(empty);
        assertThat(after(empty, new Paginator.Msg.Previous())).isSameAs(empty);
        assertThat(after(empty, new Paginator.Msg.Last())).isSameAs(empty);
        assertThat(after(empty, new Paginator.Msg.GoTo(4))).isSameAs(empty);
    }

    @Test
    void wrapAroundIsOffByDefaultAndCanBeTurnedOn() {
        Paginator.Model last = after(Paginator.Model.of(25, 10), new Paginator.Msg.Last());
        assertThat(after(last, new Paginator.Msg.Next()))
                .as("the default does not wrap")
                .isSameAs(last);

        Paginator.Config wrapping = Paginator.Config.defaults().withWrap(true);
        assertThat(after(last, wrapping, new Paginator.Msg.Next()).page()).isZero();

        Paginator.Model first = Paginator.Model.of(25, 10);
        assertThat(after(first, wrapping, new Paginator.Msg.Previous()).page()).isEqualTo(2);

        Paginator.Model empty = Paginator.Model.of(0, 10);
        assertThat(after(empty, wrapping, new Paginator.Msg.Next()))
                .as("nothing to wrap through")
                .isSameAs(empty);
        assertThat(after(empty, wrapping, new Paginator.Msg.Previous())).isSameAs(empty);
    }

    @Test
    void goToClampsToThePagesThatExist() {
        Paginator.Model state = Paginator.Model.of(25, 10);
        assertThat(after(state, new Paginator.Msg.GoTo(-5)).page()).isZero();
        assertThat(after(state, new Paginator.Msg.GoTo(99)).page()).isEqualTo(2);
        assertThat(after(state, new Paginator.Msg.GoTo(Integer.MAX_VALUE)).page())
                .isEqualTo(2);
        assertThat(after(state, new Paginator.Msg.GoTo(Integer.MIN_VALUE)).page())
                .isZero();
        assertThat(after(state, new Paginator.Msg.GoTo(1)).bounds()).isEqualTo(new Paginator.Bounds(10, 20));
    }

    @Test
    void boundsAreAlwaysInsideTheItemCount() {
        for (int count = 0; count <= 40; count++) {
            for (int perPage : new int[] {1, 2, 7, 13, 40}) {
                Paginator.Model state = Paginator.Model.of(count, perPage);
                while (true) {
                    Paginator.Bounds bounds = state.bounds();
                    assertThat(bounds.start()).isNotNegative();
                    assertThat(bounds.end()).isBetween(bounds.start(), count);
                    assertThat(bounds.length()).isLessThanOrEqualTo(perPage);
                    if (!state.hasNext()) {
                        break;
                    }
                    state = after(state, new Paginator.Msg.Next());
                }
            }
        }
    }

    // ── R-03: dot and numeric indicators ────────────────────────────────────

    @Test
    void dotsPaintTheCurrentPageAndTheOthersWithTheirOwnStyles() {
        Paginator.Config config = Paginator.Config.defaults()
                .withAppearance(Paginator.Appearance.dots().withStyles(Style.NONE.withBold(), Style.NONE.withItalic()));
        StyledText painted = Paginator.render(Paginator.Model.of(30, 10), config, 40);
        assertThat(painted.plainText()).isEqualTo("\u25CF \u25CB \u25CB");
        assertThat(painted.columns()).isEqualTo(5);
        assertThat(painted.spans()).anySatisfy(span -> {
            assertThat(span.text()).contains("\u25CF");
            assertThat(span.style()).isEqualTo(Style.NONE.withBold());
        });
        assertThat(painted.spans()).anySatisfy(span -> {
            assertThat(span.text()).contains("\u25CB");
            assertThat(span.style()).isEqualTo(Style.NONE.withItalic());
        });
    }

    @Test
    void dotsAreWindowedWithAnEllipsisOnEachHiddenSide() {
        Paginator.Config config = wideDots(5);
        Paginator.Model state = Paginator.Model.of(90, 10);
        assertThat(indicator(state, config, 60))
                .as("first page")
                .isEqualTo("\u25CF \u25CB \u25CB \u25CB \u25CB \u2026");

        Paginator.Model middle = after(state, new Paginator.Msg.GoTo(4));
        assertThat(indicator(middle, config, 60)).isEqualTo("\u2026 \u25CB \u25CB \u25CF \u25CB \u25CB \u2026");

        Paginator.Model last = after(state, new Paginator.Msg.Last());
        assertThat(indicator(last, config, 60)).isEqualTo("\u2026 \u25CB \u25CB \u25CB \u25CB \u25CF");
    }

    @Test
    void aHugePageCountPaintsTheSameBoundedWindowAsAShortOne() {
        Paginator.Model huge = new Paginator.Model(Integer.MAX_VALUE / 2, Integer.MAX_VALUE, 1, true);
        assertThat(huge.totalPages()).isEqualTo(Integer.MAX_VALUE);

        String painted = assertTimeout(Duration.ofSeconds(2), () -> indicator(huge, Paginator.Config.defaults(), 40));
        long dots =
                painted.codePoints().filter(c -> c == '\u25CF' || c == '\u25CB').count();
        assertThat(dots).as("a window, never one dot per page").isEqualTo(Paginator.DEFAULT_MAX_DOTS);
        assertThat(painted).contains("\u2026").hasSizeLessThan(40);
        assertThat(TextMeasure.columns(painted)).isLessThanOrEqualTo(40);
    }

    @Test
    void anEmptyPageCountHasOneExplicitIndicator() {
        Paginator.Model empty = Paginator.Model.of(0, 10);
        assertThat(indicator(empty, 20)).isEqualTo(Paginator.EMPTY_INDICATOR);
        Paginator.Config numeric = Paginator.Config.defaults().withAppearance(Paginator.Appearance.numeric());
        assertThat(indicator(empty, numeric, 20)).isEqualTo("0/0");
        assertThat(Paginator.EMPTY_INDICATOR).isEqualTo("0/0");
    }

    @Test
    void theIndicatorNeverExceedsTheWidthItIsGiven() {
        Paginator.Config numeric = Paginator.Config.defaults().withAppearance(Paginator.Appearance.numeric());
        List<Paginator.Model> states = List.of(
                Paginator.Model.of(0, 10),
                Paginator.Model.of(9, 10),
                after(Paginator.Model.of(90, 10), new Paginator.Msg.GoTo(4)),
                new Paginator.Model(Integer.MAX_VALUE, Integer.MAX_VALUE, 1, true));
        for (Paginator.Model state : states) {
            for (int width = 0; width <= 40; width++) {
                for (Paginator.Config config : List.of(Paginator.Config.defaults(), wideDots(5), numeric)) {
                    StyledText rendered = Paginator.render(state, config, width);
                    assertThat(rendered.columns()).as("width %d", width).isLessThanOrEqualTo(width);
                    if (width == 0) {
                        assertThat(rendered.isEmpty()).isTrue();
                    }
                }
            }
        }
    }

    @Test
    void aNarrowIndicatorDropsWholeCellsInsteadOfSplittingThem() {
        Paginator.Config config = wideDots(7);
        Paginator.Model state = after(Paginator.Model.of(200, 10), new Paginator.Msg.GoTo(10));
        // Seven dots plus two ellipses need more than 13 columns, so the window shrinks whole.
        String rendered = indicator(state, config, 13);
        assertThat(TextMeasure.columns(rendered)).isLessThanOrEqualTo(13);
        assertThat(rendered).contains("\u2026");
        assertThat(rendered).contains("\u25CF");
        for (String cluster : TextMeasure.graphemes(rendered)) {
            assertThat(cluster)
                    .as("every painted cluster is a whole glyph of the indicator")
                    .isIn(
                            Paginator.DEFAULT_ACTIVE_DOT,
                            Paginator.DEFAULT_INACTIVE_DOT,
                            Paginator.ELLIPSIS,
                            Paginator.CELL_SEPARATOR);
        }
    }

    @Test
    void aOneColumnRowPaintsOneGlyphAndAWideOneIsNeverHalved() {
        Paginator.Model state = Paginator.Model.of(30, 10);
        assertThat(indicator(state, 1)).isEqualTo(Paginator.DEFAULT_ACTIVE_DOT);
        assertThat(Paginator.render(state, Paginator.Config.defaults(), 0).isEmpty())
                .isTrue();
        assertThat(Paginator.render(state, Paginator.Config.defaults(), -3).isEmpty())
                .isTrue();

        // Emoji dots are two columns wide, so a one-column pane can hold neither of a pair.
        Paginator.Config emoji = Paginator.Config.defaults()
                .withAppearance(Paginator.Appearance.dots()
                        .withActiveDot("\uD83D\uDD35")
                        .withInactiveDot("\u26AA")
                        .withMaxDots(2));
        assertThat(indicator(state, emoji, 1)).isEqualTo(Paginator.ELLIPSIS);
        assertThat(indicator(state, emoji, 2)).isEqualTo("\uD83D\uDD35");
        assertThat(indicator(state, emoji, 4))
                .as("a wide cell, a separator and the ellipsis of the pages that did not fit")
                .isEqualTo("\uD83D\uDD35 \u2026");
        assertThat(indicator(state, emoji, 7)).as("two whole wide cells").isEqualTo("\uD83D\uDD35 \u26AA \u2026");
        StyledText wide = Paginator.render(state, emoji, 7);
        assertThat(wide.columns()).isEqualTo(7);
        assertThat(wide.spans().get(0).text()).isEqualTo("\uD83D\uDD35");
    }

    @Test
    void numericShowsTheDisplayedPageAndTheTotal() {
        Paginator.Config numeric = Paginator.Config.defaults().withAppearance(Paginator.Appearance.numeric());
        Paginator.Model state = Paginator.Model.of(25, 10);
        assertThat(indicator(state, numeric, 40)).isEqualTo("1/3");
        assertThat(indicator(after(state, new Paginator.Msg.Last()), numeric, 40))
                .isEqualTo("3/3");
    }

    @Test
    void numericIsTruncatedWithAnEllipsisWhenTheRowIsNarrow() {
        Paginator.Config numeric = Paginator.Config.defaults().withAppearance(Paginator.Appearance.numeric());
        Paginator.Model state = Paginator.Model.of(120, 10);
        StyledText rendered = Paginator.render(state, numeric, 3);
        assertThat(rendered.plainText()).isEqualTo("1/\u2026");
        assertThat(rendered.columns()).isEqualTo(3);
        assertThat(Paginator.render(state, numeric, 0).isEmpty()).isTrue();
    }

    @Test
    void anAppearanceRejectsAGlyphThatCannotBePainted() {
        Paginator.Appearance dots = Paginator.Appearance.dots();
        assertThatThrownBy(() -> dots.withActiveDot(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> dots.withInactiveDot("\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> dots.withMaxDots(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDots must be positive");
        assertThat(dots.withActiveDot(null).activeDot())
                .as("a missing glyph falls back to the default")
                .isEqualTo(Paginator.DEFAULT_ACTIVE_DOT);
        assertThat(dots.withActiveDot(" \uD83D\uDD35 ").activeDot())
                .as("one row, and no padding")
                .isEqualTo("\uD83D\uDD35");
    }

    // ── keys and help ───────────────────────────────────────────────────────

    @Test
    void theDefaultKeysMovePagesAndTheHelpDescribesThem() {
        List<String> lines = help(Paginator.defaultKeys());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("previous page").contains("next page");
        assertThat(lines.get(1)).contains("first page").contains("last page");

        Element<Paginator.Msg> view = pager().view(Paginator.Model.of(90, 10), CONTEXT);
        assertThat(InteractionRouter.message(view, key("right"))).contains(new Paginator.Msg.Next());
        assertThat(InteractionRouter.message(view, key("l"))).contains(new Paginator.Msg.Next());
        assertThat(InteractionRouter.message(view, key("pagedown"))).contains(new Paginator.Msg.Next());
        assertThat(InteractionRouter.message(view, key("left"))).contains(new Paginator.Msg.Previous());
        assertThat(InteractionRouter.message(view, key("home"))).contains(new Paginator.Msg.First());
        assertThat(InteractionRouter.message(view, key("end"))).contains(new Paginator.Msg.Last());
        assertThat(InteractionRouter.message(view, key("x")))
                .as("a pager never consumes text")
                .isEmpty();
        assertThat(InteractionRouter.message(view, key("tab"))).isEmpty();
    }

    @Test
    void aRemappedPagerKeyChangesRoutingAndHelpTogether() {
        KeyMap<Paginator.Msg> keys = Paginator.defaultKeys()
                .with(Paginator.defaultKeys()
                        .action("next")
                        .orElseThrow()
                        .withAliases("n")
                        .withLabel(""));
        Paginator remapped = new Paginator(Paginator.Config.defaults(), keys);
        Element<Paginator.Msg> view = remapped.view(Paginator.Model.of(90, 10), CONTEXT);
        assertThat(InteractionRouter.message(view, key("n"))).contains(new Paginator.Msg.Next());
        assertThat(InteractionRouter.message(view, key("right")))
                .as("the old gesture is gone")
                .isEmpty();
        assertThat(help(remapped.keys())).anySatisfy(line -> assertThat(line).contains("n next page"));
    }

    @Test
    void anUnfocusedPaginatorReceivesNoKeys() {
        Paginator.Model unfocused = Paginator.Model.of(90, 10).withFocus(false);
        Element<Paginator.Msg> view = pager().view(unfocused, CONTEXT);
        assertThat(InteractionRouter.message(view, key("right"))).isEmpty();
        assertThat(pager().update(unfocused, new Paginator.Msg.Next()).model().focused())
                .as("a reduction never moves the focus the application owns")
                .isFalse();
    }

    // ── R-04: the paged demo, mounted ───────────────────────────────────────

    @Test
    void theDemoStartsOnTheFirstOfNinePages() {
        PaginatorDemo.Model model = start();
        assertThat(headline(model)).isEqualTo("page=1/9 items=84 per=10 bounds=[0,10) selected=0 mode=dots wrap=off");
        List<String> lines = painted(demo().view(model, CONTEXT));
        assertThat(lines.get(1).strip()).isEqualTo("last=none");
        assertThat(lines.get(3).strip()).isEqualTo("> item-01");
        assertThat(lines.get(4).strip()).isEqualTo("item-02");
        assertThat(lines.get(12).strip()).isEqualTo("item-10");
        assertThat(lines.get(14).strip())
                .as("the mounted indicator")
                .isEqualTo("\u25CF \u25CB \u25CB \u25CB \u25CB \u25CB \u25CB \u2026");
        assertThat(lines.get(16)).contains("\u2190 previous page").contains("\u2192 next page");
        assertThat(lines.get(17)).contains("g dots/numeric").contains("w wrap").contains("r remove-items");
    }

    @Test
    void pagingMovesTheSliceAndTheIndicator() {
        PaginatorDemo.Model model = send(start(), "right");
        assertThat(headline(model)).isEqualTo("page=2/9 items=84 per=10 bounds=[10,20) selected=0 mode=dots wrap=off");
        List<String> lines = painted(demo().view(model, CONTEXT));
        assertThat(lines.get(3).strip()).as("the page starts at item-11").isEqualTo("item-11");
        assertThat(lines.get(12).strip()).isEqualTo("item-20");

        PaginatorDemo.Model last = send(model, "end");
        assertThat(headline(last)).isEqualTo("page=9/9 items=84 per=10 bounds=[80,84) selected=0 mode=dots wrap=off");
        List<String> rows = painted(demo().view(last, CONTEXT)).subList(3, 13);
        assertThat(rows.get(0).strip()).isEqualTo("item-81");
        assertThat(rows.get(3).strip()).isEqualTo("item-84");
        assertThat(rows.subList(4, 10))
                .as("the rows past a short page are padded, so the indicator stays put")
                .allSatisfy(row -> assertThat(row.strip()).isEmpty());

        PaginatorDemo.Model first = send(last, "home");
        assertThat(first.pager().page()).isZero();
        assertThat(first.pager().bounds()).isEqualTo(new Paginator.Bounds(0, 10));
    }

    @Test
    void aPagingKeyAtTheLastPageChangesNothingWhenWrapIsOff() {
        PaginatorDemo.Model last = send(start(), "end");
        PaginatorDemo.Model blocked = send(last, "right");
        assertThat(blocked.pager()).as("the pager kept its own instance").isSameAs(last.pager());
        assertThat(blocked.pager().page()).isEqualTo(8);
        assertThat(send(blocked, "pagedown").pager()).isSameAs(last.pager());
    }

    @Test
    void wrapCanBeTurnedOnFromTheScene() {
        PaginatorDemo.Model model = send(send(start(), "end"), "w");
        assertThat(headline(model)).contains("wrap=on");
        assertThat(model.last()).isEqualTo("wrap-on");
        assertThat(send(model, "right").pager().page())
                .as("the last page wraps to the first")
                .isZero();
        assertThat(send(model, "left").pager().page())
                .as("the first page wraps to the last")
                .isEqualTo(7);
        assertThat(send(send(model, "right"), "left").pager().page())
                .as("the first page wraps backwards to the last")
                .isEqualTo(8);
    }

    @Test
    void theIndicatorCanBeToggledBetweenDotsAndNumeric() {
        PaginatorDemo.Model model = send(start(), "g");
        assertThat(model.last()).isEqualTo("mode-numeric");
        assertThat(headline(model)).contains("mode=numeric");
        List<String> lines = painted(demo().view(model, CONTEXT));
        assertThat(lines.get(14).strip()).isEqualTo("1/9");
        assertThat(painted(demo().view(send(model, "g"), CONTEXT)).get(14).strip())
                .isEqualTo("\u25CF \u25CB \u25CB \u25CB \u25CB \u25CB \u25CB \u2026");
    }

    @Test
    void selectionMovesThePageTheParentChooses() {
        PaginatorDemo.Model model = start();
        for (int index = 0; index < 10; index++) {
            model = send(model, "down");
        }
        assertThat(model.selected()).isEqualTo(10);
        assertThat(model.last()).isEqualTo("selected-10");
        assertThat(headline(model))
                .as("the selection brought page two into view")
                .contains("page=2/9");

        model = send(model, "up");
        assertThat(model.selected()).isEqualTo(9);
        assertThat(headline(model)).contains("page=1/9");
    }

    @Test
    void aShrinkingCountClampsThePageAndTheSelection() {
        PaginatorDemo.Model model =
                new PaginatorDemo.Model(new Paginator.Model(8, 84, 10, true), 83, false, false, "none");
        assertThat(model.visibleItems()).containsExactly("item-81", "item-82", "item-83", "item-84");

        PaginatorDemo.Model shrunk = send(model, "r");
        assertThat(shrunk.pager().itemCount()).isEqualTo(74);
        assertThat(shrunk.pager().page())
                .as("eight pages left, so the ninth is gone")
                .isEqualTo(7);
        assertThat(shrunk.selected())
                .as("the selection is clamped into the list")
                .isEqualTo(73);
        assertThat(shrunk.last()).isEqualTo("items-74");
        assertThat(shrunk.visibleItems()).hasSize(4).startsWith("item-71");
        assertThat(shrunk.visibleItems().get(3)).isEqualTo("item-74");
    }

    @Test
    void reducingToZeroLeavesNoPhantomPage() {
        PaginatorDemo.Model model = start();
        for (int page = 0; page < 9; page++) {
            model = send(model, "r");
        }
        assertThat(model.pager().itemCount()).isZero();
        assertThat(model.pager().totalPages()).isZero();
        assertThat(model.pager().bounds()).isEqualTo(new Paginator.Bounds(0, 0));
        assertThat(model.selected()).isEqualTo(-1);
        assertThat(model.visibleItems()).isEmpty();
        assertThat(headline(model)).isEqualTo("page=0/0 items=0 per=10 bounds=[0,0) selected=none mode=dots wrap=off");
        assertThat(painted(demo().view(model, CONTEXT)).get(14).strip()).isEqualTo("0/0");
        assertThat(painted(demo().view(model, CONTEXT)).subList(3, 13))
                .allSatisfy(row -> assertThat(row.strip()).isEmpty());

        PaginatorDemo.Model restored = send(model, "a");
        assertThat(restored.pager().itemCount()).isEqualTo(10);
        assertThat(headline(restored))
                .isEqualTo("page=1/1 items=10 per=10 bounds=[0,10) selected=0 mode=dots wrap=off");
        assertThat(restored.visibleItems()).hasSize(10).startsWith("item-01");
        assertThat(send(restored, "a").pager().itemCount())
                .as("never more than the list")
                .isEqualTo(20);
    }

    @Test
    void aNarrowerRowTruncatesTheIndicatorAndNeverThePage() {
        PaginatorDemo.Model model = send(send(start(), "right"), "w");
        String wide = painted(demo().view(model, CONTEXT)).get(14).strip();
        String narrow =
                painted(demo().view(model, CONTEXT), SCREEN_ROWS, 9).get(14).strip();
        assertThat(narrow).isNotEqualTo(wide);
        assertThat(narrow.length()).isLessThanOrEqualTo(9);
        assertThat(narrow).contains(Paginator.ELLIPSIS);
        assertThat(model.pager().page()).as("a resize never moves the page").isEqualTo(1);
        assertThat(model.pager().bounds()).isEqualTo(new Paginator.Bounds(10, 20));
        assertThat(painted(demo().view(model, CONTEXT), SCREEN_ROWS, 9).get(0)).startsWith("page=2/9");
    }

    @Test
    void escapeQuitsTheDemo() {
        PaginatorDemo.Model model = start();
        assertThat(route(model, "escape")).contains(new PaginatorDemo.Msg.Quit());
        assertThat(route(model, "ctrl+c")).contains(new PaginatorDemo.Msg.Quit());
        Update<PaginatorDemo.Model, PaginatorDemo.Msg, Never> update =
                demo().update(model, new PaginatorDemo.Msg.Quit());
        assertThat(update.command()).isInstanceOf(Command.Quit.class);
        assertThat(update.model()).isSameAs(model);
    }

    // ── mounted scope isolation ─────────────────────────────────────────────

    /**
     * Two paginators in one parent: each owns one slot, one focus region and one visible window.
     */
    record Pair(Paginator.Model left, Paginator.Model right) {}

    sealed interface PairMsg {
        record Left(Paginator.Msg value) implements PairMsg {}

        record Right(Paginator.Msg value) implements PairMsg {}
    }

    private static final class TwoPagers implements Component<Pair, PairMsg, Never> {

        private final Mount<Pair, PairMsg, Never, Paginator.Model, Paginator.Msg, Never> left = Mount.of(
                ComponentId.of("left"),
                new Paginator(Paginator.Config.defaults()),
                ModelLens.of(Pair::left, (Pair pair, Paginator.Model child) -> new Pair(child, pair.right())),
                PairMsg.Left::new,
                OutputRoute.toOutput(value -> value));

        private final Mount<Pair, PairMsg, Never, Paginator.Model, Paginator.Msg, Never> right = Mount.of(
                ComponentId.of("right"),
                new Paginator(Paginator.Config.defaults()),
                ModelLens.of(Pair::right, (Pair pair, Paginator.Model child) -> new Pair(pair.left(), child)),
                PairMsg.Right::new,
                OutputRoute.toOutput(value -> value));

        @Override
        public Update<Pair, PairMsg, Never> init(InitContext context) {
            return Update.next(new Pair(
                    Paginator.Model.of(30, 10), Paginator.Model.of(30, 10).withFocus(false)));
        }

        @Override
        public Update<Pair, PairMsg, Never> update(Pair model, PairMsg message) {
            return switch (message) {
                case PairMsg.Left value -> left.update(model, value.value());
                case PairMsg.Right value -> right.update(model, value.value());
            };
        }

        @Override
        public Element<PairMsg> view(Pair model, ViewContext context) {
            return Elements.row(left.view(model, context), right.view(model, context));
        }
    }

    @Test
    void onlyTheFocusedPaginatorConsumesPagingKeys() {
        TwoPagers component = new TwoPagers();
        Pair start = component.init(new InitContext(null, null)).model();

        assertThat(io.github.dhoard.braxton.testing.Routing.route(component, start, CONTEXT, key("right")).stream()
                        .findFirst())
                .contains(new PairMsg.Left(new Paginator.Msg.Next()));
        assertThat(io.github.dhoard.braxton.testing.Routing.route(component, start, CONTEXT, key("end")).stream()
                        .findFirst())
                .contains(new PairMsg.Left(new Paginator.Msg.Last()));

        Pair rightFocused =
                new Pair(start.left().withFocus(false), start.right().withFocus(true));
        assertThat(
                        io.github.dhoard.braxton.testing.Routing.route(component, rightFocused, CONTEXT, key("right"))
                                .stream()
                                .findFirst())
                .contains(new PairMsg.Right(new Paginator.Msg.Next()));

        Pair unfocused = new Pair(start.left().withFocus(false), start.right().withFocus(false));
        assertThat(io.github.dhoard.braxton.testing.Routing.route(component, unfocused, CONTEXT, key("right")).stream()
                        .findFirst())
                .as("no paginator owns focus, so no paginator pages")
                .isEmpty();
    }

    @Test
    void aPagerMessageChangesOnlyItsOwnSlot() {
        TwoPagers component = new TwoPagers();
        Pair start = component.init(new InitContext(null, null)).model();

        Pair paged = component
                .update(start, new PairMsg.Left(new Paginator.Msg.Next()))
                .model();
        assertThat(paged.left().page()).isEqualTo(1);
        assertThat(paged.left().bounds()).isEqualTo(new Paginator.Bounds(10, 20));
        assertThat(paged.right().page()).as("the sibling slot did not move").isZero();
        assertThat(paged.right().bounds()).isEqualTo(new Paginator.Bounds(0, 10));
    }

    @Test
    void siblingRegionsKeepTheirScopedIdentities() {
        TwoPagers component = new TwoPagers();
        Pair start = component.init(new InitContext(null, null)).model();
        // Two siblings are two leaves: each answers only for its own rectangle, so a pointer that
        // lands in one of them never reaches the other.
        var screen = io.github.dhoard.braxton.testing.ElementScreen.render(
                component.view(start, CONTEXT),
                CONTEXT.viewport().rows(),
                CONTEXT.viewport().cols());
        assertThat(io.github.dhoard.braxton.ui.InteractionRouter.focusables(screen.placement()))
                .hasSize(2);
        assertThat(screen.hit(0, 0))
                .isNotNull()
                .isNotSameAs(screen.hit(0, CONTEXT.viewport().cols() - 1));
    }

    @Test
    void theDemoRunsUnderTheDeterministicDriver() {
        ComponentDriver<PaginatorDemo.Model, PaginatorDemo.Msg, Never> driver =
                ComponentDriver.initialize(demo(), new InitContext(null, null));
        driver.send(new PaginatorDemo.Msg.Pager(new Paginator.Msg.Next()));
        assertThat(driver.model().pager().page()).isEqualTo(1);
        assertThat(driver.screen(CONTEXT).line(0)).startsWith("page=2/9");
        driver.send(new PaginatorDemo.Msg.ToggleMode());
        assertThat(driver.screen(CONTEXT).line(14).strip()).isEqualTo("2/9");
    }
}

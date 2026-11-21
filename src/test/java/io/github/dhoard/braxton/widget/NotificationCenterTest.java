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

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyEventType;
import io.github.dhoard.braxton.input.Modifiers;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.ThemeToken;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.ElementTree;
import io.github.dhoard.braxton.ui.InteractionRouter;
import io.github.dhoard.braxton.widget.NotificationCenter.Msg;
import io.github.dhoard.braxton.widget.NotificationCenter.Notification;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The notification center: its order, its bound, its read state, its window, keys and themes.
 */
class NotificationCenterTest {

    private final NotificationCenter center = new NotificationCenter(NotificationCenter.Config.of(5));

    @Test
    void theNewestNotificationIsFirstAndTheWindowFollowsItWhileTheReaderIsAtIt() {
        NotificationCenter.Model model =
                center.init(new InitContext(null, null)).model();
        assertThat(model.notifications()).isEmpty();
        assertThat(model.selected()).isEmpty();
        assertThat(model.unread()).isZero();

        model = push(model, "n1", Severity.ERROR, "build failed");
        model = push(model, "n2", Severity.SUCCESS, "deploy finished");
        model = push(model, "n3", Severity.WARNING, "disk almost full");

        assertThat(model.notifications()).extracting(Notification::id).containsExactly("n3", "n2", "n1");
        assertThat(model.selected())
                .as("the selection follows the newest while the reader is at it")
                .isEqualTo("n3");
        assertThat(model.offset()).as("so the window is at the newest").isZero();
        assertThat(model.unread()).isEqualTo(3);
        assertThat(model.rows()).isEqualTo(NotificationCenter.DEFAULT_ROWS);
        assertThat(ElementScreen.render(Elements.notificationCenter(center, model, message -> message), 4, 20)
                        .lines())
                .containsExactly("! disk almost full", "\u2714 deploy finished", "\u2716 build failed", "");
        assertThat(center.view(model, new ViewContext(new Viewport(4, 20)))).isNotNull();
        assertThat(model.selectedNotification()).contains(Notification.of("n3", Severity.WARNING, "disk almost full"));
        assertThat(model.find("n1")).contains(Notification.of("n1", Severity.ERROR, "build failed"));
        assertThat(model.find("gone")).isEmpty();
        assertThat(model.find(null)).isEmpty();
    }

    @Test
    void aPushKeepsTheReadersPlacementUnlessTheReaderWasAlreadyAtTheNewest() {
        NotificationCenter.Model model =
                push(center.init(new InitContext(null, null)).model(), "a", Severity.INFO, "one");
        model = push(model, "b", Severity.INFO, "two");
        model = apply(model, new Msg.Down());

        assertThat(model.selected()).isEqualTo("a");
        NotificationCenter.Model arrived = push(model, "c", Severity.ERROR, "three");
        assertThat(arrived.selected())
                .as("a reader looking at an older notification is not moved by a new one")
                .isEqualTo("a");
        assertThat(arrived.notifications()).extracting(Notification::id).containsExactly("c", "b", "a");
        assertThat(arrived.notifications().get(0).read())
                .as("the new notification arrives unread")
                .isFalse();
        assertThat(arrived.unread()).isEqualTo(2);

        // A reader at the newest keeps following it.
        assertThat(push(apply(arrived, new Msg.Home()), "d", Severity.INFO, "four")
                        .selected())
                .isEqualTo("d");
        // The window still shows the selection, wherever the newest is.
        assertThat(arrived.offset())
                .isBetween(0, Math.max(0, arrived.notifications().size() - arrived.rows()));
    }

    @Test
    void aBoundDropsTheOldestAndPushingAnExistingIdRefreshesItWhereItIs() {
        NotificationCenter.Model model =
                center.init(new InitContext(null, null)).model();
        for (int index = 1; index <= 7; index++) {
            model = push(model, "n" + index, Severity.INFO, "event " + index);
        }

        assertThat(model.notifications())
                .as("the bound keeps the newest five")
                .extracting(Notification::id)
                .containsExactly("n7", "n6", "n5", "n4", "n3");
        assertThat(model.selected()).isEqualTo("n7");

        // Pushing an id the center already holds refreshes it and moves it back to the front rather
        // than listing it twice.
        NotificationCenter.Model refreshed = push(model, "n5", Severity.ERROR, "event 5 again");
        assertThat(refreshed.notifications())
                .extracting(Notification::id)
                .containsExactly("n5", "n7", "n6", "n4", "n3");
        assertThat(refreshed.find("n5")).contains(Notification.of("n5", Severity.ERROR, "event 5 again"));
        assertThat(refreshed.selected()).isEqualTo("n5");

        // A notification dropped by the bound cannot stay selected.
        NotificationCenter.Model scrolled = apply(model, new Msg.End());
        assertThat(scrolled.selected()).isEqualTo("n3");
        NotificationCenter.Model bounded = push(scrolled, "n8", Severity.INFO, "event 8");
        assertThat(bounded.notifications()).extracting(Notification::id).containsExactly("n8", "n7", "n6", "n5", "n4");
        assertThat(bounded.selected())
                .as("a selection the bound dropped falls back to the newest")
                .isEqualTo("n8");
        assertThat(new NotificationCenter(NotificationCenter.Config.of(1))
                        .config()
                        .limit())
                .isEqualTo(1);
    }

    @Test
    void readingMarksOnNavigationSelectionAndActivationAndUnreadCountsTheRest() {
        NotificationCenter.Model model =
                push(center.init(new InitContext(null, null)).model(), "a", Severity.INFO, "one");
        model = push(model, "b", Severity.INFO, "two");
        model = push(model, "c", Severity.INFO, "three");
        assertThat(model.unread()).isEqualTo(3);

        // Moving the selection marks the row it lands on read.
        NotificationCenter.Model down = apply(model, new Msg.Down());
        assertThat(down.selected()).isEqualTo("b");
        assertThat(down.unread()).isEqualTo(2);
        assertThat(down.find("b").orElseThrow().read()).isTrue();
        assertThat(down.find("c").orElseThrow().read()).isFalse();

        // Activation reports the notification, marked read.
        var activated = center.update(down, new Msg.Activate());
        assertThat(activated.outputs())
                .containsExactly(new NotificationCenter.Output.Chosen(
                        "b", Notification.of("b", Severity.INFO, "two").withRead(true)));
        assertThat(activated.model().unread()).isEqualTo(2);

        // Explicit marking, one row and then all of them.
        NotificationCenter.Model marked = apply(model, new Msg.MarkRead("a"));
        assertThat(marked.unread()).isEqualTo(2);
        assertThat(marked.selected())
                .as("marking read does not move the selection")
                .isEqualTo("c");
        NotificationCenter.Model all = apply(marked, new Msg.MarkAllRead());
        assertThat(all.unread()).isZero();
        assertThat(center.update(all, new Msg.MarkAllRead()).model())
                .as("marking everything read twice changes nothing")
                .isSameAs(all);
        assertThat(center.update(model, new Msg.MarkRead("gone")).model()).isSameAs(model);
        assertThat(center.update(model, new Msg.Select("gone")).model()).isSameAs(model);
        // A read notification keeps its severity and its message; only its styles change.
        assertThat(all.find("c"))
                .contains(Notification.of("c", Severity.INFO, "three").withRead(true));
        // Dismissing the selected notification selects the row that followed it.
        assertThat(apply(all, new Msg.Dismiss("c")).selected()).isEqualTo("b");
        assertThat(apply(all, new Msg.Dismiss("b")).notifications())
                .extracting(Notification::id)
                .containsExactly("c", "a");
        assertThat(apply(apply(all, new Msg.Dismiss("a")), new Msg.Dismiss("b")).selected())
                .isEqualTo("c");
        assertThat(apply(all, new Msg.Dismiss("gone")).notifications()).hasSize(3);
        assertThat(center.update(all, new Msg.Dismiss("gone")).model()).isSameAs(all);
    }

    @Test
    void theKeysMoveTheSelectionAndEnterReportsTheChosenNotification() {
        NotificationCenter.Model model =
                push(center.init(new InitContext(null, null)).model(), "a", Severity.INFO, "one");
        model = push(model, "b", Severity.INFO, "two");
        model = push(model, "c", Severity.INFO, "three");
        Element<Msg> root = Elements.notificationCenter(center, model, message -> message);

        assertThat(apply(model, new Msg.Up()))
                .describedAs("nothing is newer than the newest")
                .isNotNull();
        assertThat(apply(model, new Msg.Down()).selected()).isEqualTo("b");
        assertThat(apply(apply(model, new Msg.Down()), new Msg.Down()).selected())
                .isEqualTo("a");
        assertThat(apply(apply(apply(model, new Msg.Down()), new Msg.Down()), new Msg.Down())
                        .selected())
                .as("moving past the oldest row stays on it")
                .isEqualTo("a");
        assertThat(apply(model, new Msg.End()).selected()).isEqualTo("a");
        assertThat(apply(apply(model, new Msg.End()), new Msg.Home()).selected())
                .isEqualTo("c");

        assertThat(route(root, "up")).containsExactly(new Msg.Up());
        assertThat(route(root, "down")).containsExactly(new Msg.Down());
        assertThat(route(root, "home")).containsExactly(new Msg.Home());
        assertThat(route(root, "end")).containsExactly(new Msg.End());
        assertThat(route(root, "enter")).containsExactly(new Msg.Activate());
        assertThat(route(root, "q"))
                .as("a key the center does not own falls through to the application")
                .isEmpty();
        assertThat(InteractionRouter.route(
                        root,
                        InputEvent.KeyInput.of(Key.parse("down"), "", KeyEventType.RELEASE),
                        List.of(),
                        center.keys().bindings()))
                .as("a released key is not a press")
                .isEmpty();
        assertThat(center.keys().actions())
                .extracting(KeyAction::id)
                .containsExactly("up", "down", "home", "end", "open");
        assertThat(ElementScreen.render(root, 3, 20).focusables()).hasSize(1);
    }

    @Test
    void aPressSelectsARowAndASecondPressActivatesIt() {
        NotificationCenter.Model model =
                push(center.init(new InitContext(null, null)).model(), "a", Severity.INFO, "one");
        model = push(model, "b", Severity.INFO, "two");
        Element<Msg> root = Elements.notificationCenter(center, model, message -> message);

        assertThat(press(root, 3, 20, 1, 3)).containsExactly(new Msg.Select("a"));
        assertThat(press(root, 3, 20, 0, 3))
                .as("a press on the selected row opens it")
                .containsExactly(new Msg.Activate());
        assertThat(press(root, 3, 20, 2, 3))
                .as("a press below the last painted row selects nothing")
                .isEmpty();
        assertThat(press(root, 3, 20, 5, 3)).isEmpty();
        assertThat(press(root, 3, 20, PointerEvent.press(PointerButton.RIGHT, Modifiers.NONE, 0, 0)))
                .isEmpty();
        assertThat(press(root, 3, 20, PointerEvent.motion(PointerButton.NONE, Modifiers.NONE, 0, 0)))
                .isEmpty();
    }

    @Test
    void aWindowFollowsTheSelectionThroughALongListAndTheModelAdmitsIt() {
        NotificationCenter small =
                new NotificationCenter(NotificationCenter.Config.of(20).withRows(3));
        NotificationCenter.Model model = small.init(new InitContext(null, null)).model();
        for (int index = 1; index <= 7; index++) {
            model = small.update(model, new Msg.Push(Notification.of("n" + index, Severity.INFO, "event " + index)))
                    .model();
        }
        assertThat(model.offset()).isZero();
        assertThat(ElementScreen.render(Elements.notificationCenter(small, model, message -> message), 3, 20)
                        .lines())
                .containsExactly("• event 7", "• event 6", "• event 5");

        NotificationCenter.Model scrolled = apply(small, model, new Msg.Down(), new Msg.Down(), new Msg.Down());
        assertThat(scrolled.selected()).isEqualTo("n4");
        assertThat(scrolled.offset())
                .as("the window moved so the selection is the last row it shows")
                .isEqualTo(1);
        assertThat(ElementScreen.render(Elements.notificationCenter(small, scrolled, message -> message), 3, 20)
                        .lines())
                .containsExactly("• event 6", "• event 5", "• event 4");
        assertThat(apply(small, scrolled, new Msg.End()).offset()).isEqualTo(4);
        assertThat(apply(small, scrolled, new Msg.Home()).offset()).isZero();

        // A window of no rows paints nothing, and a resized window admits the selection again.
        assertThat(ElementScreen.render(
                                Elements.notificationCenter(small, scrolled.withRows(0), message -> message), 3, 20)
                        .lines())
                .containsExactly("", "", "");
        NotificationCenter.Model resized = apply(small, scrolled, new Msg.RowsChanged(5));
        assertThat(resized.rows()).isEqualTo(5);
        assertThat(resized.offset())
                .as("a taller window still shows the selection, and does not have to move to do it")
                .isEqualTo(1);
        assertThat(apply(small, model, new Msg.RowsChanged(0)).rows()).isZero();
        // A model the application builds obeys the same rules, however far its window asks to go.
        assertThat(new NotificationCenter.Model(model.notifications(), "gone", true, 99, 3).selected())
                .isEqualTo("n7");
        assertThat(new NotificationCenter.Model(model.notifications(), "n7", true, 99, 3).offset())
                .as("the window is admitted over the selection, not where the caller asked")
                .isZero();
        assertThat(new NotificationCenter.Model(model.notifications(), "n5", true, 99, 3).offset())
                .isEqualTo(2);
    }

    @Test
    void rowsPaintTheSeverityInTheThemeAndAReadRowIsMuted() {
        NotificationCenter.Model model =
                push(center.init(new InitContext(null, null)).model(), "a", Severity.ERROR, "failed");
        model = push(model, "b", Severity.WARNING, "careful");
        // The newest row is selected and unread; the older one has been seen.
        model = apply(model, new Msg.MarkRead("a"));

        Element<Msg> root = Elements.notificationCenter(center, model, message -> message);
        ElementScreen dark = ElementScreen.render(root, 2, 20, Themes.DARK);
        ElementScreen light = ElementScreen.render(root, 2, 20, Themes.LIGHT);

        assertThat(dark.styles(0).get(0))
                .as("an unread warning is painted in the warning role over the selection")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.WARNING)
                        .merge(Themes.DARK.style(ThemeToken.SELECTED))
                        .merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(0).get(2))
                .as("its message is the theme's text style")
                .isEqualTo(Themes.DARK
                        .style(ThemeToken.TEXT)
                        .merge(Themes.DARK.style(ThemeToken.SELECTED))
                        .merge(Themes.DARK.style(ThemeToken.FOCUS)));
        assertThat(dark.styles(1).get(0))
                .as("a read notification is muted")
                .isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(1).get(2)).isEqualTo(Themes.DARK.style(ThemeToken.MUTED));
        assertThat(dark.styles(0).get(19))
                .as("the selection fills the whole row")
                .isEqualTo(Themes.DARK.style(ThemeToken.SELECTED));
        assertThat(light.styles(0).get(0))
                .as("a live theme switch repaints the rows")
                .isNotEqualTo(dark.styles(0).get(0));
        assertThat(light.styles(1).get(0)).isEqualTo(Themes.LIGHT.style(ThemeToken.MUTED));
        // A selected row in a center that does not hold the keyboard carries no focus style.
        ElementScreen unfocused = ElementScreen.render(
                Elements.notificationCenter(center, model.withFocus(false), message -> message), 2, 20, Themes.DARK);
        assertThat(unfocused.styles(0).get(0))
                .isEqualTo(Themes.DARK.style(ThemeToken.WARNING).merge(Themes.DARK.style(ThemeToken.SELECTED)));
    }

    @Test
    void anEmptyOrNarrowCenterPaintsOnlyWhatFits() {
        NotificationCenter.Model empty =
                center.init(new InitContext(null, null)).model();
        Element<Msg> emptyRoot = Elements.notificationCenter(center, empty, message -> message);
        assertThat(ElementScreen.render(emptyRoot, 3, 20).lines()).containsExactly("", "", "");
        assertThat(ElementScreen.render(emptyRoot, 3, 20).focusables())
                .as("a center with nothing to read takes no turn in the focus walk")
                .isEmpty();
        assertThat(center.canFocus(empty)).isFalse();
        assertThat(center.canFocus(empty.withNotifications(List.of(Notification.of("a", "one")))))
                .isTrue();
        assertThat(ElementScreen.render(emptyRoot, 0, 0).lines()).isEmpty();

        NotificationCenter.Model model = push(empty, "a", Severity.ERROR, "failed");
        Element<Msg> root = Elements.notificationCenter(center, model, message -> message);
        assertThat(ElementScreen.render(root, 1, 4).lines()).containsExactly("\u2716 fa");
        assertThat(ElementScreen.render(root, 1, 2).lines()).containsExactly("\u2716");
        assertThat(ElementScreen.render(root, 1, 1).lines()).containsExactly("\u2716");
        assertThat(ElementScreen.render(root, 1, 0).lines()).containsExactly("");
        // A message with no text is only the marker, so a row still says which severity it carries.
        assertThat(ElementScreen.render(
                                Elements.notificationCenter(
                                        center,
                                        model.withNotifications(List.of(Notification.of("a", Severity.INFO, ""))),
                                        message -> message),
                                1,
                                4)
                        .lines())
                .containsExactly(Severity.INFO.marker());
    }

    @Test
    void notificationsAndModelsAreValidatedAndNormalized() {
        assertThatThrownBy(() -> Notification.of(" ", "text"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notification id");
        assertThatThrownBy(() -> new Notification(null, null, null, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Notification("a", null, null, false).severity()).isEqualTo(Severity.INFO);
        assertThat(new Notification("a", null, null, false).text().isEmpty()).isTrue();
        assertThat(Notification.of("a", (Severity) null, null).severity()).isEqualTo(Severity.INFO);
        assertThat(Notification.of("a", null).text().isEmpty()).isTrue();
        assertThat(Notification.of("a", Severity.ERROR, "x").withRead(true).read())
                .isTrue();
        assertThat(Notification.of("a", Severity.ERROR, "x")
                        .withText(null)
                        .text()
                        .isEmpty())
                .isTrue();

        assertThatThrownBy(() -> NotificationCenter.Config.of(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> new NotificationCenter.Config(1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rows");
        assertThatThrownBy(() -> new NotificationCenter(null)).isInstanceOf(NullPointerException.class);
        assertThat(NotificationCenter.Config.defaults().limit()).isEqualTo(NotificationCenter.DEFAULT_LIMIT);
        assertThat(NotificationCenter.Config.defaults().rows()).isEqualTo(NotificationCenter.DEFAULT_ROWS);
        assertThat(NotificationCenter.Config.of(3).withRows(2).rows()).isEqualTo(2);
        assertThat(NotificationCenter.Config.of(3).withLimit(4).limit()).isEqualTo(4);
        assertThat(new NotificationCenter().config()).isEqualTo(NotificationCenter.Config.defaults());

        // A model keeps its ids unique and resolves its selection to something it shows.
        assertThatThrownBy(() -> new NotificationCenter.Model(
                        List.of(Notification.of("a", "one"), Notification.of("a", "again")), "", true, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate notification id");
        assertThatThrownBy(() ->
                        new NotificationCenter.Model(Arrays.asList(Notification.of("a", "one"), null), "", true, 0, 2))
                .isInstanceOf(NullPointerException.class);
        NotificationCenter.Model model =
                new NotificationCenter.Model(List.of(Notification.of("a", "one")), null, true, 0, 2);
        assertThat(model.selected()).isEqualTo("a");
        assertThat(NotificationCenter.Model.empty().selected()).isEmpty();
        assertThat(NotificationCenter.Model.empty().notifications()).isEmpty();
        assertThat(model.withSelected(null).selected()).isEqualTo("a");
        assertThat(model.withFocus(false).focused()).isFalse();
        assertThat(model.withNotifications(null).notifications()).isEmpty();
        assertThat(model.withNotifications(null).selected()).isEmpty();
        assertThat(model.visibleRows()).hasSize(1);
        assertThat(model.visibleRows().get(0).id()).isEqualTo("a");
        assertThat(model.visibleRows().get(0).selected()).isTrue();
        assertThat(model.visibleRows().get(0).focused()).isTrue();
        assertThat(model.unread()).isEqualTo(1);

        // An empty center answers nothing and keeps the instance the runtime compares.
        NotificationCenter.Model empty = NotificationCenter.Model.empty();
        assertThat(center.update(empty, new Msg.Up()).model()).isSameAs(empty);
        assertThat(center.update(empty, new Msg.Down()).model()).isSameAs(empty);
        assertThat(center.update(empty, new Msg.Home()).model()).isSameAs(empty);
        assertThat(center.update(empty, new Msg.End()).model()).isSameAs(empty);
        assertThat(center.update(empty, new Msg.Activate()).model()).isSameAs(empty);
        assertThat(center.update(empty, new Msg.Activate()).outputs()).isEmpty();

        assertThatThrownBy(() -> center.init(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> center.update(null, new Msg.Activate())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> center.update(empty, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> center.view(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> center.element(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Msg.Push(null)).isInstanceOf(NullPointerException.class);
        assertThat(center.update(empty, new Msg.Push(Notification.of("a", "one")))
                        .outputs())
                .isEmpty();
        assertThat(new NotificationCenter.Model(List.of(), "", true, 0, 2).visibleRows())
                .isEmpty();
        assertThat(new NotificationCenter.Model(
                                List.of(new Notification("a", Severity.INFO, StyledText.plain("one"), false)),
                                "",
                                true,
                                0,
                                2)
                        .unread())
                .isEqualTo(1);
    }

    private NotificationCenter.Model push(NotificationCenter.Model model, String id, Severity severity, String text) {
        return center.update(model, new Msg.Push(Notification.of(id, severity, text)))
                .model();
    }

    private NotificationCenter.Model apply(NotificationCenter.Model model, Msg message) {
        return center.update(model, message).model();
    }

    private static NotificationCenter.Model apply(
            NotificationCenter list, NotificationCenter.Model model, Msg... messages) {
        NotificationCenter.Model next = model;
        for (Msg message : messages) {
            next = list.update(next, message).model();
        }
        return next;
    }

    private List<Msg> route(Element<Msg> root, String key) {
        return InteractionRouter.route(
                root,
                InputEvent.KeyInput.of(Key.parse(key)),
                List.of(),
                center.keys().bindings());
    }

    private static List<Msg> press(Element<Msg> root, int rows, int cols, int row, int col) {
        return press(root, rows, cols, PointerEvent.press(PointerButton.LEFT, Modifiers.NONE, row, col));
    }

    private static List<Msg> press(Element<Msg> root, int rows, int cols, PointerEvent report) {
        List<Msg> recorded = new ArrayList<>();
        ElementTree.Placement<Msg> placement = ElementTree.layout(root, new Rect(0, 0, rows, cols));
        InteractionRouter.pointer(placement, InteractionRouter.Interaction.NONE, report, recorded::add);
        return recorded;
    }
}

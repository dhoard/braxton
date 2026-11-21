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

import static io.github.dhoard.braxton.widget.RichList.BROWSING;
import static io.github.dhoard.braxton.widget.RichList.CHROME;

import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import java.util.Objects;

/**
 * List browsing and filter editing key maps.
 */
final class RichListKeys {

    /**
     * The actions a list defines, before its state is taken into account.
     *
     * <p>The two {@code enter} actions are declared together and never enabled together: browsing
     * chooses, editing applies the query. {@link #keys(RichListModel)} is the map for one state, and it is
     * what the component routes and what help renders.</p>
     *
     * @return the default actions
     */
    public static KeyMap<RichListMsg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Move",
                        KeyAction.<RichListMsg>of("up", new RichListMsg.Up(), "\u2191", "up", "up", "k"),
                        KeyAction.<RichListMsg>of("down", new RichListMsg.Down(), "\u2193", "down", "down", "j")),
                KeyMap.group(
                        "Page",
                        KeyAction.<RichListMsg>of(
                                "page-previous",
                                new RichListMsg.PreviousPage(),
                                "\u2190",
                                "previous page",
                                "left",
                                "pageup"),
                        KeyAction.<RichListMsg>of(
                                "page-next",
                                new RichListMsg.NextPage(),
                                "\u2192",
                                "next page",
                                "right",
                                "l",
                                "pagedown"),
                        KeyAction.<RichListMsg>of("first", new RichListMsg.First(), "home", "first item", "home")
                                .withCompact(false),
                        KeyAction.<RichListMsg>of("last", new RichListMsg.Last(), "end", "last item", "end")
                                .withCompact(false)),
                KeyMap.group(
                        "RichListFilter",
                        KeyAction.<RichListMsg>of("filter", new RichListMsg.BeginFilter(), "/", "filter", "/")
                                .withCompact(false),
                        KeyAction.<RichListMsg>of(
                                        "filter-mode",
                                        new RichListMsg.ToggleFilterMode(),
                                        "ctrl+f",
                                        "plain/fuzzy",
                                        "ctrl+f")
                                .withCompact(false),
                        KeyAction.<RichListMsg>of(
                                        "accept-filter",
                                        new RichListMsg.AcceptFilter(),
                                        "enter",
                                        "apply filter",
                                        "enter")
                                .withEnabled(false)),
                KeyMap.group(
                        "Actions",
                        KeyAction.<RichListMsg>of("choose", new RichListMsg.Choose(), "enter", "choose", "enter"),
                        KeyAction.<RichListMsg>of("mark", new RichListMsg.ToggleMark(), "space", "mark", "space")
                                .withCompact(false),
                        KeyAction.<RichListMsg>of("mark-all", new RichListMsg.MarkAll(), "ctrl+a", "mark all", "ctrl+a")
                                .withCompact(false)),
                KeyMap.group(
                        "Chrome",
                        KeyAction.<RichListMsg>of("title", new RichListMsg.ToggleTitle(), "t", "title", "t")
                                .withCompact(false),
                        KeyAction.<RichListMsg>of("status", new RichListMsg.ToggleStatus(), "c", "counts", "c")
                                .withCompact(false),
                        KeyAction.<RichListMsg>of(
                                        "pagination", new RichListMsg.TogglePagination(), "p", "pagination", "p")
                                .withCompact(false),
                        KeyAction.<RichListMsg>of("help", new RichListMsg.ToggleHelp(), "h", "help", "h")
                                .withCompact(false),
                        KeyAction.<RichListMsg>of(
                                        "expand-help", new RichListMsg.ToggleExpandedHelp(), "?", "full help", "?")
                                .withCompact(false),
                        KeyAction.<RichListMsg>of("loading", new RichListMsg.ToggleLoading(), "s", "loading", "s")
                                .withCompact(false)),
                KeyMap.group(
                        "General",
                        KeyAction.<RichListMsg>of("cancel", new RichListMsg.Cancel(), "esc", "cancel", "escape")));
    }

    /**
     * The actions one state handles: the definition with the actions that state disables or
     * relabels.
     *
     * <p>While a query is being edited only the filter's own gestures are enabled, so a printable key
     * that also names a browsing action types its character instead of moving the selection. The
     * cancel action says what it will do - cancel the edit, clear the applied query, or cancel the
     * list - so help never describes a gesture by what it did in another state.</p>
     *
     * @param state the state the actions are built for; required
     * @return the key map
     */
    public static KeyMap<RichListMsg> keys(RichListModel state) {
        Objects.requireNonNull(state, "state");
        boolean editing = state.filter().editing();
        boolean browsing = !editing && !state.items().isEmpty();
        KeyMap<RichListMsg> keys = defaultKeys();
        for (String id : BROWSING) {
            keys = keys.withEnabled(id, browsing);
        }
        for (String id : CHROME) {
            keys = keys.withEnabled(id, !editing);
        }
        keys = keys.withEnabled("accept-filter", editing);
        // One gesture cancels everything: the edit being typed, then the applied query, then the
        // list itself. Binding a second action to it would make the winner depend on declaration
        // order, which is exactly what a key map refuses to do.
        KeyAction<RichListMsg> cancel = keys.action("cancel").orElseThrow();
        return keys.with(cancel.withDescription(
                editing
                        ? "cancel edit"
                        : RichListMatching.terms(state.filter().query()).isEmpty() ? "cancel" : "clear filter"));
    }
}

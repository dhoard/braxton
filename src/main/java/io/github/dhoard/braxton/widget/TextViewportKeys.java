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

import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.widget.TextViewport.Config;
import io.github.dhoard.braxton.widget.TextViewport.Mode;
import io.github.dhoard.braxton.widget.TextViewport.Model;
import io.github.dhoard.braxton.widget.TextViewport.Msg;
import java.util.Objects;

/**
 * Viewport navigation key map.
 */
final class TextViewportKeys {

    /**
     * The gestures a pager defines, before the state is taken into account.
     *
     * <p>They are the pager's own: rows and pages vertically, cells horizontally, and the two match
     * gestures. {@link #keys(Model, Config)} is the map for one state - it disables the horizontal
     * gestures where there is nothing to pan and the match gestures while there is no match - and it
     * is what the component routes and what help renders.</p>
     *
     * @return the default gestures
     */
    public static KeyMap<Msg> defaultKeys() {
        return KeyMap.of(
                KeyMap.group(
                        "Move",
                        KeyAction.<Msg>of("up", new Msg.Up(1), "\u2191/k", "up", "up", "k", "shift+up"),
                        KeyAction.<Msg>of("down", new Msg.Down(1), "\u2193/j", "down", "down", "j", "shift+down")),
                KeyMap.group(
                        "Page",
                        KeyAction.<Msg>of("page-up", new Msg.PageUp(), "b/pgup", "page up", "pageup", "b"),
                        KeyAction.<Msg>of("page-down", new Msg.PageDown(), "f/pgdn", "page down", "pagedown", "f"),
                        KeyAction.<Msg>of("half-page-up", new Msg.HalfPageUp(), "u", "half page up", "u")
                                .withCompact(false),
                        KeyAction.<Msg>of("half-page-down", new Msg.HalfPageDown(), "d", "half page down", "d")
                                .withCompact(false),
                        KeyAction.<Msg>of("top", new Msg.Top(), "home", "top", "home")
                                .withCompact(false),
                        KeyAction.<Msg>of("bottom", new Msg.Bottom(), "end", "bottom", "end")
                                .withCompact(false)),
                KeyMap.group(
                        "Pan",
                        KeyAction.<Msg>of("left", new Msg.Left(1), "\u2190/h", "left", "left", "h"),
                        KeyAction.<Msg>of("right", new Msg.Right(1), "\u2192/l", "right", "right", "l")),
                KeyMap.group(
                        "Match",
                        KeyAction.<Msg>of("next-match", new Msg.NextMatch(), "n", "next match", "n"),
                        // A legacy terminal reports an upper-case letter without any modifier the
                        // key map can name, so Alt is the portable spelling of the reference's "N"
                        // and Shift is the one an enhanced keyboard reports.
                        KeyAction.<Msg>of(
                                "previous-match",
                                new Msg.PreviousMatch(),
                                "alt+n",
                                "previous match",
                                "alt+n",
                                "shift+n")));
    }

    /**
     * The gestures one state handles.
     *
     * <p>Help and routing read the same map, so a gesture help shows is a gesture that fires: a
     * wrapping viewport has no horizontal offset to move, and a viewport with no published ranges
     * has no match to step to.</p>
     *
     * @param state the state the gestures are built for; required
     * @param config the definition, which decides whether there is anything to pan; null is
     *     {@link Config#defaults()}
     * @return the gestures
     */
    public static KeyMap<Msg> keys(Model state, Config config) {
        Objects.requireNonNull(state, "state");
        Config opts = config == null ? Config.defaults() : config;
        KeyMap<Msg> keys = defaultKeys();
        boolean panning = opts.mode() == Mode.PAN;
        keys = keys.withEnabled("left", panning).withEnabled("right", panning);
        boolean matches = !state.highlights().isEmpty();
        return keys.withEnabled("next-match", matches).withEnabled("previous-match", matches);
    }
}

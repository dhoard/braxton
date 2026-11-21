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
import io.github.dhoard.braxton.widget.TextInput.Msg;
import java.util.List;

/**
 * Single-line editing key map.
 */
final class TextInputKeys {

    /**
     * The default actions for one policy.
     *
     * <p>Enter submits, because a single-line field has no line to break. Tab accepts the selected
     * candidate, and with nothing to accept the gesture is not consumed: it falls through to
     * whatever the surrounding application binds it to, which is how a form moves focus with the
     * same key that completes a field. Next and previous fall through as well when fewer than two
     * candidates match.</p>
     *
     * @param config the policy; null is {@link TextInputConfig#defaults()}
     * @return the default actions
     */
    public static KeyMap<Msg> defaultKeys(TextInputConfig config) {
        TextInputConfig opts = config == null ? TextInputConfig.defaults() : config;
        return KeyMap.of(
                KeyMap.group(
                        "Editing",
                        KeyAction.<Msg>of(
                                        "backspace",
                                        new Msg.Backspace(),
                                        "backspace",
                                        "delete back",
                                        "backspace",
                                        "alt+backspace")
                                .withCompact(false),
                        KeyAction.<Msg>of("delete", new Msg.Delete(), "delete", "delete forward", "delete")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "delete-word-back",
                                        new Msg.DeleteWordBackward(),
                                        "",
                                        "delete word back",
                                        "ctrl+backspace",
                                        "alt+w")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "delete-word-forward",
                                        new Msg.DeleteWordForward(),
                                        "",
                                        "delete word",
                                        "ctrl+delete",
                                        "alt+d")
                                .withCompact(false),
                        KeyAction.<Msg>of("select-all", new Msg.SelectAll(), "ctrl+a", "select all", "ctrl+a")
                                .withCompact(false)),
                KeyMap.group(
                        "Navigation",
                        KeyAction.<Msg>of("left", new Msg.MoveLeft(), "\u2190", "left", "left")
                                .withCompact(false),
                        KeyAction.<Msg>of("right", new Msg.MoveRight(), "\u2192", "right", "right")
                                .withCompact(false),
                        KeyAction.<Msg>of("word-left", new Msg.MoveWordLeft(), "", "word left", "ctrl+left", "alt+b")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "word-right", new Msg.MoveWordRight(), "", "word right", "ctrl+right", "alt+f")
                                .withCompact(false),
                        KeyAction.<Msg>of("start", new Msg.MoveStart(), "home", "line start", "home")
                                .withCompact(false),
                        KeyAction.<Msg>of("end", new Msg.MoveEnd(), "end", "line end", "end")
                                .withCompact(false)),
                KeyMap.group(
                        "Select",
                        KeyAction.<Msg>of(
                                        "select-left",
                                        new Msg.Extend(new Msg.MoveLeft()),
                                        "",
                                        "select left",
                                        "shift+left")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-right",
                                        new Msg.Extend(new Msg.MoveRight()),
                                        "",
                                        "select right",
                                        "shift+right")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-word-left",
                                        new Msg.Extend(new Msg.MoveWordLeft()),
                                        "",
                                        "select word left",
                                        "ctrl+shift+left")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-word-right",
                                        new Msg.Extend(new Msg.MoveWordRight()),
                                        "",
                                        "select word right",
                                        "ctrl+shift+right")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-start",
                                        new Msg.Extend(new Msg.MoveStart()),
                                        "",
                                        "select to start",
                                        "shift+home")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-end",
                                        new Msg.Extend(new Msg.MoveEnd()),
                                        "",
                                        "select to end",
                                        "shift+end")
                                .withCompact(false)),
                KeyMap.group(
                        "Completion",
                        KeyAction.<Msg>of("accept-suggestion", new Msg.AcceptSuggestion(), "tab", "complete", "tab"),
                        KeyAction.<Msg>of(
                                "next-suggestion", new Msg.NextSuggestion(), "ctrl+n", "next completion", "ctrl+n"),
                        KeyAction.<Msg>of(
                                "previous-suggestion",
                                new Msg.PreviousSuggestion(),
                                "ctrl+p",
                                "previous completion",
                                "ctrl+p")),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("submit", new Msg.Submit(), "enter", "submit", "enter", "alt+enter"),
                        KeyAction.<Msg>of("copy", new Msg.Copy(), "", "copy", List.of(opts.copyKey())),
                        KeyAction.<Msg>of("paste", new Msg.PasteRequest(), "", "paste", List.of(opts.pasteKey()))));
    }
}

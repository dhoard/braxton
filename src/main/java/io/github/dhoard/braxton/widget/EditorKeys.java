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

import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.widget.Editor.Msg;
import java.util.ArrayList;
import java.util.List;

/**
 * Editing key maps and input translation.
 */
final class EditorKeys {

    static List<Key> parse(String... specs) {
        List<Key> keys = new ArrayList<>(specs.length);
        for (String spec : specs) {
            keys.add(Key.parse(spec));
        }
        return List.copyOf(keys);
    }

    static KeyMap<Msg> keys(EditorConfig config, boolean enterInsertsNewline) {
        EditorConfig opts = config == null ? EditorConfig.defaults() : config;
        // Enter is one gesture with two meanings, so exactly one action claims it: the other action
        // keeps a gesture of its own, which is what "exposes submit separately" means.
        List<Key> newline = enterInsertsNewline ? parse("enter", "ctrl+j") : parse("ctrl+j");
        List<Key> submit = enterInsertsNewline ? parse("ctrl+enter", "alt+enter") : parse("enter", "alt+enter");
        String newlineLabel = enterInsertsNewline ? "enter" : "ctrl+j";
        String submitLabel = enterInsertsNewline ? "ctrl+enter" : "enter";
        return KeyMap.of(
                KeyMap.group(
                        "Editing",
                        KeyAction.<Msg>of("newline", new Msg.Newline(), newlineLabel, "new line", newline)
                                .withCompact(false),
                        // The name-only switch this replaced also accepted the Alt-modified forms a
                        // terminal sends for Meta+key, which the decoder normalizes to alt+backspace
                        // and alt+enter.
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
                        KeyAction.<Msg>of("up", new Msg.MoveUp(), "\u2191", "up", "up")
                                .withCompact(false),
                        KeyAction.<Msg>of("down", new Msg.MoveDown(), "\u2193", "down", "down")
                                .withCompact(false),
                        KeyAction.<Msg>of("word-left", new Msg.MoveWordLeft(), "", "word left", "ctrl+left", "alt+b")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "word-right", new Msg.MoveWordRight(), "", "word right", "ctrl+right", "alt+f")
                                .withCompact(false),
                        KeyAction.<Msg>of("home", new Msg.MoveHome(), "home", "line start", "home")
                                .withCompact(false),
                        KeyAction.<Msg>of("end", new Msg.MoveEnd(), "end", "line end", "end")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "document-start",
                                        new Msg.MoveDocumentStart(),
                                        "",
                                        "document start",
                                        "ctrl+home")
                                .withCompact(false),
                        KeyAction.<Msg>of("document-end", new Msg.MoveDocumentEnd(), "", "document end", "ctrl+end")
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
                        KeyAction.<Msg>of("select-up", new Msg.Extend(new Msg.MoveUp()), "", "select up", "shift+up")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-down",
                                        new Msg.Extend(new Msg.MoveDown()),
                                        "",
                                        "select down",
                                        "shift+down")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-home",
                                        new Msg.Extend(new Msg.MoveHome()),
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
                                        "select-document-start",
                                        new Msg.Extend(new Msg.MoveDocumentStart()),
                                        "",
                                        "select to document start",
                                        "ctrl+shift+home")
                                .withCompact(false),
                        KeyAction.<Msg>of(
                                        "select-document-end",
                                        new Msg.Extend(new Msg.MoveDocumentEnd()),
                                        "",
                                        "select to document end",
                                        "ctrl+shift+end")
                                .withCompact(false)),
                KeyMap.group(
                        "Actions",
                        KeyAction.<Msg>of("submit", new Msg.Submit(), submitLabel, "submit", submit),
                        KeyAction.<Msg>of("copy", new Msg.Copy(), "", "copy", List.of(opts.copyKey())),
                        KeyAction.<Msg>of("paste", new Msg.PasteRequest(), "", "paste", List.of(opts.pasteKey()))));
    }
}

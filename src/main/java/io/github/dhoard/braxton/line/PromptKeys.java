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

package io.github.dhoard.braxton.line;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import java.util.Optional;

/**
 * The one definition of what the list prompts' keys mean.
 *
 * <p>Two maps, because a single-choice list and a multiple-choice list answer one gesture
 * differently: {@code space} chooses the highlighted option in the first and marks it in the second.
 * Everything else is the same, so the movement, the choice and the abandonment of a prompt are
 * documented and remapped in one place rather than restated by each prompt.</p>
 *
 * <p>A map is the prompt's whole keyboard: a gesture it does not name does nothing, which is what
 * keeps a prompt from acting on a key the user meant for the program that will run after it. Paste
 * is not a gesture here either - a pasted block is text, and a list prompt inserts no text - so a
 * paste is ignored rather than being turned into a choice.</p>
 */
final class PromptKeys {

    /**
     * The single-choice list: {@code space} and {@code enter} both choose.
     */
    private static final KeyMap<Command> SINGLE = build(true);

    /**
     * The multiple-choice list: {@code space} marks and {@code enter} confirms.
     */
    private static final KeyMap<Command> MULTIPLE = build(false);

    private PromptKeys() {}

    /**
     * What one gesture asks a prompt to do.
     */
    enum Command {

        /**
         * Moves the highlight one option toward the start.
         */
        UP,

        /**
         * Moves the highlight one option toward the end.
         */
        DOWN,

        /**
         * Moves the highlight to the first option.
         */
        FIRST,

        /**
         * Moves the highlight to the last option.
         */
        LAST,

        /**
         * Moves the highlight one window toward the start.
         */
        PAGE_UP,

        /**
         * Moves the highlight one window toward the end.
         */
        PAGE_DOWN,

        /**
         * Answers the prompt with what it holds: the highlight, or the marks.
         */
        CHOOSE,

        /**
         * Marks or unmarks the highlighted option; only a multiple-choice list answers it.
         */
        TOGGLE,

        /**
         * Abandons the prompt without an answer.
         */
        CANCEL
    }

    /**
     * The map a single-choice list answers with, built once.
     *
     * @return the map
     */
    static KeyMap<Command> single() {
        return SINGLE;
    }

    /**
     * The map a multiple-choice list answers with, built once.
     *
     * @return the map
     */
    static KeyMap<Command> multiple() {
        return MULTIPLE;
    }

    /**
     * The command one input event asks for.
     *
     * <p>A key release, a paste, a focus change, a terminal reply or a gesture neither map claims
     * asks for nothing, and the caller leaves its prompt exactly as it was.</p>
     *
     * @param map the map to match against; required
     * @param event the event; null asks for nothing
     * @return the command, or empty
     */
    static Optional<Command> command(KeyMap<Command> map, InputEvent event) {
        if (map == null || !(event instanceof InputEvent.KeyInput key) || !key.activates()) {
            return Optional.empty();
        }
        return map.actionFor(key.key()).map(KeyAction::message);
    }

    /**
     * One map, from the one place the two lists differ.
     *
     * <p>The LF terminal's Enter, which the decoder can only report as {@code ctrl+j}, is a second
     * alias of {@code enter} - the rule every other key map in this library follows - because a
     * prompt that could not be answered from such a terminal would be unusable rather than merely
     * limited.</p>
     */
    private static KeyMap<Command> build(boolean singleChoice) {
        return KeyMap.of(
                KeyMap.group(
                        "Move",
                        KeyAction.of("up", Command.UP, "", "the option before", "up"),
                        KeyAction.of("down", Command.DOWN, "", "the option after", "down"),
                        KeyAction.of("first", Command.FIRST, "", "the first option", "home"),
                        KeyAction.of("last", Command.LAST, "", "the last option", "end"),
                        KeyAction.of("page-up", Command.PAGE_UP, "", "one window back", "pageup"),
                        KeyAction.of("page-down", Command.PAGE_DOWN, "", "one window on", "pagedown")),
                KeyMap.group(
                        "Answer",
                        KeyAction.of(
                                "choose",
                                Command.CHOOSE,
                                "",
                                singleChoice ? "choose the highlighted option" : "accept the marked options",
                                "enter",
                                "ctrl+j"),
                        singleChoice
                                ? KeyAction.of(
                                        "choose-space", Command.CHOOSE, "", "choose the highlighted option", "space")
                                : KeyAction.of(
                                        "toggle", Command.TOGGLE, "", "mark or unmark the highlighted option", "space"),
                        KeyAction.of("cancel", Command.CANCEL, "", "abandon the prompt", "escape", "ctrl+c")));
    }
}

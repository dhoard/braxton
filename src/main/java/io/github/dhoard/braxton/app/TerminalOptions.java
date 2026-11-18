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

package io.github.dhoard.braxton.app;

import io.github.dhoard.braxton.input.KeyboardEnhancements;

/**
 * Session behaviour an application can request before it starts.
 *
 * <p>Full-window applications use the alternate screen by default so exit restores the user's
 * scrollback instead of erasing it. An application that wants its output to stay where the user can
 * scroll back to it turns the alternate screen off with {@link #withAlternateScreen(boolean)}: the
 * session then manages a live region at the row it started on, repaints it in place, never clears
 * anything above it, and leaves its final frame on screen with the cursor on the line below. Durable
 * lines an application prints with {@code Command.println} are written above that region. Pointer
 * reporting is opt-in; bracketed paste defaults on when supported.</p>
 *
 * <p>Enhanced keyboard input and terminal focus reporting are opt-in and off by default, so an
 * application that does not ask keeps exactly the legacy keyboard behaviour it had. Both are
 * requested rather than inferred: whether a terminal supports either is unknown until it answers,
 * and a terminal that ignores a request simply keeps sending legacy input.</p>
 *
 * @param alternateScreen whether to enter the alternate screen for the session
 * @param mouseMode how much pointer input the terminal is asked to report; {@link MouseMode#NONE}
 *     asks for none
 * @param bracketedPaste whether to enable bracketed paste
 * @param keyboardEnhancements the enhanced keyboard facts to request; {@link KeyboardEnhancements#NONE}
 *     requests none
 * @param focusReporting whether to ask the terminal to report gaining and losing focus
 */
public record TerminalOptions(
        boolean alternateScreen,
        MouseMode mouseMode,
        boolean bracketedPaste,
        KeyboardEnhancements keyboardEnhancements,
        boolean focusReporting) {

    public static final TerminalOptions DEFAULT =
            new TerminalOptions(true, MouseMode.NONE, true, KeyboardEnhancements.NONE, false);

    public TerminalOptions {
        mouseMode = mouseMode == null ? MouseMode.NONE : mouseMode;
        keyboardEnhancements = keyboardEnhancements == null ? KeyboardEnhancements.NONE : keyboardEnhancements;
    }

    /**
     * The same options with pointer reporting turned on or off.
     *
     * <p>Turning it on asks for {@link MouseMode#CLICK}: presses, releases and wheel gestures, with
     * no motion reports. Use {@link #withMouseMode(MouseMode)} to ask for drags or hover instead.
     * This convenience is the pre-existing shape of the option, kept so a caller that only ever
     * wanted clicks does not have to name a mode.</p>
     *
     * @param alternateScreen whether to enter the alternate screen for the session
     * @param mouse whether to report presses, releases and wheel gestures
     * @param bracketedPaste whether to enable bracketed paste
     * @param keyboardEnhancements the enhanced keyboard facts to request
     * @param focusReporting whether to ask the terminal to report focus changes
     */
    public TerminalOptions(
            boolean alternateScreen,
            boolean mouse,
            boolean bracketedPaste,
            KeyboardEnhancements keyboardEnhancements,
            boolean focusReporting) {
        this(
                alternateScreen,
                mouse ? MouseMode.CLICK : MouseMode.NONE,
                bracketedPaste,
                keyboardEnhancements,
                focusReporting);
    }

    /** The legacy session options, with no enhanced keyboard input and no focus reporting.
     *
     * @param alternateScreen whether to enter the alternate screen for the session
     * @param mouse whether to report presses, releases and wheel gestures
     * @param bracketedPaste whether to enable bracketed paste
     */
    public TerminalOptions(boolean alternateScreen, boolean mouse, boolean bracketedPaste) {
        this(alternateScreen, mouse, bracketedPaste, KeyboardEnhancements.NONE, false);
    }

    /**
     * Whether pointer input is reported at all.
     *
     * @return true when a mode other than {@link MouseMode#NONE} is configured
     */
    public boolean mouse() {
        return mouseMode != MouseMode.NONE;
    }

    /**
     * The same options with pointer reporting turned on or off, in the click-only mode.
     *
     * @param value whether to report presses, releases and wheel gestures
     * @return the new options
     */
    public TerminalOptions withMouse(boolean value) {
        return withMouseMode(value ? MouseMode.CLICK : MouseMode.NONE);
    }

    /**
     * The same options with another pointer reporting mode.
     *
     * @param value the mode to ask for; null asks for none
     * @return the new options
     */
    public TerminalOptions withMouseMode(MouseMode value) {
        MouseMode requested = value == null ? MouseMode.NONE : value;
        return requested == mouseMode
                ? this
                : new TerminalOptions(alternateScreen, requested, bracketedPaste, keyboardEnhancements, focusReporting);
    }

    /**
     * The same options with or without the alternate screen.
     *
     * <p>Without it the session paints a live region on the terminal's own screen, which is what an
     * application that wants a command's output to survive needs; with it the session owns the whole
     * screen and gives it back on exit. A supplied stream negotiates no alternate screen at all, so a
     * stream session is always inline whatever this option says.</p>
     *
     * @param value whether to enter the alternate screen for the session
     * @return the new options
     */
    public TerminalOptions withAlternateScreen(boolean value) {
        return new TerminalOptions(value, mouseMode, bracketedPaste, keyboardEnhancements, focusReporting);
    }

    public TerminalOptions withBracketedPaste(boolean value) {
        return new TerminalOptions(alternateScreen, mouseMode, value, keyboardEnhancements, focusReporting);
    }

    /**
     * The same options with another enhanced-keyboard request.
     *
     * @param value the flags to request; null requests none
     * @return the new options
     */
    public TerminalOptions withKeyboardEnhancements(KeyboardEnhancements value) {
        return new TerminalOptions(alternateScreen, mouseMode, bracketedPaste, value, focusReporting);
    }

    /**
     * The same options with terminal focus reporting chosen.
     *
     * @param value whether to ask the terminal to report focus changes
     * @return the new options
     */
    public TerminalOptions withFocusReporting(boolean value) {
        return new TerminalOptions(alternateScreen, mouseMode, bracketedPaste, keyboardEnhancements, value);
    }
}

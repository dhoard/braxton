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

package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.app.MouseMode;
import io.github.dhoard.braxton.app.TerminalOptions;
import io.github.dhoard.braxton.app.TerminalPresentation;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.terminal.Capabilities;
import java.util.Objects;

/**
 * The session's terminal presentation, and only the sequences that change it.
 *
 * <p>The runtime owns one presenter per session. Each frame hands it the intent the root view
 * declared, and it answers with the sequences that take the terminal from what this session last
 * applied to what the frame asks for - a screen buffer, a pointer level, a title, a color default, a
 * progress value and a status record, each written only when it differs. A frame that asks for
 * nothing new writes nothing at all, so an idle application keeps emitting no traffic.</p>
 *
 * <p>Resolution is a function of the frame, not a running log of requests: a field the intent leaves
 * out keeps the {@link TerminalOptions} the session opened with, and nothing accumulates. The ledger
 * is what makes "diff, then write" possible, and it is committed by {@link #commit} only after the
 * frame was written, so a failed frame cannot leave the session believing it entered a mode it never
 * announced.</p>
 *
 * <p>Everything the session changed is undone here: {@link #restoreModes()} gives back the pointer,
 * paste and focus modes, and {@link #restoreMetadata()} pops the title, resets the color defaults,
 * removes the progress value and clears a status record that the protocol does not retain. A final
 * {@code done} or {@code error} record is deliberately left in place, because the protocol keeps a
 * finished result for the user to find.</p>
 *
 * <p>Only the sequences that a session actually needs are written. A terminal that does not implement
 * a sequence ignores it, which is what makes an intent safe to state without negotiating first;
 * nothing here assumes support, and nothing here writes anything at startup that the options did not
 * ask for.</p>
 */
public final class TerminalPresenter {

    /**
     * What one frame would write, and the ledger it would leave behind.
     *
     * <p>A transition is computed without touching the committed ledger, so the runtime can write the
     * frame first and {@link #commit} only when the write succeeded. The state it carries is private:
     * callers read {@link #sequences()} and {@link #bufferChanged()}, and hand the transition back.</p>
     */
    public static final class Transition {

        private final String sequences;
        private final boolean bufferChanged;
        private final Applied next;

        private Transition(String sequences, boolean bufferChanged, Applied next) {
            this.sequences = sequences;
            this.bufferChanged = bufferChanged;
            this.next = next;
        }

        /**
         * The sequences that take the terminal to the frame's intent.
         *
         * @return the escape sequences, or an empty string when nothing changed
         */
        public String sequences() {
            return sequences;
        }

        /**
         * Whether the terminal is on the alternate screen once this transition is written.
         *
         * <p>The runtime paints differently on the two buffers: the alternate screen is addressed
         * absolutely because it is entirely the session's, while the normal screen is painted
         * relatively because the content above the live region belongs to the user.</p>
         *
         * @return true when this frame's buffer is the alternate screen
         */
        public boolean alternateScreen() {
            return next.alternateScreen();
        }

        /**
         * Whether the frame asked for another screen buffer.
         *
         * <p>A switched buffer holds different cells, so the runtime has to repaint everything rather
         * than diff against the screen it presented on the other one.</p>
         *
         * @return true when the alternate screen was entered or left
         */
        public boolean bufferChanged() {
            return bufferChanged;
        }
    }

    /**
     * Everything the session has applied to the terminal and will have to give back.
     *
     * @param alternateScreen whether the session is drawing on the alternate screen
     * @param mouse the pointer level the terminal is reporting at
     * @param bracketedPaste whether bracketed paste is enabled
     * @param focusReporting whether the terminal is reporting focus changes
     * @param title the window title this session set, or null when it set none
     * @param titlePushed whether the title this session replaced was saved and must be popped
     * @param foreground the default text color this session set, or null
     * @param background the default background color this session set, or null
     * @param cursorColor the default cursor color this session set, or null
     * @param progress the progress value on show, or null for none
     * @param status the status record on show, or null for none
     */
    private record Applied(
            boolean alternateScreen,
            MouseMode mouse,
            boolean bracketedPaste,
            boolean focusReporting,
            String title,
            boolean titlePushed,
            Color foreground,
            Color background,
            Color cursorColor,
            TerminalPresentation.Progress progress,
            TerminalPresentation.Status status) {}

    private final Capabilities capabilities;
    private final boolean defaultAlternateScreen;
    private final MouseMode defaultMouse;
    private final boolean defaultBracketedPaste;
    private final boolean defaultFocusReporting;

    private boolean color;
    private Applied applied;

    /**
     * Creates a presenter for a session that has not written anything yet.
     *
     * <p>The defaults are what a frame that declares nothing keeps: the session's own options, minus
     * anything the attached terminal cannot do. Resolving them once means a later frame cannot ask for
     * a mode this terminal never offered.</p>
     *
     * @param options the session options the runtime opened with; null is the documented default
     * @param capabilities the attached terminal's capabilities; null is a terminal that offers none
     * @param color whether color output is enabled, which also gates the default colors
     */
    public TerminalPresenter(TerminalOptions options, Capabilities capabilities, boolean color) {
        this.capabilities = capabilities == null ? Capabilities.PLAIN : capabilities;
        TerminalOptions defaults = options == null ? TerminalOptions.DEFAULT : options;
        this.defaultAlternateScreen = defaults.alternateScreen() && this.capabilities.alternateScreen();
        this.defaultMouse = defaults.mouse() && this.capabilities.mouse() ? defaults.mouseMode() : MouseMode.NONE;
        this.defaultBracketedPaste = defaults.bracketedPaste() && this.capabilities.bracketedPaste();
        this.defaultFocusReporting = defaults.focusReporting();
        this.color = color;
        this.applied = sessionDefaults();
    }

    /**
     * The modes a session that has applied nothing of its own is asking its terminal for.
     */
    private Applied sessionDefaults() {
        return new Applied(
                defaultAlternateScreen,
                defaultMouse,
                defaultBracketedPaste,
                defaultFocusReporting,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Changes whether the session may set the terminal's default colors.
     *
     * <p>A session that loses color gives the defaults back, exactly as the cursor presenter gives
     * back its caret color: a palette the application imposed would otherwise outlive the profile it
     * was chosen for. Turning color on again lets the frame's colors apply as usual.</p>
     *
     * @param value whether color output is enabled
     */
    public void colorEnabled(boolean value) {
        this.color = value;
    }

    /**
     * The sequences that open the session with the options' own presentation.
     *
     * @return the escape sequences, or an empty string when the options ask for nothing
     */
    public String open() {
        StringBuilder out = new StringBuilder();
        if (applied.alternateScreen()) {
            out.append(AnsiEncoder.ALT_SCREEN_ON);
        }
        if (applied.bracketedPaste()) {
            out.append(AnsiEncoder.ENABLE_BRACKETED_PASTE);
        }
        if (applied.mouse() != MouseMode.NONE) {
            out.append(AnsiEncoder.enableMouse(applied.mouse()));
        }
        if (applied.focusReporting()) {
            out.append(AnsiEncoder.ENABLE_FOCUS_REPORTING);
        }
        return out.toString();
    }

    /**
     * The sequences that take the terminal again after this session gave it back.
     *
     * <p>Giving the terminal back forgets every mode, so taking it again is not the same as opening
     * it: the session's own defaults have to be applied once more, and the ledger has to remember
     * them, or the next frame would diff against modes the terminal no longer has. Metadata is not
     * restored here: it belongs to the frame the session is about to paint, which states it again.</p>
     *
     * @return the escape sequences, or an empty string when the session asks for no modes
     */
    public String reopen() {
        applied = sessionDefaults();
        return open();
    }

    /**
     * The sequences that take the terminal to one frame's intent, without recording them.
     *
     * <p>Nothing is written and the committed ledger is unchanged, so the caller may write the frame
     * and call {@link #commit} afterward. A modifier the intent leaves out resolves to the session
     * default; metadata the intent leaves out resolves to "none", which is the terminal's own state.</p>
     *
     * @param intent the presentation the frame declared, or null when it declared none
     * @return the transition to write and commit
     */
    public Transition plan(TerminalPresentation intent) {
        TerminalPresentation wanted = intent == null ? TerminalPresentation.EMPTY : intent;
        boolean alternateScreen = resolveAlternateScreen(wanted);
        MouseMode mouse = resolveMouse(wanted);
        boolean bracketedPaste = resolveBracketedPaste(wanted);
        boolean focusReporting = resolveFocusReporting(wanted);
        String title = wanted.title();
        Color foreground = color ? wanted.foreground() : null;
        Color background = color ? wanted.background() : null;
        Color cursorColor = color ? wanted.cursorColor() : null;
        TerminalPresentation.Progress progress = wanted.progress();
        TerminalPresentation.Status status = wanted.status();

        StringBuilder out = new StringBuilder();
        boolean bufferChanged = alternateScreen != applied.alternateScreen();
        if (bufferChanged) {
            // First, so every write after it lands on the buffer the frame is painted into.
            out.append(alternateScreen ? AnsiEncoder.ALT_SCREEN_ON : AnsiEncoder.ALT_SCREEN_OFF);
        }
        if (mouse != applied.mouse()) {
            // The old level is turned off before the new one is asked for: the two tracking modes
            // are separate terminal flags, and leaving one set would report more than the frame asked.
            out.append(AnsiEncoder.disableMouse(applied.mouse())).append(AnsiEncoder.enableMouse(mouse));
        }
        if (bracketedPaste != applied.bracketedPaste()) {
            out.append(bracketedPaste ? AnsiEncoder.ENABLE_BRACKETED_PASTE : AnsiEncoder.DISABLE_BRACKETED_PASTE);
        }
        if (focusReporting != applied.focusReporting()) {
            out.append(focusReporting ? AnsiEncoder.ENABLE_FOCUS_REPORTING : AnsiEncoder.DISABLE_FOCUS_REPORTING);
        }
        boolean pushed = applied.titlePushed();
        if (!Objects.equals(title, applied.title())) {
            if (title == null) {
                if (applied.titlePushed()) {
                    // The title the session replaced was saved when it first set one, so popping it is
                    // the only way back to what the user's shell had.
                    out.append(AnsiEncoder.POP_TITLE);
                    pushed = false;
                }
            } else {
                if (!applied.titlePushed()) {
                    // Saved once per session: a later title replaces this session's own, so pushing
                    // again would bury the user's title under every change.
                    out.append(AnsiEncoder.PUSH_TITLE);
                    pushed = true;
                }
                out.append(AnsiEncoder.title(title));
            }
        }
        if (!Objects.equals(foreground, applied.foreground())) {
            out.append(foreground == null ? AnsiEncoder.RESET_FOREGROUND : AnsiEncoder.defaultColor(10, foreground));
        }
        if (!Objects.equals(background, applied.background())) {
            out.append(background == null ? AnsiEncoder.RESET_BACKGROUND : AnsiEncoder.defaultColor(11, background));
        }
        if (!Objects.equals(cursorColor, applied.cursorColor())) {
            out.append(
                    cursorColor == null ? AnsiEncoder.RESET_CURSOR_COLOR : AnsiEncoder.defaultColor(12, cursorColor));
        }
        if (!Objects.equals(progress, applied.progress())) {
            out.append(AnsiEncoder.progress(progress == null ? TerminalPresentation.Progress.none() : progress));
        }
        if (!Objects.equals(status, applied.status())) {
            out.append(status == null ? clearSequence(applied.status()) : AnsiEncoder.programStatus(status));
        }
        Applied next = new Applied(
                alternateScreen,
                mouse,
                bracketedPaste,
                focusReporting,
                title,
                pushed,
                foreground,
                background,
                cursorColor,
                progress,
                status);
        return new Transition(out.toString(), bufferChanged, next);
    }

    /**
     * Records a transition as applied.
     *
     * <p>Called only after the frame carrying {@link Transition#sequences()} was written, which is
     * what keeps the ledger honest: a write that failed fails the run instead of leaving the session
     * with a mode it never announced.</p>
     *
     * @param transition the transition the frame wrote; required
     */
    public void commit(Transition transition) {
        if (transition == null) {
            throw new IllegalArgumentException("transition is required");
        }
        applied = transition.next;
    }

    /**
     * The sequences that give back the pointer, paste and focus modes.
     *
     * <p>Each is written only for what the session is actually reporting, so a session that never
     * asked writes nothing. The ledger forgets them, so a second call cannot repeat them.</p>
     *
     * @return the escape sequences, or an empty string when the session reports nothing
     */
    public String restoreModes() {
        StringBuilder out = new StringBuilder();
        if (applied.mouse() != MouseMode.NONE) {
            out.append(AnsiEncoder.disableMouse(applied.mouse()));
        }
        if (applied.bracketedPaste()) {
            out.append(AnsiEncoder.DISABLE_BRACKETED_PASTE);
        }
        if (applied.focusReporting()) {
            out.append(AnsiEncoder.DISABLE_FOCUS_REPORTING);
        }
        applied = new Applied(
                applied.alternateScreen(),
                MouseMode.NONE,
                false,
                false,
                applied.title(),
                applied.titlePushed(),
                applied.foreground(),
                applied.background(),
                applied.cursorColor(),
                applied.progress(),
                applied.status());
        return out.toString();
    }

    /**
     * The sequences that give back the metadata the session applied.
     *
     * <p>The saved title is popped, every color default this session set is reset to the terminal's
     * own, the progress value is removed, and a status record is cleared unless the protocol keeps it:
     * a final {@code done} or {@code error} stays for the user to find, while a run still in progress
     * does not outlive the session that reported it.</p>
     *
     * @return the escape sequences, or an empty string when the session applied no metadata
     */
    public String restoreMetadata() {
        StringBuilder out = new StringBuilder();
        if (applied.titlePushed()) {
            out.append(AnsiEncoder.POP_TITLE);
        }
        if (applied.foreground() != null) {
            out.append(AnsiEncoder.RESET_FOREGROUND);
        }
        if (applied.background() != null) {
            out.append(AnsiEncoder.RESET_BACKGROUND);
        }
        if (applied.cursorColor() != null) {
            out.append(AnsiEncoder.RESET_CURSOR_COLOR);
        }
        if (applied.progress() != null) {
            out.append(AnsiEncoder.progress(TerminalPresentation.Progress.none()));
        }
        TerminalPresentation.Status status = applied.status();
        if (status != null && status.state() != TerminalPresentation.Status.State.CLEAR && !status.retained()) {
            out.append(clearSequence(status));
        }
        applied = new Applied(
                applied.alternateScreen(),
                applied.mouse(),
                applied.bracketedPaste(),
                applied.focusReporting(),
                null,
                false,
                null,
                null,
                null,
                null,
                null);
        return out.toString();
    }

    /**
     * Whether this session is drawing on the alternate screen and must give it back.
     *
     * @return true when the alternate screen is in force
     */
    public boolean alternateScreenEntered() {
        return applied.alternateScreen();
    }

    /**
     * The sequences that leave the alternate screen.
     *
     * <p>Written last on the way out, after the modes, the cursor and the metadata: the terminal's
     * keyboard-mode stack is per screen, and the frame's own restoration belongs to the screen it was
     * drawn on.</p>
     *
     * @return the escape sequence, or an empty string when the alternate screen is not in force
     */
    public String leaveAlternateScreen() {
        if (!applied.alternateScreen()) {
            return "";
        }
        applied = new Applied(
                false,
                applied.mouse(),
                applied.bracketedPaste(),
                applied.focusReporting(),
                applied.title(),
                applied.titlePushed(),
                applied.foreground(),
                applied.background(),
                applied.cursorColor(),
                applied.progress(),
                applied.status());
        return AnsiEncoder.ALT_SCREEN_OFF;
    }

    /**
     * The record the session must remove for a status it no longer reports, or nothing.
     */
    private static String clearSequence(TerminalPresentation.Status previous) {
        if (previous == null || previous.state() == TerminalPresentation.Status.State.CLEAR) {
            // Nothing is on show: a frame that declares no status has nothing to remove.
            return "";
        }
        return AnsiEncoder.programStatus(TerminalPresentation.Status.of(TerminalPresentation.Status.State.CLEAR)
                .withId(previous.id()));
    }

    private boolean resolveAlternateScreen(TerminalPresentation wanted) {
        if (wanted.alternateScreen() == null) {
            return defaultAlternateScreen;
        }
        return wanted.alternateScreen() && capabilities.alternateScreen();
    }

    private MouseMode resolveMouse(TerminalPresentation wanted) {
        MouseMode requested = wanted.mouseMode() == null ? defaultMouse : wanted.mouseMode();
        return capabilities.mouse() ? requested : MouseMode.NONE;
    }

    private boolean resolveBracketedPaste(TerminalPresentation wanted) {
        if (wanted.bracketedPaste() == null) {
            return defaultBracketedPaste;
        }
        return wanted.bracketedPaste() && capabilities.bracketedPaste();
    }

    private boolean resolveFocusReporting(TerminalPresentation wanted) {
        return wanted.focusReporting() == null ? defaultFocusReporting : wanted.focusReporting();
    }
}

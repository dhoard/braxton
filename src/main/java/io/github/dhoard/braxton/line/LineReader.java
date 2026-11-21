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

import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Theme;
import io.github.dhoard.braxton.style.Themes;
import io.github.dhoard.braxton.style.WidthPolicy;
import io.github.dhoard.braxton.terminal.TerminalEvent;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import io.github.dhoard.braxton.ui.Cursor;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads one edited line at a time from a terminal session, inline.
 *
 * <p>The reader is the inline host a line-oriented application needs: it paints the prompt and the
 * line where the terminal's cursor already is - one row, never the alternate screen - repaints that
 * row as the line changes, and leaves the accepted line on screen with the cursor on the line below
 * it, which is where the caller's next output belongs. It never clears anything above its own row,
 * so whatever the shell printed before the program started survives the whole session. The painting
 * is {@link LineRenderer}'s one row and the region arithmetic is the runtime's own inline renderer,
 * so a line editor and an inline MVU application leave the terminal in the same state.</p>
 *
 * <h2>What the reader owns, and what it does not</h2>
 *
 * <p>The region itself is {@link InlineRegion}'s, the one host this package shares: the reader
 * presents the caret on the row it paints and the prompts in this package present their own rows.
 * Opening it hides the terminal's cursor and asks for bracketed paste, so a paste arrives as one
 * event instead of a burst of keystrokes; leaving it shows the caret again, restores any shape or
 * color the read applied and resets the styles it used. The reader does not open, close, configure
 * or change the session's terminal modes: entering raw mode is the caller's one call, and
 * {@code close()} on the session is what restores the terminal. A session backed by streams paints
 * the same bytes to its writer, which is what makes the reader testable without a terminal at all.</p>
 *
 * <h2>Ends of a read</h2>
 *
 * <p>One call to {@link #read(String)} ends in exactly one of the editor's own outputs:
 * {@link LineEditor.Output.Accepted} with the line, {@link LineEditor.Output.Canceled} when the user
 * abandoned it, or {@link LineEditor.Output.Eof} when input ended or the user asked for the end of
 * input on an empty line. Accepted leaves its line on screen; the other two erase the region, so an
 * abandoned prompt leaves no half-typed line for the caller's next output to land behind. A resize
 * while reading repaints the row at the new width, and a line wider than the terminal scrolls.</p>
 *
 * <h2>History, search and completion</h2>
 *
 * <p>A reader with a {@link History} starts each read on an editor that searches it, so
 * {@code ctrl+r}/{@code ctrl+s} and Vi's {@code /}/{@code ?} work with no further arrangement and a
 * running search repaints the row it painted the prompt on. The reader does not record what it read:
 * an accepted line is the caller's to keep, because the caller knows whether the line is worth
 * recalling and where the history lives. Recording is one call -
 * {@code history = history.recorded(accepted.text())} - and the next read is given the answer.</p>
 *
 * <p>A completion menu, a highlighter and a mask reach the editor the same way - they are the
 * starting editor's own settings, which {@link #read(String, LineEditor)} takes - and a menu is
 * painted as a second row of the same region, below the line. The region shrinks back to one row when
 * the menu ends, so an accepted line is left on screen alone.</p>
 *
 * <h2>Asking something else</h2>
 *
 * <p>The reader is also what {@link ConfirmPrompt}, {@link SelectPrompt}, {@link MultiSelectPrompt}
 * and {@link ProgressPrompt} paint with: a prompt takes a reader, so a program that reads lines and
 * asks questions in between uses one theme, one column policy and one region for both. The
 * configuration accessors are package-private because they exist for those prompts, not for an
 * application to reach the session through.</p>
 */
public final class LineReader {

    private final TerminalSession session;
    private final Mode mode;
    private final Theme theme;
    private final WidthPolicy policy;
    private final ColorProfile profile;
    private final History history;

    private LineReader(
            TerminalSession session,
            Mode mode,
            Theme theme,
            WidthPolicy policy,
            ColorProfile profile,
            History history) {
        this.session = session;
        this.mode = mode;
        this.theme = theme;
        this.policy = policy;
        this.profile = profile;
        this.history = history;
    }

    /**
     * A reader over a session, with Emacs bindings, the built-in dark theme and the pinned policy.
     *
     * @param session the session to read from and paint to; required
     * @return the reader
     */
    public static LineReader of(TerminalSession session) {
        return new LineReader(
                Objects.requireNonNull(session, "session"),
                Mode.EMACS,
                Themes.DARK,
                WidthPolicy.PINNED,
                ColorProfile.AUTO,
                History.inMemory());
    }

    /**
     * The same reader with other bindings.
     *
     * @param value the mode; null is {@link Mode#EMACS}
     * @return the reader
     */
    public LineReader withMode(Mode value) {
        return new LineReader(session, value == null ? Mode.EMACS : value, theme, policy, profile, history);
    }

    /**
     * The same reader with another theme.
     *
     * @param value the theme; null is the built-in dark theme
     * @return the reader
     */
    public LineReader withTheme(Theme value) {
        return new LineReader(session, mode, value == null ? Themes.DARK : value, policy, profile, history);
    }

    /**
     * The same reader with another column policy.
     *
     * @param value the policy; null is the pinned policy
     * @return the reader
     */
    public LineReader withPolicy(WidthPolicy value) {
        return new LineReader(session, mode, theme, value == null ? WidthPolicy.PINNED : value, profile, history);
    }

    /**
     * The same reader with another color profile.
     *
     * @param value the profile, or {@link ColorProfile#AUTO} to negotiate one from the session;
     *     null is {@link ColorProfile#AUTO}
     * @return the reader
     */
    public LineReader withColorProfile(ColorProfile value) {
        return new LineReader(session, mode, theme, policy, value == null ? ColorProfile.AUTO : value, history);
    }

    /**
     * The same reader with a history its reads search.
     *
     * <p>The history applies to the empty editor {@link #read(String)} starts with. An editor the
     * caller supplies keeps the history it was given, the same rule its mode follows: the starting
     * editor is the caller's, so nothing about it is silently replaced.</p>
     *
     * @param value the history; null is a session-only history with no entries
     * @return the reader
     */
    public LineReader withHistory(History value) {
        return new LineReader(session, mode, theme, policy, profile, value == null ? History.inMemory() : value);
    }

    /**
     * Reads one line, starting from an empty editor in the reader's mode.
     *
     * @param prompt the prompt to paint before the line; null is no prompt
     * @return the output that ended the read
     * @throws TerminalException when the session cannot be read from or written to
     */
    public LineEditor.Output read(String prompt) throws TerminalException {
        return read(prompt, null);
    }

    /**
     * Reads one line, starting from an editor the caller supplies.
     *
     * <p>The editor is the starting state, so an application can offer a default value - or restore a
     * draft it kept - and the user edits from there. Its own mode wins over the reader's: the reader
     * only chooses the mode of the empty editor {@link #read(String)} starts with. Its completer, its
     * highlighter and its mask travel with it for the same reason: they are the editor's settings, and
     * a password prompt or a menu of candidates is a property of the line being read.</p>
     *
     * @param prompt the prompt to paint before the line; null is no prompt
     * @param editor the editor to start from, or null for an empty one in the reader's mode
     * @return the output that ended the read
     * @throws TerminalException when the session cannot be read from or written to
     */
    public LineEditor.Output read(String prompt, LineEditor editor) throws TerminalException {
        LineEditor current = editor == null ? LineEditor.of(mode).withHistory(history) : editor;
        LineRenderer renderer = new LineRenderer(theme, policy);
        InlineRegion region = InlineRegion.open(session, profile);
        present(region, renderer, current, prompt);
        while (true) {
            Optional<TerminalEvent> event = session.read();
            if (event.isEmpty()) {
                region.leave(false);
                return new LineEditor.Output.Eof();
            }
            // A session reports exactly two kinds of event, so this is exhaustive by construction.
            switch (event.get()) {
                case TerminalEvent.Resize resize -> {
                    region.resize(resize.size().cols());
                    present(region, renderer, current, prompt);
                }
                case TerminalEvent.Input input -> {
                    LineEditor.Result result = current.handle(input.value());
                    for (LineEditor.Output output : result.outputs()) {
                        switch (output) {
                            case LineEditor.Output.Accepted accepted -> {
                                // The line is left on screen, so a menu that was showing goes first:
                                // the region shrinks back to the one row the accepted line needs.
                                present(region, renderer, result.editor(), prompt);
                                region.leave(true);
                                return accepted;
                            }
                            case LineEditor.Output.Canceled canceled -> {
                                region.leave(false);
                                return canceled;
                            }
                            case LineEditor.Output.Eof eof -> {
                                region.leave(false);
                                return eof;
                            }
                        }
                    }
                    if (result.editor() != current) {
                        current = result.editor();
                        present(region, renderer, current, prompt);
                    }
                }
            }
        }
    }

    /**
     * Paints the editor's row, and its menu below it when one is showing, into the region.
     */
    private void present(InlineRegion region, LineRenderer renderer, LineEditor editor, String prompt)
            throws TerminalException {
        int cols = region.columns();
        boolean menu = cols > 0 && editor.completion().isPresent();
        Screen screen = new Screen(menu ? 2 : 1, cols);
        Optional<Cursor> caret = renderer.paint(screen, 0, cols, editor, prompt);
        if (menu) {
            renderer.paintMenu(screen, 1, cols, editor);
        }
        region.present(screen, caret);
    }

    /**
     * The session the reader paints to and reads from, which the prompts in this package paint with.
     */
    TerminalSession session() {
        return session;
    }

    /**
     * The theme the reader's rows come from, which the prompts in this package paint with.
     */
    Theme theme() {
        return theme;
    }

    /**
     * The column policy the reader measures with, which the prompts in this package measure with.
     */
    WidthPolicy policy() {
        return policy;
    }

    /**
     * The profile the reader encodes with, which the prompts in this package encode with.
     *
     * <p>The value is the reader's own setting rather than a negotiated one, so a prompt that opens
     * a region negotiates it exactly the way the reader's own reads do.</p>
     */
    ColorProfile profile() {
        return profile;
    }
}

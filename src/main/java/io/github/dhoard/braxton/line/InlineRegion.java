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

import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.internal.render.CursorPresenter;
import io.github.dhoard.braxton.internal.render.InlineRenderer;
import io.github.dhoard.braxton.internal.render.Screen;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.terminal.Capabilities;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import io.github.dhoard.braxton.ui.Cursor;
import java.util.Objects;
import java.util.Optional;

/**
 * The inline region a line-oriented program paints in: the terminal's own rows, starting where the
 * cursor already is.
 *
 * <p>It is the one host {@link LineReader} and the prompts in this package share. The frames are the
 * runtime's own {@link InlineRenderer}, so a line editor, a select prompt and an inline MVU
 * application leave the terminal in the same state: nothing above the region is ever cleared, the
 * region shrinks and grows in place, and leaving it puts the cursor on the line below so the
 * caller's next output starts there.</p>
 *
 * <p>The region owns the cursor properties it presents while it paints and the settings it turns on
 * for its lifetime: {@link #open} hides the terminal's cursor and asks for bracketed paste, and
 * {@link #leave} gives both back. Hiding it is what keeps a region that shows no caret - a select
 * prompt, a progress bar - from leaving a blinking cursor at the last cell it painted, and a caller
 * that does present a caret shows it again through the frame that asks for it. The region does not
 * open, close or configure the session's terminal modes - entering raw mode stays the caller's one
 * call - and it never writes anything it did not paint.</p>
 *
 * <p>{@link #present} is the only way a frame reaches the terminal, and it writes nothing when the
 * screen it is given is the one already on screen with the caret already in place, so a caller that
 * repaints an unchanged screen costs no bytes.</p>
 */
final class InlineRegion {

    private final TerminalSession session;
    private final CursorPresenter cursor;
    private final InlineRenderer renderer;
    private final ColorProfile profile;
    private int columns;
    private Screen painted;

    private InlineRegion(TerminalSession session, ColorProfile profile, int columns) {
        this.session = session;
        this.profile = profile;
        this.columns = columns;
        this.cursor = new CursorPresenter(profile.color());
        this.renderer = new InlineRenderer(cursor);
    }

    /**
     * Opens the region the cursor is on, and asks for bracketed paste while it is open.
     *
     * @param session the session to paint to; required
     * @param profile the profile to encode with, or {@link ColorProfile#AUTO} to negotiate one from
     *     the session; null is {@link ColorProfile#AUTO}
     * @return the region
     * @throws TerminalException when the session cannot be written to
     */
    static InlineRegion open(TerminalSession session, ColorProfile profile) throws TerminalException {
        Objects.requireNonNull(session, "session");
        InlineRegion region = new InlineRegion(session, resolve(session, profile), columns(session));
        session.write(AnsiEncoder.ENABLE_BRACKETED_PASTE + AnsiEncoder.HIDE_CURSOR);
        return region;
    }

    /**
     * The columns a row of the region may use.
     */
    int columns() {
        return columns;
    }

    /**
     * Takes the width a resize reported.
     *
     * @param value the new column count
     */
    void resize(int value) {
        columns = Math.max(0, value);
    }

    /**
     * Paints one frame into the region, and writes only what changed.
     *
     * @param next the screen to present; required
     * @param caret the caret the frame resolved, or empty when nothing is to be shown
     * @throws TerminalException when the session cannot be written to
     */
    void present(Screen next, Optional<Cursor> caret) throws TerminalException {
        Objects.requireNonNull(next, "next");
        String frame = renderer.frame(painted, next, profile, caret);
        if (!frame.isEmpty()) {
            session.write(frame);
            session.flush();
        }
        painted = next;
    }

    /**
     * Leaves the region, restoring the cursor properties and the settings it turned on.
     *
     * <p>An accepted exit keeps the final frame on screen - it is the read's result - and puts the
     * cursor on the line below it. Any other exit erases the region at its own first row, so an
     * abandoned prompt leaves nothing behind.</p>
     *
     * @param accepted whether the final frame is the result the caller keeps
     * @throws TerminalException when the session cannot be written to
     */
    void leave(boolean accepted) throws TerminalException {
        StringBuilder out = new StringBuilder();
        if (accepted) {
            out.append(renderer.finish());
        } else {
            out.append(renderer.frame(painted, new Screen(1, columns), profile, Optional.empty()));
        }
        out.append(cursor.reset());
        out.append(AnsiEncoder.RESET);
        out.append(AnsiEncoder.SHOW_CURSOR);
        out.append(AnsiEncoder.DISABLE_BRACKETED_PASTE);
        session.write(out.toString());
        session.flush();
        painted = null;
    }

    /**
     * The columns the session reports, which is every column the region may paint.
     */
    private static int columns(TerminalSession session) {
        return Math.max(0, session.size().cols());
    }

    /**
     * The profile a frame is encoded with, negotiated from the session when the caller left it open.
     */
    private static ColorProfile resolve(TerminalSession session, ColorProfile profile) {
        if (profile != null && profile != ColorProfile.AUTO) {
            return profile;
        }
        Capabilities capabilities = session.capabilities();
        return ColorProfile.negotiated(capabilities.color(), capabilities.term());
    }
}

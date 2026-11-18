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

package io.github.dhoard.braxton.style;

import java.util.Locale;

/**
 * How much colour a session may put on the wire.
 *
 * <p>A profile is a property of the session's output, not of a {@link Style}: the same styled text
 * encodes as 24-bit SGR, as a palette index, as one of the sixteen ANSI colours, or as no colour at
 * all. Downsampling keeps every non-colour attribute and every hyperlink, so a session that loses
 * colour loses only colour.</p>
 *
 * <p>{@link #AUTO} is not a capability: it is the request to negotiate one, and no frame is ever
 * encoded with it. {@link #negotiated(boolean, String)} resolves it from facts the session already
 * has. An explicit profile is honoured as given; a terminal that cannot render it is the caller's
 * decision to make.</p>
 *
 * @see Colors#nearestAnsi256(Color)
 * @see Colors#nearestAnsi16(Color)
 */
public enum ColorProfile {

    /**
     * Negotiate the profile from the session's environment and terminal facts.
     *
     * <p>Never used to encode a frame: the runtime resolves it before the first one.</p>
     */
    AUTO,

    /** No colour at all; only attributes, borders, glyphs and hyperlinks are emitted. */
    NONE,

    /** The sixteen ANSI colours a plain terminal has, named by their classic SGR codes. */
    ANSI16,

    /** The xterm 256-colour palette: a 6x6x6 cube plus a 24-step grey ramp. */
    ANSI256,

    /** 24-bit SGR colour, exactly as the styled text carries it. */
    TRUECOLOR;

    /**
     * Whether this profile emits colour at all.
     *
     * @return false for {@link #NONE} and for the unresolved {@link #AUTO}
     */
    public boolean color() {
        return this != NONE && this != AUTO;
    }

    /**
     * How many distinct colours this profile can name.
     *
     * <p>Comparisons between profiles use this rather than declaration order, so an upgrade is "can
     * this profile say more" instead of "is it declared later".</p>
     *
     * @return 0, 16, 256 or 16_777_216
     */
    public int colors() {
        return switch (this) {
            case AUTO, NONE -> 0;
            case ANSI16 -> 16;
            case ANSI256 -> 256;
            case TRUECOLOR -> 1 << 24;
        };
    }

    /**
     * The lower-case name used in documentation, key bindings and status rows.
     *
     * @return the profile's name, as {@code truecolor} for {@link #TRUECOLOR}
     */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Negotiates the profile a session starts with.
     *
     * <p>Two facts decide it. A terminal the session has already found unable to address colour is
     * {@link #NONE}: there is nothing to emit. {@code NO_COLOR}, when set to any non-empty value,
     * is the user's own request to lose colour and outranks the terminal's ability
     * (<a href="https://no-color.org/">no-color.org</a>; the specification says the value is not
     * inspected, so {@code NO_COLOR=0} also disables colour). Everything else negotiates
     * {@link #TRUECOLOR}, which is what Braxton has always emitted on a colour terminal: a
     * conservative downgrade of the default would change the look of every existing application on a
     * 256-colour terminal, so a narrower profile is a choice the caller makes, not one the library
     * makes for it.</p>
     *
     * <p>This is deliberately not an environment-based colour-capability guess. A terminal's own
     * answers - the {@code RGB} and {@code Tc} termcap capabilities - are the only authority for
     * truecolor, and a session that wants them asks for them; the runtime upgrades a narrower profile
     * when such a reply arrives.</p>
     *
     * @param supportsColor whether the attached terminal accepts colour SGR at all
     * @param noColor the {@code NO_COLOR} value, or null when the variable is unset
     * @return {@link #NONE} or {@link #TRUECOLOR}; never {@link #AUTO}
     */
    public static ColorProfile negotiated(boolean supportsColor, String noColor) {
        if (!supportsColor) {
            return NONE;
        }
        if (noColor != null && !noColor.isEmpty()) {
            return NONE;
        }
        return TRUECOLOR;
    }

    /**
     * The wider of two profiles, used when a terminal reports a capability the session doubted.
     *
     * <p>Only widening is meaningful: a terminal answering a capability query is evidence of what it
     * can do, and evidence never removes a profile the caller asked for explicitly. {@link #AUTO} is
     * narrower than every resolved profile.</p>
     *
     * @param current the profile in force
     * @param candidate the profile the new evidence supports
     * @return whichever can name more colours
     */
    public static ColorProfile wider(ColorProfile current, ColorProfile candidate) {
        ColorProfile resolved = current == null ? AUTO : current;
        ColorProfile other = candidate == null ? AUTO : candidate;
        return other.colors() > resolved.colors() ? other : resolved;
    }
}

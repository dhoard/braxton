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

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.style.Colors;
import io.github.dhoard.braxton.terminal.Capabilities;

/**
 * The attached terminal, as the running session has negotiated it.
 *
 * <p>These are session facts, not model state: they describe what the session is presenting with and
 * what it has learned about the terminal. A component reads them with
 * {@link io.github.dhoard.braxton.effect.Subscription#facts} and receives the current value when it
 * starts and a new one whenever a fact changes - a color profile upgraded by a capability reply, or
 * a background color the session reported for the first time.</p>
 *
 * <p>The background is {@code null} until a session asks the terminal for it
 * ({@link TerminalQuery.ColorReport} with {@link TerminalQuery.Target#BACKGROUND}); a terminal that
 * refuses the query leaves it unknown rather than guessing. Nothing here is derived from
 * {@code TERM} except what {@link Capabilities} already reports.</p>
 *
 * @param profile the color profile frames are encoded with; never {@link ColorProfile#AUTO}
 * @param capabilities the terminal facts the session opened with
 * @param background the terminal's background color, or null when it has not been reported
 */
public record TerminalFacts(ColorProfile profile, Capabilities capabilities, Color background) {

    /**
     * Rejects an unresolved color profile.
     */
    public TerminalFacts {
        if (profile == null || profile == ColorProfile.AUTO) {
            throw new IllegalArgumentException("a resolved color profile is required");
        }
        capabilities = capabilities == null ? Capabilities.PLAIN : capabilities;
    }

    /**
     * Whether the session emits color at all.
     *
     * @return true when the profile names at least one color
     */
    public boolean color() {
        return profile.color();
    }

    /**
     * Whether the terminal's background has been reported.
     *
     * @return true when {@link #background()} is known
     */
    public boolean themeKnown() {
        return background != null;
    }

    /**
     * Whether the terminal's background reads as dark.
     *
     * <p>Unknown counts as not dark, so a theme decision that has not learned the background keeps
     * whatever it already chose instead of switching to a dark palette on no evidence.</p>
     *
     * @return true when the reported background is dark by {@link Colors#isDark(Color)}
     */
    public boolean dark() {
        return background != null && Colors.isDark(background);
    }

    /**
     * The same facts with another color profile.
     *
     * @param value the profile in force
     * @return the new facts
     */
    public TerminalFacts withProfile(ColorProfile value) {
        return new TerminalFacts(value, capabilities, background);
    }

    /**
     * The same facts with a reported background color.
     *
     * @param value the background, or null to forget it
     * @return the new facts, or this instance when nothing changed
     */
    public TerminalFacts withBackground(Color value) {
        return java.util.Objects.equals(background, value) ? this : new TerminalFacts(profile, capabilities, value);
    }
}

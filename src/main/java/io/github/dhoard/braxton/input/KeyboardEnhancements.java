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

package io.github.dhoard.braxton.input;

/**
 * The Kitty keyboard protocol progressive enhancement flags: which extended keyboard facts the
 * application asked the terminal to report, or which the terminal said it had enabled.
 *
 * <p>The flags are an ordered bit set, exactly as the protocol defines them: bit 0 disambiguates
 * escape codes, bit 1 reports key event types, bit 2 reports alternate keys, bit 3 reports every
 * key as an escape code, and bit 4 reports the text associated with a key event. A flag only takes
 * effect with the terminal's cooperation; an unrequested set is the empty {@link #NONE}, and an
 * unenhanced terminal keeps reporting legacy input.</p>
 *
 * <p>Two combinations are defined by the protocol rather than by this type:
 * {@code reportAllKeys} implies {@code disambiguate}, and {@code reportAssociatedText} is undefined
 * without {@code reportAllKeys}. Requesting an incoherent set is not rejected here, because the
 * same value also describes what a terminal reported; ask for what you need.</p>
 *
 * @param disambiguate report Escape and modified keys as escape codes instead of legacy control bytes
 * @param reportEventTypes report key press, repeat and release separately
 * @param reportAlternateKeys report the shifted and base-layout key codes alongside the key code
 * @param reportAllKeys report even text-producing keys as escape codes
 * @param reportAssociatedText embed the text a key event produces in its escape code
 */
public record KeyboardEnhancements(
        boolean disambiguate,
        boolean reportEventTypes,
        boolean reportAlternateKeys,
        boolean reportAllKeys,
        boolean reportAssociatedText) {

    /**
     * No enhancement: legacy keyboard input.
     */
    public static final KeyboardEnhancements NONE = new KeyboardEnhancements(false, false, false, false, false);

    /**
     * The bit the protocol assigns to disambiguated escape codes.
     */
    private static final int DISAMBIGUATE = 1;

    /**
     * The bit the protocol assigns to reported key event types.
     */
    private static final int EVENT_TYPES = 2;

    /**
     * The bit the protocol assigns to reported alternate keys.
     */
    private static final int ALTERNATE_KEYS = 4;

    /**
     * The bit the protocol assigns to reporting every key as an escape code.
     */
    private static final int ALL_KEYS = 8;

    /**
     * The bit the protocol assigns to reported associated text.
     */
    private static final int ASSOCIATED_TEXT = 16;

    /**
     * Every flag this type can represent.
     */
    private static final int KNOWN = DISAMBIGUATE | EVENT_TYPES | ALTERNATE_KEYS | ALL_KEYS | ASSOCIATED_TEXT;

    /**
     * The flags this value describes as one protocol bit set.
     *
     * @return the bit set, zero for {@link #NONE}
     */
    public int flags() {
        int flags = 0;
        if (disambiguate) {
            flags |= DISAMBIGUATE;
        }
        if (reportEventTypes) {
            flags |= EVENT_TYPES;
        }
        if (reportAlternateKeys) {
            flags |= ALTERNATE_KEYS;
        }
        if (reportAllKeys) {
            flags |= ALL_KEYS;
        }
        if (reportAssociatedText) {
            flags |= ASSOCIATED_TEXT;
        }
        return flags;
    }

    /**
     * The value a protocol bit set names.
     *
     * <p>Bits above the five defined flags are not represented and are dropped, so a terminal that
     * reports an unknown enhancement still yields a valid value.</p>
     *
     * @param flags the reported bit set
     * @return the value, never null
     */
    public static KeyboardEnhancements of(int flags) {
        int known = flags & KNOWN;
        return new KeyboardEnhancements(
                (known & DISAMBIGUATE) != 0,
                (known & EVENT_TYPES) != 0,
                (known & ALTERNATE_KEYS) != 0,
                (known & ALL_KEYS) != 0,
                (known & ASSOCIATED_TEXT) != 0);
    }

    /**
     * Whether any enhancement is set.
     *
     * @return true when at least one flag is set
     */
    public boolean any() {
        return flags() != 0;
    }

    /**
     * The same flags with disambiguation chosen.
     *
     * @param value whether to disambiguate escape codes
     * @return this instance when the flag did not change
     */
    public KeyboardEnhancements withDisambiguate(boolean value) {
        return value == disambiguate
                ? this
                : new KeyboardEnhancements(
                        value, reportEventTypes, reportAlternateKeys, reportAllKeys, reportAssociatedText);
    }

    /**
     * The same flags with event-type reporting chosen.
     *
     * @param value whether to report key event types
     * @return this instance when the flag did not change
     */
    public KeyboardEnhancements withReportEventTypes(boolean value) {
        return value == reportEventTypes
                ? this
                : new KeyboardEnhancements(
                        disambiguate, value, reportAlternateKeys, reportAllKeys, reportAssociatedText);
    }

    /**
     * The same flags with alternate-key reporting chosen.
     *
     * @param value whether to report alternate keys
     * @return this instance when the flag did not change
     */
    public KeyboardEnhancements withReportAlternateKeys(boolean value) {
        return value == reportAlternateKeys
                ? this
                : new KeyboardEnhancements(disambiguate, reportEventTypes, value, reportAllKeys, reportAssociatedText);
    }

    /**
     * The same flags with escape-code reporting for every key chosen.
     *
     * @param value whether to report every key as an escape code
     * @return this instance when the flag did not change
     */
    public KeyboardEnhancements withReportAllKeys(boolean value) {
        return value == reportAllKeys
                ? this
                : new KeyboardEnhancements(
                        disambiguate, reportEventTypes, reportAlternateKeys, value, reportAssociatedText);
    }

    /**
     * The same flags with associated-text reporting chosen.
     *
     * @param value whether to report associated text
     * @return this instance when the flag did not change
     */
    public KeyboardEnhancements withReportAssociatedText(boolean value) {
        return value == reportAssociatedText
                ? this
                : new KeyboardEnhancements(disambiguate, reportEventTypes, reportAlternateKeys, reportAllKeys, value);
    }
}

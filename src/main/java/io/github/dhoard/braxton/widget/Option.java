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

import io.github.dhoard.braxton.style.StyledText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One choice a user can make: an identity and the label painted for it.
 *
 * <p>{@link Select}, {@link MultiSelect} and {@link RadioGroup} all offer a list of these, so a
 * choice carries its identity rather than its row number: an application that reorders, filters or
 * refreshes its options is never handed the wrong one. The label is {@link StyledText} so an
 * application may style part of it; the widget resolves the theme's base style underneath whatever
 * the caller already set, so a live theme switch still repaints the row.</p>
 *
 * @param id the identity, unique across the options of one widget; never blank
 * @param label the label painted for the option; null is empty
 */
public record Option(String id, StyledText label) {

    /**
     * Rejects an option without an id and substitutes an empty label.
     */
    public Option {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("option id is required");
        }
        label = label == null ? StyledText.empty() : label;
    }

    /**
     * An option with a plain label.
     *
     * @param id the identity; never blank
     * @param label the plain label; null is empty
     * @return the option
     */
    public static Option of(String id, String label) {
        return new Option(id, StyledText.plain(label));
    }

    /**
     * This option with another label.
     *
     * @param value the label; null is empty
     * @return the option
     */
    public Option withLabel(StyledText value) {
        return new Option(id, value);
    }

    /**
     * An option list, as an immutable list whose entries are all present.
     *
     * @param options the options; null is empty
     * @return the copied options
     */
    static List<Option> copyAll(List<Option> options) {
        if (options == null) {
            return List.of();
        }
        List<Option> copy = new ArrayList<>(options.size());
        for (Option option : options) {
            copy.add(Objects.requireNonNull(option, "option"));
        }
        return List.copyOf(copy);
    }
}

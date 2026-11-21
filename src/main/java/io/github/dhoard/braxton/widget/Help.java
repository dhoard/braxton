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

import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.ui.Element;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link KeyMap} as help: one compact line, or one line per group.
 *
 * <p>Help is a pure factory, not a component. Which mode is shown is the parent's model data, the
 * actions come from the same map the view routes, and the width comes from the layout the parent
 * knows, so nothing about the help is retained here and two renders of the same arguments are the
 * same value.</p>
 *
 * <p>Compact help lists the enabled actions flagged for it as {@code label description} pairs
 * separated by {@code " • "}. Expanded help lists every enabled action under its group heading, one
 * group per line, so a group with nothing enabled disappears and a group that was reordered moves.
 * A disabled action is in neither: a map is the one definition, and help never advertises a gesture
 * that no longer fires.</p>
 *
 * <p>The text is truncated to the width it is given, by display columns and never through a
 * grapheme cluster, so a wide label is dropped or cut whole rather than half-painted. A truncated
 * line ends with {@link #ELLIPSIS} in the style of the run it cut. A width of zero or less paints
 * nothing, because no column of the help fits.</p>
 */
public final class Help {

    /**
     * Separates compact-help entries.
     */
    public static final String COMPACT_SEPARATOR = " \u2022 ";

    /**
     * Separates expanded-help entries and a group heading from its entries.
     */
    public static final String FULL_SEPARATOR = "    ";

    /**
     * Ends a line cut by the available width.
     */
    public static final String ELLIPSIS = "\u2026";

    private Help() {}

    /**
     * Immutable help styling.
     *
     * @param keyStyle the style of an action's label
     * @param descriptionStyle the style of an action's description
     * @param separatorStyle the style of the separators between entries
     * @param titleStyle the style of an expanded-help group heading
     */
    public record Config(Style keyStyle, Style descriptionStyle, Style separatorStyle, Style titleStyle) {

        /**
         * Replaces a null style with no styling.
         */
        public Config {
            keyStyle = keyStyle == null ? Style.NONE : keyStyle;
            descriptionStyle = descriptionStyle == null ? Style.NONE : descriptionStyle;
            separatorStyle = separatorStyle == null ? Style.NONE : separatorStyle;
            titleStyle = titleStyle == null ? Style.NONE : titleStyle;
        }

        /**
         * Bold labels, plain descriptions and separators, and highlighted group headings.
         *
         * @return the default styling
         */
        public static Config defaults() {
            return new Config(
                    Style.NONE.withBold(),
                    Style.NONE,
                    Style.NONE,
                    Style.NONE.withBold().withUnderline());
        }

        /**
         * The same styling with a different label style.
         *
         * @param value the new style
         * @return the new configuration
         */
        public Config withKeyStyle(Style value) {
            return new Config(value, descriptionStyle, separatorStyle, titleStyle);
        }

        /**
         * The same styling with a different description style.
         *
         * @param value the new style
         * @return the new configuration
         */
        public Config withDescriptionStyle(Style value) {
            return new Config(keyStyle, value, separatorStyle, titleStyle);
        }

        /**
         * The same styling with a different separator style.
         *
         * @param value the new style
         * @return the new configuration
         */
        public Config withSeparatorStyle(Style value) {
            return new Config(keyStyle, descriptionStyle, value, titleStyle);
        }

        /**
         * The same styling with a different group-heading style.
         *
         * @param value the new style
         * @return the new configuration
         */
        public Config withTitleStyle(Style value) {
            return new Config(keyStyle, descriptionStyle, separatorStyle, value);
        }
    }

    /**
     * Renders help with the default styling.
     *
     * @param keys the actions to render; null is empty
     * @param expanded whether to render every group instead of the compact line
     * @param width the available display columns
     * @return the rendered help, empty when nothing fits or nothing is enabled
     */
    public static StyledText render(KeyMap<?> keys, boolean expanded, int width) {
        return render(keys, Config.defaults(), expanded, width);
    }

    /**
     * Renders help.
     *
     * @param keys the actions to render; null is empty
     * @param config the styling; null is {@link Config#defaults()}
     * @param expanded whether to render every group instead of the compact line
     * @param width the available display columns; zero or less renders nothing
     * @return the rendered help, empty when nothing fits or nothing is enabled
     */
    public static StyledText render(KeyMap<?> keys, Config config, boolean expanded, int width) {
        Config style = config == null ? Config.defaults() : config;
        if (keys == null || width <= 0) {
            return StyledText.empty();
        }
        List<StyledText> lines = expanded ? fullLines(keys, style) : compactLines(keys, style);
        StyledText result = StyledText.empty();
        for (int index = 0; index < lines.size(); index++) {
            if (index > 0) {
                result = result.append("\n");
            }
            result = result.append(fit(lines.get(index), width, style));
        }
        return result;
    }

    /**
     * The help line as an element, for a scene that is itself an element tree.
     *
     * @param <Msg> the message type the tree produces
     * @param keys the actions to render; null is empty
     * @param config the styling; null is {@link Config#defaults()}
     * @param expanded whether to render every group instead of the compact line
     * @param width the available display columns
     * @return the element
     */
    public static <Msg> Element<Msg> element(KeyMap<?> keys, Config config, boolean expanded, int width) {
        return Elements.text(render(keys, config, expanded, width));
    }

    private static List<StyledText> compactLines(KeyMap<?> keys, Config config) {
        List<StyledText> entries = new ArrayList<>();
        for (KeyMap.Group<?> group : keys.groups()) {
            for (KeyAction<?> action : group.actions()) {
                StyledText entry = entry(action, config);
                if (action.enabled() && action.compact() && !entry.isEmpty()) {
                    entries.add(entry);
                }
            }
        }
        return entries.isEmpty() ? List.of() : List.of(join(entries, COMPACT_SEPARATOR, config.separatorStyle()));
    }

    private static List<StyledText> fullLines(KeyMap<?> keys, Config config) {
        List<StyledText> lines = new ArrayList<>();
        for (KeyMap.Group<?> group : keys.groups()) {
            List<StyledText> entries = new ArrayList<>();
            for (KeyAction<?> action : group.actions()) {
                StyledText entry = entry(action, config);
                // An action with no label and no description says nothing, so it is left out rather
                // than leaving a separator with nothing between it and the next entry.
                if (action.enabled() && !entry.isEmpty()) {
                    entries.add(entry);
                }
            }
            if (entries.isEmpty()) {
                continue;
            }
            StyledText line = join(entries, FULL_SEPARATOR, config.separatorStyle());
            if (!group.title().isBlank()) {
                line = StyledText.of(group.title(), config.titleStyle())
                        .append(StyledText.of(FULL_SEPARATOR, config.separatorStyle()))
                        .append(line);
            }
            lines.add(line);
        }
        return lines;
    }

    private static StyledText entry(KeyAction<?> action, Config config) {
        StyledText entry = StyledText.of(action.label(), config.keyStyle());
        if (!action.description().isEmpty()) {
            if (!entry.isEmpty()) {
                entry = entry.append(" ");
            }
            entry = entry.append(StyledText.of(action.description(), config.descriptionStyle()));
        }
        return entry;
    }

    private static StyledText join(List<StyledText> entries, String separator, Style separatorStyle) {
        StyledText joined = StyledText.empty();
        for (StyledText entry : entries) {
            if (!joined.isEmpty()) {
                joined = joined.append(StyledText.of(separator, separatorStyle));
            }
            joined = joined.append(entry);
        }
        return joined;
    }

    /**
     * Cuts one line to the available columns, ending it with {@link #ELLIPSIS} when anything was
     * dropped. The ellipsis replaces the last column rather than overflowing it, so the result never
     * paints past the width it was given.
     */
    private static StyledText fit(StyledText line, int width, Config config) {
        if (line.columns() <= width) {
            return line;
        }
        StyledText prefix = take(line, width - TextMeasure.columns(ELLIPSIS));
        Style style = prefix.isEmpty()
                ? config.separatorStyle()
                : prefix.spans().get(prefix.spans().size() - 1).style();
        return prefix.append(StyledText.of(ELLIPSIS, style));
    }

    /**
     * The whole spans that fit, then a truncated part of the span the budget ran out in. Each span
     * keeps its own style and link, so a cut label stays styled and a cut description stays plain.
     */
    private static StyledText take(StyledText line, int maxColumns) {
        StyledText taken = StyledText.empty();
        int used = 0;
        for (StyledText.Span span : line.spans()) {
            String value = span.text();
            int columns = TextMeasure.columns(value);
            if (used + columns <= maxColumns) {
                taken = taken.append(StyledText.of(value, span.style(), span.link()));
                used += columns;
                continue;
            }
            taken = taken.append(
                    StyledText.of(TextMeasure.truncate(value, maxColumns - used), span.style(), span.link()));
            break;
        }
        return taken;
    }
}

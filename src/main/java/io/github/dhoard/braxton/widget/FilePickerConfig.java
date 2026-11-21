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

import static io.github.dhoard.braxton.widget.FilePicker.DEFAULT_MAX_ENTRIES;
import static io.github.dhoard.braxton.widget.FilePicker.DEFAULT_ROWS;
import static io.github.dhoard.braxton.widget.FilePicker.NO_STYLE;

import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.widget.FilePicker.Kind;
import io.github.dhoard.braxton.widget.FilePicker.Msg;
import io.github.dhoard.braxton.widget.FilePicker.Theme;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable picker definition.
 *
 * <p>It holds what does not change while the picker runs: the directory it opens, the boundary
 * it enforces, what may be chosen and how it is painted. The default directory is the empty
 * path, which names the process's current directory; a picker is normally given an explicit
 * one.</p>
 *
 * @param directory the directory the picker opens; the empty path names the current directory
 * @param root the directory no navigation may leave, or null for no boundary
 * @param extensions the allowed file extensions, normalized to lower case with a leading dot; an
 *     empty set allows every regular file
 * @param selectable the kinds {@link Msg.Confirm} chooses
 * @param followLinks whether a symlink is followed; a link that is not followed is listed but
 *     neither navigable nor selectable
 * @param hidden whether hidden entries are shown at first
 * @param maxEntries the entries one listing keeps before it reports truncation; at least one
 * @param rows the rows used before the terminal's own geometry arrives
 * @param reservedRows the rows the application paints outside the picker
 * @param theme how entries and the picker's own rows are painted
 * @param activeStyle the style merged over the selection while the picker is focused
 * @param inactiveStyle the style merged over the selection while it is not
 */
public record FilePickerConfig(
        Path directory,
        Path root,
        Set<String> extensions,
        Set<Kind> selectable,
        boolean followLinks,
        boolean hidden,
        int maxEntries,
        int rows,
        int reservedRows,
        Theme theme,
        Style activeStyle,
        Style inactiveStyle) {

    /**
     * Keeps the definition usable: every value is normalized here, once.
     */
    public FilePickerConfig {
        directory = directory == null ? Path.of("") : directory;
        extensions = normalize(extensions);
        selectable = selectable == null || selectable.isEmpty() ? Set.of(Kind.FILE) : Set.copyOf(selectable);
        maxEntries = Math.max(1, maxEntries);
        rows = Math.max(0, rows);
        reservedRows = Math.max(0, reservedRows);
        theme = theme == null ? Theme.plain() : theme;
        activeStyle = activeStyle == null ? NO_STYLE : activeStyle;
        inactiveStyle = inactiveStyle == null ? NO_STYLE : inactiveStyle;
    }

    /**
     * A picker over the process's current directory that chooses any regular file.
     *
     * @return the default definition
     */
    public static FilePickerConfig defaults() {
        return new FilePickerConfig(
                Path.of(""),
                null,
                Set.of(),
                Set.of(Kind.FILE),
                true,
                false,
                DEFAULT_MAX_ENTRIES,
                DEFAULT_ROWS,
                0,
                Theme.plain(),
                NO_STYLE.withInvert(),
                NO_STYLE);
    }

    /**
     * The same definition opening a different directory.
     *
     * @param value the directory; null is the current directory
     * @return the new definition
     */
    public FilePickerConfig withDirectory(Path value) {
        return new FilePickerConfig(
                value,
                root,
                extensions,
                selectable,
                followLinks,
                hidden,
                maxEntries,
                rows,
                reservedRows,
                theme,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition with a different boundary.
     *
     * @param value the root no navigation may leave, or null for none
     * @return the new definition
     */
    public FilePickerConfig withRoot(Path value) {
        return new FilePickerConfig(
                directory,
                value,
                extensions,
                selectable,
                followLinks,
                hidden,
                maxEntries,
                rows,
                reservedRows,
                theme,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition allowing a different set of extensions.
     *
     * @param value the extensions, with or without a leading dot; an empty set allows every file
     * @return the new definition
     */
    public FilePickerConfig withExtensions(Set<String> value) {
        return new FilePickerConfig(
                directory,
                root,
                value,
                selectable,
                followLinks,
                hidden,
                maxEntries,
                rows,
                reservedRows,
                theme,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition allowing the given extensions.
     *
     * @param value the extensions, with or without a leading dot
     * @return the new definition
     */
    public FilePickerConfig withExtensions(String... value) {
        return withExtensions(value == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(value)));
    }

    /**
     * The same definition choosing a different set of kinds.
     *
     * @param value the kinds a confirmation chooses; empty selects regular files
     * @return the new definition
     */
    public FilePickerConfig withSelectable(Set<Kind> value) {
        return new FilePickerConfig(
                directory,
                root,
                extensions,
                value,
                followLinks,
                hidden,
                maxEntries,
                rows,
                reservedRows,
                theme,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition choosing the given kinds.
     *
     * @param value the kinds a confirmation chooses
     * @return the new definition
     */
    public FilePickerConfig withSelectable(Kind... value) {
        return withSelectable(value == null ? Set.of() : new LinkedHashSet<>(Arrays.asList(value)));
    }

    /**
     * The same definition following, or not following, symlinks.
     *
     * @param value whether a symlink is followed
     * @return the new definition
     */
    public FilePickerConfig withFollowLinks(boolean value) {
        return new FilePickerConfig(
                directory,
                root,
                extensions,
                selectable,
                value,
                hidden,
                maxEntries,
                rows,
                reservedRows,
                theme,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition showing, or hiding, hidden entries at first.
     *
     * @param value whether hidden entries are shown
     * @return the new definition
     */
    public FilePickerConfig withHidden(boolean value) {
        return new FilePickerConfig(
                directory,
                root,
                extensions,
                selectable,
                followLinks,
                value,
                maxEntries,
                rows,
                reservedRows,
                theme,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition with a different listing bound.
     *
     * @param value the entries one listing keeps; at least one
     * @return the new definition
     */
    public FilePickerConfig withMaxEntries(int value) {
        return new FilePickerConfig(
                directory,
                root,
                extensions,
                selectable,
                followLinks,
                hidden,
                value,
                rows,
                reservedRows,
                theme,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition with a different fallback height.
     *
     * @param value the fallback rows
     * @return the new definition
     */
    public FilePickerConfig withRows(int value) {
        return new FilePickerConfig(
                directory,
                root,
                extensions,
                selectable,
                followLinks,
                hidden,
                maxEntries,
                value,
                reservedRows,
                theme,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition with a different number of reserved rows.
     *
     * @param value the rows the application paints outside the picker
     * @return the new definition
     */
    public FilePickerConfig withReservedRows(int value) {
        return new FilePickerConfig(
                directory,
                root,
                extensions,
                selectable,
                followLinks,
                hidden,
                maxEntries,
                rows,
                value,
                theme,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition with a different theme.
     *
     * @param value the theme; null is {@link Theme#plain()}
     * @return the new definition
     */
    public FilePickerConfig withTheme(Theme value) {
        return new FilePickerConfig(
                directory,
                root,
                extensions,
                selectable,
                followLinks,
                hidden,
                maxEntries,
                rows,
                reservedRows,
                value,
                activeStyle,
                inactiveStyle);
    }

    /**
     * The same definition with different selection styles.
     *
     * @param active the style merged over a focused picker's selection
     * @param inactive the style merged over an unfocused picker's selection
     * @return the new definition
     */
    public FilePickerConfig withSelectionStyles(Style active, Style inactive) {
        return new FilePickerConfig(
                directory,
                root,
                extensions,
                selectable,
                followLinks,
                hidden,
                maxEntries,
                rows,
                reservedRows,
                theme,
                active,
                inactive);
    }

    /**
     * Whether the definition allows a file called this.
     *
     * <p>The extension is the text after the last dot, compared case-insensitively. A name with
     * no dot in it, or whose only dot is its first character (a dot file), has no extension and
     * is allowed only when the definition allows every file.</p>
     *
     * @param fileName the file name to check; null has no extension
     * @return whether the name is allowed
     */
    public boolean allows(String fileName) {
        if (extensions.isEmpty()) {
            return true;
        }
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0
                && dot < fileName.length() - 1
                && extensions.contains(fileName.substring(dot).toLowerCase(Locale.ROOT));
    }

    /**
     * Whether the definition chooses entries of a kind.
     *
     * @param kind the kind to check
     * @return whether a confirmation may choose it
     */
    public boolean selects(Kind kind) {
        return selectable.contains(kind);
    }

    /**
     * Normalizes extensions to lower case with a leading dot, dropping the empty ones.
     */
    private static Set<String> normalize(Set<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String extension : extensions) {
            if (extension == null || extension.isBlank()) {
                continue;
            }
            String value = extension.trim().toLowerCase(Locale.ROOT);
            normalized.add(value.startsWith(".") ? value : "." + value);
        }
        return Set.copyOf(normalized);
    }
}

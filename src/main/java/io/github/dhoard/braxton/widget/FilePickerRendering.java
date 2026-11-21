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

import static io.github.dhoard.braxton.widget.FilePicker.DETAIL_GAP;
import static io.github.dhoard.braxton.widget.FilePicker.DIRECTORY_SUFFIX;
import static io.github.dhoard.braxton.widget.FilePicker.ELLIPSIS;
import static io.github.dhoard.braxton.widget.FilePicker.EMPTY;
import static io.github.dhoard.braxton.widget.FilePicker.HELP_WIDTH;
import static io.github.dhoard.braxton.widget.FilePicker.MARKER;
import static io.github.dhoard.braxton.widget.FilePicker.MARKER_GAP;
import static io.github.dhoard.braxton.widget.FilePicker.NO_STYLE;
import static io.github.dhoard.braxton.widget.FilePicker.STATUS_SEPARATOR;

import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.TextMeasure;
import io.github.dhoard.braxton.widget.FilePicker.Entry;
import io.github.dhoard.braxton.widget.FilePicker.Request;
import java.nio.file.Path;
import java.util.List;

/**
 * File picker rows, path fitting, and status rendering.
 */
final class FilePickerRendering {

    /**
     * The path row: the directory, cut at the start so its last segments stay readable.
     */
    static StyledText pathLine(FilePickerModel state, FilePickerConfig config, int cols) {
        if (cols <= 0) {
            return StyledText.empty();
        }
        String path = state.directory().toString();
        String shownPath = path.isEmpty() ? "." : path;
        return StyledText.of(
                headTruncated(shownPath, cols), style(config.theme().path()));
    }

    /**
     * One entry row: the marker, the name, and the metadata column aligned to the right edge.
     *
     * <p>The metadata is dropped before the name is cut, and the name takes the whole row when the
     * metadata no longer shares it: a picker is read by its names. Both are measured after a name
     * has been flattened to one row, so the columns the arithmetic counts are the columns the row
     * paints, and the row is measured once more as it is composed because a name may begin with a
     * cluster that joins the marker's trailing space and costs a column the sum does not count.</p>
     */
    static StyledText entryLine(
            FilePickerModel state, FilePickerConfig config, Entry entry, boolean selected, int cols) {
        if (cols <= 0) {
            return StyledText.empty();
        }
        Style selection = state.focused() ? config.activeStyle() : config.inactiveStyle();
        Style markStyle = merge(Style.NONE, selected ? selection : NO_STYLE);
        Style nameStyle = merge(config.theme().name(entry, selected, state.focused()), selection);
        Style detailStyle = merge(config.theme().detail(entry, selected, state.focused()), selection);
        String mark = TextMeasure.truncate(selected ? MARKER : MARKER_GAP, cols);
        int available = Math.max(0, cols - TextMeasure.columns(mark));
        if (available == 0) {
            return StyledText.of(mark, markStyle);
        }
        String name = LineBreaks.singleRow(entry.name()) + (entry.directory() ? DIRECTORY_SUFFIX : "");
        Parts parts = parts(name, available, entry.detail());
        int overflow = TextMeasure.columns(mark + parts.plain()) - cols;
        if (overflow > 0) {
            // A cluster that joined the marker cost a column: the name gives it back, and the
            // metadata is the first thing to go.
            parts = parts(TextMeasure.truncate(name, Math.max(0, TextMeasure.columns(name) - overflow)), available, "");
        }
        StyledText line = StyledText.of(mark, markStyle)
                .append(StyledText.of(parts.name(), nameStyle))
                .append(StyledText.of(parts.spaces(), nameStyle))
                .append(StyledText.of(parts.detail(), detailStyle));
        return TextMeasure.columns(line.plainText()) <= cols ? line : oneRow(line, cols);
    }

    /**
     * The three runs of one entry row: the name, the spaces after it, and the metadata.
     */
    record Parts(String name, String spaces, String detail) {

        /**
         * The three runs as the row paints them.
         */
        String plain() {
            return name + spaces + detail;
        }
    }

    /**
     * The runs of one row: the name cut to the room, then the metadata right-aligned when it fits.
     */
    static Parts parts(String name, int available, String detail) {
        String metadata = detail == null ? "" : detail;
        int gap = available - TextMeasure.columns(name) - TextMeasure.columns(metadata);
        if (!metadata.isEmpty() && gap >= TextMeasure.columns(DETAIL_GAP)) {
            return new Parts(name, " ".repeat(gap), metadata);
        }
        return new Parts(TextMeasure.truncate(name, available), "", "");
    }

    /**
     * The status row: what is shown, whether the listing was cut, and what the state is doing.
     */
    static StyledText statusLine(FilePickerModel state, FilePickerConfig config, int shown, int cols) {
        if (cols <= 0) {
            return StyledText.empty();
        }
        int total = state.entries().size();
        StyledText line =
                StyledText.of(counts(shown, total), style(config.theme().status()));
        if (state.truncated()) {
            line = line.append(StyledText.of(
                    STATUS_SEPARATOR + "truncated at " + config.maxEntries(),
                    style(config.theme().status())));
        }
        return switch (state.request()) {
            case Request.Loading loading ->
                line.append(StyledText.of(
                        STATUS_SEPARATOR + "loading " + loading.target(),
                        style(config.theme().status())));
            case Request.Failed failed ->
                line.append(StyledText.of(
                        STATUS_SEPARATOR + failed.message() + STATUS_SEPARATOR + "r to retry",
                        style(config.theme().error())));
            case Request.Idle idle ->
                state.notice().isEmpty()
                        ? line
                        : line.append(StyledText.of(
                                STATUS_SEPARATOR + state.notice(),
                                style(config.theme().notice())));
        };
    }

    /**
     * The counts one paint shows: what is shown, and the total when hidden entries are not.
     */
    static String counts(int shown, int total) {
        if (total == 0) {
            return EMPTY;
        }
        String entries = shown == 1 ? "1 entry" : shown + " entries";
        return shown == total ? entries : entries + " of " + total;
    }

    /**
     * The rows the picker's own chrome occupies.
     */
    static int chromeRows(FilePickerModel state, FilePickerConfig config) {
        return 2
                + Help.render(FilePicker.keys(state), state.expanded(), HELP_WIDTH)
                        .lines()
                        .size();
    }

    /**
     * A text cut at the start, so the end of a path survives a narrow terminal.
     */
    static String headTruncated(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (TextMeasure.columns(text) <= width) {
            return text;
        }
        int available = width - TextMeasure.columns(ELLIPSIS);
        StringBuilder out = new StringBuilder();
        List<String> clusters = TextMeasure.graphemes(text);
        int used = 0;
        for (int index = clusters.size() - 1; index >= 0 && used < available; index--) {
            String cluster = clusters.get(index);
            int clusterWidth = TextMeasure.columns(cluster);
            if (used + clusterWidth > available) {
                break;
            }
            out.insert(0, cluster);
            used += clusterWidth;
        }
        return ELLIPSIS + out;
    }

    /**
     * One painted row: the value's line breaks become spaces and it is cut to the width.
     */
    static StyledText oneRow(StyledText value, int width) {
        if (TextMeasure.columns(value.plainText()) <= width) {
            return value;
        }
        StyledText row = StyledText.empty();
        int used = 0;
        for (StyledText.Span span : value.spans()) {
            String text = LineBreaks.singleRow(span.text());
            int columns = TextMeasure.columns(text);
            if (used + columns <= width) {
                row = row.append(StyledText.of(text, span.style(), span.link()));
                used += columns;
                continue;
            }
            row = row.append(
                    StyledText.of(TextMeasure.truncate(text, Math.max(0, width - used)), span.style(), span.link()));
            break;
        }
        return row;
    }

    /**
     * A theme's answer as a style: null paints nothing extra.
     */
    static Style style(Style value) {
        return value == null ? NO_STYLE : value;
    }

    /**
     * A base style with an overlay merged over it, both nullable.
     */
    static Style merge(Style base, Style overlay) {
        return style(base).merge(style(overlay));
    }

    /**
     * The last segment of a path, for a status phrase.
     */
    static String name(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }
}

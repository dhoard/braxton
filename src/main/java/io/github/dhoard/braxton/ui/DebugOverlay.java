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

package io.github.dhoard.braxton.ui;

import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.style.WidthPolicy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, display-only diagnostics over a resolved frame. No input route or focus state changes.
 */
public final class DebugOverlay {
    /**
     * The maximum number of layout rectangles captured per snapshot.
     */
    public static final int MAX_BOUNDS = 256;
    /**
     * The maximum number of recent messages retained in a snapshot.
     */
    public static final int MAX_MESSAGES = 4;
    /**
     * The maximum UTF-16 length of one captured message label.
     */
    public static final int MAX_LABEL_CHARS = 160;

    private static final Style BORDER = Style.fg(new Color(100, 220, 255)).withBackground(new Color(20, 35, 45));
    private static final Style PANEL = Style.fg(Color.WHITE).withBackground(Color.BLACK);

    private DebugOverlay() {}

    /**
     * Immutable diagnostic values, independent of the tree and application model.
     *
     * @param viewport the actual painting viewport, not an intrinsically sized root leaf
     * @param bounds up to 256 unique layout rectangles
     * @param focusPath active focused leaf's placement path, or none
     * @param messages last four sanitized message summaries
     * @param frameNanos view/layout/paint elapsed time, excluding diagnostics/encoding/output
     */
    public record Snapshot(Rect viewport, List<Rect> bounds, String focusPath, List<String> messages, long frameNanos) {
        /**
         * Creates a {@code Snapshot} value.
         *
         * @param viewport the actual painting viewport, not an intrinsically sized root leaf
         * @param bounds up to 256 unique layout rectangles
         * @param focusPath active focused leaf's placement path, or none
         * @param messages last four sanitized message summaries
         * @param frameNanos view/layout/paint elapsed time, excluding diagnostics/encoding/output
         */
        public Snapshot {
            Objects.requireNonNull(viewport, "viewport");
            Objects.requireNonNull(bounds, "bounds");
            bounds = List.copyOf(new LinkedHashSet<>(bounds.subList(0, Math.min(MAX_BOUNDS, bounds.size()))));
            focusPath = label(focusPath == null ? "none" : focusPath);
            Objects.requireNonNull(messages, "messages");
            List<String> clean = new ArrayList<>();
            for (int i = Math.max(0, messages.size() - MAX_MESSAGES); i < messages.size(); i++) {
                clean.add(label(Objects.requireNonNull(messages.get(i), "message")));
            }
            messages = List.copyOf(clean);
            frameNanos = Math.max(0, frameNanos);
        }
    }

    /**
     * Sanitizes a bounded single-row label; it can never supply terminal control syntax.
     *
     * @param text the text to process
     * @return sanitizes a bounded single-row label; it can never supply terminal control syntax
     */
    public static String label(String text) {
        Objects.requireNonNull(text, "text");
        String bounded = text.substring(0, Math.min(text.length(), MAX_LABEL_CHARS));
        return StyledText.plain(LineBreaks.singleRowAndTabs(bounded)).plainText();
    }

    /**
     * Captures actual geometry and the same focused leaf the input router selects.
     *
     * @param <Msg> the message type
     * @param frameNanos the measured frame duration in nanoseconds
     * @param messages the recent message summaries
     * @param placement the committed layout placement
     * @param viewport the available terminal viewport
     * @return captures actual geometry and the same focused leaf the input router selects
     */
    public static <Msg> Snapshot capture(
            ElementTree.Placement<Msg> placement, Rect viewport, List<String> messages, long frameNanos) {
        Objects.requireNonNull(placement, "placement");
        Capture<Msg> capture = new Capture<>(InteractionRouter.focused(placement));
        capture.walk(placement, "root");
        return new Snapshot(viewport, List.copyOf(capture.bounds), capture.focusPath, messages, frameNanos);
    }

    private static final class Capture<Msg> {
        private final Element.Leaf<?, Msg> focused;
        private final Set<Rect> bounds = new LinkedHashSet<>();
        private String focusPath = "none";

        Capture(Element.Leaf<?, Msg> focused) {
            this.focused = focused;
        }

        private boolean complete() {
            return bounds.size() == MAX_BOUNDS && (focused == null || !focusPath.equals("none"));
        }

        void walk(ElementTree.Placement<Msg> node, String path) {
            if (complete()) return;
            if (bounds.size() < MAX_BOUNDS) bounds.add(node.bounds());
            if (node.element() == focused) {
                focusPath =
                        path + " " + focused.widget().getClass().getSimpleName() + " @ " + coordinates(node.bounds());
            }
            for (int i = 0; i < node.children().size() && !complete(); i++)
                walk(node.children().get(i), path + "/" + i);
        }
    }

    /**
     * Tints rectangle edges without replacing glyphs, then paints a clipped panel at the bottom.
     *
     * @param snapshot the immutable layout and runtime diagnostic snapshot
     * @param surface the clipped surface to paint
     */
    public static void paint(Snapshot snapshot, Surface surface) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(surface, "surface");
        Rect viewport = snapshot.viewport();
        if (viewport.isEmpty()) return;
        for (Rect bounds : snapshot.bounds()) outline(bounds.clip(viewport), surface);
        List<String> lines = new ArrayList<>();
        lines.add("DEBUG bounds=" + snapshot.bounds().size() + " frame=" + snapshot.frameNanos() / 1000 + "us");
        lines.add("bounds " + coordinates(viewport));
        lines.add("focus=" + snapshot.focusPath());
        if (snapshot.messages().isEmpty()) lines.add("msg=(none)");
        else for (String message : snapshot.messages()) lines.add("msg=" + message);
        int rows = Math.min(viewport.rows(), lines.size());
        int top = (int) Math.min(Integer.MAX_VALUE, (long) viewport.row() + viewport.rows() - rows);
        Rect panel = new Rect(top, viewport.col(), rows, viewport.cols());
        surface.fill(panel, PANEL);
        for (int row = 0; row < rows && (long) top + row <= Integer.MAX_VALUE; row++) {
            surface.writeLine(
                    top + row, panel.col(), panel.cols(), StyledText.of(lines.get(row), PANEL), WidthPolicy.PINNED);
        }
    }

    private static String coordinates(Rect bounds) {
        return bounds.row() + "," + bounds.col() + " " + bounds.rows() + "x" + bounds.cols();
    }

    private static void outline(Rect rect, Surface surface) {
        if (rect.isEmpty()) return;
        int rows = (int) Math.min(rect.rows(), (long) Integer.MAX_VALUE - rect.row() + 1);
        int cols = (int) Math.min(rect.cols(), (long) Integer.MAX_VALUE - rect.col() + 1);
        int bottom = rect.row() + rows - 1, right = rect.col() + cols - 1;
        for (int offset = 0; offset < cols; offset++) {
            surface.restyle(rect.row(), rect.col() + offset, BORDER);
            surface.restyle(bottom, rect.col() + offset, BORDER);
        }
        for (int offset = 0; offset < rows; offset++) {
            surface.restyle(rect.row() + offset, rect.col(), BORDER);
            surface.restyle(rect.row() + offset, right, BORDER);
        }
    }
}

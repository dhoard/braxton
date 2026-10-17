/*
 * Copyright (c) 2026-present Doug Hoard
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

package examples.harness.ai;

import io.github.dhoard.braxton.style.Color;
import io.github.dhoard.braxton.style.Style;

/**
 * The showcase's semantic palette.
 *
 * <p>These values re-express the roles used by the sibling Amanda TUI as plain Braxton styles. Nothing
 * here imports Amanda, and no library type is changed.</p>
 */
final class HarnessTheme {

    static final Color ACCENT_COLOR = color("#8abeb7");
    static final Color ERROR_COLOR = color("#cc6666");
    static final Color WARNING_COLOR = color("#ffff00");
    static final Color NOTICE_COLOR = color("#de935f");
    static final Color MUTED_COLOR = color("#808080");
    static final Color DIM_COLOR = color("#666666");
    static final Color TEXT_COLOR = color("#d4d4d4");
    static final Color BACKGROUND_COLOR = color("#000000");

    static final Color USER_MESSAGE_BG_COLOR = color("#343541");
    static final Color TOOL_PENDING_BG_COLOR = color("#282832");
    static final Color TOOL_SUCCESS_BG_COLOR = color("#283228");
    static final Color TOOL_ERROR_BG_COLOR = color("#3c2828");
    static final Color THINKING_TEXT_COLOR = color("#808080");

    static final Color SCROLLBAR_TRACK_COLOR = color("#505050");
    static final Color SCROLLBAR_THUMB_COLOR = color("#d4d4d4");

    static final Style BACKGROUND = Style.bg(BACKGROUND_COLOR);
    static final Style TEXT = Style.fg(TEXT_COLOR);
    static final Style ACCENT = Style.fg(ACCENT_COLOR);
    static final Style ERROR = Style.fg(ERROR_COLOR);
    static final Style WARNING = Style.fg(WARNING_COLOR);
    static final Style NOTICE = Style.fg(NOTICE_COLOR);
    static final Style DIM = Style.fg(DIM_COLOR);
    static final Style THINKING = Style.fg(THINKING_TEXT_COLOR).withItalic();
    static final Style USER_MESSAGE_BG = Style.bg(USER_MESSAGE_BG_COLOR);
    static final Style TOOL_PENDING_BG = Style.bg(TOOL_PENDING_BG_COLOR);
    static final Style TOOL_SUCCESS_BG = Style.bg(TOOL_SUCCESS_BG_COLOR);
    static final Style TOOL_ERROR_BG = Style.bg(TOOL_ERROR_BG_COLOR);
    static final Style SCROLLBAR_TRACK = Style.fg(SCROLLBAR_TRACK_COLOR).withBackground(BACKGROUND_COLOR);
    static final Style SCROLLBAR_THUMB = Style.fg(SCROLLBAR_THUMB_COLOR).withBackground(BACKGROUND_COLOR);

    private HarnessTheme() {
    }

    private static Color color(String hex) {
        return Color.parse(hex).orElseThrow(() -> new IllegalArgumentException("bad theme colour " + hex));
    }
}

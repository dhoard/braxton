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

package io.github.dhoard.braxton.widget;

import java.util.OptionalInt;

import io.github.dhoard.braxton.clipboard.PasteMode;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.style.Style;

/** Rendering and behaviour options for an {@link Editor}. Immutable; derive changes with withers. */
public record EditorOptions(OptionalInt maxLength, PasteMode pasteMode,
        boolean collapsePasteBlankLines, Masking masking, Key copyKey, Key pasteKey,
        String prompt, Style promptStyle, Style textStyle, Style selectionStyle,
        int maxVisibleLines, boolean copyEnabled) {

    /** How the content is displayed. Copying is disabled for non-visible masking unless enabled. */
    public enum Masking {
        NONE,
        PASSWORD,
        HIDDEN
    }

    public static final Key DEFAULT_COPY_KEY = Key.of("c", io.github.dhoard.braxton.input.Modifiers.ALT);
    public static final Key DEFAULT_PASTE_KEY = Key.of("v", io.github.dhoard.braxton.input.Modifiers.CTRL);

    public EditorOptions {
        maxLength = maxLength == null ? OptionalInt.empty() : maxLength;
        maxLength.ifPresent(limit -> {
            if (limit < 0) {
                throw new IllegalArgumentException("maxLength must not be negative");
            }
        });
        pasteMode = pasteMode == null ? PasteMode.NORMAL : pasteMode;
        masking = masking == null ? Masking.NONE : masking;
        copyKey = copyKey == null ? DEFAULT_COPY_KEY : copyKey;
        pasteKey = pasteKey == null ? DEFAULT_PASTE_KEY : pasteKey;
        prompt = prompt == null ? "" : prompt;
        promptStyle = promptStyle == null ? Style.NONE : promptStyle;
        textStyle = textStyle == null ? Style.NONE : textStyle;
        selectionStyle = selectionStyle == null ? Style.NONE.withInvert() : selectionStyle;
        if (maxVisibleLines < 1) {
            throw new IllegalArgumentException("maxVisibleLines must be positive");
        }
    }

    public static EditorOptions defaults() {
        return new EditorOptions(OptionalInt.empty(), PasteMode.NORMAL, false,
                Masking.NONE, DEFAULT_COPY_KEY, DEFAULT_PASTE_KEY, "", Style.NONE, Style.NONE,
                Style.NONE.withInvert(), 6, true);
    }

    public EditorOptions withMaxLength(int limit) {
        return new EditorOptions(OptionalInt.of(limit), pasteMode, collapsePasteBlankLines, masking,
                copyKey, pasteKey, prompt, promptStyle, textStyle, selectionStyle, maxVisibleLines,
                copyEnabled);
    }

    public EditorOptions withUnlimitedLength() {
        return new EditorOptions(OptionalInt.empty(), pasteMode, collapsePasteBlankLines, masking,
                copyKey, pasteKey, prompt, promptStyle, textStyle, selectionStyle, maxVisibleLines,
                copyEnabled);
    }

    public EditorOptions withPaste(PasteMode mode, boolean collapseBlankLines) {
        return new EditorOptions(maxLength, mode, collapseBlankLines, masking, copyKey, pasteKey,
                prompt, promptStyle, textStyle, selectionStyle, maxVisibleLines, copyEnabled);
    }

    public EditorOptions withMasking(Masking value) {
        return new EditorOptions(maxLength, pasteMode, collapsePasteBlankLines, value, copyKey,
                pasteKey, prompt, promptStyle, textStyle, selectionStyle, maxVisibleLines,
                copyEnabled);
    }

    public EditorOptions withKeys(Key copy, Key paste) {
        return new EditorOptions(maxLength, pasteMode, collapsePasteBlankLines, masking, copy, paste,
                prompt, promptStyle, textStyle, selectionStyle, maxVisibleLines, copyEnabled);
    }

    public EditorOptions withPrompt(String value, Style style) {
        return new EditorOptions(maxLength, pasteMode, collapsePasteBlankLines, masking, copyKey,
                pasteKey, value, style, textStyle, selectionStyle, maxVisibleLines, copyEnabled);
    }

    public EditorOptions withStyles(Style text, Style promptText, Style selection) {
        return new EditorOptions(maxLength, pasteMode, collapsePasteBlankLines, masking, copyKey,
                pasteKey, prompt, promptText, text, selection, maxVisibleLines, copyEnabled);
    }

    public EditorOptions withVisibleLines(int lines) {
        return new EditorOptions(maxLength, pasteMode, collapsePasteBlankLines, masking, copyKey,
                pasteKey, prompt, promptStyle, textStyle, selectionStyle, lines, copyEnabled);
    }

    public EditorOptions withCopyEnabled(boolean value) {
        return new EditorOptions(maxLength, pasteMode, collapsePasteBlankLines, masking, copyKey,
                pasteKey, prompt, promptStyle, textStyle, selectionStyle, maxVisibleLines, value);
    }

    /** Whether copying is allowed under the masking policy. */
    public boolean canCopy() {
        return copyEnabled && masking != Masking.PASSWORD && masking != Masking.HIDDEN;
    }
}

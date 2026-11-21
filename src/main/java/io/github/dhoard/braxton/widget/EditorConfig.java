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

import io.github.dhoard.braxton.clipboard.PasteMode;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.style.LineBreaks;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.widget.Editor.CursorPolicy;
import io.github.dhoard.braxton.widget.Editor.Presentation;
import java.util.OptionalInt;

/**
 * Immutable editing and rendering policy.
 *
 * @param maxLength the maximum draft length in UTF-16 units, or empty for unlimited
 * @param pasteMode the sanitizing policy applied to a paste payload
 * @param collapsePasteBlankLines whether a paste collapses runs of blank lines
 * @param masking how the draft is displayed and whether it may be copied
 * @param copyKey the key gesture that copies
 * @param pasteKey the key gesture that requests a paste
 * @param prompt the one-line prompt painted before the first visible row
 * @param promptStyle the style applied to the prompt
 * @param textStyle the style applied to the draft
 * @param selectionStyle the style applied to the selection
 * @param maxVisibleLines the number of draft rows the editor paints at once
 * @param copyEnabled whether a visible draft may be copied
 * @param cursor how the caret is drawn while the editor is focused
 * @param presentation how the draft is framed: placeholder, gutter, cursor line and height
 * @param wrapWidth the columns the editor lays its rows out for, or empty to paint each logical
 *     line as one row and let the layout wrap it
 */
public record EditorConfig(
        OptionalInt maxLength,
        PasteMode pasteMode,
        boolean collapsePasteBlankLines,
        Masking masking,
        Key copyKey,
        Key pasteKey,
        String prompt,
        Style promptStyle,
        Style textStyle,
        Style selectionStyle,
        int maxVisibleLines,
        boolean copyEnabled,
        CursorPolicy cursor,
        Presentation presentation,
        OptionalInt wrapWidth) {

    /**
     * How the content is displayed.
     *
     * <p>A masked draft ({@link Masking#PASSWORD} or {@link Masking#HIDDEN}) never copies: its
     * cleartext does not leave the editor even when {@code copyEnabled} is true. Setting
     * {@code copyEnabled} to false additionally refuses to copy a visible draft, which is how a
     * field opts out of copying entirely.</p>
     */
    public enum Masking {
        /**
         * The draft is displayed as typed.
         */
        NONE,
        /**
         * Every cluster is displayed as one mask glyph.
         */
        PASSWORD,
        /**
         * The draft is not displayed at all.
         */
        HIDDEN
    }

    /**
     * The copy gesture a platform has no standard for: {@code alt+c}.
     */
    public static final Key DEFAULT_COPY_KEY = Key.of("c", io.github.dhoard.braxton.input.Modifiers.ALT);
    /**
     * The paste gesture a platform has no standard for: {@code ctrl+v}.
     */
    public static final Key DEFAULT_PASTE_KEY = Key.of("v", io.github.dhoard.braxton.input.Modifiers.CTRL);

    /**
     * Rejects an inconsistent policy and prepares the one-line prompt.
     */
    public EditorConfig {
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
        prompt = singleLine(prompt);
        promptStyle = promptStyle == null ? Style.NONE : promptStyle;
        textStyle = textStyle == null ? Style.NONE : textStyle;
        selectionStyle = selectionStyle == null ? Style.NONE.withInvert() : selectionStyle;
        if (maxVisibleLines < 1) {
            throw new IllegalArgumentException("maxVisibleLines must be positive");
        }
        cursor = cursor == null ? CursorPolicy.painted() : cursor;
        presentation = presentation == null ? Presentation.plain() : presentation;
        wrapWidth = wrapWidth == null ? OptionalInt.empty() : wrapWidth;
        wrapWidth.ifPresent(width -> {
            if (width < 1) {
                throw new IllegalArgumentException("wrapWidth must be positive");
            }
        });
    }

    /**
     * A policy that keeps the painted block and paints no frame, for callers written before
     * cursors and the textarea presentation existed.
     *
     * @param maxLength the maximum draft length in UTF-16 units, or empty for unlimited
     * @param pasteMode the sanitizing policy applied to a paste payload
     * @param collapsePasteBlankLines whether a paste collapses runs of blank lines
     * @param masking how the draft is displayed and whether it may be copied
     * @param copyKey the key gesture that copies
     * @param pasteKey the key gesture that requests a paste
     * @param prompt the one-line prompt painted before the first visible row
     * @param promptStyle the style applied to the prompt
     * @param textStyle the style applied to the draft
     * @param selectionStyle the style applied to the selection
     * @param maxVisibleLines the number of draft rows the editor paints at once
     * @param copyEnabled whether a visible draft may be copied
     * @param cursor how the caret is drawn while the editor is focused
     */
    public EditorConfig(
            OptionalInt maxLength,
            PasteMode pasteMode,
            boolean collapsePasteBlankLines,
            Masking masking,
            Key copyKey,
            Key pasteKey,
            String prompt,
            Style promptStyle,
            Style textStyle,
            Style selectionStyle,
            int maxVisibleLines,
            boolean copyEnabled,
            CursorPolicy cursor) {
        this(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                Presentation.plain(),
                OptionalInt.empty());
    }

    /**
     * A policy whose caret keeps the painted block, for callers written before cursors existed.
     *
     * @param maxLength the maximum draft length in UTF-16 units, or empty for unlimited
     * @param pasteMode the sanitizing policy applied to a paste payload
     * @param collapsePasteBlankLines whether a paste collapses runs of blank lines
     * @param masking how the draft is displayed and whether it may be copied
     * @param copyKey the key gesture that copies
     * @param pasteKey the key gesture that requests a paste
     * @param prompt the one-line prompt painted before the first visible row
     * @param promptStyle the style applied to the prompt
     * @param textStyle the style applied to the draft
     * @param selectionStyle the style applied to the selection
     * @param maxVisibleLines the number of draft rows the editor paints at once
     * @param copyEnabled whether a visible draft may be copied
     */
    public EditorConfig(
            OptionalInt maxLength,
            PasteMode pasteMode,
            boolean collapsePasteBlankLines,
            Masking masking,
            Key copyKey,
            Key pasteKey,
            String prompt,
            Style promptStyle,
            Style textStyle,
            Style selectionStyle,
            int maxVisibleLines,
            boolean copyEnabled) {
        this(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                CursorPolicy.painted(),
                Presentation.plain(),
                OptionalInt.empty());
    }

    /**
     * A prompt prefixes one line, so CR/LF, CR and LF each become one space.
     */
    private static String singleLine(String value) {
        return LineBreaks.singleRow(value);
    }

    /**
     * The default policy: unlimited, unmasked, copyable, six rows.
     *
     * @return the default configuration
     */
    public static EditorConfig defaults() {
        return new EditorConfig(
                OptionalInt.empty(),
                PasteMode.NORMAL,
                false,
                Masking.NONE,
                DEFAULT_COPY_KEY,
                DEFAULT_PASTE_KEY,
                "",
                Style.NONE,
                Style.NONE,
                Style.NONE.withInvert(),
                6,
                true,
                CursorPolicy.painted(),
                Presentation.plain(),
                OptionalInt.empty());
    }

    /**
     * The same policy with a length limit.
     *
     * @param limit the maximum draft length in UTF-16 units; never negative
     * @return the new configuration
     */
    public EditorConfig withMaxLength(int limit) {
        return new EditorConfig(
                OptionalInt.of(limit),
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                presentation,
                wrapWidth);
    }

    /**
     * The same policy with no length limit.
     *
     * @return the new configuration
     */
    public EditorConfig withUnlimitedLength() {
        return new EditorConfig(
                OptionalInt.empty(),
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                presentation,
                wrapWidth);
    }

    /**
     * The same policy with a different paste policy.
     *
     * @param mode the sanitizing policy applied to a paste payload
     * @param collapseBlankLines whether a paste collapses runs of blank lines
     * @return the new configuration
     */
    public EditorConfig withPaste(PasteMode mode, boolean collapseBlankLines) {
        return new EditorConfig(
                maxLength,
                mode,
                collapseBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                presentation,
                wrapWidth);
    }

    /**
     * The same policy with a different masking policy.
     *
     * @param value how the draft is displayed and copied
     * @return the new configuration
     */
    public EditorConfig withMasking(Masking value) {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                value,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                presentation,
                wrapWidth);
    }

    /**
     * The same policy with different copy and paste gestures.
     *
     * @param copy the key gesture that copies
     * @param paste the key gesture that requests a paste
     * @return the new configuration
     */
    public EditorConfig withKeys(Key copy, Key paste) {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copy,
                paste,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                presentation,
                wrapWidth);
    }

    /**
     * The same policy with a different prompt.
     *
     * @param value the prompt text; line breaks become spaces
     * @param style the style applied to the prompt
     * @return the new configuration
     */
    public EditorConfig withPrompt(String value, Style style) {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                value,
                style,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                presentation,
                wrapWidth);
    }

    /**
     * The same policy with different styles.
     *
     * @param text the style applied to the draft
     * @param promptText the style applied to the prompt
     * @param selection the style applied to the selection
     * @return the new configuration
     */
    public EditorConfig withStyles(Style text, Style promptText, Style selection) {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptText,
                text,
                selection,
                maxVisibleLines,
                copyEnabled,
                cursor,
                presentation,
                wrapWidth);
    }

    /**
     * The same policy with a different visible height.
     *
     * @param lines the number of draft rows to paint; must be positive
     * @return the new configuration
     */
    public EditorConfig withVisibleLines(int lines) {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                lines,
                copyEnabled,
                cursor,
                presentation,
                wrapWidth);
    }

    /**
     * The same policy with copying allowed or refused.
     *
     * @param value whether a visible draft may be copied
     * @return the new configuration
     */
    public EditorConfig withCopyEnabled(boolean value) {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                value,
                cursor,
                presentation,
                wrapWidth);
    }

    /**
     * The same policy with a different caret presentation.
     *
     * @param value how the caret is drawn; null keeps the painted block
     * @return the new configuration
     */
    public EditorConfig withCursor(CursorPolicy value) {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                value == null ? CursorPolicy.painted() : value,
                presentation,
                wrapWidth);
    }

    /**
     * Whether copying is allowed under the masking policy.
     *
     * @return whether copying is permitted
     */
    public boolean canCopy() {
        return copyEnabled && masking != Masking.PASSWORD && masking != Masking.HIDDEN;
    }

    /**
     * The same policy with a different frame: placeholder, gutter, cursor line and height.
     *
     * @param value the frame policy; null paints no frame
     * @return the new configuration
     */
    public EditorConfig withPresentation(Presentation value) {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                value == null ? Presentation.plain() : value,
                wrapWidth);
    }

    /**
     * The same policy laid out for a fixed number of columns.
     *
     * <p>This is the width vertical movement, the caret and a dynamic height are computed for.
     * The space a view is actually given still wins when it is narrower, so an application that
     * derives this from its viewport - the way it derives {@link #maxVisibleLines()} - keeps the
     * two in agreement across a resize.</p>
     *
     * @param columns the columns the rows are wrapped at; must be positive
     * @return the new configuration
     */
    public EditorConfig withWrapWidth(int columns) {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                presentation,
                OptionalInt.of(columns));
    }

    /**
     * The same policy with no declared width, so each logical line is painted as one row.
     *
     * @return the new configuration
     */
    public EditorConfig withUnboundedWidth() {
        return new EditorConfig(
                maxLength,
                pasteMode,
                collapsePasteBlankLines,
                masking,
                copyKey,
                pasteKey,
                prompt,
                promptStyle,
                textStyle,
                selectionStyle,
                maxVisibleLines,
                copyEnabled,
                cursor,
                presentation,
                OptionalInt.empty());
    }
}

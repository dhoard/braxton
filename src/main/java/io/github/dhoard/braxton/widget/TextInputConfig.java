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

import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.widget.TextInput.Appearance;
import io.github.dhoard.braxton.widget.TextInput.Completion;
import io.github.dhoard.braxton.widget.TextInput.SubmitPolicy;
import io.github.dhoard.braxton.widget.TextInput.Validator;
import java.util.OptionalInt;

/**
 * Immutable editing and presentation policy.
 *
 * @param maxLength the maximum draft length in UTF-16 units, or empty for unlimited
 * @param masking how the draft is displayed and whether it may be copied
 * @param appearance what the field paints around the draft, and how wide it is
 * @param completion whether and how the field completes from supplied candidates
 * @param validator the pure policy the draft is validated with after every edit
 * @param submitPolicy what submission does with a draft the validator refuses
 * @param copyEnabled whether a visible draft may be copied
 * @param copyKey the key gesture that copies
 * @param pasteKey the key gesture that requests a paste
 */
public record TextInputConfig(
        OptionalInt maxLength,
        EditorConfig.Masking masking,
        Appearance appearance,
        Completion completion,
        Validator validator,
        SubmitPolicy submitPolicy,
        boolean copyEnabled,
        Key copyKey,
        Key pasteKey) {

    /**
     * The copy gesture a field uses unless it is configured otherwise.
     */
    public static final Key DEFAULT_COPY_KEY = EditorConfig.DEFAULT_COPY_KEY;

    /**
     * The paste gesture a field uses unless it is configured otherwise.
     */
    public static final Key DEFAULT_PASTE_KEY = EditorConfig.DEFAULT_PASTE_KEY;

    /**
     * Rejects an inconsistent policy and substitutes defaults for what is missing.
     */
    public TextInputConfig {
        maxLength = maxLength == null ? OptionalInt.empty() : maxLength;
        maxLength.ifPresent(limit -> {
            if (limit < 0) {
                throw new IllegalArgumentException("maxLength must not be negative");
            }
        });
        masking = masking == null ? EditorConfig.Masking.NONE : masking;
        appearance = appearance == null ? Appearance.plain() : appearance;
        completion = completion == null ? Completion.disabled() : completion;
        validator = validator == null ? Validator.valid() : validator;
        submitPolicy = submitPolicy == null ? SubmitPolicy.VALID_ONLY : submitPolicy;
        copyKey = copyKey == null ? DEFAULT_COPY_KEY : copyKey;
        pasteKey = pasteKey == null ? DEFAULT_PASTE_KEY : pasteKey;
    }

    /**
     * The default policy: an unlimited, visible, copyable field that accepts every draft, with
     * no prompt, no placeholder and no completion.
     *
     * @return the default configuration
     */
    public static TextInputConfig defaults() {
        return new TextInputConfig(
                OptionalInt.empty(),
                EditorConfig.Masking.NONE,
                Appearance.plain(),
                Completion.disabled(),
                Validator.valid(),
                SubmitPolicy.VALID_ONLY,
                true,
                DEFAULT_COPY_KEY,
                DEFAULT_PASTE_KEY);
    }

    /**
     * The same policy with a length limit in UTF-16 units.
     *
     * @param limit the maximum draft length; never negative
     * @return the new configuration
     */
    public TextInputConfig withMaxLength(int limit) {
        return new TextInputConfig(
                OptionalInt.of(limit),
                masking,
                appearance,
                completion,
                validator,
                submitPolicy,
                copyEnabled,
                copyKey,
                pasteKey);
    }

    /**
     * The same policy with no length limit.
     *
     * @return the new configuration
     */
    public TextInputConfig withUnlimitedLength() {
        return new TextInputConfig(
                OptionalInt.empty(),
                masking,
                appearance,
                completion,
                validator,
                submitPolicy,
                copyEnabled,
                copyKey,
                pasteKey);
    }

    /**
     * The same policy with a different masking policy.
     *
     * @param value how the draft is displayed and copied
     * @return the new configuration
     */
    public TextInputConfig withMasking(EditorConfig.Masking value) {
        return new TextInputConfig(
                maxLength, value, appearance, completion, validator, submitPolicy, copyEnabled, copyKey, pasteKey);
    }

    /**
     * The same policy with a different presentation.
     *
     * @param value what the field paints around its draft; null paints none
     * @return the new configuration
     */
    public TextInputConfig withAppearance(Appearance value) {
        return new TextInputConfig(
                maxLength,
                masking,
                value == null ? Appearance.plain() : value,
                completion,
                validator,
                submitPolicy,
                copyEnabled,
                copyKey,
                pasteKey);
    }

    /**
     * The same policy with a different completion policy.
     *
     * @param value the completion policy; null turns completion off
     * @return the new configuration
     */
    public TextInputConfig withCompletion(Completion value) {
        return new TextInputConfig(
                maxLength,
                masking,
                appearance,
                value == null ? Completion.disabled() : value,
                validator,
                submitPolicy,
                copyEnabled,
                copyKey,
                pasteKey);
    }

    /**
     * The same policy with a different validator.
     *
     * @param value the validation policy; null accepts every draft
     * @return the new configuration
     */
    public TextInputConfig withValidator(Validator value) {
        return new TextInputConfig(
                maxLength,
                masking,
                appearance,
                completion,
                value == null ? Validator.valid() : value,
                submitPolicy,
                copyEnabled,
                copyKey,
                pasteKey);
    }

    /**
     * The same policy with a different submission policy.
     *
     * @param value what submission does with a refused draft; null refuses it
     * @return the new configuration
     */
    public TextInputConfig withSubmitPolicy(SubmitPolicy value) {
        return new TextInputConfig(
                maxLength,
                masking,
                appearance,
                completion,
                validator,
                value == null ? SubmitPolicy.VALID_ONLY : value,
                copyEnabled,
                copyKey,
                pasteKey);
    }

    /**
     * The same policy with copying allowed or refused.
     *
     * @param value whether a visible draft may be copied
     * @return the new configuration
     */
    public TextInputConfig withCopyEnabled(boolean value) {
        return new TextInputConfig(
                maxLength, masking, appearance, completion, validator, submitPolicy, value, copyKey, pasteKey);
    }

    /**
     * The same policy with different copy and paste gestures.
     *
     * @param copy the key gesture that copies
     * @param paste the key gesture that requests a paste
     * @return the new configuration
     */
    public TextInputConfig withKeys(Key copy, Key paste) {
        return new TextInputConfig(
                maxLength, masking, appearance, completion, validator, submitPolicy, copyEnabled, copy, paste);
    }

    /**
     * The same policy at a different width.
     *
     * @param columns the columns the field paints in; zero uses the space it is given
     * @return the new configuration
     */
    public TextInputConfig withWidth(int columns) {
        return withAppearance(appearance.withWidth(columns));
    }

    /**
     * The same policy with a different prompt.
     *
     * @param value the label painted before the draft
     * @param style the style applied to it
     * @return the new configuration
     */
    public TextInputConfig withPrompt(String value, Style style) {
        return withAppearance(appearance.withPrompt(value, style));
    }

    /**
     * The same policy with a different placeholder.
     *
     * @param value the text painted in place of an empty draft
     * @param style the style applied to it
     * @return the new configuration
     */
    public TextInputConfig withPlaceholder(String value, Style style) {
        return withAppearance(appearance.withPlaceholder(value, style));
    }

    /**
     * The same policy with different draft styles.
     *
     * @param text the style applied to the draft
     * @param selection the style applied to the selection
     * @param suggestion the style applied to the ghost suffix
     * @return the new configuration
     */
    public TextInputConfig withStyles(Style text, Style selection, Style suggestion) {
        return withAppearance(appearance.withStyles(text, selection, suggestion));
    }

    /**
     * The same policy with a different caret.
     *
     * @param value how the caret is drawn; null keeps the painted block
     * @return the new configuration
     */
    public TextInputConfig withCursor(Editor.CursorPolicy value) {
        return withAppearance(appearance.withCursor(value));
    }

    /**
     * Whether copying is allowed under the masking policy.
     *
     * @return whether copying is permitted
     */
    public boolean canCopy() {
        return copyEnabled && masking == EditorConfig.Masking.NONE;
    }
}

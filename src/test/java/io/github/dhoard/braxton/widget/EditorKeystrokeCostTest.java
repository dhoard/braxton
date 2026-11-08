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

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.testing.AllocationProbe;
import org.junit.jupiter.api.Test;

/**
 * Typing at the end of a large draft asks where the clusters start and end at exactly the position
 * no gap test can answer: the end of the text. Every one of those questions used to build a break
 * iterator and copy the whole draft, so the most common caret position was the most expensive one.
 */
class EditorKeystrokeCostTest {

    private static final int DRAFT_LENGTH = 4_096;

    private static final String DRAFT =
            "the quick brown fox jumps over the lazy dog 0123456789 ".repeat(75).substring(0, DRAFT_LENGTH);

    @Test
    void oneInsertAtTheEndOfALargeDraftDoesNotScanTheDraft() {
        Editor editor = new Editor(Editor.Config.defaults());
        Editor.Model state = Editor.Model.of(DRAFT);
        assertThat(AllocationProbe.bytes(() -> editor.update(state, new Editor.Msg.Insert("x"))))
                .as("an insert at the end of a draft asks only about the end of the text, which "
                        + "must answer without building a break iterator")
                .isLessThan(64_000);
    }

    @Test
    void anInsertAtTheEndOfALargeDraftLandsAfterTheLastCharacter() {
        Editor.Model next = new Editor(Editor.Config.defaults())
                .update(Editor.Model.of(DRAFT), new Editor.Msg.Insert("x"))
                .model();
        assertThat(next.text()).isEqualTo(DRAFT + "x");
        assertThat(next.caret()).isEqualTo(DRAFT_LENGTH + 1);
        assertThat(next.anchor()).isEqualTo(next.caret());
        assertThat(next.hasSelection()).isFalse();
    }

    @Test
    void aBackspaceAtTheEndOfALargeDraftLandsBeforeTheLastCharacter() {
        Editor.Model next = new Editor(Editor.Config.defaults())
                .update(Editor.Model.of(DRAFT), new Editor.Msg.Backspace())
                .model();
        assertThat(next.text()).isEqualTo(DRAFT.substring(0, DRAFT_LENGTH - 1));
        assertThat(next.caret()).isEqualTo(DRAFT_LENGTH - 1);
    }
}

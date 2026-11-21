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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Editor and TextInput expose the shared text and completion engines.
 */
class WidgetEngineMigrationTest {

    @Test
    void editorStateIsReadableAsATextBuffer() {
        assertThat(Editor.buffer(Editor.Model.of("one\ntwo")).lines()).containsExactly("one", "two");
    }

    @Test
    void textInputUsesTheSharedCompletionModes() {
        List<String> candidates = List.of("alpha", "beta-alpha", "alphabet");
        TextInput.Model model = new TextInput.Model(
                Editor.Model.of("alpha"), TextInput.Validation.ok(), new TextInput.Suggestions(0, candidates, 0), 0);

        TextInputConfig prefix = TextInputConfig.defaults().withCompletion(TextInput.Completion.prefix());
        assertThat(TextInput.matchedCandidates(model, prefix)).containsExactly("alpha", "alphabet");
        TextInputConfig substring = TextInputConfig.defaults().withCompletion(TextInput.Completion.substring());
        assertThat(TextInput.matchedCandidates(model, substring)).containsExactly("alpha", "alphabet", "beta-alpha");
    }
}

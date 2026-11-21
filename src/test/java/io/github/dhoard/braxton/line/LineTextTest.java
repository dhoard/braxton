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

package io.github.dhoard.braxton.line;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The one-row text arithmetic the editor, the renderer and the reader share.
 */
class LineTextTest {

    @Test
    void oneRowKeepsEveryCharacterACellCanCarry() {
        assertThat(LineText.singleRow(null)).isEmpty();
        assertThat(LineText.singleRow("")).isEmpty();
        assertThat(LineText.singleRow("a\r\nb")).isEqualTo("a b");
        assertThat(LineText.singleRow("a\rb")).isEqualTo("a b");
        assertThat(LineText.singleRow("a\tb")).isEqualTo("a b");
        assertThat(LineText.singleRow("a\u0000\u001bb")).isEqualTo("a  b");
        // Trailing and leading blanks are the caller's text, not a paste artifact.
        assertThat(LineText.singleRow("  > ")).isEqualTo("  > ");
    }

    @Test
    void aPasteLosesItsArtifactsAndKeepsItsText() {
        assertThat(LineText.pasted("one\r\ntwo three\u0007")).isEqualTo("one two three");
        // A pasted escape sequence is removed whole, so its parameters cannot reach a cell.
        assertThat(LineText.pasted("a\u001b[31mred\u001b[0m b")).isEqualTo("ared b");
        assertThat(LineText.pasted(null)).isEmpty();
    }

    @Test
    void clustersAreMeasuredInCellsAndFoundByBoundary() {
        assertThat(LineText.columns("abc")).isEqualTo(3);
        assertThat(LineText.columns("\u6f22b")).isEqualTo(3);
        assertThat(LineText.columnsBefore("abc", 2)).isEqualTo(2);
        assertThat(LineText.columnsBefore(null, 2)).isZero();
        assertThat(LineText.columnsBefore("abc", -1)).isZero();
        assertThat(LineText.columnsBefore("abc", 99)).isEqualTo(3);
        assertThat(LineText.previous("abc", 2)).isEqualTo(1);
        assertThat(LineText.next("abc", 1)).isEqualTo(2);
        assertThat(LineText.boundaryBefore("xe\u0301y", 2)).isEqualTo(1);
    }

    @Test
    void aClusterIsClassifiedByItsBase() {
        assertThat(LineText.kind("a")).isEqualTo(LineText.WORD);
        assertThat(LineText.kind("7")).isEqualTo(LineText.WORD);
        assertThat(LineText.kind("_")).isEqualTo(LineText.WORD);
        assertThat(LineText.kind(" ")).isEqualTo(LineText.BLANK);
        assertThat(LineText.kind("\u00a0")).isEqualTo(LineText.BLANK);
        assertThat(LineText.kind(".")).isEqualTo(LineText.PUNCTUATION);
        assertThat(LineText.kind("")).isEqualTo(LineText.PUNCTUATION);
        assertThat(LineText.kind(null)).isEqualTo(LineText.PUNCTUATION);
    }

    @Test
    void theWordMotionsStopWhereTheClassesChange() {
        assertThat(LineText.wordForward("one.two", 0)).isEqualTo(3);
        assertThat(LineText.wordForward("one.two", 3)).isEqualTo(4);
        assertThat(LineText.wordForward("a  b", 0)).isEqualTo(3);
        assertThat(LineText.wordForward("a  ", 0)).isEqualTo(3);
        assertThat(LineText.wordForward("abc", 99)).isEqualTo(3);

        assertThat(LineText.wordBack("one.two", 7)).isEqualTo(4);
        assertThat(LineText.wordBack("a  b", 4)).isEqualTo(3);
        assertThat(LineText.wordBack("  a", 3)).isEqualTo(2);
        assertThat(LineText.wordBack("   ", 3)).isZero();
        assertThat(LineText.wordBack("abc", -3)).isZero();

        assertThat(LineText.wordEnd("one.two", 0)).isEqualTo(3);
        assertThat(LineText.wordEnd("one two", 4)).isEqualTo(7);
        assertThat(LineText.wordEnd("a   ", 1)).isEqualTo(4);
        assertThat(LineText.wordEnd("abc", 99)).isEqualTo(3);
    }

    @Test
    void theClusterAccessorsAnswerNothingOutsideTheText() {
        assertThat(LineText.clusterAt("abc", 0)).isEqualTo("a");
        assertThat(LineText.clusterAt("abc", 2)).isEqualTo("c");
        assertThat(LineText.clusterAt("abc", 3)).isEmpty();
        assertThat(LineText.clusterAt("abc", -1)).isEmpty();
        assertThat(LineText.clusterBefore("abc", 3)).isEqualTo("c");
        assertThat(LineText.clusterBefore("abc", 0)).isEmpty();
        assertThat(LineText.clusterBefore("abc", 4)).isEmpty();
    }
}

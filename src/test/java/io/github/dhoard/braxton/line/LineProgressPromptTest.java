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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.style.ColorProfile;
import io.github.dhoard.braxton.terminal.TerminalException;
import io.github.dhoard.braxton.terminal.TerminalSession;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/**
 * The progress prompt, driven over a stream-backed session: the row it paints, the frames it writes
 * when the count moves, and what it leaves on screen when it closes.
 */
class LineProgressPromptTest {

    private static final Size SIZE = new Size(24, 60);

    private static final String FILLED = "\u2588";

    private static final String EMPTY = "\u2591";

    private static final String RESTORE =
            AnsiEncoder.RESET + AnsiEncoder.SHOW_CURSOR + AnsiEncoder.DISABLE_BRACKETED_PASTE;

    @Test
    void openPaintsTheNameTheCountAndThePercentage() throws Exception {
        try (Harness run = harness("Installing packages", 6, SIZE)) {
            assertThat(run.prompt().label()).isEqualTo("Installing packages");
            assertThat(run.prompt().total()).isEqualTo(6);
            assertThat(run.prompt().completed()).isZero();
            assertThat(plain(run.written().toString())).contains("Installing packages");
            assertThat(plain(run.written().toString())).contains("0/6");
            assertThat(plain(run.written().toString())).contains("0%");
            assertThat(plain(run.written().toString())).contains(EMPTY);
        }
    }

    @Test
    void theWholeRowIsTheNameABarOfTheColumnsLeftTheCountAndThePercentage() {
        assertThat(ProgressPrompt.text("pkg", 0, 4, 30))
                .isEqualTo("pkg  " + EMPTY.repeat(30 - (3 + 3 + 10)) + "  0/4" + "    0%");
        assertThat(ProgressPrompt.text("pkg", 4, 4, 30))
                .isEqualTo("pkg  " + FILLED.repeat(30 - (3 + 3 + 10)) + "  4/4" + "  100%");
        assertThat(ProgressPrompt.text("pkg", 2, 4, 30)).startsWith("pkg  " + FILLED.repeat(7) + EMPTY.repeat(7));
    }

    @Test
    void thePercentageRoundsToTheStepsTheWorkHasReached() {
        assertThat(ProgressPrompt.text("x", 0, 6, 40)).contains("  0%");
        assertThat(ProgressPrompt.text("x", 1, 6, 40)).contains(" 17%");
        assertThat(ProgressPrompt.text("x", 3, 6, 40)).contains(" 50%");
        assertThat(ProgressPrompt.text("x", 5, 6, 40)).contains(" 83%");
        assertThat(ProgressPrompt.text("x", 6, 6, 40)).contains("100%");
    }

    @Test
    void stepRepaintsTheRowItChanged() throws Exception {
        try (Harness run = harness("Installing packages", 6, SIZE)) {
            run.prompt().step();
            assertThat(run.prompt().completed()).isEqualTo(1);
            assertThat(plain(run.written().toString())).contains("1/6").contains("17%");

            run.prompt().step();
            assertThat(plain(run.written().toString())).contains("2/6").contains("33%");
        }
    }

    @Test
    void aCountThatChangesNothingWritesNothing() throws Exception {
        try (Harness run = harness("Installing packages", 6, SIZE)) {
            int painted = run.written().toString().length();
            run.prompt().update(0);
            assertThat(run.written().toString().length())
                    .as("the count did not change")
                    .isEqualTo(painted);

            run.prompt().update(6);
            int complete = run.written().toString().length();
            run.prompt().update(6);
            assertThat(run.written().toString().length())
                    .as("the row did not change")
                    .isEqualTo(complete);
            assertThat(plain(run.written().toString())).contains("6/6").contains("100%");
        }
    }

    @Test
    void aCountOutsideTheBoundIsClamped() throws Exception {
        try (Harness run = harness("Installing packages", 6, SIZE)) {
            run.prompt().update(99);
            assertThat(run.prompt().completed()).isEqualTo(6);
            assertThat(plain(run.written().toString())).contains("6/6").contains("100%");
            assertThat(ProgressPrompt.text("Installing packages", 6, 6, 60))
                    .as("a full bar has no empty cell")
                    .doesNotContain(EMPTY);

            run.prompt().update(-3);
            assertThat(run.prompt().completed()).isZero();
            assertThat(plain(run.written().toString())).contains("0/6").contains("0%");
        }
    }

    @Test
    void closingLeavesTheFinalFrameOnScreenAndGivesTheTerminalBack() throws Exception {
        Harness run = harness("Installing packages", 6, SIZE);
        run.prompt().update(6);
        run.prompt().close();

        String written = run.written().toString();
        assertThat(plain(written)).contains("6/6").contains("100%");
        // The final frame stays on screen with the cursor on the line below it, then the cursor and
        // the paste setting the region turned on are given back.
        assertThat(written).endsWith("\r\r\n" + RESTORE);
        run.session().close();
    }

    @Test
    void closingTwiceWritesOnce() throws Exception {
        Harness run = harness("Installing packages", 6, SIZE);
        run.prompt().close();
        int closed = run.written().toString().length();
        run.prompt().close();

        assertThat(run.written().toString().length()).isEqualTo(closed);
        run.session().close();
    }

    @Test
    void anUpdateAfterTheCloseIsRejected() throws Exception {
        Harness run = harness("Installing packages", 6, SIZE);
        run.prompt().close();

        assertThatThrownBy(() -> run.prompt().update(3)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> run.prompt().step()).isInstanceOf(IllegalStateException.class);
        run.session().close();
    }

    @Test
    void aTotalBelowOneIsRejected() {
        assertThatThrownBy(() -> harness("Installing packages", 0, SIZE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProgressPrompt.text("x", 0, -1, 40)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRowTooNarrowForABarKeepsItsCountAndPercentage() throws Exception {
        assertThat(ProgressPrompt.text("pkg", 2, 4, 16)).isEqualTo("pkg  2/4   50%");

        try (Harness run = harness("pkg", 4, new Size(24, 16))) {
            run.prompt().update(2);
            assertThat(plain(run.written().toString()))
                    .as("no bar cell was painted")
                    .doesNotContain(EMPTY);
            assertThat(plain(run.written().toString())).contains("pkg  2/4   50%");
        }
    }

    @Test
    void aLabelIsOneRow() throws Exception {
        try (Harness run = harness("Installing\npackages", 2, SIZE)) {
            assertThat(run.prompt().label()).isEqualTo("Installing packages");
            assertThat(plain(run.written().toString())).contains("Installing packages");
        }
    }

    private record Harness(TerminalSession session, ProgressPrompt prompt, StringWriter written)
            implements AutoCloseable {

        @Override
        public void close() throws TerminalException {
            try {
                prompt.close();
            } finally {
                session.close();
            }
        }
    }

    /**
     * A prompt over a stream-backed session, painted at a fixed size.
     */
    private static Harness harness(String label, long total, Size size) throws TerminalException {
        StringWriter written = new StringWriter();
        TerminalSession session = TerminalSession.of(new StringReader(""), written, size);
        ProgressPrompt prompt;
        try {
            prompt = ProgressPrompt.open(LineReader.of(session).withColorProfile(ColorProfile.NONE), label, total);
        } catch (TerminalException | RuntimeException failure) {
            session.close();
            throw failure;
        }
        return new Harness(session, prompt, written);
    }

    /**
     * The text a terminal would show: every CSI sequence removed, so styling cannot hide a cell.
     */
    private static String plain(String written) {
        return written.replaceAll("\u001b\\[[0-9;? ]*[A-Za-z]", "");
    }
}

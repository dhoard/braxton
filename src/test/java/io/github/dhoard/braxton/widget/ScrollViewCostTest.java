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

import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.StyledText;
import io.github.dhoard.braxton.testing.ComponentDriver;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.widget.ScrollView.Msg;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What one frame costs: a fixed window over a document far larger than it. The assertions count the
 * rows the pane reads, not seconds, so they are deterministic and pin the bound rather than the
 * machine: a frame reads the window it paints, a gesture reads no row at all, and streaming appends
 * never restart the document.
 */
class ScrollViewCostTest {

    private static final int DOCUMENT = 100_000;
    private static final int WINDOW = 24;

    /** What a snapshot was asked for: one row count and the rows themselves. */
    private static final class Counter {
        private int countCalls;
        private int fetches;
        private int lowest = Integer.MAX_VALUE;
        private int highest = -1;

        void reset() {
            countCalls = 0;
            fetches = 0;
            lowest = Integer.MAX_VALUE;
            highest = -1;
        }
    }

    /**
     * A pre-indexed document: one row count and one row per index, both constant time, sharing
     * {@code rowInstance} so a fetch cannot hide an allocation. Each instance is one content
     * version, which is what an append publishes.
     */
    private static final class Document implements ScrollView.Content {
        private static final StyledText ROW = StyledText.plain("row");

        private final Counter counter;
        private final int rows;

        Document(Counter counter, int rows) {
            this.counter = counter;
            this.rows = rows;
        }

        @Override
        public int lineCount() {
            counter.countCalls++;
            return rows;
        }

        @Override
        public StyledText line(int index) {
            counter.fetches++;
            counter.lowest = Math.min(counter.lowest, index);
            counter.highest = Math.max(counter.highest, index);
            return ROW;
        }
    }

    private static final ViewContext CONTEXT = new ViewContext(new Viewport(WINDOW, 80));

    @Test
    void aFrameReadsOneRowPerVisibleRowWhateverTheDocumentHolds() {
        Counter counter = new Counter();
        ScrollView view = new ScrollView(new ScrollView.Config(WINDOW, false));

        for (int size : new int[] {10_000, 100_000, 1_000_000}) {
            for (int top : new int[] {0, 1, size / 2, size - WINDOW}) {
                ScrollView.Model state = new ScrollView.Model(top, false, true, WINDOW, new Document(counter, size));
                counter.reset();

                view.view(state, CONTEXT);

                assertThat(counter.fetches)
                        .as("rows read for a window at %d", top)
                        .isEqualTo(WINDOW);
                assertThat(counter.countCalls)
                        .as("row count asked for a window at %d", top)
                        .isEqualTo(1);
                assertThat(counter.lowest).isEqualTo(top);
                assertThat(counter.highest).isEqualTo(top + WINDOW - 1);
            }
        }
    }

    @Test
    void aFollowingWindowReadsTheEndOfTheDocumentAndNothingBeforeIt() {
        Counter counter = new Counter();
        ScrollView view = new ScrollView(new ScrollView.Config(WINDOW, true));
        ScrollView.Model state = new ScrollView.Model(0, true, true, WINDOW, new Document(counter, DOCUMENT));

        assertThat(state.topLine()).isEqualTo(DOCUMENT - WINDOW);
        counter.reset();
        view.view(state, CONTEXT);

        assertThat(counter.fetches).isEqualTo(WINDOW);
        assertThat(counter.lowest).isEqualTo(DOCUMENT - WINDOW);
        assertThat(counter.highest).isEqualTo(DOCUMENT - 1);
    }

    @Test
    void aGestureReadsNoRowAndNoDocumentIsCopied() {
        Counter counter = new Counter();
        var driver = ComponentDriver.initialize(
                new ScrollView(new ScrollView.Config(WINDOW, false)), new InitContext(null, null));
        ScrollView.Content content = new Document(counter, DOCUMENT);
        driver.send(new Msg.ContentChanged(content));

        for (Msg message : List.of(
                new Msg.Down(1),
                new Msg.PageDown(),
                new Msg.PageUp(),
                new Msg.Home(),
                new Msg.End(),
                new Msg.ViewportChanged(WINDOW),
                new Msg.ContentChanged(content))) {
            counter.reset();
            driver.send(message);

            assertThat(counter.fetches).as("rows read by %s", message).isZero();
            assertThat(counter.countCalls).as("row count read by %s", message).isLessThanOrEqualTo(4);
        }
        assertThat(driver.model().topLine()).isEqualTo(DOCUMENT - WINDOW);
    }

    @Test
    void streamingAppendsKeepReadingTheSameWindow() {
        Counter counter = new Counter();
        ScrollView view = new ScrollView(new ScrollView.Config(WINDOW, false));
        var driver = ComponentDriver.initialize(view, new InitContext(null, null));
        int first = 1_000;
        driver.send(new Msg.ContentChanged(new Document(counter, DOCUMENT)));
        driver.send(new Msg.Down(first));

        int appended = 200;
        int frames = 0;
        long fetched = 0;
        for (int extra = 1; extra <= appended; extra++) {
            counter.reset();
            driver.send(new Msg.ContentChanged(new Document(counter, DOCUMENT + extra)));
            assertThat(counter.fetches)
                    .as("an append must not read the document in update")
                    .isZero();

            counter.reset();
            view.view(driver.model(), CONTEXT);
            frames++;
            fetched += counter.fetches;

            assertThat(counter.fetches).as("rows read by frame %d", extra).isEqualTo(WINDOW);
            assertThat(counter.lowest)
                    .as("a window the user scrolled back keeps its first row")
                    .isEqualTo(first);
            assertThat(counter.highest).isEqualTo(first + WINDOW - 1);
        }
        assertThat(driver.model().topLine())
                .as("appends do not move a window the user scrolled back")
                .isEqualTo(first);
        assertThat(driver.model().lineCount()).isEqualTo(DOCUMENT + appended);
        assertThat(fetched)
                .as("the whole run read the window once per frame, never the document")
                .isEqualTo((long) frames * WINDOW);
    }

    @Test
    void theViewTreeHoldsOnlyTheVisibleRows() {
        ScrollView view = new ScrollView(new ScrollView.Config(WINDOW, false));
        ScrollView.Model state = new ScrollView.Model(0, false, true, WINDOW, new Document(new Counter(), DOCUMENT));

        View<Msg> painted = view.view(state, CONTEXT);
        List<View<Msg>> rows = painted.children().get(0).children();

        assertThat(rows)
                .as("one row view per visible row, and nothing per hidden row")
                .hasSize(WINDOW);
        assertThat(painted.children().get(0).children().get(0).children())
                .as("each row is one fixed screen row wrapping one text row")
                .hasSize(1);
    }
}

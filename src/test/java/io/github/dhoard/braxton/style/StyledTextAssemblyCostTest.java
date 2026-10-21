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

package io.github.dhoard.braxton.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.testing.AllocationProbe;

class StyledTextAssemblyCostTest {

    @Test
    void appendChainsAssembleOnceForBothIdenticalAndAlternatingStyles() {
        for (boolean alternating : List.of(false, true)) {
            StyledText[] pieces = new StyledText[4096];
            for (int i = 0; i < pieces.length; i++) {
                pieces[i] = StyledText.of("x", alternating && i % 2 == 0
                        ? Style.NONE.withBold() : Style.NONE);
            }
            assertThat(AllocationProbe.bytes(() -> assemble(pieces))).isLessThan(2_000_000);
            assertThat(assemble(pieces)).isEqualTo(StyledText.concat(pieces));
        }
    }

    private static StyledText assemble(StyledText[] pieces) {
        StyledText result = StyledText.empty();
        for (StyledText part : pieces) {
            result = result.append(part);
        }
        result.spans();
        return result;
    }

    @Test
    void allCompositionShapesPreserveTheEagerAppendSemantics() {
        List<StyledText> values = List.of(StyledText.empty(), StyledText.plain("a"),
                StyledText.of("b", Style.NONE.withBold()), StyledText.of("\u001b", Style.NONE.withBold()),
                StyledText.of("z", Style.NONE, "https://example.com"),
                StyledText.of("a", Style.NONE.withBold()).append("b").withStyle(Style.NONE));
        for (StyledText a : values) {
            for (StyledText b : values) {
                for (StyledText c : values) {
                    assertThat(a.append(b).append(c).spans())
                            .isEqualTo(eager(eager(a.spans(), b.spans()), c.spans()));
                    assertThat(a.append(b.append(c)).spans())
                            .isEqualTo(eager(a.spans(), eager(b.spans(), c.spans())));
                }
            }
        }
    }

    private static List<StyledText.Span> eager(List<StyledText.Span> left, List<StyledText.Span> right) {
        if (left.isEmpty()) {
            return right;
        }
        List<StyledText.Span> result = new ArrayList<>(left);
        for (StyledText.Span span : right) {
            StyledText.Span last = result.get(result.size() - 1);
            if (last.style().equals(span.style()) && Objects.equals(last.link(), span.link())) {
                result.set(result.size() - 1,
                        new StyledText.Span(last.text() + span.text(), last.style(), last.link()));
            } else {
                result.add(span);
            }
        }
        return result;
    }

    @Test
    void concurrentReadsPreserveTheSameImmutableSpans() throws Exception {
        StyledText value = StyledText.empty();
        for (int i = 0; i < 4096; i++) {
            value = value.append("x");
        }
        StyledText text = value;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<StyledText.Span>>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return text.spans();
                }));
            }
            start.countDown();
            for (Future<List<StyledText.Span>> result : results) {
                assertThat(result.get()).isEqualTo(StyledText.plain("x".repeat(4096)).spans());
            }
        }
    }

    @Test
    void deepCompositionsRemainReusableAndDoNotRecurseOnRead() {
        StyledText text = StyledText.plain("x");
        for (int i = 0; i < 20000; i++) {
            text = StyledText.plain("x").append(text);
        }
        assertThat(text.length()).isEqualTo(20001);
        assertThat(text.plainText()).isEqualTo("x".repeat(20001));
        assertThat(text.append("y").plainText()).endsWith("xy");
        assertThat(text.plainText()).doesNotContain("y");
    }
}

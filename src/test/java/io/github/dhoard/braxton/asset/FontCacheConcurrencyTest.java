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

package io.github.dhoard.braxton.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class FontCacheConcurrencyTest {
    @Test
    void concurrentColdLoadsShareTheCachedFontIncludingTheNullDefault() throws Exception {
        int callers = 44;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FigletFont>> results = new ArrayList<>();
        FontLoader.Family[] families = FontLoader.Family.values();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                for (int i = 0; i < callers; i++) {
                    int familyIndex = i % (families.length + 1);
                    FontLoader.Family family = familyIndex == families.length ? null : families[familyIndex];
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("load start was not released");
                        }
                        return FontLoader.load(family);
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (int i = 0; i < callers; i++) {
                int index = i % (families.length + 1);
                FontLoader.Family family = index == families.length ? FontLoader.Family.STANDARD : families[index];
                FigletFont loaded = results.get(i).get(5, TimeUnit.SECONDS);
                assertThat(loaded).isSameAs(FontLoader.load(family));
                assertThat(loaded.name()).endsWith(family.resource());
                assertThat(loaded.render("A")).hasSize(loaded.height());
            }
        }
    }
}

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

package io.github.dhoard.braxton.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.testing.ElementScreen;
import io.github.dhoard.braxton.widget.Elements;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The concise typed root API and its README line budget.
 */
class SimpleComponentTest {

    private enum Msg {
        INC
    }

    @Test
    void functionsBecomeAComponent() {
        var component = SimpleComponent.of(
                context -> 0,
                (model, msg) -> Update.next(model + 1),
                (model, context) -> Elements.text("count: " + model));

        assertThat(component.init(null).model()).isZero();
        assertThat(component.update(4, Msg.INC).model()).isEqualTo(5);
        assertThat(ElementScreen.render(component.view(4, null), 1, 20).text()).contains("count: 4");
        assertThat(component.children(0).nodes()).isEmpty();
    }

    @Test
    void readmeCounterStaysWithinTheBudget() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        int start = readme.indexOf("```java\npackage demo;", readme.indexOf("## Quick start"));
        int end = readme.indexOf("\n```", start);
        long lines = readme.substring(start, end)
                .lines()
                .filter(line -> !line.isBlank() && !line.stripLeading().startsWith("import "))
                .count();
        assertThat(lines).as("non-import, non-blank README counter lines").isLessThanOrEqualTo(25);
    }
}

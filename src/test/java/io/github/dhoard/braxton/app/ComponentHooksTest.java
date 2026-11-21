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

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.internal.runtime.TestTerminal;
import io.github.dhoard.braxton.layout.Rect;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.render.Surface;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.ui.Sizing;
import io.github.dhoard.braxton.ui.Widget;
import io.github.dhoard.braxton.widget.Elements;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A component's claims outrank the focused widget and its observations never take a gesture away.
 *
 * <p>Both hooks are the application's way of dealing with a gesture it has to act on or has to show:
 * a claim keeps a key the focused field would take, and an observation is a copy of every event that
 * reaches what a widget already did with it.</p>
 */
class ComponentHooksTest {

    /**
     * What the scene records, in the order the runtime produced it.
     */
    private static final List<String> LOG = new ArrayList<>();

    @Test
    void aClaimBeatsTheFocusedFieldAndAnObservationRecordsEveryEvent() {
        LOG.clear();

        java.util.concurrent.atomic.AtomicBoolean fed = new java.util.concurrent.atomic.AtomicBoolean();
        try (TestTerminal fixture = new TestTerminal()) {
            fixture.onOutput(text -> {
                if (text.contains("log=") && fed.compareAndSet(false, true)) {
                    fixture.feed("a");
                    fixture.feed("q");
                }
            });
            ExitResult result = TestTerminal.run(new Scene(), RuntimeOptions.defaults(), fixture);
            assertThat(result.failure()).isNull();
        }

        // "a" was consumed by the field, so the claim did not touch it; the field answered first
        // and the observation still saw it afterward.
        assertThat(LOG).containsSubsequence("field:a", "seen:a");
        // "q" is claimed, so the field never saw it and the application quit.
        assertThat(LOG).doesNotContain("field:q");
        assertThat(LOG).containsSubsequence("claimed:quit", "seen:q");
    }

    /**
     * One message the scene handles.
     */
    private sealed interface Msg {
        record Field(String text) implements Msg {}

        record Claimed(String why) implements Msg {}

        record Seen(String event) implements Msg {}
    }

    /**
     * A scene that claims "q", observes everything and shows a field that types.
     */
    private static final class Scene implements Component<String, Msg, Never> {

        @Override
        public Update<String, Msg, Never> init(InitContext context) {
            return Update.next("");
        }

        @Override
        public Update<String, Msg, Never> update(String model, Msg message) {
            return switch (message) {
                case Msg.Field field -> {
                    model = model + field.text();
                    yield Update.next(model);
                }
                case Msg.Claimed claimed -> {
                    LOG.add("claimed:" + claimed.why());
                    yield Update.next(model, Command.quit());
                }
                case Msg.Seen seen -> Update.next(model);
            };
        }

        @Override
        public List<Binding<Msg>> claims(String model) {
            return List.of(Binding.of("q", new Msg.Claimed("quit")));
        }

        @Override
        public List<Msg> observed(InputEvent event) {
            String name = event instanceof InputEvent.KeyInput key
                    ? key.text()
                    : event.getClass().getSimpleName();
            LOG.add("seen:" + name);
            return List.of(new Msg.Seen(name));
        }

        @Override
        public Element<Msg> view(String model, ViewContext context) {
            return Elements.stack(
                    Elements.text("log=" + model),
                    new Element.Leaf<String, Msg>(new Field(), model, Sizing.measured()));
        }
    }

    /**
     * A field that owns focus and types printable text.
     */
    private static final class Field implements Widget<String, Msg> {

        @Override
        public Size measure(Size max, String model) {
            return new Size(0, 0);
        }

        @Override
        public void paint(Surface surface, Rect bounds, String model) {}

        @Override
        public boolean canFocus(String model) {
            return true;
        }

        @Override
        public boolean focused(String model) {
            return true;
        }

        @Override
        public void onKey(InputEvent.KeyInput key, String model, Consumer<Msg> emit) {
            if (key.text().isEmpty()) {
                return;
            }
            LOG.add("field:" + key.text());
            emit.accept(new Msg.Field(key.text()));
        }
    }
}

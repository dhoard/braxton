package io.github.dhoard.braxton.internal.input;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

/** The documented routing order holds for one tree. */
class InputRouterPropertyTest {

    @Test
    void captureRunsBeforeFocusAndBubbleAfterIt() {
        View<String> tree = Views.keyed(List.of(Binding.of("k", "outer-capture")), event -> {
            throw new AssertionError("interceptor should not run for a captured key");
        }, Views.keyed(List.of(Binding.of("k", "inner-capture")), event -> java.util.Optional.of("intercept"),
                Views.focus(ElementId.of("f"), true, List.of(Binding.of("k", "focus")),
                        event -> java.util.Optional.of("handler"), Views.text("x")),
                List.of(Binding.of("k", "inner-bubble"))), List.of(Binding.of("k", "outer-bubble")));
        assertThat(InputRouter.route(tree, InputEvent.KeyInput.of(Key.of("k"))))
                .as("outermost capture wins").contains("outer-capture");
        View<String> noCaptures = Views.keyed(List.of(), event -> java.util.Optional.of("intercept"),
                Views.focus(ElementId.of("f"), true, List.of(Binding.of("k", "focus")),
                        event -> java.util.Optional.of("handler"), Views.text("x")),
                List.of(Binding.of("k", "bubble")));
        assertThat(InputRouter.route(noCaptures, InputEvent.KeyInput.of(Key.of("k"))))
                .as("an interceptor runs before the focused element").contains("intercept");
        View<String> handlerFirst = Views.keyed(List.of(), View.InputHandler.none(),
                Views.focus(ElementId.of("f"), true, List.of(), event -> java.util.Optional.of("handler"),
                        Views.text("x")), List.of(Binding.of("k", "bubble")));
        assertThat(InputRouter.route(handlerFirst, InputEvent.KeyInput.of(Key.of("k"))))
                .as("the focused handler runs before bubble bindings").contains("handler");
    }
}

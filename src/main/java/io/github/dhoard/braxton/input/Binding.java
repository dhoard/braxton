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

package io.github.dhoard.braxton.input;

/**
 * Binds one key gesture to one application message value.
 *
 * @param <Msg> the message type of the owning component
 * @param key the key gesture this binding matches
 * @param message the message value it produces
 */
public record Binding<Msg>(Key key, Msg message) {

    /**
     * Creates a {@code Binding} value.
     *
     * @param key the key gesture this binding matches
     * @param message the message value it produces
     */
    public Binding {
        if (key == null) {
            throw new IllegalArgumentException("binding key is required");
        }
        if (message == null) {
            // Routing only consumes a binding that yields a message value, so a null message would
            // occupy the gesture (rejecting a real binding for it) and never fire.
            throw new IllegalArgumentException("binding message is required");
        }
    }

    /**
     * Creates a {@code Binding} value.
     *
     * @param <Msg> the message type of the owning component
     * @param spec the key or style specification
     * @param message the message value it produces
     * @return the gesture-to-message binding
     */
    public static <Msg> Binding<Msg> of(String spec, Msg message) {
        return new Binding<>(Key.parse(spec), message);
    }

    /**
     * Tests whether the gesture equals this binding, including modifiers.
     *
     * @param candidate the candidate normalized key gesture
     * @return {@code true} if the gesture equals this binding, including modifiers; otherwise {@code false}
     */
    public boolean matches(Key candidate) {
        return key.equals(candidate);
    }

    /**
     * This binding with its message lifted into a parent's message type.
     *
     * <p>A mount maps a child's answer into the parent's type everywhere else, and a claim or a
     * binding is no different: the gesture stays the child's, the message becomes the parent's.</p>
     *
     * @param <Msg2> the parent message type
     * @param mapper maps the child message to the parent's message; required
     * @return the lifted binding
     */
    public <Msg2> Binding<Msg2> map(java.util.function.Function<? super Msg, ? extends Msg2> mapper) {
        java.util.Objects.requireNonNull(mapper, "mapper");
        return new Binding<>(key, mapper.apply(message));
    }
}

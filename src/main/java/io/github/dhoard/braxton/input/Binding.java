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

package io.github.dhoard.braxton.input;

/** Binds one key gesture to one application message value. */
public record Binding<Msg>(Key key, Msg message) {

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

    public static <Msg> Binding<Msg> of(String spec, Msg message) {
        return new Binding<>(Key.parse(spec), message);
    }

    public boolean matches(Key candidate) {
        return key.equals(candidate);
    }
}

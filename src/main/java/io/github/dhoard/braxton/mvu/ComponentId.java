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

package io.github.dhoard.braxton.mvu;

import java.io.Serializable;
import java.util.Objects;

/**
 * One stable segment. Domain key conversion is explicit, never normalized.
 *
 * <p>A segment is nonblank, at most {@value #MAX_LENGTH} UTF-16 units, and contains no {@code /},
 * control character, {@code .} or {@code ..}. Rejecting rather than rewriting an invalid key is
 * what keeps two distinct domain keys from collapsing onto one component.</p>
 *
 * <p>Segments are serializable because they are part of the immutable identity carried by a
 * serializable {@link ComponentFailure}; they are values, never a persisted model format.</p>
 *
 * @param value the validated local identity segment
 */
public record ComponentId(String value) implements Serializable {
    public static final int MAX_LENGTH = 128;

    public ComponentId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()
                || value.length() > MAX_LENGTH
                || value.equals(".")
                || value.equals("..")
                || value.indexOf('/') >= 0
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid component/local identity segment");
        }
    }

    public static ComponentId of(String value) {
        return new ComponentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}

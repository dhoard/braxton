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

/**
 * What a bundled widget's reducer answers: the next state, and the intent it wants the owning
 * application to carry out, if any.
 *
 * <p>This is the shape {@link WidgetUpdate#settled} needs to turn a reduction into an {@code
 * Update}. A widget's own {@code Result} record already has these accessors, so implementing this
 * interface costs it nothing and keeps each reducer's type specific to its model.</p>
 *
 * @param <M> the widget model
 * @param <O> the widget's intent type
 */
interface WidgetResult<M, O> {

    /** The state the reduction produced. */
    M state();

    /** The intent to hand the application, or {@code null} when the reduction only changed state. */
    O intent();
}

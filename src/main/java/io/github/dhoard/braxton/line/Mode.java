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

package io.github.dhoard.braxton.line;

/**
 * The key mode a {@link LineEditor} is edited with.
 *
 * <p>A mode is the caller's choice and stays what it was chosen as; a Vi editor's
 * {@link LineEditor.State insert and normal states} are part of its editor, not of its mode. Emacs is
 * always inserting, which is why {@link LineEditor#keys(Mode, LineEditor.State)} answers the Emacs
 * map whatever state it is asked about.</p>
 */
public enum Mode {

    /**
     * Emacs bindings: control and alt chords over one always-inserting editor.
     */
    EMACS,

    /**
     * Vi bindings: an inserting state in which text is typed and a normal state in which keys are commands.
     */
    VI
}

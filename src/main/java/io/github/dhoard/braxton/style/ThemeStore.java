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

package io.github.dhoard.braxton.style;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A live theme holder: switching repaints without touching application or widget models.
 */
public final class ThemeStore {

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Theme theme;

    /**
     * Starts with the dark theme.
     */
    public ThemeStore() {
        this(Themes.DARK);
    }

    /**
     * Creates a {@code ThemeStore} value.
     *
     * @param initial the initial value
     */
    public ThemeStore(Theme initial) {
        theme = Objects.requireNonNull(initial, "theme");
    }

    /**
     * Returns the currently installed immutable theme.
     *
     * @return the currently installed immutable theme
     */
    public Theme get() {
        return theme;
    }

    /**
     * Switches theme and notifies listeners exactly once.
     *
     * @param next the next theme
     */
    public void set(Theme next) {
        Theme value = Objects.requireNonNull(next, "theme");
        if (theme == value) return;
        theme = value;
        for (Runnable listener : listeners) listener.run();
    }

    /**
     * Adds a repaint listener; the returned runnable removes it.
     *
     * @param listener the theme-change listener
     * @return the action that removes this listener
     */
    public Runnable onChange(Runnable listener) {
        Runnable value = Objects.requireNonNull(listener, "listener");
        listeners.add(value);
        return () -> listeners.remove(value);
    }
}

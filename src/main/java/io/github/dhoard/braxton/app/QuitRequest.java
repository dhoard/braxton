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

/**
 * A graceful quit request, as a {@link MessageFilter} sees it.
 *
 * <p>Quitting is a lifecycle request rather than an application message: the program asks for it with
 * {@code Command.quit()}, and a host asks for it with {@link ProgramHandle#quit()}. Both are offered
 * to the filter as this value before the run ends, so an application that guards its exit - a
 * confirmation prompt, an unsaved-changes question - can decline the request. A filter accepts the
 * request by returning anything but {@code null} and declines it by returning {@code null}.</p>
 *
 * <p>Forced termination is not a request and is never offered to the filter: {@link
 * ProgramHandle#interrupt()} ends the run whatever the filter would have answered.</p>
 *
 * @param status the exit status the run would report
 */
public record QuitRequest(int status) {}

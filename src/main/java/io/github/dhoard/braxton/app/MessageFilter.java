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
 * An optional filter over every message a session is about to deliver to its program.
 *
 * <p>A filter runs on the runtime thread, once for each application message, after the message has
 * been decoded or produced - routed input, a command's result, a source's emission, a terminal
 * query's answer, a host's {@link ProgramHandle#send} - and before the root component's
 * {@code update} sees it. Input decoding, routing and every session lifecycle event stay outside
 * it: a filter never sees raw input, a resize, a terminal reply, or the runtime's own cleanup.</p>
 *
 * <p>The return value decides what happens to the message:</p>
 *
 * <ul>
 *   <li>the message itself: delivered unchanged;</li>
 *   <li>another message of the program's own type: delivered instead, so a filter can rewrite what
 *       the program sees;</li>
 *   <li>{@code null}: dropped, and the program never learns it existed.</li>
 * </ul>
 *
 * <p>A filter also sees {@link QuitRequest} values, which are how a graceful quit is offered to it.
 * Returning {@code null} for a quit request vetoes it and the session keeps running; returning
 * anything else accepts it and the run ends normally. A forced termination never passes through the
 * filter.</p>
 *
 * <p>Two rules keep the filter from changing the runtime's contract. It must not call back into the
 * session - no {@code send}, {@code quit}, {@code update} or terminal access - because it runs inside
 * the turn that is delivering the message; and it must not block, because it runs on the thread that
 * owns the model. A filter that throws ends the run as a failure rather than silently delivering or
 * dropping the message: it is application code, and its bug is the application's to see.</p>
 *
 * <p>The filter type itself is a plain function. An application may keep its own state in a filter
 * object - a confirmation guard is the usual case - but that state is outside the model, so nothing
 * in the runtime reduces, snapshots or replays it.</p>
 */
@FunctionalInterface
public interface MessageFilter {

    /**
     * Decides what happens to one message or quit request.
     *
     * @param message the application message, or a {@link QuitRequest}; never null
     * @return the message to deliver, or null to drop it
     */
    Object apply(Object message);
}

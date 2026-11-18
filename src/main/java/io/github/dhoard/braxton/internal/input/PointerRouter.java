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

package io.github.dhoard.braxton.internal.input;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.PointerButton;
import io.github.dhoard.braxton.input.PointerEvent;
import io.github.dhoard.braxton.internal.render.LayoutEngine;
import io.github.dhoard.braxton.layout.Size;
import io.github.dhoard.braxton.view.ElementId;
import io.github.dhoard.braxton.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Hit testing and pointer routing against the frame that is actually on screen.
 *
 * <p>A pointer report names a cell, and a cell only means something against a painted frame: which
 * element is under it is decided by the regions the last layout declared, taken in reverse paint
 * order so the element painted last wins. The report is then translated into that element's own
 * coordinates and offered to it. Nothing above the decoder counts cells, and nothing below the
 * router knows where it was painted.</p>
 *
 * <p>Three pieces of state belong to the runtime rather than to a view tree:</p>
 *
 * <ul>
 *   <li><b>Capture.</b> A press inside a region captures it: every motion and the release that
 *       follow are delivered to that element even when the pointer leaves its rectangle, which is
 *       what makes dragging work. The capture ends at the release, at the unmount of its element, or
 *       as soon as a modal makes its region unreachable - a gesture never crosses into, or out of, a
 *       modal layer.</li>
 *   <li><b>The double-click window.</b> A press that repeats the previous one at the same cell
 *       within {@link #DOUBLE_CLICK_NANOS} is reported as the second click of one gesture, so an
 *       element can select a word or open an entry without counting time itself.</li>
 *   <li><b>Wheel compatibility.</b> A wheel report no element consumed becomes the wheel key it used
 *       to be, so an application that only binds wheel keys keeps scrolling. It is delivered once,
 *       through exactly one of the two paths.</li>
 * </ul>
 *
 * <p>Ancestor interceptors observe every report on the way to its target, before the target's own
 * handler runs, and each one may report a message of its own. Unlike a key event, an observer does
 * not stop a report: where the pointer landed is decided by geometry, so a report always reaches the
 * element under it. An application that logs pointer input therefore still gets the element's action,
 * and never has to re-implement hit testing to keep it. The messages a report produces are the
 * observers' in paint order, then the target's.</p>
 */
public final class PointerRouter {

    /** How long a press may follow the previous one at the same cell and still be a double click. */
    public static final long DOUBLE_CLICK_NANOS = TimeUnit.MILLISECONDS.toNanos(400L);

    private ElementId captured;
    private long lastPressAt;
    private int lastPressRow = -1;
    private int lastPressColumn = -1;
    private PointerButton lastPressButton = PointerButton.NONE;

    /**
     * Routes one pointer report against the committed frame, returning the message of the first
     * consumer.
     *
     * @param root the view the frame was painted from
     * @param input the report
     * @param regions the focus regions that frame declared, in paint order
     * @param <Msg> the root message type
     * @return the message the report produced, if any
     */
    public <Msg> List<Msg> route(View<Msg> root, InputEvent.PointerInput input, List<LayoutEngine.Region> regions) {
        if (root == null || input == null) {
            return List.of();
        }
        List<LayoutEngine.Region> frame = regions == null ? List.of() : regions;
        PointerEvent event = input.pointer();
        // The click count is decided before the target, so an element is handed one report that
        // already says whether it is the second press of a gesture.
        if (event.isPress()) {
            // Two clicks, never three: a gesture beyond a double click selects the same thing a
            // double click does, so counting past it would name a distinction no element can use.
            event = rememberPress(event.withClickCount(repeats(event) ? 2 : 1));
        }
        // A release still belongs to the element the press captured, so the capture is read here
        // and released only once the report has been routed.
        LayoutEngine.Region target = target(event, frame);
        if (event.isPress()) {
            captured = target == null ? null : target.id();
        }
        try {
            return deliver(root, event, target);
        } finally {
            if (event.isRelease()) {
                captured = null;
            }
        }
    }

    /** Routes one report to the observers on the way, then to its target, or to the legacy key. */
    private <Msg> List<Msg> deliver(View<Msg> root, PointerEvent event, LayoutEngine.Region target) {
        // The observers see the report as the terminal reported it, before it is translated: an
        // application that logs pointer input wants the cell the user pointed at, not that cell
        // relative to a widget it does not own.
        List<View<Msg>> path = InputRouter.observerPath(root, target == null ? null : target.id());
        List<Msg> produced = new ArrayList<>(2);
        InputEvent report = new InputEvent.PointerInput(event);
        produced.addAll(InputRouter.observe(path, report));
        View<Msg> node = path.get(path.size() - 1);
        if (target != null
                && node instanceof View.Focused<Msg> focused
                && focused.id().equals(target.id())) {
            offer(focused, event, target).ifPresent(produced::add);
        } else if (target != null) {
            // The frame and the tree disagree: the region names an element the current tree no
            // longer declares. Nothing can receive the report, and the capture it may have held is
            // gone with it.
            captured = null;
        }
        if (produced.isEmpty() && event.isWheel()) {
            // Nothing took the wheel, so it becomes the gesture it used to arrive as. It is delivered
            // through exactly one of the two paths, never both.
            Key legacy = legacyWheel(event.button());
            if (legacy != null) {
                InputRouter.route(root, InputEvent.KeyInput.of(legacy)).ifPresent(produced::add);
            }
        }
        return List.copyOf(produced);
    }

    /**
     * Drops a capture whose element the newest frame no longer shows, or a modal now covers.
     *
     * <p>Calling this once per frame is what keeps a removed target from holding a gesture: the
     * element is gone, so the motion it would have received belongs to nobody.</p>
     *
     * @param regions the newest frame's regions, in paint order
     */
    public void observeFrame(List<LayoutEngine.Region> regions) {
        if (captured == null) {
            return;
        }
        for (LayoutEngine.Region region : regions == null ? List.<LayoutEngine.Region>of() : regions) {
            if (region.reachable() && region.id().equals(captured)) {
                return;
            }
        }
        captured = null;
    }

    /**
     * The element a report is delivered to, or null when it is over no reachable region.
     *
     * @return the captured element while a gesture is in progress, otherwise the topmost painted
     *     region under the reported cell
     */
    private LayoutEngine.Region target(PointerEvent event, List<LayoutEngine.Region> regions) {
        if (captured != null && (event.isMotion() || event.isRelease())) {
            LayoutEngine.Region held = reachable(captured, regions);
            if (held != null) {
                return held;
            }
            // The captured element is no longer reachable - it was unmounted, or a modal opened over
            // it - so the gesture ends here rather than crossing into the layer above.
            captured = null;
            return null;
        }
        for (int index = regions.size() - 1; index >= 0; index--) {
            LayoutEngine.Region region = regions.get(index);
            if (region.reachable() && region.bounds().contains(event.row(), event.column())) {
                return region;
            }
        }
        return null;
    }

    private static LayoutEngine.Region reachable(ElementId id, List<LayoutEngine.Region> regions) {
        for (LayoutEngine.Region region : regions) {
            if (region.reachable() && region.id().equals(id)) {
                return region;
            }
        }
        return null;
    }

    /** Offers the report to one element, in its own coordinates. */
    private static <Msg> Optional<Msg> offer(View.Focused<Msg> node, PointerEvent event, LayoutEngine.Region target) {
        Size region = new Size(target.bounds().rows(), target.bounds().cols());
        return node.pointer()
                .handle(event.localTo(target.bounds().row(), target.bounds().col()), region);
    }

    /** Remembers a press, so the next one can be recognized as the second click of a gesture. */
    private PointerEvent rememberPress(PointerEvent event) {
        lastPressAt = event.capturedAtNanos();
        lastPressRow = event.row();
        lastPressColumn = event.column();
        lastPressButton = event.button();
        return event;
    }

    /**
     * Whether a press repeats the previous one: same button, same cell, inside the window.
     *
     * <p>An unstamped time cannot repeat anything, so a caller that does not stamp its reports gets
     * single clicks rather than clicks it never made.</p>
     */
    private boolean repeats(PointerEvent event) {
        if (event.capturedAtNanos() == 0L || lastPressAt == 0L) {
            return false;
        }
        return event.button() == lastPressButton
                && event.row() == lastPressRow
                && event.column() == lastPressColumn
                && event.capturedAtNanos() - lastPressAt <= DOUBLE_CLICK_NANOS
                && event.capturedAtNanos() >= lastPressAt;
    }

    /** The element currently holding the gesture, or null. */
    public ElementId captured() {
        return captured;
    }

    /** The key gesture a wheel direction used to be decoded into, or null when it had none. */
    private static Key legacyWheel(PointerButton button) {
        return switch (button) {
            case WHEEL_UP -> Key.WHEEL_UP;
            case WHEEL_DOWN -> Key.WHEEL_DOWN;
            // The horizontal wheel is newer than the legacy key identity: there is no key it ever
            // arrived as, so there is no compatibility gesture to fall back to.
            default -> null;
        };
    }
}

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

package io.github.dhoard.braxton.view;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tab through an interface built by a random number generator.
 *
 * <p>Focus traversal is where a keyboard-driven application becomes unusable: an order list that does
 * not wrap, a step that lands outside the list, or a focused element that is no longer in the list and
 * strands the caret. The contract is a cycle over the order list - the element after the current one,
 * wrapping, the first when nothing is focused, the last when stepping backwards from nothing - and a
 * cycle has a testable shape that does not need the implementation's own index arithmetic to check.</p>
 *
 * <p>So the oracle here is what a cycle must do: starting anywhere in a duplicate-free list, walking
 * forward exactly once visits every element and comes back to the start; one step back undoes one step
 * forward; the walk position advances by one per press, mod the size of the list. Lists with duplicates
 * and focused elements that are not in the list at all get the weaker guarantees that still hold - the
 * next element is always an element of the list, and a missing focus enters at the near end.</p>
 */
@Timeout(value = 60, unit = java.util.concurrent.TimeUnit.SECONDS)
class FocusStateFuzzTest {

    // Names an identity accepts: ComponentId rejects blank, over-long, ".", "..", "/" and control
    // characters, so the hostile spellings here are the ones that survive its grammar.
    private static final String[] NAMES = {
        "a",
        "b",
        "c",
        "d",
        "e",
        "f",
        "0",
        "\u4E2D",
        "\uD83D\uDE00",
        "\u00A0",
        "x".repeat(128),
        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
        "a b",
        "a.b",
        "..x",
        "x\u2022y",
    };

    @Test
    void walkingTheOrderListIsACycleThatVisitsEveryElementExactlyOnce() {
        Random random = new Random(9_021_826L);
        for (int round = 0; round < 4_000; round++) {
            List<ElementId> order = distinctOrder(random);
            if (order.isEmpty()) {
                continue;
            }
            ElementId start = order.get(random.nextInt(order.size()));
            for (int direction : new int[] {1, -1}) {
                FocusState focus = FocusState.of(start);
                Set<ElementId> seen = new HashSet<>();
                String where = "start %s order %s direction %+d".formatted(start, order, direction);
                for (int step = 1; step <= order.size(); step++) {
                    ElementId moved = direction > 0 ? focus.next(order) : focus.previous(order);
                    assertThat(moved)
                            .as("%s: step %d left the order list with %s", where, step, moved)
                            .isIn(order);
                    assertThat(seen.add(moved))
                            .as("%s: step %d visited %s twice, so a walk can get stuck", where, step, moved)
                            .isTrue();
                    focus = FocusState.of(moved);
                }
                assertThat(seen)
                        .as("%s: a full walk visited %d of %d elements", where, seen.size(), order.size())
                        .containsExactlyInAnyOrderElementsOf(order);
                // A cycle of size n lands back where it began after n presses.
                assertThat(focus.current())
                        .as("%s: the walk did not return to %s after a full cycle", where, start)
                        .contains(start);
            }
        }
    }

    /** One press back undoes one press forwards, and the position moves by exactly one. */
    @Test
    void steppingBackwardsUndoesSteppingForwardsAndPositionAdvancesByOne() {
        Random random = new Random(3_141_592L);
        for (int round = 0; round < 3_000; round++) {
            List<ElementId> order = distinctOrder(random);
            if (order.isEmpty()) {
                continue;
            }
            FocusState focus = FocusState.of(order.get(random.nextInt(order.size())));
            int size = order.size();
            for (int step = 0; step < 3 * size + 3; step++) {
                ElementId forward = focus.next(order);
                String where = "order %s after %d steps".formatted(order, step);
                assertThat(FocusState.of(forward).previous(order))
                        .as("%s: previous() did not undo next()", where)
                        .isEqualTo(focus.current().orElseThrow());
                ElementId backward = focus.previous(order);
                assertThat(FocusState.of(backward).next(order))
                        .as("%s: next() did not undo previous()", where)
                        .isEqualTo(focus.current().orElseThrow());
                // Position model: the walk advances one place per press, wrapping in both directions.
                int at = order.indexOf(focus.current().orElseThrow());
                assertThat(order.indexOf(forward))
                        .as("%s: next() moved from index %d to somewhere other than %d", where, at, (at + 1) % size)
                        .isEqualTo((at + 1) % size);
                assertThat(order.indexOf(backward))
                        .as(
                                "%s: previous() moved from index %d to somewhere other than %d",
                                where, at, Math.floorMod(at - 1, size))
                        .isEqualTo(Math.floorMod(at - 1, size));
                focus = FocusState.of(forward);
            }
        }
    }

    /**
     * A focused element that is not in the order list - because the view it belonged to is gone, or the
     * list was rebuilt - must enter at the near end rather than strand the walk.
     */
    @Test
    void focusOutsideTheOrderListEntersAtTheNearEndAndDuplicatesStayInsideTheList() {
        Random random = new Random(2_718_281L);
        for (int round = 0; round < 4_000; round++) {
            List<ElementId> order = order(random, true);
            for (ElementId focused : order(random, false)) {
                String where = "focus %s order %s".formatted(focused, order);
                if (order.isEmpty()) {
                    // Nothing to traverse: focus nowhere, and do not crash.
                    assertThat(FocusState.of(focused).next(order))
                            .as("%s: next() over an empty order list", where)
                            .isNull();
                    assertThat(FocusState.of(focused).previous(order))
                            .as("%s: previous() over an empty order list", where)
                            .isNull();
                    assertThat(FocusState.NONE.next(null))
                            .as("%s: next() over a null order list", where)
                            .isNull();
                    assertThat(FocusState.NONE.previous(null))
                            .as("%s: previous() over a null order list", where)
                            .isNull();
                    continue;
                }
                ElementId forward = FocusState.of(focused).next(order);
                ElementId backward = FocusState.of(focused).previous(order);
                assertThat(forward)
                        .as("%s: next() produced something outside the order list: %s", where, forward)
                        .isIn(order);
                assertThat(backward)
                        .as("%s: previous() produced something outside the order list: %s", where, backward)
                        .isIn(order);
                if (!order.contains(focused)) {
                    assertThat(forward)
                            .as("%s: focus outside the list did not enter at its start", where)
                            .isEqualTo(order.getFirst());
                    assertThat(backward)
                            .as("%s: focus outside the list did not enter at its end", where)
                            .isEqualTo(order.getLast());
                }
                // Stepping repeatedly from anywhere stays inside the list and always answers.
                FocusState walk = FocusState.of(focused);
                for (int step = 0; step < order.size() + 2; step++) {
                    ElementId moved = walk.next(order);
                    assertThat(moved)
                            .as("%s: walk step %d fell out of the order list", where, step)
                            .isIn(order);
                    walk = FocusState.of(moved);
                }
            }
        }
    }

    /** The value itself: no focus is a value, focus is never mutated in place, and identity is by value. */
    @Test
    void focusStatesAreValuesAndNeverShareMutableState() {
        Random random = new Random(1_414_213L);
        for (int round = 0; round < 2_000; round++) {
            ElementId first = ElementId.of(NAMES[random.nextInt(NAMES.length)]);
            ElementId second = ElementId.of(NAMES[random.nextInt(NAMES.length)]);

            FocusState none = FocusState.NONE;
            assertThat(none.current()).as("NONE carries a focus").isEmpty();
            assertThat(none.clear())
                    .as("clear() on NONE produced something new")
                    .isSameAs(FocusState.NONE);
            assertThat(FocusState.of(null))
                    .as("of(null) is not the empty focus")
                    .isEqualTo(FocusState.NONE);
            assertThat(none.next(List.of(first))).as("NONE has no next").isEqualTo(first);

            FocusState focused = FocusState.of(first);
            assertThat(focused.current()).as("focus() lost the element").contains(first);
            assertThat(focused.isFocused(first))
                    .as("isFocused() denies the focused element")
                    .isTrue();
            assertThat(focused.isFocused(second) && !first.equals(second))
                    .as("isFocused() accepts a different element")
                    .isFalse();
            assertThat(focused.isFocused(null)).as("isFocused(null) is true").isFalse();
            assertThat(FocusState.NONE.isFocused(first))
                    .as("an empty focus reports a focused element")
                    .isFalse();

            FocusState moved = focused.focus(second);
            assertThat(focused.current())
                    .as("focusing %s moved the focus of the state it was called on", second)
                    .contains(first);
            assertThat(moved).isEqualTo(FocusState.of(second));
            assertThat(moved).isEqualTo(focused.focus(ElementId.of(second.value())));
            assertThat(moved.hashCode())
                    .as("equal focus states disagree about hashCode")
                    .isEqualTo(FocusState.of(ElementId.of(second.value())).hashCode());
            assertThat(moved.clear()).isEqualTo(FocusState.NONE);
            if (!first.equals(second)) {
                assertThat(focused)
                        .as("focusing %s changed the focus of the state it was called on", second)
                        .isNotEqualTo(moved);
            }
            assertThat(focused.toString()).as("toString() is empty").isNotBlank();

            assertThat(FocusState.ofOptional(java.util.Optional.<ElementId>empty()))
                    .as("ofOptional(empty) invented a focus")
                    .isEmpty();
            assertThat(FocusState.ofOptional(java.util.Optional.of(first)))
                    .as("ofOptional(present) lost the element")
                    .contains(FocusState.of(first));
        }
    }

    private static List<ElementId> distinctOrder(Random random) {
        List<String> names = new ArrayList<>();
        for (int take = random.nextInt(NAMES.length + 1); take > 0; take--) {
            String name = NAMES[random.nextInt(NAMES.length)];
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        List<ElementId> order = new ArrayList<>();
        for (String name : names) {
            order.add(ElementId.of(name));
        }
        return order;
    }

    private static List<ElementId> order(Random random, boolean allowDuplicates) {
        List<ElementId> order = new ArrayList<>();
        for (int count = random.nextInt(6); count > 0; count--) {
            ElementId id = ElementId.of(NAMES[random.nextInt(NAMES.length)]);
            if (allowDuplicates || !order.contains(id)) {
                order.add(id);
            }
        }
        return order;
    }
}

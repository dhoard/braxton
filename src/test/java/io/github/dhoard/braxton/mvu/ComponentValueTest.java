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

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.layout.Viewport;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ComponentValueTest {
    @Test
    void updatesSnapshotOutputsAndRejectEveryNullBoundary() {
        var outputs = new ArrayList<>(List.of("out"));
        var result = new Update<>("model", Command.none(), outputs);
        outputs.clear();
        assertThat(result.outputs()).containsExactly("out");
        assertThatThrownBy(() -> result.outputs().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> Update.next(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Update.next("m", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Update<>("m", Command.none(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("outputs");
        assertThatThrownBy(() -> Update.emit("m", null)).isInstanceOf(NullPointerException.class);
        assertThat(Never.values()).isEmpty();
    }

    @Test
    void pathsAreStructuralImmutableValues() {
        var ids = new ArrayList<>(List.of(ComponentId.of("a")));
        var path = new ComponentPath(ids);
        ids.clear();
        assertThat(path.segments()).containsExactly(ComponentId.of("a"));
        assertThat(path.append(ComponentId.of("b")).startsWith(path)).isTrue();
        assertThat(path.startsWith(path.append(ComponentId.of("b")))).isFalse();
        assertThat(path.startsWith(ComponentPath.ROOT.append(ComponentId.of("ab")))).isFalse();
        assertThat(path.startsWith(ComponentPath.ROOT)).isTrue();
        assertThat(ComponentPath.ROOT.startsWith(path)).isFalse();
        var same = ComponentPath.ROOT.append(ComponentId.of("a"));
        var deeper = same.append(ComponentId.of("b"));
        assertThat(path).isEqualTo(same);
        assertThat(path.hashCode()).isEqualTo(same.hashCode());
        assertThat(new java.util.HashSet<>(List.of(path))).contains(same);
        var map = new java.util.HashMap<ComponentPath, String>();
        map.put(path, "value");
        assertThat(map).containsEntry(same, "value");
        assertThat(deeper.prepend(ComponentPath.ROOT)).isEqualTo(deeper);
        assertThat(ComponentPath.ROOT.append(ComponentId.of("x")).prepend(path))
                .isEqualTo(path.append(ComponentId.of("x")));
        assertThat(path.toString()).isEqualTo("/a");
        assertThat(ComponentPath.ROOT.toString()).isEqualTo("/");
        assertThatThrownBy(() -> path.segments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        for (String invalid : List.of("", " ", ".", "..", "a/b", "\u001b", "x".repeat(129))) {
            assertThatThrownBy(() -> ComponentId.of(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ComponentId.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> path.prepend(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("prefix");
        assertThatThrownBy(() -> path.startsWith(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("prefix");
        assertThatThrownBy(() -> new ComponentPath(java.util.Arrays.asList((ComponentId) null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ComponentPath(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("segments");
    }

    @Test
    void contextsRoutesLensesAndTreesValidateAndSnapshot() {
        assertThatThrownBy(() -> new ViewContext(null)).isInstanceOf(NullPointerException.class);
        assertThat(new ViewContext(Viewport.EMPTY).viewport()).isSameAs(Viewport.EMPTY);
        assertThatThrownBy(() -> ModelLens.of(null, (String p, String c) -> p))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OutputRoute.toMessage(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OutputRoute.toOutput(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutputRoute.Message<>(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutputRoute.Output<>(null))
                .isInstanceOf(NullPointerException.class);
        var nodes = new ArrayList<ComponentTree.Node>();
        var tree = new ComponentTree(nodes);
        nodes.add(new ComponentTree.Node(ComponentId.of("a"), ComponentTree.empty()));
        assertThat(tree.nodes()).isEmpty();
        assertThatThrownBy(() -> new ComponentTree.Node(null, ComponentTree.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ComponentTree.Node(ComponentId.of("a"), (ComponentTree) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Initialization<>(null, Command.none(), List.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Initialization<>("model", Command.none(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("outputs");
        assertThatThrownBy(() -> new ComponentTree(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("nodes");
        assertThatThrownBy(() -> ComponentTree.of((ComponentTree.Node) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("node");
    }
}

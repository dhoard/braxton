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

package io.github.dhoard.braxton.app;

import static org.assertj.core.api.Assertions.*;

import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.testing.ComponentDriver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.tools.ToolProvider;

class DocumentedHierarchyTest {
    @TempDir Path temporary;

    @Test
    void readmeQuickStartCompilesWarningFreeAndRendersThroughThePublicDriver() throws Exception {
        String readme = Files.readString(Path.of("README.md"));
        int start = readme.indexOf("```java\npackage demo;") + "```java\n".length();
        assertThat(start).isGreaterThan("```java\n".length());
        String source = readme.substring(start, readme.indexOf("```", start));
        Path java = temporary.resolve("demo/Counters.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, source);
        int result =
                ToolProvider.getSystemJavaCompiler()
                        .run(
                                null,
                                null,
                                null,
                                "--release",
                                "25",
                                "-Xlint:all",
                                "-Werror",
                                "-proc:none",
                                "-cp",
                                "target/classes",
                                "-d",
                                temporary.toString(),
                                java.toString());
        assertThat(result).isZero();
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {temporary.toUri().toURL()},
                        getClass().getClassLoader())) {
            var component =
                    (Component<?, ?, ?>)
                            loader.loadClass("demo.Counters").getConstructor().newInstance();
            verify(component);
        }
    }

    private static <M, Msg, Out> void verify(Component<M, Msg, Out> component) {
        var driver =
                ComponentDriver.initialize(component, new InitContext(new Viewport(3, 30), null));
        assertThat(driver.children().nodes()).hasSize(2);
        assertThat(driver.screen(new ViewContext(new Viewport(3, 30))).plainLine(0))
                .isEqualTo("count: 0");
    }

    @Test
    void theBreakingApiDoesNotPublishFlatTypesOrNamespaceMembers() {
        for (String removed :
                new String[] {
                    "app.Program",
                    "app.Transition",
                    "app.InitContext",
                    "widget.EditorState",
                    "widget.EditorAction",
                    "widget.EditorIntent",
                    "widget.EditorResult",
                    "widget.EditorOptions"
                }) {
            assertThatThrownBy(() -> Class.forName("io.github.dhoard.braxton." + removed))
                    .isInstanceOf(ClassNotFoundException.class);
        }
        assertThat(io.github.dhoard.braxton.effect.Command.class.getMethods())
                .noneMatch(method -> method.getName().equals("cancelNamespace"));
        assertThat(io.github.dhoard.braxton.effect.EffectId.class.getMethods())
                .noneMatch(
                        method ->
                                method.getName().equals("child")
                                        || method.getName().equals("namespace"));
    }
}

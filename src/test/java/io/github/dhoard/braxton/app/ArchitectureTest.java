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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Architecture guard: one API, one engine, no legacy callback/session code, and no third-party
 * library import in the core.
 */
class ArchitectureTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final List<String> LEGACY_ROOTS = List.of(
            "utils/", "inputForm/", "Keyhandle/", "Text/", "automation/", "clipboard/");

    /**
     * The nine implementation owners of the one-row policy in the cross-platform line-ending
     * design. Each must delegate conversion to {@code LineBreaks}; this list is explicit because a
     * test cannot discover an arbitrary future surface that reintroduces its own chain.
     */
    private static final List<String> LINE_BREAK_OWNERS = List.of(
            "io/github/dhoard/braxton/asset/ImageView.java",
            "io/github/dhoard/braxton/widget/ProgressBar.java",
            "io/github/dhoard/braxton/widget/Quote.java",
            "io/github/dhoard/braxton/widget/Markup.java",
            "io/github/dhoard/braxton/widget/TableSelector.java",
            "io/github/dhoard/braxton/widget/EditorOptions.java",
            "io/github/dhoard/braxton/widget/Selector.java",
            "io/github/dhoard/braxton/widget/Badge.java",
            "io/github/dhoard/braxton/widget/Box.java");

    private static List<Path> sources() throws IOException {
        try (var paths = Files.walk(MAIN)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void noLegacyLibraryPackagesRemain() {
        for (String root : LEGACY_ROOTS) {
            assertThat(MAIN.resolve(root)).as("legacy package %s", root).doesNotExist();
        }
    }

    @Test
    void noSourceImportsALegacyPackage() throws IOException {
        for (Path source : sources()) {
            String content = Files.readString(source);
            for (String root : LEGACY_ROOTS) {
                String packageName = root.substring(0, root.length() - 1);
                assertThat(content)
                        .as("legacy import %s in %s", packageName, source)
                        .doesNotContain("import " + packageName + ".")
                        .doesNotContain("Keyhandle.")
                        .doesNotContain("inputForm.")
                        .doesNotContain("utils.Container")
                        .doesNotContain("OnClick");
            }
        }
    }

    @Test
    void lineBreakConversionHasOneOwnerAndEveryInventoryOwnerDelegates() throws IOException {
        // Duplicating the CRLF/CR chain per site is what let Box flatten after the span boundary
        // and lose a bare CR; conversion now lives only in LineBreaks.
        for (Path source : sources()) {
            String content = Files.readString(source);
            assertThat(content)
                    .as("duplicate CRLF replacement chain in %s", source)
                    .doesNotContain(".replace(\"\\r\\n\"")
                    .as("duplicate CR replacement chain in %s", source)
                    .doesNotContain(".replace('\\r'");
        }
        for (String owner : LINE_BREAK_OWNERS) {
            Path source = MAIN.resolve(owner);
            assertThat(source).as("inventory owner %s", owner).exists();
            assertThat(Files.readString(source)).as("LineBreaks delegation in %s", owner)
                    .contains("LineBreaks.");
        }
    }

    @Test
    void theCoreDoesNotImportGson() throws IOException {
        for (Path source : sources()) {
            String relative = MAIN.relativize(source).toString().replace('\\', '/');
            if (relative.startsWith("examples/")) {
                continue;
            }
            assertThat(Files.readString(source))
                    .as("Gson import in %s", relative)
                    .doesNotContain("com.google.gson");
        }
    }

    @Test
    void theRuntimeDoesNotWriteToSystemOutOrSystemErr() throws IOException {
        for (Path source : sources()) {
            String relative = MAIN.relativize(source).toString().replace('\\', '/');
            if (!relative.startsWith("io/github/dhoard/braxton/")) {
                continue;
            }
            String content = Files.readString(source);
            assertThat(content)
                    .as("direct stdio in %s", relative)
                    .doesNotContain("System.out")
                    .doesNotContain("System.err");
        }
    }

    @Test
    void noSourceReferencesAnExternalSessionOrProvider() throws IOException {
        for (Path source : sources()) {
            String content = Files.readString(source);
            assertThat(content).as("external reference in %s", source)
                    .doesNotContain("org.amanda")
                    .doesNotContain("tui4j")
                    .doesNotContain(".amanda")
                    .doesNotContain("AI_HARNESS_SESSION");
        }
    }

    @Test
    void thePublicApplicationApiIsSmallAndTyped() throws IOException {
        List<String> publicTypes = new ArrayList<>();
        for (Path source : sources()) {
            String relative = MAIN.relativize(source).toString().replace('\\', '/');
            if (relative.startsWith("io/github/dhoard/braxton/internal/") || relative.startsWith("examples/")) {
                continue;
            }
            String content = Files.readString(source);
            if (content.contains("public interface ") || content.contains("public final class ")
                    || content.contains("public record ")) {
                publicTypes.add(relative);
            }
        }
        assertThat(publicTypes).isNotEmpty();
        assertThat(publicTypes).doesNotContain("io/github/dhoard/braxton/terminal/SystemTerminalBackend.java");
    }

    @Test
    void legacyFontsAreStillBundledUnchangedByCount() {
        Path fonts = Path.of("src/main/resources/fonts");
        assertThat(fonts).isDirectory();
        try (var paths = Files.list(fonts)) {
            assertThat(paths.filter(p -> p.toString().endsWith(".flf")).count()).isEqualTo(10);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
    @Test
    void terminalIntegrationHasOneOwnerAndNoNativeOrCompatibilityImplementation() throws Exception {
        for (Path source : sources()) {
            String relative = MAIN.relativize(source).toString().replace('\\', '/');
            if (!relative.startsWith("io/github/dhoard/braxton/")) continue;
            String content = Files.readString(source);
            assertThat(content).doesNotContain("java.lang.foreign", "sun.misc", "jdk.internal",
                    "TerminalBackend", "TerminalSession", "org.jline.reader", "org.jline.utils.Display");
            if (content.contains("import org.jline.")) {
                assertThat(relative).matches(".*/internal/(runtime|input)/[^/]+\\.java");
            }
            if (relative.contains("/internal/")) continue;
            String className = relative.substring(0, relative.length()-5).replace('/', '.');
            Class<?> type = Class.forName(className, false, getClass().getClassLoader());
            var pending = new java.util.ArrayDeque<Class<?>>(); pending.add(type);
            while (!pending.isEmpty()) {
                Class<?> current = pending.removeFirst();
                pending.addAll(List.of(current.getDeclaredClasses()));
                for (var method : current.getDeclaredMethods()) {
                    if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
                    assertThat(method.toGenericString()).doesNotContain("org.jline.", "braxton.internal.");
                }
                for (var constructor : current.getDeclaredConstructors()) {
                    if (java.lang.reflect.Modifier.isPublic(constructor.getModifiers())) {
                        assertThat(constructor.toGenericString()).doesNotContain("org.jline.", "braxton.internal.");
                    }
                }
            }
        }
    }

    @Test
    void terminalModulesAreTheSelectedLocalBuild() throws Exception {
        for (String module : List.of("jline-terminal", "jline-terminal-ffm", "jline-native")) {
            String path = "/META-INF/maven/org.jline/" + module + "/pom.properties";
            try (var input = getClass().getResourceAsStream(path)) {
                assertThat(input).isNotNull();
                var properties = new java.util.Properties(); properties.load(input);
                assertThat(properties.getProperty("version")).isEqualTo("0.1.0-jline-braxton");
            }
        }
    }

    @Test
    void theDefaultSuiteHasNoNativeAccessGrant() {
        assertThat(getClass().getModule().isNativeAccessEnabled()).isFalse();
    }
}

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
}

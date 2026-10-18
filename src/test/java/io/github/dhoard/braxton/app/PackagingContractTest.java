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
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The packaging contract the README states for the shipped artifact: the bytecode target and the
 * absence of any third-party reference in the core.
 *
 * <p>{@code ArchitectureTest} checks the same rule over the source tree. This checks it over the
 * compiled classes the artifact is built from, so a dependency that arrives without an import (a
 * generated source, a renamed package, a transitive type named in a signature) is still caught, and
 * it pins the documented bytecode version to what the build actually emits.</p>
 */
class PackagingContractTest {

    /** The README: "the build targets bytecode 69" (Java 25). */
    private static final int DOCUMENTED_MAJOR_VERSION = 69;

    /** Packages the core may reference: the JDK modules it uses and Braxton itself. */
    private static final List<String> ALLOWED_REFERENCES = List.of(
            "java/", "javax/", "org/w3c/", "org/xml/", "io/github/dhoard/");

    private static final String CORE_ROOT = "io/github/dhoard/braxton/";

    /** Array descriptors of primitives, which name no package. */
    private static final List<String> PRIMITIVE_DESCRIPTORS =
            List.of("B", "C", "D", "F", "I", "J", "S", "Z", "V");

    @Test
    void everyCoreClassTargetsTheDocumentedBytecodeVersion() throws IOException, URISyntaxException {
        List<Path> classes = coreClasses();
        assertThat(classes).as("the core compiled for this run").isNotEmpty();
        for (Path path : classes) {
            assertThat(majorVersion(Files.readAllBytes(path)))
                    .as("bytecode target of %s", path)
                    .isEqualTo(DOCUMENTED_MAJOR_VERSION);
        }
    }

    @Test
    void noCoreClassReferencesAThirdPartyPackage() throws IOException, URISyntaxException {
        List<Path> classes = coreClasses();
        assertThat(classes).as("the core compiled for this run").isNotEmpty();
        for (Path path : classes) {
            for (String referenced : referencedTypes(Files.readAllBytes(path))) {
                assertThat(ALLOWED_REFERENCES.stream().anyMatch(referenced::startsWith))
                        .as("%s references %s, which the core must not depend on", path, referenced)
                        .isTrue();
            }
        }
    }

    /** Every {@code .class} of the core, as the packaged artifact would carry it. */
    private static List<Path> coreClasses() throws IOException, URISyntaxException {
        Path root = Path.of(Application.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        assertThat(Files.isDirectory(root))
                .as("the core classes are expected to be a directory for this run, found %s", root)
                .isTrue();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path))
                    .filter(relative -> relative.toString().replace('\\', '/').startsWith(CORE_ROOT))
                    .filter(relative -> relative.toString().endsWith(".class"))
                    .map(relative -> root.resolve(relative))
                    .sorted()
                    .toList();
        }
    }

    private static int majorVersion(byte[] code) {
        assertThat(code.length > 10).as("a class file is longer than its header").isTrue();
        assertThat((code[0] & 0xFF) == 0xCA && (code[1] & 0xFF) == 0xFE
                && (code[2] & 0xFF) == 0xBA && (code[3] & 0xFF) == 0xBE)
                .as("a class file starts with its magic number").isTrue();
        return ((code[6] & 0xFF) << 8) | (code[7] & 0xFF);
    }

    /**
     * The internal names of every type the constant pool names. A non-array class entry is already
     * an internal name; an array entry is a descriptor, so its brackets and {@code L}/{@code ;} are
     * stripped. Bracket-only descriptors (primitive arrays) name no package and are skipped.
     */
    private static List<String> referencedTypes(byte[] code) {
        int count = readUnsigned(code, 8);
        Map<Integer, String> utf8 = new HashMap<>();
        List<Integer> classNameIndexes = new ArrayList<>();
        int at = 10;
        for (int index = 1; index < count; index++) {
            int tag = code[at] & 0xFF;
            switch (tag) {
                case 1 -> {
                    int length = readUnsigned(code, at + 1);
                    utf8.put(index, new String(code, at + 3, length, StandardCharsets.UTF_8));
                    at += 3 + length;
                }
                case 7 -> {
                    classNameIndexes.add(readUnsigned(code, at + 1));
                    at += 3;
                }
                case 8, 16, 19 -> at += 3;
                case 9, 10, 11, 12, 17, 18, 20 -> at += 5;
                case 15 -> at += 4;
                case 3, 4 -> at += 5;
                case 5, 6 -> {
                    at += 9;
                    index++;
                }
                default -> throw new IllegalStateException(
                        "unknown constant pool tag " + tag + " at " + at);
            }
        }
        List<String> types = new ArrayList<>();
        for (int index : classNameIndexes) {
            String name = utf8.get(index);
            if (name == null) {
                throw new IllegalStateException("a class entry names no constant pool string");
            }
            while (name.startsWith("[")) {
                name = name.substring(1);
            }
            if (name.startsWith("L") && name.endsWith(";")) {
                name = name.substring(1, name.length() - 1);
            }
            if (PRIMITIVE_DESCRIPTORS.contains(name)) {
                continue;
            }
            types.add(name);
        }
        return types;
    }

    private static int readUnsigned(byte[] code, int at) {
        return ((code[at] & 0xFF) << 8) | (code[at + 1] & 0xFF);
    }
}

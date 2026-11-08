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

package io.github.dhoard.braxton.internal.terminal;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(40)
class NativeLibraryProcessTest {
    @TempDir
    Path work;

    private Process start(String mode, Path root, Path ready, Path release, Path payload, String name)
            throws Exception {
        Path temporary = Files.createDirectories(work.resolve("tmp-" + name));
        Path home = Files.createDirectories(work.resolve("home-" + name));
        var command = List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + temporary,
                "-Duser.home=" + home,
                "-cp",
                System.getProperty("java.class.path"),
                NativeExtractionProcess.class.getName(),
                mode,
                root.toString(),
                ready.toString(),
                release.toString(),
                "1.0.0",
                payload.toString());
        var builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(work.resolve(name + ".log").toFile());
        builder.environment().put("HOME", work.resolve("must-not-be-used").toString());
        return builder.start();
    }

    private void waitFor(Path marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(marker)) {
            assertThat(System.nanoTime())
                    .as("missing process marker %s", marker)
                    .isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private String completed(Process process, String name) throws Exception {
        assertThat(process.waitFor(15, TimeUnit.SECONDS))
                .as("process %s timed out", name)
                .isTrue();
        String output = Files.readString(work.resolve(name + ".log")).strip();
        assertThat(process.exitValue()).as("process %s: %s", name, output).isZero();
        return output;
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) process.destroyForcibly();
        assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void simultaneousProcessesStartingFromAnEmptyCacheAllReadTheCompleteImage() throws Exception {
        byte[] bytes = "large complete image".repeat(65536).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path payload = Files.write(work.resolve("payload"), bytes);
        Path root = work.resolve("cache");
        Path release = work.resolve("release");
        var processes = new ArrayList<Process>();
        try {
            for (int index = 0; index < 6; index++) {
                processes.add(
                        start("extract", root, work.resolve("ready-" + index), release, payload, "worker-" + index));
            }
            for (int index = 0; index < processes.size(); index++) waitFor(work.resolve("ready-" + index));
            assertThat(root).doesNotExist();
            Files.createFile(release);
            Path expected = null;
            for (int index = 0; index < processes.size(); index++) {
                Path result = Path.of(completed(processes.get(index), "worker-" + index));
                assertThat(Files.readAllBytes(result)).isEqualTo(bytes);
                if (expected == null) expected = result;
                else assertThat(result).isEqualTo(expected);
            }
            try (var entries = Files.list(expected.getParent())) {
                assertThat(entries.map(path -> path.getFileName().toString()).toList())
                        .containsExactlyInAnyOrder("libbraxton.so", ".extract.lock");
            }
        } finally {
            for (Process process : processes) stop(process);
        }
    }

    @Test
    void processDeathReleasesTheExtractionLockAndLeavesNoPartialPublishedImage() throws Exception {
        byte[] bytes = "complete image".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path payload = Files.write(work.resolve("payload"), bytes);
        Path root = work.resolve("cache");
        Path expected = NativeLibraryLoader.cached(
                new NativeLibraryLoader.Image("1.0.0", "linux-x86_64", "libbraxton.so", bytes), root);
        Files.delete(expected);
        Path locked = work.resolve("locked");
        Process holder = start(
                "hold",
                expected.resolveSibling(".extract.lock"),
                work.resolve("holder-ready"),
                locked,
                payload,
                "holder");
        Process follower = null;
        try {
            waitFor(locked);
            follower = start("extract", root, work.resolve("follower-ready"), locked, payload, "follower");
            waitFor(work.resolve("follower-ready"));
            assertThat(follower.waitFor(150, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(expected).doesNotExist();
            holder.destroyForcibly();
            assertThat(holder.waitFor(5, TimeUnit.SECONDS)).isTrue();
            assertThat(Path.of(completed(follower, "follower"))).isEqualTo(expected);
            assertThat(Files.readAllBytes(expected)).isEqualTo(bytes);
        } finally {
            stop(holder);
            if (follower != null) stop(follower);
        }
    }

    @Test
    void fallbackUsesTheJvmUserHomeInsteadOfTheHomeEnvironmentVariable() throws Exception {
        Path payload = Files.writeString(work.resolve("payload"), "complete image");
        Path release = Files.createFile(work.resolve("release"));
        Process process = start("fallback", work.resolve("unused"), work.resolve("ready"), release, payload, "home");
        try {
            Path result = Path.of(completed(process, "home"));
            assertThat(result).startsWith(work.resolve("home-home/.local/share/braxton/native/1.0.0/linux-x86_64"));
            assertThat(work.resolve("must-not-be-used")).doesNotExist();
        } finally {
            stop(process);
        }
    }
}

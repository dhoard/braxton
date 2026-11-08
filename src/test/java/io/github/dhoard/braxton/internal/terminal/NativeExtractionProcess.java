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

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Owned, native-access-free subprocess used by NativeLibraryProcessTest. */
public final class NativeExtractionProcess {
    private NativeExtractionProcess() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path root = Path.of(args[1]);
        Path ready = Path.of(args[2]);
        Path release = Path.of(args[3]);
        var image = new NativeLibraryLoader.Image(
                args[4], "linux-x86_64", "libbraxton.so", Files.readAllBytes(Path.of(args[5])));
        Files.writeString(ready, "ready");
        if (mode.equals("hold")) {
            try (var channel = FileChannel.open(root, StandardOpenOption.WRITE);
                    var lock = channel.lock(0, 1, false)) {
                if (!lock.isValid()) throw new AssertionError("invalid lock");
                Files.writeString(release, "locked");
                Files.writeString(root.resolveSibling(".extract-abandoned.part"), "interrupted extraction");
                System.in.read();
            }
            return;
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(release)) {
            if (System.nanoTime() > deadline) throw new AssertionError("start barrier was never released");
            Thread.sleep(5);
        }
        Path result = mode.equals("fallback")
                ? NativeLibraryLoader.load(image, file -> {
                    if (file.startsWith(Path.of(System.getProperty("java.io.tmpdir"))))
                        throw new IllegalArgumentException("noexec");
                    return file;
                })
                : NativeLibraryLoader.cached(image, root);
        System.out.println(result);
    }
}

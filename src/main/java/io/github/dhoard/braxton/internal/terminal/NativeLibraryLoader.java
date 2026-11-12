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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Native extraction policy, independent of FFM so publication and fallback are testable headlessly. */
final class NativeLibraryLoader {
    @FunctionalInterface
    interface Link<T> {
        T load(Path library) throws IOException;
    }

    @FunctionalInterface
    interface Directory {
        Path get() throws IOException;
    }

    /**
     * A packaged native image and its build-recorded identity. The content hash is written beside the
     * binary during the native build; it names the cache entry and is never recomputed at startup.
     */
    static final class Image {
        private final String version;
        private final String platform;
        private final String filename;
        private final byte[] bytes;
        private final String contentHash;

        Image(String version, String platform, String filename, byte[] bytes, String contentHash) throws IOException {
            this.version = component(version);
            this.platform = component(platform);
            this.filename = component(filename);
            this.bytes = bytes.clone();
            if (bytes.length == 0) throw new IOException("empty Braxton native library");
            this.contentHash = validateContentHash(contentHash);
        }
    }

    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> STAGING_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> LIBRARY_MODE = PosixFilePermissions.fromString("r-x------");
    /** Bounded attempts to pick an entry name that nothing else already occupies. */
    static final int TEMPORARY_ATTEMPTS = 16;

    private NativeLibraryLoader() {}

    static Image packaged(String platform) throws IOException {
        String filename = platform.startsWith("macos-") ? "libbraxton.dylib" : "libbraxton.so";
        String prefix = "/META-INF/braxton/native/" + component(platform) + "/";
        String version = metadata(prefix + "version.txt");
        String contentHash = metadata(prefix + filename + ".sha256");
        try (var input = NativeLibraryLoader.class.getResourceAsStream(prefix + filename)) {
            if (input == null)
                throw new IOException("native library missing for " + platform + "; build Braxton on this platform");
            return new Image(version, platform, filename, input.readAllBytes(), contentHash);
        }
    }

    /** Reads one small build-generated metadata resource beside the packaged binary. */
    private static String metadata(String resource) throws IOException {
        try (var input = NativeLibraryLoader.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("missing Braxton native metadata " + resource);
            byte[] bytes = input.readNBytes(257);
            if (bytes.length > 256) throw new IOException("invalid Braxton native metadata " + resource);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).strip();
        }
    }

    static <T> T load(Image image, Link<T> link) throws IOException {
        return load(
                image,
                () -> propertyPath("java.io.tmpdir"),
                () -> propertyPath("user.home").resolve(".local/share/braxton"),
                link);
    }

    static <T> T load(Image image, Directory temporary, Directory fallback, Link<T> link) throws IOException {
        Throwable first;
        try {
            return temporary(image, temporary.get(), link);
        } catch (IOException | IllegalArgumentException | UnsatisfiedLinkError failure) {
            first = failure;
        }
        Path root = null;
        try {
            root = fallback.get();
            return link.load(cached(image, root));
        } catch (IOException | IllegalArgumentException | UnsatisfiedLinkError failure) {
            var combined = new IOException(
                    "cannot load Braxton native library " + image.version + "/" + image.platform
                            + " from temporary storage or " + (root == null ? "user.home/.local/share/braxton" : root),
                    failure);
            combined.addSuppressed(first);
            throw combined;
        }
    }

    private static <T> T temporary(Image image, Path root, Link<T> link) throws IOException {
        Path directory = privateTemporaryDirectory(root);
        Path file = directory.resolve(image.filename);
        Throwable primary = null;
        try {
            write(file, image.bytes, false);
            return link.load(file);
        } catch (IOException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            IOException cleanup = null;
            try {
                Files.deleteIfExists(file);
            } catch (IOException failure) {
                cleanup = failure;
            }
            try {
                Files.deleteIfExists(directory);
            } catch (IOException failure) {
                if (cleanup == null) cleanup = failure;
                else cleanup.addSuppressed(failure);
            }
            if (cleanup != null) {
                if (primary != null) primary.addSuppressed(cleanup);
                else {
                    // Loading succeeded: delayed removal must not turn that success into a fallback.
                    directory.toFile().deleteOnExit();
                    file.toFile().deleteOnExit();
                }
            }
        }
    }

    static Path cached(Image image, Path root) throws IOException {
        Files.createDirectories(
                root.toAbsolutePath().getParent(), PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
        privateDirectory(root);
        Path directory = root.resolve("native");
        privateDirectory(directory);
        directory = directory.resolve(image.version);
        privateDirectory(directory);
        directory = directory.resolve(image.platform);
        privateDirectory(directory);
        directory = directory.resolve(image.contentHash);
        privateDirectory(directory);
        directory = directory.toRealPath();
        Path file = directory.resolve(image.filename);
        Path lockFile = directory.resolve(".extract.lock");
        // A canonical, JVM-wide monitor also coordinates copies loaded by different class loaders.
        // Acquire it before opening a channel: closing another channel to a POSIX lock inode can
        // otherwise release this JVM's active lock. Never delete the shared lock file.
        String monitor = (NativeLibraryLoader.class.getName() + ":" + lockFile).intern();
        synchronized (monitor) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("native extraction interrupted");
            try (var channel = FileChannel.open(
                    lockFile,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(STAGING_MODE))) {
                trustedFile(lockFile, directory);
                try (var lock = channel.lock(0, 1, false)) {
                    if (!lock.isValid()) throw new IOException("native extraction lock is invalid");
                    if (matches(image, file, directory)) return file;
                    Path staging = stagingFile(directory);
                    Throwable primary = null;
                    try {
                        write(staging, image.bytes, true);
                        Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        return file;
                    } catch (IOException | RuntimeException | Error failure) {
                        primary = failure;
                        throw failure;
                    } finally {
                        try {
                            Files.deleteIfExists(staging);
                        } catch (IOException failure) {
                            if (primary != null) primary.addSuppressed(failure);
                            else throw failure;
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates an exclusively named private directory under {@code root}. An occupied name is never
     * adopted: it may be another user's entry, so it only costs an attempt. The mode is applied by
     * {@code mkdir} itself, so the directory is never briefly wider than owner-only.
     */
    static Path privateTemporaryDirectory(Path root) throws IOException {
        return privateTemporaryDirectory(root, unpredictable());
    }

    static Path privateTemporaryDirectory(Path root, String suffix) throws IOException {
        for (int attempt = 0; attempt < TEMPORARY_ATTEMPTS; attempt++) {
            Path candidate = root.resolve("braxton-native-" + suffix + "-" + attempt);
            try {
                Files.createDirectory(candidate, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            } catch (FileAlreadyExistsException taken) {
                continue;
            }
            validatePrivateDirectory(candidate);
            return candidate;
        }
        throw new IOException("cannot create a private Braxton temporary directory in " + root);
    }

    /**
     * A staging name inside an already validated private directory. Exclusive creation means a
     * crashed writer's partial file is never reused. Abandoned files are left alone, because they
     * may belong to another process.
     */
    private static Path stagingFile(Path directory) throws IOException {
        String suffix = unpredictable();
        for (int attempt = 0; attempt < TEMPORARY_ATTEMPTS; attempt++) {
            Path candidate = directory.resolve(".extract-" + suffix + "-" + attempt + ".part");
            try {
                Files.createFile(candidate, PosixFilePermissions.asFileAttribute(STAGING_MODE));
                return candidate;
            } catch (FileAlreadyExistsException taken) {
                continue;
            }
        }
        throw new IOException("cannot create a Braxton staging file in " + directory);
    }

    /**
     * A name component other local users cannot predict. Linux and macOS both provide /dev/urandom,
     * and eight bytes from it cost about 0.2 ms; obtaining the same strength from SecureRandom would
     * initialize the JCA provider framework (~4.6 ms) on every application start. The fallback is
     * not cryptographic and is safe only because creation is exclusive and every collision is
     * retried rather than adopted.
     */
    private static String unpredictable() {
        try (var channel = FileChannel.open(Path.of("/dev/urandom"), StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new IOException("short read");
            buffer.flip();
            return Long.toHexString(buffer.getLong());
        } catch (IOException | RuntimeException unavailable) {
            return Long.toHexString(System.nanoTime()
                    ^ (ThreadLocalRandom.current().nextLong() * 0x9E3779B97F4A7C15L)
                    ^ ProcessHandle.current().pid());
        }
    }

    /**
     * Writes one image and restricts its mode. Only a published cache entry is forced to stable
     * storage: the temporary copy is unlinked moments after it is mapped, so flushing it buys
     * nothing and costs a full journal commit per launch on a disk-backed temporary directory.
     */
    private static void write(Path file, byte[] bytes, boolean durable) throws IOException {
        try (var output = FileChannel.open(
                file,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(STAGING_MODE))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            if (durable) output.force(true);
        }
        Files.setPosixFilePermissions(file, LIBRARY_MODE);
    }

    /**
     * Reuses a published entry only when it is byte-for-byte the packaged image. Exact comparison
     * replaces a digest check: the recorded hash names the cache directory, so a duplicated or stale
     * hash can at worst cause an unnecessary replacement, never a wrong binary being loaded.
     */
    private static boolean matches(Image image, Path file, Path directory) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        PosixFileAttributes attributes = trustedFile(file, directory);
        if (attributes.size() != image.bytes.length) return false;
        try (var input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            int offset = 0;
            while (offset < image.bytes.length) {
                buffer.clear();
                int count = input.read(buffer);
                if (count <= 0) return false;
                if (Arrays.mismatch(buffer.array(), 0, count, image.bytes, offset, offset + count) != -1) return false;
                offset += count;
            }
            return true;
        }
    }

    private static void privateDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
        } catch (FileAlreadyExistsException existing) {
            /* Validate the existing entry below. */
        }
        validatePrivateDirectory(directory);
    }

    private static void validatePrivateDirectory(Path directory) throws IOException {
        var attributes = Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || writableByOthers(attributes))
            throw new IOException("unsafe native cache directory: " + directory);
    }

    private static PosixFileAttributes trustedFile(Path file, Path directory) throws IOException {
        var attributes = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || writableByOthers(attributes)
                || !attributes.owner().equals(Files.getOwner(directory)))
            throw new IOException("unsafe native cache file: " + file);
        return attributes;
    }

    private static boolean writableByOthers(PosixFileAttributes attributes) {
        return attributes.permissions().contains(PosixFilePermission.GROUP_WRITE)
                || attributes.permissions().contains(PosixFilePermission.OTHERS_WRITE);
    }

    private static String component(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,127}"))
            throw new IOException("invalid native library identity component: " + value);
        return value;
    }

    private static String validateContentHash(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IOException("invalid Braxton native content hash; the native build must record one");
        return value;
    }

    private static Path propertyPath(String name) throws IOException {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IOException("missing " + name + " for native extraction");
        try {
            return Path.of(value);
        } catch (java.nio.file.InvalidPathException failure) {
            throw new IOException("invalid " + name + " for native extraction", failure);
        }
    }
}

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

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

    static final class Image {
        private final String version;
        private final String platform;
        private final String filename;
        private final byte[] bytes;
        private final String digest;

        Image(String version, String platform, String filename, byte[] bytes) throws IOException {
            this.version = component(version);
            this.platform = component(platform);
            this.filename = component(filename);
            this.bytes = bytes.clone();
            if (bytes.length == 0) throw new IOException("empty Braxton native library");
            digest = HexFormat.of().formatHex(sha256().digest(this.bytes));
        }
    }

    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> STAGING_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> LIBRARY_MODE = PosixFilePermissions.fromString("r-x------");

    private NativeLibraryLoader() {}

    static Image packaged(String platform) throws IOException {
        String filename = platform.startsWith("macos-") ? "libbraxton.dylib" : "libbraxton.so";
        String prefix = "/META-INF/braxton/native/" + component(platform) + "/";
        String version;
        try (var input = NativeLibraryLoader.class.getResourceAsStream(prefix + "version.txt")) {
            if (input == null) throw new IOException("missing Braxton native version metadata for " + platform);
            byte[] metadata = input.readNBytes(257);
            if (metadata.length > 256) throw new IOException("invalid Braxton native version metadata");
            version = new String(metadata, java.nio.charset.StandardCharsets.UTF_8).strip();
        }
        try (var input = NativeLibraryLoader.class.getResourceAsStream(prefix + filename)) {
            if (input == null)
                throw new IOException("native library missing for " + platform + "; build Braxton on this platform");
            return new Image(version, platform, filename, input.readAllBytes());
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
        Path directory = Files.createTempDirectory(
                root, "braxton-native-", PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
        Path file = directory.resolve(image.filename);
        Throwable primary = null;
        try {
            write(file, image.bytes);
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
        directory = directory.resolve(image.digest);
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
                    Path staging = Files.createTempFile(
                            directory, ".extract-", ".part", PosixFilePermissions.asFileAttribute(STAGING_MODE));
                    Throwable primary = null;
                    try {
                        write(staging, image.bytes);
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

    private static void write(Path file, byte[] bytes) throws IOException {
        try (var output = FileChannel.open(
                file,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(STAGING_MODE))) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
        Files.setPosixFilePermissions(file, LIBRARY_MODE);
    }

    private static boolean matches(Image image, Path file, Path directory) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        trustedFile(file, directory);
        MessageDigest digest = sha256();
        try (var input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (input.read(buffer) != -1) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return image.digest.equals(HexFormat.of().formatHex(digest.digest()));
    }

    private static void privateDirectory(Path directory) throws IOException {
        try {
            Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
        } catch (FileAlreadyExistsException existing) {
            /* Validate the existing entry below. */
        }
        var attributes = Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || writableByOthers(attributes))
            throw new IOException("unsafe native cache directory: " + directory);
    }

    private static void trustedFile(Path file, Path directory) throws IOException {
        var attributes = Files.readAttributes(file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
                || writableByOthers(attributes)
                || !attributes.owner().equals(Files.getOwner(directory)))
            throw new IOException("unsafe native cache file: " + file);
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

    private static Path propertyPath(String name) throws IOException {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) throw new IOException("missing " + name + " for native extraction");
        try {
            return Path.of(value);
        } catch (java.nio.file.InvalidPathException failure) {
            throw new IOException("invalid " + name + " for native extraction", failure);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }
}

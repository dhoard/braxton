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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class NativeLibraryLoaderTest {
    @TempDir
    Path work;

    private static final byte[] PAYLOAD =
            "complete native image".repeat(8192).getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private NativeLibraryLoader.Image image(String version, byte[] bytes) throws IOException {
        return new NativeLibraryLoader.Image(version, "linux-x86_64", "libbraxton.so", bytes, sha256(bytes));
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IOException(unavailable);
        }
    }

    @Test
    void aTemporarySuccessNeedsNoHomeDirectoryAndRemovesItsOwnFiles() throws Exception {
        Path result = NativeLibraryLoader.load(
                image("1.0.0", PAYLOAD),
                () -> work,
                () -> {
                    throw new AssertionError("home was accessed after a successful temporary load");
                },
                file -> {
                    assertThat(Files.readAllBytes(file)).isEqualTo(PAYLOAD);
                    return file;
                });
        assertThat(result).doesNotExist();
        try (var entries = Files.list(work)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void anOsLoadRejectionFallsBackToAVersionedCacheAndReusesIt() throws Exception {
        Path temporary = Files.createDirectory(work.resolve("tmp"));
        Path root = work.resolve("home/.local/share/braxton");
        var image = image("1.0.0-SNAPSHOT", PAYLOAD);
        NativeLibraryLoader.Link<Path> linker = file -> {
            assertThat(Files.readAllBytes(file)).isEqualTo(PAYLOAD);
            if (file.startsWith(temporary)) throw new IllegalArgumentException("failed to map segment: noexec");
            return file;
        };
        Path first = NativeLibraryLoader.load(image, () -> temporary, () -> root, linker);
        Object inode = Files.readAttributes(first, java.nio.file.attribute.BasicFileAttributes.class)
                .fileKey();
        Path second = NativeLibraryLoader.load(image, () -> temporary, () -> root, linker);
        assertThat(second).isEqualTo(first);
        assertThat(Files.readAttributes(second, java.nio.file.attribute.BasicFileAttributes.class)
                        .fileKey())
                .isEqualTo(inode);
        assertThat(first).startsWith(root.resolve("native/1.0.0-SNAPSHOT/linux-x86_64"));
        assertThat(first.getParent().getFileName().toString()).matches("[0-9a-f]{64}");
        assertThat(Files.getPosixFilePermissions(first)).isEqualTo(PosixFilePermissions.fromString("r-x------"));
        try (var entries = Files.list(temporary)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void anUnusableTemporaryDirectoryAlsoFallsBack() throws Exception {
        Path missing = work.resolve("missing/tmp");
        Path result = NativeLibraryLoader.load(
                image("1.0.0", PAYLOAD), () -> missing, () -> work.resolve("cache"), file -> file);
        assertThat(Files.readAllBytes(result)).isEqualTo(PAYLOAD);
    }

    @Test
    void bothLoadFailuresRemainDiagnosable() throws Exception {
        var temporaryFailure = new UnsatisfiedLinkError("temporary filesystem is noexec");
        var cacheFailure = new IllegalArgumentException("home filesystem is also noexec");
        Path temporary = Files.createDirectory(work.resolve("tmp"));
        assertThatThrownBy(() -> NativeLibraryLoader.load(
                        image("1.0.0", PAYLOAD), () -> temporary, () -> work.resolve("cache"), file -> {
                            if (file.startsWith(temporary)) throw temporaryFailure;
                            throw cacheFailure;
                        }))
                .isInstanceOf(IOException.class)
                .hasCause(cacheFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(temporaryFailure));
    }

    @Test
    void permissionDenialIsNotConvertedIntoAStorageFallback() throws Exception {
        assertThatThrownBy(() -> NativeLibraryLoader.load(
                        image("1.0.0", PAYLOAD),
                        () -> work,
                        () -> {
                            throw new AssertionError("native-access denial must not fall back");
                        },
                        file -> {
                            throw new IllegalCallerException("native access denied");
                        }))
                .isInstanceOf(IllegalCallerException.class);
    }

    @Test
    void releasesPlatformsAndBuildHashesHaveSeparatePaths() throws Exception {
        Path root = work.resolve("cache");
        Path first = NativeLibraryLoader.cached(image("1.0.0", PAYLOAD), root);
        Path laterRelease = NativeLibraryLoader.cached(image("2.0.0", PAYLOAD), root);
        byte[] other = "different build of the same release".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path laterBuild = NativeLibraryLoader.cached(image("1.0.0", other), root);
        Path otherPlatform = NativeLibraryLoader.cached(
                new NativeLibraryLoader.Image("1.0.0", "macos-aarch64", "libbraxton.dylib", PAYLOAD, sha256(PAYLOAD)),
                root);
        assertThat(List.of(first, laterRelease, laterBuild, otherPlatform)).doesNotHaveDuplicates();
        assertThat(Files.readAllBytes(first)).isEqualTo(PAYLOAD);
        assertThat(Files.readAllBytes(laterRelease)).isEqualTo(PAYLOAD);
        assertThat(Files.readAllBytes(laterBuild)).isEqualTo(other);
        assertThat(Files.readAllBytes(otherPlatform)).isEqualTo(PAYLOAD);
    }

    @Test
    void corruptRegularEntriesAreRepairedAndAbandonedStagingIsNeverLoaded() throws Exception {
        Path root = work.resolve("cache");
        var image = image("1.0.0", PAYLOAD);
        Path file = NativeLibraryLoader.cached(image, root);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.writeString(file, "interrupted or corrupted file");
        Path abandoned = file.resolveSibling(".extract-abandoned.part");
        Files.writeString(abandoned, "partial image");
        assertThat(NativeLibraryLoader.cached(image, root)).isEqualTo(file);
        assertThat(Files.readAllBytes(file)).isEqualTo(PAYLOAD);
        assertThat(Files.readString(abandoned)).isEqualTo("partial image");
    }

    @Test
    void concurrentThreadsPublishOneCompleteReusableEntry() throws Exception {
        var image = image("1.0.0", PAYLOAD);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<Path>>();
            for (int index = 0; index < 8; index++)
                futures.add(pool.submit(() -> {
                    start.await();
                    Path result = NativeLibraryLoader.cached(image, work.resolve("cache"));
                    assertThat(Files.readAllBytes(result)).isEqualTo(PAYLOAD);
                    return result;
                }));
            start.countDown();
            Path expected = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (var future : futures)
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            try (var entries = Files.list(expected.getParent())) {
                assertThat(entries.map(path -> path.getFileName().toString()).toList())
                        .containsExactlyInAnyOrder("libbraxton.so", ".extract.lock");
            }
        }
    }

    @Test
    void theRecordedHashNamesTheCacheEntryAndReuseRequiresIdenticalBytes() throws Exception {
        Path root = work.resolve("cache");
        String recorded = "0123456789abcdef".repeat(4);
        var image = new NativeLibraryLoader.Image("1.0.0", "linux-x86_64", "libbraxton.so", PAYLOAD, recorded);
        Path file = NativeLibraryLoader.cached(image, root);
        assertThat(file.getParent().getFileName().toString()).isEqualTo(recorded);
        assertThat(Files.readAllBytes(file)).isEqualTo(PAYLOAD);
        byte[] altered = PAYLOAD.clone();
        altered[altered.length / 2] ^= 0x01;
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        Files.write(file, altered);
        assertThat(NativeLibraryLoader.cached(image, root)).isEqualTo(file);
        assertThat(Files.readAllBytes(file)).isEqualTo(PAYLOAD);
    }

    @Test
    void temporaryDirectoriesAreExclusiveAndNeverAdoptAnExistingEntry() throws Exception {
        Path root = Files.createDirectory(work.resolve("tmp"));
        String suffix = "fixed";
        Path foreign = Files.createDirectory(
                root.resolve("braxton-native-" + suffix + "-0"),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-x---")));
        var foreignMode = Files.getPosixFilePermissions(foreign);
        Path linked =
                Files.createSymbolicLink(root.resolve("braxton-native-" + suffix + "-1"), work.resolve("elsewhere"));
        Path created = NativeLibraryLoader.privateTemporaryDirectory(root, suffix);
        assertThat(created.getFileName().toString()).isEqualTo("braxton-native-" + suffix + "-2");
        assertThat(Files.getPosixFilePermissions(created)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(foreign))
                .as("a foreign entry keeps its own mode")
                .isEqualTo(foreignMode);
        assertThat(Files.isSymbolicLink(linked)).isTrue();
    }

    @Test
    void exhaustedTemporaryNamesFailWithoutAdoptingAnyEntry() throws Exception {
        Path root = Files.createDirectory(work.resolve("tmp"));
        String suffix = "fixed";
        for (int attempt = 0; attempt < NativeLibraryLoader.TEMPORARY_ATTEMPTS; attempt++)
            Files.createDirectory(
                    root.resolve("braxton-native-" + suffix + "-" + attempt),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        assertThatThrownBy(() -> NativeLibraryLoader.privateTemporaryDirectory(root, suffix))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("temporary directory");
    }

    @Test
    void defaultTemporaryNamesAreUnpredictableAndUnique() throws Exception {
        Path root = Files.createDirectory(work.resolve("tmp"));
        Path first = NativeLibraryLoader.privateTemporaryDirectory(root);
        Path second = NativeLibraryLoader.privateTemporaryDirectory(root);
        assertThat(first).isNotEqualTo(second);
        assertThat(first.getFileName().toString()).matches("braxton-native-[0-9a-f]{1,16}-0");
    }

    @Test
    void unsafeMetadataNeverBecomesACachePath() {
        for (String value : new String[] {"", ".", "..", "../other", "/outside", "1/2", "x\ny", "a".repeat(129)}) {
            assertThatThrownBy(() -> image(value, PAYLOAD)).isInstanceOf(IOException.class);
        }
        for (String hash : new String[] {null, "", "abc", "z".repeat(64), "A".repeat(64), "a".repeat(63)}) {
            assertThatThrownBy(() ->
                            new NativeLibraryLoader.Image("1.0.0", "linux-x86_64", "libbraxton.so", PAYLOAD, hash))
                    .isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> image("1.0.0", new byte[0])).isInstanceOf(IOException.class);
    }

    @Test
    void symlinkAndOtherUserWritableCacheEntriesAreRejected() throws Exception {
        Path actual = Files.createDirectory(work.resolve("actual"));
        Path symlink = Files.createSymbolicLink(work.resolve("linked"), actual);
        var image = image("1.0.0", PAYLOAD);
        assertThatThrownBy(() -> NativeLibraryLoader.cached(image, symlink)).isInstanceOf(IOException.class);
        Path file = NativeLibraryLoader.cached(image, work.resolve("cache"));
        Files.delete(file);
        Path outside = Files.writeString(work.resolve("outside"), "unchanged");
        Files.createSymbolicLink(file, outside);
        assertThatThrownBy(() -> NativeLibraryLoader.cached(image, work.resolve("cache")))
                .isInstanceOf(IOException.class);
        assertThat(Files.readString(outside)).isEqualTo("unchanged");
        Files.delete(file);
        Files.write(file, PAYLOAD);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"));
        assertThatThrownBy(() -> NativeLibraryLoader.cached(image, work.resolve("cache")))
                .isInstanceOf(IOException.class);
        Files.setPosixFilePermissions(actual, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThatThrownBy(() -> NativeLibraryLoader.cached(image, actual)).isInstanceOf(IOException.class);
    }

    @Test
    void packagedVersionMatchesTheLibraryBuildRatherThanAConsumerManifest() throws Exception {
        String platform = NativeTerminal.platform();
        String extension = platform.startsWith("macos") ? "dylib" : "so";
        byte[] packaged;
        try (var input =
                getClass().getResourceAsStream("/META-INF/braxton/native/" + platform + "/libbraxton." + extension)) {
            assertThat(input).isNotNull();
            packaged = input.readAllBytes();
        }
        var image = NativeLibraryLoader.packaged(platform);
        Path result = NativeLibraryLoader.cached(image, work.resolve("cache"));
        String pom = Files.readString(Path.of("pom.xml"));
        var revision =
                java.util.regex.Pattern.compile("<revision>([^<]+)</revision>").matcher(pom);
        assertThat(revision.find()).isTrue();
        assertThat(result)
                .startsWith(
                        work.resolve("cache/native").resolve(revision.group(1)).resolve(platform));
        assertThat(result.getParent().getFileName().toString())
                .as("recorded content hash describes the packaged binary")
                .isEqualTo(sha256(packaged));
    }
}

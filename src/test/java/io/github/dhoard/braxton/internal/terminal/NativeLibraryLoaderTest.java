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
        return new NativeLibraryLoader.Image(version, "linux-x86_64", "libbraxton.so", bytes);
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
                new NativeLibraryLoader.Image("1.0.0", "macos-aarch64", "libbraxton.dylib", PAYLOAD), root);
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
    void unsafeMetadataNeverBecomesACachePath() {
        for (String value : new String[] {"", ".", "..", "../other", "/outside", "1/2", "x\ny", "a".repeat(129)}) {
            assertThatThrownBy(() -> image(value, PAYLOAD)).isInstanceOf(IOException.class);
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
        var image = NativeLibraryLoader.packaged(platform);
        Path result = NativeLibraryLoader.cached(image, work.resolve("cache"));
        String pom = Files.readString(Path.of("pom.xml"));
        var revision =
                java.util.regex.Pattern.compile("<revision>([^<]+)</revision>").matcher(pom);
        assertThat(revision.find()).isTrue();
        assertThat(result)
                .startsWith(
                        work.resolve("cache/native").resolve(revision.group(1)).resolve(platform));
    }
}

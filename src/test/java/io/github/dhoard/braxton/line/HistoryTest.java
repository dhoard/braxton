/*
 * Copyright (c) 2026-present Douglas Hoard
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

package io.github.dhoard.braxton.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The history store: what it holds, what it writes, and what two writers appending at once leave
 * behind. Every file is an owned temporary one.
 */
class HistoryTest {

    @TempDir
    Path home;

    @Test
    void aFileThatDoesNotExistYetIsAnEmptyHistory() throws IOException {
        Path file = home.resolve("history");

        History history = History.load(file);

        assertThat(history.entries()).isEmpty();
        assertThat(history.isEmpty()).isTrue();
        assertThat(history.size()).isZero();
        assertThat(history.mostRecent()).isEmpty();
        assertThat(history.limit()).isEqualTo(History.DEFAULT_LIMIT);
        assertThat(history.file()).contains(file);
        assertThat(history.toString()).contains("entries=0");
        assertThat(Files.exists(file)).as("a load creates nothing").isFalse();
    }

    @Test
    void aRecordedLineIsPersistedAndReloaded() throws IOException {
        Path file = home.resolve("nested/history");

        History recorded = History.load(file).recorded("make test");

        assertThat(recorded.entries()).containsExactly("make test");
        assertThat(recorded.mostRecent()).contains("make test");
        assertThat(recorded.size()).isEqualTo(1);
        assertThat(History.load(file).entries()).containsExactly("make test");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("make test\n");
    }

    @Test
    void nothingIsRecordedForABlankLineWhichLeavesTheFileAlone() throws IOException {
        Path file = home.resolve("history");
        History history = History.load(file);

        assertThat(history.recorded("")).isSameAs(history);
        assertThat(history.recorded("   ")).isSameAs(history);
        assertThat(history.recorded("\r\n")).isSameAs(history);
        assertThat(history.recorded(null)).isSameAs(history);
        assertThat(Files.exists(file)).as("a blank line writes no file").isFalse();
    }

    @Test
    void aRecordedLineIsPreparedAsOneRow() throws IOException {
        Path file = home.resolve("history");

        History recorded = History.load(file).recorded("a\tb\r\nc\u0007d");

        assertThat(recorded.entries()).containsExactly("a b c d");
        assertThat(History.load(file).entries()).containsExactly("a b c d");
    }

    @Test
    void aStoredValueIsOneRowPerEntryAndAnEmptyRowIsSkipped() throws IOException {
        Path file = home.resolve("history");
        Files.write(file, "one\r\ntwo\r\n\r\nthree\u0000four\n".getBytes(StandardCharsets.UTF_8));

        assertThat(History.load(file).entries()).containsExactly("one", "two", "three four");
    }

    @Test
    void aMalformedByteDoesNotFailTheLoad() throws IOException {
        Path file = home.resolve("history");
        Files.write(file, new byte[] {'a', (byte) 0xff, 'b', '\n'});

        assertThat(History.load(file).entries()).containsExactly("a\uFFFDb");
    }

    @Test
    void aLoadKeepsTheNewestEntriesWithinTheBound() throws IOException {
        Path file = home.resolve("history");
        Files.writeString(file, "one\ntwo\nthree\n", StandardCharsets.UTF_8);

        assertThat(History.load(file, 2).entries()).containsExactly("two", "three");
        assertThat(History.load(file, 2).limit()).isEqualTo(2);
        assertThat(History.load(file).entries()).containsExactly("one", "two", "three");
    }

    @Test
    void theOldestEntriesAreDroppedWhenTheBoundIsReached() throws IOException {
        Path file = home.resolve("history");

        History history = History.load(file, 2).recorded("one").recorded("two").recorded("three");

        assertThat(history.entries()).containsExactly("two", "three");
        assertThat(History.load(file, 2).entries()).containsExactly("two", "three");
    }

    @Test
    void anAppendIncludesALineAnotherWriterPublishedFirst() throws IOException {
        Path file = home.resolve("history");
        // Two values loaded before either wrote: each one appends to the file as it is now, not to
        // the list it was loaded with, so neither overwrites the other.
        History mine = History.load(file);
        History theirs = History.load(file);

        History theirAnswer = theirs.recorded("theirs");
        History myAnswer = mine.recorded("mine");

        assertThat(theirAnswer.entries()).containsExactly("theirs");
        assertThat(myAnswer.entries()).containsExactly("theirs", "mine");
        assertThat(History.load(file).entries()).containsExactly("theirs", "mine");
    }

    @Test
    void concurrentWritersLoseNoEntry() throws Exception {
        Path file = home.resolve("history");
        int writers = 4;
        int perWriter = 20;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> runs = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                int index = writer;
                runs.add(pool.submit(() -> {
                    start.await();
                    History mine = History.load(file, writers * perWriter);
                    for (int line = 0; line < perWriter; line++) {
                        mine = mine.recorded("w" + index + "-" + line);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> run : runs) {
                run.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> entries = History.load(file, writers * perWriter).entries();

        assertThat(entries).hasSize(writers * perWriter);
        assertThat(new HashSet<>(entries)).as("no line was written twice").hasSize(writers * perWriter);
        for (int writer = 0; writer < writers; writer++) {
            List<String> mine = new ArrayList<>();
            for (String entry : entries) {
                if (entry.startsWith("w" + writer + "-")) {
                    mine.add(entry);
                }
            }
            List<String> expected = new ArrayList<>();
            for (int line = 0; line < perWriter; line++) {
                expected.add("w" + writer + "-" + line);
            }
            assertThat(mine)
                    .as("writer %d's lines, in the order it recorded them", writer)
                    .isEqualTo(expected);
        }
    }

    @Test
    void theFileAndItsLockAreReadableOnlyByTheirOwnerAndNoStagingFileIsLeft() throws IOException {
        Path file = home.resolve("history");

        History.load(file).recorded("a secret typed by mistake");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        Path lock = home.resolve("history.lock");
        assertThat(Files.exists(lock)).isTrue();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(lock)))
                .isEqualTo("rw-------");
        try (Stream<Path> left = Files.list(home)) {
            assertThat(left.map(path -> path.getFileName().toString()))
                    .as("a published append leaves no staging file behind")
                    .noneMatch(name -> name.endsWith(".part"));
        }
    }

    @Test
    void aTargetThatCannotBeCreatedFailsWithAnIOException() throws IOException {
        Path blocker = home.resolve("blocker");
        Files.writeString(blocker, "not a directory", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> History.load(blocker.resolve("history")).recorded("one"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void aMissingFileAndAnImpossibleBoundAreRejected() {
        assertThatThrownBy(() -> History.load(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> History.load(home.resolve("history"), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> History.load(home.resolve("history"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSessionOnlyHistoryKeepsItsEntriesAndWritesNothing() throws IOException {
        History history = History.inMemory();
        History recorded = history.recorded("one").recorded("two");

        assertThat(history.file()).isEmpty();
        assertThat(history.entries()).isEmpty();
        assertThat(recorded.file()).isEmpty();
        assertThat(recorded.entries()).containsExactly("one", "two");
        assertThat(recorded.limit()).isEqualTo(History.DEFAULT_LIMIT);
        assertThat(recorded.toString()).contains("file=session");
        assertThat(home.toFile().list()).as("a session history writes nothing").isEmpty();
    }

    @Test
    void aHistoryCanBeGivenLinesAnApplicationAlreadyHas() {
        History history = History.of(List.of("one", "", "two\r\nthree"));

        assertThat(history.entries()).containsExactly("one", "two three");
        assertThat(history.file()).isEmpty();
        assertThat(History.of(null).entries()).isEmpty();
        assertThat(History.of(List.of()).entries()).isEmpty();
    }
}

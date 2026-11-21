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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The lines an application has accepted, oldest first, as an immutable value that can persist itself.
 *
 * <p>A history is the list a {@link LineEditor} searches and the file an application keeps between
 * runs. The value is immutable: {@link #recorded(String)} answers the history that includes the new
 * line and leaves the one it was called on alone, so an application can hold the answer in its model
 * and keep the previous value for a frame that has not committed yet. Reads are a load and a value;
 * the only method that writes is {@code recorded}, and only when the history names a file.</p>
 *
 * <h2>The file</h2>
 *
 * <p>One one-row entry per line, UTF-8, with a terminating newline. {@link #load(Path)} prepares
 * every entry the same way an editor prepares text - line breaks become spaces, other control
 * characters become spaces, and blank lines are dropped - so a file edited by hand or written by a
 * previous version cannot put an escape sequence or a second row into a painted cell, and a
 * malformed byte sequence is replaced rather than failing the load. The file and its lock are
 * created readable and writable by their owner alone, because a history holds what a user typed,
 * which may be a secret typed into a prompt by mistake.</p>
 *
 * <h2>Concurrent writers</h2>
 *
 * <p>Two processes appending at the same time must not lose each other's lines, so an append is
 * read-modify-write under an exclusive lock: it opens a sibling {@code <name>.lock} file, takes a
 * lock on it, re-reads the history, adds the line, drops the oldest entries beyond the bound, and
 * publishes the result by writing a staging file in the same directory and moving it into place with
 * {@link StandardCopyOption#ATOMIC_MOVE}. A reader therefore sees either the previous complete file
 * or the new complete file, never a half-written one, and no line another writer published in the
 * meantime is overwritten. The lock lives in its own file rather than on the history itself, because
 * the move replaces the history's inode: a lock on a replaced file would no longer exclude a writer
 * that arrived after the move. Writers within one JVM are serialized by a monitor first, because a
 * POSIX lock belongs to the process and a second lock from the same process is reported as a
 * failure rather than waited for. A reader that only needs a snapshot takes no lock, so
 * {@link #load(Path)} never blocks an append.</p>
 *
 * <p>The lock does not coordinate with an unrelated program that edits the same file without it, and
 * it assumes the file system honors {@code atomically move a file within a directory}, which Linux
 * and macOS both do. Nothing is retained across processes: a crashed writer leaves its staging file
 * behind, which costs one directory entry and is never adopted.</p>
 *
 * <h2>Session-only history</h2>
 *
 * <p>{@link #inMemory()} is a history with no file: it holds its entries, {@code recorded} answers a
 * new value, and nothing is written anywhere. It is what an editor searches until an application
 * gives it a persistent history, and it is what an application that deliberately keeps no record of
 * what its user typed uses instead.</p>
 */
public final class History {

    /**
     * The number of entries a history keeps unless the caller asks for another bound.
     */
    public static final int DEFAULT_LIMIT = 1000;

    /**
     * A history file is the user's own text, so only the user reads or writes it.
     */
    private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    private final Path file;
    private final int limit;
    private final List<String> entries;

    private History(Path file, int limit, List<String> entries) {
        this.file = file;
        this.limit = limit;
        this.entries = List.copyOf(entries);
    }

    /**
     * A history that holds its entries for this session only and persists nothing.
     *
     * @return the empty history
     */
    public static History inMemory() {
        return new History(null, DEFAULT_LIMIT, List.of());
    }

    /**
     * A session-only history holding lines an application already has.
     *
     * <p>For an application whose history lives somewhere other than this store - a session log, a
     * database, a value it was started with - this is the whole of what a search needs, and nothing
     * is written anywhere. Each line is prepared as one row and a blank one is skipped, and the
     * newest {@link #DEFAULT_LIMIT} are kept so that a history never holds more than it says it
     * does.</p>
     *
     * @param lines the lines, newest last; null is no lines
     * @return the history
     */
    public static History of(List<String> lines) {
        List<String> entries = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                String entry = LineText.singleRow(line);
                if (!entry.isBlank()) {
                    entries.add(entry);
                }
            }
        }
        return new History(null, DEFAULT_LIMIT, bound(entries, DEFAULT_LIMIT));
    }

    /**
     * The history a file holds, bounded by {@link #DEFAULT_LIMIT}.
     *
     * <p>A file that does not exist yet is an empty history rather than an error, so an application
     * can point at the file it wants before the first line has been recorded.</p>
     *
     * @param file the file to read; required
     * @return the history, newest {@code DEFAULT_LIMIT} entries kept
     * @throws IOException when the file exists but cannot be read
     */
    public static History load(Path file) throws IOException {
        return load(file, DEFAULT_LIMIT);
    }

    /**
     * The history a file holds, with a bound on how many entries are kept.
     *
     * @param file the file to read; required
     * @param limit the number of entries to keep, at least one; the oldest are dropped
     * @return the history
     * @throws IOException when the file exists but cannot be read
     * @throws IllegalArgumentException when the bound is smaller than one
     */
    public static History load(Path file, int limit) throws IOException {
        Objects.requireNonNull(file, "file");
        if (limit < 1) {
            throw new IllegalArgumentException("a history keeps at least one entry");
        }
        return new History(file, limit, bound(read(file), limit));
    }

    /**
     * The file this history persists to, or empty when it persists nothing.
     *
     * @return the file this history persists to, or empty when it persists nothing
     */
    public Optional<Path> file() {
        return Optional.ofNullable(file);
    }

    /**
     * The number of entries this history keeps.
     *
     * @return the number of entries this history keeps
     */
    public int limit() {
        return limit;
    }

    /**
     * Every entry, oldest first; immutable.
     *
     * @return every entry, oldest first; immutable
     */
    public List<String> entries() {
        return entries;
    }

    /**
     * The number of entries held.
     *
     * @return the number of entries held
     */
    public int size() {
        return entries.size();
    }

    /**
     * Whether nothing has been recorded.
     *
     * @return {@code true} if nothing has been recorded; otherwise {@code false}
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * The newest entry, which is the one an empty search shows first.
     *
     * @return the newest entry, or empty when nothing has been recorded
     */
    public Optional<String> mostRecent() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(entries.size() - 1));
    }

    /**
     * The history that includes one more line.
     *
     * <p>The line is prepared like text an editor accepts, and a blank one is not recorded at all: an
     * empty command is nothing to recall, and a bound spent on it would push a real line out. When
     * this history names a file, the line is published durably before this method returns, and the
     * answer includes every entry another writer published in the meantime, so an application that
     * keeps the answer never shows a stale list.</p>
     *
     * @param line the line to record; null or blank records nothing
     * @return this instance when nothing was recorded, otherwise the history that includes the line
     * @throws IOException when the history names a file that cannot be written
     */
    public History recorded(String line) throws IOException {
        String entry = LineText.singleRow(line);
        if (entry.isBlank()) {
            return this;
        }
        if (file == null) {
            return new History(null, limit, bound(appended(entries, entry), limit));
        }
        Path target = file.toAbsolutePath();
        Path directory = target.getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path lock = lockFile(target);
        synchronized (monitor(target)) {
            try (FileChannel channel = FileChannel.open(
                            lock, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE), OWNER_ONLY);
                    FileLock held = channel.lock()) {
                if (!held.isValid()) {
                    throw new IOException("history lock is not held: " + lock);
                }
                // Re-read under the lock: the answer is the file as it is now, not as it was when
                // this value was loaded, so a line another writer published is never overwritten.
                List<String> merged = bound(appended(read(target), entry), limit);
                publish(target, merged);
                return new History(file, limit, merged);
            }
        }
    }

    /**
     * Diagnostic text naming the file and the entries held, without printing entry contents.
     */
    @Override
    public String toString() {
        return "History[file=" + (file == null ? "session" : file) + ", entries=" + entries.size() + "/" + limit + "]";
    }

    /**
     * A monitor shared by every writer of one history file in this JVM.
     *
     * <p>A POSIX lock belongs to the process, so a second {@link FileChannel#lock()} from the same
     * process is refused instead of waiting for the first to be released. Interning the key is what
     * makes the monitor the same object for two {@code History} values that name one file.</p>
     */
    private static Object monitor(Path target) {
        return (History.class.getName() + ":" + target).intern();
    }

    /**
     * The sibling lock file. It is never replaced and never deleted.
     */
    private static Path lockFile(Path target) {
        return target.resolveSibling(target.getFileName() + ".lock");
    }

    /**
     * The entries a file holds.
     *
     * <p>Read leniently: a byte sequence that is not valid UTF-8 is replaced, because a history is a
     * convenience and a truncated character in it must not keep an editor from starting. Every entry
     * is prepared as one row and a blank one is skipped.</p>
     */
    private static List<String> read(Path file) throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException missing) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String line : LineText.splitRows(new String(bytes, StandardCharsets.UTF_8))) {
            String entry = LineText.singleRow(line);
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Publishes a complete history file, atomically.
     *
     * <p>The content goes to an owner-only staging file in the destination directory, is forced to
     * stable storage, and is then moved onto the history, so a concurrent reader sees one complete
     * history or the other and never a partial one. A failed write leaves the previous history
     * untouched and its staging file removed.</p>
     */
    private static void publish(Path file, List<String> entries) throws IOException {
        StringBuilder content = new StringBuilder();
        for (String entry : entries) {
            content.append(entry).append('\n');
        }
        Path directory = file.getParent();
        Path staging = Files.createTempFile(directory, ".history-", ".part", OWNER_ONLY);
        Throwable primary = null;
        try {
            try (FileChannel channel = FileChannel.open(staging, Set.of(StandardOpenOption.WRITE))) {
                ByteBuffer buffer = ByteBuffer.wrap(content.toString().getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException failure) {
                if (primary != null) {
                    primary.addSuppressed(failure);
                } else {
                    throw failure;
                }
            }
        }
    }

    private static List<String> appended(List<String> entries, String entry) {
        List<String> next = new ArrayList<>(entries.size() + 1);
        next.addAll(entries);
        next.add(entry);
        return next;
    }

    /**
     * The newest {@code limit} entries.
     */
    private static List<String> bound(List<String> entries, int limit) {
        return entries.size() <= limit ? entries : entries.subList(entries.size() - limit, entries.size());
    }
}

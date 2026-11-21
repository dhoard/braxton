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

package io.github.dhoard.braxton.widget;

import static io.github.dhoard.braxton.widget.FilePicker.LISTING;
import static io.github.dhoard.braxton.widget.FilePicker.ORDER;
import static io.github.dhoard.braxton.widget.FilePicker.VALIDATION;

import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.widget.FilePicker.Entry;
import io.github.dhoard.braxton.widget.FilePicker.Kind;
import io.github.dhoard.braxton.widget.FilePicker.Listing;
import io.github.dhoard.braxton.widget.FilePicker.Msg;
import io.github.dhoard.braxton.widget.FilePicker.Reason;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Bounded directory reads and path validation effects.
 */
final class FilePickerIo {

    /**
     * The task that reads one directory.
     *
     * <p>The listing is registered under one id, so starting the next request replaces it, and the
     * result carries the epoch it answers so a superseded one is discarded even if it was already
     * on its way.</p>
     */
    static Command<Msg> listing(Path directory, long epoch, FilePickerConfig config) {
        return Command.task(
                LISTING,
                () -> read(directory, epoch, config),
                listing -> new Msg.Listed(
                        listing.epoch(), listing.directory(), listing.root(), listing.entries(), listing.truncated()),
                failure -> new Msg.Failed(epoch, directory, message(failure)));
    }

    /**
     * The task that re-checks one confirmed entry without reading its contents.
     */
    static Command<Msg> validation(Path path, long epoch, FilePickerConfig config) {
        return Command.task(
                VALIDATION,
                () -> check(path, epoch, config),
                checked -> checked,
                failure -> new Msg.Validated(epoch, path, Reason.MISSING));
    }

    /**
     * Reads one directory into entries.
     *
     * <p>A listing never throws for an entry it cannot describe: an entry that cannot be read is
     * reported as {@link Kind#MISSING} and remains listed, because a directory that omits what the
     * reader cannot open is more confusing than one that marks it. The stream is closed here, the
     * loop stops at the configured maximum, and an interrupt - which is how the runtime cancels a
     * replaced task - ends the read before it can publish anything.</p>
     */
    static Listing read(Path target, long epoch, FilePickerConfig config) throws IOException {
        Path real = target.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new NotDirectoryException(real.toString());
        }
        Path root = config.root() == null ? null : config.root().toRealPath();
        if (root != null && !real.startsWith(root)) {
            throw new IOException(real + " is outside the picker's root " + root);
        }
        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(real)) {
            for (Path child : stream) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("listing interrupted: " + real);
                }
                if (entries.size() >= config.maxEntries()) {
                    truncated = true;
                    break;
                }
                entries.add(entry(child, real, root, config));
            }
        }
        entries.sort(ORDER);
        return new Listing(epoch, real, root, List.copyOf(entries), truncated);
    }

    /**
     * One entry, with the facts a listing can read without reading the entry itself.
     */
    static Entry entry(Path path, Path directory, Path root, FilePickerConfig config) {
        boolean link = Files.isSymbolicLink(path);
        String target = link ? linkTarget(path) : "";
        BasicFileAttributes facts = facts(path);
        String permissions = permissions(path);
        if (facts == null) {
            return new Entry(path, Kind.MISSING, -1L, permissions, target, false, false);
        }
        Kind kind = facts.isDirectory() ? Kind.DIRECTORY : facts.isRegularFile() ? Kind.FILE : Kind.OTHER;
        boolean navigable =
                kind == Kind.DIRECTORY && (!link || (config.followLinks() && reachable(path, directory, root)));
        boolean selectable = switch (kind) {
            case FILE ->
                config.selects(Kind.FILE) && config.allows(path.getFileName().toString());
            case DIRECTORY -> navigable && config.selects(Kind.DIRECTORY);
            default -> false;
        };
        long size = kind == Kind.FILE ? facts.size() : -1L;
        return new Entry(path, kind, size, permissions, target, selectable, navigable);
    }

    /**
     * A link's own text, or empty when it cannot be read.
     */
    static String linkTarget(Path path) {
        try {
            return Files.readSymbolicLink(path).toString();
        } catch (IOException | UnsupportedOperationException notReadable) {
            return "";
        }
    }

    /**
     * An entry's attributes with links followed, or null when the entry cannot be read.
     */
    static BasicFileAttributes facts(Path path) {
        try {
            try {
                return Files.readAttributes(path, PosixFileAttributes.class);
            } catch (UnsupportedOperationException noPosix) {
                return Files.readAttributes(path, BasicFileAttributes.class);
            }
        } catch (IOException unreadable) {
            // A dangling link, or a target this process may not stat: listed, never chosen.
            return null;
        }
    }

    /**
     * The nine-character permission string, or empty when the filesystem has none.
     */
    static String permissions(Path path) {
        try {
            return render(Files.getPosixFilePermissions(path));
        } catch (IOException | UnsupportedOperationException unreadable) {
            try {
                return render(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
            } catch (IOException | UnsupportedOperationException alsoUnreadable) {
                return "";
            }
        }
    }

    /**
     * The POSIX permissions as {@code rwxr-xr-x}.
     */
    static String render(Set<PosixFilePermission> granted) {
        StringBuilder out = new StringBuilder(9);
        out.append(granted.contains(PosixFilePermission.OWNER_READ) ? 'r' : '-');
        out.append(granted.contains(PosixFilePermission.OWNER_WRITE) ? 'w' : '-');
        out.append(granted.contains(PosixFilePermission.OWNER_EXECUTE) ? 'x' : '-');
        out.append(granted.contains(PosixFilePermission.GROUP_READ) ? 'r' : '-');
        out.append(granted.contains(PosixFilePermission.GROUP_WRITE) ? 'w' : '-');
        out.append(granted.contains(PosixFilePermission.GROUP_EXECUTE) ? 'x' : '-');
        out.append(granted.contains(PosixFilePermission.OTHERS_READ) ? 'r' : '-');
        out.append(granted.contains(PosixFilePermission.OTHERS_WRITE) ? 'w' : '-');
        out.append(granted.contains(PosixFilePermission.OTHERS_EXECUTE) ? 'x' : '-');
        return out.toString();
    }

    /**
     * Whether a linked directory may be entered: it must stay inside the root and must not lead
     * back to the directory that holds it or to one of that directory's ancestors.
     */
    static boolean reachable(Path path, Path directory, Path root) {
        try {
            Path real = path.toRealPath();
            if (root != null && !real.startsWith(root)) {
                return false;
            }
            return !directory.startsWith(real);
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * The re-check of one confirmed entry: still there, still the kind the definition selects, still
     * inside the root, and still allowed by the extension policy.
     *
     * <p>It reads the entry's own attributes and never its contents, so checking a gigabyte and
     * checking a byte cost the same. Everything it decides is what the listing decided, taken again
     * from the filesystem as it is now - which is the only way a directory that changed under the
     * reader can be reported truthfully.</p>
     */
    static Msg check(Path path, long epoch, FilePickerConfig config) {
        BasicFileAttributes facts = facts(path);
        if (facts == null) {
            return new Msg.Validated(epoch, path, Reason.MISSING);
        }
        if (config.root() != null && escapes(path, config)) {
            return new Msg.Validated(epoch, path, Reason.LINK);
        }
        if (facts.isDirectory()) {
            return new Msg.Validated(epoch, path, config.selects(Kind.DIRECTORY) ? null : Reason.KIND);
        }
        if (!facts.isRegularFile() || !config.selects(Kind.FILE)) {
            return new Msg.Validated(epoch, path, Reason.KIND);
        }
        return new Msg.Validated(epoch, path, config.allows(FilePickerRendering.name(path)) ? null : Reason.EXTENSION);
    }

    /**
     * Whether an entry now resolves outside the configured root.
     */
    static boolean escapes(Path path, FilePickerConfig config) {
        try {
            return !path.toRealPath().startsWith(config.root().toRealPath());
        } catch (IOException unreadable) {
            return true;
        }
    }

    /**
     * A failure as one status-row phrase.
     */
    static String message(Throwable failure) {
        return switch (failure) {
            case AccessDeniedException denied -> "permission denied: " + denied.getFile();
            case NoSuchFileException missing -> "no such directory: " + missing.getFile();
            case NotDirectoryException notDirectory -> "not a directory: " + notDirectory.getFile();
            case InterruptedIOException interrupted -> "canceled: " + interrupted.getMessage();
            case FileSystemException files -> {
                String reason = files.getReason();
                yield reason == null || reason.isBlank() ? files.getFile() + ": cannot be read" : reason;
            }
            default -> {
                String detail = failure.getMessage();
                yield detail == null || detail.isBlank() ? failure.getClass().getSimpleName() : detail;
            }
        };
    }
}

# Native terminal service

Braxton's native terminal service is original project code under the root MIT license.
`terminal.c` implements only the POSIX operations needed by the MVU runtime. It is not a
translation of another terminal library. Java calls an opaque context through FFM; the C
compiler and target SDK supply platform layouts and constants.

The implementation was written against Braxton's existing runtime/parser tests and these
public interface specifications, without consulting third-party implementation source:

- [POSIX terminal interface](https://pubs.opengroup.org/onlinepubs/9799919799/basedefs/termios.h.html)
- [Linux terminal attributes](https://man7.org/linux/man-pages/man3/termios.3.html)
- [POSIX/Linux polling](https://man7.org/linux/man-pages/man2/poll.2.html)
- [POSIX open](https://pubs.opengroup.org/onlinepubs/9799919799/functions/open.html)
- [POSIX terminal device names](https://pubs.opengroup.org/onlinepubs/9799919799/functions/ttyname.html)
- [POSIX file identity](https://pubs.opengroup.org/onlinepubs/9799919799/functions/fstat.html)
- [Monotonic time](https://pubs.opengroup.org/onlinepubs/9799919799/functions/clock_gettime.html)
- [Signal actions](https://man7.org/linux/man-pages/man2/sigaction.2.html)
- [POSIX asynchronous signal safety](https://pubs.opengroup.org/onlinepubs/9799919799/functions/V2_chap02.html)
- [Terminal dimensions](https://man7.org/linux/man-pages/man2/TIOCGWINSZ.2const.html)
- [Java 25 FFM](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/package-summary.html)
- Target operating-system C headers for declarations, layouts and constants, including the
  [Apple terminal ioctl declarations](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/ttycom.h).

The sibling Bubble Tea project's README was consulted for general MVU behavior and its
`go.mod` for dependency boundaries. Its implementation files were not used. Braxton retains
its own typed component tree, views, routing, effects, subscriptions and runtime semantics.
This provenance record describes this replacement; it does not certify a formally separated
clean-room process or retrospectively establish provenance for all existing project files.

The ABI version is 1. `bt_open` duplicates the attached input descriptor and reopens the
output terminal with a private nonblocking open file description, snapshots input terminal
state and returns an opaque context. The reopened output is checked against the original
character device's device/inode identity; symlinks are rejected. The terminal device path must
be accessible for reopening. Failure happens before raw mode or application output modes are
entered. Unlike setting `O_NONBLOCK` on a duplicate, reopening does not change the caller's
stdout flags (duplicates share those flags). `bt_raw` applies raw mode. `bt_read` polls for a
bounded interval and reads into a caller-owned buffer. `bt_write` handles partial writes and
`EINTR`/`EAGAIN` using `poll` and a monotonic one-second total deadline per call. Expiry returns `ETIMEDOUT`, even if a prefix was written;
the runtime treats output failure as fatal and attempts mode restoration before native close.
This intentionally rejects output that takes more than one second per native transfer (Java
transfers at most 8192 bytes), rather than allowing a stalled terminal to block close forever.
This is not a guarantee of a one-second whole-application shutdown: cleanup may perform several
transfers, and application effects have their own grace period. Termios restoration does not
require output to drain. Escape-based restoration is best effort when the output peer no
longer accepts bytes; no software can guarantee delivery to a disconnected terminal.
`bt_size` returns rows and columns. `bt_close` restores terminal attributes, closes its descriptors,
and frees the context. Error codes use target `errno`; read reserves 0 for timeout and -1 for
EOF and encodes other failures as `-(errno + 2)`. Other operations return 0 or positive errno.
Java permits one input read alongside output/size queries. A fair lifetime lock prevents
close from freeing a context until active bounded calls have finished. Input/output use
separate transfer buffers. A scoped SIGWINCH handler increments only a lock-free atomic counter. It never calls Java or
uses a context pointer. The previous resize handler is saved and restored on close (unless
another owner replaced this handler in the meantime). Java samples the counter on owner
iterations and invalidates the cached frame on a change, even if final dimensions match.
Size polling remains a fallback. Termination signals stay with the JVM; its shutdown hook
restores modes. No native input worker is installed.

Maven invokes `build.sh` during generate-resources, passing its output directory and Braxton version.
The script emits `version.txt` beside the binary; shaded consumers must retain both. It requires
a C11 compiler (`CC` selects the compiler executable), Linux libc development headers or the
macOS ARM64 SDK. Warnings are errors. Darwin gates `SIGWINCH` behind `_DARWIN_C_SOURCE` even at
`_POSIX_C_SOURCE 200809L`, so `terminal.c` defines that macro before its first system include;
the guard is inert on Linux. No JNI headers, downloaded native libraries, generated
bindings or platform structure offsets are needed. The resulting jar contains the current
host binary only. Build independently on each target; do not publish one host jar as universal.
Linux x86_64 verification does not prove macOS ARM64 compilation or execution.

Native code must be exercised with owned PTYs through `tools/terminal/verify_terminal.py`.
The default Maven tests use owned streams, with native access disabled.

## Extraction and cache lifecycle

`NativeLibraryLoader` handles extraction independently of FFM. It first loads from a private
owner-only temporary directory, removing that copy after loading. Actual OS loading decides
whether the filesystem permits execution; file mode alone does not detect `noexec` mounts.
On temporary extraction/loading failure it retries from the JVM user's home cache:

```
~/.local/share/braxton/native/<embedded-version>/<platform>/<sha256>/libbraxton.so
```

macOS uses `libbraxton.dylib`. The location uses `System.getProperty("user.home")`; it does not
read `HOME`. Each version/platform/content combination gets a distinct path. SHA-256 covers
all packaged native bytes, isolating rebuilds that retain the same Maven version. Version
metadata travels with the native resource, independently of a consuming application's manifest.

Extraction creates private directories and a sibling staging file, writes and forces the
complete image, marks it owner read/execute, then atomically renames it into place. A persistent
`.extract.lock` coordinates cooperating processes. A canonical JVM-wide monitor serializes
lock-channel access across threads/class loaders before the OS lock is opened. Neither loaded
files nor lock files are truncated or removed during reuse; corrupt regular entries are
replaced atomically. Abandoned `.part` files are ignored, not mistaken for complete entries.
Old versions are not evicted automatically. Managed directories/files reject symlinks and
other-user-writable permissions. Unsupported atomic moves/locking/permissions or a cache that
also disallows loading result in an error preserving both attempts.

The opt-in `verify_native_extraction.py` compiles its Java probe outside the source tree,
coordinates separate JVMs starting from an absent cache, keeps handles alive while another
version loads, and verifies normal temporary cleanup. Its Linux `--noexec` mode mounts an owned
tmpfs inside a separate user/mount namespace, never remounting the caller's `/tmp`. It requires
unprivileged namespaces and `mount`/`umount`. All tests use an isolated `user.home`.

## Verification

### Linux x86_64

Linux x86_64 (glibc 2.43), Corretto 25.0.3: the full `./build.sh` gate passes,
including 1,125 unit tests, formatting/license-header checks and coverage floors.
`python3 tools/terminal/verify_terminal.py --repeat 3 --pending-escape --harness`
passes 20 checks using owned PTYs and pyte: input/UTF-8/paste/wheel, resize,
large output, repeated sessions, pending Escape, idle close, rapid shrink/grow, synchronized frames,
wide-cell refresh, exception cleanup, JVM shutdown/SIGTERM restoration, scroll panes
and the packaged hierarchical harness. Python 3.14.4 with pyte was used.

The redundant, unattached Javadoc in `AnsiFrameFuzzTest` has been removed. The formatter's
own deprecated-Unsafe warning is emitted by its dependency; no new Java/native source
warnings were introduced. macOS ARM64 and Linux ARM64 compilation/execution remain
unverified on that Linux x86_64 host. A build on each target is required.

`python3 tools/terminal/verify_native_lifecycle.py target/braxton-0.1.0.jar` also passes.
This developer-only C ABI probe uses its own process/PTY to verify raw reads, writes, sizing,
notification tracking, restoration of termios and the previous resize handler, and preservation
of a handler installed by another owner before close.

Earlier terminal-refactor consumer evidence: an isolated snapshot of Amanda's tracked worktree was built with the
terminal artifact declarations removed and archive expectations updated to the bundled native
resource. Its full build/archive gate passed (3,347 unit tests, five expected live-test skips,
13 integration tests); 70 Python harness tests passed. After the resize follow-up, the consumer
was repackaged and its 13 integration tests passed again. Offline PTY verification passed
241 checks with wheel/idle/retention enabled and 229 with the native mouse policy, using the
fixed packaged library. The active consumer checkout was not edited. This proves compatibility
with that inspected snapshot, not with later concurrent consumer edits.

Extraction follow-up verification: the saved pre-fix jar failed on an actual `noexec` tmpfs
in an owned Linux user/mount namespace. The current packaged jar passes
`python3 tools/terminal/verify_native_extraction.py target/braxton-0.1.0.jar --noexec`,
including simultaneous first starts, coexisting version metadata fixtures with live native
handles, and normal temporary cleanup. Its unavailable-temporary-directory case also passes.
Default tests cover six concurrent JVMs, interrupted extraction lock recovery, thread
concurrency, corrupt-cache repair, identity separation, unsafe paths, and retained causes.
No test writes to the developer's real home cache; the `--noexec` namespace case remains
Linux-only.

### macOS ARM64

macOS 27.0.1 arm64, Corretto 25.0.5 (aarch64), Apple clang 21.0.0: the full `./build.sh` gate
passes, including 1,125 unit tests, formatting/license-header checks and coverage floors. This
is the first host on which the native library was both compiled and executed; the Darwin build
previously failed to compile because `SIGWINCH` is not exposed under a strict POSIX feature-test
macro.

`python3 tools/terminal/verify_terminal.py --repeat 3 --pending-escape --harness` passes the same
20 owned-PTY/pyte checks recorded for Linux above. `verify_native_extraction.py` passes its
packaged fallback, concurrent first-start, version coexistence and temporary-cleanup cases;
the Linux-only `--noexec` namespace case does not apply.

`verify_native_lifecycle.py` passes raw reads, writes, sizing, notification tracking, previous
resize-handler restoration and cross-owner handler preservation. Its termios restoration
comparison ignores `PENDIN`, which Darwin's line discipline sets when a non-canonical terminal
returns to canonical mode; that bit is kernel bookkeeping for retyping pending input rather than
a saved attribute, and no raw-mode restore can reproduce it. Every other attribute field is still
compared exactly, and the mask is empty on Linux. Linux ARM64 remains unverified on any host.

## Hardening follow-up (reference b3af6afb5b)

Linux x86_64 verification of the bounded-output change includes a deliberately congested
4 MiB native write, unchanged caller descriptor flags, 50 raw/open/resize/close cycles with
termios comparison, and a Java FFM probe closing twice concurrently with an active reader
and a congested writer. The Java probe uses an explicit PTY/parent handshake rather than a
sleep. Run `python3 tools/terminal/verify_native_lifecycle.py target/braxton-0.1.0.jar`.
The earlier macOS evidence above predates this change; the new output-open/deadline behavior
has **not** been verified on macOS ARM64 or Linux ARM64.

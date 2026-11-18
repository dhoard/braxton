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
- [POSIX terminal session and foreground process group](https://pubs.opengroup.org/onlinepubs/9799919799/functions/tcgetpgrp.html)
  and [job-control signals](https://pubs.opengroup.org/onlinepubs/9799919799/functions/tcsetpgrp.html)
  — `tcgetpgrp`/`getpgrp` for "is this group the terminal's foreground", and `SIGTSTP`/`SIGCONT`
  with the rule that a stop signal is discarded for an orphaned process group. Consulted 2026-11-16.
- [POSIX kill](https://pubs.opengroup.org/onlinepubs/9799919799/functions/kill.html) — `kill(0, sig)`
  addresses the caller's process group, which is what a foreground job stop means.
- [Terminal dimensions](https://man7.org/linux/man-pages/man2/TIOCGWINSZ.2const.html)
- [Java 25 FFM](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/package-summary.html)
- Target operating-system C headers for declarations, layouts and constants, including the
  [Apple terminal ioctl declarations](https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/ttycom.h).

The Java keyboard decoder (`internal/input/InputDecoder.java`) is documented separately from the C
service above. It was written against these public protocol specifications, not against another
toolkit's implementation:

- [Kitty keyboard protocol](https://sw.kovidgoyal.net/kitty/keyboard-protocol/) — the `CSI ... u` key
  form and its modifier, event-type, alternate-key and associated-text fields; the progressive
  enhancement flags; the `CSI = flags ; mode u`, `CSI > flags u`, `CSI < number u` and `CSI ? u`
  query/push/pop sequences; and the functional-key number table. Revision consulted 2026-11-16; the
  protocol has no version number, so the page itself is the reference.
- The same specification's functional-key encoding and default-parameter rules were rechecked
  on 2026-11-17 for F2's parameterless `CSI Q` form: when modifiers are absent, the functional
  letter form omits both parameters. The decoder accepts this form alongside `SS3 Q`, `CSI 12 ~`
  and parameterized reports. A user-requested comparison with Bubble Tea's cached Ultraviolet
  key table identified this missing form; the correction follows the public protocol rule and
  does not copy or translate toolkit implementation code.
- [xterm control sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html) — extended mode
  1004 focus reporting (`CSI ? 1004 h`/`l`) and its `CSI I`/`CSI O` notifications; SGR mouse and
  legacy CSI/SS3 functional-key forms, which the decoder already handled.

The Java cursor presenter (`internal/render/CursorPresenter.java` and the cursor sequences in
`AnsiEncoder`) is documented against the same control-sequence reference:

- [xterm control sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html) — `CSI Ps SP q`
  Set Cursor Style (DECSCUSR), including xterm's steady and bar parameters (`Ps` 2, 4 and 6) beyond
  the VT520 table; `CSI ? 25 h`/`l` cursor visibility; `CSI Ps ; Ps H` absolute positioning; and the
  dynamic-colour controls `OSC 12 ; Pt ST` (text cursor colour, specified as for `XParseColor`, which
  is why the emitted form is `rgb:rr/gg/bb`) and `OSC 112 ST` (reset text cursor colour). Revision
  consulted 2026-11-16.

The Java terminal-query engine (`internal/runtime/TerminalQueries.java`) and the reply framing in
`internal/input/InputDecoder.java` are documented against these public specifications:

- [xterm control sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html) — Device Status
  Report (`CSI 6 n`) and its `CSI Pl ; Pc R` answer; DECRQM (`CSI ? Ps $ p`) and its
  `CSI ? Ps ; Pm $ y` answer with the documented values 0-4; XTVERSION (`CSI > Ps q`) and its
  `DCS > | text ST` answer; XTGETTCAP (`DCS + q Pt ST`) and its `DCS 1 + r Pt ST` /
  `DCS 0 + r Pt ST` answers, where `Pt` is `hex name [= hex value]`; and the dynamic-colour reports
  `OSC 10`, `OSC 11` and `OSC 12`, whose answer is an X11 colour specification or `?`. Strings are
  terminated by `ST` or `BEL`; `CAN` and `SUB` cancel them. Consulted 2026-11-16; the page carries
  no stable revision marker, so the date is the record.
- [X11 `XParseColor`](https://www.x.org/releases/X11R7.7/doc/man/man3/XParseColor.3.xhtml) — the
  `rgb:<red>/<green>/<blue>` form with one to four hexadecimal digits per channel, and the `#RGB`,
  `#RRGGBB` and `#RRRRGGGGBBBB` forms. A short channel is scaled to eight bits.
- [no-color.org](https://no-color.org/) — `NO_COLOR`, when present and not an empty string, disables
  colour output whatever its value.

The Java terminal presenter (`internal/runtime/TerminalPresenter.java` and the presentation
sequences in `AnsiEncoder`) and the program-status query are documented against these public
specifications:

- [xterm control sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html) — `OSC 2 ; Pt ST`
  (window title); XTPUSHTITLE/XTPOPTITLE (`CSI 22 ; Ps t` and `CSI 23 ; Ps t`) for saving and
  restoring the title a session replaced; the dynamic-colour controls `OSC 10`, `OSC 11` and
  `OSC 12` and their reset forms `OSC 110`, `OSC 111` and `OSC 112`; and the taskbar progress form
  `OSC 9 ; 4 ; state ; value`. Revision consulted 2026-11-16; the page carries no stable revision
  marker, so the date is the record.
- [Program Status Protocol](https://www.superlogical.com/rex/docs/build/program-status) — revision
  0.4, dated 2026-10-10 in its own revision history: `OSC 7501 ; pairs ST` with the `key=value`
  grammar and the value character set, the states `idle`, `working`, `done`, `blocked`, `error` and
  the non-state `clear`, the blocked kinds `permission`, `question` and `auth`, base64 UTF-8 for
  `title` and `msg`, the record id grammar and depth, feature detection
  `OSC 7501 ; ? ST` with its `states=` and `kinds=` lists and their fixed defaults, and the limits a
  report must respect (4096-byte sequence, 16-byte key, 2732/2048-byte `msg`, 256/192-byte `title`,
  32-byte `app`, 128-byte id). This library writes `ST` as the string terminator, as the
  specification allows, and bounds every field before it is encoded.

The query allowlist is deliberately closed: `RGB`, `Tc`, `TN`, `Co` and `Ms`. Every other termcap
name is refused by `TerminalQuery.Termcap`, so no reply can be interpreted for a capability whose
meaning this project has not decided. `RGB` and `Tc` are the two boolean capabilities that mean
"24-bit colour"; a terminal that answers either one widens the session's colour profile.

The sibling Bubble Tea project's README was consulted for general MVU behavior and its
`go.mod` for dependency boundaries. Its implementation files were not used. Braxton retains
its own typed component tree, views, routing, effects, subscriptions and runtime semantics.
This provenance record describes this replacement; it does not certify a formally separated
clean-room process or retrospectively establish provenance for all existing project files.

The ABI version is 3. ABI 2 replaced `bt_write` with `bt_write_some`; ABI 3 added
`bt_release`, `bt_reacquire`, `bt_foreground` and `bt_suspend`. `bt_release` restores the input
configuration `bt_raw` changed and marks the context released: `bt_read` then reports no input and
`bt_write_some` fails with `EBUSY`, because the terminal belongs to a child process or to the shell.
Releasing twice is defined, and `bt_reacquire` re-enters raw mode. `bt_foreground` answers 1 when the
calling process group is the terminal's foreground group, 0 when it is not (including a terminal that
is nobody's controlling terminal) and `-errno` when the device cannot be inspected. `bt_suspend`
requires a released context - a session stopped in raw mode would hand the shell a terminal whose
settings contradict its screen - refuses a group that is not in the foreground with `EPERM`, sends
`SIGTSTP` to its own process group and returns once `SIGCONT` has been observed. The continue
notification is a lock-free counter bumped by a process-wide handler that saves and restores the
previous `SIGCONT` action; the waiting side polls it, so the notification is not lost to whichever
thread the kernel chooses. The wait is bounded in running time, not wall time: a job stopped for an
hour still resumes without spending the bound. `bt_close` is defined in every state: a released
context is already in the user's configuration, so only its descriptors and the context are given
back. The ABI 2 behaviour below is unchanged. `bt_open` duplicates the attached input descriptor and reopens the
output terminal with a private nonblocking open file description, snapshots input terminal
state and returns an opaque context. The reopened output is checked against the original
character device's device/inode identity; symlinks are rejected. The terminal device path must
be accessible for reopening. Failure happens before raw mode or application output modes are
entered. Unlike setting `O_NONBLOCK` on a duplicate, reopening does not change the caller's
stdout flags (duplicates share those flags). `bt_raw` applies raw mode. `bt_read` polls for a
bounded interval and reads into a caller-owned buffer. `bt_write_some` makes one bounded attempt: it returns the number of bytes the terminal
accepted, `0` when nothing was accepted within the caller's timeout (or the wait was interrupted),
or `-errno` on failure. `POLLHUP`/`POLLERR` map to `EIO` and `POLLNVAL` to `EBADF`. Retry and stall
policy live in Java (`OutputDrain`), which knows how much progress has been made and whether
shutdown has begun. Attempts wait at most 100 ms. Output fails only when the terminal accepts no
bytes at all for the stall limit: 30 seconds while running, and the shorter of that and one second
once shutdown has begun (runtime cleanup, the JVM shutdown hook, or `NativeTerminal.close`, which
bounds output before taking the lifetime lock). A slow terminal that keeps accepting bytes is
therefore waited for, because a frame that has started must finish. A frozen peer fails, and
close is never held behind a stalled writer for more than about one second plus one attempt.
Terminal errors fail at once. This is not a guarantee of a one-second whole-application
shutdown: cleanup may perform several transfers, and application effects have their own grace
period. Termios restoration does not require output to drain. Escape-based restoration is best
effort when the output peer no longer accepts bytes; no software can guarantee delivery to a
disconnected terminal.
`bt_size` returns rows and columns. `bt_close` restores terminal attributes, closes its descriptors,
and frees the context. Error codes use target `errno`; read reserves 0 for timeout and -1 for
EOF and encodes other failures as `-(errno + 2)`; `bt_write_some` is described above. Other operations return 0 or positive errno.
Java permits one input read alongside output/size queries. A fair lifetime lock prevents
close from freeing a context until active bounded calls have finished. Input/output use
separate transfer buffers. A scoped SIGWINCH handler increments only a lock-free atomic counter. It never calls Java or
uses a context pointer. The previous resize handler is saved and restored on close (unless
another owner replaced this handler in the meantime). Java samples the counter on owner
iterations and invalidates the cached frame on a change, even if final dimensions match.
Size polling remains a fallback. Termination signals stay with the JVM; its shutdown hook
restores modes. No native input worker is installed.

Maven invokes `build.sh` during generate-resources, passing its output directory and Braxton version.
The script emits `version.txt` beside the binary, and the same Maven execution records the binary's
SHA-256 as `libbraxton.so.sha256` (`libbraxton.dylib.sha256` on macOS). Shaded consumers must retain
the binary and both metadata files. It requires
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
That directory is created exclusively as `java.io.tmpdir/braxton-native-<nonce>-<attempt>` with its
owner-only mode applied by `mkdir` itself, for at most 16 attempts. The nonce is read from
`/dev/urandom`, so no other local user can predict the name; `SecureRandom` is deliberately not
used because constructing it initializes the JCA provider framework (~4.5 ms) on every start. An
occupied name is skipped, never adopted: it may be another user's entry. The temporary copy is
written and mapped but never forced to stable storage — it is unlinked moments after loading, so a
journal commit per launch would be pure cost, and on a disk-backed temporary directory it dominated
the entire load. Distinct users and distinct instances share `java.io.tmpdir` safely: each one
creates its own directory, and failures only ever cost an attempt.
On temporary extraction/loading failure it retries from the JVM user's home cache:

```
~/.local/share/braxton/native/<embedded-version>/<platform>/<recorded-content-hash>/libbraxton.so
```

macOS uses `libbraxton.dylib`. The location uses `System.getProperty("user.home")`; it does not
read `HOME`. Each version/platform/content combination gets a distinct path. The content hash is
not computed at startup: Maven records the SHA-256 of the compiled binary beside it during
generate-resources, so it covers all packaged native bytes and isolates rebuilds that retain the
same Maven version, and the loader never hashes the image or a published entry while the
application starts. Version metadata travels with the native resource, independently of a
consuming application's manifest. Because the recorded value only names the cache entry, a
duplicate or stale record cannot load the wrong binary: reuse compares the published bytes with
the packaged image exactly.

Extraction creates private directories and an exclusively named sibling staging file, writes and
forces the complete image (durability belongs to this published copy, not to the temporary one),
marks it owner read/execute, then atomically renames it into place. A persistent
`.extract.lock` coordinates cooperating processes. A canonical JVM-wide monitor serializes
lock-channel access across threads/class loaders before the OS lock is opened. Neither loaded
files nor lock files are truncated or removed during reuse; an entry is reused only when it is
byte-for-byte the packaged image, and corrupt regular entries are replaced atomically. Abandoned
`.part` files are ignored, not mistaken for complete entries.
Old versions are not evicted automatically. Managed directories/files reject symlinks and
other-user-writable permissions. Unsupported atomic moves/locking/permissions or a cache that
also disallows loading result in an error preserving both attempts.

The cache is per user: two users have separate caches under their own `user.home`, so the same
version and build simply produce the same relative path in each. Entries are owner-only and are
rejected when their owner differs from the directory's, so a user who shares one `user.home` with
a different uid fails closed with an error instead of loading another user's binary. Sharing a home
that way is not supported; each user still works through temporary extraction whenever temporary
storage is usable.

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

## Parity acceptance (phase 24)

The reference-to-phase record is `examples/parity/PARITY.md`: it pins the Bubble Tea checkout
(`6b30ecf3280d88d949dcc916bb91fc5e9d56f667`) and the versions its own `examples/go.mod` pins
(Bubbles 2.1.0, Lip Gloss 2.0.3, Glamour 2.0.0), maps every reference example to the phase, demo,
deterministic test and terminal scenario that covers it, and names the deliberate differences
(network access, clipboard transport, markdown dialect, the inline region, Windows, canvas-style
applications). `ParityAcceptanceTest` reads that file and fails when a row names a demo, test or
scenario that does not exist.

`examples.parity.Showcase` is the one packaged entry point: a catalog that mounts each phase's own
scene component and returns to the list. Its own scenario is phase `24`, and `--parity-all` runs
every phase's scenario and packaged launch together.

Linux x86_64, Corretto 25.0.3 (the host recorded above): `./build.sh` passes, and
`python3 tools/terminal/verify_terminal.py --parity-all --repeat 10 --pending-escape --harness`
passes the whole native battery plus all 24 parity scenarios and all 24 packaged demo launches in
one invocation, including the showcase's catalog mount/return and composed workspace. The
showcase's packaged launch is verified with the library's own runtime dependencies beside the jar
and no example-only jar on the classpath.

Two showcase behaviours are deliberate and are not platform claims: the catalog asks for
`MouseMode.CELL_MOTION` for every mounted scene, and phase 23's durable lines are a defined no-op
while the showcase owns the alternate screen (phase `23`'s own scenario is where they are
verified). macOS ARM64 and Linux ARM64 have not been re-run for this phase; the phase's own PTY
scenario is the platform-independent part, and it is the same code on every host.

## Hardening follow-up (reference b3af6afb5b)

Linux x86_64 verification of the bounded-output change includes a deliberately congested
4 MiB native write, unchanged caller descriptor flags, 50 raw/open/resize/close cycles with
termios comparison, and a Java FFM probe closing twice concurrently with an active reader
and a congested writer. The Java probe uses an explicit PTY/parent handshake rather than a
sleep. Run `python3 tools/terminal/verify_native_lifecycle.py target/braxton-0.1.0.jar`.
The earlier macOS evidence above predates this change; the new output-open/deadline behavior
has **not** been verified on macOS ARM64 or Linux ARM64.

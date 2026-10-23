# Terminal platform support and native verification (added 2026-10-22)

`internal/terminal/` has one session per platform ABI, selected by
`SystemTerminalBackend.platform()` from `os.name`/`os.arch`: `LinuxTerminalSession`
(Linux/glibc x86_64, `poll`), `WindowsTerminalSession`, and `MacOsTerminalSession`
(Darwin LP64 arm64/x86_64). BSD, Linux arm64/musl, and other ABIs are rejected before any
descriptor is opened or terminal mode changed.

## Constraints worth remembering

- Darwin's `struct termios` is not the glibc layout: `tcflag_t`/`speed_t` are unsigned long,
  `NCCS=20`, `VMIN=16`, `VTIME=17`, `ICANON`/`ISIG`/`IEXTEN`/`IXON` are at different bit
  positions than Linux's. Never route macOS through `LinuxTerminalSession`.
- `os.name` is `"Mac OS X"`, which contains `win`; match macOS/Darwin before the Windows
  substring branch.
- Darwin `poll()`/kqueue report `POLLNVAL` for `/dev/tty`, so the macOS backend waits with
  `select$DARWIN_EXTSN` and dynamically sized fd-set words. `open` and `ioctl` are variadic
  and need `Linker.Option.firstVariadicArg(2)`.
- Lifetime is guarded by access-mode locks, not by code that assumes closing a descriptor in
  one thread wakes a blocked read. Output has its own lock so an idle reader cannot starve
  rendering; restoration uses `TCSANOW`; stalled writes time out.
- `BaseTerminalSession.close()` uses an `AtomicBoolean` CAS: a reader, the owner, and the
  shutdown hook can all close, and only one may restore modes and release ownership.

## Verification split

The default Maven suite is TTY-free; `MacOsTerminalSessionTest` and `SystemTerminalBackendTest`
run on any host through the fake-native and fake-factory seams. Real terminal behavior is an
opt-in developer check, never part of the gate:

```bash
./mvnw clean verify                     # target/classes for the verifier
python3 tools/terminal/verify_terminal.py [--harness]   # needs pyte, java, javac; xcrun clang on macOS
```

The driver owns its PTYs (never the caller's terminal), compares `termios` snapshots before and
after, and feeds real output into pyte. Evidence state: macOS arm64 (macOS 27.0.1, Java 25) and
Linux/glibc x86_64 (Corretto 25.0.3) pass the full check including `--harness`; macOS x86_64 is
implemented but unverified, so the README matrix says so. A macOS build alone proves nothing
about Linux, and vice versa.

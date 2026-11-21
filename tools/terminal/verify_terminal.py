#!/usr/bin/env python3
"""Opt-in Braxton POSIX native checks. Requires Java 25, a packaged Braxton jar, and pyte.

Uses owned PTYs and a private Unix-domain control socket, never the caller's terminal.
The host native library is loaded from the packaged jar; pyte remains developer-only.

--parity-phase NN runs only phase NN's parity scenario (through ParityProbe) and the packaged
demo launch, which is the focused gate an implemented parity phase records. Without the flag the
full native battery runs as before.
"""

import argparse
import base64
import fcntl
import os
from pathlib import Path
import platform
import re
import selectors
import shlex
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import termios
import time

import pyte


ROOT = Path(__file__).resolve().parents[2]
PROBE = "io.github.dhoard.braxton.internal.runtime.TerminalProbe"
SCROLL_PROBE = "io.github.dhoard.braxton.tools.ScrollViewProbe"
PARITY_PROBE = "io.github.dhoard.braxton.tools.ParityProbe"
# One packaged demo per implemented phase, and the status text that proves it started. Each demo runs
# from the Braxton jar alone: no example dependency is on its classpath.
PARITY_DEMOS = {
    "01": ("examples.parity.KeymapHelpDemo",
           ("left=alpha right=alpha focus=left help=compact keys=default",
            "\u2191 move up \u2022 \u2193 move down \u2022 enter choose \u2022 esc cancel")),
    "02": ("examples.parity.KeyboardFocusDemo",
           ("caps=none event-types=off focus-report=on",
            "? toggle help \u2022 f toggle field focus \u2022 q quit")),
    "03": ("examples.parity.CursorDemo",
           ("strategy=native shape=block mode=blink color=none cursor=visible",
            "m native/virtual")),
    "04": ("examples.parity.EditorDemo",
           ("focus=left lines=off height=fixed cursor=painted",
            "tab next pane")),
    "05": ("examples.parity.TextInputDemo",
           ("fields=3 focus=nickname completion=on",
            "tab next field")),
    "06": ("examples.parity.PaginatorDemo",
           ("page=1/9 items=84 per=10 bounds=[0,10) selected=0 mode=dots wrap=off",
            "\u2190 previous page \u2022 \u2192 next page")),
    "07": ("examples.parity.SpinnerDemo",
           ("left=line frame=",
            "\u2190 previous \u2022 \u2192 next \u2022 space run \u2022 c cadence")),
    "08": ("examples.parity.ProgressDemo",
           ("animated target=0.000 displayed=0.000 run=off",
            "s install \u2022 u uninstall \u2022 r reset \u2022 m motion \u2022 b fill")),
    "09": ("examples.parity.CountdownDemo",
           ("countdown state=idle epoch=0",
            "s start over \u2022 space start/pause \u2022 r reset \u2022 \u2190 shorter \u2022 \u2192 longer")),
    "10": ("examples.parity.StopwatchDemo",
           ("left state=stopped epoch=0",
            "s start left \u2022 r reset left \u2022 k start right \u2022 l reset right")),
    "11": ("examples.parity.RichListDemo",
           ("preset=default items=240 selected=item-001",
            "\u2191 up \u2022 \u2193 down \u2022 \u2190 previous page")),
    "12": ("examples.parity.ViewportDemo",
           ("mode=wrap numbers=on follow=off line=1/39",
            "\u2191/k up \u2022 \u2193/j down \u2022 b/pgup page up")),
    "13": ("examples.parity.TableDemo",
           ("mode=row row=1/18 cell=- top=0 pan=0 last=started",
            "\u2191/k up a row \u2022 \u2193/j down a row")),
    "14": ("examples.parity.FilePickerDemo",
           ("dir=/ entries=9 shown=7 selected=docs hidden=off state=idle",
            "\u2192/l open directory \u2022 enter choose")),
    "15": ("examples.parity.MarkdownDemo",
           ("theme=light mode=wrap numbers=on rows=19 width=74 lines=132 limits=default",
            "diagnostics=none matches=0 active=0 query=- focus=pager last=started")),
    "16": ("examples.parity.TabsDemo",
           ("tab=notes focus=page pages=3 switched=0 last=none",
            "enter submit \u2022 alt+c copy \u2022 ctrl+v paste")),
    "17": ("examples.parity.MouseDemo",
           ("pane=left dialog=closed",
            "tab switch pane \u2022 alt+m dialog \u2022 ? full help \u2022 ctrl+c quit")),
    "18": ("examples.parity.TerminalQueryDemo",
           ("profile=", "asked=0 answered=0 failed=0 last=none")),
    "19": ("examples.parity.ClipboardDemo",
           ("copy=idle", "read=idle")),
    "20": ("examples.parity.TerminalPresentationDemo",
           ("screen=default mouse=default paste=default focus=default",
            "progress=none status=none")),
    "21": ("examples.parity.ProcessHandoffDemo",
           ("handoffs=0 last=none exit=- suspend=ready",
            "e run helper")),
    "22": ("examples.parity.ApplicationControlDemo",
           ("sequence=idle order=none quit=guarded",
            "braxton application control producer=on")),
    "23": ("examples.parity.InlineIoDemo",
           ("packages=6 installed=0 phase=idle mode=inline",
            "braxton inline output packages=6")),
    "24": ("examples.parity.Showcase",
           ("catalog=27 open=none selected=01 top=0 help=compact last=none",
            "braxton parity showcase")),
    "25": ("examples.parity.TerminalMatrixDemo",
           ("term=xterm-256color caps=1/1/1/1 size=24x80",
            "Braxton terminal matrix")),
    "26": ("examples.parity.EngineDemo",
           ("engine text: alpha", "engine selection: 4", "engine scroll: 0")),
    "27": ("examples.parity.ElementKernelDemo",
           ("kernel widget=painted", "meter \u2588\u2588\u2588\u2588")),
    "28": ("examples.parity.WidgetGalleryDemo",
           ("gallery page=1/4 choice widgets=5", "name: Ada")),
    "31": ("examples.parity.DebugOverlayDemo",
           ("Braxton debug overlay", "debug overlay=on focus=left left=off right=off changes=0", "DEBUG bounds=")),
    "30": ("examples.parity.RichOutputDemo",
           ("Braxton rich output", "motion=normal value=0 target=0 state=settled moves=0")),
    "29": ("examples.parity.LinePromptsDemo",
           ("Braxton line prompts", "Overwrite the report? [y/N]")),
}
# Phases whose packaged demo needs more than ctrl+c to leave. Phase 22's quit is guarded on purpose,
# so the demo is asked and then confirmed - the packaged launch proves the same path the scenario does.
PARITY_DEMO_QUIT = {
    "22": (b"q", b"y"),
    "25": (b"q",),
    "26": (b"q",),
    "27": (b"q",),
    "28": (b"q",),
    "30": (b"q",),
    "31": (b"q",),
    # The line prompts are a scripted dialog: each cancel answers the prompt it reaches, so three of
    # them abandon the confirmation, the choice and the marks, and the progress count then runs to
    # its end and the demo exits on its own.
    "29": (b"\x03", b"\x03", b"\x03"),
}
# Phases whose demo needs the library's own runtime dependencies on its classpath, because the
# packaged jar does not shade them: markdown rendering is CommonMark behind `Markdown`. Every other
# demo still runs from the jar alone, which is the packaging gate those phases record.
RUNTIME_CLASSPATH_PHASES = {"15", "24"}
# The example-only dependency the pom declares as optional, and the annotations it brings. A parity
# demo of the library never needs them, so the launch of a runtime-dependency phase leaves them out:
# what it proves is that the jar plus the core's own dependencies is a complete classpath.
EXAMPLE_ONLY_JARS = ("gson-", "error_prone_annotations-")
NATIVE_IDENTITY = "braxton-posix " + ("macos" if platform.system() == "Darwin" else "linux") + "-" + (
    "aarch64" if platform.machine() in ("arm64", "aarch64") else "x86_64")

# Phase 08's scene: the seeded package count, the fills and the two bar glyphs it asserts.
PACKAGE_COUNT = 6
SIXTH = 1 / PACKAGE_COUNT
FIVE_SIXTHS = (PACKAGE_COUNT - 1) / PACKAGE_COUNT
FILLED_CELL = "\u2588"
EMPTY_CELL = "\u2591"
PARTIAL_CELL = "\u258D"
BAR_COLUMN = len("detail  ")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class PtyChild:
    def __init__(self, command, *, control=True, env=None):
        self.master, self.slave = os.openpty()
        self.original = termios.tcgetattr(self.slave)
        self.resize(24, 80)
        self.screen = pyte.Screen(80, 24)
        self.stream = pyte.ByteStream(self.screen)
        self.output = bytearray()
        self.pending = bytearray()
        self.lines = []
        self.selector = selectors.DefaultSelector()
        self.errors = tempfile.TemporaryFile()
        self.control_dir = tempfile.TemporaryDirectory(prefix="braxton-control-")
        self.listener = socket.socket(socket.AF_UNIX) if control else None
        environment = os.environ.copy()
        environment["TERM"] = "xterm-256color"
        # These owned terminals assert color output. Host preferences must not silently disable
        # the behavior under test; a scenario can still request NO_COLOR explicitly through env.
        environment.pop("NO_COLOR", None)
        if env:
            environment.update(env)
        if control:
            address = str(Path(self.control_dir.name)/"ipc")
            self.listener.bind(address)
            self.listener.listen(1)
            self.listener.settimeout(15)
            environment["BRAXTON_PROBE_CONTROL"] = address
        self.control = None

        def attach():
            os.setsid()
            fcntl.ioctl(self.slave, termios.TIOCSCTTY, 0)

        try:
            self.process = subprocess.Popen(
                command, cwd=ROOT, preexec_fn=attach, pass_fds=(self.slave,),
                stdin=self.slave, stdout=self.slave, stderr=self.errors, env=environment,
            )
            self.selector.register(self.master, selectors.EVENT_READ, "terminal")
            if control:
                self.control, _ = self.listener.accept()
                self.selector.register(self.control, selectors.EVENT_READ, "control")
        except BaseException as failure:
            process = getattr(self, "process", None)
            initial_status = process.poll() if process is not None else None
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            if self.control is not None:
                self.control.close()
            if self.listener is not None:
                self.listener.close()
            self.control_dir.cleanup()
            self.selector.close()
            self.errors.seek(0)
            diagnostics = self.errors.read().decode("utf-8", errors="replace")
            self.errors.close()
            os.close(self.master)
            os.close(self.slave)
            if isinstance(failure, Exception):
                raise RuntimeError(
                    f"terminal child bootstrap failed: {failure}; exit={initial_status}; stderr={diagnostics}"
                ) from failure
            raise

    def __enter__(self):
        return self

    def __exit__(self, kind, value, traceback):
        try:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=5)
        finally:
            if kind is not None:
                self.errors.seek(0)
                print(self.errors.read().decode("utf-8", errors="replace"))
            for pipe in (self.process.stdin, self.process.stdout):
                if pipe is not None:
                    pipe.close()
            self.selector.close()
            if self.control is not None: self.control.close()
            if self.listener is not None: self.listener.close()
            self.control_dir.cleanup()
            self.errors.close()
            os.close(self.master)
            os.close(self.slave)

    def resize(self, rows, columns):
        fcntl.ioctl(self.slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, columns, 0, 0))

    def command(self, command):
        self.control.sendall((command + "\n").encode())

    def pump(self, timeout):
        events = self.selector.select(timeout)
        for key, _ in events:
            chunk = os.read(key.fd, 65536)
            if not chunk:
                self.selector.unregister(key.fileobj)
            elif key.data == "terminal":
                self.output.extend(chunk)
                self.stream.feed(chunk)
            else:
                self.pending.extend(chunk)
                while b"\n" in self.pending:
                    line, _, rest = self.pending.partition(b"\n")
                    self.lines.append(line.decode())
                    self.pending = bytearray(rest)
        return bool(events)

    def expect(self, expected):
        deadline = time.monotonic() + 15
        while not self.lines and time.monotonic() < deadline:
            self.pump(min(0.25, max(0, deadline - time.monotonic())))
            if self.process.poll() is not None and not self.lines:
                break
        require(bool(self.lines), f"no control response; expected {expected}; exit={self.process.poll()}")
        actual = self.lines.pop(0)
        require(actual == expected, f"expected {expected!r}, got {actual!r}")

    def terminal_contains(self, text):
        deadline = time.monotonic() + 30
        while (text not in self.output.decode("utf-8", errors="replace")
               and text not in "\n".join(self.screen.display)):
            require(time.monotonic() < deadline, f"terminal never displayed {text!r}")
            require(self.process.poll() is None, f"child exited before displaying {text!r}")
            self.pump(0.1)

    def finish(self, expected_code=0, *, cursor_was_hidden=True):
        deadline = time.monotonic() + 10
        while self.process.poll() is None and time.monotonic() < deadline:
            self.pump(0.1)
        require(self.process.poll() is not None, "child did not exit within ten seconds")
        while self.pump(0):
            pass
        require(self.process.returncode == expected_code,
                f"exit {self.process.returncode}; expected {expected_code}")
        # Darwin revokes the slave when its controlling session leader exits. The retained
        # master still exposes the final attributes, so use it for the post-exit comparison.
        require(termios.tcgetattr(self.master) == self.original, "terminal attributes were not restored")
        if cursor_was_hidden:
            require(b"\x1b[?25h" in self.output, "cursor was not restored")
        else:
            require(b"\x1b[?25l" not in self.output and not self.screen.cursor.hidden,
                    "a low-level image write changed cursor visibility")


def check_session(java_command, plain):
    with PtyChild([*java_command, *(["--plain"] if plain else [])]) as child:
        expected_platform = NATIVE_IDENTITY
        child.expect("READY " + expected_platform)
        raw = termios.tcgetattr(child.slave)
        require(raw[3] & (termios.ECHO | termios.ICANON | termios.ISIG | termios.IEXTEN) == 0,
                "raw input flags were not applied")
        child.command("SIZE")
        child.expect("SIZE 24 80")
        child.resize(31, 101)
        time.sleep(0.35)
        child.command("SIZE")
        child.expect("SIZE 31 101")
        payload = "\x03界\x1b[A\x1b[200~paste\x1b[201~\x1b[<64;1;1M".encode()
        os.write(child.master, payload)
        for expected in ("KEY ctrl+c ", "KEY 界 e7958c", "KEY up ", "PASTE paste", "KEY wheelup "):
            child.expect(expected)
        child.command("WRITE")
        child.expect("WROTE")
        child.terminal_contains("verified")
        require(child.screen.display[0].startswith("Native 界"), "UTF-8 screen mismatch")
        require(child.screen.display[1].startswith("verified"), "cursor/style screen mismatch")
        require(child.screen.buffer[1][0].fg == "ff0000", "ANSI foreground color mismatch")
        child.resize(200, 1000)
        time.sleep(0.35)
        child.command("WRITE_LARGE")
        time.sleep(0.25)  # Own reader deliberately pauses; resume draining before asserting progress.
        child.expect("WROTE_LARGE")
        child.command("CLOSE_IDLE")
        child.expect("WAITING")
        # A real idle read must remain pending, not return a spurious EOF before close.
        idle_deadline = time.monotonic() + 0.25
        while time.monotonic() < idle_deadline:
            child.pump(min(0.05, max(0, idle_deadline - time.monotonic())))
        require(not child.lines, f"idle input ended unexpectedly: {child.lines}")
        child.command("CLOSE")
        child.expect("REOPENED " + expected_platform)
        os.write(child.master, b"z")
        child.expect("KEY z 7a")
        child.finish()
        for mode in (b"\x1b[?1049h", b"\x1b[?2004h", b"\x1b[?1000h"):
            require((mode in child.output) != plain, f"incorrect option mode {mode!r}")
        if not plain:
            require(b"\x1b[?1049l" in child.output, "alternate screen not restored")
            require(b"\x1b[?2004l" in child.output, "paste mode not restored")
    print(f"PASS native input/UTF-8/resize/pyte/large output/idle close/reopen/restoration (plain={plain})")


BSU = b"\x1b[?2026h"
ESU = b"\x1b[?2026l"


def wait_frame(child, start):
    """Wait for a complete frame, not the control ACK sent before presentation."""
    deadline = time.monotonic() + 15
    while True:
        data = bytes(child.output[start:])
        if BSU in data and ESU in data[data.index(BSU) + len(BSU):]:
            begin = data.index(BSU)
            end = data.index(ESU, begin) + len(ESU)
            frame = data[begin:end]
            require(frame.count(BSU) == frame.count(ESU) == 1, "nested frame bracket")
            require(begin == 0 and end == len(data), "output outside frame bracket")
            return frame
        require(time.monotonic() < deadline, "frame did not close synchronization")
        require(child.process.poll() is None, "child exited during frame")
        child.pump(0.1)


def require_inline_frame(frame, first_content):
    """An inline frame paints relatively and clears only its own live region first."""
    require(re.search(rb"\x1b\[\d+;\d+H", frame) is None,
            "an inline frame addressed a row absolutely")
    require(b"\x1b[0J" in frame, "the inline frame cleared no live region")
    require(frame.index(b"\x1b[0J") < frame.index(first_content),
            "the live region was cleared after its content was painted")


def check_refresh(java_command, plain):
    with PtyChild([*java_command, *(["--plain"] if plain else [])]) as child:
        child.expect("READY " + NATIVE_IDENTITY)
        child.terminal_contains("Native 界")
        # Drain startup (mode entry is deliberately not a frame).
        while child.pump(0.1):
            pass
        start = len(child.output)
        child.command("REFRESH")
        child.expect("REFRESH_READY")
        frame = wait_frame(child, start)
        require(child.screen.display[0].startswith("REFRESH_FULL"), "full row missing")
        require(child.screen.buffer[0][78].data == "界", "right-edge wide glyph was erased")
        require(all(child.screen.buffer[0][col].bg == "000000" for col in range(79)),
                "full row background mismatch")
        require(child.screen.display[1] == "b" * 80, "full second row mismatch")
        require(child.screen.display[23] == "z" * 80, "bottom-right write scrolled")
        if plain:
            # Without the alternate screen the session paints a live region relatively: it clears
            # forward from the region's own first row and addresses nothing absolutely.
            require_inline_frame(frame, b"REFRESH_FULL")
        else:
            # Empty rows may legitimately use EL2; the full first row must not erase at all.
            first_row = frame.split(b"\x1b[2;1H", 1)[0]
            require(b"\x1b[2K" not in first_row and b"\x1b[0K" not in first_row,
                    "full row was cleared")
        start = len(child.output)
        child.command("REFRESH_SHORT")
        child.expect("REFRESH_SHORT_READY")
        frame = wait_frame(child, start)
        require(child.screen.display[0].rstrip() == "short界", "short row mismatch")
        require(all(child.screen.buffer[0][col].bg == "000000" for col in range(6)),
                "short prefix background lost")
        require(all(child.screen.buffer[0][col].bg == "default" for col in range(7, 80)),
                "suffix inherited old background")
        require(child.screen.display[1] == " " * 80, "nonempty-to-empty row retained content")
        require(all(child.screen.buffer[1][col].bg == "default" for col in range(80)),
                "empty row inherited background")
        require(child.screen.display[23] == "bottom" + " " * 74, "bottom suffix not cleared")
        if plain:
            require(frame.index(b"\x1b[0J") < frame.index("short界".encode()),
                    "the live region was cleared after its content was painted")
        else:
            require(frame.index("short界".encode()) < frame.index(b"\x1b[1;8H\x1b[0m\x1b[0K"),
                    "suffix erased before overwrite or at wrong column")
        # Growing again must restore the full row, including both halves of the wide glyph.
        start = len(child.output)
        child.command("REFRESH")
        child.expect("REFRESH_READY")
        frame = wait_frame(child, start)
        require(child.screen.display[0].startswith("REFRESH_FULL"), "regrown row missing")
        require(child.screen.buffer[0][78].data == "界", "regrown right-edge wide glyph wrong")
        if plain:
            require_inline_frame(frame, b"REFRESH_FULL")
        else:
            first_row = frame.split(b"\x1b[2;1H", 1)[0]
            require(b"\x1b[2K" not in first_row and b"\x1b[0K" not in first_row,
                    "regrown full row was cleared")
        # A same-model ACK is idle: no synchronization or other output should follow.
        start = len(child.output)
        child.command("SIZE")
        child.expect("SIZE 24 80")
        deadline = time.monotonic() + 0.15
        while time.monotonic() < deadline:
            child.pump(0.02)
        require(len(child.output) == start, "idle model emitted terminal traffic")
        child.command("CLOSE")
        child.expect("REOPENED " + NATIVE_IDENTITY)
        os.write(child.master, b"z")
        child.expect("KEY z 7a")
        child.finish()
    print(f"PASS synchronized overwrite-first/wide-edge/background/suffix/idle refresh (plain={plain})")


def check_rapid_resize(java_command):
    """A shrink/grow between owner samples invalidates cells clipped by the narrow terminal."""
    with PtyChild(java_command) as child:
        child.expect("READY " + NATIVE_IDENTITY)
        child.terminal_contains("Native 界")
        while child.pump(0.1):
            pass
        start = len(child.output)
        child.command("REFRESH")
        child.expect("REFRESH_READY")
        wait_frame(child, start)
        require(child.screen.buffer[0][78].data == "界", "initial right edge missing")
        # Pause the owned child so it cannot sample the intermediate width. SIGWINCH remains
        # pending across SIGCONT, while the emulator has already discarded the clipped cells.
        os.kill(child.process.pid, signal.SIGSTOP)
        os.waitpid(child.process.pid, os.WUNTRACED)
        try:
            child.resize(24, 60)
            child.screen.resize(lines=24, columns=60)
            child.resize(24, 80)
            child.screen.resize(lines=24, columns=80)
            require(child.screen.buffer[0][78].data != "界", "shrink did not clip the test glyph")
        finally:
            os.kill(child.process.pid, signal.SIGCONT)
        deadline = time.monotonic() + 5
        while (child.screen.buffer[0][78].data != "界" or child.screen.display[1] != "b" * 80):
            require(time.monotonic() < deadline, "rapid shrink/grow left cached right-edge cells missing")
            child.pump(0.05)
        child.command("CLOSE")
        child.expect("REOPENED " + NATIVE_IDENTITY)
        os.write(child.master, b"z")
        child.expect("KEY z 7a")
        child.finish()
    print("PASS rapid shrink/grow invalidates the cached frame")


def check_refresh_wide(java_command, plain):
    """A wide glyph at the right edge must survive a full row and split cleanly when narrowed."""
    with PtyChild([*java_command, *(["--plain"] if plain else [])]) as child:
        child.expect("READY " + NATIVE_IDENTITY)
        child.terminal_contains("Native 界")
        while child.pump(0.1):
            pass
        start = len(child.output)
        child.command("REFRESH_WIDE")
        child.expect("REFRESH_WIDE_READY")
        frame = wait_frame(child, start)
        require(child.screen.buffer[0][78].data == "界", "wide glyph missing at the right edge")
        require(child.screen.buffer[0][78].bg == "000000", "wide glyph background lost")
        require(child.screen.buffer[0][79].bg == "000000", "wide glyph continuation background lost")
        require(child.screen.display[0].rstrip() == "w" * 78 + "界", "wide row content mismatch")
        require(child.screen.display[1] == "b" * 80, "wide second row mismatch")
        if plain:
            require_inline_frame(frame, ("w" * 78 + "界").encode())
        else:
            first_row = frame.split(b"\x1b[2;1H", 1)[0]
            require(b"\x1b[2K" not in first_row and b"\x1b[0K" not in first_row,
                    "full wide row was cleared")
        start = len(child.output)
        child.command("REFRESH_WIDE_SHORT")
        child.expect("REFRESH_WIDE_SHORT_READY")
        frame = wait_frame(child, start)
        require(child.screen.buffer[0][78].data == "y", "narrow replacement missing")
        require(child.screen.buffer[0][79].data == " ",
                "stale second half of the wide glyph survived")
        require(child.screen.buffer[0][79].bg == "default",
                "stale wide background survived")
        require(child.screen.display[0].rstrip() == "w" * 78 + "y", "narrow row content mismatch")
        payload = ("w" * 78 + "y").encode()
        erase = b"\x1b[1;80H\x1b[0m\x1b[0K"
        if plain:
            require(frame.index(b"\x1b[0J") < frame.index(payload),
                    "the live region was cleared after its content was painted")
        else:
            require(erase in frame, "suffix erase missing or at the wrong column")
            # A frame after the first one writes the cells that changed rather than the rows that
            # hold them, so the replacement is the addressed cell at the last column, not the whole
            # row it sits in; the row's content is what the screen assertions above check.
            replacement = b"\x1b[1;79H"
            require(replacement in frame, "the replacement cell was not addressed where it is")
            require(frame.index(replacement) < frame.index(erase),
                    "suffix erased before the replacement was painted")
        require(child.screen.display[1] == " " * 80, "second row not cleared")
        require(all(child.screen.buffer[1][col].bg == "default" for col in range(80)),
                "cleared second row inherited background")
        start = len(child.output)
        child.command("REFRESH_WIDE")
        child.expect("REFRESH_WIDE_READY")
        frame = wait_frame(child, start)
        require(child.screen.buffer[0][78].data == "界", "regrown wide glyph missing")
        require(child.screen.buffer[0][79].data == "", "regrown wide glyph not continuous")
        if plain:
            require_inline_frame(frame, ("w" * 78 + "界").encode())
        else:
            first_row = frame.split(b"\x1b[2;1H", 1)[0]
            require(b"\x1b[0K" not in first_row, "regrown full wide row was cleared")
        child.command("CLOSE")
        child.expect("REOPENED " + NATIVE_IDENTITY)
        os.write(child.master, b"z")
        child.expect("KEY z 7a")
        child.finish()
    print(f"PASS synchronized wide-edge survival/split/regrow refresh (plain={plain})")


def check_image_output(java_command):
    for no_color in (False, True):
        command = [*java_command, *(["native"] if no_color else [])]
        with PtyChild(command, control=False, env={"NO_COLOR": "1" if no_color else ""}) as child:
            child.terminal_contains("image-output-ready HALF_BLOCK")
            require(child.screen.display[0].startswith("\u2580\u2580"), "image first row missing")
            require(child.screen.display[1].startswith("\u2580\u2580"), "image odd final row missing")
            if no_color:
                require(child.screen.buffer[0][0].fg == "default", "NO_COLOR image still used color")
                require(b"\x1b_G" not in child.output, "NO_COLOR selected native image output")
            else:
                require(child.screen.buffer[0][0].fg == "ff0000", "image upper pixel lost")
                require(child.screen.buffer[0][0].bg == "00ff00", "image lower pixel lost")
                require(child.screen.buffer[0][1].fg == "0000ff", "image second column lost")
                require(child.screen.buffer[1][1].fg == "000000", "image alpha background lost")
            os.write(child.master, b"q")
            child.finish(cursor_was_hidden=False)
        print(f"PASS low-level image half-block output, alpha and NO_COLOR (no_color={no_color})")


def check_pending_escape(java_command):
    """Strict reproducer for the published provider's pending-poll/reopen race."""
    with PtyChild(java_command) as child:
        child.expect("READY " + NATIVE_IDENTITY)
        child.command("CLOSE_IDLE")
        child.expect("WAITING")
        os.write(child.master, b"\x1b")
        time.sleep(0.01)  # Stop inside the 30 ms Escape disambiguation window.
        child.command("CLOSE")
        child.expect("REOPENED " + NATIVE_IDENTITY)
        os.write(child.master, b"z")
        child.expect("KEY z 7a")
        child.finish()
    print("PASS pending-Escape stop/immediate reopen")


def check_cleanup(java_command, command, expected_code):
    with PtyChild(java_command) as child:
        child.expect("READY " + NATIVE_IDENTITY)
        if command == "SIGTERM":
            child.process.send_signal(signal.SIGTERM)
        else:
            child.command(command)
        child.finish(expected_code)
        for mode in (b"\x1b[?1049l", b"\x1b[?2004l", b"\x1b[?1000l", b"\x1b[?1006l"):
            require(mode in child.output, f"mode not restored after {command}: {mode!r}")
    print(f"PASS native restoration after {command}")


def check_harness():
    with PtyChild([str(ROOT / "run-ai-harness.sh"), "--skip-build"], control=False) as child:
        def screen_until(predicate, description):
            deadline = time.monotonic() + 30
            while not predicate("\n".join(child.screen.display)):
                require(time.monotonic() < deadline, f"harness screen never showed {description}")
                require(child.process.poll() is None, f"harness exited before {description}")
                child.pump(0.1)

        child.terminal_contains("\x1b[?2004h")
        screen_until(lambda text: "Yes" in text and "deepseek" in text, "loaded session")
        os.write(child.master, b"hierarchy probe")
        screen_until(lambda text: "hierarchy probe" in text, "mounted editor input")
        os.write(child.master, b"\x1bc")  # Alt+C: copy the editor output through the parent.
        screen_until(lambda text: "Copied" in text, "clipboard output")
        os.write(child.master, b"\x16")  # Ctrl+V: session-local clipboard, no external clipboard read.
        screen_until(lambda text: "hierarchy probehierarchy probe" in text, "paste dispatch")
        os.write(child.master, b"\r")
        screen_until(lambda text: "Working" in text, "submitted workflow")
        screen_until(lambda text: "I'll start by reading" in text, "timer-driven response")
        # Stop response pacing before comparing scroll positions; no task races the assertion.
        os.write(child.master, b"\x1b")
        screen_until(lambda text: "esc again to stop" in text, "cancel confirmation")
        os.write(child.master, b"\x1b")
        screen_until(lambda text: "Stopped." in text and "Working" not in text, "canceled workflow")
        before = tuple(child.screen.display[:17])
        os.write(child.master, b"\x1b[5~")  # PageUp, then End returns to the latest transcript.
        screen_until(lambda text: tuple(child.screen.display[:17]) != before, "scroll movement")
        os.write(child.master, b"\x1b[F")
        screen_until(lambda text: "Stopped." in text, "scroll to bottom")
        child.screen.resize(20, 64)
        child.resize(20, 64)
        screen_until(lambda text: "deepseek" in child.screen.display[-2], "resized footer")
        require(len(child.screen.display) == 20 and all(len(row) == 64 for row in child.screen.display),
                "harness resize did not match the emulated viewport")
        os.write(child.master, b"\x03")
        screen_until(lambda text: "ctrl+c again to exit" in text, "exit confirmation")
        os.write(child.master, b"\x03")
        child.finish()
    print("PASS packaged hierarchical AI Harness editor/output/paste/submit/timer/scroll/resize/cancel/quit/restoration")


def wait_panes(child, log_top, trace_top, pane, focus, description):
    """Wait until both panes and the focus row show the expected window, then return every row."""
    expected = [f"L{log_top + index:02d}   T{trace_top + index:02d}" for index in range(pane)]
    expected.append(focus)

    def rows():
        return [row.rstrip() for row in child.screen.display]

    deadline = time.monotonic() + 15
    while rows()[:len(expected)] != expected:
        require(time.monotonic() < deadline,
                f"screen never showed {description}: {rows()[:len(expected)]!r}")
        require(child.process.poll() is None, f"probe exited before {description}")
        child.pump(0.1)
    return rows()


def check_scrollview(java_command):
    """Two independent ScrollView panes painted through the real terminal and read back."""
    # A 24-row PTY gives each pane (24 - 1) // 2 = 11 rows; the focus row follows the panes.
    pane = 11
    focus_log = f"focus log 24x80 rows {pane}"
    # No control socket: this probe is driven only by the keys a terminal sends.
    with PtyChild(java_command, control=False) as child:
        child.terminal_contains("L00")
        rows = wait_panes(child, 0, 0, pane, focus_log, "both panes at their first window")
        require(rows[pane + 1] == "", "a pane painted below its allocated rows")

        # Down moves the focused pane by one row and leaves its sibling alone.
        os.write(child.master, b"\x1b[B")
        wait_panes(child, 1, 0, pane, focus_log, "one row of scroll in the focused pane")

        # PageDown moves by the pane's visible height, not by the terminal's height.
        os.write(child.master, b"\x1b[6~")
        wait_panes(child, 12, 0, pane, focus_log, "a page of the pane's own height")

        # End shows the last window.
        os.write(child.master, b"\x1b[F")
        wait_panes(child, 29, 0, pane, focus_log, "the last window of the focused pane")

        # Tab is a parent capture binding: the other pane takes the keys from now on.
        focus_trace = f"focus trace 24x80 rows {pane}"
        os.write(child.master, b"\t")
        wait_panes(child, 29, 0, pane, focus_trace, "focus moved to the second pane")
        os.write(child.master, b"\x1b[B")
        wait_panes(child, 29, 1, pane, focus_trace, "one row of scroll in the second pane")
        os.write(child.master, b"\x1b[F")
        wait_panes(child, 29, 29, pane, focus_trace, "the second pane's last window")

        # A real resize re-allocates both panes: 20 rows make each pane 9 rows tall, the offsets
        # they were left at are kept, and nothing paints past the panes.
        child.screen.resize(20, 64)
        child.resize(20, 64)
        rows = wait_panes(child, 29, 29, 9, "focus trace 20x64 rows 9",
                          "both panes re-allocated by the resize")
        require(len(rows) == 20, f"emulated screen kept {len(rows)} rows")
        require(all(row == "" for row in rows[10:]), "content painted below the resized panes")

        os.write(child.master, b"\x03")
        child.finish()
    print("PASS two independent ScrollView panes: keys/focus/page/end/resize/restoration")


def screen_until(child, predicate, description, timeout=30):
    """Wait until the emulated screen satisfies predicate(joined rows), then return those rows."""
    deadline = time.monotonic() + timeout
    while not predicate("\n".join(child.screen.display)):
        require(time.monotonic() < deadline, f"screen never showed {description}")
        require(child.process.poll() is None, f"child exited before {description}")
        child.pump(0.1)
    return "\n".join(child.screen.display)


def check_keymap_help_parity(java_command):
    """Phase 01: one key map drives routing and help in a real terminal."""
    with PtyChild(java_command, control=False) as child:
        screen_until(
            child,
            lambda text: "left=alpha right=alpha focus=left help=compact keys=default last=none" in text,
            "the compact status row")
        screen_until(
            child,
            lambda text: "? toggle help \u2022 r remap help key \u2022 tab next field \u2022 q quit" in text,
            "the scene's compact help")
        screen_until(
            child,
            lambda text: "\u2191 move up \u2022 \u2193 move down \u2022 enter choose \u2022 esc cancel" in text,
            "the selector's compact help")
        # The focused selector paints the active style; its sibling does not.
        require(child.screen.buffer[3][0].reverse, "the focused selector was not painted active")
        require(not child.screen.buffer[3][16].reverse, "the unfocused selector was painted active")

        # A navigation alias moves the focused selector and nothing else.
        os.write(child.master, b"\x1b[B")
        screen_until(
            child,
            lambda text: "left=beta right=alpha" in text and "last=down" in text,
            "one row of navigation in the left selector")

        # Expanded help lists every group, in map order.
        os.write(child.master, b"?")
        screen_until(
            child,
            lambda text: ("help=full" in text
                          and "Help    ? toggle help    r remap help key" in text
                          and "Focus    tab next field" in text
                          and "Navigation    \u2191 move up    \u2193 move down" in text),
            "the grouped full help")

        # Tab moves focus: the same gesture now reaches the other selector.
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=right" in text, "focus moving to the right selector")
        require(not child.screen.buffer[3][0].reverse, "focus did not leave the left selector")
        require(child.screen.buffer[3][16].reverse, "the right selector did not become active")
        os.write(child.master, b"\x1b[B")
        screen_until(
            child,
            lambda text: "left=beta right=beta focus=right" in text,
            "one row of navigation in the right selector")

        # The remap changes the alias help advertises and disables the action that did the remapping.
        os.write(child.master, b"r")
        screen_until(
            child,
            lambda text: "keys=remapped" in text and "Help    ?/h toggle help" in text,
            "the remapped action in help")
        require("r remap help key" not in "\n".join(child.screen.display),
                "a disabled action was still advertised in help")
        before = "\n".join(child.screen.display)
        os.write(child.master, b"r")
        deadline = time.monotonic() + 0.3
        while time.monotonic() < deadline:
            child.pump(0.05)
        require("\n".join(child.screen.display) == before, "a disabled action still fired")

        # The remapped alias fires the same message as the original one did.
        os.write(child.master, b"h")
        screen_until(
            child,
            lambda text: "help=compact" in text and "last=toggle-help" in text,
            "the remapped alias toggling help")

        # Choosing in the focused selector crosses the mount boundary as a semantic output.
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "last=chosen:beta" in text, "the selector's chosen output")

        # A narrower terminal truncates help by columns instead of wrapping it, and the wider
        # terminal brings the whole line back.
        child.screen.resize(24, 40)
        child.resize(24, 40)
        screen_until(child, lambda text: any(row.endswith("\u2026") for row in text.split("\n")),
                     "help truncated at the narrower width")
        require(all(len(row) == 40 for row in child.screen.display), "the narrow screen kept its old width")
        child.screen.resize(24, 80)
        child.resize(24, 80)
        screen_until(child, lambda text: "?/h toggle help \u2022 tab next field \u2022 q quit" in text,
                     "the full help line after widening again")

        os.write(child.master, b"q")
        child.finish()
    print("PASS parity 01 one key map drives routing and compact/expanded help")


def check_packaged_demo(java_command, markers, keys=(b"\x03",)):
    """The packaged phase demo starts, renders and quits without an example-only dependency."""
    with PtyChild(java_command, control=False) as child:
        for marker in markers:
            screen_until(child, lambda text, marker=marker: marker in text,
                         f"the packaged demo showing {marker!r}")
        for key in keys:
            os.write(child.master, key)
            # Each keystroke gets its own moment: a guarded demo answers the first one with a
            # prompt rather than an exit, and the next one confirms it.
            child.pump(0.3)
        child.finish()
    print("PASS packaged parity demo renders and quits without example-only dependencies")


def check_keyboard_focus_parity(java_command):
    """Phase 02: enhanced key events and terminal focus in a real terminal."""
    with PtyChild(java_command, control=False) as child:
        screen_until(
            child,
            lambda text: "caps=none event-types=off focus-report=on" in text,
            "the capability row before the terminal answers")
        screen_until(
            child,
            lambda text: "last=none term=focused" in text and "editor= field=focused out=none" in text,
            "the state row and the focused field")
        screen_until(
            child,
            lambda text: "? toggle help \u2022 f toggle field focus \u2022 q quit" in text,
            "the scene's compact help")

        # The session asked once, before any reply: pushed the flags it requested, asked which are
        # enabled, and switched on focus reporting. Nothing is assumed from TERM, so a terminal that
        # never answers simply keeps sending legacy input.
        transcript = child.output.decode("utf-8", errors="replace")
        require("\x1b[?1004h" in transcript, "focus reporting was not requested")
        require("\x1b[>27u" in transcript, "the requested enhancement flags were not pushed")
        require(transcript.count("\x1b[?u") == 1, "the flag query was not sent exactly once")
        require(transcript.count("\x1b[>27u") == 1, "the flags were pushed more than once")
        require("\x1b[<u" not in transcript, "the keyboard stack was popped before the session ended")

        # A terminal that supports the protocol answers; the demo reports only what it was told.
        os.write(child.master, b"\x1b[?27u")
        screen_until(child, lambda text: "caps=27 event-types=on" in text, "the reported flags")

        # Disambiguation: Enter, Shift+Enter, Tab and Ctrl+I are four different gestures.
        os.write(child.master, b"\x1b[13;2u")
        screen_until(child, lambda text: "last=press:shift+enter" in text, "a Shift+Enter press")
        os.write(child.master, b"\r")
        # Enter is the editor's submit, so both the event and the output it produced are reported.
        screen_until(child, lambda text: "last=press:enter" in text and "out=submit" in text,
                     "an Enter press and the submit it produced")
        os.write(child.master, b"\x1b[105;5u")
        screen_until(child, lambda text: "last=press:ctrl+i" in text, "a Ctrl+I press")
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "last=press:tab" in text, "a Tab press")
        require("editor=" in "\n".join(child.screen.display), "the field disappeared")

        # Event types: a release is reported with the text its press carried, and inserts none of it.
        os.write(child.master, b"\x1b[97;1:3;97u")
        screen_until(child, lambda text: "last=release:a" in text, "an 'a' release")
        require("editor=a " not in "\n".join(child.screen.display),
                "a key release inserted the text its press would have")
        os.write(child.master, b"\x1b[97;1;97u")
        screen_until(child, lambda text: "editor=a " in text and "last=press:a" in text, "an 'a' press")
        os.write(child.master, b"\x1b[97;1:2;97u")
        screen_until(child, lambda text: "editor=aa " in text and "last=repeat:a" in text, "an 'a' repeat")

        # Terminal focus is its own fact and never model focus.
        os.write(child.master, b"\x1b[O")
        screen_until(child, lambda text: "term=blurred" in text and "last=focus-lost" in text,
                     "the terminal losing focus")
        require("field=focused" in "\n".join(child.screen.display),
                "losing the terminal window changed the field's own focus")
        require("editor=aa " in "\n".join(child.screen.display), "losing focus changed the draft")
        os.write(child.master, b"\x1b[I")
        screen_until(child, lambda text: "term=focused" in text and "last=focus-gained" in text,
                     "the terminal regaining focus")

        # Field focus stays model data the application owns, and an unfocused field takes no text.
        os.write(child.master, b"f")
        screen_until(child, lambda text: "field=blurred" in text, "the field losing model focus")
        os.write(child.master, b"z")
        screen_until(child, lambda text: "last=press:z" in text, "a gesture an unfocused field ignores")
        require("editor=aa " in "\n".join(child.screen.display), "an unfocused field took text")
        os.write(child.master, b"f")
        screen_until(child, lambda text: "field=focused" in text, "the field regaining model focus")

        # Legacy typing still works after every one of these reports.
        os.write(child.master, b"b")
        screen_until(child, lambda text: "editor=aab " in text, "legacy typing after negotiation")

        os.write(child.master, b"q")
        child.finish()
        final = child.output.decode("utf-8", errors="replace")
        require(final.count("\x1b[<u") == 1, "the keyboard stack was not popped exactly once")
        require("\x1b[?1004l" in final, "focus reporting was not switched off")
        require(final.index("\x1b[<u") < final.index("\x1b[?1049l"),
                "the keyboard stack was popped after leaving the alternate screen")
    print("PASS parity 02 enhanced key events and terminal focus")


def cursor_at(child, row, column, description):
    """Wait until the emulated terminal's own cursor is on one cell."""
    deadline = time.monotonic() + 10
    while (child.screen.cursor.y, child.screen.cursor.x) != (row, column):
        actual = (child.screen.cursor.y, child.screen.cursor.x)
        require(time.monotonic() < deadline, f"{description}: cursor is at {actual}, expected {(row, column)}")
        require(child.process.poll() is None, f"child exited before {description}")
        child.pump(0.05)


def check_cursor_parity(java_command):
    """Phase 03: native and virtual cursor presentation in a real terminal."""
    with PtyChild(java_command, control=False) as child:
        screen_until(
            child,
            lambda text: "strategy=native shape=block mode=blink color=none cursor=visible" in text,
            "the presentation row")
        screen_until(child, lambda text: "focus=left caret=0:0" in text, "the position row")
        screen_until(child, lambda text: "m native/virtual" in text, "the scene's compact help")

        # The terminal's own cursor is moved onto the focused pane's caret cell. The panes start on
        # row 3, so their first content row is 4, and the first pane's text starts inside its frame.
        cursor_at(child, 4, 1, "the caret cell of the left pane")
        transcript = child.output.decode("utf-8", errors="replace")
        require("\x1b[1 q" in transcript, "the blinking block shape was not requested")
        require("\x1b[?25h" in transcript, "the cursor was never shown")

        # One cell of movement moves both the model's caret and the terminal's cursor.
        os.write(child.master, b"l")
        screen_until(child, lambda text: "caret=0:1" in text, "one cell right")
        cursor_at(child, 4, 2, "the caret cell after one step right")
        os.write(child.master, b"j")
        screen_until(child, lambda text: "caret=1:0" in text, "one row down, at the start of the line")
        cursor_at(child, 5, 1, "the caret cell after one step down")

        # The second row holds a two-column glyph. Stepping one cell at a time onto its second half
        # leaves the model's caret at cell 6, and the terminal cursor is put on the glyph instead:
        # a terminal cursor cannot address half a cell.
        os.write(child.master, b"l" * 6)
        screen_until(child, lambda text: "caret=1:6" in text, "the second half of the wide glyph")
        cursor_at(child, 5, 6, "the glyph the caret is drawn on")

        # Both panes declare a caret; only the focused pane's is shown, and it is the pane painted
        # first even though the other pane paints later.
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=right caret=0:0" in text, "focus moving to the right pane")
        cursor_at(child, 4, 41, "the caret cell of the right pane")

        # Shapes and blink are DECSCUSR parameters, not separate controls.
        os.write(child.master, b"s")
        screen_until(child, lambda text: "shape=underline" in text, "the underline shape")
        require("\x1b[3 q" in child.output.decode("utf-8", errors="replace"),
                "the blinking underline style was not written")
        os.write(child.master, b"s")
        screen_until(child, lambda text: "shape=bar" in text, "the bar shape")
        require("\x1b[5 q" in child.output.decode("utf-8", errors="replace"),
                "the blinking bar style was not written")
        os.write(child.master, b"b")
        screen_until(child, lambda text: "mode=steady" in text, "a steady caret")
        require("\x1b[6 q" in child.output.decode("utf-8", errors="replace"),
                "the steady bar style was not written")
        os.write(child.master, b"b")
        screen_until(child, lambda text: "mode=blink" in text, "a blinking caret again")

        # The caret color is OSC 12, and the session gives the terminal's own color back on exit.
        os.write(child.master, b"c")
        screen_until(child, lambda text: "color=#1e90ff" in text, "the caret color")
        require("\x1b]12;rgb:1e/90/ff\x1b\\" in child.output.decode("utf-8", errors="replace"),
                "the caret color was not written")
        cursor_at(child, 4, 41, "the caret cell after the color changed")

        # A virtual caret is a painted cell: the layout reverses it and the terminal cursor is
        # hidden, so no caret is left flying around the frame.
        start = len(child.output)
        os.write(child.master, b"m")
        screen_until(child, lambda text: "strategy=virtual" in text and "cursor=visible" in text,
                     "the virtual strategy")
        after = child.output[start:].decode("utf-8", errors="replace")
        require("\x1b[?25l" in after, "the terminal cursor was not hidden for a painted caret")
        require(child.screen.buffer[4][41].reverse, "the virtual caret did not reverse its cell")
        require(not child.screen.buffer[4][42].reverse, "the virtual caret reversed a neighboring cell")

        # Only a painted caret needs a clock: the scene blinks it half-period by half-period.
        screen_until(child, lambda text: "cursor=hidden" in text, "the dark half of the blink")
        require(not child.screen.buffer[4][41].reverse, "the dark half still marked the cell")
        screen_until(child, lambda text: "cursor=visible" in text, "the lit half of the blink")
        require(child.screen.buffer[4][41].reverse, "the lit half did not mark the cell")

        # Back to the terminal's own cursor, then out.
        os.write(child.master, b"m")
        screen_until(child, lambda text: "strategy=native" in text and "cursor=visible" in text,
                     "the native strategy again")
        cursor_at(child, 4, 41, "the caret cell after switching back")
        os.write(child.master, b"q")
        child.finish()
        final = child.output.decode("utf-8", errors="replace")
        require(final.count("\x1b[0 q") == 1, "the caret shape was not reset exactly once")
        require(final.count("\x1b]112\x1b\\") == 1, "the caret color was not reset exactly once")
        require(final.index("\x1b[0 q") < final.index("\x1b[?1049l"),
                "the caret shape was reset after leaving the alternate screen")
    print("PASS parity 03 native and virtual cursor presentation")


def check_editor_parity(java_command):
    """Phase 04: two editors, wrapped rows, a gutter and a dynamic height in a real terminal."""
    with PtyChild(java_command, control=False) as child:
        screen_until(
            child,
            lambda text: ("focus=left lines=off height=fixed cursor=painted placeholder=off wrap=38"
                          in text),
            "the presentation row")
        screen_until(child, lambda text: "left len=50 caret=50 rows=2" in text, "the left draft")
        screen_until(child, lambda text: "right len=48 caret=48 rows=2" in text, "the right draft")
        rows_at_rest = pane_rows(child, "left")
        require(rows_at_rest == 2, f"the left pane wrapped into {rows_at_rest} rows, not two")

        # Typing reaches the focused pane and only that pane: the two drafts are independent.
        os.write(child.master, b"Z")
        screen_until(
            child,
            lambda text: "left len=51 caret=51" in text and "right len=48 caret=48" in text,
            "typing in the left pane")

        # The block caret is painted in the focused pane and nowhere else.
        left, right = pane_columns(child)
        require(block_in(child, left), "the focused pane painted no block caret")
        require(not block_in(child, right), "an unfocused pane painted a caret")

        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=right" in text, "focus moving to the right pane")
        os.write(child.master, b"Q")
        screen_until(
            child,
            lambda text: "right len=49 caret=49" in text and "left len=51 caret=51" in text,
            "typing in the right pane")
        require(block_in(child, right), "the newly focused pane painted no block caret")
        require(not block_in(child, left), "the pane focus left still painted a caret")

        # A height that follows the content, and a gutter numbered once per logical line.
        os.write(child.master, b"\x1bt")
        screen_until(child, lambda text: "height=dynamic" in text and "last=height" in text,
                     "the dynamic height")
        os.write(child.master, b"\x1bl")
        screen_until(child, lambda text: "lines=on" in text and "last=line-numbers" in text,
                     "the line-number gutter")
        require(pane_row(child, "left").startswith("1 "), "the left pane painted no line number")
        require(pane_row(child, "right").startswith("1 "), "the right pane painted no line number")
        require(pane_row(child, "left", 1).startswith("  "),
                "a wrapped row was numbered as a line of its own")

        # An empty draft shows the placeholder, and submitting carries no placeholder text.
        os.write(child.master, b"\x1bp")
        screen_until(child, lambda text: "placeholder=on" in text and "last=placeholder" in text,
                     "the placeholder policy")
        os.write(child.master, b"\x01")
        os.write(child.master, b"\x7f")
        screen_until(
            child,
            lambda text: "right len=0 caret=0" in text and "ype something" in text,
            "the placeholder of an empty draft")
        os.write(child.master, b"\x1b\r")
        screen_until(child, lambda text: "last=right-submit:0" in text,
                     "submitting an empty draft without the placeholder")

        # A native caret is the terminal's own cursor, so the block stops being painted text.
        os.write(child.master, b"\x1bm")
        screen_until(child, lambda text: "cursor=native" in text and "last=caret" in text,
                     "the native caret")
        require(not block_in(child, right), "a native caret was still painted as text")

        # A narrower terminal re-wraps both panes, keeps the caret visible and grows the row count.
        child.screen.resize(24, 40)
        child.resize(24, 40)
        screen_until(
            child,
            lambda text: "wrap=18" in text and "right len=0 caret=0 rows=" in text,
            "the panes re-wrapped at the narrower width")
        require(all(len(row) == 40 for row in child.screen.display), "the narrow screen kept its old width")
        narrow = pane_rows(child, "left")
        require(narrow > rows_at_rest, f"the wrapped pane kept {narrow} rows at the narrower width")
        require(cursor_inside(child, 4, 19, 0, 40), "the caret left the terminal at the narrower width")
        child.screen.resize(24, 80)
        child.resize(24, 80)
        screen_until(child, lambda text: "wrap=38" in text, "the panes re-wrapped at the wider width")
        require(pane_rows(child, "left") == rows_at_rest,
                "the wider terminal did not restore the original row count")

        os.write(child.master, b"\x1b")
        child.finish()
    print("PASS parity 04 two editors, wrapped rows, gutter, dynamic height and resize")



def check_text_input_parity(java_command):
    """Phase 05: one-row fields, validation, completion and a masked password in a real terminal."""
    with PtyChild(java_command, control=False) as child:
        screen_until(
            child,
            lambda text: "fields=3 focus=nickname completion=on form=incomplete" in text,
            "the form's headline row")
        screen_until(
            child,
            lambda text: "nickname len=0 caret=0 scroll=0 cand=none" in text,
            "the nickname field's status row")
        screen_until(
            child,
            lambda text: "email len=0 caret=0 scroll=0 valid=no msg=empty" in text,
            "the email field's status row")
        screen_until(
            child,
            lambda text: "password len=0 caret=0 scroll=0 masked=yes" in text,
            "the password field's status row")
        screen_until(child, lambda text: "tab next field" in text, "the form's compact help")

        # Typing reaches the focused field only, and its row stays one row while it grows.
        os.write(child.master, b"br")
        screen_until(
            child,
            lambda text: "nickname len=2 caret=2 scroll=0 cand=braxton" in text
            and "email len=0 caret=0" in text,
            "typing in the nickname field")
        nickname = field_row(child, 6)
        require("br" in nickname and "axton" in nickname and "\u2588axton" in nickname,
                f"the nickname field painted no ghost suffix behind the caret: {nickname!r}")

        # Tab completes the field instead of moving focus; with nothing left to add it moves on.
        os.write(child.master, b"\t")
        screen_until(
            child,
            lambda text: "nickname len=7 caret=7 scroll=0 cand=braxton valid=yes" in text
            and "focus=nickname" in text,
            "accepting the candidate")
        require("braxton" in field_row(child, 6),
                f"the accepted candidate was not painted: {field_row(child, 6)!r}")
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=email" in text,
                     "focus moving on once the field had nothing to complete")

        # An invalid submission is reported and changes nothing.
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "last=reject:email:empty" in text,
                     "the rejected submission of an empty email")

        # A valid field satisfies its validator, and focus reaches the password.
        os.write(child.master, b"a@b.c")
        screen_until(
            child,
            lambda text: "email len=5 caret=5 scroll=0 valid=yes msg=none" in text,
            "a valid email")
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=password" in text, "focus moving to the password")
        os.write(child.master, b"hunter2!")
        screen_until(
            child,
            lambda text: "password len=8 caret=8 scroll=0 masked=yes valid=yes" in text,
            "a masked password")
        require("hunter" not in "\n".join(child.screen.display),
                "the password field painted its cleartext")
        require("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" in field_row(child, 8),
                f"the password field painted no mask glyphs: {field_row(child, 8)!r}")
        screen_until(child, lambda text: "form=ready" in text, "a form every field accepts")

        # Submitting a complete form reports the field's own submission.
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "last=submit:password:8" in text,
                     "the submission of a complete form")

        # A name wider than the field scrolls horizontally instead of wrapping.
        os.write(child.master, b"\x1b[Z")
        screen_until(child, lambda text: "focus=email" in text, "shift+tab back to the email")
        os.write(child.master, b"\x1b[Z")
        screen_until(child, lambda text: "focus=nickname" in text, "shift+tab back to the nickname")
        os.write(child.master, b"0123456789abcdef")
        screen_until(
            child,
            lambda text: "nickname len=23 caret=23 scroll=8" in text,
            "a nickname wider than its field")
        scrolled = field_row(child, 6)
        require("bra" not in scrolled, f"the scrolled field still painted its first characters: {scrolled!r}")
        require(len(scrolled.split(": ", 1)[1]) <= 16,
                f"the scrolled field painted more than its width: {scrolled!r}")

        os.write(child.master, b"\x1b")
        child.finish()
    print("PASS parity 05 one-row fields, validation, completion and a masked password")


def check_paginator_parity(java_command):
    """Phase 06: page state, slicing bounds, dots/numeric indicators and a shrinking list."""
    with PtyChild(java_command, control=False) as child:
        screen_until(
            child,
            lambda text: "page=1/9 items=84 per=10 bounds=[0,10) selected=0 mode=dots wrap=off" in text,
            "the page status row")
        screen_until(child, lambda text: "last=none" in text, "the scene's action row")
        screen_until(child, lambda text: "> item-01" in text and "item-10" in text,
                     "the first page's items, with the selection marked")
        screen_until(child, lambda text: "\u25CF \u25CB \u25CB \u25CB \u25CB \u25CB \u25CB \u2026" in text,
                     "the windowed dot indicator")
        screen_until(child, lambda text: "\u2190 previous page \u2022 \u2192 next page" in text,
                     "the pager's compact help")
        screen_until(child, lambda text: "g dots/numeric" in text, "the scene's compact help")

        # A gesture the pager does not own bubbles to the scene, which owns the selection.
        os.write(child.master, b"\x1b[B")
        screen_until(child, lambda text: "selected=1" in text and "last=selected-1" in text,
                     "the selection moved with a key the pager does not handle")
        require("> item-02" in field_row(child, 4),
                f"the moved selection was not marked: {field_row(child, 4)!r}")
        os.write(child.master, b"\x1b[A")
        screen_until(child, lambda text: "selected=0" in text and "last=selected-0" in text,
                     "the selection moved back")
        require("> item-01" in field_row(child, 3),
                f"the restored selection was not marked: {field_row(child, 3)!r}")

        # One page forward moves the slice, not just the indicator.
        os.write(child.master, b"\x1b[C")
        screen_until(child, lambda text: "page=2/9 items=84 per=10 bounds=[10,20)" in text, "page two")
        require("item-11" in field_row(child, 3) and "item-01" not in field_row(child, 3),
                f"page two did not paint its own items: {field_row(child, 3)!r}")

        # The last page is short: four items, and the rows after them stay padded.
        os.write(child.master, b"\x1b[F")
        screen_until(child, lambda text: "page=9/9 items=84 per=10 bounds=[80,84)" in text, "the last page")
        require("item-81" in field_row(child, 3) and "item-84" in field_row(child, 6),
                f"the last page did not paint items 81-84: {[field_row(child, r) for r in range(3, 7)]!r}")
        require(all(not field_row(child, row) for row in range(7, 13)),
                "the rows past the last page were not padded, so the indicator moved")

        # Wrap is off: next at the end changes nothing at all.
        os.write(child.master, b"\x1b[C")
        screen_until(child, lambda text: "wrap=off" in text, "the unchanged last page")
        require("page=9/9 items=84 per=10 bounds=[80,84)" in "\n".join(child.screen.display),
                "next at the end moved the page with wrap off")

        # Turning wrap on makes the same gesture cross to the first page.
        os.write(child.master, b"w")
        screen_until(child, lambda text: "wrap=on" in text, "wrap-around turned on")
        os.write(child.master, b"\x1b[C")
        screen_until(child, lambda text: "page=1/9 items=84 per=10 bounds=[0,10)" in text,
                     "wrap-around to the first page")

        # A narrower row truncates the indicator and leaves the page and the slice alone.
        child.screen.resize(24, 12)
        child.resize(24, 12)
        # Wait for the narrower paint itself: the resized emulated screen keeps the old row until
        # the application repaints at the new width.
        screen_until(child, lambda text: field_row(child, 14) == "\u25CF \u25CB \u25CB \u25CB \u25CB \u2026",
                     "the indicator window shrunk to the narrower row")
        require(all(len(row) == 12 for row in child.screen.display),
                "the scene did not repaint at the narrower width")
        require("page=1/9" in field_row(child, 0), "the narrower paint lost the page status row")
        narrow = field_row(child, 14)
        require("\u2026" in narrow, f"the narrow indicator kept its full window: {narrow!r}")
        require(len(narrow) <= 12, f"the narrow indicator overflowed its row: {narrow!r}")
        require("item-01" in field_row(child, 3),
                f"the narrower width changed the slice: {field_row(child, 3)!r}")
        child.screen.resize(24, 80)
        child.resize(24, 80)
        screen_until(child, lambda text: "\u25CF \u25CB \u25CB \u25CB \u25CB \u25CB \u25CB \u2026" in text,
                     "the restored dot window")

        # The numeric indicator replaces the dots, and the count and the page are still the same.
        os.write(child.master, b"g")
        screen_until(child, lambda text: "mode=numeric" in text, "the numeric indicator enabled")
        screen_until(child, lambda text: field_row(child, 14) == "1/9", "the numeric indicator row")

        # Shrinking the list from the last page clamps the page it was showing.
        os.write(child.master, b"\x1b[F")
        screen_until(child, lambda text: "page=9/9 items=84 per=10 bounds=[80,84)" in text, "the last page again")
        require(field_row(child, 14) == "9/9", f"the numeric indicator read {field_row(child, 14)!r}")
        os.write(child.master, b"r")
        screen_until(child, lambda text: "items=74" in text, "one page of items removed")
        require("page=8/8 items=74 per=10 bounds=[70,74)" in "\n".join(child.screen.display),
                "the page was not clamped to the shorter list")
        require(field_row(child, 14) == "8/8", f"the numeric indicator read {field_row(child, 14)!r}")

        # Down to zero items: no phantom page, no slice, nothing selected.
        os.write(child.master, b"r" * 8)
        screen_until(child, lambda text: "items=0" in text, "every item removed")
        require("page=0/0 items=0 per=10 bounds=[0,0) selected=none" in "\n".join(child.screen.display),
                "empty content did not report zero pages and an empty range")
        require(field_row(child, 14) == "0/0", f"the empty indicator read {field_row(child, 14)!r}")
        require("item-" not in "\n".join(child.screen.display), "an empty page painted an item")

        # And back up: the count returns with a valid first page and selection.
        os.write(child.master, b"a")
        screen_until(child, lambda text: "page=1/1 items=10 per=10 bounds=[0,10) selected=0" in text,
                     "items restored to one page")
        require("item-01" in field_row(child, 3), f"the restored page painted no item: {field_row(child, 3)!r}")

        os.write(child.master, b"\x1b")
        child.finish()
    print("PASS parity 06 pages, bounds, dots/numeric indicators and a shrinking list")


def check_spinner_parity(java_command):
    """Phase 07: configurable spinners, runtime timing and two independently mounted spinners."""
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "focus=left cadence=100ms mounted=on last=none" in text,
                     "the scene's policy row")
        screen_until(child, lambda text: "left=line frame=" in text and "run=on" in text,
                     "the first spinner running")
        screen_until(child, lambda text: "right=globe frame=" in text and "run=on" in text,
                     "the second spinner running")
        screen_until(child,
                     lambda text: "\u2190 previous \u2022 \u2192 next \u2022 space run \u2022 c cadence" in text,
                     "the scene's compact help")

        # Runtime time drives both spinners: each frame index advances on its own.
        first = status_frame(child, 0)
        screen_until(child, lambda text: status_frame(child, 0) != first, "the first spinner advanced")
        second = status_frame(child, 1)
        screen_until(child, lambda text: status_frame(child, 1) != second, "the second spinner advanced")

        # Space stops only the focused spinner: its frame and clock freeze while the other turns on.
        os.write(child.master, b" ")
        screen_until(child, lambda text: "run=off" in field_row(child, 0) and "last=stopped" in text,
                     "the first spinner stopped")
        frozen = field_row(child, 0)
        moving = field_row(child, 1)
        pump_for(child, 0.5)
        require(field_row(child, 0) == frozen,
                f"a stopped spinner kept ticking: {field_row(child, 0)!r} replaced {frozen!r}")
        require(field_row(child, 1) != moving,
                f"the second spinner stopped with the first: {field_row(child, 1)!r}")

        # Resuming starts a fresh cycle, and the spinner advances again.
        os.write(child.master, b" ")
        screen_until(child, lambda text: "run=on" in field_row(child, 0) and "last=started" in text,
                     "the first spinner restarted")
        restarted = status_frame(child, 0)
        require(status_frames(child, 0) == 4,
                f"the restart changed the definition: {field_row(child, 0)!r}")
        screen_until(child, lambda text: status_frame(child, 0) != restarted, "the restarted spinner advanced")

        # A preset change replaces the frames and restarts that spinner's cycle only.
        os.write(child.master, b"\x1b[C")
        screen_until(child, lambda text: "left=dot" in text and "last=preset-dot" in text,
                     "the first spinner's preset cycled")
        require(status_frames(child, 0) == 8, f"the dot preset has eight frames: {field_row(child, 0)!r}")
        require("right=globe" in field_row(child, 1),
                f"the second spinner lost its preset: {field_row(child, 1)!r}")

        # The cadence is scene data, so the definition - not the widget - owns the timing.
        os.write(child.master, b"c")
        screen_until(child, lambda text: "cadence=50ms" in text and "last=cadence-50ms" in text,
                     "the cadence cycled")

        # The gestures follow the focus, so the second spinner now stops on its own.
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=right" in text and "last=focus-right" in text,
                     "the second spinner focused")
        os.write(child.master, b" ")
        screen_until(child, lambda text: "run=off" in field_row(child, 1) and "run=on" in field_row(child, 0),
                     "the second spinner stopped while the first kept running")
        running = field_row(child, 0)
        screen_until(child, lambda text: field_row(child, 0) != running,
                     "the first spinner kept advancing after the second stopped")

        # Removing the mount closes its ticks; the remaining spinner is unaffected.
        os.write(child.master, b"x")
        screen_until(child, lambda text: "right=absent" in text and "mounted=off" in text,
                     "the second spinner removed")
        require(field_row(child, 4).rstrip().endswith("right"),
                f"the removed spinner still painted a frame: {field_row(child, 4)!r}")
        running = field_row(child, 0)
        screen_until(child, lambda text: field_row(child, 0) != running,
                     "the remaining spinner kept advancing after the removal")

        # Restoring mounts it again, stopped and at the first frame of the preset it had.
        os.write(child.master, b"x")
        screen_until(child,
                     lambda text: "right=globe frame=1/3 run=off" in text and "mounted=on" in text,
                     "the second spinner restored, stopped and at its first frame")

        os.write(child.master, b"\x1b")
        child.finish()
    print("PASS parity 07 configurable spinners, runtime timing and independent mounts")


def check_progress_parity(java_command):
    """Phase 08: an eased bar beside a static one, driven by a mock package manager."""
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "animated target=0.000 displayed=0.000 run=off" in text,
                     "the bar at rest on zero")
        screen_until(child,
                     lambda text: "width=32 pkgs=0/6 motion=smooth fill=plain last=none" in text,
                     "the scene's policy row")
        screen_until(child, lambda text: "s install \u2022 u uninstall \u2022 r reset" in text,
                     "the scene's compact help")

        # The start snapshot: both bars are empty, and a bar at rest holds no timer.
        require(bar_row(child, 3) == "install " + EMPTY_CELL * 32 + " 0%",
                f"the animated bar did not start empty: {bar_row(child, 3)!r}")
        require(bar_row(child, 4) == "detail  " + EMPTY_CELL * 32 + " 0%",
                f"the static bar did not start empty: {bar_row(child, 4)!r}")

        # One install retargets the bar. The frames between the retarget and the rest walk the fill
        # up in order, the static bar shows its target from the first of them, and the animated bar
        # then lands on the target exactly and releases its interval.
        os.write(child.master, b"s")
        moving = []
        first_static = None
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            child.pump(0.02)
            row = field_row(child, 0)
            if "run=on" in row:
                moving.append(displayed_value(child))
                if first_static is None:
                    first_static = bar_row(child, 4)
            elif moving:
                break
        require(len(set(moving)) >= 3, f"the fill did not ease through several frames: {moving!r}")
        require(moving == sorted(moving), f"the fill moved backwards: {moving!r}")
        require(any(0 < value < SIXTH for value in moving),
                f"no frame painted a partial target: {moving!r}")
        require(abs(displayed_value(child) - SIXTH) < 5e-4,
                f"the bar did not land on its target: {field_row(child, 0)!r}")
        require(field_row(child, 0).endswith("run=off"), f"the bar kept moving: {field_row(child, 0)!r}")
        require(bar_row(child, 3) == "install " + bar_row(child, 4)[len("detail  "):],
                f"the rested row is not the static row: {bar_row(child, 3)!r}")
        require(first_static == bar_row(child, 4),
                f"the static bar lagged behind its value: {first_static!r} then {bar_row(child, 4)!r}")

        # At rest there is nothing to tick, so the scene stops repainting on its own.
        frozen = field_row(child, 0)
        pump_for(child, 0.4)
        require(field_row(child, 0) == frozen,
                f"a resting bar kept moving: {field_row(child, 0)!r} replaced {frozen!r}")

        # A partial fill grades the cell the fill stops in: a sixth of thirty-two cells is five
        # whole cells and three eighths of the next, and the row keeps exactly its width.
        os.write(child.master, b"b")
        screen_until(child, lambda text: "fill=partial " in text and "last=fill-partial" in text,
                     "the partial-cell fill")
        graded = "detail  " + FILLED_CELL * 5 + PARTIAL_CELL + EMPTY_CELL * 26 + " 17%"
        require(bar_row(child, 4) == graded, f"the graded row is {bar_row(child, 4)!r}")
        require(bar_row(child, 3) == graded.replace("detail  ", "install ", 1),
                f"the animated row is not the same graded row: {bar_row(child, 3)!r}")

        # The blend spans the filled width: the first cell is the start color, the last filled cell
        # is further along the ramp, and the cells still to come keep the empty color.
        os.write(child.master, b"b")
        screen_until(child, lambda text: "fill=blend " in text, "the blended fill")
        os.write(child.master, b"b")
        screen_until(child, lambda text: "fill=partial+blend" in text, "the graded blended fill")
        require(bar_row(child, 4) == graded, f"the blend changed the cells: {bar_row(child, 4)!r}")
        first = cell_color(child, 4, BAR_COLUMN)
        last = cell_color(child, 4, BAR_COLUMN + 4)
        empty = cell_color(child, 4, BAR_COLUMN + 5)
        require(first == "2b8cff", f"the blend did not start at its first color: {first!r}")
        require(last != first, f"the blend did not advance across the fill: {last!r}")
        require(empty not in (first, last), f"an empty cell was painted with a fill color: {empty!r}")

        # Install the rest of the seeded run: the bar finishes at one hundred percent and the
        # spinner stops, because the work it was turning for is done.
        for expected in range(2, PACKAGE_COUNT + 1):
            os.write(child.master, b"s")
            screen_until(child, lambda text, expected=expected: f"pkgs={expected}/6" in text,
                         f"package {expected} installed")
        screen_until(child, lambda text: "animated target=1.000 displayed=1.000 run=off" in text,
                     "the bar settled on completion")
        require("last=done" in field_row(child, 1), f"the run did not finish: {field_row(child, 1)!r}")
        require(bar_row(child, 3) == "install " + FILLED_CELL * 32 + " 100%",
                f"the animated bar did not finish full: {bar_row(child, 3)!r}")
        require(bar_row(child, 4) == "detail  " + FILLED_CELL * 32 + " 100%",
                f"the static bar did not finish full: {bar_row(child, 4)!r}")
        stopped = field_row(child, 6)
        require("complete 6/6" in stopped, f"the completion line is {stopped!r}")
        pump_for(child, 0.4)
        require(field_row(child, 6) == stopped,
                f"the spinner kept turning after the run: {field_row(child, 6)!r} replaced {stopped!r}")

        # Taking a package back eases the bar down from where it was rather than resetting it.
        os.write(child.master, b"u")
        descending = []
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            child.pump(0.02)
            row = field_row(child, 0)
            if "run=on" in row:
                descending.append(displayed_value(child))
            elif descending:
                break
        require(len(descending) >= 3, f"the fill did not ease down: {descending!r}")
        require(descending == sorted(descending, reverse=True),
                f"the descending fill was not monotone: {descending!r}")
        require(descending[0] > FIVE_SIXTHS + 1e-3,
                f"the bar restarted at its new target instead of easing from where it was: {descending[0]!r}")
        require(abs(displayed_value(child) - FIVE_SIXTHS) < 5e-4,
                f"the bar did not land on the lower target: {field_row(child, 0)!r}")

        # Reduced motion reaches the target at once and holds no timer at all.
        os.write(child.master, b"m")
        screen_until(child, lambda text: "motion=instant" in text and "last=motion-instant" in text,
                     "the instant motion")
        require("run=on" not in field_row(child, 0), f"an instant bar kept moving: {field_row(child, 0)!r}")
        os.write(child.master, b"s")
        screen_until(child, lambda text: "pkgs=6/6" in text, "the instant install")
        require(field_row(child, 0) == "animated target=1.000 displayed=1.000 run=off",
                f"an instant change did not land at once: {field_row(child, 0)!r}")
        require(bar_row(child, 3) == "install " + FILLED_CELL * 32 + " 100%",
                f"an instant change did not paint the target: {bar_row(child, 3)!r}")

        # A narrower terminal lays the same normalized value out in fewer cells.
        child.screen.resize(24, 40)
        child.resize(24, 40)
        screen_until(child, lambda text: "width=24" in text, "the narrower layout")
        require(bar_row(child, 3) == "install " + FILLED_CELL * 24 + " 100%",
                f"the narrow animated bar is {bar_row(child, 3)!r}")
        require(bar_row(child, 4) == "detail  " + FILLED_CELL * 24 + " 100%",
                f"the narrow static bar is {bar_row(child, 4)!r}")
        require(abs(displayed_value(child) - 1.0) < 5e-4,
                f"the resize changed the value: {field_row(child, 0)!r}")
        require(all(len(row) == 40 for row in child.screen.display), "the narrow screen kept its old width")
        child.screen.resize(24, 80)
        child.resize(24, 80)
        screen_until(child, lambda text: "width=32" in text, "the wider layout again")
        require(bar_row(child, 4) == "detail  " + FILLED_CELL * 32 + " 100%",
                f"the restored static bar is {bar_row(child, 4)!r}")

        os.write(child.master, b"\x1b")
        child.finish()
    print("PASS parity 08 eased and static progress, blended fills and a mock package manager")


def bar_row(child, index):
    """One painted bar row of the phase-08 scene, without the blank rest of the terminal row."""
    return child.screen.display[index].rstrip()


def displayed_value(child):
    """The fraction the animated bar reports on its status row."""
    match = re.search(r"displayed=([0-9.]+)", field_row(child, 0))
    require(match is not None, f"no displayed value on the status row: {field_row(child, 0)!r}")
    return float(match.group(1))


def cell_color(child, row, column):
    """The foreground color one painted cell carries, as pyte reports it."""
    return child.screen.buffer[row][column].fg


def pump_for(child, seconds):
    """Reads the child for a fixed wall-clock interval, so a frozen row can be compared after it."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        child.pump(min(0.05, max(0, deadline - time.monotonic())))


def status_frame(child, index):
    """The frame a status row is showing, read from its ``frame=N/M`` field."""
    match = re.search(r"frame=(\d+)/\d+", field_row(child, index))
    require(match is not None, f"no frame field on row {index}: {field_row(child, index)!r}")
    return int(match.group(1))


def status_frames(child, index):
    """How many frames the definition of one spinner has, read from its ``frame=N/M`` field."""
    match = re.search(r"frame=\d+/(\d+)", field_row(child, index))
    require(match is not None, f"no frame count on row {index}: {field_row(child, index)!r}")
    return int(match.group(1))


def field_row(child, index):
    """One scene row, without the blanks the rest of the terminal row is padded with."""
    return child.screen.display[index].rstrip()


def tick_count(child):
    """How many external sends phase 22's scene has counted, from its status row."""
    match = re.search(r"ticks=(\d+)", "\n".join(child.screen.display))
    require(match is not None, "no tick count on the status row")
    return int(match.group(1))


def pane_columns(child):
    """The two panes' interior column spans, half-open, taken from the frame the scene painted."""
    for row in child.screen.display:
        if "\u250c\u2500left" in row and "\u250c\u2500right" in row:
            left_end = row.index("\u2510")
            right_start = row.index("\u250c\u2500right")
            return (1, left_end), (right_start + 1, row.rindex("\u2510"))
    raise AssertionError("the scene painted no split frame")


def pane_index(child):
    """The screen row the panes' content starts at, and the row each pane's draft is on."""
    for index, row in enumerate(child.screen.display):
        if "\u250c\u2500left" in row:
            return index + 1
    raise AssertionError("the scene painted no frame")


def pane_row(child, name, offset=0):
    """One pane's first painted draft row, without its frame."""
    row = child.screen.display[pane_index(child) + offset]
    left, right = pane_columns(child)
    start, end = left if name == "left" else right
    return row[start:end]


def pane_rows(child, name):
    """The rows a pane's editor reports it paints, read from its own status row."""
    pattern = re.compile(name + r" len=\d+ caret=\d+ rows=(\d+)")
    for row in child.screen.display:
        found = pattern.search(row)
        if found:
            return int(found.group(1))
    raise AssertionError(f"no status row for the {name} pane")


def block_in(child, span):
    """Whether the painted block caret is inside one pane's content columns."""
    start, end = span
    top = pane_index(child)
    return any("\u2588" in row[start:end] for row in child.screen.display[top:top + 6])


def cursor_inside(child, first_row, last_row, first_col, last_col):
    """Whether the terminal cursor is inside the given rectangle of the screen."""
    row, col = child.screen.cursor.y, child.screen.cursor.x
    return first_row <= row <= last_row and first_col <= col < last_col


def check_countdown_parity(java_command):
    """Phase 09: a countdown that runs, pauses, resumes, resets and expires exactly once."""
    with PtyChild(java_command, control=False) as child:
        # The countdown mounts idle at the probe's short duration: nothing is counting yet.
        screen_until(child,
                     lambda text: "countdown state=idle epoch=0" in text
                     and "nanos=5000000000 timeouts=0" in text,
                     "the idle countdown")
        screen_until(child, lambda text: "duration=00:05" in text and "last=none" in text,
                     "the configured duration")
        require(field_row(child, 3) == "remaining  00:05",
                f"the idle countdown painted {field_row(child, 3)!r}")
        screen_until(child,
                     lambda text: "s start over \u2022 space start/pause \u2022 r reset" in text,
                     "the scene's compact help")

        # A longer duration is a new definition, and the countdown is reset to it.
        os.write(child.master, b"\x1b[C")
        screen_until(child, lambda text: "duration=00:20" in text and "last=duration-20" in text,
                     "the longer duration")
        require(field_row(child, 3) == "remaining  00:20",
                f"the countdown did not follow its new duration: {field_row(child, 3)!r}")

        # The scene's bounds are a contract: a gesture past one says so and changes nothing.
        os.write(child.master, b"\x1b[D")
        screen_until(child, lambda text: "duration=00:05" in text and "last=duration-5" in text,
                     "the shorter duration")
        os.write(child.master, b"\x1b[D")
        screen_until(child, lambda text: "last=limit" in text, "the shorter-duration limit")
        require("duration=00:05" in field_row(child, 1), f"the limit changed the duration: {field_row(child, 1)!r}")

        # Space starts the run, and the painted time counts down on runtime time. The duration
        # gesture above reset the countdown, so the gesture that starts it is a resume from the whole
        # configured duration rather than a first start.
        os.write(child.master, b" ")
        screen_until(child, lambda text: "state=running" in text and "last=resume" in text,
                     "the running countdown")
        screen_until(child, lambda text: field_row(child, 3) == "remaining  00:04",
                     "the countdown reaching the next second")

        # Space pauses it: neither the painted time nor the model's own remainder moves on.
        os.write(child.master, b" ")
        screen_until(child, lambda text: "state=paused" in text and "last=pause" in text, "the paused countdown")
        paused = (field_row(child, 0), field_row(child, 3))
        pump_for(child, 0.6)
        require((field_row(child, 0), field_row(child, 3)) == paused,
                f"a paused countdown kept counting: {(field_row(child, 0), field_row(child, 3))!r} "
                f"replaced {paused!r}")
        resumed_nanos = remaining_nanos(child)
        require(0 < resumed_nanos < 5_000_000_000,
                f"the paused countdown kept the whole duration: {field_row(child, 0)!r}")

        # Space resumes from the remainder it was holding rather than starting over.
        os.write(child.master, b" ")
        screen_until(child, lambda text: "state=running" in text and "last=resume" in text,
                     "the resumed countdown")
        require(remaining_nanos(child) <= resumed_nanos,
                f"resuming restarted the run: {field_row(child, 0)!r}")
        screen_until(child, lambda text: remaining_nanos(child) < resumed_nanos,
                     "the resumed countdown counting on")

        # A reset puts the configured duration back and stops the run.
        os.write(child.master, b"r")
        screen_until(child, lambda text: "state=paused" in text and "last=reset" in text, "the reset countdown")
        require(field_row(child, 3) == "remaining  00:05", f"the reset painted {field_row(child, 3)!r}")

        # Start runs the whole duration again; reaching zero reports one timeout, once.
        os.write(child.master, b"s")
        screen_until(child, lambda text: "state=running" in text and "last=start" in text,
                     "the restarted countdown")
        screen_until(child,
                     lambda text: "state=expired" in text and "last=timeout" in text
                     and "timeouts=1" in text,
                     "the countdown expiring", timeout=20)
        require("time is up" in field_row(child, 3),
                f"the expiry painted nothing: {field_row(child, 3)!r}")
        expired = field_row(child, 0)
        pump_for(child, 1.0)
        require(field_row(child, 0) == expired,
                f"the expiry was reported again: {field_row(child, 0)!r} replaced {expired!r}")
        require("panic" not in "\n".join(child.screen.display).lower(),
                "the expiry reported a failure")

        # A finished countdown starts a new run from the same key, and keeps its one timeout.
        os.write(child.master, b" ")
        screen_until(child, lambda text: "state=running" in text and "timeouts=1" in text
                     and "last=start" in text,
                     "a new run after the expiry")

        os.write(child.master, b"\x1b")
        child.finish()
    print("PASS parity 09 countdown lifecycle, pause semantics and a single timeout")


def remaining_nanos(child):
    """The remainder the phase-09 status row reports, in nanoseconds."""
    match = re.search(r"nanos=(\d+)", field_row(child, 0))
    require(match is not None, f"no remainder on the status row: {field_row(child, 0)!r}")
    return int(match.group(1))


def elapsed_nanos(child, index):
    """The elapsed time one phase-10 status row reports, in nanoseconds."""
    match = re.search(r"nanos=(\d+)", field_row(child, index))
    require(match is not None, f"no elapsed time on status row {index}: {field_row(child, index)!r}")
    return int(match.group(1))


def check_stopwatch_parity(java_command):
    """Phase 10: two independent stopwatches, state-driven help and a running total."""
    with PtyChild(java_command, control=False) as child:
        # Both watches mount stopped at zero: nothing is counting yet.
        screen_until(child,
                     lambda text: "left state=stopped epoch=0 nanos=0" in text
                     and "right state=stopped epoch=0 nanos=0" in text,
                     "the two stopped watches")
        screen_until(child, lambda text: "tick=50ms last=none" in text, "the scene's policy row")
        require(field_row(child, 4) == "left   00:00:00.000",
                f"the left watch painted {field_row(child, 4)!r}")
        require(field_row(child, 5) == "right  00:00:00.000",
                f"the right watch painted {field_row(child, 5)!r}")
        screen_until(child,
                     lambda text: "s start left \u2022 r reset left \u2022 k start right \u2022 l reset right"
                     in text,
                     "the scene's compact help")

        # One key starts the left watch. Both the word help shows for it and its painted total follow
        # the watch's own state, and the right watch is untouched.
        os.write(child.master, b"s")
        screen_until(child, lambda text: "left state=running epoch=1" in text and "last=start-left" in text,
                     "the running left watch")
        screen_until(child, lambda text: "s stop left" in text and "k start right" in text,
                     "help following the left watch's running state")
        screen_until(child, lambda text: elapsed_nanos(child, 0) >= 300_000_000,
                     "300 ms measured by the left watch")
        require(field_row(child, 4) != "left   00:00:00.000",
                f"the running wait painted nothing: {field_row(child, 4)!r}")
        require(field_row(child, 1) == "right state=stopped epoch=0 nanos=0",
                f"the right watch moved with the left one: {field_row(child, 1)!r}")
        require(field_row(child, 5) == "right  00:00:00.000",
                f"the right watch painted {field_row(child, 5)!r}")

        # The same key stops it. The total it measured is what the display keeps, and it keeps it
        # without a timer: another half second changes neither row.
        os.write(child.master, b"s")
        screen_until(child, lambda text: "left state=stopped epoch=2" in text and "last=stop-left" in text,
                     "the stopped left watch")
        screen_until(child, lambda text: "s start left" in text, "help following the stopped left watch")
        stopped_nanos = elapsed_nanos(child, 0)
        require(stopped_nanos >= 300_000_000,
                f"the stop lost the measured time: {field_row(child, 0)!r}")
        frozen = (field_row(child, 0), field_row(child, 4))
        pump_for(child, 0.6)
        require((field_row(child, 0), field_row(child, 4)) == frozen,
                f"a stopped watch kept counting: {(field_row(child, 0), field_row(child, 4))!r} "
                f"replaced {frozen!r}")

        # The right watch has its own keys: starting it leaves the left watch's frozen total alone.
        os.write(child.master, b"k")
        screen_until(child, lambda text: "right state=running epoch=1" in text and "last=start-right" in text,
                     "the running right watch")
        require(field_row(child, 0) == frozen[0],
                f"starting the right watch moved the left one: {field_row(child, 0)!r}")
        require(field_row(child, 4) == frozen[1],
                f"the left watch painted {field_row(child, 4)!r}")
        screen_until(child, lambda text: elapsed_nanos(child, 1) >= 200_000_000,
                     "200 ms measured by the right watch")
        # The two watches have independent state, so help offers each one its own word: the right
        # watch is running, the left one is still stopped.
        require("k stop right" in field_row(child, 7),
                f"help did not follow the right watch's state: {field_row(child, 7)!r}")
        require("s start left" in field_row(child, 7),
                f"the left watch's word went with the right one's: {field_row(child, 7)!r}")

        # Stopping and resetting the right watch zeroes its own total only.
        os.write(child.master, b"k")
        screen_until(child, lambda text: "right state=stopped epoch=2" in text and "last=stop-right" in text,
                     "the stopped right watch")
        os.write(child.master, b"l")
        screen_until(child, lambda text: "right state=stopped epoch=3 nanos=0" in text
                     and "last=reset-right" in text,
                     "the reset right watch")
        require(field_row(child, 5) == "right  00:00:00.000",
                f"the reset right watch painted {field_row(child, 5)!r}")
        require(field_row(child, 0) == frozen[0],
                f"resetting the right watch moved the left one: {field_row(child, 0)!r}")

        # A reset keeps whether the watch is running: the left watch starts again, measures more than
        # it had, and is reset back to zero while its run carries on.
        os.write(child.master, b"s")
        screen_until(child, lambda text: "left state=running epoch=3" in text and "last=start-left" in text,
                     "the restarted left watch")
        screen_until(child, lambda text: elapsed_nanos(child, 0) > stopped_nanos,
                     "the left watch measuring past what it had")
        os.write(child.master, b"r")
        screen_until(child, lambda text: "left state=running epoch=4" in text and "last=reset-left" in text,
                     "the reset running left watch")
        require(elapsed_nanos(child, 0) < stopped_nanos,
                f"the reset kept the old total: {field_row(child, 0)!r}")
        require("s stop left" in field_row(child, 7),
                f"the reset watch stopped offering its stop key: {field_row(child, 7)!r}")
        reset_nanos = elapsed_nanos(child, 0)
        screen_until(child, lambda text: elapsed_nanos(child, 0) > reset_nanos,
                     "the reset watch counting on from zero")

        os.write(child.master, b"\x1b")
        child.finish()
    print("PASS parity 10 stopwatch lifecycle, independent watches and state-driven help")


def check_rich_list_parity(java_command):
    """Phase 11: a large list filtered, paged, added to, resized and reported on."""
    # Spinner.Preset.DOT: one braille dot traveling around one cell.
    frames = set("\u2801\u2802\u2804\u2840\u2880\u2820\u2810\u2808")
    with PtyChild(java_command, control=False) as child:
        # The scene's three status rows, then the list's own chrome.
        screen_until(child,
                     lambda text: "preset=default items=240 selected=item-001 added=0 last=none" in text,
                     "the fixture row")
        screen_until(child,
                     lambda text: "filter=- mode=fuzzy editing=off applied=- matches=240 page=1/30" in text,
                     "the query row")
        screen_until(child,
                     lambda text: "title=on status=on page=on help=compact spinner=off rows=21 content=16" in text,
                     "the chrome row")
        require(field_row(child, 3) == "Favorite things",
                f"the list title row painted {field_row(child, 3)!r}")
        require(field_row(child, 4) == "240 items",
                f"the list status row painted {field_row(child, 4)!r}")
        require("\u276F Raspberry jam" in field_row(child, 5),
                f"the first item was not painted selected: {field_row(child, 5)!r}")
        require("On toast, mostly" in field_row(child, 6),
                f"the first item's description was not painted: {field_row(child, 6)!r}")
        require("\u25CF \u25CB \u25CB \u25CB \u25CB \u25CB \u25CB \u2026" in field_row(child, 21),
                f"the dot indicator painted {field_row(child, 21)!r}")
        screen_until(child, lambda text: "enter choose \u2022 esc cancel" in text, "the list's own help")
        screen_until(child, lambda text: "a add item \u2022 x delete item" in text, "the application's help block")

        # A query is typed into the field and narrows the list as it goes; the counts, the page
        # count and the selection follow it.
        os.write(child.master, b"/")
        screen_until(child, lambda text: "editing=on" in text, "the filter being edited")
        require("Filter:" in field_row(child, 3), f"the filter bar painted {field_row(child, 3)!r}")
        screen_until(child, lambda text: "enter apply filter \u2022 esc cancel edit" in text,
                     "help following the edit")
        os.write(child.master, b"jam")
        screen_until(child, lambda text: "filter=jam" in text and "editing=on" in text, "the typed query")
        screen_until(child, lambda text: "12 of 240 items" in text, "the narrowed counts")
        require("\u276F Raspberry jam" in field_row(child, 5),
                f"the matched item was not painted: {field_row(child, 5)!r}")

        # Canceling restores the query and the item it hid.
        os.write(child.master, b"\x1b")
        screen_until(child, lambda text: "filter=-" in text and "editing=off" in text, "the canceled filter")
        screen_until(child, lambda text: "240 items" in text, "the restored counts")
        require("\u276F Raspberry jam" in field_row(child, 5),
                f"the restored selection was not painted: {field_row(child, 5)!r}")

        # Accepting keeps the query; the same gesture with nothing to edit clears it.
        os.write(child.master, b"/")
        screen_until(child, lambda text: "editing=on" in text, "the second edit")
        os.write(child.master, b"cats")
        screen_until(child, lambda text: "filter=cats" in text, "the second query")
        # "cats" matches two of the twenty repeated titles, twelve times each.
        screen_until(child, lambda text: "matches=24" in text and "24 of 240 items" in text,
                     "the second query narrowing the counts")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "editing=off" in text and "applied=cats" in text, "the applied query")
        os.write(child.master, b"\x1b")
        screen_until(child, lambda text: "filter=-" in text and "applied=-" in text, "the cleared query")

        # Paging moves the slice, and the selection is the first item of the page arrived at.
        os.write(child.master, b"\x1b[C")
        screen_until(child, lambda text: "selected=item-009" in text and "page=2/30" in text, "page two")
        require("\u276F Pottery" in field_row(child, 5),
                f"page two painted {field_row(child, 5)!r}")
        os.write(child.master, b"\x1b[D")
        screen_until(child, lambda text: "selected=item-001" in text and "page=1/30" in text, "page one again")

        # An item added above the selection leaves it selected, and removing it picks the nearest.
        os.write(child.master, b"a")
        screen_until(child, lambda text: "items=241" in text and "selected=item-001" in text, "the added item")
        require("added item 1" in field_row(child, 5),
                f"the added item was not painted first: {field_row(child, 5)!r}")
        os.write(child.master, b"x")
        screen_until(child, lambda text: "items=240" in text and "selected=item-002" in text,
                     "the removed item and its nearest survivor")

        # Chrome toggles give their rows back to the items.
        os.write(child.master, b"t")
        screen_until(child, lambda text: "title=off" in text, "the title switched off")
        require("Favorite things" not in "\n".join(child.screen.display),
                "the title stayed painted after it was switched off")
        os.write(child.master, b"t")
        screen_until(child, lambda text: field_row(child, 3) == "Favorite things", "the title switched on")
        os.write(child.master, b"h")
        # Help is two rows here - the list's own line and the application's - so switching it off
        # gives both back to the items.
        screen_until(child, lambda text: "help=off" in text and "content=18" in text,
                     "the help rows given back to the items")
        os.write(child.master, b"h")
        screen_until(child, lambda text: "enter choose" in text and "content=16" in text, "the help painted again")

        # The spinner runs while loading, and stops with its interval.
        os.write(child.master, b"s")
        screen_until(child, lambda text: "spinner=on" in text, "the spinner switched on")
        screen_until(child, lambda text: field_row(child, 3) and field_row(child, 3)[0] in frames,
                     "a spinner frame beside the title")
        os.write(child.master, b"s")
        screen_until(child, lambda text: "spinner=off" in text, "the spinner switched off")
        screen_until(child, lambda text: field_row(child, 3) == "Favorite things",
                     "the title without a frame")

        # Full help lists every group, one line a group.
        os.write(child.master, b"?")
        screen_until(child, lambda text: "Move" in text and "Chrome" in text and "General" in text,
                     "the full help")
        os.write(child.master, b"?")
        screen_until(child, lambda text: "enter choose" in text, "the compact help again")

        # A shorter terminal repacks the pages and keeps the selection.
        child.screen.resize(16, 80)
        child.resize(16, 80)
        screen_until(child, lambda text: "rows=13" in text and "content=8" in text, "the shorter list")
        screen_until(child, lambda text: "page=1/60" in text, "the repacked page count")
        require("selected=item-002" in field_row(child, 0),
                f"the resize lost the selection: {field_row(child, 0)!r}")
        # The added item is still first, so the selected item is the second of the page.
        require("\u276F Hazelnut spread" in field_row(child, 7),
                f"the repacked list painted {field_row(child, 5)!r} / {field_row(child, 7)!r}")
        child.screen.resize(24, 80)
        child.resize(24, 80)
        screen_until(child, lambda text: "rows=21" in text and "content=16" in text and "page=1/30" in text,
                     "the restored height")
        require("selected=item-002" in field_row(child, 0),
                f"growing the terminal lost the selection: {field_row(child, 0)!r}")

        os.write(child.master, b"\x03")
        child.finish()
    print("PASS parity 11 filtering, paging, item identity, chrome, spinner and resize")


def check_viewport_parity(java_command):
    """Phase 12: a pager that wraps, pans, searches, follows and scrolls beside an outline pane."""
    with PtyChild(java_command, control=False) as child:
        # The two status rows, the find field, the pager and the scene's own line gutter.
        screen_until(child,
                     lambda text: "mode=wrap numbers=on follow=off line=1/39 total=42 vscroll=0% hscroll=0%" in text,
                     "the pager's status row")
        screen_until(child,
                     lambda text: "matches=0 active=0 query=- focus=main added=0 last=started" in text,
                     "the scene's status row")
        require(field_row(child, 2) == "find:", f"the find field painted {field_row(child, 2)!r}")
        require(field_row(child, 3) == "  1 \u2502 A pocket guide to the artichoke",
                f"the pager's first row painted {field_row(child, 3)!r}")
        require(field_row(child, 17) == "outline=pan line=1/11 rows=4 vscroll=0%",
                f"the outline's status row painted {field_row(child, 17)!r}")
        require(field_row(child, 18) == "  1 \u2502   1  A pocket guide to the artichoke",
                f"the outline's first row painted {field_row(child, 18)!r}")
        screen_until(child, lambda text: "\u2191/k up \u2022 \u2193/j down \u2022 b/pgup page up" in text,
                     "the pager's compact help")
        screen_until(child, lambda text: "w wrap/pan \u2022 g line numbers \u2022 a append a note" in text,
                     "the scene's compact help")
        # A wrapping pane has nothing to pan, so the horizontal gestures are not in its help.
        require("left" not in field_row(child, 22), f"a wrapping pane offered {field_row(child, 22)!r}")

        # The mode switch: the same document, now one row a line, and the pane offers panning.
        os.write(child.master, b"w")
        screen_until(child, lambda text: "mode=pan numbers=on follow=off line=1/39 total=39" in text,
                     "the panned mode")
        screen_until(child, lambda text: "\u2190/h left \u2022 \u2192/l right" in field_row(child, 22),
                     "the pager's horizontal help")
        os.write(child.master, b"\x1b[C\x1b[C\x1b[C")
        screen_until(child,
                     lambda text: field_row(child, 3) == "  1 \u2502 ocket guide to the artichoke",
                     "the three cells panned off the left edge")
        require("hscroll=1%" in field_row(child, 0), f"the pager reported {field_row(child, 0)!r}")

        # Switching back to wrapping drops the offset it cannot use.
        os.write(child.master, b"w")
        screen_until(child, lambda text: "mode=wrap numbers=on follow=off line=1/39 total=42" in text,
                     "the wrapped mode again")
        require("hscroll=0%" in field_row(child, 0), f"the wrapping pane kept {field_row(child, 0)!r}")
        require(field_row(child, 3) == "  1 \u2502 A pocket guide to the artichoke",
                f"the restored row painted {field_row(child, 3)!r}")

        # The gutter is chrome the scene can take away.
        os.write(child.master, b"g")
        screen_until(child, lambda text: "numbers=off" in text, "the gutter switched off")
        require(field_row(child, 3) == "A pocket guide to the artichoke",
                f"the unguttered row painted {field_row(child, 3)!r}")
        os.write(child.master, b"g")
        screen_until(child, lambda text: field_row(child, 3) == "  1 \u2502 A pocket guide to the artichoke",
                     "the gutter switched on")

        # A query is typed into a mounted field, and every match is painted while it narrows.
        os.write(child.master, b"/")
        screen_until(child, lambda text: "focus=field" in text, "the field focused")
        os.write(child.master, b"artichoke")
        screen_until(child,
                     lambda text: "matches=8 active=1 query=artichoke focus=field" in text,
                     "the search finding every keyword")
        require("find: artichoke" in field_row(child, 2), f"the field painted {field_row(child, 2)!r}")
        screen_until(child, lambda text: "n next match" in text, "the match gestures in the help")

        # The content pane walks the matches, forwards.
        os.write(child.master, b"\x1b")
        screen_until(child, lambda text: "focus=main" in text, "the pager focused again")
        os.write(child.master, b"n")
        screen_until(child, lambda text: "active=2" in text, "the second match")
        os.write(child.master, b"n")
        screen_until(child, lambda text: "active=3" in text, "the third match")

        # A query whose match is below the window scrolls the pager to it.
        os.write(child.master, b"/")
        screen_until(child, lambda text: "focus=field" in text, "the field focused again")
        os.write(child.master, b"\x7f" * len("artichoke"))
        screen_until(child, lambda text: "query=-" in text, "the cleared query")
        os.write(child.master, b"patience")
        screen_until(child, lambda text: "matches=2 active=1 query=patience" in text, "the second query")
        os.write(child.master, b"\x1b")
        screen_until(child, lambda text: "focus=main" in text, "the pager focused again")
        os.write(child.master, b"n")
        screen_until(child, lambda text: "active=2" in text, "the hidden match selected")
        screen_until(child, lambda text: "A note on patience" in text,
                     "the pager scrolled to the match below the window")
        require(int(re.search(r"line=(\d+)/", field_row(child, 0)).group(1)) > 20,
                f"the pager did not move to the match: {field_row(child, 0)!r}")

        # Follow-end: End arms it, an append stays on the end, and moving up releases it.
        os.write(child.master, b"\x1b[F")
        screen_until(child, lambda text: "follow=on" in text and "vscroll=100%" in text, "the end of the document")
        os.write(child.master, b"a")
        screen_until(child, lambda text: "added=1" in text and "follow=on" in text, "the appended note")
        screen_until(child, lambda text: "Note 1: appended by the demo" in text,
                     "the note painted at the end")
        os.write(child.master, b"\x1b[H")
        screen_until(child, lambda text: "follow=off" in text and "line=1/" in text, "the first line again")
        os.write(child.master, b"a")
        screen_until(child, lambda text: "added=2" in text, "the second note")
        require("line=1/" in field_row(child, 0),
                f"an append moved a reader who had scrolled back: {field_row(child, 0)!r}")

        # Two mounted panes keep their own anchors: the outline scrolls, the pager does not.
        require("line=1/41" in field_row(child, 0), f"the pager's anchor moved: {field_row(child, 0)!r}")
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=outline" in text, "the outline focused")
        os.write(child.master, b"\x1b[F")
        screen_until(child, lambda text: "outline=pan line=8/11 rows=4 vscroll=100%" in text,
                     "the outline's own end")
        require("line=1/41" in field_row(child, 0),
                f"the outline moved the pager: {field_row(child, 0)!r}")
        require("A note on patience" in field_row(child, 21), f"the outline painted {field_row(child, 21)!r}")

        # A shorter terminal gives rows back to each pane, and the scene keeps its layout.
        child.screen.resize(16, 80)
        child.resize(16, 80)
        screen_until(child, lambda text: "line=1/41" in text and "outline=pan" in field_row(child, 9),
                     "the shorter layout")
        require(field_row(child, 9).startswith("outline=pan line=8/11 rows=4"),
                f"the outline's header painted {field_row(child, 9)!r}")
        require("\u2191/k up" in field_row(child, 14), f"the pager's help painted {field_row(child, 14)!r}")
        require("w wrap/pan" in field_row(child, 15), f"the scene's help painted {field_row(child, 15)!r}")
        # The focused pane is still the outline, and it still scrolls on its own anchor.
        os.write(child.master, b"\x1b[H")
        screen_until(child, lambda text: "outline=pan line=1/11" in text, "the shorter outline scrolling")

        os.write(child.master, b"\x03")
        child.finish()
    print("PASS parity 12 wrapping, panning, gutters, matches, follow-end and two mounted panes")


def check_table_parity(java_command):
    """Phase 13: a scrollable table that selects, pans, edits, adds and removes rows by identity."""
    with PtyChild(java_command, control=False) as child:
        # The two status rows, the table's header and body, the static summary and two help lines.
        screen_until(child,
                     lambda text: "mode=row row=1/18 cell=- top=0 pan=0 last=started" in text,
                     "the table's status row")
        screen_until(child,
                     lambda text: "edits=0 added=0 removed=0 rows=18 body=15" in text and "chosen=-" in text,
                     "the scene's status row")
        require(field_row(child, 2) == "id   name        family      sown note",
                f"the header painted {field_row(child, 2)!r}")
        # The header and every body row place their columns on the same cells, whatever the cells
        # hold: this is the alignment the whole phase is about, read back off the terminal.
        header = field_row(child, 2)
        first = field_row(child, 3)
        require(first.index("artichoke") == header.index("name"), f"the name column of {first!r}")
        require(first.index("thistle") == header.index("family"), f"the family column of {first!r}")
        # The counts are right-aligned, so their last cell is the column's last cell.
        require(first[header.index("sown") + 3] == "2", f"the count column of {first!r}")
        require(first.index("Sow under") == header.index("note"), f"the note column of {first!r}")
        require(field_row(child, 17).startswith("S-15 runner bean legume"),
                f"the last visible row painted {field_row(child, 17)!r}")
        require(field_row(child, 18) == "\u250c" + "\u2500" * 9 + "\u2510",
                f"the fitted summary painted {field_row(child, 18)!r}")
        screen_until(child, lambda text: "\u2191/k up a row \u2022 \u2193/j down a row" in text,
                     "the table's compact help")
        screen_until(child, lambda text: "a add a row \u2022 e edit the name \u2022 x delete the row" in text,
                     "the scene's compact help")

        # Rows: the selection moves and the window follows it under a header that does not move.
        os.write(child.master, b"\x1b[B\x1b[B")
        screen_until(child, lambda text: "mode=row row=3/18 cell=- top=0 pan=0 last=down" in text,
                     "the third row selected")
        os.write(child.master, b"\x1b[F")
        screen_until(child, lambda text: "row=18/18 cell=- top=3" in text, "the last row")
        require(field_row(child, 2) == "id   name        family      sown note",
                f"the header moved: {field_row(child, 2)!r}")
        require(field_row(child, 17).startswith("S-18 tomato"),
                f"the last window painted {field_row(child, 17)!r}")
        os.write(child.master, b"\x1b[H")
        screen_until(child, lambda text: "row=1/18 cell=- top=0 pan=0" in text, "the first row again")

        # A cell selection moves along the row, and the window pans to keep the cell on screen.
        os.write(child.master, b"m")
        screen_until(child, lambda text: "mode=cell row=1/18 cell=id" in text, "the cell selection")
        os.write(child.master, b"\x1b[C" * 4)
        screen_until(child, lambda text: "cell=note" in text and "pan=15" in text,
                     "the note column selected and panned into view")
        require(field_row(child, 2).startswith("  family"),
                f"the header did not pan with the body: {field_row(child, 2)!r}")
        require(field_row(child, 3).startswith("  thistle"),
                f"the body did not pan with the header: {field_row(child, 3)!r}")
        os.write(child.master, b"\r")
        # The chosen value is longer than the row it is reported on, so a verifier reads its head.
        screen_until(child, lambda text: "chosen=S-01/note=Sow under" in text,
                     "the chosen cell reported by the scene")

        # The scene edits, adds and removes rows, and the selection keeps its identity.
        os.write(child.master, b"m")
        screen_until(child, lambda text: "mode=row row=1/18 cell=-" in text, "the row selection again")
        os.write(child.master, b"e")
        screen_until(child, lambda text: "edits=1" in text and "last=edited-S-01" in text,
                     "the edited row")
        os.write(child.master, b"a")
        screen_until(child, lambda text: "added=1" in text and "rows=19" in text, "the added row")
        os.write(child.master, b"x")
        screen_until(child, lambda text: "removed=1" in text and "last=removed-S-01" in text,
                     "the removed row")
        screen_until(child, lambda text: "rows=18" in text and "row=1/18" in text,
                     "the nearest survivor selected")

        # A shorter terminal gives the table fewer rows and keeps its columns where they were, while
        # the static table below it is fitted to the width it is given.
        os.write(child.master, b"h" * 15)
        screen_until(child, lambda text: "pan=0" in text, "the window panned back to the first cell")
        child.screen.resize(16, 80)
        child.resize(16, 80)
        screen_until(child, lambda text: "body=7" in text, "the shorter window")
        require(field_row(child, 2) == "id   name        family      sown note",
                f"the shorter header painted {field_row(child, 2)!r}")
        require(field_row(child, 3).startswith("S-91 new seed 1"),
                f"the shorter first row painted {field_row(child, 3)!r}")
        require(field_row(child, 10) == "\u250c" + "\u2500" * 9 + "\u2510",
                f"the fitted summary painted {field_row(child, 10)!r}")
        require("up a row" in field_row(child, 14), f"the table's help painted {field_row(child, 14)!r}")
        require("a add a row" in field_row(child, 15), f"the scene's help painted {field_row(child, 15)!r}")

        os.write(child.master, b"\x03")
        child.finish()
    print("PASS parity 13 row and cell selection, fixed header, panning, identity and a fitted table")


def check_file_picker_parity(java_command):
    """Phase 14: a bounded picker that lists, navigates, refuses and chooses by its own rules."""
    with PtyChild(java_command, control=False) as child:
        # The scene's two status rows, then the picker's path row and its entries.
        screen_until(child,
                     lambda text: "dir=/ entries=9 shown=7 selected=docs hidden=off state=idle" in text,
                     "the picker's status row")
        screen_until(child,
                     lambda text: "last=started chosen=0 rejected=0 deleted=0 added=0" in text,
                     "the scene's status row")
        # The path row is the resolved fixture directory: wide terminals hold all of it.
        original = field_row(child, 2)
        require("braxton-file-picker-" in original, f"the path row painted {original!r}")
        require(field_row(child, 3).startswith("\u276F docs/"),
                f"the first entry painted {field_row(child, 3)!r}")
        require(field_row(child, 3).endswith("dir"), f"the directory metadata painted {field_row(child, 3)!r}")
        require(field_row(child, 4).startswith("  loop"),
                f"the looping link painted {field_row(child, 4)!r}")
        require(field_row(child, 5).startswith("  music/"), f"music painted {field_row(child, 5)!r}")
        require(field_row(child, 6).startswith("  archive.bin"),
                f"the refused file painted {field_row(child, 6)!r}")
        # The fixture is the demo's root, so there is nowhere above it to go and parent is not
        # advertised: the compact help is the state's own gesture map.
        screen_until(child, lambda text: "\u2192/l open directory \u2022 enter choose" in text,
                     "the picker's compact help")
        require("parent directory" not in field_row(child, 22),
                f"the help advertised a blocked parent: {field_row(child, 22)!r}")
        screen_until(child, lambda text: "a add a file \u2022 x delete the file" in text,
                     "the scene's compact help")

        # Right enters the selected directory, and the picker reads it.
        os.write(child.master, b"l")
        screen_until(child, lambda text: "dir=/docs entries=2 shown=2 selected=guide.md" in text,
                     "the docs directory")
        require(field_row(child, 2).endswith("/docs"), f"the path row painted {field_row(child, 2)!r}")
        require(field_row(child, 3).startswith("\u276F guide.md"), f"guide.md painted {field_row(child, 3)!r}")
        require(field_row(child, 4).startswith("  notes.txt"), f"notes.txt painted {field_row(child, 4)!r}")

        # Enter chooses the allowed file, through the check the picker runs first.
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "last=chose-/docs/guide.md" in text, "the chosen file")
        screen_until(child, lambda text: "chosen=1" in text, "the chosen count")

        # Left goes back to the directory it came from, and selects it.
        os.write(child.master, b"h")
        screen_until(child, lambda text: "dir=/ entries=9 shown=7 selected=docs" in text,
                     "the parent directory")
        require(field_row(child, 2) == original, f"the path row came back as {field_row(child, 2)!r}")

        # A file the extension policy refuses is listed, and confirming it reports why.
        os.write(child.master, b"\x1b[B\x1b[B\x1b[B")
        screen_until(child, lambda text: "selected=archive.bin" in text, "the refused file selected")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "last=rejected-/archive.bin-extension" in text,
                     "the refused extension")
        screen_until(child, lambda text: "archive.bin: extension not allowed" in text,
                     "the picker's own notice")
        screen_until(child, lambda text: "chosen=1 rejected=1" in text, "the rejected count")

        # Hidden files are listed by the same read and shown by a projection.
        os.write(child.master, b".")
        screen_until(child, lambda text: "entries=9 shown=9 selected=archive.bin hidden=on" in text,
                     "the hidden files")
        require(field_row(child, 6).startswith("  .config.txt"), f"a hidden file painted {field_row(child, 6)!r}")
        os.write(child.master, b".")
        screen_until(child, lambda text: "shown=7" in text and "hidden=off" in text,
                     "the hidden files hidden again")

        # Deleting the selected file leaves the listing stale, and confirming it says so. The
        # deleted entry is an allowed one: a refused entry is refused before the filesystem is read.
        os.write(child.master, b"\x1b[B")
        screen_until(child, lambda text: "selected=draft.txt" in text, "draft.txt selected")
        os.write(child.master, b"x")
        screen_until(child, lambda text: "last=deleted-/draft.txt" in text, "the deleted file")
        require(field_row(child, 7).startswith("\u276F draft.txt"),
                f"the stale entry still painted {field_row(child, 7)!r}")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "last=rejected-/draft.txt-missing" in text,
                     "the stale entry checked before it was chosen")
        os.write(child.master, b"r")
        screen_until(child, lambda text: "shown=6" in text, "the reloaded directory")
        require(field_row(child, 7).startswith("  photo.png"),
                f"the reloaded window painted {field_row(child, 7)!r}")

        # The scene creates a file through its own task; a reload shows it.
        os.write(child.master, b"a")
        screen_until(child, lambda text: "last=added-added-1.txt" in text and "added=1" in text,
                     "the created file")
        os.write(child.master, b"r")
        screen_until(child, lambda text: "entries=9" in text and "added-1.txt" in text,
                     "the created file in the listing")

        # Canceling ends the session.
        os.write(child.master, b"\x1b")
        child.finish()
    print("PASS parity 14 listing, navigation, refusal, hidden files, stale entries and a choice")


def settle(child, quiet=0.2, limit=10):
    """Reads until the child has painted nothing for a quiet interval, so a frame is complete.

    A frame arrives in more than one write, so a screen read the instant a predicate matches can
    catch the middle of one: the rows below the last painted row are still the previous frame's.
    Assertions about painted rows copy a screen, so they wait for the terminal to go quiet first.
    """
    deadline = time.monotonic() + limit
    while time.monotonic() < deadline:
        if not child.pump(quiet):
            return
    raise AssertionError("the child kept painting without a pause")


def screen_row(child, text):
    """The index of the first screen row holding text."""
    for index, row in enumerate(child.screen.display):
        if text in row:
            return index
    raise AssertionError(f"no screen row holds {text!r}: {child.screen.display!r}")


def row_cells(child, row):
    """The cells one screen row holds, in column order."""
    buffer = child.screen.buffer[row]
    return [buffer[column] for column in range(child.screen.columns)]


def row_has_style(child, row, text, attribute):
    """Whether any cell inside the first occurrence of text on a row carries one pyte attribute."""
    column = child.screen.display[row].index(text)
    return any(getattr(cell, attribute) for cell in row_cells(child, row)[column:column + len(text)])


def check_markdown_parity(java_command):
    """Phase 15: a markdown document paged, searched, styled, bounded and re-projected."""
    with PtyChild(java_command, control=False) as child:
        # The two status rows, the find field and the document's first row under its gutter.
        screen_until(child,
                     lambda text: "theme=light mode=wrap numbers=on rows=19 width=74 lines=132 limits=default" in text,
                     "the renderer's status row")
        screen_until(child,
                     lambda text: "diagnostics=none matches=0 active=0 query=- focus=pager last=started" in text,
                     "the projection's status row")
        require(field_row(child, 2) == "find:", f"the find field painted {field_row(child, 2)!r}")
        require(field_row(child, 3) == "  1 \u2502 A field guide to the paper lantern",
                f"the pager's first row painted {field_row(child, 3)!r}")
        # The heading's own style reached the terminal, and the body did not borrow it.
        require(row_has_style(child, 3, "field guide", "bold"), "the heading was not painted bold")
        require(row_has_style(child, 3, "field guide", "underscore"), "the first heading was not underlined")
        body = screen_row(child, "A lantern is a small promise")
        require(not row_has_style(child, body, "A lantern is a small promise", "bold"),
                "body text borrowed the heading's weight")
        screen_until(child, lambda text: "\u2191/k up \u2022 \u2193/j down \u2022 b/pgup page up" in text,
                     "the pager's compact help")
        settle(child)
        require("t dark/light \u2022 w wrap/pan" in field_row(child, 23),
                f"the scene's compact help painted {field_row(child, 23)!r}")

        # The theme switch re-projects the same document and paints it with the other palette.
        os.write(child.master, b"t")
        screen_until(child, lambda text: "theme=dark" in text and "last=theme-dark" in text,
                     "the dark theme")
        screen_until(child, lambda text: field_row(child, 3) == "  1 \u2502 A field guide to the paper lantern",
                     "the document under the dark theme")
        settle(child)
        require(row_has_style(child, 3, "field guide", "bold"), "the dark heading was not bold")

        # The mode switch projects wide so the pane has something to pan.
        os.write(child.master, b"w")
        screen_until(child, lambda text: "mode=pan" in text and "width=200" in text, "the panned projection")
        settle(child)
        require(field_row(child, 3) == "  1 \u2502 A field guide to the paper lantern",
                f"the panned first row painted {field_row(child, 3)!r}")
        os.write(child.master, b"\x1b[C\x1b[C\x1b[C")
        screen_until(child, lambda text: field_row(child, 3) != "  1 \u2502 A field guide to the paper lantern",
                     "the cells panned off the left edge")
        settle(child)
        require(field_row(child, 3).startswith("  1 \u2502 "), f"the gutter moved: {field_row(child, 3)!r}")
        require("guide to the paper lantern" in field_row(child, 3),
                f"the panned row painted {field_row(child, 3)!r}")
        os.write(child.master, b"w")
        screen_until(child, lambda text: "mode=wrap" in text and field_row(child, 3) == "  1 \u2502 A field guide to the paper lantern",
                     "the wrapped projection again")

        # The gutter takes cells off the projection, so taking it away widens the document by six.
        os.write(child.master, b"g")
        screen_until(child, lambda text: "numbers=off" in text and "width=80" in text,
                     "the unguttered projection")
        settle(child)
        require(field_row(child, 3) == "A field guide to the paper lantern",
                f"the unguttered row painted {field_row(child, 3)!r}")
        os.write(child.master, b"g")
        screen_until(child, lambda text: "numbers=on" in text and field_row(child, 3) == "  1 \u2502 A field guide to the paper lantern",
                     "the gutter switched on again")

        # A limit below what the document paints is a typed diagnostic, not a partial page.
        os.write(child.master, b"p")
        screen_until(child, lambda text: "limits=strict" in text and "diagnostics=too-many-cells" in text,
                     "the strict limits")
        settle(child)
        require(field_row(child, 3) == "  1 \u2502 markdown: too many cells: more than 200",
                f"the diagnostic row painted {field_row(child, 3)!r}")
        require("lines=1" in field_row(child, 0), f"the failure published {field_row(child, 0)!r}")
        os.write(child.master, b"p")
        screen_until(child, lambda text: "limits=default" in text and "diagnostics=none" in text,
                     "the default limits")
        settle(child)
        require(field_row(child, 3) == "  1 \u2502 A field guide to the paper lantern",
                f"the restored document painted {field_row(child, 3)!r}")

        # Search: the query narrows nothing, every match is published and the active one is painted.
        os.write(child.master, b"/")
        screen_until(child, lambda text: "focus=field" in text, "the field focused")
        os.write(child.master, b"lantern")
        screen_until(child,
                     lambda text: re.search(r"matches=\d+ active=1 query=lantern focus=field", text) is not None,
                     "the search finding every keyword")
        settle(child)
        require("find: lantern" in field_row(child, 2), f"the field painted {field_row(child, 2)!r}")
        found = int(re.search(r"matches=(\d+)", field_row(child, 1)).group(1))
        require(found > 5, f"the document published {found} matches")
        os.write(child.master, b"\x1b")
        screen_until(child, lambda text: "focus=pager" in text, "the pager focused again")
        os.write(child.master, b"n")
        screen_until(child, lambda text: "active=2" in text, "the second match")
        # Every match is underlined and exactly the active one is reversed, which is the pager's
        # published style rather than the heading's.
        matched = [index for index, row in enumerate(child.screen.display)
                   if index >= 3 and "lantern" in row]
        require(len(matched) >= 2, f"the window painted {len(matched)} matching rows")
        active = [index for index in matched
                  if any(cell.reverse for cell in row_cells(child, index) if cell.data.strip())]
        require(len(active) == 1, f"{len(active)} of {len(matched)} matching rows were painted active")

        # A taller terminal gives the pager more rows at the same width, so the same projection
        # simply shows more of itself.
        child.screen.resize(60, 80)
        child.resize(60, 80)
        screen_until(child, lambda text: "rows=55" in text and "width=74" in text,
                     "the taller allocation")
        settle(child)
        require(field_row(child, 3) == "  1 \u2502 A field guide to the paper lantern",
                f"a resize moved a reader at the top: {field_row(child, 3)!r}")
        screen_until(child, lambda text: "\u250c\u2500" in text and "[x] Paper bought at the market" in text,
                     "the table and the task list")
        settle(child)
        require("\u2502 A lantern is a lamp" in "\n".join(child.screen.display),
                "the block quote's bar was not painted")
        # One page down reaches the three highlighted fences and the indented block.
        os.write(child.master, b"f")
        screen_until(child, lambda text: "public final class Candle {" in text, "the fenced Java block")
        settle(child)
        require('"lantern": "paper"' in "\n".join(child.screen.display),
                "the fenced JSON block was not painted")
        require("if [ -x \"$hook\" ]; then" in "\n".join(child.screen.display),
                "the fenced shell block was not painted")
        require("open it once, and once only" in "\n".join(child.screen.display),
                "the indented code block was not painted")
        # The Java fence is a code block: its background is the theme's, not the terminal's.
        java_row = screen_row(child, "public final class Candle {")
        column = field_row(child, java_row).index("public")
        require(child.screen.buffer[java_row][column].bg != "default",
                "the code block painted no background")

        # The end of the document: the wide glyphs, the image's alt text and the raw HTML as text.
        os.write(child.master, b"\x1b[F")
        screen_until(child, lambda text: "<div class=\"stall\">" in text, "the raw HTML block")
        settle(child)
        require("</div>" in "\n".join(child.screen.display), "the rest of the HTML block was not painted")
        require("a paper lantern hanging above a stall" in "\n".join(child.screen.display),
                "the image's alt text was not painted")
        wide = screen_row(child, "\u63d0\u706f")
        column = child.screen.display[wide].index("\u63d0\u706f")
        # Each CJK glyph occupies two cells and the text after them starts where the second one
        # ends, which is the pinned width policy reaching the terminal rather than a text match.
        require([cell.data for cell in row_cells(child, wide)[column:column + 7]]
                        == ["\u63d0", "", "\u706f", "", " ", "i", "n"],
                f"the wide glyphs were painted {[cell.data for cell in row_cells(child, wide)[column:column + 7]]!r}")

        os.write(child.master, b"\x03")
        child.finish()
    print("PASS parity 15 markdown projection, themes, modes, bounds, search, resize and paging")


def timer_seconds(child):
    """The whole seconds the timer page reports on the scene's second status row."""
    match = re.search(r"timer=(\d+):(\d+)", field_row(child, 1))
    require(match is not None, f"the scene reported no timer: {field_row(child, 1)!r}")
    return int(match.group(1)) * 60 + int(match.group(2))


def check_tabs_parity(java_command):
    """Phase 16: three live pages behind one tab bar, kept alive across switches and a resize."""
    with PtyChild(java_command, control=False) as child:
        # The scene's two status rows, the bar and the joined frame edge under its cells.
        screen_until(child, lambda text: "tab=notes focus=page pages=3 switched=0 last=none" in text,
                     "the scene's status row")
        screen_until(child, lambda text: "notes=208 inbox=item-01 timer=02:00 run=off help=compact" in text,
                     "the pages' status row")
        require(field_row(child, 2) == "\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E"
                                       "\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E\u256D\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u256E",
                f"the bar's cells painted {field_row(child, 2)!r}")
        require(field_row(child, 3) == "\u2502 Notes \u2502\u2502 Inbox \u2502\u2502 Timer \u2502\u2502 Archive \u2502",
                f"the bar's labels painted {field_row(child, 3)!r}")
        # The shown tab opens into the frame: the bar's last row is the frame's top edge.
        require(field_row(child, 4).startswith("\u2502       \u2514\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534"
                                               "\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534"),
                f"the shown tab did not open into the frame: {field_row(child, 4)!r}")
        require(field_row(child, 4).endswith("\u2500\u256E"), f"the frame's top edge painted {field_row(child, 4)!r}")
        require(field_row(child, 5).startswith("\u2502A tab bar is a claim about identity"),
                f"the editor page painted {field_row(child, 5)!r}")
        screen_until(child, lambda text: field_row(child, 22) == "enter submit \u2022 alt+c copy \u2022 ctrl+v paste",
                     "the editor page's own help")
        require("alt+1 notes" in field_row(child, 23),
                f"the scene's help painted {field_row(child, 23)!r}")

        # Typing reaches the shown page and nothing else.
        os.write(child.master, b"abc")
        screen_until(child, lambda text: "notes=211" in text, "the typed draft")
        require("survive the trip.abc" in "\n".join(child.screen.display),
                "the draft was not painted")

        # Tab moves the keyboard to the bar, and the help row follows it.
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=bar" in text, "the bar focused")
        screen_until(child, lambda text: field_row(child, 22) == "\u2190 previous tab \u2022 \u2192 next tab \u2022 home first tab \u2022 end last tab",
                     "the bar's own help")

        # Right moves to the list page, which paints its items and its own actions.
        os.write(child.master, b"\x1b[C")
        screen_until(child, lambda text: "tab=inbox" in text and "switched=1" in text and "last=tab-inbox" in text,
                     "the list page")
        require(field_row(child, 4).startswith("\u251C\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2518       \u2514\u2534"),
                f"the list tab did not open into the frame: {field_row(child, 4)!r}")
        require(field_row(child, 5).startswith("\u2502Inbox"), f"the list title painted {field_row(child, 5)!r}")
        screen_until(child, lambda text: "\u276F Paper lantern" in "\n".join(child.screen.display),
                     "the list's first item")

        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=page" in text, "the list focused")
        screen_until(child, lambda text: field_row(child, 22) == "\u2191 up \u2022 \u2193 down \u2022 \u2190 previous page \u2022 \u2192 next page \u2022 enter choose \u2022 esc cancel",
                     "the list's own actions")
        os.write(child.master, b"\x1b[B")
        screen_until(child, lambda text: "inbox=item-02" in text, "the second item selected")

        # A direct activation moves both the page and the keyboard; the timer page answers space.
        os.write(child.master, b"\x1b3")
        screen_until(child, lambda text: "tab=timer" in text and "focus=page" in text and "switched=2" in text,
                     "the timer page")
        require(field_row(child, 5).startswith("\u250202:00"), f"the countdown painted {field_row(child, 5)!r}")
        screen_until(child, lambda text: field_row(child, 22) == "space start/pause \u2022 r reset",
                     "the timer page's own actions")
        os.write(child.master, b" ")
        screen_until(child, lambda text: "run=on" in text and "last=timer-toggle" in text, "the running countdown")
        screen_until(child, lambda text: timer_seconds(child) < 120, "the countdown's first second")

        # The first page keeps its draft, and the hidden timer keeps running.
        hidden_from = timer_seconds(child)
        os.write(child.master, b"\x1b1")
        screen_until(child, lambda text: "tab=notes" in text and "switched=3" in text, "the notes page again")
        require("survive the trip.abc" in "\n".join(child.screen.display),
                "the first page lost its draft")
        screen_until(child, lambda text: timer_seconds(child) < hidden_from, "the hidden countdown advancing")
        require("run=on" in field_row(child, 1), f"the countdown stopped: {field_row(child, 1)!r}")

        # The bar navigates to the last enabled tab and stops there: the disabled one is unreachable.
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=bar" in text, "the bar focused again")
        os.write(child.master, b"\x1b[F")
        screen_until(child, lambda text: "tab=timer" in text and "switched=4" in text, "the last enabled tab")
        os.write(child.master, b"\x1b[C")
        screen_until(child, lambda text: "tab=timer" in text, "the bar at its end")
        require("switched=4" in field_row(child, 0),
                f"a refused step was reported as a transition: {field_row(child, 0)!r}")

        # A narrower terminal windows the bar and keeps the shown tab's label on screen.
        child.screen.resize(24, 30)
        child.resize(24, 30)
        screen_until(child, lambda text: field_row(child, 3) == "\u2502\u2039\u2502\u2502 Timer \u2502\u2502 Archive \u2502",
                     "the windowed bar")
        require("Notes" not in field_row(child, 3), f"the window painted {field_row(child, 3)!r}")
        require(field_row(child, 4).startswith("\u251C\u2500\u2534\u2518       \u2514\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534"),
                f"the windowed frame edge painted {field_row(child, 4)!r}")
        child.screen.resize(24, 80)
        child.resize(24, 80)
        screen_until(child, lambda text: field_row(child, 3) == "\u2502 Notes \u2502\u2502 Inbox \u2502\u2502 Timer \u2502\u2502 Archive \u2502",
                     "the whole bar again")
        require("tab=timer" in field_row(child, 0), f"the resize moved the page: {field_row(child, 0)!r}")
        require("notes=211" in field_row(child, 1), f"the resize lost a draft: {field_row(child, 1)!r}")

        os.write(child.master, b"\x03")
        child.finish()
    print("PASS parity 16 tabs, the joined frame, page keep-alive, focus, help and a resize")


# The demo's geometry at 24x80, as the scene paints it: the two status rows, the split at column
# 40, the left pane's editor from row 2, the right pane's list entries from row 4, and the dialog's
# box from row 3, column 20.
MOUSE_STATUS_ROW = 0
MOUSE_POINTER_ROW = 1
MOUSE_LEFT_CELL = (2, 5)
MOUSE_LIST_ROW = 6
MOUSE_DIALOG_ROWS = (5, 6)
MOUSE_DIALOG_COLUMN = 25
MOUSE_BLANK_UNDER_DIALOG = (15, 5)


def mouse_press(row, column, button=0):
    """One SGR press report at a zero-based cell."""
    return f"\u001b[<{button};{column + 1};{row + 1}M".encode()


def mouse_release(row, column, button=0):
    """One SGR release report at a zero-based cell."""
    return f"\u001b[<{button};{column + 1};{row + 1}m".encode()


def mouse_motion(row, column, button=32):
    """One SGR motion report at a zero-based cell, with the left button held by default."""
    return f"\u001b[<{button};{column + 1};{row + 1}M".encode()


def mouse_wheel(row, column, up=False):
    """One SGR wheel report at a zero-based cell."""
    return f"\u001b[<{64 if up else 65};{column + 1};{row + 1}M".encode()


def check_mouse_parity(java_command):
    """Phase 17: pointer reports hit-tested against the frame, captured, and isolated by a modal."""
    with PtyChild(java_command, control=False) as child:
        # The mode the demo asked for is the mode the terminal was told, in the SGR encoding.
        screen_until(child, lambda text: "pane=left dialog=closed" in text, "the scene's status row")
        screen_until(child, lambda text: "pointer=none reports=0" in text, "the pointer log row")
        transcript = bytes(child.output).decode("utf-8", "replace")
        require("\u001b[?1002h\u001b[?1006h" in transcript,
                "the cell-motion tracking level and the SGR encoding were not asked for")
        require("pane=left" in field_row(child, MOUSE_STATUS_ROW), "the scene's status row is missing")
        require(field_row(child, 2).startswith("Click in this pane"), f"the editor painted {field_row(child, 2)!r}")
        require("\u2502Items" in field_row(child, 2), f"the list title painted {field_row(child, 2)!r}")

        # A click in the left pane places the caret there, and the scene logged the report.
        row, column = MOUSE_LEFT_CELL
        os.write(child.master, mouse_press(row, column))
        screen_until(child, lambda text: "caret=5" in text, "the editor's caret at the clicked cell")
        screen_until(child, lambda text: f"pointer=press:left@{row},{column} reports=1" in text,
                     "the logged press")
        os.write(child.master, mouse_release(row, column))
        screen_until(child, lambda text: f"pointer=release:left@{row},{column} reports=2" in text,
                     "the logged release")

        # The same scene, the other pane: the click lands on an item's row.
        os.write(child.master, mouse_press(MOUSE_LIST_ROW, 45))
        screen_until(child, lambda text: "sel=item-02" in text, "the list's second item")
        screen_until(child, lambda text: "pane=right" in text, "the keyboard following the click")
        require("caret=5" in field_row(child, MOUSE_STATUS_ROW),
                f"the click in one pane moved the other: {field_row(child, MOUSE_STATUS_ROW)!r}")

        # A drag keeps belonging to the pane that captured the press, even outside it.
        os.write(child.master, mouse_release(MOUSE_LIST_ROW, 45))
        os.write(child.master, mouse_press(2, 2))
        screen_until(child, lambda text: "caret=2" in text, "the dragged caret's first cell")
        os.write(child.master, mouse_motion(4, 60))
        screen_until(child, lambda text: "pointer=motion:left@4,60" in text, "the logged motion")
        require("sel=item-02" in field_row(child, MOUSE_STATUS_ROW),
                f"the drag left its pane and moved the list: {field_row(child, MOUSE_STATUS_ROW)!r}")
        os.write(child.master, mouse_release(4, 60))

        # The wheel acts on the pane it is over.
        os.write(child.master, mouse_wheel(MOUSE_LIST_ROW, 45))
        screen_until(child, lambda text: "sel=item-03" in text, "the wheel stepping the list")

        # The dialog is a route of its own: the panes under it are unreachable, and it answers.
        os.write(child.master, b"\x1bm")
        screen_until(child, lambda text: "dialog=open" in text, "the open dialog")
        require("\u250c" in field_row(child, 3) and "Dialog" in field_row(child, 4),
                f"the dialog's box painted {field_row(child, 3)!r} {field_row(child, 4)!r}")
        caret_before = field_row(child, MOUSE_STATUS_ROW)
        blank_row, blank_column = MOUSE_BLANK_UNDER_DIALOG
        os.write(child.master, mouse_press(blank_row, blank_column))
        screen_until(child, lambda text: f"pointer=press:left@{blank_row},{blank_column}" in text,
                     "the logged press outside the dialog")
        require(field_row(child, MOUSE_STATUS_ROW) == caret_before,
                f"a click under the dialog reached a pane: {field_row(child, MOUSE_STATUS_ROW)!r}")
        first, second = MOUSE_DIALOG_ROWS
        os.write(child.master, mouse_press(first, MOUSE_DIALOG_COLUMN))
        screen_until(child, lambda text: "dlg=dialog-01" in text and "pointer=press:left@"
                     f"{first},{MOUSE_DIALOG_COLUMN}" in text, "the dialog's first entry")
        os.write(child.master, mouse_press(second, MOUSE_DIALOG_COLUMN))
        screen_until(child, lambda text: "dlg=dialog-02" in text, "the dialog's second entry")
        os.write(child.master, b"\x1bm")
        screen_until(child, lambda text: "dialog=closed" in text, "the closed dialog")

        # The panes are reachable again once the dialog is gone.
        os.write(child.master, mouse_press(2, 3))
        screen_until(child, lambda text: "caret=3" in text, "the editor after the dialog closed")

        os.write(child.master, b"\x03")
        child.finish()
        transcript = bytes(child.output).decode("utf-8", "replace")
        require("\u001b[?1006l\u001b[?1002l" in transcript,
                "the pointer modes were not undone in the reverse order they were asked for")
    print("PASS parity 17 pointer reports, hit testing, capture, the wheel and a modal layer")


# Phase 18's scene rows, as the demo paints them.
QUERY_PROFILE_ROW = 0
QUERY_COLOR_ROW = 1
QUERY_FACTS_ROW = 2
QUERY_TALLY_ROW = 3

# The request sequences the xterm control sequence reference documents, as bytes.
CURSOR_REQUEST = b"\x1b[6n"
IDENTITY_REQUEST = b"\x1b[>q"
MODE_REQUEST = b"\x1b[?2004$p"
TERMCAP_REQUEST = b"\x1bP+q5463\x1b\\"
BACKGROUND_REQUEST = b"\x1b]11;?\x07"
FOREGROUND_REQUEST = b"\x1b]10;?\x07"


def written_since(child, start):
    """The bytes the child wrote after offset ``start``."""
    return bytes(child.output[start:])


def query_written(child, sequence, start, description):
    """Waits until the child wrote one request after ``start``, and returns the offset just past it.

    The offset is the position after the matched request, not the end of what the child has written.
    A batch of requests is often written in one go and read as one chunk, so returning the end of the
    buffer would put every later request of that batch behind the next window and the next search
    could never find it. Callers that only care that a request was written may ignore the result.
    """
    deadline = time.monotonic() + 15
    while True:
        found = written_since(child, start).find(sequence)
        if found >= 0:
            return start + found + len(sequence)
        require(time.monotonic() < deadline, f"the application never wrote {description}")
        require(child.process.poll() is None, f"child exited before {description}")
        child.pump(0.05)


def check_terminal_query_parity(java_command):
    """Phase 18: bounded queries, typed answers, session facts and color profiles."""
    with PtyChild(java_command, control=False) as child:
        # Nothing is asked at startup: the demo's first question is the first key.
        screen_until(child, lambda text: "asked=0 answered=0 failed=0 last=none" in text,
                     "the empty tally row")
        screen_until(child, lambda text: "profile=" in field_row(child, QUERY_PROFILE_ROW),
                     "the profile row")
        require(not any(request in bytes(child.output) for request in
                        (CURSOR_REQUEST, IDENTITY_REQUEST, MODE_REQUEST, TERMCAP_REQUEST,
                         BACKGROUND_REQUEST, FOREGROUND_REQUEST)),
                "a query reached the terminal before the application asked one")

        # One question, one answer, one typed fact: the cursor cell is reported one-based and
        # shown zero-based.
        start = len(child.output)
        os.write(child.master, b"c")
        start = query_written(child, CURSOR_REQUEST, start, "the cursor position request")
        os.write(child.master, b"\x1b[5;9R")
        screen_until(child, lambda text: "cursor=4,8" in text, "the reported cursor cell")

        # The terminal's own identity, from XTVERSION.
        os.write(child.master, b"i")
        start = query_written(child, IDENTITY_REQUEST, start, "the terminal version request")
        os.write(child.master, b"\x1bP>|xterm(390)\x1b\\")
        screen_until(child, lambda text: "identity=xterm(390)" in text, "the reported identity")

        # A mode report says what the terminal actually did with bracketed paste.
        os.write(child.master, b"m")
        start = query_written(child, MODE_REQUEST, start, "the mode request")
        os.write(child.master, b"\x1b[?2004;1$y")
        screen_until(child, lambda text: "paste=set" in text, "the reported mode")

        # A termcap capability the terminal claims.
        os.write(child.master, b"t")
        start = query_written(child, TERMCAP_REQUEST, start, "the termcap request")
        os.write(child.master, b"\x1bP1+r5463=54727565436F6C6F72\x1b\\")
        screen_until(child, lambda text: "tc=TrueColor" in text, "the reported capability")

        # The background color is the session's theme, and the theme row derives from it.
        os.write(child.master, b"b")
        start = query_written(child, BACKGROUND_REQUEST, start, "the background color request")
        os.write(child.master, b"\x1b]11;rgb:1010/1010/1010\x07")
        screen_until(child, lambda text: "theme=dark" in text, "the dark theme")
        require("bg=#101010" in field_row(child, QUERY_COLOR_ROW),
                f"the reported background: {field_row(child, QUERY_COLOR_ROW)!r}")

        # A color the terminal refuses is reported as unsupported, never as black.
        os.write(child.master, b"f")
        start = query_written(child, FOREGROUND_REQUEST, start, "the foreground color request")
        os.write(child.master, b"\x1b]10;?\x07")
        screen_until(child, lambda text: "last=foreground=unsupported" in text,
                     "the refused color")

        # A terminal that never answers is a timeout, reported honestly after the deadline.
        os.write(child.master, b"c")
        start = query_written(child, CURSOR_REQUEST, start, "the unanswered cursor request")
        screen_until(child, lambda text: "last=cursor=timeout" in text,
                     "the unanswered query's timeout", timeout=10)

        # A reply nobody asked for changes nothing, and is never delivered as input.
        tally = field_row(child, QUERY_TALLY_ROW)
        os.write(child.master, b"\x1b[9;9R")
        pump_for(child, 0.25)
        require(field_row(child, QUERY_TALLY_ROW) == tally,
                f"an unrequested reply moved the tally: {field_row(child, QUERY_TALLY_ROW)!r}")

        # Every terminal question in one batch, answered in full.
        os.write(child.master, b"a")
        for request, description in ((CURSOR_REQUEST, "the cursor request"),
                                     (IDENTITY_REQUEST, "the version request"),
                                     (MODE_REQUEST, "the mode request"),
                                     (TERMCAP_REQUEST, "the termcap request")):
            start = query_written(child, request, start, description)
        os.write(child.master, b"\x1b[2;2R")
        os.write(child.master, b"\x1bP>|tk(1.0)\x1b\\")
        os.write(child.master, b"\x1b[?2004;2$y")
        os.write(child.master, b"\x1bP0+r5463\x1b\\")
        os.write(child.master, b"\x1b]11;rgb:ffff/ffff/ffff\x07")
        os.write(child.master, b"\x1b]10;rgb:0000/0000/0000\x07")
        os.write(child.master, b"\x1b]12;rgb:0000/ffff/0000\x07")
        screen_until(child, lambda text: "answered=" in text and "failed=2" in text,
                     "the answered and refused tally")

        # The profile the frames are painted with. Each switch repaints the whole screen, so the
        # bytes after the key are the proof.
        start = len(child.output)
        os.write(child.master, b"3")
        screen_until(child, lambda text: "profile=ansi256" in text, "the 256-color profile")
        require(b"38;5;" in written_since(child, start)
                and b"38;2;" not in written_since(child, start),
                "the 256-color profile did not repaint the sample in palette indices")

        start = len(child.output)
        os.write(child.master, b"2")
        screen_until(child, lambda text: "profile=ansi16" in text, "the 16-color profile")
        painted = written_since(child, start)
        require(b"38;5;" not in painted and b"38;2;" not in painted,
                "the 16-color profile kept a wider color form")

        start = len(child.output)
        os.write(child.master, b"1")
        screen_until(child, lambda text: "profile=none color=off" in text, "the colorless profile")
        painted = written_since(child, start)
        require(b"38;" not in painted and b"48;" not in painted,
                "a colorless profile wrote color parameters")

        start = len(child.output)
        os.write(child.master, b"4")
        screen_until(child, lambda text: "profile=truecolor" in text, "the truecolor profile")
        require(b"38;2;" in written_since(child, start),
                "the truecolor profile did not repaint the sample in 24-bit color")

        # The session profile is a fact, so asking for it writes nothing to the terminal.
        start = len(child.output)
        os.write(child.master, b"p")
        screen_until(child, lambda text: "last=profile=truecolor" in text, "the profile answer")
        painted = written_since(child, start)
        require(not any(request in painted for request in
                        (CURSOR_REQUEST, IDENTITY_REQUEST, MODE_REQUEST, TERMCAP_REQUEST,
                         BACKGROUND_REQUEST, FOREGROUND_REQUEST)),
                "asking for the session profile wrote a terminal query")

        os.write(child.master, b"\x03")
        child.finish()

    # NO_COLOR is the user's own request to lose color, and it outranks the terminal's ability.
    with PtyChild(java_command, control=False, env={"NO_COLOR": "1"}) as child:
        screen_until(child, lambda text: "profile=none color=off" in text, "the colorless session")
        require(b"38;2;" not in bytes(child.output) and b"38;5;" not in bytes(child.output),
                "a NO_COLOR session painted a color")
        os.write(child.master, b"\x03")
        child.finish()
    print("PASS parity 18 bounded queries, typed answers, session facts and color profiles")


# Phase 19's scene rows, as the demo paints them.
CLIPBOARD_COPY_ROW = 0
CLIPBOARD_READ_ROW = 1
CLIPBOARD_TEXT_ROW = 2
CLIPBOARD_INTERNAL_ROW = 3

# The sample the demo copies, and the two read requests the protocol documents.
CLIPBOARD_SAMPLE = "braxton clipboard sample"
CLIPBOARD_SYSTEM_REQUEST = b"\x1b]52;c;?\x07"
CLIPBOARD_PRIMARY_REQUEST = b"\x1b]52;p;?\x07"


def clipboard_write(selection, text):
    """The OSC 52 write one selection would receive for ``text``."""
    return b"\x1b]52;" + selection.encode() + b";" + base64.b64encode(text.encode("utf-8")) + b"\x07"


def clipboard_reply(selection, text):
    """The OSC 52 reply a terminal gives for ``text``."""
    return clipboard_write(selection, text)


def check_clipboard_parity(java_command):
    """Phase 19: the terminal and primary clipboards, read and written on request."""
    # TMUX is cleared so the scenario is the direct transport wherever the verifier runs: a
    # multiplexed developer shell must not change what the assertions mean.
    with PtyChild(java_command, control=False, env={"TMUX": ""}) as child:
        # Nothing is asked at startup, and nothing is written.
        screen_until(child, lambda text: "copy=idle" in text and "read=idle" in text,
                     "the empty copy and read rows")
        screen_until(child, lambda text: "internal=unread" in text and "text=-" in text,
                     "the empty text and internal rows")
        require(b"]52;" not in bytes(child.output),
                "a clipboard sequence reached the terminal before the application asked for one")

        # A copy writes the system selection once, and reports the transport rather than claiming the
        # terminal accepted anything.
        start = len(child.output)
        os.write(child.master, b"s")
        query_written(child, clipboard_write("c", CLIPBOARD_SAMPLE), start, "the system clipboard write")
        screen_until(
            child,
            lambda text: ("copy=system path=osc52_direct bytes=24 "
                          "stored=on truncated=off dedup=off") in text,
            "the system copy row")

        # The same text to the same selection is not written a second time.
        start = len(child.output)
        os.write(child.master, b"s")
        screen_until(child, lambda text: "dedup=on" in text, "the deduplicated copy row")
        pump_for(child, 0.25)
        require(clipboard_write("c", CLIPBOARD_SAMPLE) not in written_since(child, start),
                "an identical copy was written twice")

        # The primary selection is a separate destination with its own sequence.
        start = len(child.output)
        os.write(child.master, b"p")
        query_written(child, clipboard_write("p", CLIPBOARD_SAMPLE), start, "the primary clipboard write")
        screen_until(child, lambda text: "copy=primary path=osc52_direct" in text,
                     "the primary copy row")

        # A read asks the question and reports the text the terminal sent, decoded from base64.
        os.write(child.master, b"c")
        start = query_written(child, CLIPBOARD_SYSTEM_REQUEST, len(child.output),
                              "the system clipboard request")
        os.write(child.master, clipboard_reply("c", "h\u00e9llo"))
        screen_until(child, lambda text: "read=system ok bytes=6" in text, "the reported system text")
        require(field_row(child, CLIPBOARD_TEXT_ROW) == "text=h\u00e9llo",
                f"the reported text row painted {field_row(child, CLIPBOARD_TEXT_ROW)!r}")

        # An empty selection is a successful read of nothing, which a timeout must never look like.
        os.write(child.master, b"v")
        query_written(child, CLIPBOARD_PRIMARY_REQUEST, start, "the primary clipboard request")
        os.write(child.master, b"\x1b]52;p;\x07")
        screen_until(child, lambda text: "read=primary ok bytes=0" in text, "the empty primary selection")
        require(field_row(child, CLIPBOARD_TEXT_ROW) == "text=-",
                f"an empty read painted {field_row(child, CLIPBOARD_TEXT_ROW)!r}")

        # A payload that is not base64 is reported as invalid, and none of it becomes text.
        os.write(child.master, b"c")
        start = query_written(child, CLIPBOARD_SYSTEM_REQUEST, len(child.output),
                              "the second system clipboard request")
        os.write(child.master, b"\x1b]52;c;!!!!\x07")
        screen_until(child, lambda text: "read=system invalid" in text, "the refused payload")
        require(field_row(child, CLIPBOARD_TEXT_ROW) == "text=-",
                f"a malformed payload painted {field_row(child, CLIPBOARD_TEXT_ROW)!r}")

        # A reply for the other selection answers nothing: the read waits out its deadline and says so.
        os.write(child.master, b"c")
        query_written(child, CLIPBOARD_SYSTEM_REQUEST, start, "the third system clipboard request")
        os.write(child.master, clipboard_reply("p", "wrong selection"))
        screen_until(child, lambda text: "read=system timeout" in text,
                     "the unanswered read's timeout", timeout=15)

        # A reply nobody asked for changes nothing and is never delivered as text.
        tally = field_row(child, CLIPBOARD_READ_ROW)
        os.write(child.master, clipboard_reply("c", "unsolicited"))
        pump_for(child, 0.3)
        require(field_row(child, CLIPBOARD_READ_ROW) == tally,
                f"an unrequested reply moved the read row: {field_row(child, CLIPBOARD_READ_ROW)!r}")

        # The in-process buffer needs no terminal: reading it writes no bytes at all.
        start = len(child.output)
        os.write(child.master, b"i")
        screen_until(child, lambda text: "internal=bytes=24" in text, "the in-process buffer length")
        require(b"]52;" not in written_since(child, start),
                "reading the in-process buffer wrote to the terminal")

        # The session still takes keys after the bad replies it reported.
        os.write(child.master, b"s")
        screen_until(child, lambda text: "copy=system path=osc52_direct" in text,
                     "a copy after the malformed replies")

        os.write(child.master, b"\x03")
        child.finish()
    print("PASS parity 19 terminal and primary clipboards, reads, refusals and the in-process buffer")


# Phase 20's scene rows, as the demo paints them.
PRESENT_MODES_ROW = 0
PRESENT_METADATA_ROW = 1
PRESENT_INTENT_ROW = 2
PRESENT_SUPPORT_ROW = 3
PRESENT_STATES_ROW = 4
PRESENT_KINDS_ROW = 5
PRESENT_LAST_ROW = 6
PRESENT_BODY_ROW = 8

# Phase 21's scene rows, as the demo paints them.
HANDOFF_STATUS_ROW = 0
HANDOFF_DRAFT_ROW = 1
HANDOFF_BODY_ROW = 3

# Phase 22's scene rows, as the demo paints them.
CONTROL_STATUS_ROW = 0
CONTROL_PROMPT_ROW = 1
CONTROL_BODY_ROW = 3

# The title the demo sets, the sequence it tries to inject, and the answers it reports.
PRESENT_TITLE = b"\x1b]2;braxton presentation\x1b\\"
PRESENT_TITLE_PUSH = b"\x1b[22;0t"
PRESENT_TITLE_POP = b"\x1b[23;0t"
PRESENT_INJECTION = b"\x1b]0;pwned"
PRESENT_SUPPORT_REQUEST = b"\x1b]7501;?\x1b\\"
PRESENT_SUPPORT_REPLY = (b"\x1b]7501;?:states=idle,working,done,blocked,error"
                         b":kinds=permission,question,auth\x1b\\")
PRESENT_SUPPORT_ROW_TEXT = "support=yes states=5 kinds=3"
PRESENT_STATES_TEXT = "states=blocked,done,error,idle,working"
PRESENT_KINDS_TEXT = "kinds=auth,permission,question"


def check_presentation_parity(java_command):
    """Phase 20: declarative terminal presentation, its wire forms and its restoration."""
    with PtyChild(java_command, control=False) as child:
        # The session opens with the options' own presentation and the scene declares nothing, so
        # the first frame carries no metadata at all.
        screen_until(child, lambda text: "screen=default mouse=default paste=default focus=default" in text,
                     "the mode row")
        screen_until(child, lambda text: "title=none fg=none bg=none cursor=none" in text,
                     "the metadata row")
        screen_until(child, lambda text: "progress=none status=none" in text, "the intent row")
        screen_until(child, lambda text: "support=unknown states=0 kinds=0" in text, "the support row")
        require(field_row(child, PRESENT_STATES_ROW) == "states=-"
                and field_row(child, PRESENT_KINDS_ROW) == "kinds=-",
                "the support lists were reported before the terminal answered")
        started = len(child.output)
        for unreachable in (b"\x1b]2;", b"\x1b]9;4;", b"\x1b]7501;state=", b"\x1b]10;", b"\x1b[22;0t"):
            require(unreachable not in bytes(child.output[:started]),
                    f"a frame that declared nothing wrote {unreachable!r}")

        # A title is set, pushed so the session can give the user's own back, and the injected
        # sequence it also asks for never reaches the terminal.
        start = len(child.output)
        os.write(child.master, b"t")
        screen_until(child, lambda text: "title=set" in text, "the set title row")
        query_written(child, PRESENT_TITLE, start, "the window title")
        require(PRESENT_TITLE_PUSH in written_since(child, start),
                "the title was replaced without saving the one it replaced")

        start = len(child.output)
        os.write(child.master, b"t")
        screen_until(child, lambda text: "title=unsafe" in text, "the unsafe title row")
        require(PRESENT_INJECTION not in bytes(child.output),
                "a control sequence injected through the title reached the terminal")

        start = len(child.output)
        os.write(child.master, b"t")
        screen_until(child, lambda text: "title=none" in text, "the dropped title row")
        query_written(child, PRESENT_TITLE_POP, start, "the saved title popped back")

        # Leaving the alternate screen repaints everything on the other buffer, and coming back
        # repaints again.
        gestures = field_row(child, PRESENT_BODY_ROW)
        start = len(child.output)
        os.write(child.master, b"a")
        screen_until(child, lambda text: "screen=plain" in text, "the plain-screen row")
        query_written(child, b"\x1b[?1049l", start, "the switch to the plain screen")
        screen_until(child, lambda text: gestures not in text and "gestures=" in text,
                     "the plain screen repainted in full")

        start = len(child.output)
        os.write(child.master, b"a")
        screen_until(child, lambda text: "screen=alternate" in text, "the alternate-screen row")
        query_written(child, b"\x1b[?1049h", start, "the switch back to the alternate screen")

        # The pointer level, paste and focus reporting each move from the session default to on and
        # back off, and each move is one sequence the terminal either implements or ignores.
        # The session default is no pointer reporting, so the first level writes nothing at all and
        # each later level is one level turned off before the next is asked for.
        start = len(child.output)
        os.write(child.master, b"m")
        screen_until(child, lambda text: "mouse=none" in text, "the empty pointer level")
        pump_for(child, 0.2)
        require(b"\x1b[?1000h" not in written_since(child, start)
                and b"\x1b[?1006h" not in written_since(child, start),
                "asking for no pointer reporting wrote a reporting sequence")
        start = len(child.output)
        os.write(child.master, b"m")
        screen_until(child, lambda text: "mouse=click" in text, "the click pointer level")
        query_written(child, b"\x1b[?1000h\x1b[?1006h", start, "the click pointer level")
        start = len(child.output)
        os.write(child.master, b"m")
        screen_until(child, lambda text: "mouse=cell" in text, "the drag pointer level")
        painted = written_since(child, start)
        require(b"\x1b[?1006l\x1b[?1000l" in painted and b"\x1b[?1002h" in painted,
                "a changed pointer level was not written as one level off and the next on")

        # Bracketed paste starts on, because the session asked for it, so stating it again writes
        # nothing; turning it off and back to the session default are the two transitions.
        start = len(child.output)
        os.write(child.master, b"p")
        screen_until(child, lambda text: "paste=on" in text, "the enabled paste mode")
        pump_for(child, 0.2)
        require(b"\x1b[?2004" not in written_since(child, start),
                "restating the paste mode the session already has wrote a sequence")
        start = len(child.output)
        os.write(child.master, b"p")
        screen_until(child, lambda text: "paste=off" in text, "the disabled paste mode")
        query_written(child, b"\x1b[?2004l", start, "bracketed paste disabled")
        start = len(child.output)
        os.write(child.master, b"p")
        screen_until(child, lambda text: "paste=default" in text, "the session's own paste mode")
        query_written(child, b"\x1b[?2004h", start, "bracketed paste back to the session default")

        start = len(child.output)
        os.write(child.master, b"f")
        screen_until(child, lambda text: "focus=on" in text, "the enabled focus reporting")
        query_written(child, b"\x1b[?1004h", start, "focus reporting enabled")
        start = len(child.output)
        os.write(child.master, b"f")
        screen_until(child, lambda text: "focus=off" in text, "the disabled focus reporting")
        query_written(child, b"\x1b[?1004l", start, "focus reporting disabled")

        # The terminal's default colors are set and then given back as the terminal's own.
        start = len(child.output)
        os.write(child.master, b"c")
        screen_until(child, lambda text: "fg=#e0e6f0 bg=#10141c cursor=#38a6ff" in text,
                     "the applied default colors")
        painted = written_since(child, start)
        for sequence in (b"\x1b]10;rgb:e0/e6/f0\x1b\\", b"\x1b]11;rgb:10/14/1c\x1b\\",
                         b"\x1b]12;rgb:38/a6/ff\x1b\\"):
            require(sequence in painted, f"the default color {sequence!r} was not written")
        start = len(child.output)
        os.write(child.master, b"c")
        screen_until(child, lambda text: "fg=none bg=none cursor=none" in text,
                     "the reset default colors")
        query_written(child, b"\x1b]110\x1b\\", start, "the default text color reset")
        painted = written_since(child, start)
        require(b"\x1b]111\x1b\\" in painted and b"\x1b]112\x1b\\" in painted,
                "the default background and cursor colors were not reset")

        # Every progress state, including the one that removes the indication.
        for key, state, token in ((b"g", 1, b"\x1b]9;4;1;40\x1b\\"),
                                  (b"g", 2, b"\x1b]9;4;2;70\x1b\\"),
                                  (b"g", 3, b"\x1b]9;4;3;0\x1b\\"),
                                  (b"g", 4, b"\x1b]9;4;4;90\x1b\\"),
                                  (b"g", 0, b"\x1b]9;4;0;0\x1b\\")):
            start = len(child.output)
            os.write(child.master, key)
            query_written(child, token, start, f"progress state {state}")

        # The support question is asked, answered with the lists the terminal accepts, and reported
        # as silence when the terminal never answers.
        start = len(child.output)
        os.write(child.master, b"k")
        query_written(child, PRESENT_SUPPORT_REQUEST, start, "the program-status support question")
        os.write(child.master, PRESENT_SUPPORT_REPLY)
        screen_until(child, lambda text: PRESENT_SUPPORT_ROW_TEXT in text,
                     "the reported state and kind lists")
        require(field_row(child, PRESENT_STATES_ROW) == PRESENT_STATES_TEXT,
                f"the reported states read {field_row(child, PRESENT_STATES_ROW)!r}")
        require(field_row(child, PRESENT_KINDS_ROW) == PRESENT_KINDS_TEXT,
                f"the reported kinds read {field_row(child, PRESENT_KINDS_ROW)!r}")
        os.write(child.master, b"k")
        query_written(child, PRESENT_SUPPORT_REQUEST, len(child.output),
                      "the second program-status support question")
        screen_until(child, lambda text: "support=timeout" in text,
                     "the unanswered support question", timeout=15)

        # A status record is replaced whole on each report, so the blocked record carries its own id
        # and kind, and a finished record is what the exit leaves behind.
        start = len(child.output)
        os.write(child.master, b"s")
        screen_until(child, lambda text: "status=working" in text, "the working record")
        query_written(child, b"\x1b]7501;state=working:app=braxton", start, "the working report")
        start = len(child.output)
        os.write(child.master, b"s")
        screen_until(child, lambda text: "status=blocked id=build" in text, "the blocked record")
        query_written(child, b"\x1b]7501;state=blocked:id=build:kind=permission:app=braxton", start,
                      "the blocked report")
        start = len(child.output)
        os.write(child.master, b"s")
        screen_until(child, lambda text: "status=done" in text, "the finished record")
        query_written(child, b"\x1b]7501;state=done", start, "the finished report")

        # Leave metadata applied so the exit has something to give back: a title, the terminal's
        # default colors, a progress value and a finished status that the protocol keeps.
        os.write(child.master, b"t")
        screen_until(child, lambda text: "title=set" in text, "the title before quitting")
        os.write(child.master, b"c")
        screen_until(child, lambda text: "fg=#e0e6f0" in text, "the default colors before quitting")
        os.write(child.master, b"g")
        screen_until(child, lambda text: "progress=normal:40" in text, "the progress before quitting")
        start = len(child.output)
        os.write(child.master, b"q")
        child.finish()

        restored = written_since(child, start)
        require(b"\x1b]9;4;0;0\x1b\\" in restored, "the progress value outlived the session")
        require(b"state=clear" not in bytes(child.output),
                "a finished status was cleared instead of being left for the user")
        for sequence in (b"\x1b]110\x1b\\", b"\x1b]111\x1b\\", b"\x1b]112\x1b\\",
                         b"\x1b[23;0t", b"\x1b[?1049l"):
            require(sequence in restored, f"the exit did not write {sequence!r}")
        require(bytes(child.output).rindex(b"\x1b[?1049l") > bytes(child.output).rindex(b"\x1b[23;0t"),
                "the alternate screen was left before the metadata was restored")
    print("PASS parity 20 terminal presentation, metadata wire forms, status lifecycle and restoration")


HANDOFF_TITLE = "braxton handoff helper"


def raw_attributes(child):
    """Whether the slave is in the session's raw configuration rather than the user's own."""
    attributes = termios.tcgetattr(child.slave)
    return attributes[3] & (termios.ECHO | termios.ICANON | termios.ISIG | termios.IEXTEN) == 0


def stopped(child, timeout=15):
    """Waits until the session's process group is stopped, and reports whether it is."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pid, status = os.waitpid(child.process.pid, os.WUNTRACED | os.WNOHANG)
        if pid == child.process.pid:
            require(os.WIFSTOPPED(status), f"the session exited instead of stopping: {status}")
            return True
        child.pump(0.05)
    return False


def check_process_handoff_parity(java_command):
    """Phase 21: the session hands its terminal to a child and to the job controller."""
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "handoffs=0 last=none exit=- suspend=ready" in text,
                     "the handoff status row")
        screen_until(child, lambda text: "draft=-" in field_row(child, HANDOFF_DRAFT_ROW),
                     "the empty draft row")
        screen_until(child, lambda text: "e run helper" in text, "the handoff help")
        require(raw_attributes(child), "the session opened without raw input")

        # A draft typed before the handoff must survive it.
        os.write(child.master, b"hi")
        screen_until(child, lambda text: "draft=hi" in field_row(child, HANDOFF_DRAFT_ROW),
                     "the typed draft")

        # The handoff gives the terminal back: the session leaves its screen, shows the cursor
        # again and restores the user's own input configuration, so the child reads a line the way
        # any program would.
        start = len(child.output)
        os.write(child.master, b"e")
        query_written(child, b"\x1b[?1049l", start, "the session giving the alternate screen back")
        require(b"\x1b[?25h" in written_since(child, start), "the cursor was not given back")
        query_written(child, HANDOFF_TITLE.encode(), start, "the owned helper starting")
        require(not raw_attributes(child),
                "the terminal was still in the session's raw configuration while the child owned it")
        screen_until(child, lambda text: "env=phase-21" in text, "the request's environment")
        screen_until(child, lambda text: "line> " in text, "the helper's prompt")

        # Cooked mode means the child sees the line only once it is complete, which is the whole
        # reason the session restores the configuration before handing over.
        os.write(child.master, b"hello\r")
        query_written(child, b"echo=hello", start, "the helper reading the terminal")
        query_written(child, b"edited=HELLO!", start, "the helper echoing the line")

        # The completion is delivered after the session took the terminal back and repainted.
        screen_until(child, lambda text: "handoffs=1 last=exited exit=0" in text,
                     "the handoff completion")
        require(raw_attributes(child), "the session did not take its raw configuration back")
        require(b"\x1b[?1049h" in written_since(child, start),
                "the session did not take the alternate screen back")
        # The child's exit invalidated the screen, so the frame is repainted rather than diffed.
        screen_until(child, lambda text: "draft=hi" in field_row(child, HANDOFF_DRAFT_ROW),
                     "the preserved draft")
        screen_until(child, lambda text: "braxton process handoff gestures=" in text,
                     "the repainted body row")

        # This owned PTY is the session's controlling terminal, but the process group is orphaned:
        # the verifier that started it is in another session, so the kernel discards its stop
        # signals. A suspend must say so and keep painting, rather than leaving a session nobody can
        # continue - the successful SIGTSTP/SIGCONT cycle needs a shell's own session and is proven
        # by the native job-control probe.
        start = len(child.output)
        os.write(child.master, b"\x1a")
        screen_until(child, lambda text: "suspend=failed" in text, "the refused suspend")
        require(b"\x1b[?1049l" in written_since(child, start),
                "the session did not give the terminal back for the suspend")
        require(b"\x1b[?1049h" in written_since(child, start),
                "the session did not take the terminal back when the suspend failed")
        require(raw_attributes(child), "the session did not take its raw configuration back")
        require(child.process.poll() is None, "a refused suspend ended the session")
        require(not stopped(child, timeout=0.2), "the session stopped for a suspend that cannot continue")
        screen_until(child, lambda text: "draft=hi" in field_row(child, HANDOFF_DRAFT_ROW),
                     "the preserved draft after the refused suspend")

        os.write(child.master, b"q")
        child.finish()
    print("PASS parity 21 terminal handoff, cooked child input, preserved draft and refused suspend")


def check_application_control_parity(java_command):
    """Phase 22: a sequence runs in order, a handle sends from outside and a guarded quit."""
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "sequence=idle order=none quit=guarded" in text,
                     "the idle status row")
        screen_until(child, lambda text: "prompt=none s sequence" in text, "the prompt row")
        screen_until(child, lambda text: "braxton application control producer=on" in text,
                     "the body row")
        require(raw_attributes(child), "the session opened without raw input")

        # The owned producer sends through the program handle, not through a key: the count climbs on
        # its own, which is the only way an outside event source is visible.
        deadline = time.monotonic() + 20
        while tick_count(child) < 3:
            require(time.monotonic() < deadline, "the owned producer's messages never arrived")
            require(child.process.poll() is None, "the session exited instead of counting progress")
            child.pump(0.1)

        # One sequence. Each step starts when the previous one finished, so the row it fills is an
        # order of execution rather than of submission.
        os.write(child.master, b"s")
        screen_until(child, lambda text: "sequence=done order=a,tick-a,b,b-done,c" in text,
                     "the sequence's execution order")
        ticks_after_sequence = tick_count(child)

        # q asks to quit and the guard declines it: the prompt appears and the session keeps running.
        os.write(child.master, b"q")
        screen_until(child, lambda text: "quit=confirm" in text, "the confirmation prompt")
        screen_until(child, lambda text: "prompt=confirm press y to quit" in text, "the prompt row")
        child.pump(0.3)
        require(child.process.poll() is None, "an unconfirmed quit ended the session")
        require(tick_count(child) >= ticks_after_sequence,
                "the session stopped counting progress after a declined quit")

        # y confirms; the quit is accepted and the session gives the terminal back.
        os.write(child.master, b"y")
        child.finish()
        transcript = bytes(child.output)
        require(b"\x1b[?1049l" in transcript, "the session did not leave the alternate screen")
        require(b"final progress=" in transcript and b"order=a,tick-a,b,b-done,c" in transcript,
                "the demo did not report the final model it read from the program handle")
        require(b"exit=QUIT/0" in transcript, "the demo did not report a clean quit")
    print("PASS parity 22 sequence execution order, external sends and a guarded quit")


def packaged_jar():
    """The single packaged Braxton jar, which is what a demo launch runs from."""
    jars = [p for p in (ROOT / "target").glob("braxton-*.jar")
            if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
    require(len(jars) == 1, "expected exactly one packaged Braxton jar")
    return jars[0]


def seeded(command, history="bash history line"):
    """Run a command behind one line of shell history, so the screen starts with content above it.

    The session must not be able to reach that line: it was printed by the shell before the program
    was started, exactly like the prompt output above a live view in a real shell.
    """
    inner = "printf '%s\\n' " + shlex.quote(history) + "; exec " + " ".join(shlex.quote(part) for part in command)
    return ["/bin/sh", "-c", inner]


def check_inline_io_parity(java_command):
    """Phase 23: a live inline region, durable lines above it, and a preserved screen above both."""
    history = "seeded shell history"
    with PtyChild(seeded(java_command, history), control=False) as child:
        screen_until(child, lambda text: "packages=6 installed=0 phase=idle mode=inline" in text,
                     "the live status row")
        screen_until(child, lambda text: "braxton inline output packages=6" in text,
                     "the scene's body row")
        require(raw_attributes(child), "the session opened without raw input")

        # One package: its durable line appears above the live region, which is repainted below it.
        os.write(child.master, b"n")
        screen_until(child, lambda text: "installed parser" in text, "the first durable line")
        screen_until(child, lambda text: "installed=1" in text, "the repainted live region")

        # The line above the region stays where the shell left it.
        require(history in "\n".join(child.screen.display),
                "the session cleared the shell's own history above its region")

        # A resize repaints the region and leaves the history and the durable lines alone.
        child.screen.resize(28, 60)
        child.resize(28, 60)
        time.sleep(0.4)
        os.write(child.master, b"n")
        screen_until(child, lambda text: "installed lexer" in text, "the second durable line")
        screen_until(child, lambda text: "installed=2" in text, "the resized live region")
        display = "\n".join(child.screen.display)
        require(history in display, "a resize erased the shell's own history")
        require("installed parser" in display, "a resize erased a durable line")

        # The rest of the installation runs on its own, one durable line per package.
        os.write(child.master, b"a")
        screen_until(child, lambda text: "installed examples" in text and "phase=done" in text,
                     "the finished installation")
        display = "\n".join(child.screen.display)
        require(history in display, "the finished installation erased the shell's own history")
        for name in ("parser", "lexer", "runtime", "renderer", "widgets", "examples"):
            require(f"installed {name}" in display, f"the durable line for {name} is missing")

        os.write(child.master, b"q")
        child.finish()
        transcript = bytes(child.output)
        require(b"\x1b[?1049h" not in transcript, "the inline session entered the alternate screen")
        require(b"\x1b[2J" not in transcript, "the inline session erased the whole display")
        require(re.search(rb"\x1b\[\d+;\d+H", transcript) is None,
                "an inline frame addressed a row absolutely")
        require(b"inline QUIT/0" in transcript, "the demo did not report a clean inline exit")
        text = transcript.decode("utf-8", errors="replace")
        require("installed examples" in "\n".join(child.screen.display),
                "the final frame did not stay on the terminal's own screen")

        # The final model line is printed below the region, on a line of its own.
        final_row = next(index for index, row in enumerate(child.screen.display)
                         if "inline QUIT/0" in row)
        live_row = next(index for index, row in enumerate(child.screen.display)
                        if "installed examples" in row)
        require(final_row > live_row, f"the final line was written inside the live region: {text[-200:]!r}")
    print("PASS parity 23 inline live region, durable lines above it and preserved history")


def check_inline_io_streams():
    """Phase 23: the demo's piped and headless launch modes, with no terminal and no native library."""
    jar = packaged_jar()
    java = shutil.which("java")
    command = [java, "-cp", str(jar), "examples.parity.InlineIoDemo"]

    headless = subprocess.run([*command, "--headless"], input=b"", capture_output=True, timeout=120, cwd=ROOT)
    require(headless.returncode == 0, f"headless exit {headless.returncode}: "
            f"{headless.stderr.decode('utf-8', errors='replace')[-500:]}")
    headless_text = headless.stdout.decode("utf-8", errors="replace")
    require("stream lines=3 text=alpha,beta,gamma finished=true" in headless_text,
            f"the headless run did not report its final model: {headless_text!r}")
    require("line=1 text=alpha" in headless_text, "the headless run printed no durable line")
    require("headless QUIT/0" in headless_text, "the headless run did not end cleanly")
    require("\x1b" not in headless_text, "the headless run wrote an escape sequence")

    piped = subprocess.run([*command, "--pipe"], input=b"one\ntwo\n", capture_output=True, timeout=120, cwd=ROOT)
    require(piped.returncode == 0, f"pipe exit {piped.returncode}: "
            f"{piped.stderr.decode('utf-8', errors='replace')[-500:]}")
    piped_text = piped.stdout.decode("utf-8", errors="replace")
    require("line=1 text=one" in piped_text and "line=2 text=two" in piped_text,
            f"piped stdin did not reach the model as data: {piped_text!r}")
    require("stream lines=2 text=one,two finished=true" in piped_text,
            f"the piped run did not report its final model: {piped_text!r}")
    require("pipe QUIT/0" in piped_text, "the piped run did not end cleanly")
    require("\x1b" not in piped_text, "the piped run wrote an escape sequence")
    print("PASS parity 23 headless and piped sessions without a terminal or native library")



def check_showcase_parity(java_command):
    """Phase 24: one catalog opens every phase demo, returns, and composes the workspace."""
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "catalog=27 open=none selected=01" in text,
                     "the catalog's status row")
        screen_until(child, lambda text: "braxton parity showcase" in text, "the catalog's heading")
        screen_until(child, lambda text: "01 Keymaps and help" in text, "the first entry")
        screen_until(child, lambda text: "\u2191/k up \u2022 \u2193/j down" in text,
                     "the catalog's compact help")

        # The catalog scrolls: the last entry is reachable, and it is the debug-overlay demo.
        os.write(child.master, b"\x1b[F")
        screen_until(child, lambda text: "31 Debug overlay" in text, "the last entry")
        screen_until(child, lambda text: "selected=31" in text, "the last entry selected")
        os.write(child.master, b"\x1b[H")
        screen_until(child, lambda text: "selected=01" in text, "the first entry selected again")

        # One phase demo opens as a mounted scene, paints its own rows, and returns on Ctrl+C.
        os.write(child.master, b"\x1b[B\x1b[B")
        screen_until(child, lambda text: "selected=03" in text, "the cursor phase selected")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "open=03 scene=Native and virtual cursors ctrl+c=back" in text,
                     "the mounted cursor scene")
        screen_until(child, lambda text: "strategy=native shape=block mode=blink" in text,
                     "the cursor scene's own status row")
        screen_until(child, lambda text: "ctrl+c back to catalog" in text, "the catalog's own help row")
        os.write(child.master, b"\x03")
        screen_until(child, lambda text: "catalog=27 open=none selected=03" in text,
                     "the catalog after returning")

        # The same return gesture works in selection widgets and the spinner.
        for phase in ("01", "07", "11", "13", "14"):
            os.write(child.master, b"\x1b[H" + b"\x1b[B" * (int(phase) - 1))
            screen_until(child, lambda text, phase=phase: f"selected={phase}" in text,
                         f"selection scene {phase} selected")
            os.write(child.master, b"\r")
            screen_until(child, lambda text, phase=phase: f"open={phase} scene=" in text,
                         f"selection scene {phase} mounted")
            os.write(child.master, b"\x03")
            screen_until(child, lambda text, phase=phase: f"catalog=27 open=none selected={phase}" in text,
                         f"Ctrl+C returning from selection scene {phase}")

        # Scenes with no focused widget must still own their shortcuts inside the catalog frame.
        # These keys exercise the mounted controls, rather than just proving each scene paints.
        for phase, key, result in (
                ("07", b" ", "last=stopped"),
                ("08", b"b", "fill=partial"),
                ("09", b"s", "state=running"),
                ("10", b"s", "last=start-left"),
                ("18", b"3", "profile=ansi256"),
                ("19", b"i", "internal=bytes=0")):
            os.write(child.master, b"\x1b[H" + b"\x1b[B" * (int(phase) - 1))
            screen_until(child, lambda text, phase=phase: f"selected={phase}" in text,
                         f"phase {phase} selected")
            os.write(child.master, b"\r")
            screen_until(child, lambda text, phase=phase: f"open={phase} scene=" in text,
                         f"phase {phase} mounted")
            os.write(child.master, key)
            screen_until(child, lambda text, result=result: result in text,
                         f"phase {phase} handled its shortcut")
            os.write(child.master, b"\x03")
            screen_until(child, lambda text, phase=phase: f"catalog=27 open=none selected={phase}" in text,
                         f"the catalog after phase {phase}")

        # Ctrl+C is the catalog's captured return gesture, even when the scene also binds quit.
        os.write(child.master, b"\x1b[H")
        screen_until(child, lambda text: "selected=01" in text, "the first entry selected")
        os.write(child.master, b"\x1b[B" * 18)
        screen_until(child, lambda text: "selected=19" in text, "the clipboard phase selected")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "open=19 scene=Terminal and primary clipboard" in text,
                     "the mounted clipboard scene")
        screen_until(child, lambda text: "copy=idle" in text, "the clipboard scene's own status row")
        os.write(child.master, b"\x03")
        screen_until(child, lambda text: "catalog=27 open=none selected=19" in text,
                     "the catalog after the mounted scene asked to quit")

        # The composed workspace: files on the left, a projection on the right, tasks on page two.
        os.write(child.master, b"\x1b[B" * 5)
        screen_until(child, lambda text: "selected=24" in text, "the workspace selected")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "open=24 scene=Composed task workspace" in text,
                     "the mounted workspace")
        screen_until(child, lambda text: "page=preview focus=files source=-" in text,
                     "the workspace's preview page")
        screen_until(child, lambda text: "tasks=3 done=1" in text, "the workspace's task row")

        # Choosing a file moves the projection, and the find field publishes its matches. The
        # listing is the owned fixture, read by the picker's own task: waiting for a name is what
        # keeps the keys below from racing that read. Directories first, then archive.bin, so two
        # rows below the first entry is the first markdown file.
        screen_until(child, lambda text: "guide.md" in text and "notes.md" in text,
                     "the picker's listing")
        os.write(child.master, b"\x1b[B\x1b[B\r")
        screen_until(child, lambda text: "source=guide.md lines=" in text, "the chosen document")
        screen_until(child, lambda text: "guide.md" in text and "Lantern care" in text,
                     "the chosen document's projection")
        # The chosen document leaves the keyboard on the preview; one tab reaches the find field.
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=find" in text, "the find field's focus")
        os.write(child.master, b"lantern")
        screen_until(child, lambda text: "query=lantern matches=3 active=1" in text,
                     "the find field's matches")

        # The task page: the table's own choose output drives the progress bar.
        os.write(child.master, b"\x1b2")
        screen_until(child, lambda text: "page=tasks focus=table" in text, "the task page")
        screen_until(child, lambda text: "done=1 target=0.33" in text, "the settled progress target")
        os.write(child.master, b"\x1b[B\r")
        screen_until(child, lambda text: "done=2 target=0.67" in text, "the toggled task")

        # The confirmation is modal, and confirming it lands the bar and the workspace.
        os.write(child.master, b"\x13")
        screen_until(child, lambda text: "dialog=open" in text, "the confirmation dialog")
        screen_until(child, lambda text: "Apply the workspace changes?" in text, "the dialog's question")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "dialog=closed" in text and "synced=1" in text,
                     "the applied confirmation")
        screen_until(child, lambda text: "target=1.00" in text, "the landed progress bar")

        # The catalog escape must also work while the modal confirmation owns focus.
        os.write(child.master, b"\x13")
        screen_until(child, lambda text: "dialog=open" in text, "the confirmation reopened")
        os.write(child.master, b"\x03")
        screen_until(child, lambda text: "catalog=27 open=none selected=24" in text,
                     "Ctrl+C returning from the workspace modal")

        # The mouse demo nests its modal list under layout and observer wrappers.
        os.write(child.master, b"\x1b[H" + b"\x1b[B" * 16)
        screen_until(child, lambda text: "selected=17" in text, "the mouse scene selected")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "open=17 scene=" in text, "the mouse scene mounted")
        os.write(child.master, b"\x1bm")
        screen_until(child, lambda text: "dialog=open" in text, "the modal selection opened")
        os.write(child.master, b"\x03")
        screen_until(child, lambda text: "catalog=27 open=none selected=17" in text,
                     "Ctrl+C returning from the modal selection")

        # Reopening the workspace after a modal return keeps Ctrl+C working.
        os.write(child.master, b"\x1b[B" * 7 + b"\r")
        screen_until(child, lambda text: "open=24 scene=" in text, "the workspace reopened")
        os.write(child.master, b"\x13")
        screen_until(child, lambda text: "dialog=open" in text, "the confirmation opened again")
        os.write(child.master, b"\x03")
        screen_until(child, lambda text: "catalog=27 open=none selected=24" in text,
                     "Ctrl+C returning from the workspace modal")

        # A resize proves the catalog still owns its window after the modal unmount.
        child.screen.resize(30, 100)
        child.resize(30, 100)
        screen_until(child, lambda text: "catalog=27 open=none selected=24" in text
                     and "24 Composed task workspace" in text,
                     "the resized catalog")
        os.write(child.master, b"\x1b[F\r")
        screen_until(child, lambda text: "open=31 scene=Debug overlay" in text and "DEBUG bounds=" in text,
                     "the mounted diagnostics scene")
        os.write(child.master, b"\x03")
        screen_until(child, lambda text: "catalog=27 open=none selected=31" in text and "DEBUG bounds=" not in text,
                     "the catalog after diagnostics cleanup")
        os.write(child.master, b"q")
        child.finish()
    print("PASS parity 24 catalog mount/return, composed workspace and modal confirmation")


def check_element_kernel_parity(java_command):
    """Phase 27: an application's own widget routes keys, clicks and its caret in a real terminal."""
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "kernel widget=painted cells=4/" in text, "the status row")
        screen_until(child, lambda text: "focus=meter" in text, "the meter's focus")
        # The application's own binding still answers after the meter declined the gesture.
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=scene" in text, "the focus the application moved")
        os.write(child.master, b"\t")
        # The meter answers an arrow key itself: it owns focus and emits the fill it decided.
        os.write(child.master, b"\x1b[C")
        screen_until(child, lambda text: "cells=5/" in text, "the cell the meter added")
        screen_until(child, lambda text: "reports=1" in text, "the report the meter counted")
        # A click four cells into the widget's own row fills the bar to the cell it landed on. The
        # scene also spells the bar out on a status row, so the widget's row is the one inside the
        # frame: it is the last row holding filled cells.
        bar_row = max(row for row, line in enumerate(child.screen.display) if "\u2588" in line)
        bar_column = child.screen.display[bar_row].index("\u2588")
        os.write(child.master, mouse_press(bar_row, bar_column + 6))
        os.write(child.master, mouse_release(bar_row, bar_column + 6))
        screen_until(child, lambda text: "reports=2" in text, "the click the meter counted")
        screen_until(child, lambda text: "cells=7/" in text, "the fill the click set")
        os.write(child.master, b"q")
        child.finish()
    print("PASS parity 27 an application's own widget on the public kernel")


def check_shared_engines_parity(java_command):
    """Phase 26: the shared engines are observable in a real terminal."""
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "engine text: alpha" in text, "the buffer row")
        screen_until(child, lambda text: "engine selection: 4" in text, "the selection row")
        screen_until(child, lambda text: "engine scroll: 0" in text, "the virtual scroll row")
        os.write(child.master, b"q")
        child.finish()
    print("PASS parity 26 shared text, selection and virtual scroll engines")


def check_terminal_matrix_parity(java_command):
    """Phase 25: capability facts and a full repaint after an external stop and continue."""
    status = "term=xterm-256color caps=1/1/1/1 size=24x80"
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: status in text, "the capability status row")
        screen_until(child, lambda text: "Braxton terminal matrix" in text, "the matrix heading")

        os.killpg(child.process.pid, signal.SIGTSTP)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            attributes = termios.tcgetattr(child.slave)
            if attributes[3] & termios.ICANON:
                break
            time.sleep(0.02)
        else:
            raise AssertionError("external SIGTSTP did not restore cooked input before stopping")
        os.killpg(child.process.pid, signal.SIGCONT)

        # The native continue path leaves the alternate screen and replays the entry sequence.
        # Clearing pyte here means the status row can only reappear from a fresh full repaint.
        child.screen.reset()
        screen_until(child, lambda text: status in text, "the status row after SIGCONT")
        os.write(child.master, b"q")
        child.finish()
    print("PASS parity 25 terminal capability matrix and external stop/continue repaint")


def check_line_prompts_parity(java_command):
    """Phase 29: the four line prompts, answered with real keys on a real terminal."""
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "Braxton line prompts" in text, "the demo's heading")
        screen_until(child, lambda text: "Overwrite the report? [y/N]" in text, "the confirmation")

        # A confirmation is a line: "y" alone is not an answer until Enter arrives.
        os.write(child.master, b"y")
        child.pump(0.2)
        require("confirm=" not in "\n".join(child.screen.display),
                "the confirmation answered before Enter")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "confirm=yes" in text, "the confirmed answer")

        # The choice: down moves the highlight, Enter chooses the second theme.
        screen_until(child, lambda text: "Pick a theme" in text, "the select prompt")
        screen_until(child, lambda text: "\u276F dark" in text, "the highlighted first theme")
        os.write(child.master, b"\x1b[B")
        screen_until(child, lambda text: "\u276F light" in text, "the highlight moved one option on")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "theme=light" in text, "the chosen theme")

        # The marks: space marks, down moves, space marks again, Enter confirms both.
        screen_until(child, lambda text: "Build which targets" in text, "the multiple-choice prompt")
        os.write(child.master, b" ")
        screen_until(child, lambda text: "[\u2713] linux-x86_64" in text, "the first mark")
        os.write(child.master, b"\x1b[B ")
        screen_until(child, lambda text: "[\u2713] linux-aarch64" in text, "the second mark")
        os.write(child.master, b"\r")
        screen_until(child, lambda text: "targets=linux-x86_64,linux-aarch64" in text, "the marked targets")

        # The progress count paints its bar in the same region, in place, and leaves it on screen.
        screen_until(child, lambda text: "Installing packages" in text, "the progress bar")
        screen_until(child, lambda text: "prompts confirm=yes theme=light targets=2/4 progress=6/6" in text,
                     "the status line")
        screen_until(child, lambda text: "6/6" in text and "100%" in text, "the completed progress row")
        # Nothing the program printed before the first prompt was touched, and the whole dialog ran
        # on the terminal's own screen.
        screen_until(child, lambda text: "Braxton line prompts" in text, "the heading still on screen")
        require("\x1b[?1049h" not in child.output.decode("utf-8", errors="replace"),
                "a line-oriented dialog must not take the alternate screen")

        child.finish()
    print("PASS parity 29 line prompts: confirm, select, multi-select and progress")


def check_widget_gallery_parity(java_command):
    """Phase 28: every milestone-4 widget on one screen, on four pages, driven by real keys."""
    with PtyChild(java_command, control=False) as child:
        screen_until(child,
                     lambda text: "gallery page=1/4 choice widgets=5 names=select,tags,remember,density,form" in text,
                     "the gallery's status row")
        screen_until(child, lambda text: "braxton widget gallery" in text, "the gallery's title")
        screen_until(child, lambda text: "select=standard tags=design" in text,
                     "the choice page's published state")
        screen_until(child, lambda text: "name: Ada" in text, "the form's own editors")

        # The focused select takes the arrow; the scene's own map never sees it.
        os.write(child.master, b"\x1b[B")
        screen_until(child, lambda text: "select=rich" in text and "last=select-chosen" in text,
                     "the select's own choice")
        # Tab moves the keyboard between specimens: two steps reach the checkbox.
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=tags" in text, "the multi-select focused")
        os.write(child.master, b"\t")
        screen_until(child, lambda text: "focus=remember" in text, "the checkbox focused")
        os.write(child.master, b" ")
        screen_until(child, lambda text: "remember=on" in text and "last=remember-changed" in text,
                     "the checkbox flipped")
        # The form's submission is the scene's gesture, and the seeded address is rejected.
        os.write(child.master, b"\x13")
        screen_until(child, lambda text: "last=form-rejected" in text, "the rejected submission")
        screen_until(child, lambda text: "an address needs an @" in text, "the form's error row")

        # The message surfaces: notifications, the log's tail, and a toast that owns no keys.
        os.write(child.master, b"\x1b3")
        screen_until(child, lambda text: "gallery page=3/4 message widgets=2 names=notices,log" in text,
                     "the message page")
        screen_until(child, lambda text: "gallery line 20" in text, "the log's tail")
        os.write(child.master, b"\x1b[B")
        screen_until(child, lambda text: "notices=2/3 unread=2" in text, "the notification read")
        os.write(child.master, b"\x07")
        screen_until(child, lambda text: "toast=shown" in text and "last=toast-shown" in text,
                     "the toast shown")

        # The data views: three readers and a date picker.
        os.write(child.master, b"\x1b4")
        screen_until(child, lambda text: "gallery page=4/4 data widgets=4 names=line,bars,spark,calendar" in text,
                     "the data page")
        screen_until(child, lambda text: "March 2026" in text, "the calendar's month")
        os.write(child.master, b"\x1b[D")
        screen_until(child, lambda text: "line=7/8" in text, "the line chart's reader stepped back")

        # The navigation widgets: a tree, a scrollbar, a split, a menu and a palette.
        os.write(child.master, b"\x1b2")
        screen_until(child, lambda text: "gallery page=2/4 navigate widgets=5" in text,
                     "the navigation page")
        screen_until(child, lambda text: "Switch theme" in text, "the command palette")
        os.write(child.master, b"\x1b[B")
        screen_until(child, lambda text: "tree=ui.element" in text, "the tree's own selection")

        # The dialog is a modal layer over the page, and its own gesture closes it.
        os.write(child.master, b"\x1b1")
        screen_until(child, lambda text: "gallery page=1/4 choice widgets=5" in text,
                     "the choice page again")
        os.write(child.master, b"\x04")
        screen_until(child, lambda text: "apply the gallery change?" in text, "the dialog")
        os.write(child.master, b"\x1b")
        screen_until(child, lambda text: "last=dialog-canceled" in text and "focus=select" in text,
                     "the dialog dismissed and the keyboard returned")

        os.write(child.master, b"q")
        child.finish()
    print("PASS parity 28 widget gallery: every milestone-4 widget on four pages")


def check_rich_output_parity(java_command):
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "motion=normal value=0 target=0 state=settled moves=0" in text,
                     "the initial rich-output state")
        screen_until(child, lambda text: "image fallback=half-block" in text, "the composed image")
        require(any("\u2580" in row for row in child.screen.display), "the fallback painted no pixels")
        os.write(child.master, b" ")
        screen_until(child, lambda text: "value=100 target=100 state=settled moves=1" in text,
                     "the completed transition")
        os.write(child.master, b"m")
        screen_until(child, lambda text: "motion=reduced value=100" in text, "reduced motion enabled")
        os.write(child.master, b" ")
        screen_until(child, lambda text: "motion=reduced value=0 target=0 state=settled moves=2" in text,
                     "an immediate reduced-motion transition")
        screen_until(child, lambda text: "frame=a" in text, "the stable reduced-motion frame")
        os.write(child.master, b"m ")
        screen_until(child, lambda text: "motion=normal value=100 target=100 state=settled moves=3" in text,
                     "normal motion restored")
        child.screen.resize(12, 32)
        child.resize(12, 32)
        screen_until(child, lambda text: "Braxton rich output" in text and "fallback=half-block" in text,
                     "the narrow rich-output view")
        os.write(child.master, b"q")
        child.finish()
    print("PASS parity 30 transitions, reduced motion and composed image fallback")


def check_debug_overlay_parity(java_command):
    with PtyChild(java_command, control=False) as child:
        screen_until(child, lambda text: "debug overlay=on focus=left left=off right=off changes=0" in text,
                     "the initial diagnostics scene")
        screen_until(child, lambda text: "DEBUG bounds=" in text and "frame=" in text and "focus=root/2" in text,
                     "actual layout, timing and focused path")
        os.write(child.master, b" ")
        screen_until(child, lambda text: "left=on right=off changes=1" in text and "msg=Child" in text,
                     "the focused widget receiving space under the overlay")
        os.write(child.master, b"\t ")
        screen_until(child, lambda text: "focus=right left=on right=on changes=2" in text and "focus=root/3" in text,
                     "focus movement and the second widget")
        os.write(child.master, b"d")
        screen_until(child, lambda text: "debug overlay=off" in text and "DEBUG bounds=" not in text,
                     "diagnostics disabled without stale text")
        os.write(child.master, b" ")
        screen_until(child, lambda text: "left=on right=off changes=3" in text, "input with diagnostics off")
        os.write(child.master, b"d")
        screen_until(child, lambda text: "debug overlay=on" in text and "DEBUG bounds=" in text and "msg=(none)" in text,
                     "a fresh diagnostics history")
        child.screen.resize(16, 64)
        child.resize(16, 64)
        screen_until(child, lambda text: "bounds 0,0 16x64" in text and "focus=root/3" in text,
                     "actual resized geometry")
        os.write(child.master, b"q")
        child.finish()
    print("PASS parity 31 diagnostic bounds/focus/messages/timing, toggles and preserved input")


PARITY_SCENARIOS = {
    "01": check_keymap_help_parity,
    "02": check_keyboard_focus_parity,
    "03": check_cursor_parity,
    "04": check_editor_parity,
    "05": check_text_input_parity,
    "06": check_paginator_parity,
    "07": check_spinner_parity,
    "08": check_progress_parity,
    "09": check_countdown_parity,
    "10": check_stopwatch_parity,
    "11": check_rich_list_parity,
    "12": check_viewport_parity,
    "13": check_table_parity,
    "14": check_file_picker_parity,
    "15": check_markdown_parity,
    "16": check_tabs_parity,
    "17": check_mouse_parity,
    "18": check_terminal_query_parity,
    "19": check_clipboard_parity,
    "20": check_presentation_parity,
    "21": check_process_handoff_parity,
    "22": check_application_control_parity,
    "23": check_inline_io_parity,
    "24": check_showcase_parity,
    "25": check_terminal_matrix_parity,
    "26": check_shared_engines_parity,
    "27": check_element_kernel_parity,
    "28": check_widget_gallery_parity,
    "29": check_line_prompts_parity,
    "30": check_rich_output_parity,
    "31": check_debug_overlay_parity,
}


def core_classpath(jar):
    """The packaged jar plus the library's own runtime dependencies, without an example-only one."""
    entries = [str(jar)]
    for entry in (ROOT / "target/terminal-cp.txt").read_text().strip().split(os.pathsep):
        if entry and not any(Path(entry).name.startswith(name) for name in EXAMPLE_ONLY_JARS):
            entries.append(entry)
    return os.pathsep.join(entries)


# Phases whose scenario is more than one launch: the PTY scene, plus sessions that need no terminal.
PARITY_EXTRA_CHECKS = {"23": check_inline_io_streams}


def check_parity(phase, java_command):
    """Dispatch one implemented parity phase to its pyte assertions."""
    PARITY_SCENARIOS[phase](java_command)
    extra = PARITY_EXTRA_CHECKS.get(phase)
    if extra is not None:
        extra()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--harness", action="store_true", help="also smoke-test the packaged AI Harness")
    parser.add_argument("--pending-escape", action="store_true", help="run the strict pending-Escape/immediate-reopen reproducer")
    parser.add_argument("--repeat", type=int, default=1, help="repeat idle-close/reopen cases")
    parser.add_argument("--parity-phase", metavar="NN",
                        help="run only this implemented parity phase's scenario and packaged demo")
    parser.add_argument("--parity-all", action="store_true",
                        help="also run every implemented parity phase's scenario and packaged demo")
    args = parser.parse_args()
    require(1 <= args.repeat <= 100, "repeat must be between 1 and 100")
    require(platform.system() in ("Darwin", "Linux"), "PTY verification supports Darwin/Linux")
    require(any((ROOT / "target").glob("braxton-*.jar")), "run ./build.sh first")
    java = shutil.which("java")
    javac = shutil.which("javac")
    require(java and javac, "Java 25 JDK required")
    print(f"Host: {platform.platform()}; Python {platform.python_version()}")
    subprocess.run([java, "-version"], check=True)
    with tempfile.TemporaryDirectory(prefix="braxton-terminal-") as directory:
        work = Path(directory)
        subprocess.run([str(ROOT / "mvnw"), "-B", "-q", "dependency:build-classpath", "-DincludeScope=runtime",
                        "-Dmdep.outputFile=target/terminal-cp.txt"], check=True, cwd=ROOT)
        jars = [p for p in (ROOT / "target").glob("braxton-*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
        require(len(jars) == 1, "expected exactly one packaged Braxton jar")
        classpath = os.pathsep.join((str(jars[0]), (ROOT / "target/terminal-cp.txt").read_text().strip()))
        subprocess.run([javac, "-Xlint:all", "-cp", classpath, "-d", str(work),
                        str(ROOT / "tools/terminal/TerminalProbe.java"),
                        str(ROOT / "tools/terminal/ScrollViewProbe.java"),
                        str(ROOT / "tools/terminal/ImageOutputProbe.java"),
                        str(ROOT / "tools/terminal/ParityProbe.java")], check=True)
        java_command = [java, "--enable-native-access=ALL-UNNAMED", "-cp",
                        os.pathsep.join((str(work), classpath)), PROBE]
        scroll_command = [java, "--enable-native-access=ALL-UNNAMED", "-cp",
                          os.pathsep.join((str(work), classpath)), SCROLL_PROBE]
        for extra, expected in (([], "--enable-native-access"), (["--enable-native-access=ALL-UNNAMED"], "controlling terminal")):
            result = subprocess.run([java, *extra, "-cp", os.pathsep.join((str(work),classpath)), PROBE, "--bootstrap"],
                                    input=b"", capture_output=True, timeout=30, cwd=ROOT)
            require(result.returncode == 1 and expected in result.stderr.decode(),
                    "startup must fail clearly without native access or an attached terminal: " + result.stderr.decode())
        print("PASS missing native-access/no-TTY fail without fallback")
        cases = []
        if args.parity_phase is not None:
            # One implemented phase: its own PTY scenario plus the packaged demo launch, which is
            # the focused gate that phase records. The full battery below stays the general gate.
            phase = args.parity_phase.zfill(2)
            require(phase in PARITY_SCENARIOS and phase in PARITY_DEMOS,
                    f"parity phase {args.parity_phase} is not implemented; "
                    f"implemented phases: {', '.join(sorted(PARITY_SCENARIOS))}")
            parity_command = [java, "--enable-native-access=ALL-UNNAMED", "-cp",
                              os.pathsep.join((str(work), classpath)), PARITY_PROBE, phase]
            # The demo runs from the packaged jar alone: no example dependency is needed.
            demo_class, demo_markers = PARITY_DEMOS[phase]
            # A demo that exercises a runtime dependency is launched with the jar and that
            # dependency; every other demo is launched from the jar alone.
            demo_classpath = (
                    core_classpath(jars[0]) if phase in RUNTIME_CLASSPATH_PHASES else str(jars[0]))
            demo_command = [java, "--enable-native-access=ALL-UNNAMED", "-cp", demo_classpath, demo_class]
            cases.append((f"parity phase {phase} scene", lambda: check_parity(phase, parity_command)))
            demo_keys = PARITY_DEMO_QUIT.get(phase, (b"\x03",))
            cases.append(("packaged parity demo",
                          lambda demo_command=demo_command, demo_markers=demo_markers, demo_keys=demo_keys:
                          check_packaged_demo(demo_command, demo_markers, demo_keys)))
        else:
            for attempt in range(args.repeat):
                if args.pending_escape:
                    cases.append((f"pending Escape / immediate reopen {attempt+1}", lambda: check_pending_escape(java_command)))
                cases.append((f"normal modes / reopen {attempt+1}", lambda: check_session(java_command, False)))
                cases.append((f"plain modes / reopen {attempt+1}", lambda: check_session(java_command, True)))
            cases.append(("rapid shrink/grow", lambda: check_rapid_resize(java_command)))
            for plain in (False, True):
                cases.append((f"refresh plain={plain}", lambda plain=plain: check_refresh(java_command, plain)))
                cases.append((f"refresh-wide plain={plain}", lambda plain=plain: check_refresh_wide(java_command, plain)))
            for command, code in (("EXCEPTION", 1), ("SHUTDOWN", 0), ("SIGTERM", 143)):
                cases.append((command, lambda command=command, code=code: check_cleanup(java_command, command, code)))
            cases.append(("scroll view panes", lambda: check_scrollview(scroll_command)))
            image_command = [java, "--enable-native-access=ALL-UNNAMED", "-cp",
                             os.pathsep.join((str(work), classpath)), "io.github.dhoard.braxton.tools.ImageOutputProbe"]
            cases.append(("image output", lambda: check_image_output(image_command)))
            if args.harness:
                cases.append(("packaged harness", check_harness))
            if args.parity_all:
                # The integrated gate: every phase's own scenario and its packaged launch, run
                # together. Each phase is already a passing scenario on its own; this is what proves
                # they still pass in one invocation.
                for phase in sorted(PARITY_SCENARIOS):
                    parity_scene = [java, "--enable-native-access=ALL-UNNAMED", "-cp",
                                    os.pathsep.join((str(work), classpath)), PARITY_PROBE, phase]
                    demo_class, demo_markers = PARITY_DEMOS[phase]
                    demo_classpath = (core_classpath(jars[0]) if phase in RUNTIME_CLASSPATH_PHASES
                                      else str(jars[0]))
                    demo_launch = [java, "--enable-native-access=ALL-UNNAMED", "-cp", demo_classpath, demo_class]
                    demo_keys = PARITY_DEMO_QUIT.get(phase, (b"\x03",))
                    cases.append((f"parity phase {phase} scene",
                                  lambda phase=phase, parity_scene=parity_scene:
                                  check_parity(phase, parity_scene)))
                    cases.append((f"parity phase {phase} packaged demo",
                                  lambda demo_launch=demo_launch, demo_markers=demo_markers, demo_keys=demo_keys:
                                  check_packaged_demo(demo_launch, demo_markers, demo_keys)))
        failures = []
        for label, case in cases:
            try:
                case()
            except Exception as failure:
                failures.append(f"{label}: {failure}")
                print(f"FAIL {label}: {failure}", flush=True)
        require(not failures, "terminal verification failed: " + "; ".join(failures))



if __name__ == "__main__":
    main()

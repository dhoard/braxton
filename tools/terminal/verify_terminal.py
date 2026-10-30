#!/usr/bin/env python3
"""Opt-in JLine FFM checks. Requires Java 25, a packaged Braxton jar, and pyte.

Uses owned PTYs and a private Unix-domain control socket, never the caller's terminal.
No native binary or Python dependency is added to the distributed library.
"""

import argparse
import fcntl
import os
from pathlib import Path
import platform
import selectors
import shutil
import signal
import socket
import struct
import subprocess
import tempfile
import termios
import time
import xml.etree.ElementTree as ET

import pyte


ROOT = Path(__file__).resolve().parents[2]
PROBE = "io.github.dhoard.braxton.internal.runtime.TerminalProbe"
JLINE_IDENTITY = "ffm " + ET.parse(ROOT / "pom.xml").findtext(
    "m:properties/m:jline.version", namespaces={"m": "http://maven.apache.org/POM/4.0.0"})


def require(condition, message):
    if not condition:
        raise AssertionError(message)


class PtyChild:
    def __init__(self, command, *, control=True):
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

    def finish(self, expected_code=0):
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
        require(b"\x1b[?25h" in self.output, "cursor was not restored")


def check_session(java_command, plain):
    with PtyChild([*java_command, *(["--plain"] if plain else [])]) as child:
        expected_platform = JLINE_IDENTITY
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


def check_refresh(java_command, plain):
    with PtyChild([*java_command, *(["--plain"] if plain else [])]) as child:
        child.expect("READY " + JLINE_IDENTITY)
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
        require(frame.index("short界".encode()) < frame.index(b"\x1b[1;8H\x1b[0m\x1b[0K"),
                "suffix erased before overwrite or at wrong column")
        # Growing again must restore the full row, including both halves of the wide glyph.
        start = len(child.output)
        child.command("REFRESH")
        child.expect("REFRESH_READY")
        frame = wait_frame(child, start)
        require(child.screen.display[0].startswith("REFRESH_FULL"), "regrown row missing")
        require(child.screen.buffer[0][78].data == "界", "regrown right-edge wide glyph wrong")
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
        child.expect("REOPENED " + JLINE_IDENTITY)
        os.write(child.master, b"z")
        child.expect("KEY z 7a")
        child.finish()
    print(f"PASS synchronized overwrite-first/wide-edge/background/suffix/idle refresh (plain={plain})")


def check_refresh_wide(java_command, plain):
    """A wide glyph at the right edge must survive a full row and split cleanly when narrowed."""
    with PtyChild([*java_command, *(["--plain"] if plain else [])]) as child:
        child.expect("READY " + JLINE_IDENTITY)
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
        require(erase in frame, "suffix erase missing or at the wrong column")
        require(frame.index(payload) < frame.index(erase),
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
        first_row = frame.split(b"\x1b[2;1H", 1)[0]
        require(b"\x1b[0K" not in first_row, "regrown full wide row was cleared")
        child.command("CLOSE")
        child.expect("REOPENED " + JLINE_IDENTITY)
        os.write(child.master, b"z")
        child.expect("KEY z 7a")
        child.finish()
    print(f"PASS synchronized wide-edge survival/split/regrow refresh (plain={plain})")


def check_pending_escape(java_command):
    """Strict reproducer for the published provider's pending-poll/reopen race."""
    with PtyChild(java_command) as child:
        child.expect("READY " + JLINE_IDENTITY)
        child.command("CLOSE_IDLE")
        child.expect("WAITING")
        os.write(child.master, b"\x1b")
        time.sleep(0.01)  # Stop inside the 30 ms Escape disambiguation window.
        child.command("CLOSE")
        child.expect("REOPENED " + JLINE_IDENTITY)
        os.write(child.master, b"z")
        child.expect("KEY z 7a")
        child.finish()
    print("PASS pending-Escape stop/immediate reopen")


def check_cleanup(java_command, command, expected_code):
    with PtyChild(java_command) as child:
        child.expect("READY " + JLINE_IDENTITY)
        if command == "SIGTERM":
            child.process.send_signal(signal.SIGTERM)
        else:
            child.command(command)
        child.finish(expected_code)
        for mode in (b"\x1b[?1049l", b"\x1b[?2004l", b"\x1b[?1000l", b"\x1b[?1006l"):
            require(mode in child.output, f"mode not restored after {command}: {mode!r}")
    print(f"PASS native restoration after {command}")


def check_harness():
    with PtyChild([str(ROOT / "run-ai-harness.sh"), "--no-build"], control=False) as child:
        def screen_until(predicate, description):
            deadline = time.monotonic() + 30
            while not predicate("\n".join(child.screen.display)):
                require(time.monotonic() < deadline, f"harness screen never showed {description}")
                require(child.process.poll() is None, f"harness exited before {description}")
                child.pump(0.1)

        child.terminal_contains("\x1b[?2004h")
        screen_until(lambda text: "Yes" in text and "deepseek" in text, "loaded session")
        os.write(child.master, b"hierarchy probe")
        screen_until(lambda text: "> hierarchy probe" in text, "mounted editor input")
        os.write(child.master, b"\x1bc")  # Alt+C: copy the editor output through the parent.
        screen_until(lambda text: "Copied" in text, "clipboard output")
        os.write(child.master, b"\x16")  # Ctrl+V: session-local clipboard, no external clipboard read.
        screen_until(lambda text: "> hierarchy probehierarchy probe" in text, "paste dispatch")
        os.write(child.master, b"\r")
        screen_until(lambda text: "Working" in text, "submitted workflow")
        screen_until(lambda text: "I'll start by reading" in text, "timer-driven response")
        screen_until(lambda text: "\u2503" in text, "overflow scrollbar")
        # Stop response pacing before comparing scroll positions; no task races the assertion.
        os.write(child.master, b"\x1b")
        screen_until(lambda text: "esc again to stop" in text, "cancel confirmation")
        os.write(child.master, b"\x1b")
        screen_until(lambda text: "Stopped." in text and "Working" not in text, "cancelled workflow")
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
        screen_until(lambda text: "Press Ctrl+C again" in text, "exit confirmation")
        os.write(child.master, b"\x03")
        child.finish()
    print("PASS packaged hierarchical AI Harness editor/output/paste/submit/timer/scroll/resize/cancel/quit/restoration")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--harness", action="store_true", help="also smoke-test the packaged AI Harness")
    parser.add_argument("--pending-escape", action="store_true", help="run the strict pending-Escape/immediate-reopen reproducer")
    parser.add_argument("--repeat", type=int, default=1, help="repeat idle-close/reopen cases")
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
                        str(ROOT / "tools/terminal/TerminalProbe.java")], check=True)
        java_command = [java, "--enable-native-access=ALL-UNNAMED", "-cp",
                        os.pathsep.join((str(work), classpath)), PROBE]
        for extra, expected in (([], "--enable-native-access"), (["--enable-native-access=ALL-UNNAMED"], "attached process terminal")):
            result = subprocess.run([java, *extra, "-cp", os.pathsep.join((str(work),classpath)), PROBE, "--bootstrap"],
                                    input=b"", capture_output=True, timeout=30, cwd=ROOT)
            require(result.returncode == 1 and expected in result.stderr.decode(),
                    "startup must fail clearly without native access or an attached terminal: " + result.stderr.decode())
        print("PASS missing native-access/no-TTY fail without fallback")
        cases = []
        for attempt in range(args.repeat):
            if args.pending_escape:
                cases.append((f"pending Escape / immediate reopen {attempt+1}", lambda: check_pending_escape(java_command)))
            cases.append((f"normal modes / reopen {attempt+1}", lambda: check_session(java_command, False)))
            cases.append((f"plain modes / reopen {attempt+1}", lambda: check_session(java_command, True)))
        for plain in (False, True):
            cases.append((f"refresh plain={plain}", lambda plain=plain: check_refresh(java_command, plain)))
            cases.append((f"refresh-wide plain={plain}", lambda plain=plain: check_refresh_wide(java_command, plain)))
        for command, code in (("EXCEPTION", 1), ("SHUTDOWN", 0), ("SIGTERM", 143)):
            cases.append((command, lambda command=command, code=code: check_cleanup(java_command, command, code)))
        if args.harness:
            cases.append(("packaged harness", check_harness))
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

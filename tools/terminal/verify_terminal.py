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
        child.terminal_contains("\x1b[?2004h")
        os.write(child.master, b"\x03")
        child.terminal_contains("Press Ctrl+C again")
        os.write(child.master, b"\x03")
        child.finish()
    print("PASS packaged AI Harness startup/quit/restoration")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--harness", action="store_true", help="also smoke-test the packaged AI Harness")
    parser.add_argument("--pending-escape", action="store_true", help="run the strict pending-Escape/immediate-reopen reproducer")
    parser.add_argument("--repeat", type=int, default=1, help="repeat idle-close/reopen cases")
    args = parser.parse_args()
    require(1 <= args.repeat <= 100, "repeat must be between 1 and 100")
    require(platform.system() in ("Darwin", "Linux"), "PTY verification supports Darwin/Linux")
    require((ROOT / "target/braxton-0.1.0-SNAPSHOT.jar").is_file(), "run ./mvnw clean install first")
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

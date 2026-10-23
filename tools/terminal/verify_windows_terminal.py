#!/usr/bin/env python3
"""Developer-only JLine FFM verification using an owned Windows ConPTY.

Requires Windows 10 1809+, Java 25, Python with pyte, and a packaged Braxton jar.
No native calls here are distributed with Braxton. Never uses the caller's console.
"""
import argparse
import ctypes as C
from ctypes import wintypes as W
import json
import os
from pathlib import Path
import queue
import select
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PROBE = "io.github.dhoard.braxton.internal.runtime.TerminalProbe"
JLINE_IDENTITY = "ffm " + ET.parse(ROOT / "pom.xml").findtext(
    "m:properties/m:jline.version", namespaces={"m": "http://maven.apache.org/POM/4.0.0"})


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def kernel():
    api = C.WinDLL("kernel32", use_last_error=True)
    api.GetStdHandle.argtypes = [W.DWORD]
    api.GetStdHandle.restype = W.HANDLE
    api.GetConsoleMode.argtypes = [W.HANDLE, C.POINTER(W.DWORD)]
    return api


def modes(api):
    result = []
    for which in (-10, -11):
        value = W.DWORD()
        require(api.GetConsoleMode(api.GetStdHandle(which & 0xffffffff), C.byref(value)),
                "GetConsoleMode failed in owned console")
        result.append(value.value)
    return result


def monitor(snapshot, command_file):
    api = kernel()
    before = modes(api)
    command = json.loads(Path(command_file).read_text())
    result = subprocess.run(command, check=False)
    after = modes(api)
    Path(snapshot).write_text(json.dumps({"before": before, "after": after, "code": result.returncode}))
    return result.returncode


class Coord(C.Structure):
    _fields_ = [("X", C.c_short), ("Y", C.c_short)]


class Startup(C.Structure):
    _fields_ = [("cb", W.DWORD), ("reserved", W.LPWSTR), ("desktop", W.LPWSTR),
                ("title", W.LPWSTR), ("x", W.DWORD), ("y", W.DWORD),
                ("xsize", W.DWORD), ("ysize", W.DWORD), ("xchars", W.DWORD),
                ("ychars", W.DWORD), ("fill", W.DWORD), ("flags", W.DWORD),
                ("show", W.WORD), ("reserved_size", W.WORD), ("reserved_ptr", C.c_void_p),
                ("stdin", W.HANDLE), ("stdout", W.HANDLE), ("stderr", W.HANDLE)]


class StartupEx(C.Structure):
    _fields_ = [("startup", Startup), ("attributes", C.c_void_p)]


class ProcessInfo(C.Structure):
    _fields_ = [("process", W.HANDLE), ("thread", W.HANDLE), ("pid", W.DWORD), ("tid", W.DWORD)]


class JobLimits(C.Structure):
    _fields_ = [("process_time", C.c_longlong), ("job_time", C.c_longlong),
                ("flags", W.DWORD), ("minimum_working_set", C.c_size_t),
                ("maximum_working_set", C.c_size_t), ("active_process_limit", W.DWORD),
                ("affinity", C.c_size_t), ("priority", W.DWORD), ("scheduling", W.DWORD)]


class ExtendedJobLimits(C.Structure):
    _fields_ = [("basic", JobLimits), ("io", C.c_ulonglong * 6),
                ("process_memory", C.c_size_t), ("job_memory", C.c_size_t),
                ("peak_process_memory", C.c_size_t), ("peak_job_memory", C.c_size_t)]


class Console:
    def __init__(self, command, work):
        import pyte
        self.api = kernel()
        api = self.api
        api.CreatePipe.argtypes = [C.POINTER(W.HANDLE), C.POINTER(W.HANDLE), C.c_void_p, W.DWORD]
        api.CreatePseudoConsole.argtypes = [Coord, W.HANDLE, W.HANDLE, W.DWORD, C.POINTER(W.HANDLE)]
        api.CreatePseudoConsole.restype = C.c_long
        api.ResizePseudoConsole.argtypes = [W.HANDLE, Coord]
        api.ResizePseudoConsole.restype = C.c_long
        api.ClosePseudoConsole.argtypes = [W.HANDLE]
        api.CloseHandle.argtypes = [W.HANDLE]
        api.ReadFile.argtypes = [W.HANDLE, C.c_void_p, W.DWORD, C.POINTER(W.DWORD), C.c_void_p]
        api.WriteFile.argtypes = [W.HANDLE, C.c_void_p, W.DWORD, C.POINTER(W.DWORD), C.c_void_p]
        api.WaitForSingleObject.argtypes = [W.HANDLE, W.DWORD]
        api.TerminateProcess.argtypes = [W.HANDLE, W.UINT]
        api.GetExitCodeProcess.argtypes = [W.HANDLE, C.POINTER(W.DWORD)]
        api.InitializeProcThreadAttributeList.argtypes = [C.c_void_p, W.DWORD, W.DWORD, C.POINTER(C.c_size_t)]
        api.UpdateProcThreadAttribute.argtypes = [C.c_void_p, W.DWORD, C.c_size_t, C.c_void_p,
                                                C.c_size_t, C.c_void_p, C.c_void_p]
        api.DeleteProcThreadAttributeList.argtypes = [C.c_void_p]
        api.CreateProcessW.argtypes = [W.LPCWSTR, W.LPWSTR, C.c_void_p, C.c_void_p, W.BOOL,
                                      W.DWORD, C.c_void_p, W.LPCWSTR, C.c_void_p, C.POINTER(ProcessInfo)]
        api.CreateJobObjectW.argtypes = [C.c_void_p, W.LPCWSTR]
        api.CreateJobObjectW.restype = W.HANDLE
        api.SetInformationJobObject.argtypes = [W.HANDLE, C.c_int, C.c_void_p, W.DWORD]
        api.AssignProcessToJobObject.argtypes = [W.HANDLE, W.HANDLE]
        api.TerminateJobObject.argtypes = [W.HANDLE, W.UINT]
        api.ResumeThread.argtypes = [W.HANDLE]
        self.job = None
        self.attributes_ready = False
        self.handles = []
        self.pseudo = W.HANDLE()
        self.info = ProcessInfo()
        self.attributes = None
        self.control = None
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen(1)
        self.listener.settimeout(30)
        self.pending = bytearray()
        self.lines = []
        self.output = bytearray()
        self.chunks = queue.Queue(maxsize=32)
        self.closing = threading.Event()
        self.screen = pyte.Screen(80, 24)
        self.stream = pyte.ByteStream(self.screen)
        self.snapshot = Path(work) / "console-modes.json"
        self.snapshot.unlink(missing_ok=True)
        self.command_file = Path(work) / "command.json"
        self.command_file.write_text(json.dumps(command))
        old_control = os.environ.get("BRAXTON_PROBE_CONTROL")
        try:
            input_read, self.input_write, self.output_read, output_write = [W.HANDLE() for _ in range(4)]
            require(api.CreatePipe(C.byref(input_read), C.byref(self.input_write), None, 0), "input pipe failed")
            self.handles.extend([input_read, self.input_write])
            require(api.CreatePipe(C.byref(self.output_read), C.byref(output_write), None, 0), "output pipe failed")
            self.handles.extend([self.output_read, output_write])
            require(api.CreatePseudoConsole(Coord(80, 24), input_read, output_write, 0, C.byref(self.pseudo)) == 0,
                    "CreatePseudoConsole failed")
            size = C.c_size_t()
            api.InitializeProcThreadAttributeList(None, 1, 0, C.byref(size))
            self.attributes = C.create_string_buffer(size.value)
            require(api.InitializeProcThreadAttributeList(self.attributes, 1, 0, C.byref(size)), "attributes failed")
            self.attributes_ready = True
            require(api.UpdateProcThreadAttribute(self.attributes, 0, 0x00020016, self.pseudo,
                                                 C.sizeof(W.HANDLE), None, None), "ConPTY attribute failed")
            startup = StartupEx()
            startup.startup.cb = C.sizeof(startup)
            startup.attributes = C.cast(self.attributes, C.c_void_p)
            os.environ["BRAXTON_PROBE_CONTROL"] = "tcp:" + str(self.listener.getsockname()[1])
            args = [sys.executable, str(Path(__file__).resolve()), "--monitor", str(self.snapshot), str(self.command_file)]
            line = C.create_unicode_buffer(subprocess.list2cmdline(args))
            self.job = api.CreateJobObjectW(None, None)
            require(self.job, "CreateJobObjectW failed")
            limits = ExtendedJobLimits()
            limits.basic.flags = 0x00002000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            require(api.SetInformationJobObject(self.job, 9, C.byref(limits), C.sizeof(limits)), "job limits failed")
            require(api.CreateProcessW(None, line, None, None, False, 0x00080004, None, str(ROOT),
                                       C.byref(startup), C.byref(self.info)), "CreateProcessW failed")
            require(api.AssignProcessToJobObject(self.job, self.info.process), "AssignProcessToJobObject failed")
            require(api.ResumeThread(self.info.thread) != -1, "ResumeThread failed")
            self.reader = threading.Thread(target=self._read_output, daemon=True)
            self.reader.start()
            self.control, _ = self.listener.accept()
            self.control.setblocking(False)
        except BaseException:
            self.close()
            raise
        finally:
            if old_control is None:
                os.environ.pop("BRAXTON_PROBE_CONTROL", None)
            else:
                os.environ["BRAXTON_PROBE_CONTROL"] = old_control

    def _read_output(self):
        buffer = C.create_string_buffer(65536)
        count = W.DWORD()
        while self.api.ReadFile(self.output_read, buffer, len(buffer), C.byref(count), None) and count.value:
            while not self.closing.is_set():
                try:
                    self.chunks.put(buffer.raw[:count.value], timeout=.1)
                    break
                except queue.Full:
                    pass

    def pump(self):
        while True:
            try:
                chunk = self.chunks.get_nowait()
            except queue.Empty:
                break
            self.output.extend(chunk)
            self.stream.feed(chunk)
        if self.control and select.select([self.control], [], [], .05)[0]:
            chunk = self.control.recv(65536)
            if chunk:
                self.pending.extend(chunk)
                while b"\n" in self.pending:
                    line, _, rest = self.pending.partition(b"\n")
                    self.pending = bytearray(rest)
                    self.lines.append(line.rstrip(b"\r").decode())

    def expect(self, line):
        deadline = time.monotonic() + 30
        while not self.lines and time.monotonic() < deadline:
            self.pump()
        require(self.lines and self.lines.pop(0) == line, f"missing/unexpected response {line!r}; output={self.output[-2000:]!r}")

    def command(self, value):
        self.control.sendall((value + "\n").encode())

    def feed(self, value):
        data = value.encode()
        count = W.DWORD()
        require(self.api.WriteFile(self.input_write, data, len(data), C.byref(count), None)
                and count.value == len(data), "console input failed")

    def resize(self, rows, columns):
        require(self.api.ResizePseudoConsole(self.pseudo, Coord(columns, rows)) == 0, "console resize failed")

    def finish(self, expected):
        deadline = time.monotonic() + 30
        while self.api.WaitForSingleObject(self.info.process, 0) != 0 and time.monotonic() < deadline:
            self.pump()
        self.pump()
        require(self.api.WaitForSingleObject(self.info.process, 0) == 0, "owned console child timed out")
        require(self.snapshot.exists(), "console mode monitor produced no result")
        result = json.loads(self.snapshot.read_text())
        require(result["code"] == expected, f"unexpected Java exit: {result}")
        require(result["before"] == result["after"], f"console modes not restored: {result}")
        require(b"\x1b[?25h" in self.output, "cursor not restored")

    def close(self):
        self.closing.set()
        if self.info.process and self.api.WaitForSingleObject(self.info.process, 0) != 0:
            self.api.TerminateProcess(self.info.process, 1)
            self.api.WaitForSingleObject(self.info.process, 5000)
        if self.job:
            self.api.TerminateJobObject(self.job, 1)
            self.api.CloseHandle(self.job)
        if self.control:
            self.control.close()
        self.listener.close()
        if self.pseudo:
            self.api.ClosePseudoConsole(self.pseudo)
        for handle in self.handles + [self.info.thread, self.info.process]:
            if handle:
                self.api.CloseHandle(handle)
        if self.attributes_ready:
            self.api.DeleteProcThreadAttributeList(self.attributes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--monitor", nargs=2, metavar=("SNAPSHOT", "COMMAND"))
    args = parser.parse_args()
    require(os.name == "nt", "this verifier requires Windows with ConPTY")
    if args.monitor:
        return monitor(*args.monitor)
    java, javac = shutil.which("java"), shutil.which("javac")
    require(java and javac, "JDK 25 required")
    jars = [p for p in (ROOT / "target").glob("braxton-*.jar") if not p.name.endswith(("-sources.jar", "-javadoc.jar"))]
    require(len(jars) == 1, "build the packaged jar first")
    cp_file = ROOT / "target/terminal-cp.txt"
    require(cp_file.exists(), "generate target/terminal-cp.txt using Maven dependency:build-classpath -DincludeScope=runtime")
    classpath = os.pathsep.join((str(jars[0]), cp_file.read_text().strip()))
    with tempfile.TemporaryDirectory(prefix="braxton-windows-") as work:
        subprocess.run([javac, "-Xlint:all", "-cp", classpath, "-d", work, str(ROOT / "tools/terminal/TerminalProbe.java")], check=True)
        command = [java, "--enable-native-access=ALL-UNNAMED", "-cp", os.pathsep.join((work, classpath)), PROBE]
        for scenario, code in (("CLOSE", 0), ("EXCEPTION", 1), ("SHUTDOWN", 0)):
            console = Console(command, work)
            try:
                console.expect("READY " + JLINE_IDENTITY)
                if scenario == "CLOSE":
                    console.feed("\x03界\x1b[A\x1b[200~paste\x1b[201~\x1b[<64;1;1M")
                    for line in ("KEY ctrl+c ", "KEY 界 e7958c", "KEY up ", "PASTE paste", "KEY wheelup "):
                        console.expect(line)
                    console.resize(31, 101)
                    time.sleep(.35)
                    console.command("SIZE")
                    console.expect("SIZE 31 101")
                    console.command("WRITE")
                    console.expect("WROTE")
                    deadline = time.monotonic() + 5
                    while "verified" not in "\n".join(console.screen.display) and time.monotonic() < deadline:
                        console.pump()
                    require("verified" in "\n".join(console.screen.display), "pyte did not see rendered output")
                    console.resize(200, 1000)
                    time.sleep(.35)
                    console.command("WRITE_LARGE")
                    console.expect("WROTE_LARGE")
                    console.command("CLOSE_IDLE")
                    console.expect("WAITING")
                console.command(scenario)
                if scenario == "CLOSE":
                    console.expect("REOPENED " + JLINE_IDENTITY)
                    console.feed("z")
                    console.expect("KEY z 7a")
                console.finish(code)
                print("PASS Windows FFM input/resize/pyte/cleanup", scenario)
            finally:
                console.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())

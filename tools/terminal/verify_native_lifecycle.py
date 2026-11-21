#!/usr/bin/env python3
"""Opt-in ABI/lifecycle check in this owned process and PTY; never uses the caller's terminal.

Usage: python3 tools/terminal/verify_native_lifecycle.py target/braxton-0.1.0.jar
       python3 tools/terminal/verify_native_lifecycle.py --stall-only JAR   (Java stall probes only)
"""
# Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT
import ctypes as c
import errno
import fcntl
import os
from pathlib import Path
import platform
import re
import signal
import select
import threading
import struct
import sys
import tempfile
import time
import subprocess
import termios
import zipfile

# Darwin's line discipline sets PENDIN when a non-canonical terminal returns to canonical
# mode. It is kernel bookkeeping for retyping pending input, not a settable attribute, so no
# raw-mode restore can reproduce it. Every other field is compared exactly.
KERNEL_MANAGED_FLAGS = getattr(termios, "PENDIN", 0)


def same_attributes(restored, original):
    restored, original = list(restored), list(original)
    restored[3] &= ~KERNEL_MANAGED_FLAGS
    original[3] &= ~KERNEL_MANAGED_FLAGS
    return restored == original


def congested_write(native, context, master, slave):
    """A stopped reader yields bounded attempts that report no progress; nothing blocks."""
    flags = fcntl.fcntl(slave, fcntl.F_GETFL)
    size = 4 * 1024 * 1024
    payload = c.create_string_buffer(b"x" * size)
    result = []

    def fill():
        written = 0
        while written < size:
            count = native.bt_write_some(context, c.byref(payload, written), size - written, 1000)
            if count <= 0:
                result.append((written, count))
                return
            written += count
        result.append((written, None))

    worker = threading.Thread(target=fill)
    worker.start()
    worker.join(3)
    stalled = worker.is_alive()
    # Release a write that unexpectedly blocks before closing its context. Never free under I/O.
    if stalled:
        os.set_blocking(master, False)
        while worker.is_alive():
            if select.select([master], [], [], 1)[0]:
                try:
                    os.read(master, 65536)
                except BlockingIOError:
                    pass
        worker.join()
    assert not stalled, "congested output blocked a bounded native attempt for more than 3 seconds"
    (written, last), = result
    assert 0 < written < size, f"expected partial progress before congestion, wrote {written}"
    assert last == 0, f"a congested attempt must report no progress (0), got {last}"
    assert fcntl.fcntl(slave, fcntl.F_GETFL) == flags, "caller descriptor flags changed"
    # Drain what was written so later checks start from an empty output queue.
    os.set_blocking(master, False)
    try:
        while select.select([master], [], [], 0.2)[0]:
            os.read(master, 65536)
    except (BlockingIOError, OSError):
        pass
    os.set_blocking(master, True)


def java_stall_probe(jar, work, mode):
    """Run NativeStallProbe on an owned PTY. slow: stop reading for 3 s, then drain. frozen: never read."""
    classes = Path(work) / "stall-classes"
    classes.mkdir(exist_ok=True)
    source = Path(__file__).with_name("NativeStallProbe.java")
    subprocess.run(["javac", "-cp", str(jar), "-d", str(classes), str(source)], check=True)
    master, slave = os.openpty()
    home = Path(work) / f"home-{mode}"
    home.mkdir(exist_ok=True)
    process = subprocess.Popen([
        "java", "--enable-native-access=ALL-UNNAMED", f"-Duser.home={home}",
        f"-Djava.io.tmpdir={work}", "-cp", os.pathsep.join((str(classes), str(jar))),
        "NativeStallProbe", mode
    ], stdin=slave, stdout=slave, stderr=subprocess.PIPE, env={**os.environ, "TERM": "xterm-256color"})
    os.close(slave)
    report = b""
    try:
        assert select.select([master], [], [], 10)[0], "writer never produced output"
        if mode == "slow":
            time.sleep(3)
        deadline = time.monotonic() + 60
        while process.poll() is None and time.monotonic() < deadline:
            ready = select.select([master, process.stderr], [], [], 0.5)[0]
            if mode == "slow" and master in ready:
                try:
                    os.read(master, 65536)
                except OSError:
                    pass
            if process.stderr in ready:
                report += os.read(process.stderr.fileno(), 4096)
        if process.poll() is None:
            raise AssertionError(f"{mode} probe did not finish")
        report += process.stderr.read()
        return process.returncode, report.decode(errors="replace").strip()
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        os.close(master)


def java_stall_probes(jar, work):
    for mode in ("slow", "frozen"):
        status, report = java_stall_probe(jar, work, mode)
        print(report)
        assert status == 0 and report.startswith("PASS"), f"{mode} stall probe failed: {report}"


def java_close_probe(jar, work):
    source = Path(__file__).with_name("NativeCloseProbe.java")
    subprocess.run(["javac", "-cp", str(jar), "-d", str(work), str(source)], check=True)
    master, slave = os.openpty()
    original = termios.tcgetattr(slave)
    home = Path(work) / "home"
    home.mkdir()
    process = subprocess.Popen([
        "java", "--enable-native-access=ALL-UNNAMED", f"-Duser.home={home}",
        f"-Djava.io.tmpdir={work}", "-cp", os.pathsep.join((str(work), str(jar))),
        "NativeCloseProbe"
    ], stdin=slave, stdout=slave, stderr=subprocess.PIPE, env={**os.environ, "TERM": "xterm-256color"})
    try:
        assert select.select([master], [], [], 10)[0], "writer never produced output"
        os.write(master, b"g")
        _, error = process.communicate(timeout=10)
        assert process.returncode == 0, error.decode()
        assert b"PASS concurrent Java read/write/close" in error, error.decode()
        assert same_attributes(termios.tcgetattr(slave), original), "Java close did not restore termios"
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        os.close(master)
        os.close(slave)



def released_probe(native):
    """release/reacquire keep the context usable and close is defined from every state.

    An owned PTY of its own: the caller's original configuration is what a released context has to
    restore, so this check cannot run inside a context that has already changed it.
    """
    master, slave = os.openpty()
    original = termios.tcgetattr(slave)
    context = c.c_void_p()
    assert native.bt_open(slave, slave, c.byref(context)) == 0
    assert native.bt_raw(context) == 0
    raw = termios.tcgetattr(slave)
    assert not same_attributes(raw, original), "raw mode did not change the device"

    # Giving the terminal back restores exactly the configuration the user had, and keeps the
    # context usable: nothing is closed and nothing is reallocated.
    assert native.bt_release(context) == 0
    assert same_attributes(termios.tcgetattr(slave), original), "release did not restore the device"
    # A released terminal reports no input and refuses output, so a session cannot take the
    # child's keystrokes or paint over its screen.
    buffer = c.create_string_buffer(32)
    assert native.bt_read(context, buffer, 32, 10) == 0, "a released terminal reported input"
    assert native.bt_write_some(context, c.c_char_p(b"x"), 1, 10) == -errno.EBUSY, \
        "a released terminal accepted output"
    # Releasing twice is defined, and taking the terminal back restores raw mode exactly.
    assert native.bt_release(context) == 0
    assert native.bt_reacquire(context) == 0
    assert same_attributes(termios.tcgetattr(slave), raw), "reacquire did not restore raw mode"
    assert native.bt_reacquire(context) == 0
    os.write(master, b"a")
    assert native.bt_read(context, buffer, 32, 100) == 1, "a reacquired terminal did not read"

    # An owned PTY that is nobody's controlling terminal has no foreground group, so job control is
    # refused rather than left stopped with nobody to continue it. Suspending requires a released
    # terminal, which is what the session does before it stops.
    assert native.bt_foreground(context) == 0, "a process without the controlling terminal claimed it"
    assert native.bt_suspend(context) == errno.EINVAL, "a suspend with the terminal in use was attempted"
    assert native.bt_release(context) == 0
    started = time.monotonic()
    assert native.bt_suspend(context) == errno.EPERM, "a background session suspended itself"
    assert time.monotonic() - started < 5, "a discarded stop was waited for instead of reported"
    assert native.bt_reacquire(context) == 0

    # Close is defined from the released state: the configuration is already the user's own.
    assert native.bt_release(context) == 0
    assert native.bt_close(context) == 0
    assert same_attributes(termios.tcgetattr(slave), original), "close from released did not restore"
    os.close(master)
    os.close(slave)


def job_control_probe(native):
    """A shell-like session stops its own foreground job and continues it.

    Job control needs a session whose controlling terminal is the owned PTY and a job in a separate
    process group of that same session, so this builds both: the fork that calls setsid is the
    shell, and the job it starts is the process that suspends. A group whose parent is outside its
    session is orphaned, and its stop signals are discarded - which is exactly why a session that
    does not own the foreground must be refused instead of stopped.
    """
    master, slave = os.openpty()
    original = termios.tcgetattr(slave)
    report = os.pipe()      # job -> outer: "R" released, "0" resumed status
    stopped = os.pipe()     # shell -> outer: "S" the job is stopped
    handshake = os.pipe()   # shell -> job: the foreground group is set
    shell = os.fork()
    if shell == 0:
        try:
            os.close(report[0])
            os.close(stopped[0])
            os.setsid()
            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
            job = os.fork()
            if job == 0:
                try:
                    os.close(stopped[1])
                    os.close(handshake[1])
                    os.setpgid(0, 0)
                    os.read(handshake[0], 1)
                    context = c.c_void_p()
                    assert native.bt_open(slave, slave, c.byref(context)) == 0
                    assert native.bt_raw(context) == 0
                    assert native.bt_foreground(context) == 1, "the job did not own the foreground"
                    assert native.bt_release(context) == 0
                    os.write(report[1], b"R")
                    status = native.bt_suspend(context)
                    os.write(report[1], str(int(status)).encode())
                    assert native.bt_reacquire(context) == 0
                    assert native.bt_close(context) == 0
                    os._exit(0)
                except BaseException:
                    os._exit(3)
            os.close(handshake[0])
            # The job put itself in its own process group; making that group the terminal's
            # foreground is what a shell does before it continues a job.
            os.setpgid(job, job)
            fcntl.ioctl(slave, termios.TIOCSPGRP, struct.pack("i", job))
            os.write(handshake[1], b"g")
            pid_seen, status = os.waitpid(job, os.WUNTRACED)
            if pid_seen != job or not os.WIFSTOPPED(status):
                os._exit(4)
            os.write(stopped[1], b"S")
            os.killpg(job, signal.SIGCONT)
            _, status = os.waitpid(job, 0)
            os._exit(0 if os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0 else 5)
        except BaseException:
            os._exit(6)
    os.close(report[1])
    os.close(stopped[1])
    os.close(handshake[0])
    os.close(handshake[1])
    try:
        def expect(descriptor, what):
            ready = select.select([descriptor], [], [], 20)[0]
            assert ready, f"the job never reported {what}"
            return os.read(descriptor, 16)

        assert expect(report[0], "released") == b"R", "the job never gave the terminal back"
        assert same_attributes(termios.tcgetattr(slave), original), \
            "the terminal was not in the user's configuration while the job held it"
        assert expect(stopped[0], "the stop") == b"S", "the job did not stop for a suspend"
        assert same_attributes(termios.tcgetattr(slave), original), \
            "the terminal was not in the user's configuration while the job was stopped"
        assert expect(report[0], "the resume status") == b"0", "the suspend did not resume cleanly"
        _, status = os.waitpid(shell, 0)
        assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0, \
            f"the simulated shell reported {os.WEXITSTATUS(status) if os.WIFEXITED(status) else status}"
        assert same_attributes(termios.tcgetattr(slave), original), \
            "the terminal was not restored after the resumed job closed it"
    finally:
        os.close(report[0])
        os.close(stopped[0])
        os.close(master)
        os.close(slave)


def java_handoff_probe(jar, work):
    """The Java terminal gives the device back, refuses I/O while released, and takes it again."""
    classes = Path(work) / "handoff-classes"
    classes.mkdir(exist_ok=True)
    subprocess.run(["javac", "-cp", str(jar), "-d", str(classes),
                    str(Path(__file__).with_name("NativeHandoffProbe.java"))], check=True)
    for mode in ("release", "close"):
        master, slave = os.openpty()
        original = termios.tcgetattr(slave)
        released = Path(work) / f"handoff-{mode}-released"
        resume = Path(work) / f"handoff-{mode}-resume"
        home = Path(work) / f"home-handoff-{mode}"
        home.mkdir(exist_ok=True)
        process = subprocess.Popen([
            "java", "--enable-native-access=ALL-UNNAMED", f"-Duser.home={home}",
            f"-Djava.io.tmpdir={work}", "-cp", os.pathsep.join((str(classes), str(jar))),
            "NativeHandoffProbe", mode, str(released), str(resume)
        ], stdin=slave, stdout=slave, stderr=subprocess.PIPE, env={**os.environ, "TERM": "xterm-256color"})
        try:
            deadline = time.monotonic() + 20
            while not released.exists():
                assert process.poll() is None, process.stderr.read().decode(errors="replace")
                assert time.monotonic() < deadline, f"{mode} probe never released the terminal"
                time.sleep(0.02)
            assert same_attributes(termios.tcgetattr(slave), original), \
                f"{mode}: the Java terminal did not give the device back"
            resume.write_text("resume")
            if mode == "release":
                os.write(master, b"k")
            _, error = process.communicate(timeout=20)
            text = error.decode(errors="replace")
            assert process.returncode == 0, text
            assert "PASS" in text, text
            print(text.strip().splitlines()[-1])
            assert same_attributes(termios.tcgetattr(slave), original), \
                f"{mode}: the Java terminal did not restore the device"
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            os.close(master)
            os.close(slave)


def redirected_stdin_probe(jar, work):
    """R-02: keys come from /dev/tty, piped stdin remains unread, no tty is a typed failure."""
    classes = Path(work) / "ctl-classes"
    classes.mkdir(exist_ok=True)
    subprocess.run(["javac", "-cp", str(jar), "-d", str(classes),
                    str(Path(__file__).with_name("ControllingTerminalProbe.java"))], check=True)
    master, slave = os.openpty()

    def attach():
        os.setsid()
        fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

    process = subprocess.Popen([
        "java", "--enable-native-access=ALL-UNNAMED", f"-Djava.io.tmpdir={work}",
        "-cp", os.pathsep.join((str(classes), str(jar))), "ControllingTerminalProbe"
    ], stdin=subprocess.PIPE, stdout=slave, stderr=subprocess.PIPE, preexec_fn=attach,
       env={**os.environ, "TERM": "xterm-256color"})
    os.close(slave)

    def marker():
        assert select.select([process.stderr], [], [], 20)[0], "the native probe never reported"
        return process.stderr.readline()

    try:
        os.write(master, b"k")
        assert marker().strip() == b"KEY=107", "the native reader did not get the PTY key"
        process.stdin.write(b"from-pipe\n")
        process.stdin.flush()
        assert marker().strip() == b"PIPED=from-pipe", "piped stdin was consumed by the terminal"
        assert process.wait(timeout=10) == 0, "the native probe did not exit cleanly"
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        os.close(master)

    no_tty = subprocess.Popen([
        "java", "--enable-native-access=ALL-UNNAMED", f"-Djava.io.tmpdir={work}",
        "-cp", os.pathsep.join((str(classes), str(jar))), "ControllingTerminalProbe", "notty"
    ], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
       env={**os.environ, "TERM": "xterm-256color"})
    _, error = no_tty.communicate(timeout=10)
    assert no_tty.returncode == 0 and b"NO_TTY" in error, error.decode(errors="replace")


def external_stop_probe(native):
    """R-03: external SIGTSTP restores cooked mode and leaves the screen; SIGCONT resumes."""
    master, slave = os.openpty()
    original = termios.tcgetattr(slave)
    report = os.pipe()
    child = os.fork()
    if child == 0:
        def say(value):
            os.write(report[1], value + b"\n")
        try:
            os.close(report[0])
            os.setsid()
            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)
            context = c.c_void_p()
            assert native.bt_open(slave, slave, c.byref(context)) == 0
            assert native.bt_raw(context) == 0
            restore, resume = b"RESTORE", b"RESUME"
            assert native.bt_set_suspend_bytes(context, c.c_char_p(restore), len(restore),
                                               c.c_char_p(resume), len(resume)) == 0
            say(f"START{native.bt_resume_epoch()}".encode())
            say(b"READY")
            for _ in range(2):
                before = native.bt_resume_epoch()
                while native.bt_resume_epoch() == before:
                    time.sleep(0.01)
                say(b"RESUMED")
            say(b"DONE")
            say(f"FINAL{native.bt_resume_epoch()}".encode())
            assert native.bt_close(context) == 0
            os._exit(0)
        except BaseException as failure:
            say(b"ERROR:" + repr(failure).encode(errors="replace"))
            os._exit(7)
    os.close(report[1])

    def line(timeout=20):
        data = bytearray()
        deadline = time.monotonic() + timeout
        while not data.endswith(b"\n"):
            assert time.monotonic() < deadline and select.select([report[0]], [], [], 1)[0], \
                "the child never reported"
            data.extend(os.read(report[0], 1))
        return bytes(data[:-1])

    def drain():
        data = bytearray()
        while select.select([master], [], [], 0)[0]:
            try:
                data.extend(os.read(master, 4096))
            except BlockingIOError:
                break
        return bytes(data)

    try:
        ready = b""
        while b"READY" not in ready and b"ERROR" not in ready:
            chunk = line()
            ready += chunk
        assert b"READY" in ready, f"the child did not register its suspend bytes: {ready!r}"
        start = int(re.search(rb"START(\d+)", ready).group(1))
        for cycle in (1, 2):
            os.killpg(child, signal.SIGTSTP)
            pid, status = os.waitpid(child, os.WUNTRACED)
            assert pid == child and os.WIFSTOPPED(status), f"the job did not stop: status={status}"
            if os.WIFSTOPPED(status):
                stop_signal_number = os.WSTOPSIG(status)
            else:
                stop_signal_number = -1
            stop_output = drain()
            assert b"RESTORE" in stop_output, f"the restore bytes were not written before the stop: {stop_output!r}"
            stopped_attributes = termios.tcgetattr(slave)
            assert same_attributes(stopped_attributes, original), \
                f"the terminal was not cooked before the stop: signal={stop_signal_number}, {original[3]:#x} -> {stopped_attributes[3]:#x}, output={stop_output!r}"
            os.killpg(child, signal.SIGCONT)
            resumed = line()
            assert resumed == b"RESUMED", f"the resume epoch did not advance: {resumed!r}"
            assert b"RESUME" in drain(), "the resume bytes were not written after the continue"
            after = termios.tcgetattr(slave)
            assert not (after[3] & termios.ICANON), "raw mode was not restored after continue"
        assert line() == b"DONE", "the child did not finish both cycles"
        final = line()
        assert final == f"FINAL{start + 2}".encode(), f"unexpected final resume epoch: {final!r}"
        _, status = os.waitpid(child, 0)
        assert os.WIFEXITED(status) and os.WEXITSTATUS(status) == 0, "the child failed to close cleanly"
        assert same_attributes(termios.tcgetattr(slave), original), "terminal was not restored after close"
    finally:
        try:
            if os.waitpid(child, os.WNOHANG)[0] == 0:
                os.killpg(child, signal.SIGKILL)
                os.waitpid(child, 0)
        except ChildProcessError:
            pass
        os.close(report[0])
        os.close(master)
        os.close(slave)


def main():
    if sys.argv[1] == "--stall-only":
        # Java probes only: usable against any build, including one predating ABI 2.
        with tempfile.TemporaryDirectory(prefix="braxton-stall-check-") as work:
            status, report = java_stall_probe(Path(sys.argv[2]).resolve(), work, sys.argv[3] if len(sys.argv) > 3 else "slow")
            print(report)
            sys.exit(status)
    system = {"Linux": "linux", "Darwin": "macos"}[platform.system()]
    arch = "aarch64" if platform.machine() in ("aarch64", "arm64") else "x86_64"
    extension = "dylib" if system == "macos" else "so"
    resource = f"META-INF/braxton/native/{system}-{arch}/libbraxton.{extension}"
    with tempfile.TemporaryDirectory(prefix="braxton-native-check-") as work:
        library = Path(work) / f"libbraxton.{extension}"
        with zipfile.ZipFile(sys.argv[1]) as jar:
            library.write_bytes(jar.read(resource))
        native = c.CDLL(str(library))
        native.bt_open.argtypes = (c.c_int, c.c_int, c.POINTER(c.c_void_p))
        native.bt_raw.argtypes = (c.c_void_p,)
        native.bt_close.argtypes = (c.c_void_p,)
        native.bt_read.argtypes = (c.c_void_p, c.c_void_p, c.c_int, c.c_int)
        native.bt_write_some.argtypes = (c.c_void_p, c.c_void_p, c.c_int, c.c_int)
        native.bt_size.argtypes = (c.c_void_p, c.POINTER(c.c_int), c.POINTER(c.c_int))
        native.bt_release.argtypes = (c.c_void_p,)
        native.bt_reacquire.argtypes = (c.c_void_p,)
        native.bt_foreground.argtypes = (c.c_void_p,)
        native.bt_suspend.argtypes = (c.c_void_p,)
        native.bt_set_suspend_bytes.argtypes = (c.c_void_p, c.c_char_p, c.c_int, c.c_char_p, c.c_int)
        native.bt_resume_epoch.restype = c.c_int
        assert native.bt_abi() == 5
        master, slave = os.openpty()
        original = termios.tcgetattr(slave)
        previous = signal.getsignal(signal.SIGWINCH)
        notifications = []
        signal.signal(signal.SIGWINCH, lambda *_: notifications.append("original"))
        context = c.c_void_p()
        try:
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
            assert native.bt_open(slave, slave, c.byref(context)) == 0
            assert native.bt_raw(context) == 0
            rows, columns = c.c_int(), c.c_int()
            assert native.bt_size(context, c.byref(rows), c.byref(columns)) == 0
            assert (rows.value, columns.value) == (24, 80)
            buffer = c.create_string_buffer(32)
            assert native.bt_read(context, buffer, 32, 1) == 0
            os.write(master, b"\x03hi")
            assert native.bt_read(context, buffer, 32, 100) == 3
            assert buffer.raw[:3] == b"\x03hi"
            assert native.bt_write_some(context, c.c_char_p(b"output"), 6, 100) == 6
            assert os.read(master, 6) == b"output"
            epoch = native.bt_resize_epoch()
            os.kill(os.getpid(), signal.SIGWINCH)
            assert native.bt_resize_epoch() != epoch
            assert not notifications
            congested_write(native, context, master, slave)
            released_probe(native)
            acquired, context = context, c.c_void_p()
            assert native.bt_close(acquired) == 0
            assert same_attributes(termios.tcgetattr(slave), original), "terminal attributes were not restored"
            os.kill(os.getpid(), signal.SIGWINCH)
            assert notifications == ["original"], "previous resize handler was not restored"
            assert native.bt_open(slave, slave, c.byref(context)) == 0
            signal.signal(signal.SIGWINCH, lambda *_: notifications.append("replacement"))
            acquired, context = context, c.c_void_p()
            assert native.bt_close(acquired) == 0
            os.kill(os.getpid(), signal.SIGWINCH)
            assert notifications[-1] == "replacement", "close replaced a subsequently installed handler"
            for _ in range(50):
                assert native.bt_open(slave, slave, c.byref(context)) == 0
                assert native.bt_raw(context) == 0
                os.kill(os.getpid(), signal.SIGWINCH)
                acquired, context = context, c.c_void_p()
                assert native.bt_close(acquired) == 0
                assert same_attributes(termios.tcgetattr(slave), original)
        finally:
            if context.value:
                native.bt_close(context)
            signal.signal(signal.SIGWINCH, previous)
            os.close(master)
            os.close(slave)
        job_control_probe(native)
        java_close_probe(Path(sys.argv[1]).resolve(), work)
        java_stall_probes(Path(sys.argv[1]).resolve(), work)
        java_handoff_probe(Path(sys.argv[1]).resolve(), work)
        redirected_stdin_probe(Path(sys.argv[1]).resolve(), work)
        external_stop_probe(native)
    print("PASS native ABI/raw I/O/size/congested output/output stall/concurrent Java close/resize-handler "
          "ownership/restoration/release-reacquire/Java handoff/job control/redirected stdin/external stop")


if __name__ == "__main__":
    main()

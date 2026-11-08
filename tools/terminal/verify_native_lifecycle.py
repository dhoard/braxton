#!/usr/bin/env python3
"""Opt-in ABI/lifecycle check in this owned process and PTY; never uses the caller's terminal.

Usage: python3 tools/terminal/verify_native_lifecycle.py target/braxton-0.1.0.jar
"""
# Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT
import ctypes as c
import errno
import fcntl
import os
from pathlib import Path
import platform
import signal
import select
import threading
import struct
import sys
import tempfile
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
    """A stopped reader must not prevent native close from ever running."""
    flags = fcntl.fcntl(slave, fcntl.F_GETFL)
    payload = c.create_string_buffer(b"x" * (4 * 1024 * 1024))
    result = []
    worker = threading.Thread(target=lambda: result.append(native.bt_write(context, payload, len(payload))))
    worker.start()
    worker.join(3)
    stalled = worker.is_alive()
    # Release even the pre-fix blocking write before closing its context. Never free under I/O.
    if stalled:
        os.set_blocking(master, False)
        while worker.is_alive():
            if select.select([master], [], [], 1)[0]:
                try:
                    os.read(master, 65536)
                except BlockingIOError:
                    pass
        worker.join()
    assert not stalled, "congested output blocked native shutdown for more than 3 seconds"
    assert result == [errno.ETIMEDOUT], result
    assert fcntl.fcntl(slave, fcntl.F_GETFL) == flags, "caller descriptor flags changed"


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


def main():
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
        native.bt_write.argtypes = (c.c_void_p, c.c_void_p, c.c_int)
        native.bt_size.argtypes = (c.c_void_p, c.POINTER(c.c_int), c.POINTER(c.c_int))
        assert native.bt_abi() == 1
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
            assert native.bt_write(context, c.c_char_p(b"output"), 6) == 0
            assert os.read(master, 6) == b"output"
            epoch = native.bt_resize_epoch()
            os.kill(os.getpid(), signal.SIGWINCH)
            assert native.bt_resize_epoch() != epoch
            assert not notifications
            congested_write(native, context, master, slave)
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
        java_close_probe(Path(sys.argv[1]).resolve(), work)
    print("PASS native ABI/raw I/O/size/congested output/concurrent Java close/resize-handler ownership/restoration")


if __name__ == "__main__":
    main()

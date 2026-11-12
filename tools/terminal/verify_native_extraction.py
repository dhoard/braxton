#!/usr/bin/env python3
"""Verify packaged extraction with owned JVMs/homes and optional isolated Linux noexec storage.

Usage: python3 tools/terminal/verify_native_extraction.py target/braxton-0.1.0.jar [--noexec]
Noexec verification creates its own user/mount namespace; it never remounts the caller's /tmp.
"""
# Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT
import argparse
from contextlib import ExitStack
import hashlib
import os
from pathlib import Path
import platform
import selectors
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PROBE = "io.github.dhoard.braxton.internal.terminal.NativeLoadProbe"


def require(value, message):
    if not value:
        raise AssertionError(message)


def await_files(paths):
    deadline = time.monotonic() + 20
    while not all(path.exists() for path in paths):
        require(time.monotonic() < deadline, "JVMs did not reach the start barrier")
        time.sleep(0.01)


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    process.stdin.close()
    process.stdout.close()


def loaded(process, diagnostics):
    with selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        require(selector.select(20), "native loading timed out")
        line = process.stdout.readline().decode().strip()
    if line != "LOADED":
        diagnostics.seek(0)
        raise AssertionError("native load failed: " + diagnostics.read().decode())


def verify(args):
    jar = args.jar.resolve()
    with tempfile.TemporaryDirectory(prefix="braxton-extraction-") as temporary, ExitStack() as resources:
        work = Path(temporary)
        native_tmp = work / "native-tmp"
        if args.namespace_child:
            require(args.parent_mount and os.readlink("/proc/self/ns/mnt") != args.parent_mount,
                    "noexec verification requires an isolated mount namespace")
            native_tmp.mkdir()
            subprocess.run(["mount", "-t", "tmpfs", "-o", "noexec,nosuid,nodev,size=16m", "tmpfs", str(native_tmp)], check=True)
            resources.callback(lambda: subprocess.run(["umount", str(native_tmp)], check=True))
        # Without --noexec the missing temporary directory also exercises real fallback loading.
        classes = work / "classes"
        classes.mkdir()
        subprocess.run(["javac", "-Xlint:all", "-cp", str(jar), "-d", str(classes),
                        str(ROOT / "tools/terminal/NativeLoadProbe.java")], check=True)
        system = "macos" if platform.system() == "Darwin" else "linux"
        arch = "aarch64" if platform.machine() in ("arm64", "aarch64") else "x86_64"
        target = f"{system}-{arch}"
        filename = "libbraxton.dylib" if system == "macos" else "libbraxton.so"
        prefix = f"META-INF/braxton/native/{target}/"
        with zipfile.ZipFile(jar) as source:
            payload = source.read(prefix + filename)
            if args.expect_load_failure:
                # A saved pre-fix jar has no recorded hash; only the failure path uses this mode.
                version, digest = "pre-fix", ""
            else:
                version = source.read(prefix + "version.txt").decode().strip()
                digest = source.read(prefix + filename + ".sha256").decode().strip()
        if digest:
            require(digest == hashlib.sha256(payload).hexdigest(),
                    f"packaged {filename}.sha256 does not describe the packaged binary")
        home = work / "home"
        home.mkdir()
        gate = work / "release"
        cache = home / ".local/share/braxton/native"
        processes = []
        markers = []

        def start(application_jar, name, temporary_root=native_tmp):
            marker = work / (name + ".ready")
            markers.append(marker)
            diagnostics = resources.enter_context(tempfile.TemporaryFile())
            command = ["java", "--enable-native-access=ALL-UNNAMED", f"-Duser.home={home}",
                       f"-Djava.io.tmpdir={temporary_root}", "-cp", str(classes) + os.pathsep + str(application_jar),
                       PROBE, str(gate), str(marker)]
            process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=diagnostics)
            processes.append(process)
            resources.callback(stop, process)
            return process, diagnostics

        first = start(jar, "first")
        second = start(jar, "second") if not args.expect_load_failure else None
        await_files(markers)
        require(not cache.exists(), "cache existed before simultaneous first starts")
        gate.touch()
        if args.expect_load_failure:
            process, diagnostics = first
            process.stdin.close()
            process.stdin = open(os.devnull, "wb")
            require(process.wait(timeout=20) != 0, "pre-fix loader unexpectedly succeeded on noexec storage")
            diagnostics.seek(0)
            error = diagnostics.read().decode()
            require("Cannot open library" in error and "braxton-native-" in error, error)
            print("PASS pre-fix packaged loader reproduces noexec load failure")
            return
        loaded(*first)
        loaded(*second)
        published = cache / version / target / digest / filename
        require(published.read_bytes() == payload, "concurrent apps did not publish the packaged binary")
        inode = published.stat().st_ino
        require(not list(cache.rglob("*.part")), "publication left staging files")
        later_version = version + "-loader-test"
        other_jar = work / "different-version.jar"
        with zipfile.ZipFile(jar) as source, zipfile.ZipFile(other_jar, "w") as target_jar:
            for entry in source.infolist():
                contents = (later_version + "\n").encode() if entry.filename == prefix + "version.txt" else source.read(entry)
                target_jar.writestr(entry, contents)
        later = start(other_jar, "later-version")
        loaded(*later)
        other_file = cache / later_version / target / digest / filename
        require(other_file != published and other_file.read_bytes() == payload, "native versions did not coexist")
        require(published.stat().st_ino == inode, "loading another version replaced the first library")
        normal_tmp = work / "executable-tmp"
        normal_tmp.mkdir()
        normal = start(jar, "normal-temp", normal_tmp)
        loaded(*normal)
        require(not list(normal_tmp.iterdir()), "normal temporary extraction was not cleaned")
        for process in processes:
            process.stdin.write(b"x")
            process.stdin.flush()
            require(process.wait(timeout=20) == 0, "loaded native handle failed after concurrent starts")
            require(process.stdout.read().strip() == b"ABI 1", "native ABI handle did not survive")
        print("PASS packaged native fallback/concurrent first starts/version coexistence/temp cleanup" +
              (" on an actual noexec filesystem" if args.namespace_child else " with unavailable temp storage"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("--noexec", action="store_true")
    parser.add_argument("--expect-load-failure", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--namespace-child", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--parent-mount", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.noexec and not args.namespace_child:
        require(platform.system() == "Linux" and shutil.which("unshare"), "--noexec requires Linux user/mount namespaces")
        command = ["unshare", "--user", "--map-root-user", "--mount", "--propagation", "private",
                   sys.executable, str(Path(__file__).resolve()), str(args.jar.resolve()), "--namespace-child",
                   "--parent-mount", os.readlink("/proc/self/ns/mnt")]
        if args.expect_load_failure:
            command.append("--expect-load-failure")
        return subprocess.run(command).returncode
    verify(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())

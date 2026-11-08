/* Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT */
package io.github.dhoard.braxton.internal.terminal;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;

/** Developer probe: exercise the packaged FFM loader without opening a process terminal. */
@SuppressWarnings("restricted")
public final class NativeLoadProbe {
    public static void main(String[] args) throws Throwable {
        Path gate = Path.of(args[0]);
        Files.writeString(Path.of(args[1]), "ready");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(gate)) {
            if (System.nanoTime() > deadline) throw new AssertionError("start barrier not released");
            Thread.sleep(5);
        }
        var holder = Class.forName(NativeTerminal.class.getName() + "$Library");
        var field = holder.getDeclaredField("SYMBOLS");
        field.setAccessible(true);
        SymbolLookup symbols = (SymbolLookup) field.get(null);
        var abi = Linker.nativeLinker().downcallHandle(symbols.find("bt_abi").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        if ((int) abi.invokeExact() != 1) throw new AssertionError("invalid native ABI");
        System.out.println("LOADED");
        System.out.flush();
        System.in.read();
        // Keep and use the handle after another application/version has loaded its binary.
        if ((int) abi.invokeExact() != 1) throw new AssertionError("native ABI changed while loaded");
        System.out.println("ABI 1");
    }
}

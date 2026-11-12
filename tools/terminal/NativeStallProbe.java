/* Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT */

import io.github.dhoard.braxton.internal.terminal.NativeTerminal;
import java.time.Duration;

/**
 * Opt-in output stall probe, run by verify_native_lifecycle.py on an owned PTY.
 *
 * <p>{@code slow}: the parent stops reading for three seconds, then drains. Only the default
 * {@link NativeTerminal#open()} is used, so the same probe also runs against older builds (where it
 * fails with the one-second native budget).</p>
 *
 * <p>{@code frozen}: the parent never reads. With a two-second stall limit the write must fail, and
 * close must still complete promptly.</p>
 */
public final class NativeStallProbe {
    private static final int PAYLOAD = 4 * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        String mode = args.length == 1 ? args[0] : "";
        switch (mode) {
            case "slow" -> slow();
            case "frozen" -> frozen();
            default -> throw new IllegalArgumentException("usage: NativeStallProbe slow|frozen");
        }
    }

    private static void slow() throws Exception {
        var terminal = NativeTerminal.open();
        long start = System.nanoTime();
        terminal.writer().write("x".repeat(PAYLOAD));
        terminal.writer().flush();
        boolean failed = terminal.writer().checkError();
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        terminal.close();
        if (failed) {
            System.err.println("FAIL slow terminal output failed after " + elapsed + " ms");
            System.exit(1);
        }
        System.err.println("PASS slow terminal waited out (" + elapsed + " ms)");
    }

    private static void frozen() throws Exception {
        // Reflection keeps this file compilable against builds without the stall-limit seam.
        var terminal = (NativeTerminal)
                NativeTerminal.class.getMethod("open", Duration.class).invoke(null, Duration.ofSeconds(2));
        long start = System.nanoTime();
        terminal.writer().write("x".repeat(PAYLOAD));
        terminal.writer().flush();
        boolean failed = terminal.writer().checkError();
        long elapsed = (System.nanoTime() - start) / 1_000_000L;
        Throwable cause = (Throwable) NativeTerminal.class.getMethod("outputError").invoke(terminal);
        long closeStart = System.nanoTime();
        terminal.close();
        long closed = (System.nanoTime() - closeStart) / 1_000_000L;
        if (!failed || cause == null || !String.valueOf(cause.getMessage()).contains("accepted no output")) {
            System.err.println("FAIL frozen terminal: failed=" + failed + " cause=" + cause);
            System.exit(1);
        }
        if (elapsed < 1_900L || elapsed > 5_000L || closed > 2_000L) {
            System.err.println("FAIL frozen terminal timing: write " + elapsed + " ms, close " + closed + " ms");
            System.exit(1);
        }
        System.err.println("PASS frozen terminal failed after " + elapsed + " ms: " + cause.getMessage());
    }
}

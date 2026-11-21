/* Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT */
import io.github.dhoard.braxton.internal.terminal.NativeTerminal;
import io.github.dhoard.braxton.terminal.NoControllingTerminalException;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Opt-in R-02 probe: native keys come from /dev/tty while piped stdin stays available.
 */
public final class ControllingTerminalProbe {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("notty")) {
            try (var terminal = NativeTerminal.open()) {
                System.err.println("OPENED");
            } catch (NoControllingTerminalException expected) {
                System.err.println("NO_TTY");
            }
            return;
        }
        try (var terminal = NativeTerminal.open()) {
            terminal.enterRawMode();
            int key = terminal.reader().read(10_000);
            System.err.println("KEY=" + key);
            String piped = new BufferedReader(new InputStreamReader(System.in)).readLine();
            System.err.println("PIPED=" + piped);
        }
    }
}

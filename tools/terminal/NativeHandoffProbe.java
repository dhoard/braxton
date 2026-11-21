/* Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT */
/* Developer-only: exercises the Java terminal's reversible ownership against an owned PTY. */
import io.github.dhoard.braxton.internal.terminal.NativeTerminal;
import io.github.dhoard.braxton.internal.terminal.Terminal;
import io.github.dhoard.braxton.internal.terminal.TimedReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parent-owned PTY probe for {@code release}/{@code reacquire}/{@code close}.
 *
 * <p>The parent watches the device's attributes while this process holds the terminal released, so
 * the evidence is the real terminal and not a self-report. Two marker files are the handshake:
 * {@code released} appears once the terminal has been given back, and {@code resume} appears when
 * the parent has finished inspecting it.</p>
 */
public final class NativeHandoffProbe {

    private NativeHandoffProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: NativeHandoffProbe <release|close> <released-marker> <resume-marker>");
            System.exit(2);
        }
        String mode = args[0];
        Path released = Path.of(args[1]);
        Path resume = Path.of(args[2]);
        NativeTerminal terminal = NativeTerminal.open();
        terminal.enterRawMode();
        check(terminal.state() == Terminal.State.ACTIVE, "open terminal is not active: " + terminal.state());
        terminal.release();
        check(
                terminal.state() == Terminal.State.RELEASED,
                "released terminal is not released: " + terminal.state());
        // A released terminal belongs to somebody else: it reports no input to read, so the
        // session cannot take the child's keystrokes.
        int read = terminal.reader().read(20);
        check(read == TimedReader.READ_EXPIRED, "a released terminal reported input: " + read);
        check(!terminal.foreground(), "a PTY with no controlling terminal claimed the foreground");
        Files.writeString(released, "released");
        while (!Files.exists(resume)) {
            Thread.sleep(10);
        }
        if (mode.equals("close")) {
            // Output is refused while released. That failure is the session's own output failure,
            // which is fatal for a run by design, so it is asserted only here, where nothing is
            // written afterward: a refused write poisons the writer on purpose.
            terminal.writer().write("frame");
            terminal.writer().flush();
            check(terminal.writer().checkError(), "a released terminal accepted output");
            check(terminal.outputError() != null, "the refused write recorded no failure");
            // Closing from the released state is defined: the configuration is already the user's
            // own, and a second close is a no-op.
            terminal.close();
            terminal.close();
            check(terminal.state() == Terminal.State.CLOSED, "closed terminal is not closed");
            System.err.println("PASS release/idle-input/refused-output/close-while-released");
            return;
        }
        terminal.reacquire();
        check(terminal.state() == Terminal.State.ACTIVE, "reacquired terminal is not active");
        // Raw input works again: the parent writes one byte and this process reads it.
        int value = terminal.reader().read(5_000);
        check(value == 'k', "reacquired terminal did not read the parent's byte: " + value);
        terminal.writer().write("back");
        terminal.writer().flush();
        check(!terminal.writer().checkError(), "the reacquired terminal refused output");
        terminal.close();
        terminal.close();
        check(terminal.state() == Terminal.State.CLOSED, "closed terminal is not closed");
        System.err.println("PASS release/idle-input/reacquire/read-write/idempotent-close");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

/* Copyright (c) 2026-present Doug Hoard. SPDX-License-Identifier: MIT */
/* Developer-only: parent owns the PTY and does not drain output until this process exits. */
import io.github.dhoard.braxton.internal.terminal.NativeTerminal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class NativeCloseProbe {
    public static void main(String[] args) throws Exception {
        var terminal = NativeTerminal.open();
        terminal.enterRawMode();
        var failure = new AtomicReference<Throwable>();
        Thread writer = Thread.ofVirtual().start(() -> {
            terminal.writer().write("x".repeat(4 * 1024 * 1024));
            terminal.writer().flush();
        });
        // Parent sends this only after the PTY has output, without draining it. This handshake
        // ensures close competes with actual I/O, not merely a thread scheduled for later.
        if (System.in.read() < 0) throw new AssertionError("missing parent handshake");
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                while (terminal.reader().read(100) != -1) { }
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            }
        });
        var closed = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) Thread.ofVirtual().start(() -> {
            try {
                terminal.close();
            } catch (Throwable problem) {
                failure.compareAndSet(null, problem);
            } finally {
                closed.countDown();
            }
        });
        if (!closed.await(5, TimeUnit.SECONDS)) throw new AssertionError("concurrent close blocked");
        writer.join(2000);
        reader.join(2000);
        if (writer.isAlive() || reader.isAlive()) throw new AssertionError("I/O survived close");
        if (failure.get() != null) throw new AssertionError("close/I/O failed", failure.get());
        terminal.close();
        System.err.println("PASS concurrent Java read/write/close");
    }
}

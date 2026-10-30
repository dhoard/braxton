/* Development-only child: exercises the packaged MVU API under an owned PTY/console. */
package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.app.*;
import io.github.dhoard.braxton.effect.*;
import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.layout.*;
import io.github.dhoard.braxton.mvu.*;
import io.github.dhoard.braxton.style.*;
import io.github.dhoard.braxton.view.*;

import java.io.*;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class TerminalProbe {
    private static final AtomicReference<Consumer<Object>> EMITTER = new AtomicReference<>();
    private static PrintWriter reports;

    private record Message(String command, InputEvent input, Size size) {}

    private record Model(String text, Size size, boolean reopen) {}

    private static Command<Message> report(String line) {
        return Command.task(
                EffectId.of("report-" + line),
                () -> {
                    synchronized (reports) {
                        reports.println(line);
                        reports.flush();
                    }
                    return new Message("ACK", null, null);
                },
                value -> value,
                error -> {
                    throw new IllegalStateException(error);
                });
    }

    private static final class ProbeProgram implements Component<Model, Message, Never> {
        private final boolean second;
        private final String identity;

        ProbeProgram(boolean second, String identity) {
            this.second = second;
            this.identity = identity;
        }

        public Update<Model, Message, Never> init(InitContext facts) {
            var size = new Size(facts.viewport().rows(), facts.viewport().cols());
            return Update.next(
                    new Model("Native 界", size, false),
                    report((second ? "REOPENED " : "READY ") + identity));
        }

        public Update<Model, Message, Never> update(Model model, Message message) {
            if (message.input() != null) {
                String line = message.input() instanceof InputEvent.PasteInput paste
                        ? "PASTE " + paste.text()
                        : message.input() instanceof InputEvent.KeyInput key
                                ? "KEY " + key.key() + " "
                                        + HexFormat.of()
                                                .formatHex(
                                                        key.text().getBytes(StandardCharsets.UTF_8))
                                : "INPUT";
                return Update.next(
                        model,
                        second
                                ? report(line).map(ignored -> new Message("CLOSE", null, null))
                                : report(line));
            }
            if (message.size() != null)
                return Update.next(new Model(model.text(), message.size(), false));
            return switch (message.command()) {
                case "ACK" -> Update.next(model);
                case "SIZE" ->
                        Update.next(
                                model,
                                report("SIZE " + model.size().rows() + " " + model.size().cols()));
                case "WRITE" ->
                        Update.next(new Model("verified", model.size(), false), report("WROTE"));
                case "REFRESH", "REFRESH_SHORT", "REFRESH_WIDE", "REFRESH_WIDE_SHORT" ->
                        Update.next(
                                new Model(message.command(), model.size(), false),
                                report(message.command() + "_READY"));
                case "WRITE_LARGE" ->
                        Update.next(
                                new Model("x".repeat(200000), model.size(), false),
                                report("WROTE_LARGE"));
                case "EXCEPTION" -> throw new IllegalStateException("intentional probe failure");
                case "SHUTDOWN" ->
                        Update.next(
                                model,
                                Command.task(
                                        EffectId.of("shutdown"),
                                        () -> {
                                            System.exit(0);
                                            return message;
                                        },
                                        v -> v,
                                        f -> message));
                case "CLOSE_IDLE" -> Update.next(model, report("WAITING"));
                case "CLOSE" ->
                        Update.next(new Model(model.text(), model.size(), true), Command.quit());
                default -> throw new IllegalArgumentException(message.command());
            };
        }

        public View<Message> view(Model model, ViewContext context) {
            Viewport viewport = context.viewport();
            View<Message> content = model.text().equals("verified")
                    ? Views.column(
                            Views.text("Native 界"),
                            Views.text("verified", Style.NONE.withForeground(new Color(255, 0, 0))))
                    : Views.text(model.text());
            if (model.text().startsWith("REFRESH_WIDE")) {
                boolean wide = model.text().equals("REFRESH_WIDE");
                int cols = viewport.cols();
                var rows = new ArrayList<View<Message>>();
                rows.add(Views.text("w".repeat(Math.max(0, cols - 2)) + (wide ? "界" : "y"),
                        Style.bg(Colors.BLACK).withBold()));
                rows.add(Views.text(wide ? "b".repeat(cols) : "", Style.bg(new Color(255, 0, 0))));
                for (int row = 2; row < viewport.rows() - 1; row++) rows.add(Views.text(""));
                rows.add(Views.text(wide ? "z".repeat(cols) : "bottom"));
                content = Views.column(rows);
            } else if (model.text().startsWith("REFRESH")) {
                boolean full = model.text().equals("REFRESH");
                var rows = new ArrayList<View<Message>>();
                rows.add(Views.text(full
                        ? "REFRESH_FULL" + "x".repeat(viewport.cols() - 14) + "界"
                        : "short界", Style.bg(Colors.BLACK).withBold()));
                rows.add(Views.text(full ? "b".repeat(viewport.cols()) : "",
                        Style.bg(new Color(255, 0, 0))));
                for (int row = 2; row < viewport.rows() - 1; row++) rows.add(Views.text(""));
                rows.add(Views.text(full ? "z".repeat(viewport.cols()) : "bottom"));
                content = Views.column(rows);
            }
            return Views.focus(
                    ElementId.of("input"),
                    true,
                    List.of(),
                    input -> Optional.of(new Message("", input, null)),
                    content);
        }

        public Subscription<Message> subscriptions(Model model) {
            return Subscription.batch(
                    Subscription.source(
                            SubscriptionId.of("control"),
                            "control",
                            emitter -> {
                                EMITTER.set(emitter);
                                return () -> EMITTER.compareAndSet(emitter, null);
                            },
                            value -> (Message) value),
                    Subscription.resize(
                            SubscriptionId.of("size"), size -> new Message("", null, size)));
        }
    }

    private static String releaseIdentity() throws IOException {
        String version = null;
        for (String artifact : List.of("jline-terminal", "jline-terminal-ffm", "jline-native")) {
            var properties = new Properties();
            String resource = "/META-INF/maven/org.jline/" + artifact + "/pom.properties";
            try (var input = org.jline.terminal.Terminal.class.getResourceAsStream(resource)) {
                if (input == null) throw new IOException("missing JLine release metadata: " + artifact);
                properties.load(input);
            }
            String loaded = properties.getProperty("version");
            if (loaded == null || loaded.isBlank() || (version != null && !version.equals(loaded)))
                throw new IOException("missing or misaligned JLine version: " + artifact + " " + loaded);
            version = loaded;
        }
        // RuntimeLoop validates the acquired TerminalExt provider before program initialization.
        return "ffm " + version;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--bootstrap")) {
            var result =
                    Application.run(
                            new Component<Integer, String, Never>() {
                                public Update<Integer, String, Never> init(InitContext facts) {
                                    return Update.next(0, Command.quit());
                                }

                                public Update<Integer, String, Never> update(
                                        Integer model, String message) {
                                    return Update.next(model);
                                }

                                public View<String> view(Integer model, ViewContext context) {
                                    return Views.text("bootstrap");
                                }
                            });
            if (result.failure() != null) result.failure().printStackTrace(System.err);
            System.exit(result.status());
        }
        String identity = releaseIdentity();
        String control = System.getenv("BRAXTON_PROBE_CONTROL");
        try (var channel = control.startsWith("tcp:")
                ? SocketChannel.open(new java.net.InetSocketAddress(
                        "127.0.0.1", Integer.parseInt(control.substring(4))))
                : SocketChannel.open(UnixDomainSocketAddress.of(control))) {
            reports = new PrintWriter(
                    java.nio.channels.Channels.newOutputStream(channel),
                    true,
                    StandardCharsets.UTF_8);
            // Channels' stream wrappers serialize opposite directions; use the socket channel's
            // independent read/write operations so waiting for a command cannot block a report.
            Thread.ofVirtual().start(() -> {
                try {
                    var buffer = java.nio.ByteBuffer.allocate(1024);
                    var text = new StringBuilder();
                    while (channel.read(buffer) >= 0) {
                        buffer.flip();
                        while (buffer.hasRemaining()) {
                            char c = (char) (buffer.get() & 255);
                            if (c == '\n') {
                                Consumer<Object> emitter = EMITTER.get();
                                if (emitter != null)
                                    emitter.accept(new Message(text.toString(), null, null));
                                text.setLength(0);
                            } else text.append(c);
                        }
                        buffer.clear();
                    }
                } catch (IOException closed) {
                    /* Owned IPC closes after the run. */
                }
            });
            var options = RuntimeOptions.defaults()
                    .withTerminal(
                            args.length > 0 && args[0].equals("--plain")
                                    ? new TerminalOptions(false, false, false)
                                    : TerminalOptions.DEFAULT.withMouse(true));
            var first = new ProbeProgram(false, identity);
            var runtime = new RuntimeLoop<Model, Message>(first, options);
            ExitResult result = runtime.run();
            if (result.failure() != null) result.failure().printStackTrace(System.err);
            if (result.ok() && runtime.model().reopen()) {
                result = Application.run(new ProbeProgram(true, identity), options);
            }
            System.exit(result.status());
        }
    }
}

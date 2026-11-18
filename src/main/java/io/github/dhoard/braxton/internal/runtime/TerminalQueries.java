/*
 * Copyright (c) 2026-present Douglas Hoard
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.dhoard.braxton.internal.runtime;

import io.github.dhoard.braxton.app.TerminalQuery;
import io.github.dhoard.braxton.clipboard.ClipboardPath;
import io.github.dhoard.braxton.clipboard.ClipboardSelection;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.TerminalReply;
import io.github.dhoard.braxton.internal.clipboard.Osc52;
import io.github.dhoard.braxton.internal.clipboard.TmuxClipboard;
import io.github.dhoard.braxton.internal.render.AnsiEncoder;
import io.github.dhoard.braxton.mvu.ComponentPath;
import io.github.dhoard.braxton.style.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Issues terminal queries, correlates the replies, and reports one typed answer per query.
 *
 * <p>Answers are bounded on every side. A query is written once; a reply deadline turns silence into
 * {@link TerminalQuery.Reply.Timeout}; at most {@link #DEFAULT_MAX_OUTSTANDING} queries are live; and
 * only one request per reply kind is on the wire, because a terminal's answer carries no request
 * identity. A reply that no live query is waiting for is discarded: it can neither satisfy a
 * different query nor reach the application as input. A query whose kind is already on the wire waits
 * its turn and is sent when that answer arrives or its deadline passes.</p>
 *
 * <p>All correlation state is guarded by one lock, because a deadline fires on the timer thread while
 * a reply arrives on the owner thread. Application mappers are never called under that lock, so an
 * application cannot deadlock the runtime from one.</p>
 *
 * <p>The wire formats are the documented xterm ones: {@code CSI 6 n}, {@code CSI > q},
 * {@code CSI ? Ps $ p}, {@code DCS + q <hex name> ST}, {@code OSC 10|11|12 ; ?} and
 * {@code OSC 52 ; <selection> ; ?}. Nothing is sent that a query did not ask for, and nothing is sent
 * at startup.</p>
 *
 * <p>A clipboard read is answered with the user's own text, so its payload is decoded strictly and
 * bounded by the session's read limit before it becomes a message: a reply that is not base64, is not
 * UTF-8, names another selection or decodes past the limit resolves the query as an answer the
 * application can tell apart from a timeout. The decoder applies the same bound to the framing
 * buffer, so a payload the session would refuse is never buffered in full.</p>
 *
 * @param <Msg> the component message type a query result is mapped to
 */
public final class TerminalQueries<Msg> {

    /** How long a query waits for its answer before reporting a timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);

    /** How many queries may be live - on the wire or waiting for their kind to clear - at once. */
    public static final int DEFAULT_MAX_OUTSTANDING = 16;

    /**
     * One resolved answer, delivered to the component that asked.
     *
     * @param <Msg> the component message type a resolved answer is delivered as
     */
    @FunctionalInterface
    public interface Sink<Msg> {

        /**
         * Delivers one mapped answer on whichever thread resolved the query.
         *
         * @param message the mapped message
         * @param origin the scope that asked, or null for the root
         */
        void deliver(Msg message, ScopeManager.Origin origin);
    }

    /** One live query: on the wire, or waiting for the earlier query of its kind to finish. */
    private static final class Pending<Msg> {
        final EffectId id;
        final TerminalQuery query;
        final Function<TerminalQuery.Reply, Msg> mapper;
        final ScopeManager.Origin origin;
        /** What a sequence waits for when this query is one of its steps. */
        final Runnable finished;

        Timers.Handle timer;

        Pending(
                EffectId id,
                TerminalQuery query,
                Function<TerminalQuery.Reply, Msg> mapper,
                ScopeManager.Origin origin,
                Runnable finished) {
            this.id = id;
            this.query = query;
            this.mapper = mapper;
            this.origin = origin;
            this.finished = finished == null ? () -> {} : finished;
        }
    }

    private final Consumer<String> output;
    private final Timers timers;
    private final Sink<Msg> sink;
    private final Supplier<TerminalQuery.Reply.Profile> presentation;
    private final Consumer<String> diagnostics;
    private final int maxOutstanding;
    private final long timeoutNanos;
    private final int maxClipboardReadBytes;
    private final Object lock = new Object();
    /** Live queries by identity, in submission order. */
    private final Map<EffectId, Pending<Msg>> entries = new LinkedHashMap<>();
    /** The one query per kind that may be on the wire. */
    private final Map<String, Pending<Msg>> wire = new HashMap<>();

    private ScopeManager scopes;
    private BiConsumer<TerminalQuery, TerminalQuery.Reply> observer = (query, answer) -> {};
    private boolean closed;

    /**
     * Creates the query engine with the documented bounds.
     *
     * @param output where request sequences are written
     * @param timers the reply deadlines
     * @param sink where resolved answers are delivered
     * @param presentation the session's presentation, for a query that asks about it
     * @param diagnostics receives internal notes, such as a request that could not be written
     */
    public TerminalQueries(
            Consumer<String> output,
            Timers timers,
            Sink<Msg> sink,
            Supplier<TerminalQuery.Reply.Profile> presentation,
            Consumer<String> diagnostics) {
        this(
                output,
                timers,
                sink,
                presentation,
                diagnostics,
                DEFAULT_MAX_OUTSTANDING,
                DEFAULT_TIMEOUT,
                io.github.dhoard.braxton.clipboard.ClipboardConfig.DEFAULT_MAX_READ_PAYLOAD_BYTES);
    }

    /**
     * Creates the query engine with explicit bounds.
     *
     * @param output where request sequences are written
     * @param timers the reply deadlines
     * @param sink where resolved answers are delivered
     * @param presentation the session's presentation, for a query that asks about it
     * @param diagnostics receives internal notes
     * @param maxOutstanding the bound on live queries; at least one
     * @param timeout how long a query waits for its answer; positive
     */
    public TerminalQueries(
            Consumer<String> output,
            Timers timers,
            Sink<Msg> sink,
            Supplier<TerminalQuery.Reply.Profile> presentation,
            Consumer<String> diagnostics,
            int maxOutstanding,
            Duration timeout) {
        this(
                output,
                timers,
                sink,
                presentation,
                diagnostics,
                maxOutstanding,
                timeout,
                io.github.dhoard.braxton.clipboard.ClipboardConfig.DEFAULT_MAX_READ_PAYLOAD_BYTES);
    }

    /**
     * Creates the query engine with explicit bounds.
     *
     * @param output where request sequences are written
     * @param timers the reply deadlines
     * @param sink where resolved answers are delivered
     * @param presentation the session's presentation, for a query that asks about it
     * @param diagnostics receives internal notes
     * @param maxOutstanding the bound on live queries; at least one
     * @param timeout how long a query waits for its answer; positive
     * @param maxClipboardReadBytes the decoded byte limit for one clipboard read; not negative
     */
    public TerminalQueries(
            Consumer<String> output,
            Timers timers,
            Sink<Msg> sink,
            Supplier<TerminalQuery.Reply.Profile> presentation,
            Consumer<String> diagnostics,
            int maxOutstanding,
            Duration timeout,
            int maxClipboardReadBytes) {
        this.output = java.util.Objects.requireNonNull(output, "output");
        this.timers = java.util.Objects.requireNonNull(timers, "timers");
        this.sink = java.util.Objects.requireNonNull(sink, "sink");
        this.presentation = java.util.Objects.requireNonNull(presentation, "presentation");
        this.diagnostics = diagnostics == null ? message -> {} : diagnostics;
        if (maxOutstanding < 1) {
            throw new IllegalArgumentException("outstanding query bound must be positive");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("query timeout must be positive");
        }
        this.maxOutstanding = maxOutstanding;
        this.timeoutNanos = timeout.toNanos();
        this.maxClipboardReadBytes = Math.max(0, maxClipboardReadBytes);
    }

    /** Binds the scopes a query's origin is captured from. */
    public void bindScopes(ScopeManager scopes) {
        this.scopes = scopes;
    }

    /**
     * Binds an observer of every reply this engine resolves.
     *
     * <p>It runs on the owner thread, before the mapped answer is delivered, and only for a reply the
     * session was waiting for - a timeout is not evidence of anything a terminal said. The runtime uses
     * it to record the session-wide meaning of a capability or background colour reply.</p>
     *
     * @param observer receives the query and its answer
     */
    public void onReply(BiConsumer<TerminalQuery, TerminalQuery.Reply> observer) {
        this.observer = observer == null ? (query, answer) -> {} : observer;
    }

    /**
     * Registers one query and writes it if its kind is free.
     *
     * <p>Registering an identity that is already live replaces it: the earlier query is withdrawn and
     * its answer, if it ever arrives, satisfies nothing.</p>
     *
     * @param id the query's effect identity
     * @param query the question to ask
     * @param mapper maps the answer to the delivered message
     * @param path the structural scope that asked
     */
    public void submit(
            EffectId id, TerminalQuery query, Function<TerminalQuery.Reply, Msg> mapper, ComponentPath path) {
        submit(id, query, mapper, path, null);
    }

    /**
     * Registers one query and reports when its single answer has been handed over.
     *
     * <p>The completion is what a {@link io.github.dhoard.braxton.effect.Command.Sequence} step waits
     * for: it runs after the mapped answer has been delivered to the session, on whichever thread
     * resolved the query. A query that is withdrawn before it is answered completes nothing, because
     * nothing was delivered.</p>
     *
     * @param id the query's effect identity
     * @param query the question to ask
     * @param mapper maps the answer to the delivered message
     * @param path the structural scope that asked
     * @param onDelivered what to run once the answer has been delivered, or null for none
     */
    public void submit(
            EffectId id,
            TerminalQuery query,
            Function<TerminalQuery.Reply, Msg> mapper,
            ComponentPath path,
            Runnable onDelivered) {
        ScopeManager.Origin origin = scopes == null ? null : scopes.capture(path);
        TerminalQuery.Reply immediate;
        Pending<Msg> sending = null;
        String freed;
        synchronized (lock) {
            if (closed) {
                return;
            }
            freed = withdrawn(id);
            if (query instanceof TerminalQuery.Profile) {
                // A session fact, not a terminal question: no bytes, no deadline, no correlation.
                immediate = presentation.get();
            } else if (entries.size() >= maxOutstanding) {
                immediate = new TerminalQuery.Reply.Rejected(query, wire.size());
            } else {
                Pending<Msg> pending = new Pending<>(id, query, mapper, origin, onDelivered);
                entries.put(id, pending);
                immediate = null;
                if (!wire.containsKey(query.kind())) {
                    wire.put(query.kind(), pending);
                    sending = pending;
                }
            }
        }
        if (freed != null && (sending == null || !freed.equals(query.kind()))) {
            advance(freed);
        }
        if (immediate != null) {
            deliver(immediate, mapper, origin);
            if (onDelivered != null) onDelivered.run();
            return;
        }
        if (sending != null) {
            send(sending);
        }
    }

    /**
     * Offers one framed reply to the queries that are waiting for it.
     *
     * <p>Runs on the owner thread. A reply whose kind no live query is waiting for is discarded, and a
     * reply whose payload cannot be believed resolves its query as
     * {@link TerminalQuery.Reply.Malformed}.</p>
     *
     * @param reply the framed reply
     */
    public void accept(TerminalReply reply) {
        if (reply == null) {
            return;
        }
        Pending<Msg> matched;
        TerminalQuery.Reply answer;
        String kind;
        synchronized (lock) {
            if (closed) {
                return;
            }
            kind = kindOf(reply);
            if (kind == null) {
                return;
            }
            matched = wire.get(kind);
            if (matched == null) {
                // Nobody is waiting for this answer of this kind: an unsolicited or late reply.
                return;
            }
            answer = answer(matched.query, reply);
            if (answer == null) {
                return;
            }
            removeLocked(matched);
        }
        observer.accept(matched.query, answer);
        deliver(answer, matched.mapper, matched.origin);
        matched.finished.run();
        advance(kind);
    }

    /** Withdraws one query by identity; a queued successor takes the wire if it owned it. */
    public void cancel(EffectId id) {
        String freed;
        synchronized (lock) {
            freed = withdrawn(id);
        }
        if (freed != null) {
            advance(freed);
        }
    }

    /** Withdraws every query belonging to a removed scope and its descendants. */
    public void cancelScope(ComponentPath path) {
        List<String> freed = new ArrayList<>();
        synchronized (lock) {
            for (Pending<Msg> pending : new ArrayList<>(entries.values())) {
                if (pending.id.path().startsWith(path) && removeLocked(pending) != null) {
                    freed.add(pending.query.kind());
                }
            }
        }
        for (String kind : freed) {
            advance(kind);
        }
    }

    /** Withdraws every query and cancels every deadline; used at shutdown. */
    public void close() {
        synchronized (lock) {
            closed = true;
            for (Pending<Msg> pending : entries.values()) {
                cancelTimer(pending);
            }
            entries.clear();
            wire.clear();
        }
    }

    /** Exposed for the runtime and tests: how many queries are live, queued ones included. */
    public int live() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /** Writes one query's request, resolving it as malformed when the terminal refuses the write. */
    private void send(Pending<Msg> pending) {
        String request = request(pending.query);
        Timers.Handle timer;
        synchronized (lock) {
            if (entries.get(pending.id) != pending || wire.get(pending.query.kind()) != pending) {
                // Withdrawn or superseded between registering and writing.
                return;
            }
            timer = timers.schedule(timeoutNanos, () -> expire(pending));
            pending.timer = timer;
        }
        try {
            output.accept(request);
        } catch (RuntimeException failure) {
            diagnostics.accept(
                    "terminal query could not be written: " + failure.getClass().getSimpleName());
            fail(pending, "request could not be written");
        }
    }

    /** The reply deadline of one query fired. */
    private void expire(Pending<Msg> pending) {
        TerminalQuery.Reply answer;
        synchronized (lock) {
            if (closed || entries.get(pending.id) != pending || wire.get(pending.query.kind()) != pending) {
                // The query was answered, withdrawn, or replaced before its deadline.
                return;
            }
            answer = new TerminalQuery.Reply.Timeout(pending.query);
            removeLocked(pending);
        }
        deliver(answer, pending.mapper, pending.origin);
        pending.finished.run();
        advance(pending.query.kind());
    }

    /** Resolves a query whose request never reached the terminal. */
    private void fail(Pending<Msg> pending, String detail) {
        synchronized (lock) {
            if (entries.get(pending.id) != pending) {
                return;
            }
            removeLocked(pending);
        }
        deliver(new TerminalQuery.Reply.Malformed(pending.query, detail), pending.mapper, pending.origin);
        pending.finished.run();
        advance(pending.query.kind());
    }

    /**
     * Writes the oldest query of {@code kind} waiting for the wire, now that its slot is free.
     *
     * <p>Serialization is what makes correlation honest: two queries of one kind cannot be on the wire
     * together, because their answers would be indistinguishable.</p>
     */
    private void advance(String kind) {
        Pending<Msg> next = null;
        synchronized (lock) {
            if (closed || wire.containsKey(kind)) {
                return;
            }
            for (Pending<Msg> candidate : entries.values()) {
                if (candidate.query.kind().equals(kind)) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                return;
            }
            wire.put(kind, next);
        }
        send(next);
    }

    /** Removes the query registered under {@code id}; the caller advances its kind. */
    private String withdrawn(EffectId id) {
        Pending<Msg> existing = entries.get(id);
        return existing == null ? null : removeLocked(existing);
    }

    /** Removes one live query, cancels its deadline, and frees its wire slot if it held one. */
    private String removeLocked(Pending<Msg> pending) {
        entries.remove(pending.id, pending);
        cancelTimer(pending);
        String kind = pending.query.kind();
        return wire.remove(kind, pending) ? kind : null;
    }

    private static void cancelTimer(Pending<?> pending) {
        if (pending.timer != null) {
            pending.timer.cancel();
            pending.timer = null;
        }
    }

    /** Maps and delivers one answer for a query that is already removed from the registry. */
    private void deliver(
            TerminalQuery.Reply answer, Function<TerminalQuery.Reply, Msg> mapper, ScopeManager.Origin origin) {
        if (mapper == null) {
            return;
        }
        Msg message;
        try {
            message = mapper.apply(answer);
        } catch (RuntimeException failure) {
            // A mapper is application code. Its failure must not lose the query's single answer or
            // leave the runtime holding a half-delivered result, so it is reported and dropped here.
            diagnostics.accept(
                    "terminal query mapper threw " + failure.getClass().getSimpleName());
            return;
        }
        if (message != null) {
            sink.deliver(message, origin);
        }
    }

    /** The request sequence a query writes, or null for a query that never reaches the wire. */
    private static String request(TerminalQuery query) {
        return switch (query) {
            case TerminalQuery.CursorPosition position -> "\u001B[6n";
            case TerminalQuery.Identity identity -> "\u001B[>q";
            case TerminalQuery.Mode mode -> "\u001B[?" + mode.mode() + "$p";
            case TerminalQuery.Termcap termcap -> "\u001BP+q" + hex(termcap.name()) + "\u001B\\";
            case TerminalQuery.ColorReport color -> "\u001B]" + oscCode(color.target()) + ";?\u0007";
            case TerminalQuery.ClipboardRead clipboard -> clipboardRequest(clipboard);
            case TerminalQuery.ProgramStatus programStatus -> AnsiEncoder.QUERY_PROGRAM_STATUS;
            case TerminalQuery.Profile profile -> null;
        };
    }

    /**
     * The request for one clipboard read, wrapped the way the configured transport requires.
     *
     * <p>A passthrough session wraps the question exactly as it wraps a write: tmux has to forward
     * the query to the terminal that owns the selection, and only the outer terminal can answer it.
     * Every other transport writes the plain sequence.</p>
     */
    private static String clipboardRequest(TerminalQuery.ClipboardRead read) {
        String sequence = Osc52.request(read.selection());
        return read.path() == ClipboardPath.TMUX_PASSTHROUGH ? TmuxClipboard.passthrough(sequence) : sequence;
    }

    /** The OSC command that reports one of the terminal's three colours. */
    private static int oscCode(TerminalQuery.Target target) {
        return switch (target) {
            case FOREGROUND -> 10;
            case BACKGROUND -> 11;
            case CURSOR -> 12;
        };
    }

    /** The colour an OSC command number reports, or null for any other command. */
    private static TerminalQuery.Target colorTarget(int code) {
        return switch (code) {
            case 10 -> TerminalQuery.Target.FOREGROUND;
            case 11 -> TerminalQuery.Target.BACKGROUND;
            case 12 -> TerminalQuery.Target.CURSOR;
            default -> null;
        };
    }

    /** Upper-case hexadecimal, the encoding XTGETTCAP uses for names. */
    private static String hex(String text) {
        StringBuilder hex = new StringBuilder(text.length() * 2);
        for (int index = 0; index < text.length(); index++) {
            int value = text.charAt(index);
            hex.append(Character.toUpperCase(Character.forDigit((value >> 4) & 0xF, 16)));
            hex.append(Character.toUpperCase(Character.forDigit(value & 0xF, 16)));
        }
        return hex.toString();
    }

    /** The text a hexadecimal run names, or null when it is not valid hexadecimal. */
    static String unhex(String text) {
        if (text == null || text.length() % 2 != 0) {
            return null;
        }
        StringBuilder decoded = new StringBuilder(text.length() / 2);
        for (int index = 0; index < text.length(); index += 2) {
            int high = Character.digit(text.charAt(index), 16);
            int low = Character.digit(text.charAt(index + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            decoded.append((char) ((high << 4) | low));
        }
        return decoded.toString();
    }

    /**
     * The kind key a framed reply answers, or null when no query of this library asks that question.
     *
     * <p>Deciding the kind from the reply alone is what keeps correlation honest: the answer names the
     * mode, capability, colour or selection it is about, or it is one of the two forms that carry no
     * identity at all and can only be matched by being the one outstanding query of its kind.</p>
     */
    static String kindOf(TerminalReply reply) {
        if (reply instanceof TerminalReply.Control control) {
            if (control.finalByte() == 'y') {
                String parameters = control.parameters();
                int separator = parameters.indexOf(';');
                if (parameters.startsWith("?") && parameters.endsWith("$") && separator > 1) {
                    String mode = parameters.substring(1, separator);
                    return isDecimal(mode) ? "mode:" + parseInt(mode) : null;
                }
                return null;
            }
            if (control.finalByte() == 'R') {
                return control.parameters().indexOf(';') > 0 ? "cursor-position" : null;
            }
            return null;
        }
        if (reply instanceof TerminalReply.OperatingSystem osc) {
            if (osc.code() == Osc52.CODE) {
                // A clipboard reply names the selection it answers for, so it is attributable even
                // when nothing was asked of that selection - and then discarded.
                return clipboardKind(Osc52.selectionField(osc.data()));
            }
            if (osc.code() == AnsiEncoder.PROGRAM_STATUS_OSC) {
                // The command number is the answer's identity. A payload that is not the detection
                // form is answered as malformed rather than silently waited out, because the terminal
                // did say something about the question this library asked.
                return "program-status";
            }
            TerminalQuery.Target target = colorTarget(osc.code());
            return target == null ? null : "color:" + target.name().toLowerCase(Locale.ROOT);
        }
        if (reply instanceof TerminalReply.Overlong overlong) {
            // The payload was dropped, but the head it kept names the selection it answered for.
            return overlong.code() == Osc52.CODE ? clipboardKind(Osc52.selectionField(overlong.head())) : null;
        }
        TerminalReply.DeviceControl dcs = (TerminalReply.DeviceControl) reply;
        if (dcs.header().equals(">|")) {
            return "identity";
        }
        if (dcs.header().endsWith("+r")) {
            String name = unhex(firstField(dcs.data(), '='));
            if (name == null || !TerminalQuery.Termcap.ALLOWED.contains(name)) {
                // A capability this library never asks for, or a name that is not hexadecimal.
                return null;
            }
            return "termcap:" + name;
        }
        return null;
    }

    /** The kind key one clipboard reply answers, or null when its selection field names none. */
    private static String clipboardKind(String selectionField) {
        ClipboardSelection selection = selectionField == null || selectionField.isEmpty()
                ? ClipboardSelection.SYSTEM
                : ClipboardSelection.of(selectionField.charAt(0));
        return selection == null ? null : "clipboard:" + selection.code();
    }

    /** The answer one framed reply gives to a known query, or null when the reply is not usable. */
    private TerminalQuery.Reply answer(TerminalQuery query, TerminalReply reply) {
        return switch (query) {
            case TerminalQuery.CursorPosition position -> cursorPosition(position, reply);
            case TerminalQuery.Identity identity -> identity(identity, reply);
            case TerminalQuery.Mode mode -> modeReport(mode, reply);
            case TerminalQuery.Termcap termcap -> capability(termcap, reply);
            case TerminalQuery.ColorReport color -> colorValue(color, reply);
            case TerminalQuery.ClipboardRead read -> clipboard(read, reply);
            case TerminalQuery.ProgramStatus programStatus -> programStatus(programStatus, reply);
            case TerminalQuery.Profile profile -> null;
        };
    }

    /**
     * One clipboard reply, decoded strictly and bounded by the session's read limit.
     *
     * <p>Every outcome is an answer the application can act on: the text, a refusal, an answer too
     * large for the limit, or an answer that could not be believed. None of a malformed payload is
     * passed on as text.</p>
     */
    private TerminalQuery.Reply clipboard(TerminalQuery.ClipboardRead query, TerminalReply reply) {
        if (reply instanceof TerminalReply.Overlong overlong) {
            return new TerminalQuery.Reply.Oversize(query, maxClipboardReadBytes);
        }
        if (!(reply instanceof TerminalReply.OperatingSystem osc) || osc.code() != Osc52.CODE) {
            return new TerminalQuery.Reply.Malformed(query, "not a clipboard report");
        }
        return switch (Osc52.decode(query.selection(), osc.data(), maxClipboardReadBytes)) {
            case Osc52.Decoded.Text text ->
                new TerminalQuery.Reply.Clipboard(query.selection(), text.text(), text.byteLength());
            case Osc52.Decoded.Refused refused -> new TerminalQuery.Reply.Unsupported(query);
            case Osc52.Decoded.Oversize oversize -> new TerminalQuery.Reply.Oversize(query, oversize.limitBytes());
            case Osc52.Decoded.Invalid invalid -> new TerminalQuery.Reply.Malformed(query, invalid.detail());
        };
    }

    /**
     * What a terminal's program-status feature detection accepts.
     *
     * <p>The answer is {@code ?} followed by optional {@code :state=...}/{@code :kinds=...} pairs. A
     * pair that is not well formed is skipped and the rest is still read, exactly as the protocol
     * requires; a list the terminal leaves out becomes the protocol's own default set; and a name this
     * library has never heard of is passed through, because a later revision adds names by listing
     * them here.</p>
     */
    private static TerminalQuery.Reply programStatus(TerminalQuery query, TerminalReply reply) {
        if (!(reply instanceof TerminalReply.OperatingSystem osc) || osc.code() != AnsiEncoder.PROGRAM_STATUS_OSC) {
            return new TerminalQuery.Reply.Malformed(query, "not a program-status report");
        }
        String data = osc.data().strip();
        if (!data.startsWith("?")) {
            return new TerminalQuery.Reply.Malformed(query, "the answer does not start with the detection marker");
        }
        Set<String> states = null;
        Set<String> kinds = null;
        for (String pair : data.substring(1).split(":", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = pair.substring(0, separator).strip();
            Set<String> names = names(pair.substring(separator + 1));
            if (key.equals("states")) {
                states = names.isEmpty() ? null : names;
            } else if (key.equals("kinds")) {
                kinds = names.isEmpty() ? null : names;
            }
        }
        return new TerminalQuery.Reply.ProgramStatus(states, kinds);
    }

    /** The well-formed names in one comma-separated list, in the order the terminal wrote them. */
    private static Set<String> names(String list) {
        Set<String> names = new LinkedHashSet<>();
        for (String field : list.split(",", -1)) {
            String name = field.strip();
            if (protocolName(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /** Whether {@code name} is one character of the protocol's value set, repeated. */
    private static boolean protocolName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_'
                    || character == '.'
                    || character == '+'
                    || character == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static TerminalQuery.Reply cursorPosition(TerminalQuery query, TerminalReply reply) {
        if (!(reply instanceof TerminalReply.Control control) || control.finalByte() != 'R') {
            return new TerminalQuery.Reply.Malformed(query, "not a cursor position report");
        }
        String[] fields = control.parameters().split(";", -1);
        if (fields.length != 2 || !isDecimal(fields[0]) || !isDecimal(fields[1])) {
            return new TerminalQuery.Reply.Malformed(query, "cursor position fields are not decimal");
        }
        int row = parseInt(fields[0]);
        int column = parseInt(fields[1]);
        if (row < 1 || column < 1) {
            // The terminal counts rows and columns from one, so a zero names no cell.
            return new TerminalQuery.Reply.Malformed(query, "cursor position is not one-based");
        }
        return new TerminalQuery.Reply.CursorPosition(row - 1, column - 1);
    }

    private static TerminalQuery.Reply identity(TerminalQuery query, TerminalReply reply) {
        if (reply instanceof TerminalReply.DeviceControl dcs) {
            return new TerminalQuery.Reply.Identity(dcs.data());
        }
        return new TerminalQuery.Reply.Malformed(query, "not a terminal version report");
    }

    private static TerminalQuery.Reply modeReport(TerminalQuery query, TerminalReply reply) {
        if (!(reply instanceof TerminalReply.Control control) || control.finalByte() != 'y') {
            return new TerminalQuery.Reply.Malformed(query, "not a mode report");
        }
        String parameters = control.parameters();
        if (!parameters.startsWith("?") || !parameters.endsWith("$")) {
            return new TerminalQuery.Reply.Malformed(query, "mode report framing is wrong");
        }
        String[] fields = parameters.substring(1, parameters.length() - 1).split(";", -1);
        if (fields.length != 2 || !isDecimal(fields[0]) || !isDecimal(fields[1])) {
            return new TerminalQuery.Reply.Malformed(query, "mode report fields are not decimal");
        }
        int mode = parseInt(fields[0]);
        int value = parseInt(fields[1]);
        if (value < 0 || value > 4) {
            return new TerminalQuery.Reply.Malformed(query, "mode report value " + value + " is not documented");
        }
        if (mode != ((TerminalQuery.Mode) query).mode()) {
            return new TerminalQuery.Reply.Malformed(query, "mode report names mode " + mode);
        }
        return new TerminalQuery.Reply.Mode(mode, TerminalQuery.Setting.of(value));
    }

    private static TerminalQuery.Reply capability(TerminalQuery query, TerminalReply reply) {
        if (!(reply instanceof TerminalReply.DeviceControl dcs)) {
            return new TerminalQuery.Reply.Malformed(query, "not a capability report");
        }
        TerminalQuery.Termcap asked = (TerminalQuery.Termcap) query;
        String header = dcs.header();
        boolean found = header.startsWith("1");
        boolean missing = header.startsWith("0");
        if (!(found || missing) || !header.endsWith("+r")) {
            return new TerminalQuery.Reply.Malformed(query, "capability report framing is wrong");
        }
        for (String field : dcs.data().split(";", -1)) {
            int separator = field.indexOf('=');
            String name = unhex(separator < 0 ? field : field.substring(0, separator));
            if (name == null) {
                return new TerminalQuery.Reply.Malformed(query, "capability name is not hexadecimal");
            }
            if (!name.equals(asked.name())) {
                continue;
            }
            if (missing) {
                // The terminal understood the question and does not have the capability.
                return new TerminalQuery.Reply.Unsupported(query);
            }
            String value = separator < 0 ? "" : unhex(field.substring(separator + 1));
            if (value == null) {
                return new TerminalQuery.Reply.Malformed(query, "capability value is not hexadecimal");
            }
            return new TerminalQuery.Reply.Capability(name, value);
        }
        return new TerminalQuery.Reply.Malformed(query, "reply does not name the capability asked for");
    }

    private static TerminalQuery.Reply colorValue(TerminalQuery query, TerminalReply reply) {
        if (!(reply instanceof TerminalReply.OperatingSystem osc)) {
            return new TerminalQuery.Reply.Malformed(query, "not a colour report");
        }
        TerminalQuery.ColorReport asked = (TerminalQuery.ColorReport) query;
        if (colorTarget(osc.code()) != asked.target()) {
            return new TerminalQuery.Reply.Malformed(query, "colour report names another colour");
        }
        String data = osc.data().strip();
        if (data.equals("?")) {
            // The documented answer of a terminal that cannot report the colour.
            return new TerminalQuery.Reply.Unsupported(query);
        }
        Color color = parseColor(data);
        return color == null
                ? new TerminalQuery.Reply.Malformed(query, "colour is not an X11 rgb specification")
                : new TerminalQuery.Reply.ColorValue(asked.target(), color);
    }

    /**
     * One X11 colour specification, normalized to eight bits per channel.
     *
     * <p>The documented forms are {@code rgb:r/g/b} with one to four hexadecimal digits per channel,
     * and the {@code #} forms with three, six or twelve digits. A channel shorter than four digits is
     * scaled, so {@code rgb:f/0/0} is the same colour as {@code #ff0000}; {@code rgba:} is accepted
     * with its alpha ignored, because the colour channels are what a terminal reports.</p>
     *
     * @param text the specification
     * @return the colour, or null when the text is not one of those forms
     */
    public static Color parseColor(String text) {
        if (text == null) {
            return null;
        }
        String value = text.strip();
        if (value.startsWith("rgb:")) {
            return parseRgb(value.substring(4));
        }
        if (value.startsWith("rgba:")) {
            String[] channels = value.substring(5).split("/", -1);
            if (channels.length != 4) {
                return null;
            }
            return parseRgb(channels[0] + "/" + channels[1] + "/" + channels[2]);
        }
        if (value.startsWith("#")) {
            return parseHex(value.substring(1));
        }
        return null;
    }

    /** {@code r/g/b}, one to four hex digits each, scaled to eight bits. */
    private static Color parseRgb(String channels) {
        String[] parts = channels.split("/", -1);
        if (parts.length != 3) {
            return null;
        }
        int[] scaled = new int[3];
        for (int index = 0; index < 3; index++) {
            Integer value = parseHexValue(parts[index]);
            if (value == null || parts[index].length() > 4) {
                return null;
            }
            scaled[index] = scale(value, parts[index].length());
        }
        return new Color(scaled[0], scaled[1], scaled[2]);
    }

    /** {@code rgb}, {@code rrggbb} or {@code rrrrggggbbbb}, in hexadecimal. */
    private static Color parseHex(String digits) {
        if (digits.length() != 3 && digits.length() != 6 && digits.length() != 12) {
            return null;
        }
        Integer value = parseHexValue(digits);
        if (value == null) {
            return null;
        }
        int width = digits.length() / 3;
        int[] scaled = new int[3];
        for (int index = 0; index < 3; index++) {
            String part = digits.substring(index * width, (index + 1) * width);
            scaled[index] = scale(Integer.parseInt(part, 16), width);
        }
        return new Color(scaled[0], scaled[1], scaled[2]);
    }

    /** The value of a nonempty hexadecimal run, or null when it holds any other character. */
    private static Integer parseHexValue(String text) {
        if (text.isEmpty()) {
            return null;
        }
        int value = 0;
        for (int index = 0; index < text.length(); index++) {
            int nibble = Character.digit(text.charAt(index), 16);
            if (nibble < 0) {
                return null;
            }
            value = (value << 4) | nibble;
        }
        return value;
    }

    /** Scales a channel of {@code digits} hexadecimal digits to 0..255, rounding to nearest. */
    private static int scale(int value, int digits) {
        int maximum = (1 << (4 * digits)) - 1;
        return (value * 255 + maximum / 2) / maximum;
    }

    /** The text before the first {@code separator}, or the whole text when there is none. */
    private static String firstField(String text, char separator) {
        int index = text.indexOf(separator);
        return index < 0 ? text : text.substring(0, index);
    }

    private static boolean isDecimal(String field) {
        if (field.isEmpty()) {
            return false;
        }
        for (int index = 0; index < field.length(); index++) {
            char digit = field.charAt(index);
            if (digit < '0' || digit > '9') {
                return false;
            }
        }
        return true;
    }

    /** A decimal field as an int, saturated rather than wrapped when the digits are absurd. */
    private static int parseInt(String field) {
        if (field.length() > 9) {
            return Integer.MAX_VALUE;
        }
        return Integer.parseInt(field);
    }
}

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

package examples.parity;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.ExitResult;
import io.github.dhoard.braxton.clipboard.ClipboardReadResult;
import io.github.dhoard.braxton.clipboard.ClipboardResult;
import io.github.dhoard.braxton.clipboard.ClipboardSelection;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.effect.EffectId;
import io.github.dhoard.braxton.input.KeyAction;
import io.github.dhoard.braxton.input.KeyMap;
import io.github.dhoard.braxton.mvu.Component;
import io.github.dhoard.braxton.mvu.InitContext;
import io.github.dhoard.braxton.mvu.Never;
import io.github.dhoard.braxton.mvu.Update;
import io.github.dhoard.braxton.mvu.ViewContext;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;
import io.github.dhoard.braxton.widget.Help;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Phase 19 parity demo: the terminal's clipboards, read and written on request.
 *
 * <p>Every clipboard action is a key, and every outcome is painted as the session actually observed it.
 * A copy reports the transport it used and whether the text was stored, because OSC 52 has no
 * acknowledgement: the demo never claims the terminal accepted anything. A read reports the text the
 * terminal sent, or the reason there is none - {@code refused}, {@code timeout}, {@code invalid},
 * {@code oversize}, {@code disabled} or {@code rejected}. A terminal that ignores the question shows a
 * timeout, not an empty clipboard.</p>
 *
 * <ul>
 *   <li>{@code s} and {@code p} copy the sample to the system and primary selections.</li>
 *   <li>{@code c} and {@code v} ask the terminal for the system and primary selections.</li>
 *   <li>{@code i} reads the session's in-process buffer, which is what a widget's paste receives and
 *       which never involves the terminal.</li>
 * </ul>
 *
 * <p>The four status rows are pinned to one row each so a wrap cannot move what a verifier reads. The
 * {@code text=} row shows what the terminal reported, with control characters replaced: the value is
 * the user's own clipboard, so it is displayed as data and never written back as a sequence.</p>
 */
public final class ClipboardDemo {

    /** The status row carrying the last copy's outcome. */
    public static final int COPY_ROW = 0;

    /** The status row carrying the last terminal read's outcome. */
    public static final int READ_ROW = 1;

    /** The status row carrying the text the last successful read reported. */
    public static final int TEXT_ROW = 2;

    /** The status row carrying the in-process buffer's length. */
    public static final int INTERNAL_ROW = 3;

    /** The static sample row, which is what the two copy actions write. */
    public static final int SAMPLE_ROW = 4;

    /** The text every copy action writes. */
    public static final String SAMPLE = "braxton clipboard sample";

    /** How much reported text the {@code text=} row shows. */
    private static final int TEXT_ROW_LIMIT = 32;

    private static final Style STATUS = Style.NONE.withBold();

    private ClipboardDemo() {}

    /** Every message the scene handles. */
    public sealed interface Msg {

        /**
         * A key asked for one clipboard to be written.
         *
         * @param selection the selection to write
         */
        record Copy(ClipboardSelection selection) implements Msg {

            public Copy {
                Objects.requireNonNull(selection, "selection");
            }
        }

        /**
         * A key asked the terminal what one of its clipboards holds.
         *
         * @param selection the selection to read
         */
        record Read(ClipboardSelection selection) implements Msg {

            public Read {
                Objects.requireNonNull(selection, "selection");
            }
        }

        /** A key asked for the in-process buffer, which needs no terminal. */
        record ReadInternal() implements Msg {}

        /**
         * A copy finished.
         *
         * @param selection the selection that was written
         * @param result what the session observed
         */
        record Copied(ClipboardSelection selection, ClipboardResult result) implements Msg {

            public Copied {
                Objects.requireNonNull(selection, "selection");
                Objects.requireNonNull(result, "result");
            }
        }

        /**
         * A terminal read finished.
         *
         * @param result what the terminal answered, or why there is no answer
         */
        record Answered(ClipboardReadResult result) implements Msg {

            public Answered {
                Objects.requireNonNull(result, "result");
            }
        }

        /**
         * The in-process buffer was read.
         *
         * @param text the stored text
         */
        record Internal(String text) implements Msg {

            /**
             * Treats missing text as empty.
             */
            public Internal {
                text = text == null ? "" : text;
            }
        }

        /** Ends the session. */
        record Quit() implements Msg {}
    }

    /**
     * The scene state.
     *
     * @param copy the last copy outcome, {@code idle} before the first one; the row prefixes it
     * @param read the last terminal read outcome, {@code idle} before the first one; the row prefixes
     *     it
     * @param text the text the last successful read reported
     * @param internal the UTF-8 length of the in-process buffer, or -1 when it has not been read
     */
    public record Model(String copy, String read, String text, int internal) {

        /** Substitutes the starting state for missing values. */
        public Model {
            copy = copy == null ? "idle" : copy;
            read = read == null ? "idle" : read;
            text = text == null ? "" : text;
        }

        /** The state the scene starts in: nothing copied, nothing read. */
        public static Model initial() {
            return new Model("idle", "idle", "", -1);
        }

        Model withCopy(ClipboardSelection selection, ClipboardResult result) {
            String path = result.path().name().toLowerCase(Locale.ROOT);
            return new Model(
                    selection.token()
                            + " path=" + path
                            + " bytes=" + result.byteLength()
                            + " stored=" + onOff(result.storedInternally())
                            + " truncated=" + onOff(result.truncated())
                            + " dedup=" + onOff(result.deduplicated()),
                    read,
                    text,
                    internal);
        }

        Model withRead(ClipboardReadResult result) {
            return new Model(
                    copy, result.selection().token() + " " + outcome(result), displayable(result.text()), internal);
        }

        Model withInternal(String value) {
            return new Model(copy, read, text, value.getBytes(StandardCharsets.UTF_8).length);
        }

        /** One read outcome, as a single token. */
        static String outcome(ClipboardReadResult result) {
            return switch (result) {
                case ClipboardReadResult.Text text -> "ok bytes=" + text.byteLength();
                case ClipboardReadResult.Refused refused -> "refused";
                case ClipboardReadResult.Timeout timeout -> "timeout";
                case ClipboardReadResult.Invalid invalid -> "invalid";
                case ClipboardReadResult.Oversize oversize -> "oversize limit=" + oversize.limitBytes();
                case ClipboardReadResult.Disabled disabled -> "disabled";
                case ClipboardReadResult.Rejected rejected -> "rejected outstanding=" + rejected.outstanding();
            };
        }

        /** {@code on}/{@code off}, the spelling every status row uses for a boolean. */
        static String onOff(boolean value) {
            return value ? "on" : "off";
        }

        /**
         * Reported text as one row can hold it.
         *
         * <p>A clipboard holds anything, including escape sequences, so the value is filtered to
         * printable code points before it reaches a row: what the terminal reported is displayed, never
         * replayed.</p>
         */
        static String displayable(String value) {
            StringBuilder shown = new StringBuilder();
            int printed = 0;
            for (int index = 0; index < value.length() && printed < TEXT_ROW_LIMIT; ) {
                char first = value.charAt(index);
                int codePoint = value.codePointAt(index);
                // codePointAt returns the lone surrogate itself when a surrogate has no pair, and a
                // surrogate is not a character any row can paint.
                boolean lone = Character.isSurrogate(first) && Character.charCount(codePoint) == 1;
                index += Character.charCount(codePoint);
                boolean printable = codePoint >= 0x20
                        && codePoint != 0x7F
                        && !(codePoint >= 0x80 && codePoint <= 0x9F)
                        && !lone
                        && Character.isDefined(codePoint);
                shown.appendCodePoint(printable ? codePoint : '?');
                printed++;
            }
            if (value.codePointCount(0, value.length()) > printed) {
                shown.append('\u2026');
            }
            return shown.toString();
        }
    }

    /**
     * The scene's actions for one state.
     *
     * @param model the scene state the actions are built for
     * @return the map, in help order
     */
    public static KeyMap<Msg> keys(Model model) {
        return KeyMap.of(
                KeyMap.group(
                        "Copy",
                        KeyAction.<Msg>of(
                                "copy-system", new Msg.Copy(ClipboardSelection.SYSTEM), "s", "copy: system", "s"),
                        KeyAction.<Msg>of(
                                "copy-primary", new Msg.Copy(ClipboardSelection.PRIMARY), "p", "copy: primary", "p")),
                KeyMap.group(
                        "Read the terminal",
                        KeyAction.<Msg>of(
                                "read-system", new Msg.Read(ClipboardSelection.SYSTEM), "c", "read: system", "c"),
                        KeyAction.<Msg>of(
                                "read-primary", new Msg.Read(ClipboardSelection.PRIMARY), "v", "read: primary", "v")),
                KeyMap.group(
                        "Session",
                        KeyAction.<Msg>of("read-internal", new Msg.ReadInternal(), "i", "read: internal buffer", "i"),
                        KeyAction.<Msg>of("quit", new Msg.Quit(), "esc", "quit", "escape", "ctrl+c")));
    }

    /** Runs the demo against the owned terminal. */
    public static ExitResult run() {
        return Application.run(new Scene());
    }

    /**
     * Runs the demo.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        ExitResult result = run();
        if (result.failure() != null) {
            result.failure().printStackTrace(System.err);
        }
        System.exit(result.status());
    }

    /** A scene whose whole model is what the clipboard reports said. */
    public static final class Scene implements Component<Model, Msg, Never> {

        /**
         * Writes nothing: a session that asked the terminal for its clipboard at startup would be
         * taking the user's data without being asked. The demo's first clipboard action is a keypress.
         */
        @Override
        public Update<Model, Msg, Never> init(InitContext context) {
            return Update.next(Model.initial());
        }

        @Override
        public Update<Model, Msg, Never> update(Model model, Msg message) {
            return switch (message) {
                case Msg.Copy copy ->
                    Update.next(
                            model,
                            Command.copy(copy.selection(), SAMPLE, result -> new Msg.Copied(copy.selection(), result)));
                case Msg.Read read ->
                    Update.next(
                            model,
                            Command.readTerminalClipboard(
                                    EffectId.of("read-" + read.selection().token()),
                                    read.selection(),
                                    Msg.Answered::new));
                case Msg.ReadInternal internal -> Update.next(model, Command.readClipboard(Msg.Internal::new));
                case Msg.Copied copied -> Update.next(model.withCopy(copied.selection(), copied.result()));
                case Msg.Answered result -> Update.next(model.withRead(result.result()));
                case Msg.Internal internal -> Update.next(model.withInternal(internal.text()));
                case Msg.Quit quit -> Update.next(model, Command.quit());
            };
        }

        @Override
        public View<Msg> view(Model model, ViewContext context) {
            int cols = context.viewport().cols();
            KeyMap<Msg> keys = keys(model);
            List<View<Msg>> rows = new ArrayList<>(8);
            rows.add(Views.fixed(1, Views.text(copyRow(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(readRow(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(textRow(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(internalRow(model), STATUS)));
            rows.add(Views.fixed(1, Views.text(sample())));
            rows.add(Views.gap(1));
            rows.add(Help.view(keys, false, cols));
            return Views.onKeys(keys.bindings(), Views.column(rows));
        }

        /** The copy row: which selection was written, the transport it used, and what it kept. */
        private static String copyRow(Model model) {
            return "copy=" + model.copy();
        }

        /** The read row: which selection was asked, and what came back. */
        private static String readRow(Model model) {
            return "read=" + model.read();
        }

        /** The text row: the text the terminal reported, or a dash when there is none. */
        private static String textRow(Model model) {
            return "text=" + (model.text().isEmpty() ? "-" : model.text());
        }

        /** The internal row: what a widget's paste would receive. */
        private static String internalRow(Model model) {
            return "internal=" + (model.internal() < 0 ? "unread" : "bytes=" + model.internal());
        }

        /** The sample row: the text the copy actions write. */
        private static String sample() {
            return "sample=" + SAMPLE;
        }
    }
}

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

package io.github.dhoard.braxton.internal.clipboard;

import io.github.dhoard.braxton.clipboard.ClipboardSelection;
import io.github.dhoard.braxton.internal.text.Graphemes;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * OSC 52 clipboard encoding and decoding.
 *
 * <p>An OSC 52 write is {@code ESC ] 52 ; <selection> ; <base64 UTF-8> BEL}. The BEL terminator is
 * used because every mainstream OSC 52 implementation accepts it and it avoids embedding another
 * ESC byte in a tmux passthrough payload. A read is the same command with {@code ?} in place of the
 * payload; the terminal answers with the payload it holds.</p>
 *
 * <p>There is no acknowledgement in the protocol. A caller can know that a sequence was written,
 * never that a terminal or tmux actually placed the text on the system clipboard.</p>
 *
 * <p>Decoding is strict in both steps, because a reply is the one place terminal bytes become text an
 * application may paste. Only the basic base64 alphabet with correct padding is accepted, and the
 * decoded bytes must be well-formed UTF-8 with no malformed or unmappable sequence. A reply that
 * fails either check is reported as invalid and none of it reaches the application.</p>
 */
public final class Osc52 {

    /** The default clipboard selection: the system clipboard. */
    public static final String CLIPBOARD_SELECTION = "c";

    /** The OSC command number that reads and writes a clipboard. */
    public static final int CODE = 52;

    /** The payload a read sends in place of the text. */
    private static final String READ_MARKER = "?";

    private static final char ESC = '\u001b';
    private static final char BEL = '\u0007';
    private static final String PREFIX = ESC + "]52;";

    /**
     * How far past the byte limit the truncating path segments the source: enough to reach the end
     * of the cluster that straddles the limit in text a terminal holds.
     */
    private static final int CLUSTER_LOOKAHEAD = 64;

    private Osc52() {}

    /**
     * Encodes {@code text} for the system clipboard without a size limit.
     *
     * @return the complete OSC 52 sequence, or an empty string when there is no text (including
     *     {@code null}), because an empty payload would clear the user's clipboard
     */
    public static String encode(String text) {
        // Nothing to encode means nothing to write. An empty payload would otherwise reach the
        // terminal as a sequence that clears the clipboard.
        return text == null || text.isEmpty()
                ? ""
                : encode(text, CLIPBOARD_SELECTION, Integer.MAX_VALUE).sequence();
    }

    /**
     * Encodes {@code text} for the system clipboard, truncating it on a grapheme-cluster boundary
     * when it exceeds {@code maxBytes}.
     */
    public static Encoded encode(String text, int maxBytes) {
        return encode(text, CLIPBOARD_SELECTION, maxBytes);
    }

    /**
     * Encodes {@code text} for the requested selection, truncating it on a grapheme-cluster boundary
     * when it exceeds {@code maxBytes}.
     *
     * @param selection the selection to write; a missing selection writes the system clipboard
     * @param text the text to encode; {@code null} is treated as empty
     * @param maxBytes maximum UTF-8 payload size
     */
    public static Encoded encode(ClipboardSelection selection, String text, int maxBytes) {
        return encode(text, selection == null ? CLIPBOARD_SELECTION : String.valueOf(selection.code()), maxBytes);
    }

    /**
     * The request sequence for one selection's clipboard: {@code ESC ] 52 ; <selection> ; ? BEL}.
     *
     * <p>The terminal answers with the payload it holds, or not at all. Nothing else is written, so a
     * session that never reads a clipboard writes nothing.</p>
     *
     * @param selection the selection to ask about; a missing selection reads the system clipboard
     */
    public static String request(ClipboardSelection selection) {
        return PREFIX + (selection == null ? CLIPBOARD_SELECTION : String.valueOf(selection.code())) + ";" + READ_MARKER
                + BEL;
    }

    /**
     * Decodes one OSC 52 reply payload.
     *
     * @param selection the selection the session asked about
     * @param data the reply's data, exactly as the decoder framed it: {@code <selection>;<payload>}
     * @param maxBytes the largest decoded payload accepted
     * @return the decoded outcome, never null
     */
    public static Decoded decode(ClipboardSelection selection, String data, int maxBytes) {
        ClipboardSelection asked = selection == null ? ClipboardSelection.SYSTEM : selection;
        int limit = Math.max(0, maxBytes);
        String reply = data == null ? "" : data;
        int separator = reply.indexOf(';');
        if (separator < 0) {
            return new Decoded.Invalid("the reply does not name a selection");
        }
        String name = reply.substring(0, separator);
        if (name.length() > 1) {
            return new Decoded.Invalid("the reply names more than one selection");
        }
        // An omitted selection is the protocol's default, which is the system clipboard. It matches
        // only a session that asked about the system clipboard.
        ClipboardSelection reported =
                name.isEmpty() ? ClipboardSelection.SYSTEM : ClipboardSelection.of(name.charAt(0));
        if (reported == null) {
            return new Decoded.Invalid("the reply names no known selection");
        }
        if (reported != asked) {
            return new Decoded.Invalid("the reply names the " + reported.token() + " selection");
        }
        String payload = reply.substring(separator + 1);
        if (payload.equals(READ_MARKER)) {
            // The terminal understood the question and cannot answer it.
            return new Decoded.Refused();
        }
        if (payload.isEmpty()) {
            // An empty selection: a successful read that reported no text.
            return new Decoded.Text("", 0);
        }
        if (payload.length() > encodedLength(limit)) {
            // Refused before allocating anything for a payload that cannot fit the limit anyway.
            return new Decoded.Oversize(limit);
        }
        if (payload.length() % 4 != 0) {
            // Base64 is written in whole four-character groups, padding included. Java's decoder
            // would accept a short final group, but every conforming encoder - and so every
            // terminal - pads, and a payload that is not what the protocol defines is not text this
            // library can vouch for.
            return new Decoded.Invalid("the payload is not padded base64");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException notBase64) {
            return new Decoded.Invalid("the payload is not base64");
        }
        if (bytes.length > limit) {
            // The encoded bound is a whole group of three, so a payload that fits the bound can still
            // decode to more bytes than the limit allows.
            return new Decoded.Oversize(limit);
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer text = decoder.decode(ByteBuffer.wrap(bytes));
            return new Decoded.Text(text.toString(), bytes.length);
        } catch (CharacterCodingException notUtf8) {
            return new Decoded.Invalid("the payload is not UTF-8");
        }
    }

    /**
     * The selection field of a reply's data: everything before the first {@code ;}.
     *
     * <p>It is how a framed reply is attributed to the selection it answers for, without decoding the
     * payload - including a reply the decoder refused to buffer in full.</p>
     *
     * @param data the reply's data or its leading characters
     * @return the selection field, or an empty string when the data has no separator
     */
    public static String selectionField(String data) {
        String reply = data == null ? "" : data;
        int separator = reply.indexOf(';');
        return separator < 0 ? "" : reply.substring(0, separator);
    }

    /**
     * The largest payload that can encode {@code maxBytes} decoded bytes, excluding the selection
     * field.
     *
     * <p>Base64 encodes three bytes as four characters, so the bound is one whole group of three
     * rounded up. The bound is exact in one direction only: any payload no longer than this may
     * still decode to more than {@code maxBytes} bytes, which the decoded-length check catches.</p>
     *
     * @param maxBytes the decoded byte limit
     * @return the encoded character bound
     */
    public static int encodedLength(int maxBytes) {
        int limit = Math.max(0, maxBytes);
        return 4 * ((limit + 2) / 3);
    }

    /**
     * Encodes {@code text} for the requested selection.
     *
     * @param text clipboard text; {@code null} is treated as empty
     * @param selection OSC 52 selection ({@code c}, {@code p}, {@code s}, {@code 0}–{@code 7}); an
     *     unknown value falls back to {@code c} so the sequence cannot be injected through it
     * @param maxBytes maximum UTF-8 payload size; a value below zero is treated as zero
     */
    public static Encoded encode(String text, String selection, int maxBytes) {
        String safeText = text == null ? "" : text;
        String safeSelection = validSelection(selection);
        int limit = Math.max(0, maxBytes);
        String limited = limitUtf8(safeText, limit);
        boolean truncated = limited.length() < safeText.length();
        byte[] bytes = limited.getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        String sequence = PREFIX + safeSelection + ";" + base64 + BEL;
        return new Encoded(sequence, limited, bytes.length, truncated);
    }

    /**
     * The encoded payload. {@code byteLength} is the UTF-8 size of {@code text} after any
     * truncation, and {@code truncated} reports whether the configured limit removed text.
     * @param sequence the complete OSC 52 sequence
     * @param text the payload text after any truncation
     * @param byteLength the UTF-8 length of the payload text
     * @param truncated whether a limit removed text
     */
    public record Encoded(String sequence, String text, int byteLength, boolean truncated) {
        public Encoded {
            sequence = sequence == null ? "" : sequence;
            text = text == null ? "" : text;
            byteLength = Math.max(0, byteLength);
        }
    }

    /**
     * What one OSC 52 reply payload said.
     *
     * <p>Every outcome is a fact about the reply rather than an exception: a terminal that declines and
     * one that answers with text it cannot vouch for are both ordinary answers to the same
     * question.</p>
     */
    public sealed interface Decoded {

        /**
         * The text the terminal reported.
         *
         * @param text the decoded text, empty when the selection is empty
         * @param byteLength the UTF-8 length of {@code text}
         */
        record Text(String text, int byteLength) implements Decoded {

            /**
             * Treats missing text as empty.
             */
            public Text {
                text = text == null ? "" : text;
                byteLength = Math.max(0, byteLength);
            }
        }

        /** The terminal understood the question and cannot answer it. */
        record Refused() implements Decoded {}

        /**
         * The payload could not be believed.
         *
         * @param detail what was wrong with it
         */
        record Invalid(String detail) implements Decoded {

            /**
             * Treats a missing detail as empty.
             */
            public Invalid {
                detail = detail == null ? "" : detail;
            }
        }

        /**
         * The payload decoded to more than the limit allows.
         *
         * @param limitBytes the decoded byte limit that was exceeded
         */
        record Oversize(int limitBytes) implements Decoded {

            /**
             * Rejects a negative limit.
             */
            public Oversize {
                limitBytes = Math.max(0, limitBytes);
            }
        }
    }

    private static String validSelection(String selection) {
        if (selection == null || selection.isEmpty()) {
            return CLIPBOARD_SELECTION;
        }
        if (selection.length() == 1) {
            char c = selection.charAt(0);
            if (c == 'c' || c == 'p' || c == 's' || (c >= '0' && c <= '7')) {
                return selection;
            }
        }
        return CLIPBOARD_SELECTION;
    }

    /**
     * Truncates to at most {@code maxBytes} UTF-8 bytes without splitting a grapheme cluster, so a
     * combining accent, a ZWJ emoji sequence, or a flag never reaches the clipboard half-formed.
     */
    static String limitUtf8(String text, int maxBytes) {
        if (text.isEmpty() || maxBytes <= 0) {
            return "";
        }
        if (utf8Length(text, maxBytes) <= maxBytes) {
            return text;
        }
        return prefixOfCompleteClusters(text, maxBytes);
    }

    /**
     * The longest whole-cluster prefix of {@code text} that fits {@code maxBytes} UTF-8 bytes.
     *
     * <p>Only a window of the source is segmented, and only the byte count is measured. Segmenting
     * is the costly half of truncation - the pinned break iterator copies and indexes everything it
     * is given, and a cluster list holds every cluster at once - while the answer depends only on
     * the text up to the cluster that straddles the limit, so a copy far larger than the limit costs
     * no more than the limit does.</p>
     *
     * <p>UTF-8 is never shorter than the UTF-16 length that produces it, so a window of the limit
     * plus {@link #CLUSTER_LOOKAHEAD} units either overflows the limit inside the window or holds
     * the whole source. A window that stops short of the source with every one of its clusters
     * fitting is the one case that settles nothing, and it segments the whole source then: the
     * answer is never guessed at.</p>
     */
    private static String prefixOfCompleteClusters(String text, int maxBytes) {
        int length = text.length();
        int window = (int) Math.min(length, (long) maxBytes + CLUSTER_LOOKAHEAD);
        while (true) {
            int end = settledPrefixEnd(text, window, maxBytes);
            if (end >= 0) {
                return text.substring(0, end);
            }
            // The end of the source always settles it, so this runs at most twice.
            window = length;
        }
    }

    /**
     * The length of the longest whole-cluster prefix among the first {@code window} units of
     * {@code text} that fits {@code maxBytes} UTF-8 bytes, or {@code -1} when the window stops short
     * of the source with every one of its clusters fitting.
     *
     * <p>A window settles every boundary before its last cluster: a break, once decided, does not
     * change because more text follows. The last cluster of a window that stops short can be a
     * fragment the source continues, and that doubt never reaches the answer - a fragment that
     * already exceeds the limit means the cluster it belongs to does too, and a fragment that fits
     * is reported as unsettled instead of being accepted.</p>
     */
    private static int settledPrefixEnd(String text, int window, int maxBytes) {
        int used = 0;
        int end = 0;
        for (String cluster : Graphemes.iterate(window == text.length() ? text : text.substring(0, window))) {
            int size = utf8Length(cluster, Integer.MAX_VALUE);
            if (used + size > maxBytes) {
                return end;
            }
            used += size;
            end += cluster.length();
        }
        return window == text.length() ? end : -1;
    }

    /**
     * UTF-8 bytes in {@code text}, or the running count once it passes {@code stopAfter}.
     *
     * <p>The count is the encoder's own, without the array it would encode into: an ASCII unit is
     * one byte, the two- and three-byte forms follow, a surrogate pair is four bytes, and an
     * unpaired half - which cannot be encoded at all - is the single replacement byte the encoder
     * writes for it.</p>
     */
    private static int utf8Length(CharSequence text, int stopAfter) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isSurrogate(c)) {
                bytes += 1;
            } else {
                bytes += 3;
            }
            if (bytes > stopAfter) {
                return bytes;
            }
        }
        return bytes;
    }
}

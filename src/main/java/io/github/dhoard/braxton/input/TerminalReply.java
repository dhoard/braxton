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

package io.github.dhoard.braxton.input;

/**
 * One sequence a terminal sent in reply to a query, framed but not yet interpreted.
 *
 * <p>A reply is not a keystroke, and Braxton never treats one as input: the decoder recognizes the
 * three framed forms a terminal answers a question with and hands the bytes to the runtime, which
 * decides whether the session is waiting for them. What a reply <em>means</em> depends on the
 * question: {@code CSI 1;2 R} is a cursor position, and the same bytes are also the shape of one
 * modified function key, so the decoder deliberately does not interpret. A reply nobody asked for is
 * discarded, which is what keeps an unrequested answer from being delivered as something else.</p>
 *
 * <p>Framing is bounded. The decoder caps a reply's data and abandons an unterminated one at the
 * escape timeout, so a terminal that starts a reply and stops half-way cannot stall the reader or
 * grow its buffer without limit.</p>
 *
 * @see InputEvent.TerminalReplyInput
 */
public sealed interface TerminalReply {

    /**
     * A control sequence: {@code CSI <parameters> <intermediate> <final>}.
     *
     * @param parameters the parameter and intermediate bytes as written, in {@code 0x30-0x3F} and
     *     {@code 0x20-0x2F}, or empty
     * @param finalByte the final byte that ended the sequence, in {@code 0x40-0x7E}
     */
    record Control(String parameters, char finalByte) implements TerminalReply {

        /**
         * Treats missing parameters as none.
         */
        public Control {
            parameters = parameters == null ? "" : parameters;
        }
    }

    /**
     * An operating system command: {@code OSC <code> ; <data>}, ended by {@code BEL} or {@code ST}.
     *
     * @param code the numeric command the terminal answered
     * @param data the command's data, with the separator already removed
     */
    record OperatingSystem(int code, String data) implements TerminalReply {

        /**
         * Treats missing data as none.
         */
        public OperatingSystem {
            data = data == null ? "" : data;
        }
    }

    /**
     * A device control string: {@code DCS <header> <data> ST}.
     *
     * <p>The header is everything between the introducer and the data - parameters, an intermediate
     * and a final byte for a request that answers with data - written as the terminal wrote it, so a
     * consumer can tell {@code 1+r} from {@code >|}. The data is everything before the terminator.</p>
     *
     * @param header the parameter, intermediate and final bytes of the sequence
     * @param data the string payload that follows the header
     */
    record DeviceControl(String header, String data) implements TerminalReply {

        /**
         * Treats missing header and data as none.
         */
        public DeviceControl {
            header = header == null ? "" : header;
            data = data == null ? "" : data;
        }
    }

    /**
     * An operating system command whose data outgrew the decoder's bound.
     *
     * <p>The sequence was still consumed through its terminator, so nothing of it can arrive as
     * typed text; only the payload was dropped. The frame is reported rather than silently
     * discarded because "the terminal answered and the answer was too large" is a different fact
     * from "the terminal never answered", and a consumer that a reply is owed can say which
     * happened.</p>
     *
     * <p>{@code head} holds the first characters of the dropped data. That is enough to attribute
     * the frame - an OSC 52 reply begins with the selection it answers for - without keeping any
     * part of a payload the session refused.</p>
     *
     * @param code the numeric command the terminal answered
     * @param head the first characters of the data the decoder kept
     * @param limit the bound the data exceeded, in characters
     */
    record Overlong(int code, String head, int limit) implements TerminalReply {

        /**
         * Treats missing head as empty and a negative bound as zero.
         */
        public Overlong {
            head = head == null ? "" : head;
            limit = Math.max(0, limit);
        }
    }
}

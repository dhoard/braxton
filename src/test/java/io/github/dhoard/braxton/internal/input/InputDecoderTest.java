/*
 * Copyright (c) 2026-present Doug Hoard
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

package io.github.dhoard.braxton.internal.input;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.dhoard.braxton.input.InputEvent;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.input.Modifiers;

/** Terminal bytes become normalized events exactly once. */
class InputDecoderTest {

    /** Serves one byte per read so UTF-8 and escape sequences are genuinely split. */
    private static final class ByteAtATime extends InputStream {
        private final byte[] data;
        private int index;

        ByteAtATime(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return index < data.length ? data[index++] & 0xFF : -1;
        }

        @Override
        public int available() {
            return index < data.length ? 1 : 0;
        }
    }

    private static Optional<InputEvent> read(byte[] bytes) throws IOException {
        return new InputDecoder(new ByteAtATime(bytes)).read();
    }

    private static Optional<InputEvent> read(String text) throws IOException {
        return read(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void printableAsciiCarriesItsTextOnce() throws IOException {
        InputEvent event = read("a").orElseThrow();
        assertThat(event).isInstanceOf(InputEvent.KeyInput.class);
        InputEvent.KeyInput key = (InputEvent.KeyInput) event;
        assertThat(key.key()).isEqualTo(Key.of("a"));
        assertThat(key.text()).isEqualTo("a");
    }

    @Test
    void altModifiedMultibyteTextIsStillDecodedAsUtf8() throws IOException {
        // ESC followed by a printable key is Alt+key. A non-ASCII character must still be decoded
        // as one UTF-8 scalar instead of the Latin-1 character its lead byte names.
        InputEvent.KeyInput event = (InputEvent.KeyInput) read("\u001B\u00E9").orElseThrow();
        assertThat(event.key()).isEqualTo(Key.of("\u00E9", Modifiers.ALT));
        assertThat(event.text()).isEqualTo("\u00E9");
    }

    @Test
    void spaceIsAKeyWithItsTextAndNeverABlankIdentity() throws IOException {
        InputEvent.KeyInput space = (InputEvent.KeyInput) read(" ").orElseThrow();
        assertThat(space.key()).isEqualTo(Key.SPACE);
        assertThat(space.text()).isEqualTo(" ");

        InputEvent.KeyInput altSpace = (InputEvent.KeyInput) read("\u001B ").orElseThrow();
        assertThat(altSpace.key()).isEqualTo(Key.of("space", Modifiers.ALT));
        assertThat(altSpace.text()).isEqualTo(" ");

        InputEvent.KeyInput ideographic = (InputEvent.KeyInput) read("\u3000").orElseThrow();
        assertThat(ideographic.key()).isEqualTo(Key.SPACE);
        assertThat(ideographic.text()).isEqualTo("\u3000");
    }

    @Test
    void keyNamesAreDerivedWithoutBlankIdentities() {
        assertThat(InputDecoder.keyForText(" ")).isEqualTo(Key.SPACE);
        assertThat(InputDecoder.keyForText("\u3000")).isEqualTo(Key.SPACE);
        assertThat(InputDecoder.keyForText("A")).isEqualTo(Key.of("a"));
        assertThat(InputDecoder.keyForText("\u65E5")).isEqualTo(Key.of("\u65E5"));
    }

    @Test
    void shiftIsRepresentedByTheUppercaseText() throws IOException {
        InputEvent.KeyInput key = (InputEvent.KeyInput) read("A").orElseThrow();
        assertThat(key.text()).isEqualTo("A");
        assertThat(key.key().name()).isEqualTo("a");
    }

    @Test
    void controlKeysAreNamed() throws IOException {
        assertThat(((InputEvent.KeyInput) read(new byte[] {3}).orElseThrow()).key())
                .isEqualTo(Key.of("c", Modifiers.CTRL));
        assertThat(((InputEvent.KeyInput) read(new byte[] {13}).orElseThrow()).key())
                .isEqualTo(Key.ENTER);
        assertThat(((InputEvent.KeyInput) read(new byte[] {10}).orElseThrow()).key())
                .isEqualTo(Key.of("j", Modifiers.CTRL));
        assertThat(((InputEvent.KeyInput) read(new byte[] {9}).orElseThrow()).key())
                .isEqualTo(Key.TAB);
        assertThat(((InputEvent.KeyInput) read(new byte[] {127}).orElseThrow()).key())
                .isEqualTo(Key.BACKSPACE);
    }

    @Test
    void csiArrowsAndEditingKeysAreNormalized() throws IOException {
        assertThat(((InputEvent.KeyInput) read("\u001B[A").orElseThrow()).key()).isEqualTo(Key.UP);
        assertThat(((InputEvent.KeyInput) read("\u001B[B").orElseThrow()).key()).isEqualTo(Key.DOWN);
        assertThat(((InputEvent.KeyInput) read("\u001B[3~").orElseThrow()).key())
                .isEqualTo(Key.DELETE);
        assertThat(((InputEvent.KeyInput) read("\u001B[5~").orElseThrow()).key())
                .isEqualTo(Key.PAGE_UP);
        assertThat(((InputEvent.KeyInput) read("\u001B[Z").orElseThrow()).key())
                .isEqualTo(Key.of("tab", Modifiers.SHIFT));
    }

    @Test
    void altCharacterKeepsTheModifierAndTheText() throws IOException {
        InputEvent.KeyInput key = (InputEvent.KeyInput) read("\u001Bx").orElseThrow();
        assertThat(key.key()).isEqualTo(Key.of("x", Modifiers.ALT));
        assertThat(key.text()).isEqualTo("x");
    }

    @Test
    void altBackspaceNeverCarriesAControlCharacterInItsNameOrText() throws IOException {
        InputEvent.KeyInput key = (InputEvent.KeyInput) read("\u001B\u007F").orElseThrow();
        assertThat(key.key()).isEqualTo(Key.BACKSPACE.with(Modifiers.ALT));
        assertThat(key.text()).isEmpty();
    }

    @Test
    void altWithAControlScalarIsNotAPrintableGesture() throws IOException {
        InputEvent.KeyInput key = (InputEvent.KeyInput)
                read(new byte[] {0x1B, (byte) 0xC2, (byte) 0x9B}).orElseThrow();
        assertThat(key.key()).isEqualTo(Key.ESCAPE);
        assertThat(key.text()).isEmpty();
    }

    @Test
    void aLoneEscapeIsEscape() throws IOException {
        assertThat(((InputEvent.KeyInput) read("\u001B").orElseThrow()).key()).isEqualTo(Key.ESCAPE);
    }

    @Test
    void aSecondEscapeIsDeliveredAsItsOwnEvent() throws IOException {
        InputDecoder decoder = new InputDecoder(new ByteAtATime(new byte[] {0x1B, 0x1B}));
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).key()).isEqualTo(Key.ESCAPE);
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).key()).isEqualTo(Key.ESCAPE);
        assertThat(decoder.read()).isEmpty();
    }

    @Test
    void multiByteUtf8SurvivesByteAtATimeReads() throws IOException {
        InputEvent.KeyInput key = (InputEvent.KeyInput) read("\u65E5").orElseThrow();
        assertThat(key.text()).isEqualTo("\u65E5");
    }

    @Test
    void aTruncatedUtf8SequenceDoesNotSwallowTheFollowingCharacter() throws IOException {
        Optional<InputEvent> event = read(new byte[] {(byte) 0xE3, 'A'});
        assertThat(event).contains(InputEvent.KeyInput.of(Key.of("a"), "A"));
    }

    @Test
    void anInvalidUtf8LeadByteDoesNotSwallowTheFollowingCharacter() throws IOException {
        InputDecoder stray = new InputDecoder(new ByteAtATime(new byte[] {(byte) 0x80, 'a'}));
        assertThat(((InputEvent.KeyInput) stray.read().orElseThrow()).text()).isEqualTo("a");

        InputDecoder overlong = new InputDecoder(new ByteAtATime(new byte[] {(byte) 0xC0, 'b'}));
        assertThat(((InputEvent.KeyInput) overlong.read().orElseThrow()).text()).isEqualTo("b");
    }

    /**
     * A well-formed lead can still start an ill-formed sequence: an overlong form, a surrogate, or a
     * code point past U+10FFFF. The JDK decoder turns those into replacement characters, which would
     * reach the application as printable text and be inserted into a draft; the decoder drops them
     * exactly as it drops a stray continuation byte or an overlong lead.
     */
    @Test
    void anOverlongOrSurrogateSequenceIsDroppedInsteadOfBecomingAReplacementCharacter()
            throws IOException {
        InputDecoder overlong = new InputDecoder(
                new ByteAtATime(new byte[] {(byte) 0xE0, (byte) 0x80, (byte) 0x80}), 5, 1024, 5L);
        InputDecoder surrogate = new InputDecoder(
                new ByteAtATime(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80}), 5, 1024, 5L);
        InputDecoder outOfRange = new InputDecoder(new ByteAtATime(
                new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80}), 5, 1024, 5L);

        assertThat(overlong.read()).isEmpty();
        assertThat(surrogate.read()).isEmpty();
        assertThat(outOfRange.read()).isEmpty();
    }

    /** Dropping an ill-formed sequence must not swallow the character that follows it. */
    @Test
    void theCharacterAfterAnIllFormedSequenceIsStillDecoded() throws IOException {
        InputDecoder decoder = new InputDecoder(new ByteAtATime(
                new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80, 'x'}), 5, 1024, 5L);
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text()).isEqualTo("x");
    }

    /** U+FFFD is a character a terminal may really send; only ill-formed bytes are dropped. */
    @Test
    void aLiteralReplacementCharacterIsStillDecoded() throws IOException {
        InputEvent.KeyInput key = (InputEvent.KeyInput) read("\uFFFD").orElseThrow();
        assertThat(key.text()).isEqualTo("\uFFFD");
        assertThat(key.key()).isEqualTo(Key.of("\uFFFD"));
    }

    @Test
    void bracketedPasteBecomesOneAtomicEvent() throws IOException {
        InputEvent event = read("\u001B[200~hello\nworld\u001B[201~").orElseThrow();
        assertThat(event).isInstanceOf(InputEvent.PasteInput.class);
        assertThat(((InputEvent.PasteInput) event).text()).isEqualTo("hello\nworld");
    }

    @Test
    void inputAfterAPasteIsLeftInTheStream() throws IOException {
        ByteAtATime stream = new ByteAtATime(
                "\u001B[200~hi\u001B[201~x".getBytes(StandardCharsets.UTF_8));
        InputDecoder decoder = new InputDecoder(stream);
        assertThat(decoder.read().orElseThrow()).isInstanceOf(InputEvent.PasteInput.class);
        InputEvent.KeyInput key = (InputEvent.KeyInput) decoder.read().orElseThrow();
        assertThat(key.text()).isEqualTo("x");
    }

    @Test
    void aPasteLimitNeverLeavesABrokenCharacter() throws IOException {
        String body = "\uD83D\uDE00ab";
        ByteAtATime stream = new ByteAtATime(
                ("\u001B[200~" + body + "\u001B[201~").getBytes(StandardCharsets.UTF_8));
        InputDecoder decoder = new InputDecoder(stream, 1, 3, 4096);
        InputEvent.PasteInput paste = (InputEvent.PasteInput) decoder.read().orElseThrow();
        assertThat(paste.text()).isEmpty();

        ByteAtATime roomy = new ByteAtATime(
                ("\u001B[200~ab" + body + "\u001B[201~").getBytes(StandardCharsets.UTF_8));
        InputEvent.PasteInput limited =
                (InputEvent.PasteInput) new InputDecoder(roomy, 1, 2, 4096).read().orElseThrow();
        assertThat(limited.text()).isEqualTo("ab");
    }

    @Test
    void wheelEventsAreMappedAndOtherMouseEventsIgnored() throws IOException {
        assertThat(((InputEvent.KeyInput) read("\u001B[<64;10;5M").orElseThrow()).key())
                .isEqualTo(Key.WHEEL_UP);
        assertThat(read("\u001B[<0;10;5M")).isEmpty();
    }

    @Test
    void ignoredMouseEventsDoNotLookLikeEndOfInput() throws IOException {
        ByteAtATime stream = new ByteAtATime(
                "\u001B[<0;10;5M\u001B[<0;10;5ma".getBytes(StandardCharsets.UTF_8));
        InputDecoder decoder = new InputDecoder(stream);
        InputEvent.KeyInput key = (InputEvent.KeyInput) decoder.read().orElseThrow();
        assertThat(key.text()).isEqualTo("a");
        assertThat(decoder.read()).isEmpty();
    }

    @Test
    void anOverLongCsiIsSkippedWholeInsteadOfLeakingItsTailAsText() throws IOException {
        // Past the length cap the decoder stopped consuming, so the sequence's remaining parameter
        // bytes and its final byte arrived as ordinary text and were inserted into the draft.
        InputDecoder decoder = new InputDecoder(new ByteAtATime(
                ("\u001B[" + "1".repeat(70) + "mX").getBytes(StandardCharsets.UTF_8)));
        InputEvent event = decoder.read().orElseThrow();
        assertThat(((InputEvent.KeyInput) event).text()).isEqualTo("X");
        assertThat(decoder.read()).isEmpty();
    }

    @Test
    void skippedSequencesLeaveTheFollowingInputIntact() throws IOException {
        // An unsupported CSI with modifiers, an unknown numeric CSI, an X10 click whose three
        // payload bytes must be consumed, and an SGR click are all skipped; the printable input,
        // the paste and the text after it must arrive unchanged and in order.
        InputDecoder decoder = new InputDecoder(new ByteAtATime(
                "\u001B[1;5A\u001B[99~\u001B[M!!!\u001B[<0;10;5Mx\u001B[200~p\u001B[201~y"
                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text()).isEqualTo("x");
        assertThat(decoder.read().orElseThrow()).isEqualTo(new InputEvent.PasteInput("p"));
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text()).isEqualTo("y");
        assertThat(decoder.read()).isEmpty();
    }

    @Test
    void endOfInputIsEmpty() throws IOException {
        assertThat(new InputDecoder(new ByteArrayInputStream(new byte[0])).read()).isEmpty();
    }
}

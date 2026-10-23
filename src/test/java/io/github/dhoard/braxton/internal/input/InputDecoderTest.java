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
@org.junit.jupiter.api.extension.ExtendWith(io.github.dhoard.braxton.internal.input.TestReaders.class)
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
        return new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(bytes))).read();
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
    void carriageReturnIsEnterAndLineFeedKeepsTheDocumentedCtrlJEncoding() throws IOException {
        // The documented limitation: "terminals that encode Enter as LF cannot be distinguished
        // from Ctrl+J; LF is treated as a newline". CR is the enter key; LF is Ctrl+J, which the
        // editor turns into a newline rather than a submission.
        InputEvent.KeyInput enter = (InputEvent.KeyInput) read("\r").orElseThrow();
        assertThat(enter.key()).isEqualTo(Key.ENTER);
        assertThat(enter.text()).isEmpty();
        InputEvent.KeyInput lineFeed = (InputEvent.KeyInput) read("\n").orElseThrow();
        assertThat(lineFeed.key()).isEqualTo(Key.of("j", Modifiers.CTRL));
        assertThat(lineFeed.text()).isEmpty();
        assertThat(io.github.dhoard.braxton.widget.Editor.actionFor(lineFeed,
                io.github.dhoard.braxton.widget.EditorOptions.defaults()))
                .contains(new io.github.dhoard.braxton.widget.EditorAction.Newline());
        assertThat(io.github.dhoard.braxton.widget.Editor.actionFor(enter,
                io.github.dhoard.braxton.widget.EditorOptions.defaults()))
                .as("only CR submits")
                .contains(new io.github.dhoard.braxton.widget.EditorAction.Submit());
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

    /**
     * A control byte after ESC is a terminal's Alt-modified gesture: ESC DEL is Alt+Backspace, and an
     * xterm-family terminal whose Meta key sends ESC encodes Alt+Enter and Alt+Tab as ESC CR and ESC
     * TAB. The pair used to be consumed and reported as a bare Escape, so the key the user pressed
     * never arrived and a key they never pressed did; in an application that binds Escape, two
     * Alt+Enter submissions became a two-Escape cancel.
     */
    @Test
    void aControlAfterEscapeIsThatKeyWithAlt() throws IOException {
        InputEvent.KeyInput altEnter = (InputEvent.KeyInput) read("\u001B\r").orElseThrow();
        assertThat(altEnter.key()).isEqualTo(Key.parse("alt+enter"));
        assertThat(altEnter.text()).isEmpty();

        assertThat(((InputEvent.KeyInput) read("\u001B\t").orElseThrow()).key())
                .isEqualTo(Key.parse("alt+tab"));
        assertThat(read("\u001B\u0008").orElseThrow())
                .as("ESC BS names the same gesture as the ESC DEL pair")
                .isEqualTo(read("\u001B\u007F").orElseThrow());
        // A control byte that already names a Ctrl gesture keeps it: ESC ETX is Alt plus Ctrl+C, not
        // the plain Alt+C the ESC c pair reports.
        assertThat(((InputEvent.KeyInput) read("\u001B\u0003").orElseThrow()).key())
                .isEqualTo(Key.parse("alt+ctrl+c"));
    }

    /**
     * The rule covers every C0 control, not only the common three: each ESC pair names the key the
     * bare control byte names and adds ALT to it, never a control character as a name or as text, and
     * the keystroke typed after the pair is still decoded.
     */
    @Test
    void everyControlAfterEscapeKeepsItsGestureAndTheKeyAfterIt() throws IOException {
        for (int control = 0x00; control <= 0x1F; control++) {
            if (control == 0x1B) {
                continue; // two Escape presses stay two separate gestures
            }
            InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(new byte[] {0x1B, (byte) control, 'x'})));
            InputEvent.KeyInput gesture = (InputEvent.KeyInput) decoder.read().orElseThrow();

            assertThat(gesture.key()).as("ESC plus 0x%02X is not a bare Escape", control)
                    .isNotEqualTo(Key.ESCAPE);
            assertThat(gesture.key().name().chars().allMatch(c -> !Character.isISOControl(c)))
                    .as("ESC plus 0x%02X names no control character", control).isTrue();
            assertThat(gesture.text()).as("ESC plus 0x%02X carries no control text", control)
                    .isEmpty();
            InputEvent.KeyInput bare =
                    (InputEvent.KeyInput) read(new byte[] {(byte) control}).orElseThrow();
            assertThat(gesture.key().name())
                    .as("ESC plus 0x%02X names the key the bare byte names", control)
                    .isEqualTo(bare.key().name());
            assertThat(gesture.key().modifiers().alt())
                    .as("ESC plus 0x%02X is Alt-modified", control).isTrue();
            assertThat(gesture.key().modifiers().ctrl())
                    .as("ESC plus 0x%02X keeps the Ctrl the bare byte names", control)
                    .isEqualTo(bare.key().modifiers().ctrl());

            assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text())
                    .as("the key after ESC plus 0x%02X", control).isEqualTo("x");
            assertThat(decoder.read()).as("nothing is left after ESC plus 0x%02X", control)
                    .isEmpty();
        }
    }

    /**
     * Two Alt+Enter presses are two Enter presses, never the two Escape presses that a running turn's
     * cancel window counts.
     */
    @Test
    void twoAltEnterPressesAreNeverTwoEscapePresses() throws IOException {
        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(new byte[] {0x1B, 0x0D, 0x1B, 0x0D})));
        for (int press = 0; press < 2; press++) {
            assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).key())
                    .as("press %d", press).isEqualTo(Key.parse("alt+enter"));
        }
        assertThat(decoder.read()).isEmpty();
    }

    @Test
    void aLoneEscapeIsEscape() throws IOException {
        assertThat(((InputEvent.KeyInput) read("\u001B").orElseThrow()).key()).isEqualTo(Key.ESCAPE);
    }

    @Test
    void aSecondEscapeIsDeliveredAsItsOwnEvent() throws IOException {
        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(new byte[] {0x1B, 0x1B})));
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
        InputDecoder decoder = new InputDecoder(TestReaders.utf8(new ByteAtATime(new byte[] {(byte) 0xE3, 'A'})));
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text()).isEqualTo("\uFFFD");
        assertThat(decoder.read()).contains(InputEvent.KeyInput.of(Key.of("a"), "A"));
    }

    @Test
    void anInvalidUtf8LeadByteDoesNotSwallowTheFollowingCharacter() throws IOException {
        InputDecoder stray = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(new byte[] {(byte) 0x80, 'a'})));
        assertThat(((InputEvent.KeyInput) stray.read().orElseThrow()).text()).isEqualTo("\uFFFD");
        assertThat(((InputEvent.KeyInput) stray.read().orElseThrow()).text()).isEqualTo("a");

        InputDecoder overlong = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(new byte[] {(byte) 0xC0, 'b'})));
        assertThat(((InputEvent.KeyInput) overlong.read().orElseThrow()).text()).isEqualTo("\uFFFD");
        assertThat(((InputEvent.KeyInput) overlong.read().orElseThrow()).text()).isEqualTo("b");
    }

    /**
     * A C1 control (U+0080-U+009F) reaches the decoder as the two-byte UTF-8 scalar {@code C2 80} to
     * {@code C2 9F}. It is no more printable than a C0 control, so it names no gesture and carries no
     * text: like an unknown escape it is skipped inside the call. Naming a key after it is rejected by
     * {@link Key}, and the input thread reports any decoder failure as the end of the run.
     */
    @Test
    void aC1ControlScalarIsSkippedRatherThanBecomingAKey() throws IOException {
        for (int control = 0x80; control <= 0x9F; control++) {
            InputEvent event = read(new byte[] {(byte) 0xC2, (byte) control, 'x'}).orElseThrow();
            assertThat(event).isInstanceOf(InputEvent.KeyInput.class);
            assertThat(((InputEvent.KeyInput) event).key()).isEqualTo(Key.of("x"));
        }
    }

    /** Skipping a C1 scalar must not end the stream or swallow the keystroke after it. */
    @Test
    void aC1ControlScalarDoesNotEndTheStream() throws IOException {
        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(
                new byte[] {'a', (byte) 0xC2, (byte) 0x85, 'b'})));
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text()).isEqualTo("a");
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text()).isEqualTo("b");
        assertThat(decoder.read()).isEmpty();
    }

    /**
     * A well-formed lead can still start an ill-formed sequence: an overlong form, a surrogate, or a
     * code point past U+10FFFF. The JDK decoder turns those into replacement characters, which would
     * reach the application as printable text and be inserted into a draft; the decoder drops them
     * exactly as it drops a stray continuation byte or an overlong lead.
     */
    @Test
    void malformedSequencesFollowJLineReplacementDecoding()
            throws IOException {
        InputDecoder overlong = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(new byte[] {(byte) 0xE0, (byte) 0x80, (byte) 0x80})), new io.github.dhoard.braxton.input.InputOptions(java.time.Duration.ofMillis(5),64,1024,java.time.Duration.ofMillis(5L),java.time.Duration.ofMillis(100)));
        InputDecoder surrogate = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80})), new io.github.dhoard.braxton.input.InputOptions(java.time.Duration.ofMillis(5),64,1024,java.time.Duration.ofMillis(5L),java.time.Duration.ofMillis(100)));
        InputDecoder outOfRange = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(
                new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80})), new io.github.dhoard.braxton.input.InputOptions(java.time.Duration.ofMillis(5),64,1024,java.time.Duration.ofMillis(5L),java.time.Duration.ofMillis(100)));

        for (InputDecoder decoder : java.util.List.of(overlong, surrogate, outOfRange)) {
            int replacements = 0;
            for (Optional<InputEvent> event; (event = decoder.read()).isPresent();) {
                assertThat(((InputEvent.KeyInput) event.get()).text()).isEqualTo("\uFFFD");
                replacements++;
            }
            assertThat(replacements).isBetween(1,4);
        }
    }

    /** Dropping an ill-formed sequence must not swallow the character that follows it. */
    @Test
    void theCharacterAfterAnIllFormedSequenceIsStillDecoded() throws IOException {
        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(
                new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80, 'x'})), new io.github.dhoard.braxton.input.InputOptions(java.time.Duration.ofMillis(5),64,1024,java.time.Duration.ofMillis(5L),java.time.Duration.ofMillis(100)));
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text()).isEqualTo("\uFFFD");
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
        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(stream));
        assertThat(decoder.read().orElseThrow()).isInstanceOf(InputEvent.PasteInput.class);
        InputEvent.KeyInput key = (InputEvent.KeyInput) decoder.read().orElseThrow();
        assertThat(key.text()).isEqualTo("x");
    }

    @Test
    void aPasteLimitNeverLeavesABrokenCharacter() throws IOException {
        String body = "\uD83D\uDE00ab";
        ByteAtATime stream = new ByteAtATime(
                ("\u001B[200~" + body + "\u001B[201~").getBytes(StandardCharsets.UTF_8));
        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(stream), new io.github.dhoard.braxton.input.InputOptions(java.time.Duration.ofMillis(1),64,3,java.time.Duration.ofMillis(4096),java.time.Duration.ofMillis(100)));
        InputEvent.PasteInput paste = (InputEvent.PasteInput) decoder.read().orElseThrow();
        assertThat(paste.text()).isEmpty();

        ByteAtATime roomy = new ByteAtATime(
                ("\u001B[200~ab" + body + "\u001B[201~").getBytes(StandardCharsets.UTF_8));
        InputEvent.PasteInput limited =
                (InputEvent.PasteInput) new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(roomy), new io.github.dhoard.braxton.input.InputOptions(java.time.Duration.ofMillis(1),64,2,java.time.Duration.ofMillis(4096),java.time.Duration.ofMillis(100))).read().orElseThrow();
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
        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(stream));
        InputEvent.KeyInput key = (InputEvent.KeyInput) decoder.read().orElseThrow();
        assertThat(key.text()).isEqualTo("a");
        assertThat(decoder.read()).isEmpty();
    }

    @Test
    void anOverLongCsiIsSkippedWholeInsteadOfLeakingItsTailAsText() throws IOException {
        // Past the length cap the decoder stopped consuming, so the sequence's remaining parameter
        // bytes and its final byte arrived as ordinary text and were inserted into the draft.
        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(
                ("\u001B[" + "1".repeat(70) + "mX").getBytes(StandardCharsets.UTF_8))));
        InputEvent event = decoder.read().orElseThrow();
        assertThat(((InputEvent.KeyInput) event).text()).isEqualTo("X");
        assertThat(decoder.read()).isEmpty();
    }

    @Test
    void skippedSequencesLeaveTheFollowingInputIntact() throws IOException {
        // An unsupported CSI with modifiers, an unknown numeric CSI, an X10 click whose three
        // payload bytes must be consumed, and an SGR click are all skipped; the printable input,
        // the paste and the text after it must arrive unchanged and in order.
        InputDecoder decoder = new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteAtATime(
                "\u001B[1;5A\u001B[99~\u001B[M!!!\u001B[<0;10;5Mx\u001B[200~p\u001B[201~y"
                        .getBytes(StandardCharsets.UTF_8))));
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text()).isEqualTo("x");
        assertThat(decoder.read().orElseThrow()).isEqualTo(new InputEvent.PasteInput("p"));
        assertThat(((InputEvent.KeyInput) decoder.read().orElseThrow()).text()).isEqualTo("y");
        assertThat(decoder.read()).isEmpty();
    }

    @Test
    void endOfInputIsEmpty() throws IOException {
        assertThat(new InputDecoder(io.github.dhoard.braxton.internal.input.TestReaders.utf8(new ByteArrayInputStream(new byte[0]))).read()).isEmpty();
    }
}

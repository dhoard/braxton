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
    void aLoneEscapeIsEscape() throws IOException {
        assertThat(((InputEvent.KeyInput) read("\u001B").orElseThrow()).key()).isEqualTo(Key.ESCAPE);
    }

    @Test
    void multiByteUtf8SurvivesByteAtATimeReads() throws IOException {
        InputEvent.KeyInput key = (InputEvent.KeyInput) read("\u65E5").orElseThrow();
        assertThat(key.text()).isEqualTo("\u65E5");
    }

    @Test
    void anInvalidUtf8LeadByteDoesNotSwallowTheFollowingCharacter() throws IOException {
        InputDecoder stray = new InputDecoder(new ByteAtATime(new byte[] {(byte) 0x80, 'a'}));
        assertThat(((InputEvent.KeyInput) stray.read().orElseThrow()).text()).isEqualTo("a");

        InputDecoder overlong = new InputDecoder(new ByteAtATime(new byte[] {(byte) 0xC0, 'b'}));
        assertThat(((InputEvent.KeyInput) overlong.read().orElseThrow()).text()).isEqualTo("b");
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
    void endOfInputIsEmpty() throws IOException {
        assertThat(new InputDecoder(new ByteArrayInputStream(new byte[0])).read()).isEmpty();
    }
}

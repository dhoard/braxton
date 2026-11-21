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

package io.github.dhoard.braxton.tools;

import io.github.dhoard.braxton.asset.ImageAsset;
import io.github.dhoard.braxton.image.ImageCapabilities;
import io.github.dhoard.braxton.image.ImageOutput;
import io.github.dhoard.braxton.image.ImageProtocol;
import io.github.dhoard.braxton.terminal.TerminalEvent;
import io.github.dhoard.braxton.terminal.TerminalSession;
import java.awt.image.BufferedImage;

/**
 * Owned low-level session used only by the opt-in pyte image-output gate.
 */
public final class ImageOutputProbe {
    private ImageOutputProbe() {}
    public static void main(String[] args) throws Exception {
        BufferedImage raster = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = {0xffff0000, 0xff0000ff, 0xff00ff00, 0xffffffff, 0xff0000ff, 0};
        raster.setRGB(0, 0, 2, 3, pixels, 0, 2);
        try (TerminalSession session = TerminalSession.open()) {
            session.enterRawMode();
            session.clear();
            ImageCapabilities support = args.length == 0 ? ImageCapabilities.NONE
                    : new ImageCapabilities(true, true, true);
            ImageProtocol written = ImageOutput.of(ImageAsset.of(raster)).write(session, support);
            session.write("\r\nimage-output-ready " + written + "\r\n");
            session.flush();
            for (var event = session.read(); event.isPresent(); event = session.read()) {
                if (event.get() instanceof TerminalEvent.Input) break;
            }
        }
    }
}

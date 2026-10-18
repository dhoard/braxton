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

package io.github.dhoard.braxton.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The shipped provenance matches the files it describes.
 *
 * <p>The README promises that the pinned provenance ships with the artifact and that per-font
 * authorship is recorded in NOTICE. The provenance also repeats the SHA-256 of each pinned Unicode
 * data file, so those digests must still describe the checked-in data.</p>
 */
class DistributionProvenanceTest {

    private static final Path UNICODE_DATA = Path.of("tools", "unicode", "16.0.0");

    @Test
    void theProvenanceDigestsDescribeThePinnedData() throws IOException {
        List<String> rows = Files.readAllLines(provenancePath()).stream()
                .filter(line -> line.startsWith("| `")).toList();
        assertThat(rows).as("the provenance table lists three pinned files").hasSize(3);
        for (String row : rows) {
            String[] cells = row.split("\\|");
            String file = cells[1].strip().replace("`", "");
            String digest = cells[3].strip().replace("`", "");
            assertThat(sha256(UNICODE_DATA.resolve(file)))
                    .as("recorded digest of %s", file)
                    .isEqualTo(digest);
        }
    }

    @Test
    void theLicenseAndProvenanceShipWithTheArtifact() throws IOException {
        for (String resource : List.of("/META-INF/LICENSE", "/META-INF/NOTICE",
                "/META-INF/braxton/unicode-16.0.0/LICENSE.txt",
                "/META-INF/braxton/unicode-16.0.0/PROVENANCE.md")) {
            try (InputStream stream = getClass().getResourceAsStream(resource)) {
                assertThat(stream).as("%s is on the classpath", resource).isNotNull();
                assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                        .as("%s is not empty", resource).isNotBlank();
            }
        }
    }

    @Test
    void noticeListsEveryBundledFont() throws IOException {
        String notice = Files.readString(Path.of("NOTICE"), StandardCharsets.UTF_8);
        List<String> fonts = new ArrayList<>();
        try (var paths = Files.list(Path.of("src", "main", "resources", "fonts"))) {
            paths.filter(path -> path.toString().endsWith(".flf"))
                    .forEach(path -> fonts.add(path.getFileName().toString()));
        }
        assertThat(fonts).hasSize(10);
        assertThat(fonts).allSatisfy(font -> assertThat(notice)
                .as("NOTICE names %s", font)
                .contains(font.substring(0, font.indexOf('.'))));
        assertThat(notice).as("the third-party section names its licence")
                .contains("FIGlet").contains("Redistribution and use in source and binary forms");
    }

    private static Path provenancePath() {
        return Path.of("src", "main", "resources", "META-INF", "braxton", "unicode-16.0.0",
                "PROVENANCE.md");
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException missing) {
            throw new AssertionError(missing);
        }
    }
}

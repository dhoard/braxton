# Unicode 16.0.0 column-width data

Braxton's fixed column-width policy is generated from two files published by the Unicode
Consortium for version 16.0.0:

| File | Source | SHA-256 |
| --- | --- | --- |
| `EastAsianWidth.txt` | https://www.unicode.org/Public/16.0.0/ucd/EastAsianWidth.txt | `43adc76c0686a42cb370764eb8cfe2b2a45b10b855e5572a2db4a0eecce15d5b` |
| `emoji-data.txt` | https://www.unicode.org/Public/16.0.0/ucd/emoji/emoji-data.txt | `f1365a5173eee18e1f98b240cdc492e84a25f1ce7e0c9d1094eb29c41a22696a` |
| `LICENSE.txt` | https://www.unicode.org/license.txt | `e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96` |

`tools/generate_unicode_widths.py` reads the pinned copies in `tools/unicode/16.0.0/`,
verifies those hashes, and emits `src/main/java/io/github/dhoard/braxton/internal/text/Unicode16Width.java`
(Emoji_Presentation plus East Asian W/F ranges). Run `--check` to verify the generated
table is current. Builds compile the checked-in table and never fetch data.

The data is redistributed under the Unicode License V3, reproduced in `LICENSE.txt`.

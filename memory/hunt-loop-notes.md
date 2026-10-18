# HUNT_LOOP working notes

Verified against the source at commits 164-196 (2026-10-18).

## Producer hand-off policy (three sites)

Envelopes produced off the owner thread must not be dropped because the mailbox is momentarily
full: a full mailbox means the owner is still inside `update`/layout. Three sites now retry a short
`enqueue` until the mailbox accepts the envelope or is closed (`close()` releases the wait; the
owner's final `join` interrupts it):

- `internal/runtime/RuntimeLoop.offer` - input reader, resize reader, control (Close) envelopes.
- `internal/runtime/SubscriptionManager.enqueue` - interval ticks and source emissions.
- `internal/runtime/EffectRunner.deliver` - task results, delayed messages, clipboard reports
  (owner-side delivery still uses `enqueueFromOwner`, where overflow is fatal by design).

Keep these consistent if the mailbox policy changes.

## Pyte end-to-end probe for the harness

`./mvnw -o -q clean package -DskipTests` then
`./mvnw -o -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/showcase-lib`,
then spawn with `pty.fork()` (NOT `setsid` + pty: without a controlling terminal the Linux session
fails with "cannot open /dev/tty"). 40x120 works. Useful assertions: first screen paints the
transcript; submit + double Escape writes `Stopped.` (or `Stopped; N queued prompt(s) discarded.`);
two Ctrl+C presses exit with status 0. Probe scripts are owned temporary state under /tmp.

## Coverage-hunting notes

- The suite is TTY-free and fast (~10 s for `./mvnw -o test`, 512 tests).
- `Rect`/`Screen`/`LayoutEngine`/`PasteSanitizer` survive randomized probes; leading combining
  marks make measured and painted widths disagree in table-like widgets (Unicode segmentation of
  a mark after a break), which is inherent, not a widget defect - exclude them from probes.
- `EditorState.withText` normalizes null like the constructor/factory; `Box.responsive()` discards a
  fixed width and `isResponsive()` mirrors what `render` uses.

## Rules pinned at commits c65380c..9378397 (cycles 187-196)

- `Border` is a value type. `Box.render` asks `border.isVisible()` (as `LayoutEngine.paintBorder`
  and `Table.render` already did) instead of comparing against the `Border.NONE` constant, so a
  border assembled from its own characters behaves like `NONE` (no frame, no inset).
- Content that cannot survive the UTF-8 write boundary is dropped at the boundary:
  `StyledText.Span.sanitize` and `clipboard/PasteSanitizer.stripArtifacts` both drop unpaired
  surrogates and keep both halves of a pair. Keep the two in sync; the editor draft and the
  in-process clipboard must not hold a unit no cell can display while the OSC 52 payload carries
  '?'.
- `Key.parse` reads a doubled separator as the literal `+` name: "+" is the plus key and "ctrl++"
  is Ctrl+plus. A single trailing separator ("ctrl+") is still rejected.
- An interval mapper is application code: a RuntimeException from it is reported through the
  diagnostics sink ("subscription <id> mapper threw <Class>") and the interval keeps ticking, so
  reconcile never keeps a silently dead resource. An external source's mapper still propagates into
  the source's own thread (deliberate).
- Coverage now pinned for: an idle application (no update, no view rebuild, no write), markup
  DOCTYPE/entity rejection (XXE guard), the README default numbers, cleanup failures not replacing
  the primary cause, and TableSelector's focused-only inversion (also corrected in its javadoc).
- Probe recipe for the packaged example: `./mvnw -o -q clean package`,
  `./mvnw -o -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/showcase-lib`,
  then a pyte script over `pty.fork()` (40x120 for the full screen; 1x1..8x20 confirmed crash-free).
  Double Ctrl+C exits with status 0; a submitted prompt shows "Working".
- Inherent, not a widget defect (do not "fix" without a decision): a dangling combining mark or
  variation selector immediately before a tab makes the layout engine's expansion one column wider
  than `TabStops.expand` + re-measure ("\u0301\ta\t\uFE0E\tb": 13 vs 12 columns).

## Rules pinned at commits c9ad532..a4716ce (cycles 177-186)

- `PasteSanitizer.skipEscape` parses the ANSI grammar (0x20-0x2F intermediates, then one 0x30-0x7E
  final byte) instead of a byte list; ESC # 8, ESC % G, ESC $ B, ESC SP F are removed whole.
- `InputDecoder.decodeUtf8` builds the code point from the payload bits and drops overlong,
  surrogate, and >U+10FFFF sequences; never let the JDK string decoder turn them into U+FFFD text
  (a literal U+FFFD, EF BF BD, still decodes).
- Coverage now pinned for: Editor invariants over random action sequences, Gradient, Badge, Quote,
  Cell emptiness, Colors palette ramps, focus region geometry, and "a measured height paints the
  whole tree" (the latter excludes border frames, which paint their whole allocation, and end/centre
  rows, which place content at the aligned offset).

## Rules pinned at commits 9989f7e..96705e0 (cycles 197-216)

- The idle-coverage oracle must wait for the *write*, not for the view counter: the owner paints
  after `buildView` returns, so snapshotting the write log on `views == 1` recorded an empty
  baseline and reported the initial frame as idle output (it failed 4 of 5 isolated runs).
- Markup is one well-formed XML document: exactly one root element and explicitly closed empty
  elements (`<br/>`; a bare `<br>` is rejected). `Markup` now says so, and
  `MarkupTest.emptyElementsMustBeClosedExplicitlyBecauseTheDocumentIsXml` pins it.
- `Editor.copyText` is raw (selection or draft) and ignores masking; `Editor.copyableText` and the
  `Copy` action are the masking-aware paths. Do not describe `copyText` as honouring masking.
- Widget assembly has two styles and they are deliberate: `ProgressBar` is an immutable value
  (setters return a new instance); `Box`, `Badge`, `Quote`, `Table`, `ImageView` and `Logo` are
  mutable builders whose setters return `this`. Build them inside `view`; never share one across
  frames. Unifying the styles is an open user decision.
- Widget padding (`Table` columns, `Quote` blocks, `ProgressBar` cells, `Markup` tables and list
  indents) is measured with `WidthPolicy.PINNED`, so a renderer built with another policy wraps and
  clips differently but does not realign those baked widths (README "Limitations"). Making widgets
  policy-aware is an open user decision.
- Two reproducibility oracles now exist that no build gate had: `Unicode16WidthReproducibilityTest`
  re-derives the wide table from `tools/unicode/16.0.0` in Java (1,114,112 code points, 122 merged
  ranges - the same count the Python generator prints) and `DistributionProvenanceTest` recomputes
  the SHA-256 digests recorded in `META-INF/braxton/unicode-16.0.0/PROVENANCE.md`, checks the four
  distribution resources are on the classpath, and checks NOTICE names every bundled `.flf`.
- The suite is now 591 tests; `./mvnw -o clean package` is ~14 s. Test-only changes still need
  `./mvnw -o spotless:apply` (license headers are checked in `verify`).
- Probe deliberately: write throwaway Java against `target/classes` (no jshell in this image) or a
  temporary JUnit class. `pyt*`/pyte is only needed when TUI code changes; no cycle in this range
  changed TUI code.
- When appending to `HUNT_TRACKING.txt` with a shell here-document, quote the delimiter (`<<'EOF'`)
  and substitute the commit hash afterwards: backticks in an unquoted heredoc are command
  substitutions and silently corrupt the record.

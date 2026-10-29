# Frame presentation, refresh checks, and coverage (added 2026-10-29)

## Presentation protocol

- `RuntimeLoop.present()` builds the frame first, then writes it through
  `writeSynchronized`: JLine `beginSynchronizedUpdate()`, payload, `endSynchronizedUpdate()`,
  one `flush()`, and `PrintWriter.checkError()`. The bracket is under `outputLock`; generic
  `writeOutput` (clipboard, mode entry) stays unbracketed. `currentScreen`/`viewDirty` are
  committed only after the bracket closes and the output check passes.
- `AnsiEncoder.encodeRow` never pre-clears a nonempty row: absolute CUP, painted prefix with
  interior gaps, closed style/link, then a neutral `EL0` at the first unused column. A full row
  emits no erase. Empty rows use reset + `EL2`. `paintedEnd` includes the continuation of a wide
  glyph and is separate from `lastPaintedColumn`, which still selects the spill-repair row below a
  full-width row.
- Terminals that ignore DEC 2026 still get overwrite-before-erase rows, so they do not show an
  intentional blank interval; only supporting terminals get atomic presentation.

## Verification

- `tools/terminal/verify_terminal.py` uses owned FFM PTYs and pyte. `check_refresh` covers
  full -> short -> full with backgrounds and suffix erasure; `check_refresh_wide` covers a wide
  glyph at the right edge, splitting it to a narrow glyph (stale half and background cleared at
  column 80), and growing it back. `wait_frame` requires exactly one balanced BSU/ESU per frame and
  no output outside it.
- JaCoCo 0.8.15 runs in `verify` (`target/site/jacoco/index.html`) and gates `AnsiEncoder` at 100%
  line/branch and `RuntimeLoop` at 88% line / 74% branch. The uncovered `RuntimeLoop` lines are the
  real-terminal FFM open path (native access + attached terminal), defensive unreachable exception
  composition, and shutdown races; the PTY checks exercise the first group.
- JaCoCo marks a line that ends in a throwing call as uncovered even when it executes (the probe
  follows the call). Returning the exception (`throw unchecked(failure)`) instead of calling a
  `void` helper makes the call site measurable and keeps identity semantics for runtime exceptions
  and errors.

## Comparing installed artifacts

`/opt/amanda/amanda-0.1.0.jar` is a consumer build, not a source oracle: its embedded layout and
subscription classes predate later source restoration, and byte differences also come from debug
attributes (`LocalVariableTable`) and different javac configurations. Compare behavior: decompile or
`javap -c` instruction streams, and for the encoder run a deterministic screen corpus through both
builds and diff the emitted bytes. The 2026-10-29 corpus (51 sizes, random text, wide glyphs, gaps,
styles, links, backgrounds, row diffs) produced identical output; the only runtime difference was
the intentional pre-layout size resampling in `present()`.

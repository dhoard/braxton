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

## Additional boundary regressions (2026-10-20)

- Paste burst capacity must include the pending end-marker prefix from the previous burst, even
  though those bytes are not yet in the payload. A mismatch can flush that prefix and consume the
  new burst immediately. `PasteDecodeCostTest.aRejectedMarkerPrefixAcrossBurstsCannotExhaustThePayloadBuffer`
  places the split at the initial buffer capacity; small window-only tests missed this defect
  (fixed in f44a7f7). Keep the extra-byte calculation in long arithmetic.
- Sanitizing content can leave an empty styled span. Such a tail must not hide earlier newline
  content from `stripTrailingNewlines`; when no trimming is needed, structural empty tails remain
  intact (`StyledTextTest.emptySanitizedSpansCannotHideTrailingNewlines`, d0f1d07).
- Hyperlink metadata has its own control boundary: span content sanitization does not validate a
  URL, and direct Screen callers bypass Span entirely. Both typed link validation and the encoder's
  defensive `safeLink` path now exclude C1 as well as C0 controls (fe526d2).

## Geometry arithmetic (2026-10-21)

- `Insets.horizontal()`/`vertical()` add two non-negative ints, so a valid `Insets` (constructor only
  rejects negatives) can sum past `Integer.MAX_VALUE` and wrap negative. `Rect.inset` then *grows*
  the rectangle: a 5x5 rect with `Insets.uniform(1_200_000_000)` became 1,894,967,301 cells.
  Both accessors now saturate at `Integer.MAX_VALUE` (widened long sum), pinned by
  `GeometryTest.insetSpacingThatOverflowsIntCannotGrowTheRectangle`. `LayoutEngine` padding
  measurement consumes the same accessors.
- Unfixed lead: `Rect.inset` still adds the insets to the origin in `int`, so a valid rect near
  `Integer.MAX_VALUE` (`new Rect(Integer.MAX_VALUE - 2, 0, 8, 9).inset(Insets.uniform(5))`) throws
  `IllegalArgumentException: rect must be non-negative` instead of clamping. `Rect.contains` and
  `Rect.clip` already widen to long; `inset`'s origin arithmetic does not. Needs a decision on what
  a shifted origin beyond `Integer.MAX_VALUE` should be (clamp vs reject).

## Asset symbol boundary (2026-10-21)

- `ImageView.symbol` paints one pixel per grid cell, so it flattens a symbol to one line at the
  builder boundary: CRLF/CR/LF and tab each become one space, and text the `StyledText.Span`
  boundary would drop (unpaired surrogates, C0/C1 controls other than newline/tab) is removed
  before measuring. A value with no measured column still falls back to the block. Pinned by
  `ImageViewTest.aSymbolThatCannotPaintOnOneLineCannotSplitOrShortenAnImageRow`; the editor prompt
  uses the same break-to-space rule at `EditorOptions`.

## Progress bar icon boundary (2026-10-21)

- `ProgressBar.icons(active, inactive)` is the only bar input that can still carry a bare CR:
  prepended and appended labels arrive as `StyledText`, whose `Span` boundary drops a bare CR as
  an unrepresentable control character before the widget is called. `flatten` now maps CRLF, CR,
  LF and tab each to one space, so an icon `"a\rb"` paints `"a b"` instead of the merged `"ab"` it
  painted when the CR reached the span boundary. Pinned by
  `ProgressBarTest.anIconBreakCannotJoinTheWordsOnEitherSide`, which asserts both icons, the
  painted rows through `ScreenRenderer`, and the measured height.
- Still open, same rule, not fixed: `Badge.singleRow` and `Box.singleLine` flatten only LF and tab.
  They take `StyledText`, so a bare CR is already gone; only a CRLF split across two spans yields
  two spaces there. Treat them as coverage/decision leads rather than defects unless the rule is
  moved to a shared helper.
- Consequence for `ProgressBar`: the icon is painted one cell per row, so a wide icon is the
  caller's choice (`aWideIconSpansItsOwnColumnsRatherThanBeingSqueezed`); flattening must not
  change the bar's cell count.

## Editor caret width boundary (2026-10-21)

- `Editor.renderLine` measures every cluster where it is painted: the caret's block covers that
  cluster's painted width, and a tab is the gap to the next four-column stop counted from the
  prompt (or its blank stand-in) plus the clusters already painted. A one-cell block over a tab
  slid the rest of the line left by the tab's remaining columns (a `"> ab\tc"` composer painted
  `"> ab█c"` with the caret on the tab). `Editor.visualWidth` is now the single tab-width rule for
  both caret movement and painting. Pinned by
  `EditorTest.theBlockCursorOnATabCoversTheColumnsTheTextViewPaints` (with and without a prompt)
  and mirrored in the cluster-by-cluster reference of `EditorRenderCostTest`.
- The harness composer always paints a `"> "` prompt, so a tab after `"> a"` is only one column
  wide there; a discriminating packaged-harness pyte check needs a draft such as `"ab\tc"` whose
  tab starts on a stop boundary (four columns at visual column 4).

## Markup list prefix boundary (2026-10-21)

- A Markup list `icon` is a one-line prefix. `Markup.renderList` flattens CRLF/CR/LF to one space
  and expands tabs with `TabStops.expand` over `indent + icon + " "` (tabs count from the line
  start, i.e. after the `gap` indent), then hangs continuation lines under
  `TextMeasure.columns(prefix)`. Measuring the raw icon instead put them under columns the prefix
  never paints: with `icon=">&#9;"` the prefix paints 5 columns but the continuation was indented
  3, and `icon="a&#10;b"` split the item across rows (CR was silently dropped, merging words).
  Pinned by `MarkupTest.aListIconIsMeasuredAsThePaintedOneLinePrefix`, which also asserts the
  painted columns through `ScreenRenderer`; a whitespace-only icon still falls back to the default
  bullet (`CatalogTest.aWhitespaceListIconFallsBackToTheDefaultBullet`).
- Consequence to remember: `TextMeasure.columns` counts a tab and a line break as one column, so
  every widget that measures a tab-bearing prefix must expand it first (`TabStops`).
- Unfixed leads (no decision yet): `ScreenRenderer.columns`/`TextMeasure.columns` report a tab as
  one column while the layout engine paints 1-4, which cannot be fixed inside `columns()` alone
  because `truncate`/`fit` need the positional stop rule.

## Selector option boundary (2026-10-21)

- `Selector.view` builds `marker + option` as one text row, so an option is one painted row and the
  marker always names a whole option. `Selector.singleLine` flattens CRLF/CR/LF to one space, the
  same rule `Markup` and `EditorOptions` apply to their raw prefix strings (and `ProgressBar` to
  its icon strings): raw text split the option across rows ("❯ a" / "b" for option `a\nb`) and
  pushed every later option down, and a bare CR was dropped by the styled-text boundary (`c\rd`
  painted `cd`). Pinned by
  `SelectorTest.anOptionCannotSplitItsRowAcrossLineBreaks`, which asserts the painted rows and that
  the list measures one row per option (`LayoutEngine.intrinsicHeight`).
- `Selector` does not expand tabs itself: it never measures the option, and the layout engine
  expands a tab from the line start, which already includes the two-cell marker. A tab therefore
  paints as the engine paints it; measuring a selector row is still subject to the `columns()` tab
  lead below.

## Quote bar boundary (2026-10-21)

- `Quote.bar` is a one-line prefix painted at the start of every quoted line, so the builder
  flattens CRLF/CR/LF to one space (the same rule `Markup`, `Selector`, `EditorOptions` and, for
  its raw icon strings, `ProgressBar` apply; `Box` and `Badge` still flatten only LF and tab -
  they take `StyledText`, whose span boundary drops a bare CR before the widget sees it). A raw
  break split one quoted line into extra rows (`a\nb` rendered `a` / `b x`), and a bare CR was
  dropped by the styled-text boundary
  (`a\rb` merged to `ab x`). The null/empty fallback to a space is unchanged, and a
  whitespace-only bar remains a bar of spaces. Pinned by
  `QuoteTest.aBarCannotSplitAQuotedLineAcrossRows`, which asserts the rendered rows, the painted
  rows through `ScreenRenderer`, and the measured height.
- The bar's tabs are not expanded at the builder boundary: like `Selector`, the quote never
  measures the bar (it is the same prefix on every row), and the layout engine expands it from the
  line start.

## Subscription emission boundary (2026-10-21)

- Every emission path treats a `null` mapper result as "no message": `SubscriptionManager.enqueue`
  returns early for null (interval ticks and external sources), `EffectRunner.deliver` does the
  same for effect results, and `Binding` rejects a null message outright. The resize fan-out was the one path that wrapped `mapper.apply(size)` in an
  `AppMessage` unconditionally, so a resize mapper that declined a size handed `Program.update` a
  null: a sealed-message dispatch threw `NullPointerException` and `Application.run` ended with
  `FAILURE` instead of continuing. `notifyResize` now drops the null result like every other path,
  and `Subscription`'s javadoc states the rule once. Pinned by
  `SubscriptionManagerTest.aResizeMapperThatReturnsNullEmitsNoMessage` (owning boundary: the
  mailbox stays empty and a later non-null result still arrives) and
  `RuntimeLoopTest.aResizeMapperThatDeclinesASizeDoesNotEndTheRun` (end-to-end: the run ends with
  `INPUT_CLOSED`, not `FAILURE`, after the declined resize).
- Separate, still-unfixed lead: a resize mapper that *throws* propagates on the owner thread and
  ends the run, while a throwing interval mapper is reported through diagnostics and keeps ticking
  (`anIntervalMapperThatThrowsIsReportedAndTheIntervalKeepsTicking`). Deciding whether resize
  failures should be contained needs a policy decision.

## Flex weight boundary (2026-10-21)

- `View.Grow(weight, child)` accepts any non-negative `int`, and the README says the remaining
  main-axis cells are "divided by weight in declaration order". `LayoutEngine.rowWidths` and
  `paintColumn` summed the weights and multiplied `remaining * weight` in `int`, so a weight sum
  past `Integer.MAX_VALUE` went negative (no flex child got a cell: a row of `MAX`/`MAX` painted
  blank) and a large weight wrapped the product (children were squeezed to a couple of cells or
  misplaced). Both sites now accumulate the weight in `long` and multiply in `long`; the allocation
  stays `<= remaining`, so the remainder still reaches the earliest children.
  Pinned by `LayoutEngineTest.flexWeightsTooLargeForAnIntStillDivideTheRemainingSpace` (rows and
  columns, sum overflow and product overflow).
- The harness uses no `Grow` view, so the pyte probe only proves the packaged application still
  starts, paints, submits, stops, and exits 0; the arithmetic path's oracle is `ScreenRenderer`
  through `LayoutEngineTest`.

## Table selector cell boundary (2026-10-21)

- Every raw-string cell in `TableSelector.State` is normalized by `TableSelector.expanded` before it
  is measured, and each cell is one painted row. The rule is now the same one `Selector`, `Markup`,
  `Quote`, `ImageView`, `ProgressBar` and `EditorOptions` apply: CRLF, CR and LF each become one
  space. Flattening per character (`replace('\n', ' ')` then `replace('\r', ' ')`) turned a CRLF into
  two spaces, so a Windows line ending widened the shared column and shifted every later column:
  cells `"a\r\nb"` painted `"a  b"` where `"a\nb"` painted `"a b"`. Pinned by
  `TableSelectorTest.aCellBreakCannotWidenTheSharedGrid` (CRLF/CR/LF paint identically, and a third
  column lands on the same painted column), which asserts the painted grid through `ScreenRenderer`.
- Tabs are still expanded after the break flattening, from the cell's own start, because the cell is
  painted as a unit; the stored state and `selectedValue()` remain the caller's raw text.

## Intrinsic measurement saturation (2026-10-21)

- `LayoutEngine.intrinsicHeight(view, cols)` must answer at most the rows it was offered (the
  private `maxRows`, `Integer.MAX_VALUE` for the public call), but `View.Fixed` returned its raw
  size and `Padding`/`BorderBox` added their edges with `int` arithmetic. Two `Fixed(2_000_000_000)`
  children in a column measured -294,967,296, vertical insets of 1,000,000,000 on top of such a
  reservation measured -1,294,967,296, and a border over `Fixed(Integer.MAX_VALUE)` measured
  -2,147,483,647, so a caller sizing a viewport from the measurement cannot build a `Rect`.
  Every height case now respects `maxRows`: `Fixed` caps its size, and `Padding`/`BorderBox` add
  their edges through a `cappedSum` helper (widened sum clamped to `maxRows`). The sums in
  `columnHeight` therefore stay inside the int range and saturate at `Integer.MAX_VALUE` instead of
  wrapping; painting already clipped to the viewport, so painted output is unchanged. Pinned by
  `LayoutEngineTest.aContentHeightPastTheIntRangeSaturatesInsteadOfGoingNegative` (five shapes:
  two reservations, insets plus reservation, border plus reservation, insets larger than the rows
  a previous sibling left, and the border-wrapped form).
- A 20,000-tree probe with sizes near `Integer.MAX_VALUE` reported 295 negative measurements and
  93 layout exceptions before the change, and 0 negative measurements after it; the 91 remaining
  `RuntimeException`s are all `Rect.inset` origin overflow (see "Geometry arithmetic"), reached
  through `LayoutEngine.paint`'s padding inset, not the measurement path. The width side needed no
  change: every width case already caps at `maxCols`.

## Harness key observation boundary (2026-10-21)

- The README promises "Any other key cancels a pending Ctrl+C confirmation", but a view can change
  model state only by producing a message. `Editor.view` maps only the gestures in its own table,
  so a key it does not map (F1, Insert, an unmapped Ctrl chord) produced no message at all: the
  harness never saw it and `quitArmedUntilNanos` stayed set. `HarnessView` now builds the composer
  from the same public `Editor.render`/`Editor.actionFor` API and emits
  `HarnessMsg.UnhandledKey` for any `KeyInput` the editor did not consume. The program treats it as
  a user action and its update is a no-op, so the fallback disarms without repainting when nothing
  else changed.
- If the core editor ever needs to expose "unhandled input" generally, prefer a fallback hook in
  `Editor.view` over copying this wiring; today the duplication is two lines and keeps the public
  API unchanged.
- Pinned by `HarnessProgramTest.aKeyTheComposerIgnoresStillCancelsThePendingCtrlC` (routes F1
  through the production view, then updates) and a pyte probe: F1 removes the top-rule hint, a
  later Ctrl+C rearms instead of exiting, and the third exits 0.

## Border character content boundary (2026-10-21)

- A `Border` holds raw characters, and `LayoutEngine.paintBorder` wrote them straight into cells. It
  was the one content path that bypassed `StyledText.Span` sanitization: with `vertical = ESC` and
  the legitimate child text `[2K`, the frame spelled `ESC [ 2 K` after the encoder's own line clear,
  so a real terminal erased the painted row (pyte: row `""` instead of `" [2K"`), and `ESC A` can
  likewise be spelled from a corner plus an `A` edge. `borderGlyph` now builds each edge character
  through `StyledText.plain(...)` once per painted border, so a control character or an unpaired
  surrogate occupies its cell as a blank and never reaches the terminal. Pinned by
  `BorderTitleTest.aCustomBorderCannotCompleteAnEscapeSequenceFromItsOwnCharacters` (the cell text,
  the painted rows, exactly one encoder line clear per row, and a surrogate edge).
- `Box` and `Table` already sanitized their border characters because they build `StyledText`; the
  layout path was the outlier. A new paint path that writes a value type's characters straight to
  `Screen` must pass the span boundary first.
- Still open, same value: a wide border character is painted with width 1 into a one-column edge, so
  a `Border('日', …)` over a 6-column allocation paints rows of 12 and 8 columns (probed). Deciding
  whether to reject a wide border character, replace it, or paint it two columns wide (and widen the
  reserved edge to match) needs a user/design decision.

## Runtime cleanup containment (2026-10-21)

- `RuntimeLoop.cleanup` contained a throwing `subscriptions.closeAll()` and a throwing effect
  cancellation, but called `session.close()` unguarded. A `TerminalSession.close()` that throws (a
  custom embedding session, a `SecurityException` from `removeShutdownHook`, or a throwing
  `onClosed` callback in `BaseTerminalSession`) aborted the rest of cleanup: the mailbox stayed
  open, so producers (including the input reader) kept retrying an owner that was gone; sources and
  effects were not cancelled; the reader threads were not joined; and `Application.run` threw
  instead of returning the `ExitResult` that the API says "carries any cleanup failures".
  `closeSession()` now records a `RuntimeException` in `cleanupFailures` and continues, at both
  close sites, exactly like the other cleanup steps. An `Error` still propagates, matching them.
  Pinned by
  `RuntimeLoopTest.aFailingSessionRestoreIsCarriedAsACleanupFailureInsteadOfEscapingTheRun`
  (quit-at-init program, a session whose restore throws once: kind FAILURE, `failure()` and
  `cleanupFailures()` carry "session restore failed", and the second close still ran).
- A cleanup step that is added later must be contained the same way; the session close was the one
  step whose failure could escape `run()`.

## Harness status-rule hint boundary (2026-10-21)

- The README promises the Ctrl+C confirmation is transient feedback on the rule above the footer and
  that any other key cancels the pending confirmation. The hint was a `statusMessage` written by
  `HarnessProgram.quitRequest`, and neither the disarm (`disarmQuitOnUserAction`) nor the window's
  expiry (`tick`) cleared it: an abandoned (F1) or expired confirmation left
  "Press Ctrl+C again within 2 s to exit." painted on the bottom rule while `topRule` had already
  dropped its own `ctrl+c again to exit` hint, so the screen kept a false promise indefinitely (a
  later Ctrl+C only rearms). `endQuitConfirmation` now ends the deadline and the hint together, at
  both the disarm and the expiry sites, and clears only that exact text: feedback a background event
  put on the rule after arming still belongs to that event. Pinned by
  `HarnessProgramTest.anAbandonedCtrlCConfirmationTakesItsHintOffTheStatusRule`, which asserts the
  production view's painted rows (`HarnessView.renderLines`), not just the model field.
- Any future confirmation hint painted from `statusMessage` needs the same pairing: clear it in
  every transition that ends the confirmation, not only where it is set. The Esc window needs none
  today because `topRule` derives its hint from `escArmedUntilNanos` on every paint.

## Shutdown wait arithmetic (2026-10-21)

- `EffectRunner.awaitIdle` computes `deadline = System.nanoTime() + grace.toNanos()` and then only
  compares clock differences. That addition wraps for a grace near `Long.MAX_VALUE` (which
  `RuntimeOptions` deliberately accepts when it fits in nanoseconds), but the wrapped deadline
  subtracts back to exactly `grace - elapsed`, so the wait is still correct: the modular result fits
  in a long for every elapsed time below ~292 years. Do not re-open this as an overflow defect
  without evidence of a comparison that uses the absolute deadline. Pinned as coverage by
  `EffectRunnerTest.aGraceTooLargeForTheClockAdditionStillWaitsForARunningTask` (a blocking task
  body and `Duration.ofNanos(Long.MAX_VALUE)`; the test fails if `awaitIdle` returns without
  waiting).
- By contrast, `PasteDecoder.addSaturated` needs a saturated deadline because a paste idle timeout
  is compared as `System.nanoTime() - deadline >= 0` against an absolute value, and `SystemTimers`
  rounds its milliseconds with a subtract-first form for the same reason. The difference is what the
  comparison does with the absolute deadline, not whether the addition wraps.

## Element identity boundary (2026-10-21)

- `ElementId.child(name)` joins `value + "/" + name`, so a `name` that contains the separator forges
  another parent's path: `ElementId.of("a").child("b/c")` and `ElementId.of("a/b").child("c")` were
  both `a/b/c` (equal, same hash), which is exactly the collision the class says namespaces prevent.
  The same rationale that already rejected null/blank (`"parent/null"`, `"parent/"`) now rejects a
  name carrying `/`; every identity a caller could have meant is unchanged, so rejection is the
  smallest remedy (an escape scheme would change every value). Direct construction still accepts an
  opaque value containing `/`; only the join validates a child name. Pinned by
  `ElementIdTest.aChildNameMustNotCarryTheNamespaceSeparator`.
- `EffectId.child` now applies the same rule (`EffectId` javadoc: "Ids are namespaced so composed
  features cannot cancel each other's work accidentally"). The consequence was proven at the runner,
  not just at the id: a delay under `EffectId.of("a").child("b/c")` and one under
  `EffectId.of("a/b").child("c")` are the same key in `EffectRunner.entries`, so `replaceLocked`
  cancelled the first entry and its message was never delivered (`delivered=[second]`). Pinned by
  `EffectIdTest.aChildIdMustNotForgeAnotherNamespace`. Rejection is again the whole remedy: an id
  whose parent is already namespaced (`EffectId.of("a/b").child("c")`) keeps working.
- `SubscriptionId.child` applies the same rule. The consequence is at the declaration, not a
  running resource: `Subscription.leaves` rejects a batch whose ids repeat, so
  `SubscriptionId.of("a").child("b/c")` and `SubscriptionId.of("a/b").child("c")` joined to one
  id and two distinct declared paths were reported as `duplicate subscription id: a/b/c` (probed).
  Pinned by `EffectIdTest.aSubscriptionChildIdMustNotForgeAnotherNamespace`; rejection preserves
  every legitimate namespaced parent (`SubscriptionId.of("a/b").child("c")` is still `a/b/c`).
- All three identity types now validate a child name (`ElementId`, `EffectId`, `SubscriptionId`). A
  fourth join site added later must apply the same rule; a raw value built directly can still be
  opaque and contain `/`.

## Editor mask boundary (2026-10-21)

- `EditorState.displayText` in PASSWORD mode is documented as "one mask glyph per cluster, line
  breaks kept, exactly as `Editor.render` draws them", and `Editor.render` masks the CR cell of a
  CR/LF pair and breaks the line at the LF. `masked()` had mapped the whole CR/LF cluster (one
  grapheme cluster) to a single glyph, so a draft containing CRLF collapsed two painted lines into
  one: `displayText` returned "•••" where `render` painted "••\n•" (probed: 112 of 500 random
  drafts over `{a,b,\n,\r,\r\n,\t,mark,wide}` mismatched before the change, 0 after). A CR/LF
  cluster now masks as one glyph **plus** the line break; a lone LF stays a bare break. Pinned by
  `EditorMaskingTest.aCrLfPairMasksAsOneGlyphAndKeepsItsLineBreak` (five drafts, equality with
  `Editor.render`, line count, and the exact masked form of "a\r\nb").
- Reachability: the reducer normalizes what it inserts (`insert` replaces CRLF/CR with LF) and the
  paste sanitizer does the same, so only a draft built directly from raw text
  (`new EditorState(...)`, `EditorState.of`, `withText`) can hold CRLF. That is the path the
  masked display must still describe correctly; the public record constructor accepts it.
- A bare CR is its own cluster and stays one mask glyph (it is invisible in NONE mode, but masking
  shows one cell per non-break cluster). `Editor.render` masks it the same way, so the two agree.

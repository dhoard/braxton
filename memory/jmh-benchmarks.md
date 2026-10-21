# JMH benchmark harness (added 2026-10-19)

Verified against the tree at HEAD `44536d3` plus the uncommitted presentation-cadence/text-assembly
work, with the benchmark harness implemented (see
`plans/jmh-benchmark-harness.impl.md` §9 for the execution record).

## What exists

`src/test/java/io/github/dhoard/braxton/benchmark/` - `MeasureBenchmark`, `StyledTextBenchmark`,
`WrapBenchmark`, `LayoutBenchmark`, `ScreenEncodeBenchmark`, `EditorRenderBenchmark`,
`InputDecodeBenchmark`, `FramePipelineBenchmark`, `SmokeBenchmark`, plus `BenchCorpus`/`BenchViews`
fixtures and a `package-info.java` that documents the run recipe. 23 benchmark methods, 67
parameterized runs, ~18 min for the full suite.

```bash
./mvnw -o -B -ntp test-compile dependency:build-classpath \
    -Dmdep.includeScope=test -Dmdep.outputFile=target/bench-cp.txt
java -cp "$(cat target/bench-cp.txt):target/classes:target/test-classes" org.openjdk.jmh.Main -l
java -cp "$(cat target/bench-cp.txt):target/classes:target/test-classes" \
    org.openjdk.jmh.Main -f 1 -wi 1 -i 1 -r 1 -w 1 -prof gc     # smoke, seconds
java -cp "$(cat target/bench-cp.txt):target/classes:target/test-classes" \
    org.openjdk.jmh.Main -prof gc -rff target/benchmarks/results.csv   # full suite
```

## Build constraints that are easy to get wrong

- JMH is **test scope only**; the published jar and its `javap -public` surface are unchanged
  (verified by listing comparison). Keep it that way - the core must stay dependency-free.
- **JDK 23+ does not run annotation processors discovered on the compile classpath.** The JMH generator
  must be named in `maven-compiler-plugin` `annotationProcessorPaths` on the `default-testCompile`
  execution, or the benchmark sources compile and silently generate nothing. `jmh-core` is not needed
  on the processor path.
- Enabling processing makes javac emit `[WARNING] No processor claimed any of these annotations:
  /org.junit.jupiter.api.Test` under `-Xlint:all`. Test compilation therefore uses
  `-Xlint:all,-processing` (`combine.self="override"`); main compilation keeps the full `-Xlint:all`.
  Do not "restore" the full set on test compilation without replacing the JMH processor wiring.
- Surefire excludes `**/jmh_generated/**` and `**/*Benchmark*`. JMH's generated `*_jmhTest` classes do
  match surefire's `*Test` shape; JUnit 6.1.3 discovers zero tests in them, but the excludes keep the
  gate deterministic.
- JMH 1.37 is the only version cached in `~/.m2`; a newer one needs network access.

## Evidence discipline

- Numbers are machine-specific. Two full runs on 2026-10-19 (JDK 25.0.3 Corretto, Ryzen 9 7900,
  Linux x86_64) took 1105 s and 1104 s; most rows repeated within ~3%, but `EditorRenderBenchmark`
  ascii/4096 moved 11% between runs. A benchmark delta alone never justifies a change: a deterministic
  in-repo oracle (the `RenderCostTest` allocation-bound style) must fail first, per the design.
- `target/` proved volatile for benchmark evidence: a `target/benchmarks/` directory created after a
  build disappeared without any command of ours deleting it. Copy raw results out of `target/`
  immediately; the durable record is the table in the implementation plan.
- Re-running one benchmark family is enough to validate a cycle: `org.openjdk.jmh.Main -prof gc
  -rff <file> '<FamilyName>'` keeps the annotation parameters, so the numbers stay comparable to the
  recorded baseline. A whole-family run is tens of seconds, not the ~18 min of the full suite.
- **Pyte check that actually proves "no user-visible change"**: build the jar with the fix reverted
  and the jar with the fix, run the *same* scripted session against both, and `diff` the pyte-decoded
  screens. Asserting properties of one screen is weaker and easy to get wrong. Two traps found doing
  it: the harness repaints only changed rows, so pyte must be fed the **whole byte stream** from
  startup (feeding a later chunk into a fresh `pyte.Screen` shows an empty composer), and
  `dependency:copy-dependencies` must be re-run after any `clean` or the packaged example starts with
  `NoClassDefFoundError` for the bundled transcript. A long draft scrolls the composer horizontally
  and clips the caret off-screen - that is expected, not a defect.

## Measured gaps and what has been fixed

- **Fixed (cycle 224, commit 8392f51)**: `TextMeasure.isBoundary` scanned over all indices was
  quadratic: 256 chars 0.49 ms / 1.7 MB, 4096 chars 119.9 ms / 466 MB; a single call on a 4096-char
  draft cost ~29 µs and ~114 KB. The four boundary lookups now answer locally when the gap asked
  about, and the gap returned, sit between printable ASCII characters: 35.1 µs and 113.8 KB per
  4096-char scan, and a 256-lookup probe fell from 148.9 MB / 69.5 ms to 48 B / 0.1 ms.
  `EditorRenderBenchmark` and `FramePipelineBenchmark` did not move beyond their error margins - the
  editor render path segments each line once already, so this cost was never in the frame path.
- **Durable fact**: `BreakIterator.setText(text)` copies the whole text into the iterator's own
  buffers on every call (constructing the iterator is cheap; `setText` is not). Reusing or cloning
  one iterator turned 148 MB into 25 MB, not 0, so iterator reuse is not the fix - answer the
  question locally, or segment once per operation.
- **Durable fact**: no UAX #29 grapheme break rule joins two characters in 0x20-0x7E, so a gap
  between two printable ASCII characters is a cluster boundary whatever the rest of the text holds.
  `TextMeasureBoundaryCostTest` pins that by comparing every boundary method against the break
  iterator at every offset of a corpus of combining marks, ZWJ emoji, variation selectors, a lone
  surrogate, tabs, CR/LF and newlines, and measures allocation with
  `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(long)` (HotSpot-only; selected with
  `Thread.currentThread().threadId()`), which is deterministic enough for a 256x margin.
- **Fixed (cycle 226, commit ebb2fdd)**: `Editor.render` of a 4096-char draft cost 0.53-0.60 ms
  (ascii) / 1.12 ms (cjk) and 8.6-17.4 MB per call. The cost was in `Editor.renderLine`, which
  accumulated the run of same-styled clusters with `pending += display` - a fresh copy of the whole
  prefix per cluster. One `StringBuilder` instead: ascii/4096 597,593 -> 41,902 ns/op (14.3x) and
  8,627,375 -> 70,872 B/op (122x); cjk/4096 1,118,682 -> 135,843 ns/op and 17,424,789 -> 496,009
  B/op. `EditorRenderCostTest` pins it with allocation bounds plus a cluster-by-cluster reference
  comparison of the painted text, cursor and masking.
  **Durable fact**: render allocation scales with how the line is *accumulated*, not with how it is
  segmented - `TextMeasure.graphemes` of the same 4096-char line costs 51 KB, so an 8.6 MB render was
  never a segmentation cost. Measure the two separately before chasing a render cost.
- **Fixed (2026-10-21, user-authorized performance work)**: append chains keep private immutable
  operands and normalize on first span access, then release the operands. The public `Span` record
  and public signatures are unchanged. The earlier assumption that a public builder/record change
  was necessary was incorrect. Preserve the first operand's existing internal span boundaries
  (e.g. after `withStyle`), and normalize only subsequent appended spans; eager-reference and
  concurrent-read tests cover this. See `plans/performance-fixes.impl.md` for measured results.
- **Historical pre-fix baseline**: one 40x120 frame through `ScreenRenderer` cost 206 µs and 739 KB
  before encoding; do not treat this as a measurement of the current implementation.
- **Fixed (cycle 225, commit fa6f81a)**: a 64 KiB bracketed paste through `PasteDecoder.read` cost
  2.37 ms / 793 KB because `readByte` called `available()`, `read()` and `System.nanoTime()` once per
  byte (101,952 of each) and a `ByteArrayOutputStream` doubled and was then copied again. The stream
  is now asked what it holds once per burst and the payload goes into one array sized from that
  burst: 473,660 ns/op (5.0x) and 539,035 B/op.
  **Durable fact**: the byte-at-a-time `read()` has to stay. `PasteDecoder` must not consume anything
  after `ESC[201~` (pinned by `InputDecoderTest.inputAfterAPasteIsLeftInTheStream`), and a bulk
  `read(byte[])` would take the keystrokes that follow the marker with no way to hand them back -
  a private `PushbackInputStream` would strand them. Batching the *availability and clock* checks is
  the safe win; batching the *reads* is not, unless the leftover bytes are given a home.
  **Durable fact**: `new String(bytes, 0, len, UTF_8)` is itself ~437 KB for a 102 KB non-Latin-1
  payload (the JDK copies the input and then builds a UTF-16 array), so an allocation bound on a
  decode path should be stated relative to that String's own cost, not as an absolute number.

## Performance fixes preserving public behavior (2026-10-21)

- `Editor.indexAtColumn` still called whole-draft `boundaryAfter` once per non-ASCII target
  cluster even after renderLine was fixed. Segment the target line once. `EditorNavigationCostTest`
  compares vertical movement with a whole-draft BreakIterator reference, including CR/LF and tabs.
- Bounded paint needs streaming grapheme iteration **and** a row-limited wrapper; taking a sublist
  after full wrapping does not bound allocation. Public intrinsic measurements remain complete.
  START-aligned columns do not use intrinsic cross-axis widths, so skip that measurement.
- Repeated zero-width format clusters append to the last cell without advancing its column.
  Accumulate that suffix and update the cell once; rebuilding a String per format cluster is
  quadratic. Iterate code points directly when measuring widths, rather than allocating streams
  and arrays per cluster.
- Pure harness projection can wrap entries and count rows first, then style/pad only the selected
  window. This avoids introducing mutable caches into the model or projection. Word wrapping can
  append ordinary ASCII runs by source range without allocating each word as a substring.
- Focus search can push/pop one owner-local ancestor path instead of copying every prefix.
- Validation included 669 passing tests, public javap parity for 151 existing class listings,
  2,000 generated layout comparisons, 200 transcript comparisons, and eight identical pyte
  snapshots from baseline/fixed packaged sessions. Full benchmark numbers remain machine-specific.

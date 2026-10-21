# Project Memory

This file indexes useful project knowledge retained across agent sessions. It is historical context,
not an authoritative source of requirements; verify details against `PROJECT.md` and current code.

## Index

- [HUNT_LOOP working notes](memory/hunt-loop-notes.md) - the producer hand-off retry policy, the
  pyte harness probe recipe, the content/surrogate and border-visibility rules pinned in cycles
  187-196, the markup/editor/widget documentation rules and the two reproducibility oracles added
  in cycles 197-216, the one-line prefix rules for markup/selector/quote/progress-bar/editor
  options, the subscription null-emission rule, the table-selector cell one-line rule, and
  coverage-hunting observations.
- [JMH benchmark harness](memory/jmh-benchmarks.md) - how to run the offline benchmark suite, the
  JDK 23+ annotation-processor and `-Xlint:processing` build traps, the evidence discipline for
  benchmark numbers, and the measured performance gaps recorded as hunt candidates.

## Maintenance

- Search for an existing entry before adding information.
- Update obsolete entries instead of appending conflicting claims.
- Never record secrets, credentials, or transient conversation details.

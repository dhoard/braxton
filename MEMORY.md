# Project Memory

This file indexes useful project knowledge retained across agent sessions. It is historical context,
not an authoritative source of requirements; verify details against `PROJECT.md` and current code.

## Index

- [JMH benchmark harness](memory/jmh-benchmarks.md) - how to run the offline benchmark suite, the
  JDK 23+ annotation-processor and `-Xlint:processing` build traps, the evidence discipline for
  benchmark numbers, and historical measurements and verified performance fixes.
- [Baseless-cluster painting in the layout engine](memory/render-cluster-attachment.md) - the
  measure-equals-paint width invariant (one painted cell per cluster of the plain text), why plain
  text contiguity - not "a cell was painted" - decides that, the known span-initial-extend
  divergence, and the quadratic-copy trap `ClippedLayoutCostTest` guards.

## Maintenance

- Search for an existing entry before adding information.
- Update obsolete entries instead of appending conflicting claims.
- Never record secrets, credentials, or transient conversation details.
- During externally orchestrated SDLC runs, keep this index and its topic files read only;
  use the orchestrator's external run artifacts for current progress and results.

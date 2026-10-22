# Baseless-cluster painting in the layout engine (added 2026-10-21, corrected 2026-10-22)

The invariant behind the width arithmetic: **a painted row occupies the columns `TextMeasure.columns`
(and so `StyledText.columns`) reports for the same plain text.** `Box` measures
`vertical + line.plainText()` and `horizontal + " " + label + " "` as one cluster sequence, so the paint
path in `LayoutEngine` must place one cell per cluster of that plain text - never one cell per span
cluster.

## What the paint path does now

- A cell is one cluster of the **plain text**, not of a span. `paintLine` accumulates the clusters of
  each span into the cell it is painting and only paints the cell when no later cluster continues it
  (`continuesCluster`: `TextMeasure.graphemeCount(cell + cluster) == 1`). That covers the reported
  "styled title" case (`"e"` in one span, `U+0301` in the next) and a selector that widens the base
  (`"b"` + `U+FE0F`).
- The test is plain-text contiguity, not "a cell was painted". `TextMeasure` segments with
  `BreakIterator`, which breaks after a control such as `U+200B`, `U+00AD`, `U+FEFF` (and after a
  tab). The mark after a control is a cluster of its own that the measurement counts as one column,
  so it must keep its own cell. Joining on "a cell exists" instead folded that column into the
  previous cell and moved every later cell - a frame's right border included - one column left.
- No width clamp: a joined cell takes `TextMeasure.clusterColumns` of the whole cluster (at most two
  columns for a single cluster), so no measured column can be dropped. `Screen` still clamps what it
  is handed to a two-column cell, and the measurement never asks for more.
- `wrapLines` decides the cell's columns **before** it commits the cell to a line: it peeks at the
  clusters that continue the base (`ClusterCursor`) and places the whole cluster as one unit. A
  selector that widens a base with no column left moves the pair to the next line; dropping it would
  change the content a style boundary must not touch. The reserved width comes from the same
  plain-text cluster, so `intrinsicHeight` and the painted rows agree.
- A cluster with no base of its own (`hasNoBase`) still gets `TextMeasure.DOTTED_CIRCLE` where no
  cell before it continues it; `wrapLines` adds it when it starts a cell, and `intrinsicHeight`
  therefore counts the same column. Zero-width clusters that lead a row take no column; `paintLine`
  holds them for the cell that follows so a span boundary does not drop them.

## Why it matters (regression history)

An earlier fix corrected `Box` padding for an unstyled title, where the mark and the frame merged into one
span. A span boundary (styled title, `StyledText` content) kept the mark in its own span, and
`paintLine` gave it a dotted-circle cell: the frame row gained a column, its right corner wrapped to
the next row, and `intrinsicHeight` reported one row more than `render`. The fix replaced the
per-cluster "paint a dotted circle" rule with cells that hold one cluster of the plain text.

Tests: `LayoutEngineTest.aStyleBoundaryInsideAClusterDoesNotAddAColumn`,
`…aSelectorSplitAtTheLineEdgeMovesTheWholeClusterToTheNextRow`,
`…aControlBeforeABaselessClusterKeepsTheColumnTheMarkMeasures`,
`…aTabStopAndABaselessClusterKeepTheColumnsTheyMeasure`,
`…aStyleBoundaryNeverChangesTheLayout`, and in `BoxFrameWidthTest`
`aStyledTitleWithABaselessClusterKeepsTheFrameWidthThroughPaint`,
`…aStyledContentWithABaselessCluster…` (both cell-level: re-measuring the row's plain text hides a
lost column), `…aControlBetweenABaseAndABaselessClusterKeepsTheFramesColumns`.

## Known remaining divergence (pre-existing, not introduced here)

A span that **starts** with an extend character before its own base (`U+FE0E`/`U+FE0F` + CJK in one
span) is still clustered by the span, not the plain text: the painted cell measures two columns where
`TextMeasure` reads the selector and the base as two clusters. Differential against HEAD (`styled`
value vs the same value as one plain span, random CJK/emoji/marks/ZWSP/BOM/soft-hyphen/tab/newline
tokens and span splits, 33000 cases per seed): seed 7 - 5688 cases fixed, 0 newly differing, 487
remaining; seed 12345 - 5232 fixed, 1 newly differing, 322 remaining. Every remaining case fails at
HEAD too, and the single one that differs in the other direction is the same span-initial-extend
family clipped into a one-column viewport (a tab stop plus format characters, six measured columns
into one). Fixing that family means segmenting the line's plain text and mapping clusters back to
spans in both `paintLine` and `wrapLines`.

## Cost constraints that are easy to regress

- `ClippedLayoutCostTest.zeroWidthSuffixIsAccumulatedBeforeRepaintingItsCell` guards the zero-width
  run: `paintLine` appends zero-width clusters to the cell's `StringBuilder`, and the contiguity test
  is skipped for clusters that claim no column and hold no mark, so a run of `U+200B` neither copies
  nor re-segments the growing cell. Do not run `continuesCluster` per zero-width character.
- A long run of combining marks still re-measures the growing cluster per join; that is proportional
  to the cluster, and no test floods marks the way the zero-width test floods `U+200B`.
- Lookahead allocation: `ClusterCursor` reuses one ahead slot, and `Piece` is allocated only when a
  style boundary actually splits a cell.

# Braxton — autonomous single-cycle defect hunt

You are operating inside the **Braxton** repository (`https://github.com/dhoard/braxton`), a Java 25, zero-core-runtime-dependency, Model–Update–View terminal UI framework. Your mission is to find **real, previously unaddressed correctness defects** and fix them with reproducible evidence. Think deeply, inspect widely, and use as much reasoning and local testing as useful. **This invocation completes exactly ONE defect cycle and exits.** An external shell loop performs subsequent cycles and runs `./mvnw clean package` after your process exits successfully.

## Non-negotiable operating rules

1. Read `AGENTS.md`, `PROJECT.md`, `MEMORY.md` (and relevant linked memory), existing `HUNT.md` / `HUNT_LOOP.md` for useful conventions, current source/tests, and any path-specific `AGENTS.md`. Respect higher-priority project instructions. Existing hunt documents provide background; **this file defines this invocation's one-cycle behavior**.
2. Work autonomously: investigate, implement, test, and complete a verified change without seeking permission for ordinary in-scope local engineering decisions. Never invent requirements, bugs, passing tests, or performed work. If correctness is ambiguous, establish the intended contract from current source, tests, and public documentation before editing.
3. **Exactly one independently meaningful bug per invocation.** Do not fix unrelated issues, perform cosmetic refactors, optimize without a correctness defect, or add speculative APIs. Spend effort finding a substantial bug rather than merely selecting the easiest change.
4. Preserve existing edits and untracked files. Check `git status --short` at start and before closing. No `git reset --hard`, `git clean`, force pushes, or overwriting user work. Do not push, deploy, publish, or modify external systems.
5. Do not edit the caller's autonomous shell script. Do not create or delete the `STOP` sentinel; it is the user's control. Do not modify the workflow prompt as a way to make an iteration pass.
6. Treat the wrapper's `./mvnw clean package` as an **additional external gate**, not a replacement for your own focused regression tests, formatter, and quality checks. Exit successfully **only** after your local changes have been validated. If you cannot establish a safe, verified outcome, report the blocker and return a nonzero exit status when possible so the outer loop does not continue under a false success signal.
7. You have a generous reasoning and test budget, **not** permission for unbounded real-time loops. Do not sleep, wait for future cycles, or recurse into Amanda or this workflow. Exhaust useful lines of inquiry during the current invocation, then close it.

## What to hunt: Braxton-specific correctness targets

Systematically rotate through these areas instead of repeatedly picking the same obvious test. Prioritize externally observable failures, state corruption, deadlocks, race conditions, resource leaks, invalid rendering, and safety/security boundary violations:

- **MVU ownership and update/effect order:** single-owner invariant; stale message delivery; `init`/`update`/`view` purity assumptions; queue backpressure and termination; effect cancellation and namespace collisions; epoch guarding; close-once subscriptions; stale source callbacks.
- **Terminal lifecycle and concurrency:** partial initialization, exceptions during setup, raw-mode restoration, shutdown hook races, blocked reads, end-of-input, resize/read cancellation, output serialization, screen/presentation pacing, interrupt behavior, virtual-thread cleanup.
- **Input parsing and focus:** fragmented CSI/ESC sequences, timeouts, malformed or oversized inputs, bracketed paste boundaries, modifier interpretation, focus identity, modal capture/bubble order, duplicate bindings, key handling versus printable text insertion, mouse/resize paths.
- **Unicode/text/editor:** grapheme clusters, ZWJ sequences, combining marks, surrogate pairs, East Asian width, zero-width characters, UTF-16 caret offsets, editing selection boundaries, multiline paste, truncation, invalid strings, masking and copy restrictions.
- **Layout and rendering:** zero/tiny viewports, width/height overflows, negative space, clipping wide glyphs, nested rows/columns, overlay occlusion, cell diff stability, style reset and terminal escape encoding, excessive dimensions and integer overflow.
- **Widgets, markup, clipboard:** malformed markup, escaping, parser security, OSC 52 truncation and transport boundaries, per-session buffers, copy deduplication, stateful builder misuse, image sizing and asset edge cases.
- **Bundled AI Harness example:** transcript races, generation/ordinal invalidation, task completion after cancellation, delayed updates, clipboard interaction, animation subscription disposal.

These are **candidate areas, not assertions that defects exist**. Inspect implementation paths and build a falsifiable hypothesis before declaring a bug.

## Cycle protocol

### 1. Discover state and history

- Confirm you are operating in the intended checkout. Read project guidance and relevant contracts.
- Capture the starting revision, `git status --short`, and any preexisting test failures. Do not take ownership of dirty files without a safe way to isolate your changes.
- Read `HUNT_TRACKING.txt` if present. It may contain entries from other hunt workflows. Preserve the complete history; identify previously closed hypotheses, pending candidates, and uninspected areas. Append records tagged `Workflow: HUNT2`, never rewrite old ones. If missing, create it without erasing other files.
- Inspect the current build configuration and CI, particularly the project's exact verification commands. `PROJECT.md` currently requires `./mvnw spotless:apply` after source changes and terminal checks using **pyte** after TUI code changes; re-read it for latest instructions. A referenced final gate `./mvw clean package` may be a typo: check actual executables rather than silently assuming; the outer wrapper separately uses `./mvnw clean package`.

### 2. Explore until you have one credible hypothesis

- Pick an unexamined or unresolved subsystem from the rotating target list above. Read callers, callees, invariants, contracts, edge cases, and nearby test coverage.
- Challenge implicit assumptions with boundary values, adversarial but locally safe inputs, repeated lifecycle operations, malformed sequences, and controllable interleavings.
- Search for **behavioral mismatches**, not just suspicious-looking code. Ask: what should happen, what actually happens, and what observation distinguishes them?
- If a hypothesis is disproved, record the evidence as an invalidated candidate and continue investigating **within the same invocation** until you find one confirmed defect or reach a genuine blocker. Avoid redundant exploration of previously dismissed leads unless the code changed.

### 3. Prove the defect before repairing it

- Create the smallest deterministic JUnit regression (or appropriate project-native test) using owned temporary resources. Prefer a unit/headless test over a flaky live terminal test; use a real/pyte terminal-emulation test when the behavior genuinely requires it.
- Run the new test against unmodified production code; confirm that it fails for the **intended behavioral reason**. Capture the exact command and key failure evidence. A compilation error, flaky timing failure, environmental issue, or misinterpreted contract does not prove a bug.
- For concurrency problems, use controlled barriers/latches/fake clocks or deterministic scheduling seams where possible instead of arbitrary sleeps. Use time bounds so tests cannot hang.
- If the suspected issue is not reproducible, do not patch production code on speculation.

### 4. Repair the ownership boundary

- Apply the smallest complete fix at the component responsible for the invariant. Avoid masking failure in a caller if the underlying invariant belongs in a lower layer.
- Cover the confirmed failure plus adjacent negative/boundary scenarios and ensure the original contract remains intact.
- Do not add new runtime dependencies, weaken tests, introduce debug switches, or change public behavior except as necessary to correct the defect. Update documentation if the observable contract truly changes.

### 5. Verify without shortcuts

Run and record the results of:

1. The focused failing-before regression test, now passing.
2. Relevant adjacent tests for the touched component.
3. `./mvnw spotless:apply` after changes, followed by an explicit diff inspection for formatter-caused changes.
4. The applicable full Maven build/test gate derived from current project configuration; at minimum run `./mvnw clean package` unless it is clearly impossible. If formatting modifies source, rerun relevant tests.
5. For terminal/TUI code changes, a meaningful **pyte** terminal-emulator validation as required by `PROJECT.md`; verify both rendered output and terminal lifecycle/escape behavior as relevant. Follow its environment instructions without altering global packages unnecessarily.
6. `git diff --check`, `git status --short`, and an exact review of all changed files. Check tests weren't skipped and no broad changes slipped in.

If a required gate fails, distinguish baseline/preexisting failures from newly introduced defects. Resolve new failures before declaring success; never skip tests to make the loop proceed.

### 6. Persist verified progress and exit

- Append a concise entry to `HUNT_TRACKING.txt` with date/time, `Workflow: HUNT2`, candidate ID, subsystem, hypothesis and observed failing-before proof, root cause, production/test files changed, exact validation commands/results, disposition (`CLOSED`, `INVALIDATED`, `BLOCKED`), and **next uninspected areas** for future invocations. Preserve previous entries.
- If the repository's active instructions and this invocation authorize local commits, commit **only verified, owned code/tests/docs**, with a descriptive message; do not stage unrelated changes. Do not commit the tracking file unless project policy explicitly tracks it. Otherwise leave verified changes available for the wrapper's build and report their paths. Never push.
- Print a compact completion report with one of:
  - `CLOSED`: confirmed defect, failing-before evidence, fix, passing checks, changed files, and commit ID if any.
  - `INVALIDATED`: no production bug fixed; useful validated regression coverage retained, evidence, and checks. Only use when substantive new coverage was added and verified.
  - `BLOCKED`: explicit reason and uncompleted work/checks, with no claim of success.
- **Stop after one closed defect or one substantive coverage-only invalidation.** Do not start another fix, even if much time or context remains. The outer script will launch a fresh process for the next cycle.

## Success criteria

A successful hunt cycle produces a **provable correctness improvement**: a regression that failed before the fix, passes after the fix, and remains in the suite, together with clean required validation and no unrelated changes. Finding no defect is acceptable; inventing one, silently changing contracts, or returning success after failed gates is not.

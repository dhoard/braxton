# Continuous bug-hunt workflow

Run the bug-hunt cycle continuously: investigate a contract violation, reproduce it, fix the
owning boundary, validate, close the change, record, report, and begin the next cycle. Retain and
validate tests that invalidate a suspected defect as separate coverage changes.

Read applicable [AGENTS.md](AGENTS.md) files, root [PROJECT.md](PROJECT.md), and this document
before acting, including direct invocation of this document. Resolve them from the intended
project root, not an assumed VCS root or current invocation directory. Discover actual project
contracts and tools; do not assume a language, framework, layout, build system, CI, or VCS.

An explicit request to run this workflow authorizes the scoped implementation and validated
commits described below, subject to the user's restrictions and `PROJECT.md`. Merely reading,
copying, reviewing, or editing this file does not start the loop. This workflow alone does not
authorize pushes, installation, deployment, real-user-state tests, or external-service operations.

## Constraints and authority

- **One defect per cycle.** Select one candidate and fix one owning boundary. Do not bundle
  unrelated defects, refactors, or cleanups into the same cycle.
- **Evidence over assumption.** Treat notes, commit messages, and earlier records as leads and
  baselines, not as proof of current behavior. Re-read source and re-run checks.
- **Preserve user work.** Never discard uncommitted edits, untracked files, runtime history, or
  test artifacts you do not own. Never use destructive reset/clean operations.
- **Smallest coherent change.** Follow repository conventions and preserve public compatibility,
  schemas, protocols, persistence, and user-visible behavior unless the defect requires a
  deliberate contract change; make such changes explicit and update all affected consumers.
- **Do not weaken oracles.** Do not delete, skip, or loosen tests, assertions, lint rules, or
  gates to manufacture success. A compile error, skipped test, or invalid expectation is not
  evidence.
- **Owned state only.** Do not modify real user data, production services, global caches,
  credentials, or external accounts. Tests must use owned temporary state.
- **No expanded side effects.** Do not push, publish, deploy, install, or change external
  infrastructure. This workflow authorizes local implementation, local checks, and scoped commits
  only.
- **Serialized writers.** Do not run concurrent code-changing hunts or other writers in the same
  workspace or history. Do not begin a cycle over unresolved owned edits that would contaminate
  its baseline. No locking mechanism is provided; coordinate explicitly.
- **Honest reporting.** Never claim a check, commit, or closure happened when it did not. Report
  unrun or failing gates as gaps.

## Tracking

Use exactly one fixed file, `<project root>/HUNT_TRACKING.txt`, recording `Workflow: HUNT_LOOP` in
each entry. Read it before selecting or resuming a candidate. Create it if it does not exist. It is
append-only: preserve existing records, candidate identifiers, queue entries, inspected areas, and
pending work; never rewrite or delete prior history.

Each closed, invalidated, or blocked cycle appends a record containing:

- date/time and the `Workflow:` name;
- candidate identifier, area inspected, and prior leads considered;
- suspected defect and the executed evidence (oracle/test and observed result) produced before any
  production change;
- owning boundary and a summary of the production change and retained tests;
- focused and broader validation commands with observed results;
- closure: commit/checkpoint identifier, or an explicit statement that none was possible;
- status (`CLOSED`, `INVALIDATED`, or `BLOCKED`) and the next queue/pending items.

On stop, pause, or interruption, append the exact stage, outstanding changes and checks, and
concrete resume instructions instead of leaving the file silent.

## 1. Establish the baseline

1. Re-read applicable `AGENTS.md`, `PROJECT.md`, this workflow, and the tracking file.
2. Determine workspace/VCS state: current revision, pre-existing modified and untracked files, and
   any runtime history or artifacts you must not disturb. Preserve them.
3. Discover the actual build, test, lint/format, and platform gates from project configuration,
   `PROJECT.md`, and CI definitions. Do not invent commands.
4. Run the baseline gate (or the narrowest applicable subset) before changing production code and
   record the result, including pre-existing failures.
5. Confirm the baseline is isolated enough to attribute new results to this cycle. If unresolved
   owned edits would contaminate it, stop and record the blocker rather than guessing.

## 2. Select a candidate

Choose one specific, testable suspected contract violation:

- resume a pending candidate from `HUNT_TRACKING.txt`, or inspect an area not yet settled there;
- state the violated contract, the owning boundary, and the observable oracle that would
  distinguish correct from incorrect behavior;
- prefer a candidate whose baseline and fix remain within one component and can be validated with
  deterministic, offline tests;
- do not repeat a settled lead without new evidence, and do not invent a defect to keep busy.

If inspection finds no testable candidate, record the inspected areas and broaden the search within
the authorized scope rather than inventing a defect.

## 3. Reproduce and confirm

Before changing production code, add the most focused deterministic test that demonstrates the
suspected defect, and run it. Record the actual failing output in the tracking file.

- Use owned temporary fixtures and avoid network access or real external state.
- A failure caused by a compile error, a skipped/disabled test, an invalid expectation, or an
  unrelated pre-existing defect does not confirm the candidate; correct the test or discard the
  candidate.
- If the evidence invalidates the suspicion, do not change production code. If the test asserts
  intended existing behavior, keep it as a retained coverage change, record the invalidation
  evidence, and close the cycle as `INVALIDATED`.
- Never weaken the oracle to obtain a fix.

## 4. Fix the owning boundary

Implement the smallest coherent production change at the boundary that owns the contract:

- follow repository conventions for naming, architecture, error handling, resource cleanup, and
  state transitions;
- preserve compatibility and data integrity, and update every affected consumer if a contract
  genuinely changes;
- update generated sources through their generator rather than editing generated output;
- avoid unrelated refactoring, speculative abstractions, dead scaffolding, debug code, and new
  TODOs;
- update authoritative documentation and fixtures when behavior or contracts change.

## 5. Validate

Run, at minimum:

1. the focused regression test(s) from step 3, now passing;
2. the project's full test gate discovered in step 1;
3. formatter/linter/static-analysis and packaging gates required by `PROJECT.md` or CI;
4. any component-specific check the project requires (for example, a terminal-emulation check for
   TUI changes);
5. `git diff --check` and a full review of the diff when Git is present; otherwise an explicit
   inventory of every modified path.

Do not claim success while a required gate fails or remains unrun. Distinguish new failures from
the recorded baseline and report remaining risks.

## 6. Close the change

When the implementation is validated:

- stage only the files owned by this cycle; do not stage unrelated user edits;
- commit with a descriptive message following project conventions (including any required
  sign-off), if the workflow invocation authorized commits and a usable VCS exists;
- leave `HUNT_TRACKING.txt` as the untracked running record unless the project explicitly tracks
  it;
- if no VCS exists, do not initialize one; report the change inventory and the missing commit
  capability;
- never push, publish, deploy, or install.

If validation cannot be completed, leave the work uncommitted, preserve the useful tests, and
record the cycle as `BLOCKED`.

## 7. Record and report

Append the record described in [Tracking](#tracking), then report to the user:

- the candidate and the observed evidence;
- the owning boundary and files changed;
- the exact checks run and their observed results, distinguishing new failures from baseline;
- the commit or checkpoint, or why none was possible;
- remaining gaps, risks, and anything the user must authorize.

## 8. Continuous cadence

This section defines when the next cycle starts; it does not override the constraints, procedure,
validation, closure, or tracking rules above.

After each validated fix or retained-coverage change is closed, append its evidence to
`HUNT_TRACKING.txt`, send an intermediate progress report, then return immediately to
[baseline discovery](#1-establish-the-baseline). Do not ask for per-cycle confirmation or stop
merely because one cycle succeeded. Each candidate gets a fresh baseline, oracle, checks, and
separate closure; do not combine defects or amend prior cycles.

If a candidate is blocked, record the reason and preserve useful tests/work. Investigate another
independent candidate only if its baseline can be isolated safely. Report and stop when unresolved
constraints, environment, authorization, ownership, validation, or closure prevent all further
safe progress. A problem affecting one candidate does not automatically block unrelated safe work.

If inspection finds no testable candidate, record the inspected areas and broaden the search within
the authorized scope. Do not invent a defect or weaken the oracle to obtain a fix. Keep the queue
and settled leads in the tracker so repeated cycles produce new evidence rather than repeating the
same inspection.

Continue until the user stops/pauses the workflow or a blocker prevents all further safe progress.
Respect explicit run limits and higher-priority tool/environment limits; persistence never expands
authorization or justifies skipped checks. Send progress updates during investigation and
validation.

On stop, pause, or interruption, stop starting work, clean up owned resources when possible, and
append the exact stage, outstanding changes/checks, and resume instructions to
`HUNT_TRACKING.txt`. Leave incomplete work uncommitted, preserve valid coverage, and summarize
actual completed commits/checkpoints and remaining work. A future invocation reads this same
history and reinspects the project before resuming.

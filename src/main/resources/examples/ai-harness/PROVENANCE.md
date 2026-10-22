# AI Harness example transcript

`session.jsonl` is example data for the AI Harness showcase. It is not a live recording and is
never refreshed at runtime.

## Source

The transcript is copied from a real local Amanda session log recorded on 2026-10-17 in this
repository:

```text
~/.amanda/projects/mnt/storage/Development/github/dhoard/braxton/sessions/
    01a14c5c-c2b7-756e-9291-62d0bed736a4/session-log/00000001.log
```

That session ran the project's `HUNT_LOOP.md` workflow for 45 of the 50 requested cycles, in two
turns. The workflow documents and tracker quoted in the fixture have since been retired.
Their contents are historical example data, not active project instructions or runtime dependencies.
The fixture keeps:

- the greeting turn (`u there?`) from the previous fixture, with its profile fields set to the
  recorded profile;
- a contiguous slice of the first real turn: its `input.attached` and `turn.started` records plus
  the work through cycle 13 (source records 3-171 of 1,692: 169 records, 88 tool calls, 62 of them
  longer than the six-line preview).

So the second turn ends mid-work, at the "Cycle 13 CLOSED" report; the remaining cycles, the three
compaction events, and the session's closing report are omitted. Nothing in the slice was
reconstructed or invented.

## Transformations

Only identifiers and host details were changed:

| Change | Reason |
| --- | --- |
| Session, input, turn, step, group, and tool-call ids replaced with `demo-*` values | Session identity is not part of the example |
| Profile id replaced; provider, model, and reasoning kept (`deepseek`, `deepseek-flash`, `high`) | Real values without a local profile id |
| Absolute project path replaced with `~/braxton`; `/tmp/...` probe directories with `probe/...` | No absolute paths or user directories |
| Every email address replaced with `dev@example.invalid` (commit `Signed-off-by` lines and the third-party FIGlet/TOILET font notices) | No personal data |
| Time-zone offsets in Maven's `Finished at:` timestamps replaced with `Z` | No host time-zone detail |
| Assistant reasoning shortened to at most 240 characters, cut at a word boundary | The showcase paints reasoning as a full transcript block; full reasoning made the demo unreadable |
| The greeting turn's profile fields set to the recorded profile | One consistent profile across the fixture |

Everything else is verbatim: prompts, commands, tool output (including long test logs, file
contents, and commit output), assistant text, and per-step usage. The slice deliberately contains
long tool results, so the transcript exercises preview truncation, scrolling, and the scrollbar.

The `context.compacted` records in the source log are not included; the reader ignores unknown
event types in any case.

## Runtime behavior

The completed demo loads this file from the classpath. It does not read `~/.amanda`, an external
session path, an environment variable, or any user state.

No credentials, tokens, or personal data are present.

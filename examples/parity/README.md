# Parity examples

Runnable demos and terminal scenarios cover the implemented parity phases. The Java applications
live under `src/main/java/examples/parity` and ship in the Braxton jar. Markdown rendering also
requires the declared CommonMark runtime dependencies, as described under "Launching a demo."

The registries in `tools/terminal/verify_terminal.py` define the available phases and packaged demo
launch checks. `SceneCatalog` defines which scenes the integrated showcase can mount; standalone
terminal and line-reader probes also have their own launches.

## Runnable

| Phase | Application | Scenario |
| --- | --- | --- |
| 01 — keymaps and help | `examples.parity.KeymapHelpDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 01` |
| 02 — enhanced keyboard and terminal focus | `examples.parity.KeyboardFocusDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 02` |
| 03 — native and virtual cursors | `examples.parity.CursorDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 03` |
| 04 — complete multiline editor | `examples.parity.EditorDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 04` |
| 05 — single-line text input | `examples.parity.TextInputDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 05` |
| 06 — pagination state and indicators | `examples.parity.PaginatorDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 06` |
| 07 — configurable spinners | `examples.parity.SpinnerDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 07` |
| 08 — animated progress and blended fills | `examples.parity.ProgressDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 08` |
| 09 — countdown timer | `examples.parity.CountdownDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 09` |
| 10 — stopwatch | `examples.parity.StopwatchDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 10` |
| 11 — rich filterable lists | `examples.parity.RichListDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 11` |
| 12 — rich text viewport and pager | `examples.parity.ViewportDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 12` |
| 13 — scrollable interactive tables | `examples.parity.TableDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 13` |
| 14 — file picker | `examples.parity.FilePickerDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 14` |
| 15 — markdown rendering | `examples.parity.MarkdownDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 15` |
| 16 — tabs and composed views | `examples.parity.TabsDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 16` |
| 17 — mouse events and hit testing | `examples.parity.MouseDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 17` |
| 18 — terminal queries and color profiles | `examples.parity.TerminalQueryDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 18` |
| 19 — terminal and primary clipboard | `examples.parity.ClipboardDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 19` |
| 20 — declarative terminal presentation | `examples.parity.TerminalPresentationDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 20` |
| 21 — process handoff and suspend/resume | `examples.parity.ProcessHandoffDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 21` |
| 22 — command ordering and application control | `examples.parity.ApplicationControlDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 22` |
| 23 — inline output, streaming and headless I/O | `examples.parity.InlineIoDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 23` |
| 24 — integrated showcase and composed workspace | `examples.parity.Showcase` | `python3 tools/terminal/verify_terminal.py --parity-phase 24` |
| 25 — terminal capability matrix | `examples.parity.TerminalMatrixDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 25` |
| 26 — shared state engines | `examples.parity.EngineDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 26` |
| 27 — element and widget kernel | `examples.parity.ElementKernelDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 27` |
| 28 — widget gallery | `examples.parity.WidgetGalleryDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 28` |
| 29 — line prompts | `examples.parity.LinePromptsDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 29` |
| 30 — images and transitions | `examples.parity.RichOutputDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 30` |
| 31 — developer diagnostics | `examples.parity.DebugOverlayDemo` | `python3 tools/terminal/verify_terminal.py --parity-phase 31` |


## Launching a demo

Build first (`./build.sh`), then run the packaged application. A demo needs only the Braxton jar,
except one that exercises a declared runtime dependency: `MarkdownDemo` renders markdown through the
CommonMark parser, which the jar does not shade, so it is launched with the dependencies the build
copies beside it. The AI Harness example's Gson dependencies are never on a parity demo's classpath.

```bash
java --enable-native-access=ALL-UNNAMED -cp target/braxton-0.1.0.jar examples.parity.KeymapHelpDemo

./mvnw dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/showcase-lib
java --enable-native-access=ALL-UNNAMED -cp 'target/braxton-0.1.0.jar:target/showcase-lib/*' \
     examples.parity.MarkdownDemo
```

`--enable-native-access=ALL-UNNAMED` is required because Braxton owns the POSIX terminal.

### Phase 01: `KeymapHelpDemo`

Two mounted selectors, a status row, and a help block. The scene's own actions and each selector's
actions are `KeyMap` values, so the bindings the view routes and the text the help renders come from
one definition.

| Key | Action |
| --- | --- |
| `?` | switch between compact help and the grouped full help |
| `r` | remap the toggle-help action onto `h` and disable itself |
| `tab` | move focus between the two selectors |
| `q`, `ctrl+c` | quit |
| `↑` / `↓`, `enter`, `esc` | the focused selector's own actions |

The status row spells out what the model holds, so a terminal verifier can assert the scene
without reading styled cells:

```text
left=alpha right=alpha focus=left help=compact keys=default last=none
```

After `r` the same row reads `keys=remapped` and the help line shows `?/h toggle help` with no
`r remap help key`: the alias changed in routing and help at once, and the disabled action fires
nothing.

### Phase 02: `KeyboardFocusDemo`

One editor field and a status block. The session asks the terminal for the enhanced keyboard flags it
needs and for focus reporting, then reports what actually arrived: an unenhanced terminal leaves the
capability row at `caps=none` and keeps sending legacy input.

| Key | Action |
| --- | --- |
| `?` | switch between compact help and the grouped full help |
| `f` | move the field's own focus, which terminal focus never changes |
| `q`, `ctrl+c` | quit |
| any other gesture | recorded, and applied to the field when it is focused |

The status block spells out the state, so a verifier can assert the scene without reading styles:

```text
caps=none event-types=off focus-report=on
last=none term=focused
editor= field=focused
```

`caps=` is the flag set the terminal reported, `event-types=` says whether it includes event types,
`last=` is the most recent key event as `kind:key`, `term=` is the terminal window's own focus, and
`field=` is the editor's model focus. After a terminal answers `CSI ? 27 u` the first row reads
`caps=27 event-types=on`; a Shift+Enter press reads `last=press:shift+enter` where Enter reads
`last=press:enter`, and a release reads `last=release:<key>` while inserting nothing. A terminal blur
changes `term=blurred` and leaves `field=focused` and the draft untouched.

### Phase 03: `CursorDemo`

Two panes of fixed text with a movable caret, and a status block. Both panes declare a caret, so the
one that is shown is the focused pane's even though the other pane paints later: a caret whose owner
is not the focused element is not eligible.

| Key | Action |
| --- | --- |
| `tab` | move focus, and the caret with it, between the two panes |
| `m` | switch between the terminal's own cursor and a painted one |
| `s` | cycle the shape: block, underline, bar |
| `b` | switch between a blinking and a steady caret |
| `c` | cycle the caret color between the terminal's own and one the scene picks |
| `←` / `→` / `↑` / `↓`, or `h` / `l` / `k` / `j` | move the caret one cell or one row |
| `q`, `ctrl+c` | quit |

The status block is two rows, so a verifier can assert the scene without reading styles:

```text
strategy=native shape=block mode=blink color=none cursor=visible
focus=left caret=0:0
```

`strategy=` is the presentation the panes ask for, `shape=`/`mode=`/`color=` are what a declared
cursor asks the terminal for, `cursor=` is what a virtual caret resolves to at the scene's own clock,
and `caret=` is the model's caret in cells. Right and left move by cells rather than by graphemes, so
the caret can be stepped onto the second half of the two-column glyph on the left pane's second row;
the terminal cursor is then put on the glyph itself.

The layout is fixed: the two status rows, one blank row, then the panes (a frame row, three content
rows and a frame row), one blank row and the help line. Each pane is 40 columns wide, so the left
pane's text starts at row 4 column 1 and the right pane's at row 4 column 41 on an eighty-column
terminal. `cursor=visible` in virtual mode marks the caret's cell with reverse video and leaves the
terminal's own cursor hidden; a blinking virtual caret alternates between `cursor=visible` and
`cursor=hidden` every 250 ms, and only then does the scene run a timer.

### Phase 04: `EditorDemo`

Two independently mounted editors, each a textarea, and four status rows. The two own their drafts,
carets and selections, so typing only ever reaches the focused pane.

| Key | Action |
| --- | --- |
| `tab`, `shift+tab` | move focus between the panes |
| `alt+l` | paint or remove the line-number gutter |
| `alt+t` | switch between the configured height and one that follows the wrapped content |
| `alt+m` | cycle the caret between the painted block, the terminal's cursor and a marked cell |
| `alt+p` | paint or remove the placeholder an empty draft shows |
| `escape`, `ctrl+c` | quit |
| any other gesture | the focused editor's own action, with `enter` breaking a line and `ctrl+enter` submitting |

Every scene gesture carries a modifier or is a focus gesture, and the scene's map is matched before
the focused editor's own, so an unmodified key is always text. `alt+t` rather than `alt+d` because the
editor binds `alt+d` to deleting a word forward.

The status rows, so a verifier can assert the scene without reading styles:

```text
focus=left lines=off height=fixed cursor=painted placeholder=off wrap=38
left len=50 caret=50 rows=2
right len=48 caret=48 rows=2
last=none
```

`wrap=` is the width the scene derived from the viewport for each pane, `rows=` is what the pane's
frame paints at that width, and `last=` is the scene's own log: `line-numbers`, `height`, `caret`,
`placeholder`, or an editor output as `left-submit:50`. A resize re-derives `wrap=`, re-wraps both
panes and moves their carets; the drafts are never edited by it.

### Phase 05: `TextInputDemo`

Three independently mounted single-line fields - a nickname, an email and a password - and five
status rows. The scene owns what the fields deliberately do not: which field has focus, the candidate
list a completion is answered with, and what a submission means.

| Key | Action |
| --- | --- |
| `tab` | complete the focused field, or move focus on when it has nothing to complete |
| `shift+tab` | move focus to the previous field |
| `escape`, `ctrl+c` | quit |
| any other gesture | the focused field's own action, with `enter` submitting it |

The status rows, so a verifier can assert the scene without reading styled cells:

```text
fields=3 focus=nickname completion=on form=incomplete
nickname len=0 caret=0 scroll=0 cand=none valid=no msg=empty
email len=0 caret=0 scroll=0 valid=no msg=empty
password len=0 caret=0 scroll=0 masked=yes valid=no msg=too-short
last=none
```

`focus=` is the field that types, `form=` is `ready` once every field passes its own validator,
`scroll=` is how many columns the field scrolled off its row, `cand=` is the candidate `tab` would
accept, and `last=` is the scene's own log of what a field reported: `submit:<field>:<length>`,
`reject:<field>:<reason>`, `validation:<field>:<valid|invalid>`, `copy:` or `paste:`.

The nickname completes from a static list of repository names, so nothing is fetched; typing `br`
paints `br` with `axton` as the ghost suffix behind the caret, and `tab` accepts `braxton` without
leaving the field. Typing it wider than sixteen columns scrolls the row instead of wrapping it. The
email has a validator and no candidates, so `tab` moves on. The password is masked: its row paints
one bullet per character, `hunter2!` appears nowhere on the screen, and `tab` never completes it -
there is no candidate list and no completion over a masked draft.

### Phase 06: `PaginatorDemo`

A paged list of eighty-four static items, ten items a page, and one mounted `Paginator`. The scene
owns the items and the selection; the paginator owns the page, the count, the page size, the
wrap policy and the indicator. That split is the composition a later rich list uses: the
application keeps its data, slices it with `Paginator.Model.bounds()`, and moves the page itself
when a selection has to stay visible.

| Key | Action |
| --- | --- |
| `←` / `h` / `pageup` | previous page |
| `→` / `l` / `pagedown` | next page |
| `home` / `end` | first page / last page |
| `↑` / `↓` | move the selection, bringing its page into view |
| `g` | switch the indicator between dots and the numeric label |
| `w` | toggle wrap-around paging |
| `r` / `a` | remove or restore one page of items |
| `escape`, `ctrl+c` | quit |

The paging keys are the paginator's own `KeyMap`, and the scene's are bubble bindings: the focused
paginator consumes paging gestures, and every other gesture reaches the scene. Both maps render
through `Help`, so the two compact help lines are the definitions that route.

The first status row spells out the state, so a verifier can assert the scene without reading
styled cells:

```text
page=1/9 items=84 per=10 bounds=[0,10) selected=0 mode=dots wrap=off
last=none
```

`page=` is the displayed page and the page count (`0/0` with no items), `bounds=` is the half-open
range the page covers, `selected=` is the scene's own selection (`none` when the list is empty),
`mode=` is the indicator, and `wrap=` is the paging policy. `last=` is the scene's log of its own
actions: `mode-numeric`, `mode-dots`, `wrap-on`, `wrap-off`, `items-<count>` or `selected-<index>`.

The dot indicator is windowed: seven dots at most, with an ellipsis wherever pages are hidden, so
the row is the same width whether the list has nine pages or a million. `end` shows the short last
page, which paints four items and pads the remaining rows, so the indicator never moves. `r` from
that page removes a page of items and the page is clamped to the shorter list; nine `r` presses
reach zero items, where the status row reads `page=0/0 items=0 per=10 bounds=[0,0) selected=none`,
the indicator reads `0/0` and no item is painted. `a` restores a page. At a narrower terminal the
indicator shrinks whole cells and ends with an ellipsis; the page and the slice do not move.

### Phase 07: `SpinnerDemo`

Two mounted `Spinner` components, each with its own model slot, preset and interval. Every gesture
acts on the focused spinner, so one can be stopped while the other keeps turning.

| Key | Action |
| --- | --- |
| `←` / `h`, `→` / `l` | cycle the focused spinner's preset, which restarts its cycle |
| `space` | start or stop the focused spinner |
| `c` | cycle the cadence both spinners are asked to run at |
| `tab` | move the gestures to the other spinner |
| `x` | remove or restore the second spinner's mount |
| `escape`, `ctrl+c` | quit |

The status rows spell out both spinners, so a verifier can assert the scene without reading styled
cells:

```text
left=line frame=2/4 run=on t=412ms
right=globe frame=1/3 run=on t=418ms
focus=left cadence=100ms mounted=on last=none
```

`frame=` is the painted frame and the definition's frame count, `run=` is whether the spinner holds
a runtime-time subscription, and `t=` is the elapsed time the frame was derived from. `last=` is the
scene's log of its own actions: `stopped`, `started`, `preset-<name>`, `cadence-<ms>`, `focus-left`,
`focus-right`, `removed` or `restored`. The row below the status rows holds the two glyphs, painted
by the mounted components' own views, so what a user sees is what the widget renders.

`space` stops the focused spinner: its frame and its clock freeze while the other spinner's keep
moving, which is the independence the two mounts exist to show. Cycling the preset resets that
spinner's cycle, so the new frame list begins at its first frame instead of continuing the phase of
the one it replaced; the other spinner keeps its preset and its phase. `c` retimes both definitions,
because the cadence is scene data and the widget holds no timing of its own. `x` removes the second
spinner's mount, which closes its interval - the remaining spinner is unaffected - and restoring it
mounts a fresh, stopped spinner at the first frame of the preset it had.

### Phase 08: `ProgressDemo`

A mock package manager over a seeded list of six packages: an `AnimatedProgress` that eases toward
how many of them are done, a static `ProgressBar` painted from the same target, and a `Spinner`
turning while work remains. Nothing is fetched, installed or read from the file system - the run is
the scene's own data - so the two progress regimes sit side by side without a real package manager.

| Key | Action |
| --- | --- |
| `s` / `space` | complete the next package, which retargets the bar |
| `u` / `backspace` | give the last completed package back |
| `r` | start the run over |
| `m` | cycle the bar between smooth motion and the instant, reduced-motion definition |
| `b` | cycle how both bars paint their fill: `plain`, `partial`, `blend`, `partial+blend` |
| `escape`, `ctrl+c` | quit |

The status rows and the two painted bars, so a verifier can assert the scene without decoding
styled cells:

```text
animated target=0.000 displayed=0.000 run=off
width=32 pkgs=0/6 motion=smooth fill=plain last=none

install ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ 0%
detail  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ 0%
⡀ installing figlet 0/6
```

`target=` is the fraction the run has reached and `displayed=` the fraction the bar has eased to, both
to three decimals; `run=` is whether the bar holds a runtime-time subscription, so `run=off` means it
is at rest on its target. `width=` is the bar width the scene derived from the viewport, `pkgs=` is
the run's own progress and `last=` is the scene's log of its own actions: `install-<package>`,
`back-<package>`, `done`, `reset`, `motion-instant`, `motion-smooth`, `fill-<label>`, `empty` or
`complete`. The `install` row is the mounted component's own view and the `detail` row is the pure
renderer, both from the same bar definition, so the animated row becomes the static row with its own
label as soon as the bar comes to rest.

`s` retargets the bar toward the next package: the frames between the retarget and the rest walk
the fill up in order, and pressing `s` again mid-ease retargets from wherever the fill has reached,
so the bar bends instead of restarting. `u` does the same in the other direction: the row keeps
`displayed=1.000` for a moment before it eases down to `0.833`, which is the proof that a retarget
carries the position over. Finishing the run stops the spinner, because the work it was turning for
is done.

`b` cycles the two opt-in refinements of the renderer, and both bars take them together. A partial
fill grades the cell the fill stops in, so a sixth of thirty-two cells is five whole cells and three
eighths of the next, and the row keeps exactly its width. A blend ramps the filled cells from one
color to the other, anchored to the bar so a cell keeps its color as the fill grows; at a sixth of
the bar the first filled cell carries the exact start color and the last one is already further
along the ramp.

`m` turns animation off: the bar lands on the target it was easing toward and holds no timer at
all, which is the reduced-motion definition. A narrower terminal re-lays both bars at the same
normalized value in fewer cells, and widening it restores the width.

### Phase 09: `CountdownDemo`

One local countdown with its whole lifecycle on the keyboard: a `Countdown` mounted into the scene,
the duration the scene owns, and a status row that spells out the state, the run's epoch, the exact
remainder and how many timeouts the scene was told about. Nothing is fetched, scheduled or persisted
- the countdown is the scene's own duration - and the probe runs it at five seconds for a terminal
verifier and at a minute and a half for a person.

| Key | Action |
| --- | --- |
| `space` | start, pause or resume: the one-key gesture |
| `s` | start a run of the configured duration from the beginning |
| `r` | reset to the configured duration, stopped |
| `←` / `→` | shorten or lengthen the configured duration by 15 seconds |
| `escape`, `ctrl+c` | quit |

The status rows, the painted time and the help line:

```text
countdown state=paused epoch=2 nanos=3000000000 timeouts=0
duration=00:05 tick=100ms last=pause

remaining  00:03

s start over • space start/pause • r reset • ← shorter • → longer • esc quit
```

`state=` is derived from the widget's own facts rather than stored beside them: `running` while a run
is live, `expired` once it has nothing left, and `paused` otherwise (`idle` before the first start).
`epoch=` is the identity of the current run and `nanos=` the exact remainder the model holds, which is
what lets a verifier compare a paused countdown with itself a moment later. `last=` is the scene's log
of its own actions: `start`, `pause`, `resume`, `reset`, `timeout`, `limit` or `duration-<seconds>`.

`space` pauses a running countdown: the painted time and the model's remainder both stop moving, and
only `space` again - a resume - starts them again from the value they froze at, so the time a paused
countdown was holding is the time it carries on with. `s` is the other half of that: it begins a run of
the whole configured duration, discarding what was left. A duration gesture resets the countdown, and
one that would leave the scene's five-second to ten-minute range changes nothing and says `limit`.

A run that reaches zero reports one `Countdown.Output.Timeout`, which crosses the mount as a scene
message: `timeouts=` moves once, `last=timeout` and the painted row says `time is up`. Waiting another
second leaves all three unchanged, and `space` starts a new run under the same one timeout.

### Phase 10: `StopwatchDemo`

Two stopwatches live at once: both are `Stopwatch` components mounted into one scene, each with its
own keys, and a status row that spells out the state, the run's epoch and the exact elapsed time of
each. The scene owns nothing the widget already holds - no timers, no totals - so what the screen
shows is what the two mounts measured. Nothing is fetched, scheduled or persisted.

| Key | Action |
| --- | --- |
| `s` | start the left watch while it is stopped, stop it while it runs |
| `r` | reset the left watch: elapsed back to zero, running state kept |
| `k` | start or stop the right watch |
| `l` | reset the right watch |
| `escape`, `ctrl+c` | quit |

The status rows, the painted times and the help line, with the left watch running two seconds into
its run and the right one still stopped:

```text
left state=running epoch=1 nanos=2000000000
right state=stopped epoch=0 nanos=0
tick=50ms last=start-left

left   00:00:02.000
right  00:00:00.000

s stop left • r reset left • k start right • l reset right • esc quit
```

`state=` is the watch's own liveness, `epoch=` the identity of its current run and `nanos=` the
exact elapsed time the model holds, which is what lets a verifier compare a stopped watch with
itself a moment later. `last=` is the scene's log of its own gestures: `start-left`, `stop-left`,
`reset-left` and the same three for the right watch. A repaint is not a gesture, so the log does not
churn with every tick.

`state=` and the help line move together because both come from the same place: `s` carries a start
action and a stop action on one key, exactly one of them is enabled, and the map that routes the key
is the map help renders. While the left watch runs, its words are `s stop left` / `r reset left` and
the right watch still offers `k start right` - the two watches have independent state, so they have
independent help.

Starting the right watch leaves the left one's frozen total alone, stopping one leaves the other
running, and each mount holds its own interval only while its own watch runs. `r` on a running watch
keeps it running and restarts its total from zero, which is why the help still offers `s stop left`
after the reset.

### Phase 11: `RichListDemo`

One large filterable list, three item presets and the list's whole feature set on the keyboard. The
scene owns three status rows, the preset and the counter that names an added item; a mounted
`RichList` owns the items, the query, the selection, the pages and the chrome. Nothing is fetched,
scheduled or persisted - the fixture is 240 generated items.

| Key | Action |
| --- | --- |
| `↓` / `↑` (or `j` / `k`) | move the selection one item |
| `→` / `←` (or `l` / `h`, `pgdn` / `pgup`) | move one page, selecting its first item |
| `home` / `end` | first or last item |
| `/` | start filtering: the title bar becomes the query field |
| `ctrl+f` | switch the filter between plain and subsequence matching |
| `enter` | apply the query while editing, choose an item while browsing |
| `escape` | cancel the edit, then clear the applied query, then quit |
| `t`, `c`, `p`, `h` | toggle the title bar, the counts, the page indicator and help |
| `?` | switch between compact and full help |
| `s` | start or stop the loading spinner |
| `a`, `x` | add an item above the selection, remove the selected one |
| `1`, `2`, `3` | paint the items as one row (`simple`), two rows (`default`) or three (`fancy`) |
| `ctrl+c` | quit |

The status rows, the list's own chrome and its items:

```text
preset=default items=240 selected=item-001 added=0 last=none
filter=- mode=fuzzy editing=off applied=- matches=240 page=1/30
title=on status=on page=on help=compact spinner=off rows=21 content=16
Favorite things
240 items
❯ Raspberry jam
  On toast, mostly
  Hazelnut spread
  Better than the sum of its parts
...
● ○ ○ ○ ○ ○ ○ …
↑ up • ↓ down • ← previous page • → next page • enter choose • esc cancel
a add item • x delete item • ctrl+c quit
```

`matches=` and `page=` are the list's own public projections rather than a second copy of its state:
`RichList.visible(...)` and `RichList.pages(...)` answer for the mounted model. `content=` is
`RichList.contentRows(...)`, which is how many rows the items get after the list's own chrome - 21
less a title, counts, the indicator and two help rows. `last=` is the scene's log: `preset-<name>`,
`added-<n>`, `removed-<id>`, `chose-<id>`, `canceled` or `none`.

A query is typed into the field and the list narrows as it goes: `matches=` and the counts row follow
every character, the page count changes with the result set, and a selection the query hides falls to
the first item it shows. `escape` cancels the edit and restores both the applied query and the item
that was selected before it - the list remembers the selection a query hid, so nothing is lost by
looking. `enter` keeps the query; the same gesture with nothing to edit clears it.

Items are not one row each: the list packs whole items into the content rows, so the `fancy` preset
fits fewer items a page than `simple` at the same height, and a taller terminal fits more of them
without changing the selection. The three presets are three renderers over one fixture, which is what
proves the delegation: the default renderer paints a marker, a title and a description, and the fancy
one paints three rows of its own and puts a diamond on a row the query matched.

Adding an item above the selection leaves it selected, because the selection is an item id and not a
position; removing the selected item selects the one that took its place. Chrome toggles give their
rows back to the items, so `content=` grows and the page count shrinks while the selection stays.
`s` starts a composed spinner beside the title and stops it again - the interval exists only while it
is shown - and a bigger or smaller terminal repacks the pages through the widget's own size
subscription.

### Phase 12: `ViewportDemo`

A searchable pager over an original field-guide document, beside an outline pane over the same
document's sections. Two `TextViewport`s are mounted over one scene: the pager owns the lines, the
matches and its own anchor, the outline owns its sections and its own anchor, and the scene owns the
document, the search and which component has focus. The pager's status rows report everything a
verifier needs without decoding styling, and the gutter is the widget's own line numbers.

| Key | Action |
| --- | --- |
| `↑`/`k`, `↓`/`j` | scroll the focused pane by visual rows |
| `b`/`pgup`, `f`/`pgdn` | page up and page down |
| `u`, `d` | half page up and half page down |
| `home`, `end` | first line; `end` also arms follow-end |
| `←`/`h`, `→`/`l` | pan, in the panned mode only |
| `n`, `alt+n` | next and previous match, wrapping at both ends |
| `/`, `esc` | focus the find field; `esc` returns to the text |
| `w` | switch the pager between wrapping and panning |
| `g` | show or hide the line numbers |
| `a` | append a note to the document |
| `tab` | move focus between the pager and the outline |
| `ctrl+c` | quit |

The scene paints two status rows above the pager, the find field, the pager, the outline's status
row, the outline, and one help line per key map:

```text
mode=wrap numbers=on follow=off line=1/39 total=42 vscroll=0% hscroll=0%
matches=0 active=0 query=- focus=main added=0 last=started
find:
  1 │ A pocket guide to the artichoke
  2 │
  3 │ What it is
...
outline=pan line=1/11 rows=4 vscroll=0%
  1 │   1  A pocket guide to the artichoke
```

`line=` is the logical line the pager's window starts on and how many lines the document holds;
`total=` is `TextViewport.totalRows(...)`, the visual rows the document needs at the pager's content
width; `vscroll=` and `hscroll=` are the two percentages; `active=` is the active match, one-based,
or 0 for none; `added=` counts the appended notes and `last=` is the scene's log.

`w` is the difference between the two modes in one key: wrapping keeps `total=42` for 39 lines and
`total` drops to 39 when the same document is panned, because a panned line is one row however long
it is. Panning right takes cells off the left edge of every line - the "One long line" section is
the one wide enough to matter - and switching back to wrapping drops the offset it cannot use.
`g` takes the gutter away, and the text moves left by exactly the cells it gave up.

Typing `artichoke` in the find field publishes the ranges the scene's own search found and selects
the first one, so the pager scrolls to it; `n` walks them forwards, `alt+n` backwards, and both wrap.
A query whose match is below the window - `patience` - scrolls the pager down to it. `end` arms
follow-end and an appended note stays visible at the end of the document; a reader who scrolled back
to the first line keeps their place when a note is appended. `tab` moves focus to the outline, which
scrolls on its own anchor while the pager's `line=` stays where it was.

### Phase 13: `TableDemo`

A scrollable, editable table over an original seed inventory, beside a static table fitted to the
terminal's width. The scene owns the data and the window's geometry; the mounted `DataTable` owns the
selection, the vertical window and the horizontal pan. Rows are added, edited and removed while the
selection keeps its identity, and the status rows report everything a verifier needs without decoding
styling.

| Key | Action |
| --- | --- |
| `↑`/`k`, `↓`/`j` | move the selection one row |
| `b`/`pgup`, `f`/`pgdn` | page up and page down, one window at a time |
| `u`, `d` | half page up and half page down |
| `home`, `end` | first row and last row |
| `←`, `→` | move the selected column, in the cell mode only |
| `h`, `l` | pan the window one cell left and right |
| `m` | switch the selection between a whole row and one cell |
| `enter`, `esc` | choose the selection, or cancel the table |
| `e` | edit the selected row's name |
| `a` | add a row below the selection |
| `x` | delete the selected row |
| `ctrl+c` | quit |

The scene paints two status rows, the table, the summary and one help line per key map:

```text
mode=row row=1/18 cell=- top=0 pan=0 last=started
chosen=- edits=0 added=0 removed=0 rows=18 body=15 width=95
id   name        family      sown note
S-01 artichoke   thistle       12 Sow under cover in early spring and prick out hard.
...
┌─────────┐
│rows 18  │
│sown 588 │
└─────────┘
```

`row=` is the selected row and how many the table holds, `cell=` is the selected column's key or `-`
for a row selection, `top=` is the first body row the window shows, `pan=` is the first painted cell
it shows, `body=` is how many body rows the terminal leaves under the header, and `width=` is the
whole row's painted width - the cells there are to pan through. `edits=`, `added=` and `removed=`
count the scene's own changes and `chosen=` is what the last `enter` reported.

`end` moves the selection to the last row and the window with it, while the header stays on the
screen's third line. `m` then `→` four times reaches the note column, and the window pans so the
selected cell stays on screen - the header pans with the body, which is the alignment the phase is
about. `enter` reports the chosen cell, `e` edits the selected row's name through `Msg.Replace`, `a`
inserts a row below the selection and `x` removes it; the rows move and the selected id does not, and
removing the selected row selects the nearest survivor. A shorter terminal gives the table fewer body
rows and leaves its columns where they were, and the static summary below it is fitted to the width
it is given.

### Phase 14: `FilePickerDemo`

A file picker over a directory the demo is given, beside the application's own gestures. The scene
owns the fixture, the two status rows and the file it creates or deletes; the mounted `FilePicker`
owns the directory, its entries, the selection, the request in flight and the window. Nothing is
fetched and nothing is persisted.

Run without an argument, the demo creates its own fixture under the system temporary directory and
removes it when the session ends; given a path, it browses that directory. Either way the directory
it opens is also the picker's root, so the demo cannot be walked out of the directory it was asked to
show, and its picker allows `.txt` and `.md` files: everything is listed, and only those can be
chosen. The fixture holds two directories, four allowed files, two files refused by extension, two
hidden files and a link that points back at the directory that holds it.

| Key | Action |
| --- | --- |
| `↑`/`k`, `↓`/`j` | move the selection one entry |
| `b`/`pgup`, `f`/`pgdn` | page up and page down, one window at a time |
| `home`, `end` | first entry and last entry |
| `→`/`l`, `enter` | enter the selected directory |
| `←`/`h`, `backspace` | read the parent directory, selecting the one left |
| `enter` | choose the selected entry, or report why it cannot be chosen |
| `.` | show and hide hidden entries |
| `r` | re-read the directory |
| `?` | switch between compact and full help |
| `esc` | cancel the picker, which ends the session |
| `a` | create a file in the directory being shown |
| `x` | delete the selected file |
| `ctrl+c` | quit |

The scene paints two status rows, the picker and its own help line:

```text
dir=/ entries=9 shown=7 selected=docs hidden=off state=idle
last=started chosen=0 rejected=0 deleted=0 added=0
/tmp/braxton-file-picker-1554521712688312478
❯ docs/                                                           rwxrwxr-x  dir
  loop/                                                     rwx------  dir  -> .
  music/                                                          rwxrwxr-x  dir
  archive.bin                                                     rw-rw-r--  21B
  draft.txt                                                        rw-rw-r--  8B
  photo.png                                                       rw-rw-r--  21B
  readme.md                                                       rw-rw-r--  10B
...
7 entries of 9
↑/k up • ↓/j down • →/l open directory • enter choose • esc cancel
a add a file • x delete the file • ctrl+c quit
```

`dir=` is the directory being shown relative to the fixture, `entries=` is everything the listing
read and `shown=` is how much of it the hidden-file toggle shows, `selected=` is the selected entry,
`state=` is the request in flight (`idle`, `loading` or `failed`) and `last=` is what the picker or
the scene last did. The picker paints its own path row, one row per entry with the size and the
permissions where the filesystem has them, a status row and its help. Its compact help is the map of
the gestures that state handles: the fixture is the root, so there is nowhere above it to go and
`parent` is not advertised.

`→` enters `docs` and reads it, `enter` chooses `guide.md` through the check the picker runs before
it reports a choice, and `←` comes back to the fixture with `docs` selected. `enter` on
`archive.bin` reports `rejected-/archive.bin-extension` and paints why, and `enter` on `loop`
reports `rejected-/loop-link`: following that link would lead back into the directory that holds it,
so it is listed and refused. `.` shows the two hidden files without re-reading anything. `x` deletes
the selected file through the scene's own task and leaves the picker's listing stale on purpose -
`enter` then reports that the entry is no longer there, and `r` re-reads the directory.

### Phase 15: `MarkdownDemo`

A complete markdown pager over an original, checked-in document. The scene owns the source, the
projection, the theme and the width the projection was made at; a mounted `TextViewport` owns the
window, the anchor and the matches, and a mounted `TextInput` owns the query. Nothing is fetched and
nothing is downloaded: the document is the resource `src/main/resources/examples/parity/markdown.md`
that the jar packages beside the demo.

| Key | Action |
| --- | --- |
| `↑`/`k`, `↓`/`j` | scroll the pager by visual rows |
| `b`/`pgup`, `f`/`pgdn` | page up and page down |
| `u`, `d` | half page up and half page down |
| `home`, `end` | first row and last row |
| `←`/`h`, `→`/`l` | pan, in the panned projection only |
| `n`, `alt+n` | next and previous match, wrapping at both ends |
| `/`, `esc` | focus the find field; `esc` returns to the pager |
| `t` | switch between the light and the dark theme |
| `w` | switch between a wrapped projection and a wide, panned one |
| `g` | show or hide the line numbers, which takes six cells off the projection |
| `p` | switch between the default bounds and limits this document does not fit |
| `ctrl+c` | quit |

The scene paints two status rows, the find field, the pager and one help line per key map:

```text
theme=light mode=wrap numbers=on rows=19 width=74 lines=132 limits=default
diagnostics=none matches=0 active=0 query=- focus=pager last=started
find:
  1 │ A field guide to the paper lantern
...

↑/k up • ↓/j down • b/pgup page up • f/pgdn page down
/ find • esc back to text • t dark/light • w wrap/pan • g line numbers • p stri…
```

`theme=`, `mode=`, `numbers=` and `limits=` are the scene's own switches, `rows=` and `width=` are
the cells the pager and the projection were given, `lines=` is the projection's row count and
`diagnostics=` is the bound that stopped it, or `none`. `matches=` and `active=` are the pager's own
published ranges and `last=` is the scene's log: `theme-dark`, `mode-pan`, `numbers-off`,
`limits-strict`, `pager-page-down`, `resized` and `typed`.

The document holds every construct the dialect supports: six heading levels, emphasis, strong,
strikethrough, inline code, two links and a bare autolink, an escaped marker pair, a hard break, a
tight bullet list with a nested list, an ordered list with a nested one, a task list, a quotation
with a nested quotation, fenced code in Java, JSON and shell, a fence that declares no language, an
indented block, a GFM table with three different column alignments, a thematic break, an image, raw
HTML written as text, wide CJK glyphs, and the word `lantern` in enough places for the find field to
have matches to walk.

`t` re-projects the same source with the other palette: the first heading keeps its weight and
changes its color, and the body text never borrows it. `w` projects at two hundred cells and puts
the pager in its panning mode, so `→` takes cells off the left edge of every row and switching back
to wrapping drops the offset the wrap cannot use. `g` takes the gutter away and hands those six cells
to the projection, so the same paragraph wraps later. `p` lowers the cell budget below what this
document paints: the projection publishes one typed diagnostic instead of a partial page, the pager
pages over that row, and `p` again restores the document. Typing `lantern` publishes twenty-three
ranges, selects the first, and `n` walks them with the active one painted in reverse video while the
others keep the underline. A taller terminal shows more of the same projection; a narrower one
re-projects, and the pager keeps its logical line.

### Phase 16: `TabsDemo`

One tab bar over three live pages: an editor, a filtered list and a countdown. Every page is mounted
and declared for the whole session, so switching tabs changes what is painted and nothing else - the
draft, the list selection and the running timer are all still there when the user comes back. The
scene owns where the keyboard is; the bar declares its own focus element and key map, so it receives
a gesture only while it holds focus, and each page declares its own, so typing reaches the editor and
a list gesture reaches the list.

| Key | Action |
| --- | --- |
| `tab`, `shift+tab` | move the keyboard between the bar and the page |
| `alt+1`, `alt+2`, `alt+3` | show the notes, inbox or timer page |
| `←`/`h`, `→`/`l` | previous and next enabled tab, while the bar holds the keyboard |
| `home`, `end` | first and last enabled tab, while the bar holds the keyboard |
| `space`, `r` | start/pause and reset the countdown, while the timer page holds the keyboard |
| `?` | switch between compact and full help, where the focused page does not consume it |
| `ctrl+c` | quit |
| any text, arrows, `enter` | the focused page's own actions: the editor types, the list browses and filters |

The scene paints two status rows, the bar, the framed page and two help rows - the focused region's
actions, then the scene's:

```text
tab=notes focus=page pages=3 switched=0 last=none
notes=208 inbox=item-01 timer=02:00 run=off help=compact
╭───────╮╭───────╮╭───────╮╭─────────╮
│ Notes ││ Inbox ││ Timer ││ Archive │
│       └┴───────┴┴───────┴┴─────────┴─────────────────────────────────────────╮
│A tab bar is a claim about identity: the page you were looking at is still the│
...
enter submit • alt+c copy • ctrl+v paste
tab focus bar/page • shift+tab focus bar/page • alt+1 notes • alt+2 inbox • alt…
```

`tab=` is the shown page's id, `focus=` where the keyboard is, `pages=` how many tabs are enabled,
`switched=` how many times the shown page changed and `last=` the scene's log (`tab-inbox`,
`timer-toggle`, `help`, `notes-submit`). The second row reports every page's kept state: the draft's
length, the list's selected item, the countdown's remaining time and whether it runs.

`→` while the bar holds the keyboard shows the next enabled tab, and the last one is the end of the
bar: `Archive` is disabled, so it is painted but not reachable, and wrap-around is off, so `→` on
`Timer` changes nothing. The shown tab opens into the frame - its bottom edge is the gap the page
shows through - and the window follows it: a narrower terminal drops the tabs that do not fit and
marks each side with `‹`/`›`, so the shown tab's label is always painted. `alt+2` shows the list page
and keeps the keyboard where it was, so tabbing back into the page focuses the list that has just
become visible rather than the editor that is now hidden. The countdown keeps ticking while its page
is hidden and the timer page's `space` is a typed space on the editor page, because that page's field
holds the keyboard.

### Phase 17: `MouseDemo`

Two panes side by side - an editor on the left, a list on the right - with a modal dialog over both.
The scene logs every pointer report and paints two status rows, so what a click at a known cell did
is visible without decoding styling. The session asks for `MouseMode.CELL_MOTION`, which is what
drag selection needs: presses, releases and wheel gestures are reported in every mode, and motion
while a button is held only in that one.

| Key | Action |
| --- | --- |
| `tab` | move the keyboard between the panes |
| `alt+m` | open or close the dialog |
| `?` | switch between compact and full help |
| `ctrl+c` | quit |
| any text, arrows, `enter` | the focused pane's own actions |
| click | place the caret in the editor, or select the list row under the pointer |
| double click | select the word under the pointer, or choose the list row |
| drag | select in the editor, from where the press landed to where the pointer is |
| wheel | step the list the pointer is over |

```text
pane=left dialog=closed draft=58 caret=58 sel=item-01 dlg=dialog-01 last=none
pointer=none reports=0
Click in this pane to place the caret, a│Items
nd drag to select.█                      4 items
                                         ❯ Lantern
                                           Hangs above the stall
...
tab switch pane • alt+m dialog • ? full help • ctrl+c quit
```

The first status row reports where the keyboard is, whether the dialog is open, the draft's length
and caret, each pane's selection, and the scene's own log. The second reports the last report the
scene observed - its action, its button and its cell - and how many it has seen, which is what makes
a drag visibly a press followed by motion.

A click is delivered to the element painted under the cell: cell `(2, 5)` is the editor's fifth
cluster, cell `(6, 45)` is the list's second item, and neither knows where the other is. The pane that
acted takes the keyboard - the widgets never move focus themselves, so `pane=` follows the click
because the scene decided it should. A press
captures the element it landed on, so motion that leaves the editor - over the divider, over the
list, or below both - keeps selecting in the editor that captured it. `alt+m` opens the dialog, and the
panes under it stop being reachable: a click outside the dialog's box is logged and then reaches
nothing, while a click on its entries selects them. The wheel acts on what it is over, and no report
is ever also delivered as a wheel key.

### Phase 18: `TerminalQueryDemo`

Four status rows and a styled sample. Every terminal question is asked on a key, never at startup,
and each answer is painted exactly as the terminal gave it - including the answers that are not
facts: a terminal that refuses a query shows `unsupported`, and one that stays silent shows
`timeout` after the reply deadline.

| Key | Action |
| --- | --- |
| `c` | ask where the cursor is |
| `i` | ask which terminal is attached |
| `m` | ask whether bracketed paste (mode 2004) is set |
| `t` | ask whether the terminal claims 24-bit color (`Tc`) |
| `b` / `f` / `k` | ask for the background, foreground or cursor color |
| `p` | ask for the session's color profile (a session fact: no bytes are written) |
| `a` | ask every terminal question at once |
| `1` `2` `3` `4` | paint with no color, 16 colors, 256 colors or truecolor |
| `esc`, `ctrl+c` | quit |

```text
profile=truecolor color=on theme=dark
bg=#101010 fg=unknown caret=unknown
identity=xterm(390) cursor=4,8 paste=set tc=TrueColor
asked=5 answered=4 failed=1 last=foreground=unsupported
sample red amber green blue violet
help asks the terminal • c cursor position • i terminal name • m paste mode • …
```

`profile=` is what the session negotiated or what the application chose, `theme=` follows the
background the terminal reported, and `asked=`/`answered=`/`failed=` count what the session did
rather than what it hopes. The sample row is ordinary styled text: pressing `3` repaints it as
palette indices and pressing `4` repaints it as 24-bit color, so a verifier can read the switch out
of the transcript without a single escape sequence appearing in any content string.

### Phase 19: `ClipboardDemo`

Four status rows and a sample. Copying and reading are separate actions, and every outcome is painted
as the session observed it: a copy reports the transport it used and what it kept, and a read reports
the text the terminal sent or the reason there is none. Nothing is copied or read at startup.

| Key | Action |
| --- | --- |
| `s` / `p` | copy the sample to the system or primary selection |
| `c` / `v` | ask the terminal for the system or primary selection |
| `i` | read the session's in-process buffer (no terminal involved) |
| `esc`, `ctrl+c` | quit |

```text
copy=system path=osc52_direct bytes=24 stored=on truncated=off dedup=off
read=system ok bytes=6
text=héllo
internal=bytes=24
sample=braxton clipboard sample
help s copy: system • p copy: primary • c read: system • v read: primary • …
```

`copy=` names the selection, the transport (`osc52_direct`, `tmux_native`, `tmux_passthrough`,
`internal_only` or `none`) and whether the text was stored, truncated or suppressed as a duplicate.
`dedup=on` is what a repeated copy of the same text shows: the sequence is not written twice.
`read=` is `ok`, `refused`, `timeout`, `invalid`, `oversize`, `disabled` or `rejected`, and `text=`
shows what the terminal reported with control characters replaced, so the value is displayed and
never replayed. `internal=` is what a widget's paste would receive, because the in-process buffer -
not the terminal - is that source.

The verifier answers the two requests with canned replies, including an empty selection, a payload
that is not base64 and a reply that names the other selection, so what is asserted is Braxton's
behavior rather than a particular terminal's support.

### Phase 20: `TerminalPresentationDemo`

One scene declares the session's whole terminal presentation from its own model: the screen buffer,
the pointer level, bracketed paste, focus reporting, the window title, the terminal's default colors,
a taskbar progress value and a program-status record. Every field starts at `default`, which means
"the session's own options", so the first frame asks the terminal for nothing it did not already open
with.

| Key | Action |
| --- | --- |
| `a` | cycle the screen buffer: session default, plain, alternate |
| `m` | cycle the pointer level: default, none, click, drag, move |
| `p` | cycle bracketed paste: default, on, off |
| `f` | cycle focus reporting: default, on, off |
| `t` | cycle the title: none, a title, one that tries to inject a sequence |
| `c` | apply the terminal's default colors, then give them back |
| `g` | cycle progress: none, normal 40, error 70, indeterminate, paused 90 |
| `s` | cycle the status record: working, blocked, done, error, clear |
| `k` | ask the terminal whether it keeps program-status records |
| `q`, `ctrl+c` | quit |

The status rows spell out the intent and what the terminal said it supports, so a verifier can assert
the scene without decoding escape sequences:

```text
screen=default mouse=default paste=default focus=default
title=none fg=none bg=none cursor=none
progress=none status=none
support=unknown states=0 kinds=0
states=-
kinds=-
last=none

braxton terminal presentation gestures=0
```

`screen=`/`mouse=`/`paste=`/`focus=` are the modes the scene asks for, with `default` for the
session's own; `title=` is `none`, `set` or `unsafe`; `fg=`/`bg=`/`cursor=` are the default colors the
scene imposes; `progress=` is the state and percentage, `status=` the program-status state and record
id. `support=` reports whether the terminal answered the program-status question, with the number of
names it listed on that row and the names themselves on the next two. `last=` is the scene's log of
its own gestures, and `gestures=` on the body row changes with every one of them, which is what makes
a repaint visible.

The transcript is the evidence the frames cannot show. `t` writes `ESC [ 22 ; 0 t` and
`ESC ] 2 ; braxton presentation ESC \`, and the second press sets the title that tries to inject
`ESC ] 0 ; pwned BEL`: the row reads `title=unsafe` and the transcript never contains the injected
sequence, because control characters are removed before the title is written. `c` writes
`ESC ] 10 ; rgb:e0/e6/f0 ESC \` and its two neighbors, and the second press resets them with
`ESC ] 110`/`111`/`112`. `g` writes `ESC ] 9 ; 4 ; <state> ; <value> ESC \` for each state, and `s`
writes `ESC ] 7501 ; state=...` with the id, kind, app and base64 message the record carries.

`a` is the one gesture that changes the buffer: leaving the alternate screen paints over the shell's
own screen, and coming back repaints everything, because the other buffer holds different cells. A
session that quits with a finished `done` or `error` record leaves it behind - that is what the
protocol keeps for the user to find - while a run still in progress is cleared, and everything else
the session changed (modes, title, colors, progress) is given back on the way out.

### Phase 21: `ProcessHandoffDemo`

A draft, three rows and the help. Two gestures need the session to stop owning the terminal: `e`
hands it to a child process, and `ctrl+z` asks the job controller to suspend the session. Both are
`Command` values, so the scene stays a pure function of its model.

The child is this same program started again with `--helper`, so the demo needs nothing installed:
it prints the working directory and the environment entry the request carried, prompts for a line,
reads it in the terminal's own (cooked) configuration, echoes it, and exits with the status it was
given. `BRAXTON_HANDOFF=phase-21` is what the `env=` line reports.

| Key | Action |
| --- | --- |
| `e` | hand the terminal to the owned helper |
| `ctrl+z` | suspend the session, when it owns the terminal's foreground |
| `backspace` | delete the last character of the draft |
| `q`, `ctrl+c` | quit |
| any other printable key | append it to the draft |

```text
handoffs=0 last=none exit=- suspend=ready
draft=-

braxton process handoff gestures=0

e run helper • ctrl+z suspend • q quit • backspace delete
```

`handoffs=` counts the children that ran, `last=` is `none`, `exited`, `failed` or `rejected`,
`exit=` is the child's exit status or `-`, and `suspend=` is `ready`, `resumed`, `refused` or
`failed`. `draft=` is what a handoff must not lose, and `gestures=` changes with every gesture so a
verifier can tell a repainted frame from a skipped one.

```text
braxton handoff helper
cwd=/home/user/project
env=phase-21
line> a line typed for the helper
echo=a line typed for the helper
edited=A LINE TYPED FOR THE HELPER!
```

The child's transcript appears where the session left off - the alternate screen was given back
before it started - and the session repaints its own screen when the child exits, with `draft=`
exactly as it was and `handoffs=1 last=exited exit=0`. A session that does not own the terminal's
foreground, or whose process group the kernel will not stop, reports `suspend=refused` or
`suspend=failed` and keeps painting: a suspend is a request, never a promise.

### Phase 22: `ApplicationControlDemo`

A status board driven from outside and inside at once. `Application.start` runs the scene on its own
runtime thread, an owned producer thread sends progress through the `ProgramHandle`, and the demo
prints the final model it read from `ProgramResult` once the terminal is its own again.

| Key | Action |
| --- | --- |
| `s` | run the demonstration sequence |
| `q`, `ctrl+c` | ask to quit; the message filter guards the exit until it is confirmed |
| `y` | confirm the quit |
| `esc` | keep running |

```text
progress=3 ticks=4 sequence=idle order=none quit=guarded
prompt=none s sequence • q quit

braxton application control producer=on

s run sequence • q quit • y confirm quit • esc keep running
```

`progress=` and `ticks=` count the producer's messages, which no key produced: they climb on their
own, one every 100 ms. `s` returns one `Command.sequence` of five steps - a task, a delay, a task, an
`afterUpdate` continuation and a task - so `order=` records the order the steps actually finished in:

```text
progress=7 ticks=8 sequence=done order=a,tick-a,b,b-done,c quit=guarded
```

`q` asks to quit and is declined: the filter returns `null` for the `QuitRequest` until the
confirmation message has armed it, so the prompt row changes to `prompt=confirm press y to quit`,
`quit=confirm` appears, and the session keeps painting. `y` is an ordinary message the filter still
passes; the quit that follows is accepted and the session restores the terminal. Pressing `q` and then
`y` is exactly what the packaged launch and the verifier scenario do, because the guarded quit needs
both.

```text
final progress=12 ticks=13 sequence=done order=a,tick-a,b,b-done,c exit=QUIT/0
```

That last line is printed after the runtime has closed, which is why it lands on the terminal's own
screen rather than the alternate one: it is the final model and the exit result the handle returned.

### Phase 23: `InlineIoDemo`

A package installer on the terminal's **own** screen, with three launch modes that differ only in
where the session's bytes come from and go to.

| Launch | What runs |
| --- | --- |
| `--inline` (default) | the owned terminal, alternate screen off: a live region plus one durable line per installed package |
| `--pipe` | stdin as UTF-8 application data, renderer disabled, durable output on stdout |
| `--headless` | local fixtures and a `StringWriter`, renderer disabled, no terminal and no native library |

| Key | Action |
| --- | --- |
| `n` | install the next package |
| `a` | install everything that is left, one package at a time |
| `q`, `ctrl+c` | quit |

```text
$ java -cp braxton.jar examples.parity.InlineIoDemo
packages=6 installed=0 phase=idle mode=inline
live=parser progress=[------]

braxton inline output packages=6

n next package • a install all • q quit
```

Each installed package is printed **above** the live region with `Command.println`, and the region is
repainted below it in the same write:

```text
installed parser
installed lexer
installed runtime
packages=6 installed=3 phase=installing mode=inline
live=renderer progress=[###---]

braxton inline output packages=6

n next package • a install all • q quit
```

Whatever the shell printed before the program started stays above all of it: an inline frame is
painted relatively and clears forward from its own first row, so it cannot reach the user's
scrollback. `q` ends the session, the final frame stays on screen, and the prompt that follows it
starts on a fresh line.

```text
$ printf 'one\ntwo\n' | java -cp braxton.jar examples.parity.InlineIoDemo --pipe
line=1 text=one
line=2 text=two
stream lines=2 text=one,two finished=true
pipe QUIT/0
```

The piped and headless modes run with the renderer disabled, so the session writes no frame, no mode
sequence and no escape sequence at all; what it prints is the whole output. The lines are stdin as
*application data* - a source subscription reads them - not terminal key input, and the run ends with
the model it reached, which is why the demo can report it as plain text. Neither mode needs
`--enable-native-access`, because neither opens a device.

## The integrated showcase

`examples.parity.Showcase` is the single entry point that covers every phase above. It is a catalog,
not a menu of processes: choosing an entry mounts that phase's own scene component as the showcase's
child, and returning is the unmount. One scene is alive at a time, which is what makes a stale timer,
a stale query or a stale focus impossible to observe - the closed scene's subscriptions and commands
were canceled with its mount.

```bash
./run-demo.sh                      # ./mvnw clean package, then the showcase
./run-demo.sh --skip-build         # reuse target/

java --enable-native-access=ALL-UNNAMED -cp 'target/braxton-0.1.0.jar:target/showcase-lib/*' \
     examples.parity.Showcase
```

The second form is what the verifier uses: the showcase, the library's own runtime dependencies
beside it, and nothing else - no example-only jar is on the classpath. `./run-demo.sh` is the
convenience launcher and does the same thing after building.

| Key | Action |
| --- | --- |
| `↑`/`k`, `↓`/`j` | move the highlight |
| `home`, `end` | first and last entry |
| `enter` | open the highlighted entry |
| click, double click | select an entry, then open it |
| wheel | move the highlight |
| `ctrl+c` | leave the mounted scene and return to the catalog |
| `?` | switch between the list and the grouped help |
| `q`, `ctrl+c` | quit - from the catalog only |

```text
catalog=27 open=none selected=01 top=0 help=compact last=none
braxton parity showcase
❯ 01 Keymaps and help — one action definition drives routing and help
  02 Enhanced keyboard and terminal focus — key event types and focus reporting
  ...
 24 Composed task workspace — files, preview, drafts, tasks, progress and a confirmation
 28 Widget gallery — every milestone-4 widget on four pages
↑/k up • ↓/j down • home first • end last • enter open the scene • ? toggle help • q quit
```

In a mounted scene the catalog paints one status row and one help row around it, and it captures
one gesture: `ctrl+c`. Every printable key stays with the mounted scene, so typing in a draft
never triggers catalog navigation. A scene that
binds `q` itself still gets its own `q`: the demo's `Command.quit()` is a quit request like any
other, and the run ends. The showcase therefore leaves by `ctrl+c` and quits from the
catalog. The help rows display `ctrl+c back to catalog`.

Scenes keep their own root key bindings and event observer inside the catalog frame, including
scenes without a focused input widget. Spinner, progress, countdown, stopwatch, terminal-query and
clipboard shortcuts therefore work just as they do in standalone demos.
The catalog's `ctrl+c` return binding also belongs to each modal scope, so an open
selection dialog or confirmation can return directly to the catalog.

Two behaviors differ from a standalone launch, both deliberate:

- **Pointer level.** The showcase asks for `MouseMode.CELL_MOTION`, which is the level its scenes
  need most (drag selection in the mouse scene). A scene that runs standalone asks for its own.
- **Inline output.** Phase 23's `Command.println` is a defined no-op on the alternate screen, and the
  showcase owns the alternate screen. The mounted inline demo therefore paints its status rows and
  counts its installs while its durable lines stay unseen; `--parity-phase 23` or
  `./run-demo.sh` on that demo alone is where the durable lines are verified.

The refresh of each mounted scene is the scene's own: the catalog passes it the rows and columns it
allocated, so a scene that sizes itself from the viewport sizes itself to its frame.

### Phase 24: `WorkspaceDemo`

The catalog proves each phase in isolation; `WorkspaceDemo` proves they compose. It is one task
workspace over six mounted widgets, arranged so that each one's output is another one's input.

| Key | Action |
| --- | --- |
| `tab`, `shift+tab` | move the keyboard through the shown page's panes |
| `alt+1`, `alt+2` | show the preview page or the task page |
| `ctrl+s` | open the confirmation dialog |
| `alt+t` | start or pause the review timer |
| `a`, `x` | add a task, remove the selected one |
| `enter`, `esc` | the focused widget's own actions, and the dialog's confirm and cancel |
| `q`, `ctrl+c` | quit |

```text
page=preview focus=files source=notes.md lines=7 limits=none
query=lantern matches=2 active=1 follow=on
tasks=3 done=1 target=0.33 timer=02:00 run=off dialog=closed
synced=0 notes=0 refused=0 timeouts=0 last=chosen-notes.md
╭───────╮╭───────╮╭─────────╮
│Preview││ Tasks ││ Archive │
```

The workspace's own rules, in the order they bind:

- **Files decide the projection.** Choosing a Markdown entry replaces the source the pager pages;
  an entry the picker's extension policy refuses is reported (`refused=`) and the projection is left
  alone. The documents are the fixture set the picker browses, mapped by name - the scene never reads
  a file back, so a test drives the same path with no filesystem at all.
- **The query decides the ranges, the pager paints them.** Typing in the find field republishes the
  matches and selects the first one; a re-projection of the same source selects nothing, so a resize
  never moves the reader.
- **The table decides the bar.** The table's own `enter` output toggles the chosen task through one
  `Replace` message, and the progress bar's target follows the completed fraction. Neither widget
  knows the other exists.
- **The field decides whether a draft is a note.** The note field carries the scene's policy, so a
  blank or over-long draft is refused by the field, reported as `refused=`, and never submitted; a
  valid one is counted as `notes=`, and the field clears itself.
- **The dialog decides when the workspace applies.** `ctrl+s` opens a modal confirmation; while it
  is open nothing under it is reachable, `esc` declines and leaves everything as it was, and `enter`
  re-projects the preview, lands the progress bar and counts `synced=`.
- **The clock is the countdown's.** The review timer runs on the runtime's own monotonic readings and
  reports its expiry as `timeouts=`. Nothing in the scene reads a clock.

The draft, the query, the selection and the running timer all survive a tab switch, because the bar
keeps every page mounted for the whole session: switching a tab changes what is painted and nothing
else.

### Phase 28: `WidgetGalleryDemo`

Every widget added for the UI-capability milestone on one screen: the standalone scrollbar and split
pane, the tree, the menu and the command palette, the select, multi-select, checkbox, radio group and
form, the dialog, the toast and the notification center, the line chart, bar chart, sparkline and
gauge, and the calendar and log viewer. Nineteen widget suites prove one widget at a time; this scene
is the evidence that the set composes under one model, one key path and one theme store.

| Key | Action |
| --- | --- |
| `alt+1` … `alt+4` | show the choice, navigation, message or data page |
| `tab`, `shift+tab` | move the keyboard between the specimens of the shown page |
| `ctrl+g` | show a toast |
| `ctrl+d` | open the confirmation dialog |
| `ctrl+s` | submit the form with what its two editors hold |
| `q`, `esc` | quit |
| any specimen's own keys | the focused widget's actions: arrows, `enter`, `space`, typing |

```text
braxton widget gallery — milestones 4.1 to 4.9
gallery page=1/4 choice widgets=5 names=select,tags,remember,density,form
focus=select last=none reports=0
select=standard tags=design remember=off density=compact form=name
select      Standard                                                           ▾
multi-selectDesign                                                             ▾
checkbox    [ ] remember this page
radio group
● Compact
○ Comfortable
form (ctrl+s submits)
❯ Name
name: Ada
  Email
email: ada.example
```

`gallery page=` is the shown page, how many specimens it holds and their gallery names; `focus=` is
whose keyboard it is; `last=` is the scene's log of its own gestures - `page-choice`, `focus-tags`,
`select-chosen`, `tags-chosenmany`, `remember-changed`, `menu-chosen`, `palette-chosen`, `bar-14`,
`split-27`, `log-chosen`, `notices-chosen`, `toast-shown`, `dialog-canceled`, `calendar-chosen`,
`form-rejected` - and `reports=` counts them, which is what makes a repainted frame distinguishable
from a skipped one. The fourth row is the shown page's published state, so a verifier asserts what a
widget decided without decoding a style.

Six rules the scene is written to:

- **Focus is model data, not tree data.** Every specimen holds its own `focused` flag, and `tab`
  writes it into the next one's model; the kernel then routes the gesture to the leaf whose model
  says it is focused. Nothing in the scene moves focus in the tree, because there is nothing to move.
- **A widget instance outlives a frame.** The scene holds every widget, so a pointer capture, a drag
  and a double press stay with the widget that started them - a per-frame instance would lose the
  gesture between the press and the release.
- **An output is the scene's message.** Each specimen's reduction returns the widget's own output -
  `Chosen`, `ChosenMany`, `Changed`, `Inspected`, `Submitted`, `Rejected`, `Expired`, `Canceled` -
  and the gallery records the kind on the status row rather than reading the widget's state again.
- **A scrollbar scrolls something.** The standalone bar's window is the log viewer's own admitted
  window, and a bar gesture becomes the log line it lands on, so the two cannot disagree about where
  the reader is.
- **The page under a modal layer is unreachable.** The dialog is an `Elements.modal` layer over the
  painted page, and its own `esc` closes it and hands the keyboard back to the page.
- **The form's focus names a field.** The form places the scene's two editors, and the scene gives
  one of them the keyboard: a form cannot hold it while a field is editing, because the kernel has
  one focused leaf. `ctrl+s` is the scene's own binding, because only the scene holds the values.

Typing in the palette filters its commands and shows the keyboard's turn; a press on a log line or a
calendar day is the same gesture in the pointer's terms. The page is four screens because nineteen
widgets do not fit on a twenty-four row terminal at a size worth reading.

### Running the whole record

```bash
python3 tools/terminal/verify_terminal.py --parity-all --repeat 10 --pending-escape --harness
```

`--parity-all` runs every phase's own scenario and every packaged demo launch in one invocation, on
top of the native battery those flags name. It is the integrated gate: a phase is expected to pass on
its own first (`--parity-phase NN`), and this is what proves they still pass together.

### Phase 29: `LinePromptsDemo`

The four prompts of the `line` package, asked in order by a line-oriented program that never takes
the screen. The demo opens the terminal, enters raw mode and asks a confirmation, a single choice, a
set of marks and a bounded progress count, printing one durable answer line after each so the
transcript says where the dialog reached. Everything already printed above the prompts survives the
whole run.

| Key | Action |
| --- | --- |
| `y`, `yes`, `n`, `no`, `enter` | answer the confirmation; a bare `enter` takes the default |
| `ctrl+c` | abandon the confirmation, the line editor's own cancellation |
| `↑` / `↓`, `home` / `end`, `pageup` / `pagedown` | move the highlight in a list prompt |
| `enter` or `space` | choose the highlighted option (single choice) |
| `space` | mark or unmark the highlighted option (multiple choice) |
| `enter` | accept the marks made so far |
| `esc`, `ctrl+c` | abandon a list prompt |

```text
Braxton line prompts
Overwrite the report? [y/N] y
confirm=yes
Pick a theme  (enter chooses, esc cancels)
❯ dark
  light
  high-contrast
theme=light
Build which targets  (space marks, enter chooses, esc cancels)
[✓] linux-x86_64
[✓] linux-aarch64
[ ] macos-arm64
[ ] macos-x86_64
targets=linux-x86_64,linux-aarch64
Installing packages  ████████████████████████████  6/6  100%
prompts confirm=yes theme=light targets=2/4 progress=6/6
```

What the scene is written to, and why:

- **Nothing takes the screen.** Every prompt paints in the terminal's own rows, below whatever the
  program has already printed, so a wizard is usable after a command has produced output.
- **A confirmation is a line.** Typing `y` alone answers nothing until `enter` arrives, which is
  what keeps a user who types `yes` from answering three times and leaving keystrokes behind.
- **A list prompt is a value's window.** The highlight clamps rather than wrapping, the window follows
  it, and a key the prompt does not name changes nothing at all.
- **A mark stays where it is.** `space` marks or unmarks the highlighted option without moving, so
  marking two neighbors is two gestures rather than a skip.
- **The progress row is repainted, not appended.** The bar keeps one row and the final frame stays on
  screen when the prompt closes, so the transcript shows the completed count once.

## Adding a scenario

A phase contributes three things, in this order, once its behavior exists:

1. **A demo** under `src/main/java/examples/parity/`. Keep a public `main` for the packaged launch
   and a reusable scene component for tests, and keep the demo dependency-free.
2. **A probe entry** in `tools/terminal/ParityProbe.java`: add the two-digit phase to the dispatch
   and run the demo's scene. The verifier compiles the probe into its own temporary directory, so
   nothing new belongs in the build.
3. **Verifier assertions** in `tools/terminal/verify_terminal.py`: write a `check_<phase>_scenario`
   function that drives real keys through an owned PTY, then register it in `PARITY_SCENARIOS`.
   `--parity-phase NN` runs only that scenario and the packaged demo launch.
4. **A row in [PARITY.md](PARITY.md)** for every reference example the phase covers, so the record
   says what the phase is evidence for. `ParityAcceptanceTest` reads that file and fails when a row
   names a demo, test or scenario that does not exist, so the record cannot drift from the code.

Assertions belong on the emulated screen and on what the model published to it: a status row, a
paint style, a chosen output. Prefer text that names state over text that names styling, and keep
every assertion reproducible from a fixed terminal size.


## Phase 30: rich output and reduced motion

Run `examples.parity.RichOutputDemo` or select `30` in the showcase. Space starts or retargets a
600 ms transition, `m` toggles reduced motion, and `q` quits. Reduced motion selects the target
immediately and holds animation frame `a`; switching back keeps the settled value. Tick
subscriptions exist only while a normal-motion transition is active. The image uses the composed
half-block grid, including when the terminal is narrow.

```
rich output motion=normal value=0 target=0 state=settled moves=0
image fallback=half-block
```

`ParityRichOutputTest` uses manual time; verifier scenario `30` checks normal completion,
immediate reduced-motion completion, the stable frame, fallback pixels, resizing and cleanup.
Native graphics codecs are verified separately by independent payload decoders.


## Phase 31: developer diagnostics

Run `examples.parity.DebugOverlayDemo` or select `31` in the showcase. The runtime overlay starts
on. Tab moves focus between two ordinary checkboxes, space toggles the focused one, `d` toggles
diagnostics, and `q` quits. The panel reports actual layout bounds, the active focus path, up to
four recent messages and view/layout/paint timing. It does not participate in input routing.
Turning it off releases its message history; returning from this scene in the showcase also
turns the session-level overlay off.

```
debug overlay=on focus=left left=off right=off changes=0
```

Scenario `31` verifies routed widget input, focus paths, fresh history after a toggle and geometry
after resize. `DebugOverlayTest`, `RuntimeDiagnosticsTest`, `DebugOverlayRuntimeTest` and
`ParityDebugOverlayTest` cover the snapshot, owner-side effect and scene without native access.

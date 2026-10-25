# Braxton

**A Model–Update–View (MVU) terminal UI framework for Java.**

[![Java](https://img.shields.io/badge/Java-25%2B-blue.svg)](#requirements)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Runtime dependencies](https://img.shields.io/badge/terminal-JLine%20FFM-blue.svg)](#design-principles)
[![Status](https://img.shields.io/badge/status-pre--1.0-orange.svg)](#project-status-and-versioning)

Braxton builds interactive terminal applications from immutable state, typed messages, pure
functions, and declarative views. A single runtime owns the terminal, the input loop, effects,
rendering, and shutdown, so application code never touches escape sequences, threads, or global
key handlers.

- **Immutable by default** — models, views, effects, and widget state are values.
- **Pure updates and views** — no I/O, clock reads, or terminal access in application logic.
- **Typed end to end** — application-owned sealed messages, exhaustive dispatch, no event bus.
- **Explicit effects** — commands and subscriptions have defined lifetimes and cancellation.
- **Deterministic rendering** — a structured cell renderer, one encoder, and a headless test path.
- **JLine FFM terminal integration** — Braxton owns MVU and rendering; JLine owns native terminal access.

---

## Table of contents

- [Requirements](#requirements)
- [Build from source](#build-from-source)
- [Quick start](#quick-start)
- [Core concepts](#core-concepts)
- [Views and layout](#views-and-layout)
- [Input, focus, and routing](#input-focus-and-routing)
- [Commands and subscriptions](#commands-and-subscriptions)
- [The editor](#the-editor)
- [Widgets, assets, and clipboard](#widgets-assets-and-clipboard)
- [Runtime and terminal](#runtime-and-terminal)
- [AI Harness](#ai-harness)
- [Architecture](#architecture)
- [Design principles](#design-principles)
- [Testing and quality gates](#testing-and-quality-gates)
- [Benchmarks](#benchmarks)
- [Compatibility](#compatibility)
- [Limitations](#limitations)
- [Contributing](#contributing)
- [Security](#security)
- [Project status and versioning](#project-status-and-versioning)
- [License and notices](#license-and-notices)
- [Acknowledgments](#acknowledgments)

---

## Requirements

| Requirement | Version |
| --- | --- |
| JDK | **25 or newer** (the build targets bytecode 69) |
| Maven | **3.9.16 or newer** (the wrapper pins this version) |
| Terminal | ANSI/VT-capable terminal; interactive runs require `--enable-native-access=ALL-UNNAMED` |

Platform support is described in [Compatibility](#compatibility).

## Build from source

From a Braxton checkout beside the patched JLine checkout (`../jline3`), first install its three required modules
under the distinct local version used by this project's POM:

```bash
cd ../jline3
TERM=xterm-256color ./mvx mvn -B -ntp -pl terminal-ffm -am -Dnisse.jgit.dynamicVersion=0.1.0-3601-SNAPSHOT -Djava.awt.headless=true clean install
cd ../braxton
```

This local JLine build serializes a closed-stream check and byte consumption after native poll completion, preventing a
closed input pump from consuming the next application's first key. Published Maven 4.4.8 does not
contain this fix. The local version is not a Central release and must be installed before building
Braxton or using its Maven artifact on another machine.

```bash
./mvnw clean verify     # compile, run the test suite, build the jar
./run-ai-harness.sh     # package, then launch the bundled AI Harness example
```

`./run-ai-harness.sh` runs `./mvnw clean package` and starts the harness from the packaged
`target/braxton-*.jar`; pass `--no-build` to reuse an existing jar. Gson and its annotation
companion are copied to `target/showcase-lib` and are never bundled in the Braxton jar.

On Windows, use a local Maven 3.9.16+ installation (`mvn clean verify`); the Bash launcher and the
`mvnw` script are Unix-only. The local JLine build uses the FFM provider exclusively; JNI, exec, and dumb fallback are disabled.

**Maven coordinates:** `io.github.dhoard:braxton` (automatic module name
`io.github.dhoard.braxton`). Braxton is pre-1.0 and is not yet published to a public repository;
build it from source or install it locally with `./mvnw clean install`.

## Quick start

A complete program is a model, a message type, an `update` function, and a `view` function.

```java
package demo;

import java.util.List;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.app.InitContext;
import io.github.dhoard.braxton.app.Program;
import io.github.dhoard.braxton.app.Transition;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.layout.Viewport;
import io.github.dhoard.braxton.style.Style;
import io.github.dhoard.braxton.view.View;
import io.github.dhoard.braxton.view.Views;

public final class HelloProgram implements Program<HelloProgram.Model, HelloProgram.Msg> {

    public record Model(int count) {}

    public sealed interface Msg {
        record Increment() implements Msg {}
        record Quit() implements Msg {}
    }

    @Override
    public Transition<Model, Msg> init(InitContext context) {
        return Transition.next(new Model(0));
    }

    @Override
    public Transition<Model, Msg> update(Model model, Msg message) {
        return switch (message) {
            case Msg.Increment ignored -> Transition.next(new Model(model.count() + 1));
            case Msg.Quit ignored -> Transition.next(model, Command.quit());
        };
    }

    @Override
    public View<Msg> view(Model model, Viewport viewport) {
        return Views.keyed(
                List.of(
                        Binding.of("space", new Msg.Increment()),
                        Binding.of("ctrl+c", new Msg.Quit())),
                Views.column(
                        Views.text("Hello, terminal", Style.NONE.withBold()),
                        Views.text("count: " + model.count())),
                List.of());
    }

    public static void main(String[] args) {
        Application.run(new HelloProgram());
    }
}
```

```bash
./mvnw -q compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/showcase-lib
java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/showcase-lib/*" demo.HelloProgram
```

The only bundled application is the [AI Harness](#ai-harness); it is a complete, production-shaped
example of the same API.

## Core concepts

```text
input / resize / timers / task results
                │
          runtime envelopes
                │
      scoped input routing → Msg
                │
       update(model, msg) → Transition
                │
     immutable model + Command
                │
      view(model, viewport) → View<Msg>
                │
   layout → styled cells → diff → terminal
```

The contract is deliberately small:

```java
public interface Program<M, Msg> {
    Transition<M, Msg> init(InitContext context);
    Transition<M, Msg> update(M model, Msg message);
    View<Msg> view(M model, Viewport viewport);
    default Subscription<Msg> subscriptions(M model) {
        return Subscription.none();
    }
}

public record Transition<M, Msg>(M model, Command<Msg> command) {}
```

Rules the runtime relies on:

1. **`init`, `update`, `view`, and `subscriptions` are pure.** No I/O, clock reads, terminal
   access, or mutation of their arguments. `InitContext` carries already-captured values
   (viewport and terminal capabilities). Application configuration belongs in the program constructor; time arrives through explicit effects/messages.
2. **Messages are application-owned.** A sealed interface of records keeps dispatch exhaustive
   and lets child features map their messages into a parent without casts.
3. **Effects are data.** Return a `Command`; the runtime interprets it after the model commits.
4. **Views are descriptions.** Building or drawing a view never mutates state.
5. **One owner.** The runtime serializes routing, `update`, command interpretation, subscription
   reconciliation, layout, and output. Background workers only enqueue messages.

## Views and layout

`View<Msg>` is a sealed immutable tree. The primitives are intentionally few:

| View | Purpose |
| --- | --- |
| `View.Text(StyledText)` | Wrapping, clipped styled text |
| `View.Column` / `View.Row` | Stacking with cross-axis alignment |
| `View.Fixed(n, child)` | Exactly `n` cells on the main axis |
| `View.Grow(weight, child)` | Share of the remaining main-axis space |
| `View.Padding` / `View.BorderBox` | Spacing and frames with optional titles |
| `View.Overlay(layers, modal)` | Layered painting; `modal` restricts input |
| `View.Keyed(capture, intercept, child, bubble)` | Ancestor bindings and event interception |
| `View.Focused(id, focused, bindings, input, child)` | Focus identity, bindings, and input mapping |
| `View.Fill` / `View.Blank` | Background fill and zero-size placeholder |

Layout is deterministic: intrinsic sizes are measured first, flex space is divided by weight in
declaration order, children are clipped to their rectangle and to ancestor bounds, and a wide glyph
that does not fit is clipped whole rather than half-painted. Tiny viewports degrade by clipping,
never by negative arithmetic.

For tests and diagnostics, `ScreenRenderer` lays out a view for a fixed viewport using the same
code the runtime paints through:

```java
List<String> lines = new ScreenRenderer().plainLines(view, new Viewport(24, 80));
```

## Input, focus, and routing

Keys are values (`Key.of("up")`, `Key.parse("ctrl+alt+x")`) and a `Binding<Msg>` maps one gesture
to one message. Routing order is structural and independent of registration order:

```text
topmost modal scope
  → ancestor capture bindings (outer → inner)
  → ancestor interceptors (full InputEvent)
  → focused element bindings
  → focused element input handler
  → ancestor bubble bindings (inner → outer)
```

The first consumer wins, so a consumed shortcut never also inserts text. Duplicate bindings in one
phase are rejected when the view is built.

Focus is model data: `FocusState` holds an `ElementId`, and `ElementId.child("name")` namespaces a
child feature. A child name must not contain the `/` separator, because joining one would forge an
identity that another parent already owns. Focus survives view rebuilds and sibling reordering; a
target that disappears simply receives nothing.

Normalized input is one of `KeyInput`, `PasteInput`, or `ResizeInput`. Printable keys carry their
text exactly once, and bracketed paste arrives as one atomic event that never triggers Enter or a
shortcut.

An ESC byte followed by a key inside the escape timeout is that key with `alt` added to the
modifiers the byte already names, which is how xterm-family terminals report the Alt/Meta key:
`ESC CR` is `alt+enter`, `ESC TAB` is `alt+tab`, `ESC DEL` is `alt+backspace`, and `ESC` plus the
Ctrl+C byte is `ctrl+alt+c`. A control byte never disappears into a bare Escape and never becomes a
key name or text. ESC followed by ESC is two Escape gestures, and a lone ESC is one.

An ESC byte inside an escape sequence cancels the sequence in progress and starts the next keypress,
so a sequence a terminal leaves open — Meta+[ is reported as `ESC [` — never types its parameters or
its final byte, and the key that follows keeps its own meaning. A sequence left incomplete when the
escape timeout expires is abandoned the same way rather than waited for, because the byte that
arrives later may be the next key the user pressed.

## Commands and subscriptions

`Command` values are inert descriptions:

| Command | Effect |
| --- | --- |
| `Command.none()` / `Command.batch(...)` | Nothing / ordered batch |
| `Command.delay(id, duration, message, rejected)` | Deliver a message later |
| `Command.task(id, work, onSuccess, onFailure)` | Run application work on the effect executor |
| `Command.cancel(id)` / `Command.cancelNamespace(ns)` | Invalidate effects |
| `Command.copy(text, mapper)` | Write the clipboard and report a `ClipboardResult` |
| `Command.readClipboard(mapper)` | Read the session's in-process clipboard buffer |
| `Command.quit()` | End the program |

Effects are epoch-guarded: replacing or canceling an id means a late completion can never reach the
model. Effect ids are namespaced too, and, like a focus target, a child name must not contain the
`/` separator: joining one would forge an id another parent already owns, so the two features would
register as one effect and the later would replace and cancel the earlier. Task results, timer
ticks, and clipboard reports arrive as ordinary messages, so `update` is never re-entered.
`Command.task` bodies are the documented impure boundary — they must not touch the terminal,
mutate captured state, or call `update`.

`Subscription` describes ongoing sources:

```java
Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(50), Msg.Tick::new);
Subscription.resize(SubscriptionId.of("resize"), Msg.Resized::new);
Subscription.source(id, configuration, emitter -> closeable, mapper);
```

After every commit the runtime reconciles the description against the live set: unchanged
identity/configuration keeps the resource and replaces only the message mapper, changed or removed
sources are closed exactly once, and duplicate ids are rejected. Subscription ids are namespaced the
same way as effect ids, and a child name must not contain the `/` separator: joining one would make
two declared paths one id, which the duplicate check then rejects.

## The editor

`Editor` is a pure reducer plus a declarative view. The draft lives in your model:

```java
EditorResult result = Editor.update(model.draft(), options, action);
```

`EditorAction` covers insertion, paste, deletion, movement, selection, submit, copy, and paste
requests. The result is a new `EditorState` plus an `EditorIntent`, so the application decides what
submission, copying, or pasting means.

```java
View<Msg> composer = Editor.view(ElementId.of("composer"), state.draft(), options,
        focused, action -> new Msg.Editor(action));
```

Guarantees:

- caret and selection positions are UTF-16 offsets on grapheme boundaries;
- insertion leaves the caret after the inserted content, advancing to the end of the resulting
  grapheme when it joins the following text;
- deletion leaves the caret at the deletion point, advancing to the end of the joined grapheme
  when the surviving text on either side of the removed range merges into one cluster;
- editing never splits combining sequences, ZWJ emoji, or surrogate pairs;
- length limits truncate at a complete grapheme; unlimited is explicit;
- password/hidden editors mask output and refuse to copy by default;
- visual scroll-to-caret is derived, so drawing never mutates state.

## Widgets, assets, and clipboard

Widgets are pure factories producing immutable `StyledText`/`View` values, and rendering one
mutates nothing. Two assembly styles exist for the multi-part widgets. `ProgressBar` is an
immutable value: every setter returns a new instance. `Table`, `Box`, `Badge`, `Quote`,
`ImageView`, and `Logo` are assembled with a small mutable builder whose setters return the same
instance, so build one inside `view` (or a factory) instead of sharing it across frames. Widget
state you own - `Selector.State`, `EditorState` - is a value in your model:

| Type | Purpose |
| --- | --- |
| `Markup` | Safe XML-like rich text (`span`, `p`, `a`, `ul`/`li`, `table`, `br`) |
| `Table` | Buffered column widths, optional border |
| `Box` | Border, title, responsive or fixed width |
| `Badge` | Pill segments with optional pathway arrows |
| `ProgressBar` | Active/inactive icons, separators, percentage |
| `Quote` | Colored bar and background |
| `Gradient` | Vertical, horizontal, or diagonal per-grapheme color |
| `Selector` / `TableSelector` | Immutable selection state and focusable views; the marker shows the selection, the active style follows focus |
| `Animation` | Spinner and shimmer frames driven by model time |
| `FontLoader`, `FigletFont`, `Logo` | Bundled FIGlet fonts and styled logos |
| `ImageAsset`, `ImageView` | Explicit image loading and pixel-art rendering |

`Markup` parses one well-formed XML document, so inline markup stays predictable: exactly one
root element and explicitly closed empty elements (`<br/>`, not `<br>`). It disables DTDs and
external entities, so untrusted text cannot fetch a file or URL.

**Line endings.** `LineBreaks` is the one preparation policy for raw text: `normalize` turns LF,
CRLF, and lone CR into one LF per logical break, `singleRow` turns them into one space, and
`singleRowAndTabs` also turns a tab into one space. Prepare the *complete* raw string before
constructing `StyledText`, because `Span` deletes a bare CR as an unrepresentable control
character: `StyledText.plain("a\rb")` is `"ab"`, and no widget can recover a separator that is
already gone. Pass `StyledText.plain(LineBreaks.normalize(raw))` to a styled-input surface such
as `Badge`, or flatten the raw text when a one-row value is intended. The helpers are stateless:
a CR at the end of one call and the LF at the start of the next are two independent breaks, so
assemble the value first. `PasteSanitizer` and editor insertion normalize before their own
filtering and truncation. `EditorState` normalizes the draft it is given - by its constructor,
`of(...)`, or `withText(...)` - and carries the caller's caret and anchor offsets onto the prepared
text, so a draft restored from CRLF data never holds a character that the caret's column arithmetic
counts while the painted row omits its cell. `Box.title(String)` flattens its raw title before the
span boundary; its `StyledText` overload still receives already-sanitized content. Lower-level
`View.BorderBox` titles keep their existing first-row clipping and do not flatten.

**Clipboard and paste.** `PasteSanitizer` offers `NORMAL` (preserves indentation) and `AGGRESSIVE`
(trims and can collapse blank lines) modes. `ClipboardConfig` snapshots `braxton.clipboard.*`
properties per session. The in-process buffer is always the dependable destination; OSC 52 is
written at most once, through tmux-native or DCS-passthrough transport when configured.
`ClipboardResult` distinguishes stored, emitted, truncated, deduplicated, and internal-only
outcomes — OSC 52 has no acknowledgment, so Braxton never claims the operating-system clipboard
accepted a payload. Copy sequences share the frame output owner and cannot split a render.

## Runtime and terminal

`Application.run(program)` owns a JLine FFM `Terminal`, raw mode, alternate-screen/cursor,
paste/mouse modes, input, sizing, and cleanup. It requires an attached process terminal and explicit
native access. It returns a structured failure when those prerequisites are missing. There is no
Braxton terminal backend/session SPI, alternate provider, or implicit `/dev/tty`/plain-output fallback.
Closing a run stops its input consumer before terminal restoration and release; shared process streams
remain available for subsequent runs.

`RuntimeOptions` contains immutable limits and choices, including `InputOptions`, clipboard policy,
and `app.TerminalOptions`. Clocks, timers, executors and diagnostic plumbing are internal runtime
services, not application configuration. `ScreenRenderer.screen` returns an immutable
`RenderedScreen` with cells/continuations and public ID/rectangle regions, using the same layout path.

Defaults are configuration, not hidden constants:

| Setting | Default |
| --- | --- |
| Mailbox capacity | 4,096 non-coalescible envelopes |
| Concurrent one-shot effects (tasks + delays) | 64 |
| Ongoing sources | 64 |
| Paste payload / idle timeout / escape timeout / CSI length | 1 MiB / 250 ms / 30 ms / 64 bytes |
| Shutdown grace | 2 s |

`ExitResult` distinguishes a requested quit, clean end-of-input, caller interruption (status 130), and failure, and carries cleanup
failures without hiding the primary cause. Rendering is event-driven: an idle application does no
work, and an interval subscription drives animation only while it is needed.

Application messages update the model, commands, and subscriptions in order. Their dirty screens
share presentations with a minimum 16 ms gap, using the mailbox's real monotonic wait clock.
A pending screen is painted even if no further message arrives; continuous traffic cannot defer
it indefinitely. Input and resize request immediate presentation, and input bindings are rebuilt
from the latest committed model before routing. Orderly quit/end-of-input flushes a pending screen.
An unchanged model does not request a frame. Pacing adds no rendering thread or recurring idle timer.

Text wrapping and concatenation assemble whole styled runs rather than copying an immutable
prefix for each grapheme. Immutable append chains normalize their spans on first read and release
their intermediate operands afterward. Painting stops wrapping at the visible row limit, while
public intrinsic measurements still describe the full content. Unicode cursor movement segments
each target line once, and transcript projection styles only the visible window. Printable ASCII
uses the same one-column semantics without Unicode iterator/code-point allocation; other text
retains the pinned Unicode and width-policy behavior.

Braxton uses a virtual input worker and virtual threads for its timer/effect work. The runtime
owner is the caller's thread. Resize signals mark pending work without calling application code;
the owner measures through JLine, with a 250 ms fallback size check. JLine manages its own native
reader, signal, and shutdown threads; those threads are not necessarily virtual.

Input uses JLine's timed character reader. Timeout is distinct from EOF; scalar assembly preserves
supplementary characters and paste limits count UTF-8-equivalent bytes of complete scalars.
Malformed encoded text follows JLine replacement decoding, including its handling of incomplete
trailing bytes. Input content still passes through Braxton's normalization and rendering sanitization.

## AI Harness

`examples.harness.ai.HarnessProgram` is a complete application built on the public API:

- transcript loading is a task command;
- response pacing is a delayed message guarded by generation and ordinal;
- animation is a 50 ms interval subscription, active only while working or while a hint is armed;
- clipboard copy/paste goes through commands and the shared output owner;
- cancellation and exit use input capture timestamps.

| Key | Action |
| --- | --- |
| Type / Enter | Edit and submit a prompt |
| Ctrl+J | Insert a newline |
| Up / Down, mouse wheel | Scroll the transcript |
| PageUp / PageDown | Page the transcript |
| End | Jump to the bottom |
| Tab | Focus the composer |
| Esc | First press arms a 2 s cancel window; a second press inside it stops the turn and reports any queued prompts it discarded |
| Ctrl+C | First press arms a 2 s exit window; a second press inside it exits |
| Alt+C | Copy the composer selection (or the whole draft) |
| Ctrl+V | Paste the in-process clipboard buffer |
| Alt+T / Alt+L | Copy the visible transcript / the last assistant reply |

Any other key cancels a pending Ctrl+C confirmation; a later Ctrl+C becomes the new first press.
Transient feedback (clipboard outcomes, a full queue, the Ctrl+C confirmation) appears on the rule
above the footer until the next action replaces it; the Ctrl+C hint leaves the rule with the
confirmation it announces, so cancelling it or missing the window also clears it.
The harness is fully offline: it replays a sanitized bundled transcript and deterministic fallback
responses, with no model, provider, network, or user-session access.

## Architecture

| Package | Responsibility |
| --- | --- |
| `io.github.dhoard.braxton.app` | `Program`, `Transition`, `Application`, runtime options, immutable startup facts and terminal presentation choices |
| `io.github.dhoard.braxton.effect` | `Command`, `Subscription`, effect and source identities |
| `io.github.dhoard.braxton.input` | Value keys, modifiers, normalized events, bindings |
| `io.github.dhoard.braxton.view` | Immutable views, `Views`, `ElementId`, `FocusState`, `ScreenRenderer` |
| `io.github.dhoard.braxton.layout` | `Size`, `Viewport`, `Rect`, `Insets`, `Alignment`, `Border` |
| `io.github.dhoard.braxton.style` | `Color`, `Colors`, `LineBreaks`, `Style`, `StyledText`, `TextMeasure`, `WidthPolicy` |
| `io.github.dhoard.braxton.widget` | Editor, markup, tables, boxes, badges, bars, quotes, gradients, selectors, animation |
| `io.github.dhoard.braxton.asset` | FIGlet fonts, logos, image assets and views |
| `io.github.dhoard.braxton.clipboard` | Configuration, sanitization, modes, results |
| `io.github.dhoard.braxton.internal` | Runtime loop, mailbox, effects, subscriptions, layout, cells, encoder, direct JLine integration |

Dependencies point inward: examples depend on the public library; runtime/input internals use JLine;
models, views, routing and layout remain independent of terminal I/O. `internal` packages are not a supported
API surface.

## Design principles

- **State changes only through `update`.** Widget state is a value in the model; configuration is
  separate and immutable.
- **Effects are data with lifetimes.** Commands run once; subscriptions are reconciled, epoch-guarded,
  and closed deterministically.
- **Routing is structural.** Modal → capture → interceptor → focused → bubble, with duplicate
  bindings rejected at build time.
- **Rendering is safe by construction.** Content is control-character sanitized; only the encoder
  emits SGR/OSC 8 sequences. `LineBreaks` prepares raw line endings before that boundary.
- **Measurement is explicit.** Grapheme segmentation and terminal-column width are separate
  policies; the pinned Unicode 16.0.0 table is reproducible offline, and invisible format
  characters (joiners, bidi marks, a byte-order mark) contribute no column.
- **Testing is first-class.** The same layout/rendering path is available headlessly, and the
  automated suite is TTY-free.

## Testing and quality gates

```bash
./mvnw -B clean verify     # compile with -Xlint:all, run the suite, build the jar
./run-ai-harness.sh        # package and run the bundled example
```

The build must stay warning-free under `-Xlint:all`, the core must confine JLine terminal dependencies to runtime/input integration, and the test suite must remain independent of a TTY, stdin, and raw mode. A headless
test run does not replace a manual check of a real terminal session.

MIT license headers are enforced by Spotless during `verify`. Dependency-update reports honor the
pre-release filter in `.versions-rules.xml`:

```bash
./mvnw versions:display-dependency-updates
```

## Benchmarks

The JMH suite under `src/test/java/io/github/dhoard/braxton/benchmark` measures the paths a frame
actually costs time in: text measurement and wrapping, styled-run assembly, whole-tree layout, cell
painting and ANSI encoding, editor draft rendering, input decoding, and one whole frame end to end.

```bash
./mvnw -o -B -ntp test-compile dependency:build-classpath \
    -Dmdep.includeScope=test -Dmdep.outputFile=target/bench-cp.txt

java -cp "$(cat target/bench-cp.txt):target/classes:target/test-classes" \
    org.openjdk.jmh.Main -l                       # list the benchmarks

java -cp "$(cat target/bench-cp.txt):target/classes:target/test-classes" \
    org.openjdk.jmh.Main -f 1 -wi 1 -i 1 -r 1 -w 1 -prof gc   # smoke run, seconds

java -cp "$(cat target/bench-cp.txt):target/classes:target/test-classes" \
    org.openjdk.jmh.Main -prof gc -rff target/benchmarks/results.csv   # full suite, ~18 min
```

Benchmarks are compiled by the normal build and **never run by `verify`** — surefire excludes them.
Fork, warm-up and measurement counts live in the benchmark annotations, so a full run is comparable
across changes; overriding them on the command line (as the smoke run does) produces a number that
must not be compared with a recorded baseline. Every result carries an error margin and an allocation
rate (`gc.alloc.rate.norm`), and both are specific to the machine and JDK that produced them.

A benchmark delta is not by itself a reason to change the library. A performance change needs a
focused scope, a deterministic in-repo oracle that fails before the change and passes after it,
the full project gate, and a terminal-emulation check when TUI code is touched.

## Compatibility

| Platform | Status |
| --- | --- |
| Linux / glibc x86_64 | Local JLine FFM build; repeated PTY/pyte checks pass on Corretto 25.0.3; see implementation record |
| macOS arm64 / aarch64 | JLine FFM target; native verification of this refactor pending |
| macOS x86_64 / amd64 | JLine FFM target; native verification pending |
| Windows x86_64 / console / ConPTY | JLine FFM target; Windows driver added, native verification pending |
| Other OS/architecture combinations | Unverified; no fallback provider |
| Java | 25 or newer |

Interactive runs require `--enable-native-access=ALL-UNNAMED` on the classpath. A module-path
launch must instead grant access to `org.jline.terminal.ffm`. Braxton checks permission before
initializing the FFM provider, even on JDKs that otherwise only warn. Maven resolves the locally
installed `0.1.0-3601-SNAPSHOT` version of the pinned
`jline-terminal` and `jline-terminal-ffm` dependencies and their transitive support artifacts;
JLine is not shaded or optional. A launch with only the Braxton jar on its classpath is insufficient.
JLine still performs native integration and can use internal signal APIs as an upstream fallback.

### Native terminal verification

The ordinary Maven suite uses owned JLine stream terminals and needs no real terminal or native
access. Developer checks use owned PTYs and a private IPC channel to test the packaged MVU runtime:
normalized Unicode/control/paste/wheel input, resizing, pyte output, large frames, idle-reader stop,
immediate reopen, and restoration after quit, failure, JVM shutdown and SIGTERM. Prior native-backend
platform checks do not establish support for this refactor. Forced process kill cannot guarantee restoration.

Use Python with `pyte` and Java/javac on PATH:

```bash
./mvnw clean install
python3 tools/terminal/verify_terminal.py --repeat 10 --pending-escape --harness  # owns its PTYs
```

The verifier builds a runtime dependency classpath, compiles its Java probe in an owned temporary
directory, and tests the packaged jar. It needs no C compiler or Braxton ABI layout probe. The
harness launcher copies all runtime dependencies into `target/showcase-lib` after each clean build.
On Windows, build with Maven and prepare the classpath before the owned ConPTY verifier:

```text
mvn clean install
mvn dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/terminal-cp.txt
python tools/terminal/verify_windows_terminal.py
```

The Windows driver uses developer-only Python ctypes for ConPTY and console-mode snapshots;
it is not part of the published library. Run the corresponding checks on each named platform before
claiming its terminal behavior is verified. Test outcomes and remaining gaps are recorded in
[the implementation record](plans/jline4-mvu-refactor.impl.md#9-execution-record-and-handoff).

The JLine refactor intentionally breaks the previous bootstrap/infrastructure API: backend/session
interfaces and backend-taking run overloads are removed; terminal value imports move to `app`;
`InitContext` exposes facts only; `RuntimeOptions.withEnvironment` and `Transition.stay` are removed.
Use `Transition.next(model)` for a transition without work and `next(model, command)` for work.
Headless callers consume immutable `RenderedScreen`, rather than internal mutable engine objects.

## Limitations

- Emoji width and variation-selector rendering vary between terminal emulators. Braxton pins and
  tests a documented policy; that is not a guarantee about every terminal.
- Terminals that encode Enter as LF cannot be distinguished from Ctrl+J; LF is treated as a newline.
- Mouse support covers wheel scrolling, not click-drag selection.
- Widget padding - `Table` columns, `Quote` blocks, `ProgressBar` cells, `Markup` tables and list
  indents - is measured with the pinned width policy. A renderer built with another policy
  (`WidthPolicy.ONE_COLUMN`, or an application's own) applies to text wrapping and clipping, not
  to those computed widths, so a widget mixing wide and narrow content can look ragged under it.
- Cancellation skips task bodies that have not started on the executor. For already-started tasks
  it is cooperative: a task that ignores interruption is not forcibly stopped; its result is
  suppressed and shutdown waits only for the configured grace period. User task code is not
  sandboxed.
- The API is pre-1.0 and may change between minor releases.

## Contributing

Issues and pull requests are welcome at
[github.com/dhoard/braxton](https://github.com/dhoard/braxton).

Before opening a pull request:

1. Run `./mvnw -B clean verify` and keep the build warning-free.
2. Keep the automated suite TTY-free — no terminal, stdin, raw mode, or external services.
3. Preserve the public contracts: immutable values, typed messages, pure `update`/`view`, explicit
   effects, and structural routing.
4. Keep terminal integration on the selected local JLine FFM build and keep JLine types out of the public MVU API.
   Preserve the thin jar and declared Maven runtime dependencies; Gson remains example-specific.
5. Include a focused regression test for a behavior change and update the README/NOTICE when a
   contract changes.

## Security

- Braxton performs no telemetry and makes no network calls. The bundled AI Harness is offline and
  uses only a sanitized classpath fixture.
- Terminal content is untrusted data: pasted text, markup, links, and asset files are sanitized or
  parsed without external entity resolution.
- `Command.task` runs ordinary application code with the JVM's permissions; it is a boundary, not a
  sandbox.
- Please report security issues through a private security advisory on the project repository rather
  than a public issue.

## Project status and versioning

Braxton is **pre-1.0** (`0.1.0-SNAPSHOT`). The public API is stabilizing but may change between minor
releases; `internal` packages are never part of the supported surface. Releases follow semantic
versioning once 1.0 is reached.

## License and notices

Braxton is licensed under the [MIT License](LICENSE). Bundled fonts, Unicode column-width data, and
the optional example dependency retain their own notices; see [NOTICE](NOTICE).

## Acknowledgments

- The bundled FIGlet fonts come from the FIGlet project; per-font authorship and licensing are
  recorded in [NOTICE](NOTICE) and in each font header.
- The terminal column-width policy is generated from Unicode 16.0.0 data; the license and pinned
  provenance ship with the artifact under `META-INF/braxton/unicode-16.0.0/`.

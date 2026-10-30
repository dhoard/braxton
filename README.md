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

The patched JLine sources are vendored in `vendor/jline3`. `./build.sh` installs its three
required modules under the distinct local version used by this project's POM and then runs the
Maven wrapper with the arguments you pass; with no arguments it runs the `clean install` quality
gate:

```bash
./build.sh                        # install vendored JLine, then ./mvnw clean install
./build.sh clean verify           # install vendored JLine, then ./mvnw clean verify
./build.sh clean install -DskipTests
./build.sh clean                  # remove target/ for Braxton and vendor/jline3
```

The vendored JLine version is derived from the Braxton version: `0.2.0-SNAPSHOT` builds
`0.2.0-jline-braxton`. `./build.sh` refuses to build when `pom.xml` `<jline.version>` or
`vendor/jline3/.mvn/maven.config` disagree with that derivation, and it skips the JLine build when
the installed artifacts are newer than the vendored sources. A `clean` goal removes the vendored
JLine build output as well, so the next build compiles JLine from scratch; installed artifacts stay
in the local repository, as `./mvnw clean` leaves Braxton's installed artifact alone. `./mvnw` on
its own does not build JLine; the `0.2.0-jline-braxton` artifacts must already be installed.

This local JLine build serializes a closed-stream check and byte consumption after native poll completion, preventing a
closed input pump from consuming the next application's first key. Published Maven 4.4.8 does not
contain this fix. The local version is not a Central release and must be installed before building
Braxton or using its Maven artifact on another machine.

```bash
./build.sh clean verify    # install vendored JLine, compile, run the suite, build the jar
./run-ai-harness.sh        # package, then launch the bundled AI Harness example
```

`./run-ai-harness.sh` runs `./mvnw clean package` and starts the harness from the packaged
`target/braxton-*.jar`; pass `--no-build` to reuse an existing jar. Gson and its annotation
companion are copied to `target/showcase-lib` and are never bundled in the Braxton jar.

On Windows, use a local Maven 3.9.16+ installation (`mvn clean verify`); the Bash launcher,
`build.sh`, and the `mvnw` script are Unix-only, so install the vendored JLine artifacts from
`vendor/jline3` separately before building. The local JLine build uses the FFM provider
exclusively; JNI, exec, and dumb fallback are disabled.

**Maven coordinates:** `io.github.dhoard:braxton` (automatic module name
`io.github.dhoard.braxton`). Braxton is pre-1.0 and is not yet published to a public repository;
build it from source or install it locally with `./build.sh`.

## Quick start

A root and its children implement the same `Component<Model, Msg, Out>` contract. This complete
example mounts two counters. Press **a** or **b** to update only that child; its output becomes a
parent message after the child model commits. Save it as `demo/Counters.java`.

```java
package demo;

import java.util.List;
import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.mvu.*;
import io.github.dhoard.braxton.view.*;

public final class Counters implements Component<Counters.Model, Counters.Msg, Never> {
    public record Model(Integer left, Integer right, int lastOutput) { }
    public sealed interface Msg {
        record Left(Counter.Msg value) implements Msg { }
        record Right(Counter.Msg value) implements Msg { }
        record Changed(int value) implements Msg { }
        record Quit() implements Msg { }
    }
    public static final class Counter implements Component<Integer, Counter.Msg, Integer> {
        public enum Msg { INCREMENT }
        public Update<Integer, Msg, Integer> init(InitContext context) { return Update.next(0); }
        public Update<Integer, Msg, Integer> update(Integer model, Msg message) {
            return Update.emit(model + 1, model + 1);
        }
        public View<Msg> view(Integer model, ViewContext context) { return Views.text("count: " + model); }
    }
    private static final Mount<Model, Msg, Never, Integer, Counter.Msg, Integer> LEFT = Mount.of(
            ComponentId.of("left"), new Counter(),
            ModelLens.of(Model::left, (p, c) -> new Model(c, p.right(), p.lastOutput())),
            Msg.Left::new, OutputRoute.toMessage(Msg.Changed::new));
    private static final Mount<Model, Msg, Never, Integer, Counter.Msg, Integer> RIGHT = Mount.of(
            ComponentId.of("right"), new Counter(),
            ModelLens.of(Model::right, (p, c) -> new Model(p.left(), c, p.lastOutput())),
            Msg.Right::new, OutputRoute.toMessage(Msg.Changed::new));

    public Update<Model, Msg, Never> init(InitContext context) {
        var left = LEFT.initialize(context);
        var right = RIGHT.initialize(context);
        return Update.next(new Model(left.model(), right.model(), 0),
                Command.batch(left.command(), right.command()));
    }
    public Update<Model, Msg, Never> update(Model model, Msg message) {
        return switch (message) {
            case Msg.Left left -> LEFT.update(model, left.value());
            case Msg.Right right -> RIGHT.update(model, right.value());
            case Msg.Changed changed -> Update.next(new Model(model.left(), model.right(), changed.value()));
            case Msg.Quit quit -> Update.next(model, Command.quit());
        };
    }
    public ComponentTree children(Model model) { return ComponentTree.of(LEFT.tree(model), RIGHT.tree(model)); }
    public View<Msg> view(Model model, ViewContext context) {
        return Views.keyed(List.of(
                Binding.of("a", new Msg.Left(Counter.Msg.INCREMENT)),
                Binding.of("b", new Msg.Right(Counter.Msg.INCREMENT)),
                Binding.of("ctrl+c", new Msg.Quit())),
                Views.column(LEFT.view(model, context), RIGHT.view(model, context),
                        Views.text("last output: " + model.lastOutput())), List.of());
    }
    public static void main(String[] args) { Application.run(new Counters()); }
}
```

```bash
./build.sh package
./mvnw -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/showcase-lib
javac -cp target/classes -d target/classes demo/Counters.java
java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/showcase-lib/*" demo.Counters
```

## Core concepts

`mvu.Component<M, Msg, Out>` owns pure `init`, `update`, `view`, `subscriptions`, and `children`
functions. `Update<M, Msg, Out>` contains a non-null model, inert command, and an immutable ordered
list of non-null outputs. `Never` has no values and is the only root output type accepted by
`Application.run`. Definitions hold immutable configuration; models hold application state.
`InitContext` carries startup viewport/capability facts; `ViewContext` carries the current viewport.
No component gets a terminal, executor, clock, or runtime handle. Time and I/O arrive as messages.

A `ModelLens` reads/replaces one child in an explicit aggregate. `Mount` initializes a child, replaces
its returned model while preserving siblings, maps its messages, and scopes the child's commands,
subscriptions, focus IDs and descendant paths. If the child returns its incoming model reference,
the mount keeps the parent reference too, without suppressing commands or outputs.
Use `OutputRoute.toMessage` for a parent reaction or `toOutput` to lift an event further upward.
Neither route calls a parent update from the child stack.
A message routed from an output belongs to the parent, so it is dispatched in the parent's scope: it
still arrives when the same update unmounts the child. Initialization returns `Initialization`:
construct the aggregate model before returning its lifted commands and outputs. If children emit
upward during initialization, include those outputs in the parent's `Update`, not just their
commands.

Declare every active mount with `children(model)`, including invisible/effect-only children, and
include its `subscriptions(model)` in the parent's subscription batch. A mount is not implicitly
active because it is visible. Mounts defer descendant discovery so tree validation can enforce
bounds before descending. Dynamic children use explicit stable domain keys converted to
`ComponentId`; list position and component object identity do not own lifetimes. Reordering keeps
resources; removal invalidates the path, closes sources and cancels effects. Reinserting that path
creates a fresh epoch, suppressing results already queued by the old instance.

`ComponentId` and local effect/source/element IDs reject blank, slash-containing, control, dot,
dot-dot and over-128-UTF-16-unit segments; conversion never silently normalizes keys.
`ComponentPath` stores immutable segments, not parsed namespace strings. Mounts prepend paths
structurally; siblings can reuse local `load`, `tick` and `input` IDs safely. The `scoped` value
operations support composition; application code normally needs only mounts and local factories.

The owner validates the candidate tree and subscription descriptors before installing the model,
invalidates removed scopes before starting new resources, reconciles subscriptions, and interprets
commands. Duplicate paths or subscriptions fail before new resources activate. A failure during
resource startup still fails the run and triggers cleanup; it is not an automatic fallback model.

`Command.dispatch(message)` appends to an owner-local FIFO after commit. One initialization or
external envelope plus all its local messages is a **turn**. Local messages drain before the next
external envelope; view work is coalesced until the turn ends. Dispatch never re-enters `update`.
Batches retain declaration order. **Quit is terminal:** it stops the batch and discards every
queued-but-undrained local message. A cycle fails at the configured per-turn dispatch limit rather
than starving input. Unchanged model references still reconcile resources and interpret commands.

A command is interpreted against the scope that produced it. When the very update that returned a
child's command also unmounts that child, the command is void: it is discarded, with a diagnostic,
instead of failing the run — the component no longer exists, so its effects and its own messages
must not run. A command naming a path no component ever declared is still a run failure.

Nested failures report `ComponentFailure.path()` and `phase()` (`INIT`, `UPDATE`, `VIEW`,
`SUBSCRIPTIONS`, `CHILDREN`, `OUTPUT_ROUTE`). A declaration the framework rejects — an exceeded
component count or depth, an invalid subscription declaration, or a command scoped to an undeclared
component — also states its structural reason in the message and in `ComponentFailure.detail()`,
so the reason is visible without unwrapping the cause. The original throwable remains the cause, and cleanup failures remain
separate in `ExitResult`. Fatal `Error`s (for example `StackOverflowError`) are rethrown unchanged
rather than wrapped. Diagnostic messages do not render model/message payloads; application
exceptions can still contain sensitive data, so handle their causes accordingly.

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

Focus is model data: `FocusState` holds an `ElementId`. Local IDs are automatically scoped by
mounts, so rebuilding or reordering siblings does not merge targets. A removed target receives no
input; the next view is routed against the latest committed model.

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
| `Command.cancel(id)` / `Command.cancelAll()` | Invalidate effects |
| `Command.copy(text, mapper)` | Write the clipboard and report a `ClipboardResult` |
| `Command.readClipboard(mapper)` | Read the session's in-process clipboard buffer |
| `Command.dispatch(message)` | Bounded local FIFO message after commit |
| `Command.quit()` | End the application and discard queued local messages |

Effects are epoch-guarded and component-scoped. Individual cancellation affects a local ID;
`cancelAll()` invalidates work in the current scope and descendants, never siblings. Removing a
component invalidates queued async messages even if a task ignores interruption. Task results,
timer ticks and clipboard reports arrive as ordinary messages, so update is never re-entered.
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
sources are closed exactly once, and duplicate ids are rejected. Removing or replacing an interval
cancels its queued timer even when scheduling overlaps closure; an unchanged id and period keep the
timer and refresh only its mapper. Mounts scope source IDs structurally; duplicate local IDs in
one scope remain an error, while the same ID in different children is independent. A source mapper
may return `null` to decline an emission; message mapping through mounts preserves that decision
without invoking parent message mappers.

## The editor

`Editor` is a component with nested immutable `Model`, sealed `Msg`/`Output`, and immutable
`Config`. Mount it exactly like a domain component. Configuration holds editing/rendering policy;
model data holds text, caret, selection, preferred visual column and focus.

```java
var editor = new Editor(Editor.Config.defaults().withMaxLength(4096));
var initialized = editor.init(new InitContext(null, null));
var edited = editor.update(initialized.model(), new Editor.Msg.Insert("hello"));
var submitted = editor.update(edited.model(), new Editor.Msg.Submit());
// submitted.outputs() contains Editor.Output.Submit("hello"); no I/O occurred.
```

Submission, copy and paste requests are semantic outputs consumed by the parent. To clear an
accepted draft send `Editor.Msg.Clear`; to change focus use immutable `Model.withFocus`, which
returns the same instance when focus is unchanged. An action that changes nothing returns the
incoming model instance, so the runtime's unchanged-reference repaint rule holds. Navigation and
editing remain messages, and `Editor.render` remains a pure text renderer. `Selector` and
`TableSelector` follow the same component/model/message/output/config pattern, with chosen and
cancelled outputs, the same `Model.focused` state and the same no-op reference rule. Empty
selections emit no synthetic choice. Their `Config.defaults()` provides empty initial data and
standard styles; configure options/table data explicitly for initialization.

Guarantees:

- caret and selection positions are UTF-16 offsets on grapheme boundaries;
- insertion leaves the caret after the inserted content, advancing to the end of the resulting
  grapheme when it joins the following text;
- deletion leaves the caret at the deletion point, advancing to the end of the joined grapheme
  when the surviving text on either side of the removed range merges into one cluster;
- editing never splits combining sequences, ZWJ emoji, or surrogate pairs;
- length limits truncate at a complete grapheme; unlimited is explicit;
- password/hidden editors mask output and refuse to copy by default; a vertical move in a masked
  draft returns to the column that masked row paints, one cell per cluster;
- visual scroll-to-caret is derived, so drawing never mutates state.

## Widgets, assets, and clipboard

Stateful widgets are components; stateless widgets are pure factories producing immutable
`StyledText`/`View` values. Rendering either mutates nothing. Two assembly styles exist for the multi-part widgets. `ProgressBar` is an
immutable value: every setter returns a new instance. `Table`, `Box`, `Badge`, `Quote`,
`ImageView`, and `Logo` are assembled with a small mutable builder whose setters return the same
instance, so build one inside `view` (or a factory) instead of sharing it across frames. Widget
state you own - `Selector.Model`, `Editor.Model` - is a value in your model:

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
filtering and truncation. `Editor.Model` normalizes the draft it is given - by its constructor,
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

`Application.run(component)` owns a JLine FFM `Terminal`, raw mode, alternate-screen/cursor,
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
| Active components (including root) / child depth | 4,096 / 64 |
| Local dispatches per turn | 1,024 |
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

Each nonempty frame is sent as one synchronized-output transaction (DEC private mode 2026)
under the same output lock as clipboard and terminal controls. Supporting terminals can present
that completed frame together. Terminals or multiplexers that ignore the mode still receive
replacement rows before their unused suffix is erased, avoiding a deliberate clear-before-paint
blank interval. Full-width rows are not erased after painting; cleared suffixes use default
attributes. This does not change frame cadence or add idle output, capability queries, or a public
option, and it does not promise atomic presentation on unsupported terminals. On output failure,
Braxton attempts to end synchronization and flush without accepting the failed screen. Orderly
restoration ends synchronization again before restoring modes; cleanup remains best-effort if
output is broken, the terminal's synchronization watchdog expires, or the process is forcibly killed.

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

`examples.harness.ai.Harness` is a hierarchical application built on the public API.
`Harness.Model` embeds independent `Session.Model` and `Editor.Model` values. The session owns
transcript loading/history/metadata/replay/scroll; the editor owns the composer draft and gestures.
The root coordinates cross-component workflow and consumes editor outputs through local dispatch.
Mounted child views use the same production renderer as the headless projection tests:


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
| `io.github.dhoard.braxton.app` | `Application`, runtime options and terminal presentation choices |
| `io.github.dhoard.braxton.mvu` | `Component`, `Update`, contexts, mounts/lenses, output routes, identity/tree and failure values |
| `io.github.dhoard.braxton.testing` | Pure `ComponentDriver`, inert effect inspection and headless rendering |
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

Consumers can call pure component functions directly or use `ComponentDriver.initialize(component,
context)`, then `send(message)`, `model()`, `latest()`, `subscriptions()`, `children()` and
`screen(viewContext)`. The driver does **not** interpret commands (including dispatch/quit), start
sources or emulate a runtime. Inspect returned commands/outputs and send local dispatch messages
explicitly when testing a composed update sequence. It uses production component/view/layout code
without JLine access or a test-framework dependency.

```bash
./build.sh clean verify    # install vendored JLine, compile with -Xlint:all, run the suite
./run-ai-harness.sh        # package and run the bundled example
```

The build must stay warning-free under `-Xlint:all`, the core must confine JLine terminal dependencies to runtime/input integration, and the test suite must remain independent of a TTY, stdin, and raw mode. A headless
test run does not replace a manual check of a real terminal session.

JaCoCo measures the suite during `verify` and writes its report to `target/site/jacoco/index.html`.
The coverage check keeps `AnsiEncoder` at 100% line and branch coverage and `RuntimeLoop` at or above
88% line and 74% branch coverage. The runtime lines still uncovered by unit tests are the real-terminal
FFM open path, which needs native access and an attached terminal; the PTY verifier below exercises it.

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
installed `0.2.0-jline-braxton` version of the pinned
`jline-terminal` and `jline-terminal-ffm` dependencies and their transitive support artifacts;
JLine is not shaded or optional. A launch with only the Braxton jar on its classpath is insufficient.
JLine still performs native integration and can use internal signal APIs as an upstream fallback.

### Native terminal verification

The ordinary Maven suite uses owned JLine stream terminals and needs no real terminal or native
access. Developer checks use owned PTYs and a private IPC channel to test the packaged MVU runtime:
normalized Unicode/control/paste/wheel input, resizing, pyte output, large frames, idle-reader stop,
immediate reopen, and restoration after quit, failure, JVM shutdown and SIGTERM. It also checks
synchronized-frame ordering, overwrite-first rows, suffix erasure, and wide glyphs at the right edge
across full-to-short-to-full refresh cycles. Prior native-backend
platform checks do not establish support for this refactor. Forced process kill cannot guarantee restoration.

Use Python with `pyte` and Java/javac on PATH:

```bash
./build.sh clean install
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
[the implementation record](plans/hierarchical-mvu-refactor.impl.md#9-execution-record-and-handoff).

### Source migration to 0.2

This is an intentional source/binary break, with no adapters, aliases or deprecated forwarders.
Replace the flat two-generic application contract/result with `Component<M, Msg, Out>` and
`Update<M, Msg, Out>`; root output is `Never`. Import contexts from `mvu`, and use `ViewContext`
instead of passing a viewport directly. Split feature state/messages into child components, embed
their models explicitly, and compose via lenses, mounts and typed output routes. Declare active
children and lifted subscriptions. Replace manual slash IDs and namespace cancellation with local
IDs and scope-local `cancelAll()`. Migrate stateful widgets to their nested component types and
instance methods. Recompile consumers; old binaries are unsupported. The renderer/terminal provider,
module name, coordinates, Unicode policy and offline fixture remain unchanged.

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

1. Run `./build.sh clean verify` and keep the build warning-free.
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

Braxton is **pre-1.0** (`0.2.0-SNAPSHOT`). The public API is stabilizing but may change between minor
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

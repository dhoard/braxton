# Braxton

**A Model–Update–View (MVU) terminal UI framework for Java 25+.**

[![Java](https://img.shields.io/badge/Java-25%2B-blue.svg)](#requirements)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Terminal integration](https://img.shields.io/badge/terminal-POSIX%20native-blue.svg)](#runtime-and-terminal)
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
- **Owned POSIX terminal integration** — Braxton owns MVU, rendering, and a small native terminal library.

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
- [The scroll view](#the-scroll-view)
- [The text viewport](#the-text-viewport)
- [Markdown rendering](#markdown-rendering)
- [Tabs and composed views](#tabs-and-composed-views)
- [Scrollable tables](#scrollable-tables)
- [File pickers](#file-pickers)
- [Paging a list](#paging-a-list)
- [Filterable lists](#filterable-lists)
- [Timed spinners](#timed-spinners)
- [Animated progress](#animated-progress)
- [Countdowns](#countdowns)
- [Stopwatches](#stopwatches)
- [Widgets, assets, and clipboard](#widgets-assets-and-clipboard)
- [Terminal queries and colour profiles](#terminal-queries-and-colour-profiles)
- [Declarative terminal presentation](#declarative-terminal-presentation)
- [Process handoff and suspend/resume](#process-handoff-and-suspendresume)
- [Command ordering and application control](#command-ordering-and-application-control)
- [Inline output, streaming and headless I/O](#inline-output-streaming-and-headless-io)
- [The integrated showcase](#the-integrated-showcase)
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
| Native build | C11 compiler and target libc/SDK headers |
| Terminal | ANSI/VT-capable terminal; interactive runs require `--enable-native-access=ALL-UNNAMED` |

Platform support is described in [Compatibility](#compatibility). On macOS ARM64, use an ARM64
JDK: the native library is built for the host architecture and loaded into the JVM's own, so an
x86_64 JDK running under Rosetta is rejected as an unsupported platform.

## Build from source

Braxton builds its own small POSIX terminal library from `src/main/native/terminal.c`.
A **C compiler** (`cc`, or an executable selected by `CC`) and platform development headers are
required alongside Java and Maven. On macOS ARM64 install the Xcode Command Line Tools;
on Linux install a C toolchain and libc development headers. No terminal dependency needs a
separate build or installation.

```bash
./build.sh                        # ./mvnw clean install, including the native library
./build.sh clean verify           # compile, run tests and package
./build.sh clean install -DskipTests
./build.sh clean                  # remove target/
./run-ai-harness.sh               # package and launch the AI Harness example
```

Maven compiles native code during `generate-resources`, so `./mvnw clean verify` also works
directly. The jar contains the binary for the build host under
`META-INF/braxton/native/<platform>/`, alongside its Braxton version metadata. Build separately on Linux and macOS ARM64; a jar from
one platform is not a universal binary. Missing platform resources fail explicitly. The C
source and its [provenance](src/main/native/README.md) are maintained in this repository.

`./run-ai-harness.sh` uses the packaged `target/braxton-*.jar`; pass `--skip-build` to reuse it.
The optional example's Gson dependencies are copied to `target/showcase-lib` and are never
bundled in the Braxton jar.

**Maven coordinates:** `io.github.dhoard:braxton` (automatic module name
`io.github.dhoard.braxton`). Braxton is pre-1.0 and is not yet published to a public repository;
build it from source or install it locally with `./build.sh`.


Consumers depend on `io.github.dhoard:braxton:0.1.0` and grant native access at launch; no
additional terminal artifact is required. When shading Braxton into an application jar, retain
`META-INF/braxton/native/` and the required license/provenance resources. The native resource
must match the application's target OS and architecture. Headless component tests use
`ComponentDriver` or `ScreenRenderer` without native access.

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
| `View.CursorAt(cursor, child)` | Cursor intent: where the caret is and how it looks, resolved by layout |
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

Normalized input is one of `KeyInput`, `PasteInput`, `PointerInput`, `ResizeInput`, or one of the
terminal's own notifications. Printable keys carry their text exactly once, and bracketed paste
arrives as one atomic event that never triggers Enter or a shortcut.

### Enhanced keyboard input and terminal focus

Legacy terminal input cannot express three things: Enter without a modifier and Shift+Enter are the
same byte, so are Tab and Ctrl+I; a key release does not exist; and a window losing focus is
invisible. An application can ask for all three, and Braxton requests them without ever assuming the
terminal agrees:

```java
KeyboardEnhancements flags = KeyboardEnhancements.NONE
        .withDisambiguate(true)      // Shift+Enter is not Enter, Ctrl+I is not Tab
        .withReportEventTypes(true)  // press, repeat and release are reported separately
        .withReportAllKeys(true)     // every key, not only the ones without text
        .withReportAssociatedText(true);

TerminalOptions terminal = TerminalOptions.DEFAULT
        .withKeyboardEnhancements(flags)
        .withFocusReporting(true);
Application.run(program, RuntimeOptions.defaults().withTerminal(terminal));
```

`TerminalOptions` defaults to none of this, so an application that does not ask keeps exactly the
legacy keyboard behaviour. Both requests are made once when the session opens and restored on every
exit, including a shutdown hook: the flags are *pushed* with `CSI > flags u` and popped with
`CSI < u`, which restores whatever the user's shell had rather than clearing it.

Whether the request took effect is the terminal's answer, not Braxton's assumption. The session also
sends `CSI ? u` once, and the terminal's `CSI ? flags u` reply arrives as
`InputEvent.KeyboardEnhancementsInput`. A terminal that does not implement the protocol ignores all
three sequences and keeps sending legacy input; a terminal that implements only some flags reports
only those. Nothing is inferred from `TERM`.

A reported key event carries its `KeyEventType`. `KeyInput.activates()` is true for a press and for a
repeat, and false for a release, and routing uses only that: a release never runs a capture, local or
bubble binding, never reaches a focused element's input handler, and never inserts text, even though
it reports the same key, modifiers and associated text as the press it ends. Releases and the
terminal's own notifications still reach `Views.keyed` interceptors, which are the full-event hook for
an application that wants to observe them. `Editor.actionFor` and the widgets keep mapping gestures
exactly as before, so nothing changes for an application that never requests enhancements.

Terminal focus is its own event. `InputEvent.FocusInput` reports that the terminal window gained or
lost focus; it is not `FocusState`, it is not a keystroke, and no framework path can fold it into a
model's focus. Losing the terminal window therefore leaves `Editor.Model.focused` and every other
focus value exactly as the application set it. The terminal reports changes only, never a starting
state, so the first event tells you about the first change.

The decoder understands the protocol's encodings rather than a fixed table of sequences: the
`CSI unicode-key-code : shifted : base ; modifiers : event-type ; text u` form, the same modifiers
and event type on the legacy functional-key forms such as `CSI 1 ; 5 A` and `CSI 3 ; 5 : 3 ~`, and
the protocol's private-use numbers for keys without a Unicode character. Every field is validated
and the sequence length is already bounded, so a malformed, truncated or absurd report decodes to no
event instead of an invented keystroke.

### Native and virtual cursors

A terminal cursor is terminal state and a view is a value, so Braxton keeps them apart: `Cursor`
describes what a caret should be, `View.CursorAt` places that description inside the tree, and layout
turns it into one screen cell. Nothing in the description touches a terminal, so a caret can be
built, compared and asserted headlessly.

```java
ElementId field = ElementId.of("input");
View<Msg> input = Views.focus(field, true, Views.cursor(
        Cursor.at(row, column)
                .withShape(Cursor.Shape.BAR)
                .withMode(Cursor.Mode.BLINK)
                .withOwner(field),
        content));

// The cell the caret resolves to, in screen coordinates.
Optional<Cursor> caret = new ScreenRenderer().cursor(input, new Viewport(24, 80));
```

Row and column are local to the wrapper that carries them, so nesting it under padding, a frame or
inside a split pane is what moves the caret on screen. Layout resolves one cell per frame: the
topmost cursor in paint order wins; a cursor that names an `owner` is eligible only while that
element is focused, which is what keeps a blurred field from showing a second caret; a modal layer is
a screen of its own, so the layers it covers place nothing; a caret whose cell is clipped away or off
the screen is hidden rather than pinned to the edge; and a caret on the second half of a two-column
glyph moves to the glyph it belongs to, because that is the only cell a terminal cursor can address.

`Cursor.Strategy` decides how the caret is presented. `NATIVE` moves and shapes the terminal's own
cursor: the shape and its blink are one `DECSCUSR` control (`CSI Ps SP q`), the colour is `OSC 12`,
and the position is an absolute move. The session writes each of those only when it changes, after
the frame's content and inside the same synchronized update, so a frame that changed nothing writes
nothing and an idle application still emits no traffic. Every exit path - quit, end of input, a
failure, the shutdown hook - puts back what the session changed (`CSI 0 SP q` for the shape,
`OSC 112` for the colour) before the cursor is shown again for the shell.

`VIRTUAL` paints the caret instead: layout reverses the caret's cell, the character under it stays
readable, and the terminal's cursor stays hidden. Nothing blinks a painted cell, so the application
drives that blink from its own clock with `Cursor.atTime(millis)`, a pure function of model time - a
`BLINK` cursor is steady for the first half of a 500 ms period and hidden for the second. A scene that
asks for no painted caret never needs a timer at all.

### Key maps and help

A `KeyAction` names one action: its id, its ordered key aliases, the message it produces, a display
label, a description, and whether it is enabled and shown in compact help. A `KeyMap` groups actions
for display and answers three questions from that one definition:

```java
KeyMap<Msg> keys = KeyMap.of(
        KeyMap.group("Navigation",
                KeyAction.of("up", new Msg.Up(), "↑/k", "move up", "up", "k"),
                KeyAction.of("down", new Msg.Down(), "↓/j", "move down", "down", "j")),
        KeyMap.group("General",
                KeyAction.of("help", new Msg.ToggleHelp(), "?", "toggle help", "?"),
                KeyAction.of("quit", new Msg.Quit(), "q", "quit", "q", "ctrl+c")));

// Routing: one binding per enabled alias, in declaration order.
View<Msg> view = Views.keyed(keys.bindings(), content, List.of());
// Matching inside a widget's input handler.
Optional<Msg> pressed = keys.actionFor(key).map(KeyAction::message);
// Help, from the same actions.
StyledText help = Help.render(keys, /* expanded= */ false, context.viewport().cols());
```

Aliases match a gesture exactly — the rule `Binding` applies — so a map can bind `t` and `alt+t` to
different actions without one shadowing the other. Two enabled actions may not claim the same
gesture; the duplicate is reported when the map is built rather than resolved by declaration order.
A disabled action matches nothing, compiles to no binding, and is absent from help, and it keeps its
definition so it can be enabled again. `KeyMap.with`, `withAliases` and `withEnabled` return a new
map, so a remap is model data and nothing is mutated in place.

`Help` is a pure factory (`StyledText` or `View`) whose expansion state belongs to the parent:
compact help is one line of `label description` pairs, expanded help is one line per group, and
both are truncated by display columns without splitting a grapheme, ending a cut line with `…`.
A width of zero paints nothing. `Selector`, `TableSelector` and `Editor` expose
`defaultKeys()`/`defaultKeys(Config)` and their `keys()`, so an application can render a widget's
own help or replace the map through the `(Config, KeyMap)` constructor.

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

### Mouse and pointer input

Pointer input is opt-in, and it arrives as a value rather than as a keystroke: a report names what
happened, which button, which modifiers, and which cell. Reporting is asked for in the session
options, because a terminal only sends what it was asked for:

```java
RuntimeOptions options = RuntimeOptions.defaults()
        .withTerminal(TerminalOptions.DEFAULT.withMouseMode(MouseMode.CELL_MOTION));
```

| Mode | What the terminal reports |
| --- | --- |
| `MouseMode.NONE` | nothing; the default |
| `MouseMode.CLICK` | presses, releases and wheel gestures |
| `MouseMode.CELL_MOTION` | the same, plus motion while a button is held - what a drag needs |
| `MouseMode.ALL_MOTION` | the same, plus every motion, with or without a button - what hover needs |

The request is always the SGR form (`CSI ? 1006`), the only encoding that can name a released
button and address a column past 223, together with the tracking level the mode names (`1000`,
`1002`, `1003`). Both are undone in the reverse order when the session ends. A terminal that
implements neither ignores the sequences and keeps sending what it sent before.

`PointerEvent` is one report: an action (`PRESS`, `RELEASE`, `MOTION`, `WHEEL`), a button (the three
pressable ones, the four wheel directions, or `NONE`), the modifiers, the **zero-based** cell, and a
click count. The decoder converts the terminal's one-based origin once, so nothing above it has to
know the protocol counted the first column as one. Malformed coordinates, an unmodelled extra button
and a release that names a wheel decode to no event rather than to an invented one.

**Hit testing.** A report is delivered to the element painted under it. That decision is made
against the committed frame - the focus regions the last layout declared - taken in reverse paint
order, so the element painted last wins. The report is then translated into that element's own
coordinates, which is why no widget ever has to know where it was painted:

```java
View<Msg> view = Views.focus(
        ElementId.of("field"),
        focused,
        (report, region) -> hitTest(report, region).map(Msg::Clicked),
        content);
```

A `PointerHandler` is handed the local report and the size of the rectangle it arrived in. It must
take its geometry from that size rather than from the size its subtree was built with: routing
builds a size-aware subtree at zero columns to find the handlers inside it.

**Capture.** A press captures the element it landed on, so every report until the release goes to
that element even when the pointer leaves its rectangle - which is what makes a drag work. The
capture ends at the release, when the element is unmounted, and when a modal layer starts covering
its region: a gesture never crosses into or out of a modal layer.

**Double click.** A press that repeats the previous one at the same cell within 400 ms is reported as
the second click of one gesture (`clickCount() == 2`), so a widget can select a word or open an entry
without measuring time itself. The window is measured on the runtime's clock; an unstamped report is
a single click.

**Modal isolation.** A modal layer is a route of its own: the regions of the layers it covers are
reported as unreachable, so a click outside the dialog reaches neither the dialog nor the pane it is
painted over.

**Observation.** The interceptors on the way to the element see the report first and may report a
message of their own, but they do not stop it: where the pointer landed is decided by geometry, so a
report always reaches the element under it. That is what lets a scene log pointer input without
re-implementing hit testing. A report therefore produces the observers' messages in paint order,
then the element's own.

**The wheel.** A wheel gesture that no element consumes becomes the wheel key it used to be
(`wheelup` / `wheeldown`), so an application that only binds wheel keys keeps scrolling. It is
delivered through exactly one of the two paths, never both. The horizontal wheel has no legacy key
and is only ever a pointer report.

**What the widgets do with a pointer.** `Tabs` activates the tab whose cell was clicked. `RichList`,
`DataTable`, `FilePicker` and `TextViewport` select or scroll what is under the pointer - a list or
table row on a click and on a double click respectively, a file-picker entry on a click and opening
it on a double click - and their wheel steps the selection. `Editor` and `TextInput` place the caret
on a click, extend the selection on a drag and select a word on a double click, always on a cluster
boundary so a click can never split a grapheme. None of them changes its own focus: a press reports
what the user asked for, and the model decides where the keyboard goes.

## Commands and subscriptions

`Command` values are inert descriptions:

| Command | Effect |
| --- | --- |
| `Command.none()` / `Command.batch(...)` | Nothing / ordered batch |
| `Command.sequence(...)` | Ordered sequence: each step submitted when the previous one finishes |
| `Command.afterUpdate(message)` | Owner-local message whose sequence step finishes when its transition commits |
| `Command.delay(id, duration, message, rejected)` | Deliver a message later |
| `Command.monotonicTime(mapper)` | Read the runtime's monotonic clock once and deliver the reading |
| `Command.task(id, work, onSuccess, onFailure)` | Run application work on the effect executor |
| `Command.cancel(id)` / `Command.cancelAll()` | Invalidate effects |
| `Command.copy(text, mapper)` | Write the system clipboard and report a `ClipboardResult` |
| `Command.copy(selection, text, mapper)` | Write the system or primary selection and report a `ClipboardResult` |
| `Command.readClipboard(mapper)` | Read the session's in-process clipboard buffer |
| `Command.readTerminalClipboard(id, selection, mapper)` | Ask the terminal what a selection holds and report a `ClipboardReadResult` |
| `Command.exec(request, mapper)` | Hand the terminal to a child process and report a `ProcessResult` |
| `Command.suspend(mapper)` | Suspend the session until the job controller continues it |
| `Command.dispatch(message)` | Bounded local FIFO message after commit |
| `Command.quit()` | End the application and discard queued local messages |

Effects are identity-guarded and component-scoped: registering an id again replaces its
registration, and removing a component invalidates its scope's epoch, so a late completion cannot
reach the model. Individual cancellation affects a local ID;
`cancelAll()` invalidates work in the current scope and descendants, never siblings. Removing a
component invalidates queued async messages even if a task ignores interruption. Task results,
timer ticks and clipboard reports arrive as ordinary messages, so update is never re-entered.
`Command.task` bodies are the documented impure boundary — they must not touch the terminal,
mutate captured state, or call `update`. `Command.monotonicTime` is the supported way for a
component to learn what time it is: it has no identity and is not counted against the effect quota,
its mapper runs on the owner thread, and its reading comes from the same clock the runtime's
intervals report, so a timestamp fact and a tick are on one timeline.

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

The gestures the editor handles are a `KeyMap`: `Editor.defaultKeys(config)` derives it from the
policy (the copy and paste gestures come from `Config.copyKey()`/`pasteKey()`), `editor.keys()`
exposes it for `Help`, and `new Editor(config, keys)` replaces it. A configured action is matched
before the text a gesture carries, so an action remapped onto a printable key fires instead of
inserting. Submit, copy, paste, new line, select all and the navigation keys keep their documented
gestures, and the default aliases also accept the `alt+enter`/`alt+backspace` forms a terminal sends
for Meta+Enter/Meta+Backspace.

The caret is a `CursorPolicy`. `Config.defaults()` keeps `Caret.PAINTED`, the block glyph drawn inside
the editor's own text, so nothing an existing application sees changes. `Caret.NATIVE` and
`Caret.VIRTUAL` declare a cursor at the model's caret instead: the same line of the window and the
same painted column the block would cover, prompt width and tabs included, so the character under the
caret stays readable and `shape`/`mode` choose how it looks. The caret is declared only while
`Model.focused` is true, and a declared cursor is owned by the editor's focus identity, so a field
that loses focus shows no caret at all.

```java
Editor editor = new Editor(Editor.Config.defaults()
        .withCursor(Editor.CursorPolicy.nativeCursor(Cursor.Shape.BAR, Cursor.Mode.BLINK)));
```

### Multiline editing: rows, wrapping and the textarea frame

`Editor.textarea(config)` is the explicit multiline preset. The default map keeps Enter as submit and
Ctrl+J as the line break, so no existing caller becomes a textarea by accident; the preset makes Enter
break a line and submits with `ctrl+enter` or `alt+enter` instead. Both maps carry the same actions -
`Editor.defaultKeys(config)` and `Editor.textareaKeys(config)` differ only in which gesture they claim.

```java
// A field that accepts several lines and submits with ctrl+enter.
var editor = Editor.textarea(Editor.Config.defaults().withWrapWidth(60));
```

Word and document motion are part of that map: `ctrl+left`/`alt+b` and `ctrl+right`/`alt+f` move by
word, `ctrl+backspace` and `ctrl+delete` delete one, `ctrl+home`/`ctrl+end` jump to the draft's edges,
and the `shift+` forms of every navigation gesture extend the selection (`Msg.Extend`). A word is a run
of letters, digits and underscores, a run of blanks, or a run of punctuation, and every step is a
grapheme boundary.

`Config.withWrapWidth(cols)` declares the width the editor lays its rows out for. The draft is then
projected into visual rows: a row that does not fit wraps, a tab reaches the next four-column stop from
the row's own first column, a two-column glyph moves to the next row instead of being split, and the
blank a soft break leaves stays with the row it broke from. Wrapping is derived and never inserted into
the text, so offsets, selection and submission are unaffected. `Msg.MoveUp`/`Msg.MoveDown` cross a soft
break before they leave the line and keep the painted column, which is what makes a run of vertical
moves return the caret to where it started.

The space a view is actually given still wins when it is narrower than the declared width, so a split
pane or a resized terminal wraps its rows and places its caret in the same projection. Derive the
declared width from the viewport the way the demo does, and the two agree exactly:

```java
var config = Editor.Config.defaults()
        .withWrapWidth(Math.max(1, viewport.cols() / 2 - 2))
        .withPresentation(Editor.Presentation.plain()
                .withPlaceholder("Type something", Style.NONE)
                .withLineNumbers(true, Style.NONE)
                .withCursorLine(Style.bg(new Color(0x2A, 0x2A, 0x3A)))
                .withDynamicHeight(true, 2, 6)
                .withMaxContentLines(40));
```

`Config.presentation()` is the frame around the draft, and none of it is content:

- a placeholder is painted where an empty draft would be, and is never submitted or copied;
- a line-number gutter numbers logical lines once, and the rows a line was wrapped into continue under
  a blank gutter;
- a cursor-line style fills the row the caret is on, while the editor holds focus;
- a dynamic height follows the wrapped content between `minLines` and `maxLines` and stops counting at
  `maxContentLines`, so a longer draft scrolls instead of growing; the rows below `minLines` are painted
  empty, so an empty textarea is the height of the smallest one.

`Editor.visibleLineCount(model, config, cols)` reports the rows that frame paints, and the editor
declares itself as a `View.Sized` subtree - `Views.sized(size -> view)` - so the rows it wraps and the
caret it places come from the geometry its ancestors actually allocate.

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
- a wrapped row, the caret's row and a dynamic height come from one projection, so a resize cannot
  paint a row the caret was not placed on;
- visual scroll-to-caret is derived, so drawing never mutates state.

## The text input

`TextInput` is a single-line field: one row of editable text, validated, optionally completed, and
scrolled horizontally when the draft is wider than the field. It is a component with nested
immutable `Model`, sealed `Msg`/`Output`, and immutable `Config`, the same shape as `Editor`, and it
mounts the same way.

It is not a second editor. The draft, caret, selection, word motion, grapheme-safe length cap, mask
and paste policy are the editor's own, reached through the `Editor` a field owns; the field adds
what makes a draft one row - a line break becomes a space, and a wide draft scrolls instead of
wrapping - plus validation and completion.

```java
var email = new TextInput(TextInput.Config.defaults()
        .withWidth(24)
        .withMaxLength(64)
        .withPlaceholder("Email", Style.NONE)
        .withValidator(text -> text.contains("@")
                ? TextInput.Validation.ok()
                : TextInput.Validation.invalid("needs an @")));

var initialized = email.init(new InitContext(null, null));
var typed = email.update(initialized.model(), new TextInput.Msg.Insert("ada@example.com"));
// typed.outputs() carries the validation change; nothing was written anywhere.
```

**One row.** `Msg.Insert` and `Msg.Paste` flatten every CR/LF, CR and LF to one space before the
draft is edited, and a model given a draft flattens it too, so no message and no restored value can
make the field two rows tall. A draft wider than the field is not wrapped: the row is scrolled the
least it can be while the caret's own cells stay inside it, never starting in the middle of a wide
glyph. The width is the configured `Appearance.width()`, and the space a view is actually given wins
when it is narrower - the same rule the editor applies to its rows - so a field in a resized pane
scrolls instead of wrapping. A field configured with no width paints its whole draft.
`TextInput.scrollOffset(model, config, cols)` reports the columns scrolled off, and
`TextInput.render(model, config, focused, cols)` is the pure row a target or a test can assert.

**Validation.** `Config.validator()` is a pure `Validator` that answers `Validation.Valid` or
`Validation.Invalid` with a reason. It runs after every edit, when a completion is accepted, after a
clear and on submission; the model carries its answer, so a view or a status row paints it without
calling the policy again. Invalid text stays editable - the policy never locks the draft - and what
submission does with it is `SubmitPolicy`:

- `VALID_ONLY` (the default) refuses: the attempt is reported as `Output.Rejected(validation)` and
  nothing is submitted;
- `ALLOW_INVALID` submits the text anyway.

A changed answer is published as `Output.ValidationChanged`, which an application uses for a live
message under the field. An exception a validator throws is not swallowed, and neither is a
validator that answers nothing: both are configuration failures, reported where they happen.

**Completion.** Completion is off unless `Config.completion()` asks for it, because a ghost suffix
the application never supplied would be invented data. When it is on, the field publishes
`Output.SuggestionsRequested(revision, text)` after every edit, and the application answers with
`Msg.Candidates(revision, candidates)`:

```java
// What the field published, and the answer the application hands back to its own mount.
if (output instanceof TextInput.Output.SuggestionsRequested request) {
    field = fieldMount.update(model, new TextInput.Msg.Candidates(
            request.revision(), repositoryNames(request.text())));
}
```

The revision is what keeps a slow provider honest: an answer that names another revision than the
model's is ignored, so a list computed for a draft the user has already changed cannot replace the
candidates of the text they are looking at. Candidates keep the order they were supplied in, are
de-duplicated, and match by a case-sensitive prefix unless `Completion.withIgnoreCase(true)` says
otherwise; an empty draft matches nothing, so an empty field shows its placeholder rather than a
candidate. `tab` accepts the selected candidate whole - never a fragment of the draft it replaces -
and `ctrl+n`/`ctrl+p` cycle forward and backward through the matches. The suffix the selected
candidate would add is painted as a ghost after the caret, and `TextInput.selectedCandidate` and
`TextInput.suggestedSuffix` are the same answer a test can assert.

A completion gesture with nothing to do is not consumed: `tab` with no candidate, or with a
candidate the draft already spells, falls through to whatever the form around the field binds it to.
That is what lets one key both complete a field and move focus to the next one. `Msg.Copy`,
`Msg.PasteRequest` and a masked draft's refusal to copy follow the editor: a password never leaves
its field.

**Keys and help.** `TextInput.defaultKeys(config)` derives the gestures from the policy - the copy
and paste gestures come from `Config.copyKey()`/`pasteKey()` - and `field.keys()` exposes the map for
`Help`. Enter submits, because a single-line field has no line to break. `new TextInput(config, keys)`
replaces the whole map.

Guarantees:

- the draft never contains a line break, and every line-ending form becomes one space exactly once;
- the row is one row: a wide draft scrolls, and the window reserves the caret's own cells;
- the caret and selection are UTF-16 offsets on grapheme boundaries, and a length cap truncates at a
  complete grapheme, exactly as the editor's does;
- a masked or hidden draft paints one mask glyph per cluster (or nothing), never its cleartext, and
  never shows a completion;
- validation is model data, produced by one pure policy and republished only when it changes;
- candidates are application data with a revision, and a stale answer is ignored;
- a message that changes nothing returns the incoming model instance.

## The scroll view

`ScrollView` is a reusable vertical pane over text the application owns - a transcript, a trace or
log pane, a long read-only list. It is a component with nested immutable `Model`, sealed `Msg`,
immutable `Config` and a `Never` output type, because every interaction it offers is a state change
the parent can already observe. The model holds only the offset, the follow-end flag, the visible
height and focus; the content is the application's snapshot, held by reference.

```java
// Definition: a 12-row pane that follows appended content.
var log = new ScrollView(new ScrollView.Config(12, true));

Mount<Model, Msg, Never, ScrollView.Model, ScrollView.Msg, Never> logPane =
        Mount.of(ComponentId.of("log"), log,
                 ModelLens.of(Model::log, (model, child) -> model.withLog(child)),
                 Msg.Log::new,
                 OutputRoute.toOutput(value -> value));

// An update that changes the content publishes one snapshot through the mount.
return logPane.update(model, new ScrollView.Msg.ContentChanged(rows));

// The view reserves exactly the height the pane reports, then nests it like any other view.
View<Msg> pane = logPane.view(model, context);
View<Msg> body = Views.column(
        Views.fixed(model.log().visibleRows(), pane),
        Views.fixed(1, composer.view(model, context)));
```

`visibleLines(model)` returns exactly the rows the pane shows, which is how an application copies
or inspects the visible window; it reads the same rows a frame does.

**Content contract.** A `Content` is an immutable, line-indexed snapshot: `lineCount()` and
`line(index)`, both constant time. `Content.of(List<StyledText>)` copies the list of references
once, so a caller's list cannot change under a model; an application with its own immutable,
indexed structure implements `Content` directly and publishes without copying. A *line* is one
display row that the application has already wrapped to the pane's width. The pane slices and pages
by those rows exactly, keeps `StyledText` spans, links and empty rows, and never reads a row it is
not painting: one frame is one `lineCount()` call and one `line(index)` call per visible row, so a
frame over 100 000 rows costs what a frame over 20 rows costs. `update` reads no row at all.

A row wider than the pane is clipped at the pane's columns instead of wrapping onto a second row,
because a wrap would change how many rows the window covers and push the last rows out of the pane.
A break that a row still contains is clipped the same way. Wrapping and line-ending normalization
are therefore the application's preparation step: `LineBreaks.normalize` and `LineBreaks.singleRow`
are the existing helpers, and `StyledText` has already removed an unrepresentable bare CR before a
row reaches the pane. Text that ends in a newline contributes a final empty row, the same logical
line the editor counts - `Editor.lines(Editor.Model.of("a\n"))` is `["a", ""]` - and the pane keeps
that empty row rather than dropping it. Snapshots compare by reference, one instance being one
content version, so model comparison stays constant time and a republished snapshot repaints even
when its lines are equal.

**Geometry.** The visible height is explicit, in `Model.visibleRows()`: it is the height the parent
reserved and the height paging uses. It is deliberately *not* `ViewContext.viewport()`, because a
mounted component receives its parent's context and the root height is not this pane's height when
the pane sits inside a `BorderBox`, `Padding`, `Row` or a shared column. The parent keeps one number
and uses it twice - `Views.fixed(model.log().visibleRows(), pane)` for the reservation, and
`ScrollView.Msg.ViewportChanged(rows)` when the allocation changes. While following, a resize keeps
the last rows visible; a pane the user scrolled back keeps its first visible row and is clamped only
when the offset no longer fits.

```java
// Resize: rebuild the allocation, then tell each pane its new height. The runtime delivers
// resize as a subscription message; the parent's own update forwards it.
case Msg.Resized resized -> {
    Model next = model.withViewport(resized.size());
    int rows = allocation(next).transcript();
    yield logPane.update(next, new ScrollView.Msg.ViewportChanged(rows));
}
```

**Navigation.** Up/Down move by visual rows, PageUp/PageDown by the pane's visible height (never by
the terminal's height), Home to the first row, End to the last window. Non-positive counts and
moves the content bounds already forbid are no-ops that return the incoming model instance.
Follow-end means appended content stays visible while the window is at the end; a deliberate upward
move that actually changes the offset releases it, `End` arms it again when `Config.followEnd`
allows it, and reaching the bottom with `Down` does not - so a pane the user stepped down by hand
never starts following silently. Content replacement, truncation and a zero-sized pane clamp the
offset instead of throwing.

**Focus and input.** Focus is model data, as in `Editor` and `Selector`: the application sets it
with `Model.withFocus` and a reduction never changes it. The pane declares one focus element, so an
unfocused pane receives no keys, and it consumes only its six navigation gestures - `up`, `down`,
`pageup`, `pagedown`, `home`, `end` - so it never takes printable text and leaves Ctrl-/Alt-prefixed
gestures to the application (capture bindings see every event first, whichever pane is focused). It
paints no focus decoration; a border or title that shows which pane is active is the application's
choice. Mouse wheels are not mapped, because the step is application policy:

```java
// Two independent panes and a composer: the wheel and Tab stay parent bindings.
View<Msg> root = Views.keyed(
        List.of(Binding.of("tab", new Msg.FocusNext()),
                Binding.of("wheelup", new Msg.Log(new ScrollView.Msg.Up(3))),
                Binding.of("wheeldown", new Msg.Log(new ScrollView.Msg.Down(3)))),
        body, List.of());
```

Out of scope by design: horizontal scrolling, scrollbars, selection and copy highlighting, mouse
hit-testing, reflow on resize, and nested scroll orchestration. `border`, `padding` and `grow` still
work around a pane; they only change which rectangle the parent allocates.

## The text viewport

`TextViewport` is the pager of the family: a pane over the application's *logical* lines that wraps
them itself, pans them horizontally, paints a gutter and highlights the ranges the application
found. It is a component with nested immutable `Model`, sealed `Msg`, immutable `Config` and a
`Never` output type, and it is the richer counterpart of `ScrollView` - which stays the small pane
over rows the application has already prepared, unchanged.

```java
// Definition: a wrapping pane with line numbers that reserves the rows the scene paints around it.
TextViewport.Config config = TextViewport.Config.defaults()
        .withMode(TextViewport.Mode.WRAP)
        .withGutter(TextViewport.Gutter.lineNumbers(6))
        .withReservedRows(4)
        .withReservedColumns(0);

Mount<Model, Msg, Never, TextViewport.Model, TextViewport.Msg, Never> pager =
        Mount.of(ComponentId.of("pager"), new TextViewport(config),
                 ModelLens.of(Model::pager, (model, child) -> model.withPager(child)),
                 Msg.Pager::new,
                 OutputRoute.toOutput(value -> value));

// Publish the document, then the ranges the search found, then the first one as active.
Update<Model, Msg, Never> content =
        pager.update(model, new TextViewport.Msg.ContentChanged(TextViewport.Content.of(lines)));
Update<Model, Msg, Never> highlighted =
        pager.update(content.model(), new TextViewport.Msg.Highlights(matches));
Update<Model, Msg, Never> published =
        pager.update(highlighted.model(), new TextViewport.Msg.NextMatch());
```

**Logical lines, one cell mapping.** Content is a `Content` snapshot of logical lines: one
`StyledText` per line, holding no line break (`Content.of` splits any value that still holds one).
The viewport never copies or concatenates a snapshot; a frame reads the lines it paints and no
others, so a frame over a million-line document costs what a frame over a twenty-line document
costs. `Mode.WRAP` breaks a line into as many rows as it needs at the width it is given, and
`Mode.PAN` keeps one row a line and shows the window the horizontal offset names. Both charge a
tab, a wide glyph and a combining mark the same way - the projection the editor uses - so the row a
match lands on is the row it is painted on. A gutter is a declared number of cells taken off the
left edge, and its text is fitted to that width, so the content column starts at one cell on every
row whatever a gutter renders.

**Anchor, offsets, follow-end.** The window is named by a *logical* anchor - a line and the row
within it - plus the first cell the content column shows. That is what survives a reflow: after a
resize, a rotation or a changed gutter the anchor still names the same line, so a reader keeps
their place instead of being dropped wherever a screen-row count lands. While follow-end is set the
window covers the end of the content; an append or a resize re-anchors it there, a move that really
changes the window releases it, `Msg.Bottom` arms it again when `Config.followEnd()` allows it, and
reaching the bottom by hand does not.

**Geometry is the parent's.** `Config.rows()` and `Config.columns()` are only the fallback a
headless test or a preview uses; startup geometry is the terminal's own size less
`Config.reservedRows()` and `Config.reservedColumns()`, and every later allocation arrives as
`Msg.Resized(rows, columns)`. The pane owns no resize source, because a composed pager splits one
terminal between several panes and only the parent knows how: the scene keeps one number, reserves
each pane's share with `Views.fixed(rows, pane)`, and tells each pane what it allocated.

```java
// One resize source for the scene, one allocation per pane.
public Subscription<Msg> subscriptions(Model model) {
    return Subscription.resize(SIZE, size -> new Msg.Resized(size.rows(), size.cols()));
}

case Msg.Resized resized -> {
    Model next = panes(model, resized.rows(), resized.cols());
    yield Update.next(next);
}
```

**Ranges and matches.** The application finds matches and publishes them as `Highlight` ranges, one
per line, in UTF-16 offsets widened to grapheme clusters; the viewport paints them with
`Config.Styles.highlight()` and the active one with `selectedHighlight()`. Publishing ranges that
name a line the content does not hold, run past its text or split a grapheme is rejected where the
mistake is made. `Msg.NextMatch` and `Msg.PreviousMatch` walk the ranges in publication order,
wrapping at both ends, and bring the active one into view vertically - and, when panning,
horizontally. New content invalidates the ranges that described the old version, so publishing
content clears them and the application publishes them again for the new one.

**Navigation and input.** Up/Down move by visual rows, PageUp/PageDown by the pane's height,
half-page gestures by half of it, Home/End to the first and last window; Left/Right pan by cells
and are disabled while wrapping. The percentages `verticalPercent` and `horizontalPercent` are
defined as 0 when there is nothing to scroll, so a document that fits is not "at the end" of
anything. `defaultKeys()` is the definition the component routes and the one `Help` renders, and
`keys(model, config)` is that map for one state - it drops the gestures the state cannot honor.
Focus is model data, as everywhere else: the application sets it with `Model.withFocus` and a
reduction never changes it.

Out of scope by design: selection and copy highlighting, mouse hit-testing, scrollbars, editing,
and word wrapping - a wrapped row is broken at the cell that fits, exactly as the layout engine
breaks text, so an application that wants prose wrapped at word boundaries prepares its rows (or
uses `ScrollView` with its own wrapping). A `TextViewport` computes no matches of its own: what a
match is belongs to the application's search.

## Markdown rendering

`Markdown` renders a markdown document into the styled logical lines a pane paints, so a markdown
page is one `TextViewport` mount over the projection rather than a second scrolling implementation.
It is a pure renderer plus a small bounded projection cache: `document(source)` answers an immutable
`Markdown.Document` whose `lines()` are single-line `StyledText` values and whose `content()` is the
snapshot the pager takes. There is no component and no message - the application owns the source,
decides when it changed, and publishes the projection.

```java
Markdown markdown = new Markdown(Markdown.Config.defaults().withWidth(columns));
Markdown.Document document = markdown.document(source);

Mount<Model, Msg, Never, TextViewport.Model, TextViewport.Msg, Never> pager = Mount.of(
        ComponentId.of("pager"), new TextViewport(pagerConfig),
        ModelLens.of(Model::pager, (model, child) -> model.withPager(child)),
        Msg.Pager::new, OutputRoute.toOutput(value -> value));

Update<Model, Msg, Never> published =
        pager.update(model, new TextViewport.Msg.ContentChanged(document.content()));
Model highlighted = pager.update(published.model(),
        new TextViewport.Msg.Highlights(matches(document.lines(), query))).model();
```

**Dialect.** CommonMark plus the GFM extensions: tables with their declared alignments,
strikethrough, task list items, and autolinks - so a bare `https://example.com` is a link and so is
`<https://example.com>`. Six heading levels, nested and tight or loose lists, block quotes (nested),
fenced and indented code, thematic breaks, hard and soft breaks, escapes and images all render. An
image paints its alt text and nothing else, because a terminal renderer does not fetch a picture.
Raw HTML is *literal text*: every `<div>`, `<script>` and `<style>` in a document is painted as the
characters that were written, never executed, which is the promise `Markup` keeps for its own
grammar.

**Links.** A link paints its text with the theme's link style and carries an OSC 8 target only when
its destination names one of `Config.linkSchemes()` - `http`, `https` and `mailto` by default. A
relative destination, an unknown scheme such as `javascript:` or `file:`, and a destination holding a
control character all paint as styled text with no target, so the renderer cannot hand a terminal a
scheme the application did not allow. Nothing is fetched, resolved or opened.

**Themes and code.** `Theme.light()` and `Theme.dark()` are the two documented palettes, and
`Config.defaults()` and `Config.dark()` pair each with a matching highlighter. Highlighting is a
`Highlighter`: a pure function from a fence's language and text to styled spans, so an application
can plug in its own. The bundled tokenizers cover Java, JSON and a POSIX shell; any other language,
and a fence that declares none, paints its text unchanged, and the theme's code-block style is
merged over the token colours either way.

**Width, indentation and caching.** `Config.width()` is the cells one projected row may occupy:
prose is word-wrapped there, code is broken at the cell that fits, and a table is fitted and then
truncated rather than wrapped, because a wrapped cell would break the grid. `Config.indent()` is the
cells one nesting level of a list or a quote adds. Width is projection data, so a resize is a new
projection rather than a second wrapping rule in the pager; `document()` keeps the four projections
it answered last, keyed by the source text and the whole configuration, and answers the same
instance for a repeated request - which is what an unchanged frame, a theme toggled back, or a second
pane over one source relies on.

**Bounds.** Every projection is bounded by `Limits`: the input's UTF-8 size (1 MiB), the parser nodes
it visits (100 000), the block and inline nesting it descends (128) and the cells it paints (one
million, counting every row as at least one). The documented defaults are the ceiling - a definition
can lower a bound and cannot raise one - so no configuration makes a projection unbounded. The input
size is checked before the text reaches the parser, and the nesting limit is handed to the parser as
well, so a pathological document is bounded while it is parsed. A document over a bound is not
published partially: `diagnostics()` names the limit that stopped it with a typed `Diagnostic`
(`InputTooLarge`, `TooManyNodes`, `NestingTooDeep`, `TooManyCells`), `failed()` is then true, and the
document's lines are the diagnostic rows themselves in the theme's diagnostic style.

Out of scope by design: embedded HTML rendering, images, footnotes, definition lists, math,
front matter, and syntax highlighting beyond the three bundled tokenizers. A markdown table does not
wrap a cell - a narrow pane truncates it - and a code block is broken at the cell that fits rather
than re-indented, so a very narrow pane splits a statement. The renderer parses and projects; it
never reads a file, fetches a URL, resolves a reference or writes a terminal escape sequence.

## Tabs and composed views

`Tabs` is a tab bar with stable identities and a frame for the page it selects. It owns one fact -
which tab is shown - and the labels and enablement of the tabs, never their contents: page models,
which pages exist and whether a hidden page stays mounted are the parent's. The active tab is stored
as an id rather than an index, so reordering or renaming a tab list keeps the page the user is
looking at, and a page's own state is never keyed by where its tab happens to sit.

```java
// Definition: the tabs, the wrap policy and the cells' appearance.
Tabs tabs = new Tabs(Tabs.Config.defaults());

// State: the list, the shown id and whether the bar holds the keyboard.
Tabs.Model bar = tabs.init(context).model().withTabs(List.of(
        Tabs.Tab.of("notes", "Notes"),
        Tabs.Tab.of("inbox", "Inbox"),
        Tabs.Tab.of("timer", "Timer"),
        Tabs.Tab.of("archive", "Archive", false)));

// Composition: the bar joins the frame, and the page the active tab selects goes inside it.
View<Msg> composed = tabs.view(bar, notesMount.view(model, context), Msg.Bar::new);
```

**Identity and navigation.** `Msg.Next`, `Msg.Previous`, `Msg.First` and `Msg.Last` step over
disabled tabs - a disabled tab is a page the keyboard cannot reach, not one shown on the way past it
- and `Msg.Activate(String)` chooses by id and is refused for a disabled or unknown one. Wrap-around
is `Config.wrap()` and is off by default, so `next` on the last enabled tab is a no-op instead of an
invisible jump to the beginning. `Msg.SetTabs`, `Msg.InsertTab` and `Msg.RemoveTab` publish a new
list; the shown tab survives while its id survives and stays enabled, and otherwise the nearest
enabled survivor - the tab that now holds the removed position, or the closest enabled one before it
- takes its place. A tab list with a duplicate id is rejected where it is made, and a message that
changes nothing returns the incoming model instance. Every changed selection reports exactly one
`Output.Activated`, which is what an owner switches pages on; removing the last enabled tab reports
nothing and leaves `activeId()` null.

**The bar, the frame and the join.** The active tab opens into the content frame: its bottom edge is
the gap the page is visible through, so the bar and the page read as one shape. The bar windows rather
than overflowing - a bar narrower than its tabs shows the tabs around the active one and marks each
side where tabs were left out - and the window follows the active tab, so a narrow viewport always
reveals the label the user selected; the markers are spent only when the label keeps a column to
paint in. A label that does not fit is cut on a grapheme boundary and ends with `…`, so a two-column
glyph or a combining mark is never split. `Appearance` styles the shown, unshown and disabled cells
separately and the frame around the page apart from them, `Border.DOUBLE` joins with double
junctions, and a frame that draws nothing is rejected rather than painted as a bar of blank edges.
`Tabs.render(model, config, columns, content)` answers the same composition as text for a program
that paints its own content, `Tabs.view(model, content, mapper)` answers it as a view that routes the
bar's own keys, and `Tabs.frame(...)` answers it without declaring a focus target.

**Focus, keys and help.** Focus is model data the application owns, exactly as in `Selector` and
`Paginator`: the bar declares one focus element and consumes only the gestures its `KeyMap` names,
so an unfocused bar receives no keys and a focused one leaves text input and every other gesture to
its parent. `Tabs.defaultKeys()` moves between tabs and jumps to the first or the last one, and
`Help` renders from the same map, so the help line cannot describe a gesture that does not fire.
`tab` and `shift+tab` are deliberately not bound: they are the keys a text field completes on and a
form moves on, and only the application knows which of its fields wants them.

**Pages and keep-alive.** A bar owns no page, so the parent owns the composition: which page models
exist, which of them are mounted, and what moving to another tab does to them. Mounting every page
and painting the active one is the policy this widget is built for - a page that is not painted keeps
its model, its scoped element ids and its subscriptions, so a draft, a list selection and a running
timer are all still there when the user comes back. A parent that would rather free a hidden page
declares no `ComponentTree` node for it, which cancels that page's scoped work and drops its state;
the page starts again from its own `init` when it is mounted again. `Mount` states the rule and the
phase-16 demo, `examples.parity.TabsDemo`, is one worked example: an editor, a list and a countdown
behind one bar, with the countdown still ticking while its page is hidden.

Out of scope by design: the widget paints the bar and the frame; it does not own page models, mount
or unmount them, move focus, or bind the keys a page uses. It has no mouse activation and no
scrollable bar - both are the application's, or a later phase's.

## Scrollable tables

`DataTable` is the interactive table of the family: a schema, rows that carry their identity, a
bounded window under a fixed header, a row or single-cell selection that survives the rows changing
underneath it, and the horizontal pan that shows columns wider than the pane. It is a component with
nested immutable `Model`, sealed `Msg`/`Output` and immutable `Config`. The static `Table` and
`TableSelector` are unchanged, and they stay the right tools for a grid that is painted once or
selected from as a whole.

```java
// Definition: a schema, per-column width policies, and the rows the application paints around it.
DataTable.Config config = DataTable.Config.defaults()
        .withContent(DataTable.Content.of(
                List.of(
                        DataTable.Column.of("id", "id", 4),
                        DataTable.Column.of("name", "name"),
                        DataTable.Column.of("sown", "sown", 5)
                                .withAlignment(DataTable.Alignment.RIGHT)),
                List.of(DataTable.Row.of("S-01", "S-01", "artichoke", "12"))))
        .withReservedRows(4);

Mount<Model, Msg, Never, DataTable.Model, DataTable.Msg, DataTable.Output> table =
        Mount.of(ComponentId.of("table"), new DataTable(config),
                 ModelLens.of(Model::table, (model, child) -> model.withTable(child)),
                 Msg.Table::new,
                 OutputRoute.toMessage(Msg.TableOutput::new));

// The rows move; the selection keeps its identity.
table.update(model, new DataTable.Msg.Insert(0, DataTable.Row.of("S-00", "S-00", "cardoon", "8")));
table.update(model, new DataTable.Msg.Remove("S-01"));
```

**Schema, rows and widths.** A `Column` has a stable key, a header, a `Width` policy and an
`Alignment`; a `Row` has a stable id and one value per column. `Content` is the pair, validated where
it is made - two columns may not share a key, two rows may not share an id - and it is where the
column widths are resolved, once, when the content is built. A `Width` is a floor, a preferred width
and a cap: a fixed policy is exactly as wide as it says, and an automatic one measures the header and
the cells and keeps the result between the bounds. The natural width of a column is its preferred
width or its widest cell, whichever is larger, so `Width.of(8, 30, 60)` never drops below its
heading and never takes the whole terminal. A row may be ragged: a value it does not hold is empty,
and a value beyond the schema is not painted.

**The window.** `Model.height()` is the rows the parent allocates the table - the header line
included - and `Model.width()` is the cells it allocates. `Config.height()`/`width()` are the
fallbacks a headless test or a preview uses, and every later allocation arrives as `Msg.Resized`, the
contract `TextViewport` follows: the pane owns no resize source, because a table inside a box or a row
is not the whole terminal. The header is painted first and stays while the body scrolls under it;
`bodyHeight(state, config)` is the room the header leaves, and `lines(state, config)` is what a frame
paints. A page and a half page move the window as well as the selection, a line moves the selection
and the window the least it can, and every projection reveals the selection, so a state an application
built by hand still paints it.

**Selection and identity.** `Mode.ROW` selects the row as a whole and `Mode.CELL` one cell of it;
`Msg.ToggleMode` switches between them without losing the row or the column the reader was on. The
current row is an index and the identity rule lives in the data messages: `Msg.SetRows`,
`Msg.Insert`, `Msg.Replace` and `Msg.Remove` keep the selected row's id selected wherever it lands,
and removing the selected row selects the nearest survivor. An empty table has nothing selected and
answers no `Output.Chosen`; `Msg.Choose` reports the row, or the cell's column and value, and
`Msg.Cancel` reports that nothing was chosen. Horizontally the window is clamped to keep a cell
selection visible, so panning reaches the columns beside the selection and never loses it; a row
selection is free to pan the whole row.

**One cell mapping.** A row's painted width, the column starts, the horizontal offset and the
truncation of a cell that does not fit all use the projection the editor and the pager use, so a tab,
a wide glyph or a combining mark costs the same columns wherever the window is. A cell is one painted
row - tab stops are expanded and every break becomes one space - and a value wider than its column is
truncated on a cluster boundary rather than split. `Theme` paints: `header`, `row` and `cell` return
styles from the facts they are given, once per painted header cell, body row and body cell, and the
selection is merged last so a theme cannot hide what is selected.

**Keys.** `defaultKeys()` is the definition the component routes and the one `Help` renders, and
`keys(state)` is that map for one state: the column gestures are disabled while a whole row is
selected, and the pan is disabled while nothing is clipped. Arrows move the selection, `home`/`end`
jump to the first and last row, `m` switches the selection's mode, `enter` chooses and `esc` cancels,
and printable text and every unnamed gesture reach the parent.

Out of scope by design: sorting, filtering, editing a cell in place, multi-select, mouse hit-testing
and scrollbars. Those are messages an application sends - `Msg.SetRows` for a re-sorted list,
`Msg.Replace` for an edited row - which keeps the table a pure function of the content it is given.

**Fitting a static table.** `Table.width(cells)` is the same idea for a table that is painted once:
the columns are fitted to the budget, the widest giving cells up first and no column shrinking below
`Table.MIN_COLUMN`, and a cell or frame that still overflows is truncated on a cluster boundary. A
table that declares no budget renders exactly as it always did, so the cost of the budget is opt-in
and every existing table is unaffected.

## File pickers

`FilePicker` browses a directory of application-owned paths: the entries with their metadata, a
bounded window, directory navigation and one explicit choice. It is a component with nested immutable
`Model`, sealed `Msg`/`Output` and immutable `Config`, and it reads no filesystem in `init`, `update`
or `view` - reading a directory and re-checking a chosen entry are task effects whose results arrive
as ordinary messages.

```java
FilePicker.Config config = FilePicker.Config.defaults()
        .withDirectory(Path.of("/srv/notes"))
        .withRoot(Path.of("/srv"))     // no navigation may leave it
        .withExtensions("txt", "md")   // everything is listed; only these can be chosen
        .withReservedRows(3);          // what the application paints around the picker

Mount<Model, Msg, Never, FilePicker.Model, FilePicker.Msg, FilePicker.Output> picker =
        Mount.of(ComponentId.of("picker"), new FilePicker(config),
                 ModelLens.of(Model::picker, (model, child) -> model.withPicker(child)),
                 Msg.Picker::new,
                 OutputRoute.toMessage(Msg.PickerOutput::new));
```

**Entries.** A listing reads one directory into `Entry` values: the path, the resolved `Kind` (a
directory, a regular file, something else, or `MISSING` for an entry that cannot be read), the size
of a regular file, the nine-character POSIX permissions or empty where the filesystem has none, a
symlink's own target, and the two facts the picker decides from - whether the entry is navigable and
whether it is selectable. Directories sort first, then names case-insensitively with the code points
breaking a tie, so the order never depends on the order the directory happens to return. Hidden
entries are read with the rest and hidden by a projection, so the toggle is instant and nothing is
read twice; `Config.maxEntries` bounds one listing and truncation is painted rather than silent.

**Requests.** Reading a directory is one task under one replaceable effect id, carrying the model's
request epoch: starting the next request replaces the registration, and the epoch is checked again
where the result arrives, so a superseded listing can never publish the directory the reader has
left. A failure keeps the last successful listing on screen and reports a retryable error in the
status row (`r` retries), and the path row always describes the entries below it. `Msg.Listed` and
`Msg.Failed` are public because they are what a listing task produces: an application that already
knows a directory's contents sends the same messages.

**Choosing.** `enter` chooses a selectable entry, enters a navigable directory, or reports why the
entry cannot be chosen (an `Output.Rejected` with a `Reason`). A choice is re-checked before it is
reported: the entry must still be there, still be the kind the definition selects, still be inside
the root and still be allowed by the extension policy - and the check reads the entry's attributes,
never its contents. An entry that disappeared, was replaced by a directory or left the root is
reported instead of chosen. A directory link is navigable only when following it stays inside the
root and does not lead back to the directory that holds it, so a link to `.` is listed and refused
rather than walked into.

**Painted rows.** `Model.rows()` is what the parent allocated, chrome included, and
`contentRows(state, config)` is what is left for entries after the path row, the status row and the
`Help` lines of `keys(state)`. Entries are painted one row each - the marker and name style
`RichList` uses, with the metadata right-aligned and dropped before the name is cut - and
`lines(state, config, width)` returns exactly the rows a terminal receives, so a parent lays the
picker out without measuring it. A picker too short for its own chrome keeps the path and status rows
and gives up the help.

**Keys.** `defaultKeys()` is the definition the component routes and the one `Help` renders, and
`keys(state)` is that map for one state: `open` exists only while the selection is a directory,
`parent` only while there is somewhere inside the boundary to go, and confirming is always available
so a refused entry can say why. Everything the picker does not own - a scene's own create, delete or
quit - bubbles to the application.

Out of scope by design: searching and filtering (send `Msg.Listed` with what you read, or narrow the
directory with `Config.withExtensions`), creating, renaming and deleting files, file contents,
multi-select, mouse hit-testing and persisted state. The picker never writes, never creates a
directory and never follows a link outside the configured root.

## Paging a list

`Paginator` is page state and a page indicator over items the application owns: which page is
shown, how many pages the content has, and the half-open item range the page covers. It is a
component with nested immutable `Model`, sealed `Msg`, immutable `Config` and a `Never` output
type, because every interaction it offers is a state change the parent can observe. It stores no
items, filters nothing and sorts nothing.

```java
// Definition: twenty items a page, no wrap-around, dots.
var pager = new Paginator(Paginator.Config.defaults().withPerPage(20));

Mount<Model, Msg, Never, Paginator.Model, Paginator.Msg, Never> pages =
        Mount.of(ComponentId.of("pages"), pager,
                 ModelLens.of(Model::pages, (model, child) -> model.withPages(child)),
                 Msg.Pages::new,
                 OutputRoute.toOutput(value -> value));

// The parent keeps its own list and slices it with the bounds the state reports.
Paginator.Bounds bounds = model.pages().bounds();
List<Item> visible = model.items().subList(bounds.start(), bounds.end());

// A new list is a new count, and the page is clamped to the pages that exist.
return pages.update(model, new Paginator.Msg.ItemCountChanged(model.items().size()));
```

**State and bounds.** The page index is zero-based. `Model` keeps itself valid: a page past the end
is the last page, a negative page the first, and a negative count is zero. A page size is a
definition rather than a value to repair, so `Config`, `Model` and `Msg.PageSizeChanged` reject a
non-positive one - a page that can never hold an item would be a phantom page. Empty content has
zero pages, page zero and an empty range `[0,0)`, so no consumer has to special-case it, and
`Model.bounds()` is always within the item count (`0 <= start <= end <= itemCount`), so
`items.subList(bounds.start(), bounds.end())` is legal for every state. Page arithmetic is done in `long` and clamped before it narrows, so a count
or page size near `Integer.MAX_VALUE` cannot overflow into a negative index. `totalPages`,
`hasPages`, `hasNext`, `hasPrevious`, `isFirstPage` and `isLastPage` are the same facts the indicator
paints, so a status row and a rendered indicator cannot disagree. Empty content is both first and
last, having one position and no pages.

**Navigation.** `Msg.Next`, `Msg.Previous`, `Msg.First`, `Msg.Last` and `Msg.GoTo` move the page;
`Msg.ItemCountChanged` and `Msg.PageSizeChanged` publish a fact and let the model clamp. Wrap-around
is `Config.wrap()` and is off by default: `next` on the last page is then a no-op, not an invisible
jump to the beginning. A message that changes nothing returns the incoming model instance.

What the paginator deliberately does not own is everything else about a list: item storage,
filtering, sorting, selection, and whether a selection should bring its page into view. Moving the
page to follow a selection is a parent decision expressed as `Msg.GoTo`, exactly as `PaginatorDemo`
does it.

**Indicators.** `Paginator.render(model, config, width)` paints `Appearance.mode()` as dots or as a
numeric `current/total` label, and the mounted view paints the same value at the width its layout
actually allocates. Dots are windowed, never one per page: at most `Appearance.maxDots()` dots are
painted around the current page and an ellipsis marks each side where pages are hidden.

```text
first page   ● ○ ○ ○ ○ ○ ○ …
middle       … ○ ○ ● ○ ○ ○ …
last page    … ○ ○ ○ ○ ○ ○ ●
```

A frame therefore costs what the configured window costs, not what the page count costs, so a list
with millions of pages paints the same number of cells as a short one. The active dot, the inactive
dots, the ellipses and the separators carry `Appearance.activeStyle()`/`inactiveStyle()`, and the
numeric mode is the same indicator in one label. Zero pages has one explicit indicator, `0/0`, in
both modes instead of a blank row.

The indicator is cut to the width it is given by display columns and never through a grapheme
cluster: a narrow pane first shrinks the dot window whole, then ends the row with an ellipsis, and a
row too narrow for one dot and an ellipsis paints nothing. A wide (two-column) dot glyph is never
half-painted. A resize therefore truncates the indicator and never moves the page - only an
explicit `Msg.PageSizeChanged` changes how many items a page holds.

**Keys and help.** `Paginator.defaultKeys()` moves a page and jumps to the first or the last one;
`pager.keys()` is the map the component routes, so `Help` describes the gestures that actually fire
and a remapped action changes routing and help at once. `new Paginator(config, keys)` replaces the
whole map. The component declares one focus element, so an unfocused paginator receives no keys and
a focused one consumes only its paging gestures - printable text and every other gesture reach the
application, which is what lets one screen bind `up`/`down` to a selection and `left`/`right` to
pages.

Guarantees:

- the page is always inside the pages that exist, the count is never negative, and a page size is
  always positive;
- the bounds are a half-open interval inside the item count, so the pages partition the items
  exactly once;
- empty content has zero pages and an empty range, and no state paints a phantom page;
- the indicator never exceeds the width it is given and never splits a grapheme cluster;
- dots are bounded by the configured window, never by the page count;
- a message that changes nothing returns the incoming model instance.

## Filterable lists

`RichList` is the whole list: items that keep their identity, a query, a selection, pages, chrome and
feedback. It is the rich counterpart of `Selector` - one rendered row per option is all a small
choice needs, and everything a filterable list needs is here. The widget owns the data, the query,
the selection and the chrome; the application owns the keys that do something with a choice. The
list never inserts, removes, fetches or navigates on the application's behalf, and it performs no
I/O, holds no clock and starts no thread.

```java
var list = new RichList(RichList.Config.defaults()
        .withItems(items)                              // RichList.Item(id, title, description, filterText)
        .withTitle("Favourite things")
        .withRenderer(RichList.DefaultRenderer.defaults())
        .withRows(24 - 3)                              // headless height; a terminal publishes its own
        .withReservedRows(3)                           // rows the application paints outside the list
        .withExtraHelp(appKeys));                      // painted, not routed, by the list

Mount<Model, Msg, Never, RichList.Model, RichList.Msg, RichList.Output> choices =
        Mount.of(ComponentId.of("list"), list,
                 ModelLens.of(Model::list, (model, child) -> model.withList(child)),
                 Msg.List::new,
                 OutputRoute.toMessage(Msg.Output::new));

// A choice or a cancellation crosses the mount as the scene's own message.
return switch (message) {
    case Msg.Output value -> switch (value.value()) {
        case RichList.Output.Chosen chosen -> open(chosen.item());
        case RichList.Output.Cancelled cancelled -> Command.quit();
    };
    case Msg.List value -> choices.update(model, value.value());
    // ...
};
```

**Selection is an item, not an index.** `RichList.Item.id()` is the identity filtering, paging and
reordering preserve. An item inserted above the selection leaves it selected, a query that hides it
and shows it again leaves it selected, and a resize that repacks the pages leaves it selected. An id
is required and unique within one list; a model that repeats one, or a blank one, is rejected where
the mistake was made. A removal selects the nearest surviving item the query still shows - the one
that took the removed row's place, or the one before it - and a model built with an id the list does
not hold falls back to its first item. A selection the current query hides falls to the first item
the query shows, and a query that hides everything keeps the selection, so narrowing and widening a
query is reversible.

**Filtering.** The query lives in a filter field composed from `TextInput`, so editing it has the
gestures that field already defines: the widget routes a typed character, a paste or a caret move to
the field and keeps its own browsing actions disabled while the edit is open. Matching is
case-insensitive per Unicode code point with the simple lowercase mapping, so folding never moves an
offset inside a value, and the query is split on whitespace with every term required to match.
`FilterMode.PREFIX` is the order-preserving mode - each term must appear in the item's filter text -
and `FilterMode.FUZZY` takes each term as a subsequence and ranks by the shortest matched span, then
the earliest match start, then item order. Both keys are total, so equal matches never depend on the
sort implementation.

```text
query "abc", fuzzy
  abc        span 3, first 0
  xabc       span 3, first 1
  ab c       span 3, first 0
  a b c      span 5, first 0
```

`Item.filterText()` decides whether an item matches and defaults to its title; its title is
highlighted where the same query matches the title, so a range is always an offset into text that is
painted. `RichList.matchHighlights(query, text, mode)` is the same matcher for a renderer that paints
its own item, and its ranges are widened to whole grapheme clusters - a combining mark, a selector or
a surrogate pair is never split by a style boundary.

**Pages come from rows, not from an item count.** A `RichList.Renderer` decides how many rows an item
occupies, and whole items fill a page until the content rows are used up, so a tall item simply
occupies more of its page and an item taller than the content is clipped rather than skipped. Every
page is filled to the same height, which keeps the indicator and the help on the same screen rows
however many items the last page holds.

The height is the startup viewport minus `Config.reservedRows()`, follows the terminal's own resizes
through one scoped subscription, and is also publishable as `Msg.RowsChanged` for a preview or a
test. Which page is shown is *derived* from the selection rather than stored, so a query, a resize or
a changed renderer repacks the pages without the list ever pointing at a page that no longer exists.
`Msg.NextPage` and `Msg.PreviousPage` move one page and select the first item of the page they arrive
at - an explicit, documented move rather than a silent re-selection.

Chrome is five sections: a title bar that becomes the filter field while the query is edited, a
count-and-status row, the items, a page indicator painted by `Paginator`, and help. Each is a row
budget as well as a painted section, so `Msg.ToggleTitle`, `Msg.ToggleStatus`, `Msg.TogglePagination`
and `Msg.ToggleHelp` give their rows back to the items and repack the pages; `Msg.ToggleExpandedHelp`
switches compact help for the grouped listing. The title bar is painted while a query is edited even
when the title is switched off, because a filter the user cannot see is a trap.

**Feedback.** `Msg.Notice` paints a transient message after the counts and schedules one scoped
`Command.delay` that carries the notice's epoch; an expiry that arrives after a newer notice was set
is ignored, so a stale timer cannot clear the next message. `Msg.ToggleLoading` starts a composed
`Spinner`, and the spinner's interval exists only while it is shown, so a list that is not loading
owns no timer.

**Keys and help.** `RichList.defaultKeys()` is the definition the component routes and the definition
`Help` renders, and `RichList.keys(model)` is that map for one state: while a query is edited only the
filter's own gestures are enabled, so a printable key that also names a browsing action types its
character, and the two actions bound to `enter` - apply the filter and choose an item - are never
enabled together. One gesture cancels everything: the edit being typed, then the applied query, then
the list itself, at which point the list reports `Output.Cancelled` and the application decides what
that means. `Config.extraHelp(...)` adds the application's own actions to the painted help without
claiming their gestures, which stay with the application's own bindings around the mount.

Guarantees:

- the selection always names an item the current query shows, unless it shows nothing, and item ids
  are unique and non-blank;
- filtering is deterministic: `PREFIX` keeps item order and `FUZZY` orders by span, then start, then
  item order;
- highlight ranges are offsets into painted text and start and end on grapheme boundaries;
- pages cover every visible item exactly once and hold at least one item, so paging always advances
  and a page loop always terminates;
- the list paints exactly the rows it reports: content rows are `rows` minus its own chrome, item rows
  are bounded by the renderer's own heights, and no paint exceeds the width it was given;
- the whole model is a value: a message that changes nothing returns the incoming instance, and two
  paints of one model are the same cells.

Out of scope by design: multi-select, drag and drop, group headers, sticky sections, inline editing,
mouse hit-testing, and any persistence of an item list. `RichList` paints chrome itself, so an
application that wants different chrome around it composes its own rows and mounts the list between
them.


## Timed spinners

`Spinner` is a configurable frame indicator: a definition of frames and the time one frame lasts,
plus the model time that selects one. The definition is data, so two spinners on one screen can run
different frames at different cadences without sharing any state.

```java
// A definition is an immutable value, validated where it is made.
var frames = Spinner.Config.of(List.of("|", "/", "-", "\\"), Duration.ofMillis(100));
var braille = Spinner.Config.preset(Spinner.Preset.DOT).withFrameDuration(Duration.ofMillis(80));

// The bundled presets: line, dot, pulse, globe and moon.
Spinner.Preset.GLOBE.config().columns();   // 2 - the wide frames report their own width
Spinner.Preset.MOON.config().frameCount(); // 8

// Mount it: the component declares one interval while it runs.
Mount<Model, Msg, Never, Spinner.Model, Spinner.Msg, Never> busy =
        Mount.of(ComponentId.of("busy"), new Spinner(frames),
                 ModelLens.of(Model::busy, (model, child) -> model.withBusy(child)),
                 Msg.Busy::new,
                 OutputRoute.toOutput(value -> value));

// Or render without mounting anything: the frame is a pure function of the model.
StyledText glyph = Spinner.render(model.busy(), frames);
```

**Definitions are validated where they are made.** A frame list must be non-empty, every frame must
be non-empty text, and a frame may carry line breaks - a multi-row spinner is legal - but no other
control character: an escape sequence would be a terminal command and a tab would make a painted
width depend on where the row begins. A frame duration must be strictly positive and expressible in
nanoseconds. `frameCount()`, `rows()` and `columns()` are the definition's sizing facts, so a caller
can reserve the box before it paints, and an existing `Config` can be retimed or restyled without
touching the model.

**The state is time, not a frame.** `Model` holds `(running, epoch, originNanos, nowNanos)`; the
frame is derived, so equal model and time always paint equal frames, and a tick that arrives after a
long gap lands on the frame the clock selects instead of replaying the ticks it missed. `Msg.Start`
subscribes and arms the cycle; the first `Msg.Tick` records that runtime-supplied time as the origin,
which is what makes a restart begin at frame zero without the application reading a clock.
`Msg.Stop` keeps the frame it was showing and drops the subscription, and `Msg.Reset` starts the
cycle over, which is what a caller sends after changing the definition. Every one of those gestures
increments `epoch`, and a tick mapped before it matches no epoch and is ignored, so an old tick can
never advance a newer animation. Elapsed arithmetic saturates, and a clock that went backwards clamps
to frame zero.

**Mounting is the lifetime.** A running spinner declares one scoped interval whose period is the
frame duration, and none while it is stopped; there is no per-widget timer, thread or executor.
`Spinner.init` starts a running spinner, because a mounted spinner is normally one the application
wants to show. Unmounting closes the source with the component's scope, so a removed spinner cannot
keep ticking, and a changed cadence is a changed configuration that the runtime reconciles as a new
source.

**Gestures belong to the application.** The widget owns no keys and no focus: which preset is shown
and when a spinner starts or stops are the application's decisions, which is what lets one screen
drive two spinners independently. `Animation.spinner` keeps its original four diamond frames at a
quarter-second cadence, and both it and a configured definition select a frame with the same
arithmetic, so the legacy animation and a new definition cannot disagree about which frame a time
belongs to.

Guarantees:

- a definition that exists is paintable: non-empty frames, no control text, a positive cadence;
- the frame is a pure function of the state and the time, and never depends on how many ticks arrived;
- a stopped spinner paints the frame it stopped on and holds no timer;
- a stale tick is ignored, so a restart never continues an older cycle;
- a rendered frame never exceeds the width it is given and never splits a grapheme cluster;
- no clock reading the widget took itself is ever part of the state.

## Animated progress

`ProgressBar` is a pure renderer: it paints the fraction it is given. `AnimatedProgress` is the
component that eases that fraction, so a target that jumps does not make the fill jump with it.

```java
Color start = new Color(0x2B, 0x8C, 0xFF);
Color end = new Color(0xC0, 0x5B, 0xFF);
var bar = ProgressBar.of(40)
        .fractional(true)                       // grade the cell the fill stops in
        .blend(start, end)                      // ramp the filled cells
        .showPercentage(true);

Mount<Model, Msg, Never, AnimatedProgress.Model, AnimatedProgress.Msg, Never> install =
        Mount.of(
                ComponentId.of("install"),
                new AnimatedProgress(AnimatedProgress.Config.of(bar)),
                ModelLens.of(Model::install, Model::withInstall),
                Msg.Install::new,
                OutputRoute.toOutput(value -> value));

// An application message names the new target; the widget owns the time from there.
case Msg.Installed installed ->
        install.update(model, new AnimatedProgress.Msg.Set(installed.fraction()));
```

**The scale is normalized.** A target, a displayed position and a velocity are all fractions of the
whole bar, so a resize changes how many cells carry the value and not the value itself. A finite
target outside `[0,1]` clamps to an end of it - which is what makes `Msg.Adjust(0.1)` a safe nudge -
and a target, position or velocity that is NaN or infinite is rejected where it is offered rather
than painted as an empty bar, because a bar that is a number the scale cannot hold has no honest
rendering. `ProgressBar` keeps its own contract for the static case, where a bar no cells wide or a
non-positive maximum paints nothing instead of throwing.

**The ease is analytic.** A segment is a critically damped response evaluated in closed form from
its origin, the position it started at and the velocity it started with. The painted value is
therefore a pure function of the model and the time: a stalled runtime that delivers one late tick
lands where the animation really is instead of replaying the steps it missed, and an irregular
cadence cannot make a stepped solver overshoot. `Config.responseTime()` - 300 ms by default - is how
long a full-scale change takes to come to rest; retargeting carries the current position and
velocity over, so the bar bends towards the new target instead of restarting, and the velocity can
carry it past the target for a moment before it comes back.

**Rest is a state, not a coincidence.** A segment is at rest when both its remaining distance and
its normalized velocity are below `SETTLE_EPSILON` (`1e-4`). That tick lands on the target exactly
and `Model.running()` turns false, so the interval is no longer declared and the bar paints the
target rather than a value a hair away from it. A bar at rest holds no timer at all, which is what
keeps an idle screen idle. `Msg.Set(double)` on a bar that already has that target changes nothing.

**Graded and blended fills** are opt-in refinements of the same renderer, off by default so a bar
that does not ask for them paints exactly what it always painted:

- `fractional(true)` stops the fill inside a cell with one of the eighth-block glyphs
  `▏▎▍▌▋▊▉` instead of rounding it to a whole cell. The graded cell is padded to the active icon's
  width, so the row keeps its painted width as the fill crosses a boundary; a custom active icon is
  replaced at that one cell.
- `blend(start, end)` colours the filled cells along a ramp. The ramp is anchored to the bar, so
  cell `i` of `n` keeps its colour as the fill grows and an animated bar does not shimmer; a cell
  the fill does not reach keeps the colour `colors(...)` gave it.

**Reduced motion** is a definition, not a special case: `AnimatedProgress.Config.instant(bar)`
reaches every target at once and never subscribes to runtime time, and `Msg.Settle()` is the same
gesture for one bar mid-animation. Both keep reporting the same targets; they simply stop easing.

Guarantees:

- the painted value is a pure function of the state and the time, and never depends on how many
  ticks arrived;
- a finite target is always on the scale, and a value off it clamps instead of throwing;
- a retarget never jumps: the position the bar had reached is the position it keeps painting;
- a bar at rest holds no subscription, and a stale tick belongs to no segment and is ignored;
- a graded cell never splits a grapheme cluster and never changes the row's painted width;
- no clock reading the widget took itself is ever part of the state.

## Countdowns

`Countdown` is a component: a configured duration that runs, pauses, resumes and expires once. Its
remaining time is a `Duration` the model holds exactly, and its default view paints whole seconds.

```java
var countdown = new Countdown(Countdown.Config.of(Duration.ofMinutes(5)));

Mount<Model, Msg, Never, Countdown.Model, Countdown.Msg, Countdown.Output> timer =
        Mount.of(
                ComponentId.of("timer"),
                countdown,
                ModelLens.of(Model::timer, Model::withTimer),
                Msg.Timer::new,
                OutputRoute.toMessage(Msg.Timed::new));

// One key is the whole gesture: pause while running, resume while paused, start once finished.
case Msg.TogglePressed pressed -> timer.update(model, new Countdown.Msg.Toggle());
```

**Remaining time is derived, not accumulated.** A running countdown stores the instant it expires and
reads the time left as the distance to it, so a stalled runtime that delivers one late tick lands on
the time that is really left instead of replaying the ticks it missed, and an irregular cadence
cannot make the countdown drift. The component reads no clock: `Msg.Start` and `Msg.Resume` carry no
time, so they arm the run and return `Command.monotonicTime`, and the `Msg.Reading` it produces names
the deadline. That is why a countdown begins at the instant it was started rather than at the next
tick.

**A reading can be late, and arithmetic can overflow.** A reading is clamped to the last one the
model saw, so a late or reordered reading can only shorten what is left and never lengthen it;
deadlines and remainders saturate instead of wrapping; and a countdown that passed its deadline
reports zero rather than going backwards.

**Every gesture is idempotent.** `Msg.Start` begins a run of the configured duration - starting a
running countdown changes nothing, and starting a paused one discards the remainder it was holding.
`Msg.Stop` freezes that remainder and drops the subscription, so no time passes while a countdown is
paused. `Msg.Resume` continues from the frozen remainder, `Msg.Reset` stops and puts the configured
duration back, and `Msg.Toggle` is the one-key form of all three. A zero-length countdown has nothing
to wait for, so it expires the moment it is started.

**An expiry is reported once.** The reading that sees the deadline pass stops the run and emits one
`Output.Timeout`; later readings of that run match no running countdown and are ignored, so a
duplicate tick, a queued emission or a paused countdown's leftover reading can never report a second
timeout. Every state change increments `Model.epoch()`, which is what tells a reading that belongs to
an older run apart from one that belongs to the current run.

**Formatting is definition data.** `Countdown.CLOCK`, the default, writes `mm:ss` and grows an hours
field (`h:mm:ss`) once an hour or more is left; a positive fraction of a second rounds *up*, so a
countdown that still has time left never paints the zero it has not reached.
`Config.withFormatter(...)` replaces that policy for the whole widget, and `Model.remaining()` is the
exact value for a caller that needs the number rather than the picture of it.

Guarantees:

- the remaining time is a pure function of the state and the runtime's readings, never of how many
  ticks arrived;
- a run reports exactly one timeout, and a countdown that was stopped first reports none;
- a paused countdown holds no subscription, and a stale reading belongs to no run and is ignored;
- a configured duration is never negative and a zero one expires once per start;
- a painted time is cut to the width it is given and never splits a grapheme cluster;
- no clock reading the widget took itself is ever part of the state.

## Stopwatches

`Stopwatch` is a component: one elapsed duration that runs, stops, resets and runs on again. Its
elapsed time is a `Duration` the model holds exactly, and its default view paints
`hh:mm:ss.mmm`.

```java
var stopwatch = new Stopwatch(Stopwatch.Config.defaults());

Mount<Model, Msg, Never, Stopwatch.Model, Stopwatch.Msg, Never> watch =
        Mount.of(
                ComponentId.of("watch"),
                stopwatch,
                ModelLens.of(Model::watch, Model::withWatch),
                Msg.Watch::new,
                OutputRoute.toOutput(value -> value));

case Msg.TogglePressed pressed -> watch.update(model, new Stopwatch.Msg.Toggle());
```

**Elapsed time is derived, not accumulated per tick.** A running stopwatch stores the instant its
current run began and adds that to what earlier runs measured, so a stalled runtime that delivers
one late tick lands on the time that has really elapsed instead of replaying the ticks it missed,
and an irregular cadence cannot make the watch drift. The component reads no clock:
`Msg.Start` and a reset of a running watch carry no time, so they arm the run and return
`Command.monotonicTime`, and the `Msg.Reading` it produces names the origin. That is why a run
begins at the instant it was started rather than at the next tick.

**A reading can be late, and arithmetic can overflow.** A reading is clamped to the last one the
model saw, so a late or reordered reading can never make the painted time run backwards; the
accumulated total and the distance from the origin saturate instead of wrapping; and elapsed time
is never negative.

**Every gesture is idempotent.** `Msg.Start` begins a run and keeps the time already accumulated -
starting a running stopwatch changes nothing, and there is no separate resume, because for a
stopwatch resuming is starting. `Msg.Stop` folds the current run into the total and drops the
subscription, so a stopped watch holds no timer and no time passes. `Msg.Reset` zeroes the elapsed
time and keeps whether the watch is running: a stopped watch stays stopped at zero, and a running
one carries on from zero. `Msg.Toggle` is the one-key form of start and stop. Every gesture
increments `Model.epoch()`, which is what tells a reading of an older run apart from one that
belongs to the current run.

**Formatting is definition data.** `Stopwatch.CLOCK`, the default, writes `hh:mm:ss.mmm`: the hours
field is at least two digits wide and widens for a longer run, and the milliseconds field is
truncated rather than rounded, so the painted time never claims a millisecond that has not elapsed.
The largest time a `long` of nanoseconds can express - about 2562047 hours - still renders, so the
display has no artificial ceiling. `Config.withFormatter(...)` replaces that policy for the whole
widget, `Config.withTickPeriod(...)` changes only the repaint cadence, and `Model.elapsed()` is the
exact value for a caller that needs the number rather than the picture of it.

Guarantees:

- the elapsed time is a pure function of the state and the runtime's readings, never of how many
  ticks arrived;
- a stopped or reset watch holds no subscription, and a stale reading belongs to no run and is
  ignored;
- a late or reordered reading can only leave the painted time where it was, never move it backwards;
- a painted time is cut to the width it is given and never splits a grapheme cluster;
- no clock reading the widget took itself is ever part of the state.

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
| `Markdown` | A CommonMark-plus-GFM document projected into styled logical lines, with themes, bundled code highlighting and typed bounds |
| `Table` | Buffered column widths, optional border, opt-in width budget and per-column alignment |
| `Box` | Border, title, responsive or fixed width |
| `Badge` | Pill segments with optional pathway arrows |
| `ProgressBar` | Active/inactive icons, separators, percentage, and opt-in graded and blended fills |
| `AnimatedProgress` | A `ProgressBar` eased towards a target by runtime time, with one scoped interval while it moves |
| `Countdown` | A configured duration that runs, pauses, resumes and expires once, derived from runtime readings and formatted `mm:ss` |
| `Stopwatch` | One elapsed duration that runs, stops, resets and runs on, derived from runtime readings and formatted `hh:mm:ss.mmm` |
| `Quote` | Colored bar and background |
| `Gradient` | Vertical, horizontal, or diagonal per-grapheme color |
| `Selector` / `TableSelector` | Immutable selection state and focusable views; the marker shows the selection, the active style follows focus |
| `KeyMap` / `KeyAction` | Named actions with key aliases, message, help text and enabled flag; one definition for routing and help |
| `Help` | Compact one-line or grouped full help for a `KeyMap`, truncated by display columns |
| `View.Sized` / `Views.sized` | A subtree built from the geometry it is given, for a widget that wraps or clips its own content |
| `ScrollView` | Focusable vertical pane over application-owned display rows; the window and follow-end are model data, the content stays with the application |
| `TextViewport` | Focusable pager over application-owned logical lines: it wraps or pans them, paints a gutter, highlights published ranges and walks matches, and keeps a logical anchor across a reflow |
| `DataTable` | A scrollable, selectable table over typed columns and identity-carrying rows: a fixed header, a bounded window, row or cell selection, horizontal panning, per-column width policies and a per-cell theme |
| `Paginator` | Immutable page state with half-open slice bounds and a focusable dot/numeric indicator; the items stay with the application |
| `Tabs` | A tab bar with stable tab ids, keyboard navigation that skips disabled tabs, a bounded window that keeps the shown label visible, and a frame the shown tab opens into; the pages and their mounts stay with the application |
| `RichList` | A filterable, paged list whose items keep their identity: stable-id items, a composed filter field, plain or subsequence matching, height-packed pages, toggleable chrome, a loading spinner and transient status |
| `FilePicker` | A directory browser over application-owned paths: entries with their size, permissions and link target, deterministic ordering, bounded listings, scoped listing and re-check tasks, a boundary no navigation leaves and one explicit choice |
| `Animation` | The original fixed four-diamond spinner and the shimmer highlight, driven by model time |
| `Spinner` | Configurable frame definitions (line, dot, pulse, globe, moon or custom), a pure time-driven model and one scoped interval while it runs |
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

`ClipboardSelection` names the two clipboards a terminal addresses: `SYSTEM` (OSC 52 selection `c`)
and `PRIMARY` (selection `p`, X11 and Wayland only). `Command.copy(selection, ...)` writes either
one, and the two are independent destinations: each keeps its own deduplication and a write to one
never rewrites the other.

A read is a question with a typed answer. `Command.readTerminalClipboard(id, selection, mapper)`
writes `OSC 52 ; <selection> ; ?` and delivers a `ClipboardReadResult`, which distinguishes the text
the terminal reported (`Text`, including an empty selection) from `Refused` (the terminal said it
cannot answer), `Timeout` (no answer within the reply deadline), `Invalid` (the payload was not
strict base64, not UTF-8, or named another selection), `Oversize` (the decoded text exceeded
`maxReadPayloadBytes`), `Disabled` (the session cannot ask) and `Rejected` (the live-query bound was
reached). No part of a reply the session cannot believe becomes text, so a malformed payload never
reaches an editor. A read never writes the in-process buffer: an application that wants what it read
to become the session's paste value issues its own copy. Reads are serialized per selection and are
cancelled with their effect or scope, so a late reply satisfies nothing. The framing buffer accepts
`maxReadEncodedCharacters()` characters of an OSC 52 reply - the encoded bound of the configured
limit, so a reply that could decode within the limit is never dropped before it is checked - and a
longer one is reported as `Oversize` rather than confused with silence.

Copying and pasting in a widget are parent-owned: `Editor.Copy` and `TextInput.Copy` produce output
values, the parent turns them into copy commands, and a masked draft yields empty text. A paste
request names no source, so the parent chooses between the in-process buffer and a terminal read and
then delivers the paste message that applies the paste policy. Widgets never write clipboard bytes
themselves.

## Terminal queries and colour profiles

A terminal can be asked what it is and how it is configured. `app.TerminalQuery` names every question
this library asks, and `Command.query` asks one:

```java
Command.query(
        EffectId.of("background"),
        new TerminalQuery.ColorReport(TerminalQuery.Target.BACKGROUND),
        reply -> new Msg.Terminal(reply))
```

The answer arrives as an ordinary message, so nothing blocks `update` and no terminal read happens
inside a transition. Every query produces exactly one answer, and the answer is typed:

| Query | Wire form | Answer |
| --- | --- | --- |
| `CursorPosition` | `CSI 6 n` | `Reply.CursorPosition(row, column)`, zero-based |
| `Identity` | `CSI > q` (XTVERSION) | `Reply.Identity(name)`, e.g. `xterm(390)` |
| `Mode(mode)` | `CSI ? Ps $ p` (DECRQM) | `Reply.Mode(mode, Setting)` |
| `Termcap(name)` | `DCS + q <hex name> ST` | `Reply.Capability(name, value)` |
| `ColorReport(target)` | `OSC 10`/`11`/`12 ; ?` | `Reply.ColorValue(target, Color)` |
| `ClipboardRead(selection)` | `OSC 52 ; <selection> ; ?` | `Reply.Clipboard(selection, text, bytes)`, bounded by the read limit |
| `ProgramStatus()` | `OSC 7501 ; ?` | `Reply.ProgramStatus(states, kinds)`, the names the terminal accepts |
| `Profile()` | nothing: a session fact | `Reply.Profile(profile, explicit)` |

A terminal can also say no. `Reply.Unsupported` is the terminal's own refusal - `0+r` for a
capability, `?` for a colour - and `Reply.Timeout` is silence, reported after the reply deadline.
`Reply.Malformed` is an answer whose payload cannot be believed, and `Reply.Rejected` means the
session already had as many queries outstanding as it allows. `Reply.Oversize` is an answer that
decoded to more than the session reads: a clipboard is the user's data, so a value that does not fit
is refused rather than truncated. An application that needs a fact it
could not get decides what to render instead; nothing is invented in between.

Queries are bounded and correlated, because a terminal reply carries no request identity:

- One second per reply, measured on the runtime's timers, so a terminal that never answers costs one
timeout instead of a stall.
- At most sixteen queries live at once. A seventeenth is answered immediately with `Rejected`.
- At most one request of each reply kind is on the wire. Two cursor-position queries cannot be told
apart by their answers, so the second waits for the first to be answered or to time out.
- A reply nothing is waiting for is discarded. It can neither satisfy a different query nor reach
the view tree as input, and a reply that arrives after its query timed out or was withdrawn
satisfies nothing.
- Cancelling the effect, cancelling its scope, or unmounting the component withdraws the query.

`Termcap` is closed: only `RGB`, `Tc`, `TN`, `Co` and `Ms` are requestable, so a reply can never be
interpreted for a name whose meaning this library has not decided. `RGB` and `Tc` both mean "this
terminal displays 24-bit colour".

### Colour profiles

`style.ColorProfile` is the amount of colour a session puts on the wire: `NONE`, `ANSI16`,
`ANSI256` or `TRUECOLOR`. The same styled text encodes as 24-bit SGR, as a 256-colour palette index,
as one of the classic sixteen codes, or as no colour at all; bold, italic, underline and OSC 8
hyperlinks survive every profile, so a session that loses colour loses only colour.

A frame is encoded in the profile the session is presenting with:

- `RuntimeOptions.withColorProfile(...)` sets it for a run. The default, `ColorProfile.AUTO`,
  negotiates: no colour when the terminal cannot address it or when `NO_COLOR` is set to any
  non-empty value, and truecolor otherwise - the output every earlier release emitted on a colour
  terminal, so no existing application changes appearance.
- `Command.colorProfile(...)` switches it while running. The next frame repaints the whole screen,
  because every cell was written with the previous profile; `ColorProfile.AUTO` returns to the
  negotiated one.
- A termcap reply that confirms `RGB` or `Tc` widens a narrower profile. Evidence never narrows one,
  and an explicit profile is honoured as given.

Downsampling to a palette is a documented rule rather than an accident. A colour maps to the
nearest entry measured in CIE L\*a\*b\*, so a mid blue maps to blue and not to the cyan an RGB
channel distance would pick; an exact tie goes to the lower palette index, and the arithmetic is
`StrictMath`, so the same colour maps to the same entry on every platform. In a 256-colour session
only the 6x6x6 cube and the grey ramp are candidates, because a terminal may redefine its first
sixteen colours; a 16-colour session has nothing but those sixteen to choose from. The mapping is
memoized within a bounded cache.

### Session facts

Some answers describe the session rather than a component: the profile in force, the terminal the
session opened, and the background colour the terminal reported. `app.TerminalFacts` is that set, and
`Subscription.facts` delivers it:

```java
Subscription.facts(SubscriptionId.of("facts"), Msg.Facts::new)
```

The current facts arrive when the source starts, and again whenever one changes - a profile widened
by a capability reply, or a background colour reported for the first time. `TerminalFacts.dark()`
answers the question a theme actually asks, through the same classification rule as
`Reply.ColorValue.dark()` and `Colors.isDark`: the BT.601 luma of the colour, everything below the
halfway point counting as dark. A background nobody has asked for is `themeKnown() == false` rather
than a guessed theme.

Nothing is queried at startup. A session that wants terminal facts asks for them, and a terminal
that implements none of the queries simply answers nothing.

## Declarative terminal presentation

A frame draws cells; `view.TerminalPresentation` describes everything about the terminal that is not
a cell, and `Views.presentation(...)` declares it around the root of the tree:

```java
return Views.presentation(
        TerminalPresentation.EMPTY
                .withTitle("braxton")
                .withMouseMode(MouseMode.CELL_MOTION)
                .withProgress(TerminalPresentation.Progress.normal(40))
                .withStatus(TerminalPresentation.Status.working("braxton", "Rendering")),
        Views.column(statusRows(), content()));
```

| Field | Meaning | Absent means |
| --- | --- | --- |
| `alternateScreen` | which screen buffer the frames are drawn on | the session's `TerminalOptions` |
| `mouseMode` | how much pointer input the terminal reports | the session's `TerminalOptions` |
| `bracketedPaste` | whether paste is bracketed | the session's `TerminalOptions` |
| `focusReporting` | whether focus changes are reported | the session's `TerminalOptions` |
| `title` | the window title | no title from this session |
| `foreground`, `background`, `cursorColor` | the terminal's own default colours | the terminal's configured colours |
| `progress` | a taskbar progress value: none, normal, error, indeterminate, paused | no progress |
| `status` | a program-status record: idle, working, blocked, done, error, clear | no record |

A session mode left out keeps the option the session opened with, so an application that declares no
presentation has exactly the behaviour it had before this existed. Metadata left out means the
session presents none, so a title is popped, a colour default is reset, a progress value is removed
and a status record is cleared. The intent is a value: it is as terminal-free as any other view data,
so it can be built, compared and asserted in a test, and `View.Sized` subtrees are not part of the
tree a frame declares it in.

One frame declares one presentation. A second, different declaration is a `ComponentFailure` in the
`VIEW` phase rather than a value chosen by paint order, because a widget that changed the window
title or the screen buffer would otherwise win or lose by where it happened to be painted. The
wrapper is transparent to layout and to routing: it reserves no space, paints only its child, and
does not stop the scene's own bindings from matching when nothing is focused.

The runtime diffs the declaration against what it last applied and writes only what changed, inside
the same synchronized update as the cells, so a frame that asks for nothing new writes nothing at
all. A frame that changes the screen buffer repaints in full, because the other buffer holds
different cells. The exit path gives back everything the session changed: the pointer, paste and
focus modes, the popped title, the reset colour defaults, the removed progress value and the cleared
status record.

The wire forms are the documented ones:

- `OSC 2` sets the title, and `CSI 22 ; 0 t` / `CSI 23 ; 0 t` (XTPUSHTITLE/XTPOPTITLE) save and
  restore the title the shell had. A title is bounded to 4,096 UTF-8 bytes on a grapheme boundary and
  every control character is removed, so a title cannot end the sequence it is written into and start
  another.
- `OSC 10`, `OSC 11` and `OSC 12` set the terminal's default text, background and cursor colours, and
  `OSC 110`, `OSC 111` and `OSC 112` reset them to the terminal's own configuration. Braxton resets
  rather than restoring a captured value: the reset command needs no round trip and is exact on a
  terminal whose user customized its palette.
- `OSC 9 ; 4 ; state ; value` is the taskbar progress; state 0 removes it, 1 is normal, 2 is an
  error, 3 is indeterminate and 4 is paused.
- `OSC 7501` is the [Program Status Protocol](https://www.superlogical.com/rex/docs/build/program-status):
  a report replaces the record it addresses, free text is base64 UTF-8, a blocked record names why it
  is waiting and a record can carry its own percentage. A finished `done` or `error` record is left
  behind when the session ends - that is what the protocol keeps for the user to find - while a run
  in progress is cleared. `TerminalQuery.ProgramStatus()` asks whether the terminal keeps records at
  all and which states and kinds it accepts; a terminal that implements nothing answers nothing, and
  the application reports the silence rather than guessing.

Nothing here assumes support. A terminal that does not implement a sequence ignores it, so a declared
progress value or status record costs nothing on a terminal that cannot show it, and the text a frame
draws is never affected by a terminal that cannot present it.

## Process handoff and suspend/resume

Two operations need the session to stop owning its terminal: running another program in it, and
letting the shell have it back while the job is suspended.

```java
case Msg.OpenEditor open -> Update.next(
        model,
        Command.exec(
                ProcessRequest.of("vim", "notes.txt")
                        .withEnvironment(Map.of("EDITOR_VISIT", "1"))
                        .withDirectory(Path.of(".")),
                Msg.EditorFinished::new));

case Msg.SuspendRequested requested -> Update.next(model, Command.suspend(Msg.Suspended::new));
```

Before the child starts, the runtime stops its input reader, gives back every mode the session
applied (pointer, paste, focus, the window title, the terminal's default colours, progress, status,
the alternate screen), writes the same restoration the exit path writes, and restores the terminal's
own input configuration. The child then inherits the device - its own stdin, stdout and stderr - and
is the only consumer of it. Nothing is presented while the terminal is released, so no frame can
land on top of the program that owns the screen. Timed effects keep their monotonic periods; the
interval ticks that elapse meanwhile are coalesced, and one tick per interval is delivered on resume
rather than a burst.

When the child exits, the runtime takes the terminal back, re-applies the session's modes, measures
the size again (the child may have resized the window), and repaints in full, because the cells that
were on the other screen buffer are gone. Only then is the completion delivered - exactly one
message, in the asking component's scope:

| Result | Meaning |
| --- | --- |
| `ProcessResult.Exited(status)` | the child ran and exited; a signal-ended child reports `128 + signal` |
| `ProcessResult.Failed(cause)` | the child could not be started, or its status could not be read |
| `ProcessResult.Rejected(reason)` | the session declined: another handoff is running, or it is closing |

`ProcessRequest` is a value: the argv list (never a shell), environment entries added to the
session's own, and a working directory. A second handoff is refused rather than queued, because one
terminal has one owner. A session that cannot take its terminal back - the device is gone, or the
modes cannot be written - ends the run with `app.TerminalRecoveryException` instead of reporting a
completion: it no longer knows what is on the screen, so painting would be a guess. On shutdown,
including a termination signal, a child the session handed the terminal to is ended gracefully and
then forcibly after `RuntimeOptions.shutdownGrace()`.

`Command.suspend(...)` stops the session's process group only when that group owns the terminal's
foreground; anything else is `SuspendResult.Refused`, because a stopped background job has nobody to
continue it. The terminal is given back before the stop and taken again after the continue, so `fg`
finds the shell's own configuration and screen. `SuspendResult.Resumed` is the resume notification:
the frame painted next is the resumed screen, at the size measured after the resume. A job-control
call that fails after the terminal was released reports `SuspendResult.Failed`, and the session takes
its terminal back. Nothing in `Command` exposes a terminal handle, a process or a wait; the value is
inert until the runtime interprets it.

The demo in `examples.parity.ProcessHandoffDemo` runs itself as its own child (`--helper`), types a
line in the cooked terminal, and keeps its draft across the handoff: `e` runs the helper, `ctrl+z`
suspends, `q` quits.

## Command ordering and application control

Two things a terminal program usually ends up needing: effects that happen one after another, and a
way to drive the run from outside it.

`Command.batch` starts everything at once. `Command.sequence` waits:

```java
case Msg.SaveAndExit save -> Update.next(
        model,
        Command.sequence(
                Command.task(EffectId.of("write"), () -> store.write(model.document()), Msg.Saved::new,
                        Msg.SaveFailed::new),
                Command.delay(EffectId.of("pause"), Duration.ofMillis(200), new Msg.Settled(), new Msg.Settled()),
                Command.quit()));
```

A step finishes when its work is done and its message has been handed to the runtime: a task when its
success or failure message is enqueued, a delay when it delivers, a query when its answer arrives, a
batch when every member has finished, and a command that produces no message once it has taken effect.
Step N+1 is submitted at that point, so its messages are delivered after step N's - not after the
reducer has seen them. A sequence that must observe the model its own step produced says so with
`Command.afterUpdate(...)`, which finishes only once the transition its message caused has committed.

A sequence stops where it stands, running nothing further, when a step never finishes because it was
cancelled, when the component its steps belong to is removed, or when a step ends the program. The
remaining steps belong to work the application has already invalidated, so they are discarded rather
than run late. A sequence holds no thread, no timer and no registration of its own: an idle step costs
exactly what that step's own effect costs.

The same run can be controlled from outside it. `Application.start(component, options)` puts the
runtime on its own thread and returns a `ProgramHandle`:

```java
try (var inbox = queue) {
    ProgramHandle<Model, Msg> program = Application.start(new Scene());
    inbox.onMessage(text -> program.send(new Msg.Received(text)));
    ProgramResult<Model> result = program.await();
    report(result.model(), result.exit());
}
```

| Handle method | Behavior |
| --- | --- |
| `send(message)` | Delivers a message, waiting while the mailbox is full; `false` once the run has ended |
| `trySend(message)` | Never waits: `ACCEPTED`, `FULL`, or `CLOSED` |
| `quit()` / `quit(status)` | Asks for a graceful quit: a final update, a pending frame, then the ordinary cleanup |
| `interrupt()` | Forced end: nothing further is delivered or painted, cleanup is still bounded |
| `await()` | Blocks for `ProgramResult`: the final model and the `ExitResult` |
| `await(timeout)` | The same, or empty when the run is still going |

Messages sent before the runtime has opened its terminal are held in the mailbox it already owns and
delivered once it is ready, so a producer does not have to wait for a start it cannot observe. The
handle never exposes the terminal, the effect executor or the mailbox itself; it can only ask. An
`await()` from the runtime's own thread - from inside `update`, a command's mapper or a source - would
wait for the thread that is running it, so it throws instead of deadlocking. Interrupting the awaiting
thread ends the run and still returns the outcome.

`RuntimeOptions.withMessageFilter(...)` puts an application's own `MessageFilter` between the runtime
and `update`. It runs on the runtime thread, once per application message, after decoding and routing
and before the root update, and returns the message unchanged, a different message, or `null` to drop
it. Input decoding, resize, terminal replies and the runtime's own lifecycle stay outside it. It also
sees a `QuitRequest` for every graceful quit - from `Command.quit()` or from `handle.quit()` - and
returning `null` for one vetoes it, which is how an application guards an exit that needs confirming.
Forced termination never asks.

| Option | Effect |
| --- | --- |
| `withMessageFilter(filter)` | Rewrite or drop delivered messages, veto a graceful quit |
| `withFps(1..240)` | Presentation ceiling; application changes share frames at that rate |
| `withFailurePropagation(true)` | A failed run throws its failure after cleanup instead of returning it |
| `withShutdownHook(false)` | Do not install the run's own terminal-restoration hook; a host that owns restoration turns it off |

A filter is application code with no access to the runtime: it must not call `send`, `quit`, `update`
or a terminal, and it must not block, because it runs inside the turn that is delivering the message. A
filter that throws fails the run after cleanup rather than silently delivering or dropping the message.
The phase-22 demo in `examples.parity.ApplicationControlDemo` shows all of it: an owned producer that
sends progress through the handle, a sequence whose order the status row records, and a quit that is
declined until it is confirmed.

## Inline output, streaming and headless I/O

A run normally owns the process's terminal: it opens the device, reads it, paints the alternate
screen and gives it back. `IoOptions` is the supported alternative for the three other shapes a
terminal program has - a live region on the normal screen, a session over streams somebody else owns,
and a run with no terminal at all.

### Streams you own

```java
IoOptions io = IoOptions.defaults()
        .withStreams(reader, writer)      // UTF-8 text in and out; never closed by the runtime
        .withEnvironment(Map.of("NO_COLOR", "1"))
        .withSize(24, 80);

ExitResult result = Application.run(new Scene(), RuntimeOptions.defaults(), io);
```

| Choice | Effect |
| --- | --- |
| `withStreams(reader, writer)` | Read and write these endpoints instead of the process terminal |
| `withInput(...)` / `withOutput(...)` | Supply one end; the other discards or reports end-of-input |
| `withEnvironment(map)` | Values this session reads instead of the process's own, e.g. `NO_COLOR` |
| `withSize(rows, cols)` | The size to lay out for; a stream has no geometry to measure |
| `withRenderer(false)` | Paint nothing at all: no frame, no mode, no escape sequence |
| `withDiagnostics(sink)` | The caller-owned sink the runtime reports diagnostics to |

Nothing about a supplied stream is implicit. No native library is loaded, no device configuration
changes, and the streams are flushed but never closed - the caller decides when they end. A supplied
stream is not a VT, so it negotiates no alternate screen, no pointer reporting, no bracketed paste and
no colour: the session renders inline and asks for colour only through
`RuntimeOptions.withColorProfile(...)`. Input and output are one decision, so a half pair is rejected
rather than silently mixing a supplied reader with the device.

A renderer-disabled session is the piped or batch shape: it writes no terminal output at all, and it
still runs the whole program - input decoding, effects, sources, the message filter and cleanup - so
the model it ends with is the result. `Application.start(component, io)` returns that model in a
`ProgramResult`, which is how a batch run reports what it did.

`withDiagnostics(sink)` is the session's log. The library creates no log file, writes no diagnostic
onto the terminal it is drawing on, and a sink that throws cannot fail the run: a report about the
session is not part of it.

### A live region on the normal screen

`TerminalOptions.withAlternateScreen(false)` paints a managed **live region** instead of a full
screen. Everything it writes is relative:

```java
RuntimeOptions options = RuntimeOptions.defaults()
        .withTerminal(TerminalOptions.DEFAULT.withAlternateScreen(false));
Application.run(new Installer(), options);
```

The region begins at whatever row the session started on and moves with the terminal as it scrolls,
which is why no row is ever addressed absolutely. A frame returns to the region's first row, erases
forward from there, and repaints its own rows - so nothing above the region is ever touched, and a
view that got shorter clears its old rows instead of leaving the tail of the previous frame. Resizing
repaints the same way, and an unchanged frame writes nothing at all.

The session leaves its final frame on screen on the way out and puts the cursor on the line below it,
where the shell's prompt belongs.

### Durable output above the live region

```java
case Msg.Installed installed -> Update.next(
        model.installed(installed.name()),
        Command.println("installed " + installed.name()));
```

`Command.println` writes into the terminal's own content, above the live region, so the line scrolls
away with everything else and later frames never paint over it. The managed region is erased, the
text is written where it began, and the region is repainted below it in the *same* write, so a
terminal that renders a synchronized update atomically never shows a gap between them. Ordering is
automatic: printing happens on the runtime thread between commands, so no task can interleave with a
frame.

Where the line goes is defined rather than left to the terminal:

| Session | `Command.println` |
| --- | --- |
| Inline | The line becomes terminal content above the repainted region |
| Alternate screen | An explicit no-op: there is no scrollback to write into |
| Renderer disabled | The text is written to the session's output as plain text |

Text is normalized on the way out: `CRLF` and a bare `CR` both end a line, and every control
character except tab and newline is dropped, because durable output is the one path where an
application's own bytes reach the terminal beside a frame the session is managing. Text is line
granular by design - a live region can only be anchored at the start of a line - so an empty text
prints nothing and there is no newline-less variant.

The phase-23 demo in `examples.parity.InlineIoDemo` shows all three modes: an installer whose finished
packages become durable lines above its progress region, a piped run that treats stdin as application
data, and a headless run over local fixtures.

## The integrated showcase

`examples.parity.Showcase` is one executable over every completed area. It is a catalog whose
entries are the phase demos themselves: choosing one mounts that phase's own scene component as the
showcase's child, and leaving it is the unmount.

```bash
./run-demo.sh
```

| Key | Action |
| --- | --- |
| `↑`/`k`, `↓`/`j` | move the highlight |
| `home`, `end` | first and last entry |
| `enter` | open the highlighted entry |
| click, double click | select an entry, then open it |
| `ctrl+c` | return to the catalog |
| `?` | switch between the list and the grouped help |
| `q`, `ctrl+c` | quit, from the catalog |

Two composition rules are what the showcase demonstrates about the framework rather than about a
widget:

- **One scene is alive at a time.** `children()` declares the open scene's node and nothing else, so
  closing a scene is the documented unmount: its scoped subscriptions and commands are cancelled and
  its focus elements are released. A timer that a closed scene started cannot paint over the catalog.
- **The catalog captures only what a scene cannot need.** Returning is `ctrl+c`; every letter
  a scene might type stays the scene's, and a demo's own quit gesture still ends the session, because
  a quit request is the runtime's and an application cannot turn it back into a message.

The composed workspace (`examples.parity.WorkspaceDemo`, entry `24`) is the other half of the
acceptance: a file picker driving a Markdown projection, a find field driving that projection's
match ranges, a validated note draft, a task table whose updates move an animated progress bar, a
countdown on the runtime's own clock, and a modal confirmation over all of it. Each part is a shipped
widget mounted into its own slot of one model; what the scene adds is the wiring between them. It is
described in [the parity examples record](examples/parity/README.md#phase-24-workspacedemo).

The reference-to-phase mapping - every behaviour the reference example corpus demonstrates, and the
deliberate differences - is [the parity record](examples/parity/PARITY.md). `ParityAcceptanceTest`
reads that file and fails when a row names a demo, test or scenario that does not exist.

## Runtime and terminal

`Application.run(component)` owns a native POSIX terminal, raw mode, alternate-screen/cursor,
paste/mouse modes, input, sizing, and cleanup. It requires an attached process terminal and explicit
native access. It returns a structured failure when those prerequisites are missing. The one supported
alternative is explicit: passing an `IoOptions` with streams (or with the renderer off) runs the same
runtime over endpoints the caller owns, opening no device. There is no Braxton terminal
backend/session SPI, alternate provider, or implicit `/dev/tty`/plain-output fallback - a run either
opened the device it was given or was handed its bytes. Closing a run stops its input consumer before
terminal restoration and release; shared process streams remain available for subsequent runs.

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
| Terminal query reply deadline / live queries / reply payload | 1 s / 16 / 4,096 characters |
| OSC 52 write payload / read payload / read reply characters | 64 KiB / 1 MiB / 1,398,106 characters |
| Presentation ceiling | 60 frames per second (min 1, max 240) |
| Message filter / failure propagation / shutdown hook | none / returned in `ExitResult` / installed |
| Session I/O / renderer / stream size | owned terminal / painted / 24×80 |
| Window title / status message, in UTF-8 bytes | 4,096 / 2,048 |
| Status record id / id segment / id depth / program identifier | 128 / 32 / 8 / 32 characters |
| Shutdown grace | 2 s |

`ExitResult` distinguishes a requested quit, clean end-of-input, caller interruption (status 130), and failure, and carries cleanup
failures without hiding the primary cause. Rendering is event-driven: an idle application does no
work, and an interval subscription drives animation only while it is needed.

Application messages update the model, commands, and subscriptions in order. Their dirty screens
share presentations paced to `RuntimeOptions.fps()` - 60 frames per second, so at most one about every
16 ms, by default - using the mailbox's real monotonic wait clock.
A pending screen is painted even if no further message arrives; continuous traffic cannot defer
it indefinitely. Input and resize request immediate presentation, and input bindings are rebuilt
from the latest committed model before routing. Orderly quit/end-of-input flushes a pending screen.
An unchanged model does not request a frame. Pacing adds no rendering thread or recurring idle timer.

Each nonempty frame is sent as one synchronized-output transaction (DEC private mode 2026)
under the same output lock as clipboard and terminal controls. Supporting terminals can present
that completed frame together. On the alternate screen, terminals or multiplexers that ignore the
mode still receive replacement rows before their unused suffix is erased, avoiding a deliberate
clear-before-paint blank interval; full-width rows are not erased after painting, and cleared
suffixes use default attributes. An inline frame is different by design: it clears forward from its
own first row and repaints that region, because rows above it belong to the user. This does not
change frame cadence or add idle output, capability queries, or a public option, and it does not
promise atomic presentation on unsupported terminals. On output failure,
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

Braxton uses a virtual input worker and virtual threads for timer/effect work. The runtime
owner is the caller's thread. Terminal size is measured before presentation and at most every
250 ms while idle. A small native SIGWINCH handler increments a lock-free counter, allowing
the owner to notice even a shrink/grow that returns to the previous dimensions and repaint
cells clipped during the shrink. The previous handler is restored on close. Native polling
is bounded; termination signals remain with the JVM, whose shutdown hook restores modes.

Input uses a timed UTF-16 reader backed by incremental JDK UTF-8 decoding. Timeout is distinct
from EOF; partial characters survive timeouts and malformed or incomplete trailing sequences
produce replacement characters. Scalar assembly preserves supplementary characters; paste
limits count UTF-8-equivalent bytes of complete scalars. Input still passes through Braxton's
normalization and rendering sanitization.

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

The frame is a full agent-terminal layout: a pinned identity block over a rule, the scrollable
transcript, a queued-prompt band, one gap row, the status rule, the composer, a second rule, and a
four-row footer (project path, example source, provider/model identity, metrics). Every transcript
block is a `TYPE` header carrying the time the message entered the session, one blank row, and its
body, so prompts, reasoning, tool calls, notices, and failures are told apart by their label accent
rather than by a coloured panel.
The composer is flush-left, the status rule carries the spinner, elapsed time, and cancel hint, and
the metrics row ends with the exit-confirmation hint while one is armed.

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
Transient feedback (clipboard outcomes, a full queue, a rejected schedule, a stop) is appended to
the transcript as a `SYSTEM` notice, so it reads in order with the work it describes; the Ctrl+C
confirmation hint sits at the right edge of the metrics row for exactly the window it announces, so
cancelling it or missing the window also clears it.
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
| `io.github.dhoard.braxton.widget` | Editor, text input, markup, tables, lists, pickers, boxes, badges, bars, quotes, gradients, selectors, animation |
| `io.github.dhoard.braxton.asset` | FIGlet fonts, logos, image assets and views |
| `io.github.dhoard.braxton.clipboard` | Configuration, selections, sanitization, modes, copy and read results |
| `io.github.dhoard.braxton.internal` | Runtime loop, mailbox, effects, subscriptions, layout, cells, encoder, native terminal integration |

Dependencies point inward: examples depend on the public library; runtime/input internals use owned terminal services;
models, views, routing and layout remain independent of terminal I/O. `internal` packages are not a supported
API surface.

Native access follows one path: the runtime uses `internal.terminal.NativeTerminal`, which
calls the packaged POSIX C ABI. The input decoder sees only `TimedReader`; layout, rendering,
and public component APIs do not depend on native types. Input polling can run alongside
output and size queries. Closing a terminal waits for active native calls before freeing it.


## Design principles

- **State changes only through `update`.** Widget state is a value in the model; configuration is
  separate and immutable.
- **Effects are data with lifetimes.** Commands run once; subscriptions are reconciled,
  identity-guarded, and closed deterministically.
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
without native terminal access or a test-framework dependency.

```bash
./build.sh clean verify    # compile native and Java code with -Xlint:all, run the suite
./run-ai-harness.sh        # package and run the bundled example
./run-demo.sh              # package and run the integrated parity showcase
```

The build must stay warning-free under `-Xlint:all`, the core must confine FFM access to internal terminal integration, and the test suite must remain independent of a TTY, stdin, and raw mode. A headless
test run does not replace a manual check of a real terminal session.

JaCoCo measures the suite during `verify` and writes its report to `target/site/jacoco/index.html`.
The coverage check keeps `AnsiEncoder` at 100% line and branch coverage and `RuntimeLoop` at or above
88% line and 74% branch coverage. Real-terminal opening and JVM shutdown cleanup also require
the opt-in PTY verifier, which exercises native access and owned terminal resources.

Spotless formats every Java source under `src/main/java` and `src/test/java` with Palantir Java
Format (version pinned in the POM) and enforces the MIT license headers during `verify`:

```bash
./mvnw spotless:apply
./mvnw spotless:check
```

The developer probes under `tools/`
are also outside the formatter's include set, because they are compiled ad hoc by the terminal
verifier rather than as part of the build.

The generated Unicode column-width table is formatter-clean and reproducible from the pinned data
under `tools/unicode/16.0.0`. Regenerate it only through its script, and check it before a release:

```bash
python3 tools/generate_unicode_widths.py           # rewrite the table
python3 tools/generate_unicode_widths.py --check   # verify it matches the generator
```

The check needs only the Python standard library, so it is not part of `./build.sh`;
`Unicode16WidthReproducibilityTest` independently re-derives the wide set from the same pinned
files during `verify`, so a hand edit or stale data fails the normal gate.

Dependency- and plugin-update reports honor the pre-release filter in `versions-rules.xml`:

```bash
./mvnw versions:display-dependency-updates
./mvnw versions:display-plugin-updates
```

### Rendering resource limit

Rendering rejects a screen above **4,194,304 cells** with `IllegalArgumentException` before
allocating its cell arrays. Both dimensions are widened before multiplication; each zero-sized
axis counts as one for this limit, so an empty screen cannot still request billions of row
iterations. This applies to live and headless rendering and prevents overflowing dimensions
from becoming undersized arrays or uncontrolled allocations. Ordinary terminal geometry is
unchanged; oversized live geometry fails through the runtime's normal cleanup path.

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

`ScrollViewBenchmark` separates indexed-content append/scroll, paint/encode, unchanged-frame
diffing, and list-snapshot publication at 10k, 100k and 1M display rows. It varies visible height
independently of history. `HarnessProjectionBenchmark` measures the example's application-level
projection separately: it still wraps retained history on each projection, but styles and pads
only requested rows. Do not confuse this example cost with `ScrollView`'s indexed-content
contract, or `Content.of`'s explicit O(history) publication copy with viewport painting.

A benchmark delta is not by itself a reason to change the library. A performance change needs a
focused scope, a deterministic in-repo oracle that fails before the change and passes after it,
the full project gate, and a terminal-emulation check when TUI code is touched.

## Compatibility

| Platform | Status |
| --- | --- |
| Linux / glibc x86_64 | Native build target; verification results recorded below |
| Linux ARM64 | Native build target; execution pending on an ARM64 host |
| macOS ARM64 / aarch64 | Native build target; verified on macOS 27 arm64 with JDK 25.0.5 ([record](src/main/native/README.md#macos-arm64)) |
| Windows, macOS x86_64, other combinations | Unsupported |
| Java | 25 or newer |

Interactive runs require `--enable-native-access=ALL-UNNAMED` on the classpath, or
`--enable-native-access=io.github.dhoard.braxton` on the module path. Permission is checked
before native initialization. The library first extracts into a private directory under
`java.io.tmpdir`, loads the binary, and removes its temporary copy. That directory is created
exclusively with an unpredictable name taken from `/dev/urandom` and an owner-only mode applied at
creation, so instances and different users sharing a temporary directory never collide or adopt
one another's entry, and no `SecureRandom` (and therefore no JCA provider) is initialized to name
it. The temporary copy is not flushed to stable storage, because it is removed moments after it is
loaded. If extraction or loading
fails there (including a `noexec` filesystem), it falls back to
`user.home/.local/share/braxton/native/<version>/<platform>/<content-hash>/libbraxton.so`
(`libbraxton.dylib` on macOS). `user.home` is the JVM property, independent of the `HOME`
environment variable.

The cache uses the embedded Braxton version and the SHA-256 that Maven records beside the compiled
binary, so separate releases and different builds of one release coexist. Nothing is hashed while
the application starts: the recorded value only names the cache entry, and an existing entry is
reused only when its bytes match the packaged image exactly. Concurrent first starts use
private staging files, a process lock, and atomic publication; published libraries are never
modified in place. Existing cached bytes are checked before reuse, and corrupted regular files
are repaired atomically. Managed entries reject symlinks and permissions that allow other
users to write them. Old versions and shared lock files remain in the cache; a running
application never removes another application's library. If both locations fail, the error
retains both causes. The core's one third-party runtime Java dependency is the CommonMark parser
behind `Markdown` - `org.commonmark:commonmark` with its official GFM extension modules and the
autolink library they use - and no parser type appears in a public API. Every other dependency is
example-specific or build-only: the AI Harness additionally requires its optional Gson dependencies,
and they are not on the core's classpath.

An attached process terminal on stdin and stdout and a recognized VT-compatible `TERM` are
required. There is no `/dev/tty` fallback or terminfo database dependency. Capabilities are
inferred conservatively from common VT terminal names; unset, dumb and unknown terminal types
are rejected. Linux console and basic VT types disable optional paste/mouse/alternate-screen
features. Process termination through SIGKILL cannot guarantee restoration.

### Native terminal verification

Default tests use owned streams without native access or a TTY. Developer verification uses
owned PTYs and pyte to check the packaged runtime's Unicode/control/paste/wheel input, resizing,
frame ordering, wide glyphs, repeated sessions, cancellation, cleanup, JVM shutdown and SIGTERM.

```bash
./build.sh
python3 tools/terminal/verify_terminal.py --repeat 10 --pending-escape --harness
python3 tools/terminal/verify_terminal.py --parity-phase 01
python3 tools/terminal/verify_terminal.py --parity-phase 02
python3 tools/terminal/verify_terminal.py --parity-phase 03
python3 tools/terminal/verify_terminal.py --parity-phase 04
python3 tools/terminal/verify_terminal.py --parity-phase 05
python3 tools/terminal/verify_terminal.py --parity-phase 06
python3 tools/terminal/verify_terminal.py --parity-phase 07
python3 tools/terminal/verify_terminal.py --parity-phase 08
python3 tools/terminal/verify_terminal.py --parity-phase 09
python3 tools/terminal/verify_terminal.py --parity-phase 10
python3 tools/terminal/verify_terminal.py --parity-phase 11
python3 tools/terminal/verify_terminal.py --parity-phase 12
python3 tools/terminal/verify_terminal.py --parity-phase 13
python3 tools/terminal/verify_terminal.py --parity-phase 14
python3 tools/terminal/verify_terminal.py --parity-phase 15
python3 tools/terminal/verify_terminal.py --parity-phase 16
python3 tools/terminal/verify_terminal.py --parity-phase 17
python3 tools/terminal/verify_terminal.py --parity-phase 18
python3 tools/terminal/verify_terminal.py --parity-phase 19
python3 tools/terminal/verify_terminal.py --parity-phase 20
python3 tools/terminal/verify_terminal.py --parity-phase 21
python3 tools/terminal/verify_terminal.py --parity-phase 22
python3 tools/terminal/verify_terminal.py --parity-phase 23
python3 tools/terminal/verify_terminal.py --parity-phase 24
python3 tools/terminal/verify_terminal.py --parity-all --repeat 10 --pending-escape --harness
python3 tools/terminal/verify_native_lifecycle.py target/braxton-0.1.0.jar
python3 tools/terminal/verify_native_extraction.py target/braxton-0.1.0.jar
# Linux only; requires unprivileged user/mount namespaces:
python3 tools/terminal/verify_native_extraction.py target/braxton-0.1.0.jar --noexec
```

`--parity-phase NN` runs one implemented parity phase's scenario and the packaged demo launch
instead of the full battery; the demos live under `examples/parity` and are listed in
[the parity examples record](examples/parity/README.md). `--parity-all` adds every phase's scenario
and packaged launch to the battery in one invocation, which is the integrated gate: the phases pass
individually first, and this proves they still pass together. The reference-to-phase mapping, with
its deliberate differences, is [the parity record](examples/parity/PARITY.md).

The verifier compiles its Java probes in a temporary directory and runs the packaged jar.
Python with `pyte` and Java/javac on PATH are required. Run it on each target before claiming
that platform is verified. Results and remaining gaps are recorded in
[the native verification record](src/main/native/README.md#verification).

### Source migration to 0.1.0

`0.1.0` is an intentional source/binary break, with no adapters, aliases or deprecated forwarders.
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
- A `KeyAction` alias matches its gesture exactly, modifiers included. A widget's default shortcut
  therefore does not fire when an unlisted modifier accompanies the key; the editor and the
  selectors keep the `alt+enter`/`alt+backspace` forms the decoder produces, and any other form has
  to be listed as an alias.
- Pointer input is opt-in and depends on the terminal. A mode is requested, never assumed: a
terminal that ignores the request keeps sending legacy input, and an application that binds no
pointer handler sees exactly what it saw before. A wheel gesture no element consumes still becomes
`wheelup`/`wheeldown`, so legacy wheel bindings keep working.
- Pointer reports name three buttons and four wheel directions. A terminal report that sets one of
the protocol's four extra buttons decodes to no event rather than to one of the three Braxton has,
and the legacy X10 byte form cannot name the button a release ends or address a column past 223 -
Braxton always asks for the SGR form, which can do both.
- Hit testing uses the committed frame, and regions are rectangles: a widget whose content is clipped
by a narrower ancestor still occupies the rectangle it was painted in, so a click on the clipped
cells of a list row selects the row. Hover only exists if the session asked for `ALL_MOTION`, and no
widget paints a hover state yet.
- A pointer report's observers see it wherever it lands, including inside a modal layer, and cannot
consume it; key routing still stops at the modal layer. A modal layer is therefore a route of its own
for keys and only a hit-testing boundary for the pointer.
- A double click is two presses of the same button at the same cell within 400 ms. There is no triple
click: a third press selects what the second one did, which is what every completed widget needs.
- The text widget's pointer support places the caret, extends a selection and selects the word under
a double click. It does not scroll a pane to follow a drag beyond the visible rows, does not
auto-scroll while dragging, and does not implement a terminal's own selection or clipboard
behaviour - `Msg.Copy` and the widget's outputs remain the only way text leaves a field.
- Enhanced keyboard input is opt-in and depends on the terminal. A terminal that does not implement
the Kitty keyboard protocol keeps sending legacy input, which cannot distinguish Enter from
Shift+Enter, Tab from Ctrl+I, or report a release; nothing is inferred from `TERM` to guess
otherwise. Of the protocol's modifiers only Shift, Alt and Ctrl are carried: a chord that additionally
sets Super, Hyper or Meta decodes to no event rather than to a plain key, because delivering it as one
would run a binding or insert text for a gesture the user aimed at the terminal. Alternative key codes
(the shifted and base-layout characters) are decoded for the text they supply, not exposed as
separate fields.
- Whether a terminal answers an OSC 52 read is not something this library can detect, and reading
was **not** verified against a physical terminal or a tmux session on the host this phase was tested
on. The reply deadline is the only bound: a terminal that ignores the question - including one that
forwards writes but not queries - produces `Timeout`, and an answer of `?` produces `Refused`. A tmux
passthrough session wraps the query the way it wraps a write, but whether tmux returns the terminal's
answer to the pane depends on the tmux release and its `allow-passthrough` setting, so a read inside
tmux may time out whatever the outer terminal supports. Only the system (`c`) and primary (`p`)
selections are addressable; the protocol's numbered selections and `s` are not exposed.
- A handoff runs on the runtime owner's thread: the application is paused for as long as the child
runs, which is what makes it the only consumer of the terminal. Mail and effect results produced
meanwhile are bounded by the mailbox and the effect quota rather than unbounded, and a producer
blocks instead of dropping a result. A child that is still running when the process is forcibly
killed is not terminated by anything here; a normal shutdown or a termination signal ends it.
- A suspended process group cannot act while it is stopped. A termination signal received during a
suspend is handled only after the job is continued, and a `SIGKILL` takes effect immediately, which
leaves the terminal in the shell's own configuration but with the session's screen still painted.
- Suspend requires an owned POSIX terminal that is the process group's foreground. A session whose
  terminal is supplied (a stream, a test fixture) refuses with `SuspendResult.Refused`, and Windows
  is unsupported.
- Handoff and suspend were verified on Linux x86_64 only. The PTY scenario proves the cooked-mode
  handover, the preserved draft and a real `SIGTSTP`/`SIGCONT` cycle under a session it creates; a
  physical terminal and macOS were not exercised for this phase.
- A sequence orders completion, not acknowledgment: step N+1 is submitted when step N's message has
  been handed to the runtime, and the reducer may not have seen it yet. `Command.afterUpdate` is the
  continuation that waits for the transition; there is no option that makes every step wait, because
  one sequence would then pay for a reducer round trip it may not need.
- A step that is cancelled, or whose component is removed, finishes nothing, so the steps after it
  never run. That is deliberate - the rest of the sequence belongs to work the application has
  invalidated - but it means a sequence is not a guarantee that every step runs, only that no step
  runs out of order.
- A sequence step scoped to a component that was removed before the step was submitted fails the run,
  like any other command addressed to a component that no longer exists. The sequence stops before
  submitting such a step only when the scope it was started from is the one that went away.
- A `MessageFilter` runs on the runtime's owner thread inside the turn that delivers the message, so a
  slow filter slows the application and a blocking one stops it. Its own state - a confirmation guard,
  typically - lives outside the model, so nothing in the runtime reduces, snapshots or replays it; a
  filter rebuilt between runs starts from its own defaults.
- The filter sees application messages, not raw input, resize, terminal replies or the runtime's own
  lifecycle, so it cannot drop a key before routing or a reply before the query engine decodes it.
  Transforming a message into another type is not checked: a filter that returns the wrong message
  type fails the program's own `update`.
- A graceful quit can be declined, but a declined request is not retried: the application sees its own
  message and asks again if it wants to. `ProgramHandle.interrupt()` is the only path that cannot be
  vetoed, and it ends the run with `ExitResult.Kind.INTERRUPTED` (status 130) rather than a quit status.
- `ProgramHandle.await()` reads the final model after the runtime has stopped; a run whose `init` never
  committed - the terminal could not be opened, or a stop arrived that early - reports no model.
- The presentation ceiling is a ceiling, not a schedule: input, resize and an orderly exit present at
  once, and an idle application presents nothing at all. Lowering `fps` coalesces more application
  changes into one frame; it does not make the session repaint on a timer.
- Command ordering and application control were verified on Linux x86_64 only. The PTY scenario proves
  the sequence order, the external sends and the guarded quit; macOS was not exercised for this phase.
- An inline live region is a region the runtime can only account for while it is the only writer below
  its first row. A background job, a shell hook or another process that writes to the same terminal
  while a session is running corrupts it: relative moves still reach the right rows, but the cells
  themselves were changed by somebody else. The alternate screen is unaffected, because nothing else
  draws on it. Fixing this would need a terminal that reports its cursor back, which not every VT does.
- Durable output is line granular. A live region can only be anchored at the start of a line, so there
  is no newline-less print, and text is normalized before it is written: `CRLF` and `CR` become `LF`, and
  control characters other than tab and newline are dropped. An escape sequence the caller embeds
  therefore reaches the terminal as inert text (`[31m`), never as a sequence.
- A supplied stream is a byte source and sink, not a VT: a stream session negotiates no alternate
  screen, pointer reporting, bracketed paste or colour, and `TerminalOptions` modes it cannot honour are
  gated off rather than attempted. Alternate-screen and pointer sessions need the owned terminal.
- A stream session's size is the one it was given. It changes only when a host reports a new one
  explicitly; nothing measures a pipe, and a stream has no window to resize.
- The renderer-disabled mode still decodes the session's input as terminal input. A piped run that wants
  stdin as application data reads it itself - the phase-23 demo does exactly that - rather than passing
  it to a session as keys.
- Inline output, streaming and headless I/O were verified on Linux x86_64 only; macOS was not exercised
  for this phase. The PTY scenario proves the live region, the durable lines above it, a preserved
  screen above both, and the piped and headless launches; the pyte cell assertions and the raw
  transcripts are the evidence, and a physical terminal was not exercised.
- Focus reporting and keyboard enhancement modes are session options, not a per-frame request, so
changing them means starting a session with different `TerminalOptions`.
- A declared caret (`Editor.Caret.NATIVE` or `Editor.Caret.VIRTUAL`) is placed from the same visual-row
projection the editor paints, so it lands on the cell the painted block would cover - wrapped rows
included. It does not skip a two-column glyph's second cell the way a terminal cannot address it: the
layout moves a caret declared on such a cell back to the glyph it belongs to.
- `Config.withWrapWidth` is the width vertical movement, the caret and a dynamic height are computed
for, and the space a view is actually given only wins when it is narrower. A caller that leaves the
declared width behind after a resize gets rows that still fit the pane but vertical movement aimed at
the old width; derive it from the viewport. Without a declared width the editor paints one row per
logical line and lets the text view wrap it, which is the pre-existing behaviour.
- A wrapped row is as wide as it may be, so a tab stop that would reach past the row's last column is
painted as the spaces the row can hold instead of the columns the tab names. Every other tab stays the
character the text view expands to the same stop the arithmetic counted.
- The editor paints a virtual caret steadily; `Cursor.Mode.BLINK` on a virtual caret is the mode the
application asked for, and a scene that wants it to blink alternates it with its own model time, as
`CursorDemo` does.
- The terminal's own cursor shape and colour cannot be read back, so a session restores the DEC
default shape (`CSI 0 SP q`) and the configured cursor colour (`OSC 112`) rather than a style the
user had customized. Terminals that ignore `DECSCUSR` or `OSC 12` keep their own cursor and lose
nothing else: the position and visibility controls are the widely implemented part.
- `Cursor.Mode.BLINK` is the terminal's own blink for a native cursor. A virtual caret is one cell
  style, so a blinking one repaints on the application's clock, and content that is already reverse
  video - a selection, typically - looks unchanged under it.
- Widget padding - `Table` columns, `Quote` blocks, `ProgressBar` cells, `Markup` tables and list
  indents - is measured with the pinned width policy. A renderer built with another policy
  (`WidthPolicy.ONE_COLUMN`, or an application's own) applies to text wrapping and clipping, not
  to those computed widths, so a widget mixing wide and narrow content can look ragged under it.
- Cancellation skips task bodies that have not started on the executor. For already-started tasks
  it is cooperative: a task that ignores interruption is not forcibly stopped; its result is
  suppressed and shutdown waits only for the configured grace period. User task code is not
  sandboxed.
- `FilePicker` orders names case-insensitively by UTF-16 code point, never by a locale collation,
  so a name outside ASCII sorts where its characters do rather than where a reader's language would
  put it. A listing is read once per request and bounded by `Config.maxEntries`, and the entries it
  keeps are the first ones the filesystem returned before they were sorted; the status row reports
  truncation but a directory larger than the bound is not paged. An entry that cannot be read - a
  dangling link, or a target the process may not stat - is listed as `MISSING` rather than omitted.
  One entry is one painted row, so the metadata column is dropped before a name is cut, and a
  renderer that wants two rows an entry belongs in `RichList` instead. The picker reads `.`-prefixed
  names as its hidden entries; the platform's own hidden-file attribute is not consulted. A listing
  is a snapshot: an entry can be renamed, replaced or removed between being listed and being chosen,
  which is why a choice is re-checked before it is reported, and why a directory that changed under
  the reader shows the last listing until it is re-read.
- `ScrollView` is vertical and line-oriented. Content is published as pre-wrapped display rows; the
  pane clips a row that is too wide and does not reflow on resize, and there is no horizontal
  scrolling, scrollbar, selection, or mouse hit-testing.
- `TextViewport` wraps by cells, not by words: a row is broken at the last cell that fits, so a very
  long word is split, and an application that wants word wrapping prepares its own rows. Its
  `verticalPercent`, `firstVisibleRow` and `totalRows` walk the whole content, and a horizontal
  gesture measures the widest line, so those belong in a scrollbar or a status line rather than in a
  frame; a frame and a vertical gesture read a window. A panning
  viewport applies one offset to every line, so a short line scrolled past its end paints nothing -
  that is what one offset over one column means. The pane computes no matches and holds no search
  policy, and its gutter is a fixed number of cells, so a gutter that needs to grow with the
  content's line count is the application's decision at configuration time.
- `DataTable` scrolls and pans; it does not reflow. A column wider than the pane is panned rather than
  shortened, so a narrow terminal shows part of a row at a time. Its widths are resolved once from
  the content, which means `Msg.SetRows`, `Msg.Insert`, `Msg.Replace` and `Msg.Remove` measure the
  whole table and a frame, a selection move and a resize never measure a cell - an application that
  rewrites a row per keystroke pays one measurement pass per keystroke. Selection is one row or one
  cell, not a set; a cell selection keeps its cell on screen, so panning stops at the selected
  column. It sorts, filters and edits nothing, has no mouse hit-testing and no scrollbar, and a cell
  that does not fit is truncated rather than wrapped.
- `Markdown` renders and projects; it does not edit, fetch or execute anything. Raw HTML is literal
  text by design, an image is its alt text, and a link keeps a hyperlink target only for an allowed
  scheme. Its projection is bounded by `Limits` - 1 MiB of input, 100 000 nodes, 128 levels of
  nesting and one million cells - and a document over a bound paints its diagnostic instead of the
  rows it had. A paragraph is word-wrapped at `Config.width()`, a code block is broken at the cell
  that fits (so a very narrow pane splits a statement), and a table is fitted and truncated rather
  than wrapped, so a narrow pane shows the start of each cell. Bounds are ceilings: a definition can
  lower one and cannot raise it. The cache keeps four projections per renderer, so a session that
  resizes through many widths re-projects each time; the bundled highlighter covers Java, JSON and a
  POSIX shell only. The projection is also the unit the pager sees, so a resize re-projects and
  republishes content - the pager keeps its logical line, which is the nearest line at the new width
  rather than the same word.
- `Tabs` paints the bar and frames the page; it does not own pages, mount or unmount them, or move
  focus between a page's widgets - the application composes those and passes the page's view in. A
  composed bar keeps its focus element at the root scope, so one composition has one bar; the pages
  inside it are scoped by their own mounts. A bar narrower than its tabs windows around the shown one
  and marks each side it left tabs out on, so an application that needs every tab visible at once
  must paint its own bar. There is no mouse activation and no scrollable bar.
- `Table.width(...)` fits a static table's own columns and truncates what is left over; it does not
  wrap a cell's text. Each column keeps `Table.MIN_COLUMN` cells while the budget shrinks, and a
  budget too small for the frame's own two edges paints a truncated frame rather than one wider than
  the box it was given.
- `Paginator` is page state, not a list: it stores, filters and sorts nothing, and it does not move
  the page to follow a selection - the parent sends `Msg.GoTo`. Dots are windowed to
  `Appearance.maxDots()`, so a page outside the window is an ellipsis rather than a dot; an
  indicator narrower than one dot and its ellipsis paints the ellipsis, or nothing when not even
  that fits.
- `RichList` filters and pages one list and paints its own chrome: it holds no multi-select, no
  group headers and no inline editing, and it does not sort items beyond the fuzzy rank the query
  asks for. Its height comes from the terminal and `Config.reservedRows()`, so an application that
  paints a variable number of rows outside the list publishes the change with `Msg.RowsChanged`. A
  renderer's `height` is a function of the item alone - the same item is the same height wherever it
  lands - and matching is a linear scan of the item titles per paint, so a list of hundreds of
  thousands of items should publish a pre-narrowed list rather than one filter over everything.
- `Spinner` is presentation and timing: it owns no keys and no focus, so the application decides
  which preset is shown and when a spinner starts or stops. A definition's frames are painted as
  they are defined, so frames of different widths move whatever is painted after them; `rows()` and
  `columns()` report the largest box rather than padding to it. Frames may be multi-row, but a frame
  cannot contain a control character other than its own line breaks, and the interval runs at the
  frame cadence, so a spinner repaints once per frame instead of interpolating between them.
- `AnimatedProgress` eases one bar towards one target: it holds no queue and no history, so a target
  that arrives mid-ease replaces the trajectory it was on, and only the position and velocity carry
  over. Its response is one fixed critically damped shape whose response time is the only knob - the
  damping ratio, the settle threshold and the tick period the caller sets are not a spring the
  application tunes. A graded fill's glyphs are the widget's own eighth-block set, so a custom
  active icon is replaced at that one cell, and a blend's ramp is anchored to the bar rather than to
  the fill, so it spans as much of the ramp as the fill covers instead of rescaling with it.
- `Countdown` counts down only: it holds no queue, no laps and no expired history, so the
  application decides what a timeout means. A stop freezes the remainder at the last runtime reading,
  which is up to one tick period before the pause, because the gesture itself carries no timestamp;
  nothing accrues while it is paused. `Msg.Start` and `Msg.Resume` are separate because the first
  begins a run of the configured duration and the second continues one, and a `Msg.Toggle` collapsed
  into either would quietly lose the difference.
- `Stopwatch` measures one total: it holds no laps, no splits and no history, so the application
  decides what a reading of the total means. A `Msg.Stop` folds the current run in at the last
  runtime reading, which is up to one tick period before the gesture, because the gesture itself
  carries no timestamp; nothing accrues while it is stopped. The painted millisecond field is
  truncated, so a total smaller than one millisecond paints zero, and the display refreshes at the
  configured cadence rather than at the resolution it prints.
- `TextInput` scrolls horizontally and never wraps: a draft wider than the field is clipped to the
field's width, so a caller that leaves `Appearance.width` behind after a resize paints a row that no
longer fills the pane. Completion matches a UTF-16 prefix only - no substring, fuzzy or normalized
matching, and candidates are never sorted - and it never runs over a masked draft, because a ghost
suffix or a whole-text replacement would show what the mask hides. A caret at the end of a draft is
a cell of its own, so a field exactly as wide as its text scrolls one column to show it.
- One `ImageAsset` holds at most 4,194,304 pixels, the same bound the renderer puts on the cells it
  allocates. A file's dimensions are read from its header before any pixel is decoded, so a larger
  image fails with a diagnostic instead of allocating an unbounded raster; downsample it before
  loading.
- A terminal reply carries no request identity, so a session can only tell one answer of a kind
from another by not having two outstanding: a second query of the same kind waits for the first.
The one ambiguity that cannot be removed is the shape `CSI rows ; columns R`, which is both a
cursor-position report and one form a terminal can use for a modified function key; a session with a
cursor-position query outstanding reads it as the answer. No other reply shape overlaps a key.
- A colour reply is decoded from the numeric X11 forms - `rgb:r/g/b` with one to four hexadecimal
digits per channel, and the `#` forms with three, six or twelve digits. A terminal that answers with
a colour *name* is reported as malformed rather than mapped through an X11 name table this library
does not carry, and `rgba:` is accepted with its alpha ignored.
- `rgb:1/2/3` and `rgb:11/22/33` are the same colour: a short channel is scaled to eight bits, which
is what X11 specifies and what a terminal means by it.
- Terminal presentation is requested, never negotiated. A terminal that does not implement the
title push/pop sequences keeps the title the session set, because there is no portable way to read
it back or to ask for "no title"; a terminal that does not implement `OSC 7501` shows no status record
and one that does not implement `OSC 9;4` shows no progress. None of these can corrupt the frame: an
unimplemented sequence is ignored, and every payload is bounded and stripped of control characters
before it is written.
- `OSC 9` is also where iTerm2 reads desktop notifications, so a terminal that knows the notification
  form and not the progress form may show a progress value as a notification. Progress is opt-in per
  frame, and an application that targets such a terminal leaves it out.
- The default colours a frame sets are the terminal's own palette entries. A session that sets them
  changes how the terminal draws everything it paints itself - prompt included, once the session
  ends - until the reset, which is why the reset is written on the exit path even when the frame that
  set them is long gone.
- A program-status record the session clears on exit is its own record or, when it reported the root
  record, every record on the terminal: the protocol defines a root `clear` that way. An application
  that shares a terminal with another program's records declares an `id` so that clearing it is
  scoped to that record.
- The API is pre-1.0 and may change between minor releases.

## Contributing

Issues and pull requests are welcome at
[github.com/dhoard/braxton](https://github.com/dhoard/braxton).

Before opening a pull request:

1. Run `./build.sh clean verify` and keep the build warning-free.
2. Keep the automated suite TTY-free — no terminal, stdin, raw mode, or external services.
3. Preserve the public contracts: immutable values, typed messages, pure `update`/`view`, explicit
   effects, and structural routing.
4. Keep FFM integration inside the internal terminal package and native types out of the public MVU API.
   Keep the core's third-party dependencies to the declared ones - the CommonMark parser behind
   `Markdown`, named in `PackagingContractTest` - and keep every third-party type out of a public
   API; Gson remains example-specific.
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

Braxton is **pre-1.0** (`0.1.0`). The public API is stabilizing but may change between minor
releases; `internal` packages are never part of the supported surface. Releases follow semantic
versioning once 1.0 is reached.

## License and notices

Braxton's Java and native implementation are licensed under the [MIT License](LICENSE).
Bundled FIGlet fonts retain their BSD terms, and the Unicode column-width data retains
Unicode License V3. Their copyright notices, licenses, and provenance remain part of the
distribution. The optional example uses separately licensed dependencies; see [NOTICE](NOTICE).

## Acknowledgments

- The bundled FIGlet fonts come from the FIGlet project; per-font authorship and licensing are
  recorded in [NOTICE](NOTICE) and in each font header.
- The terminal column-width policy is generated from Unicode 16.0.0 data; the license and pinned
  provenance ship with the artifact under `META-INF/braxton/unicode-16.0.0/`.

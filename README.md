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
| `ScrollView` | Focusable vertical pane over application-owned display rows; the window and follow-end are model data, the content stays with the application |
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

`Application.run(component)` owns a native POSIX terminal, raw mode, alternate-screen/cursor,
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
| `io.github.dhoard.braxton.widget` | Editor, markup, tables, boxes, badges, bars, quotes, gradients, selectors, animation |
| `io.github.dhoard.braxton.asset` | FIGlet fonts, logos, image assets and views |
| `io.github.dhoard.braxton.clipboard` | Configuration, sanitization, modes, results |
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
without native terminal access or a test-framework dependency.

```bash
./build.sh clean verify    # compile native and Java code with -Xlint:all, run the suite
./run-ai-harness.sh        # package and run the bundled example
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
retains both causes. The core has no third-party runtime Java dependencies.
The AI Harness additionally requires its optional Gson dependencies.

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
python3 tools/terminal/verify_native_lifecycle.py target/braxton-0.1.0.jar
python3 tools/terminal/verify_native_extraction.py target/braxton-0.1.0.jar
# Linux only; requires unprivileged user/mount namespaces:
python3 tools/terminal/verify_native_extraction.py target/braxton-0.1.0.jar --noexec
```

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
- Mouse support covers wheel scrolling, not click-drag selection.
- Widget padding - `Table` columns, `Quote` blocks, `ProgressBar` cells, `Markup` tables and list
  indents - is measured with the pinned width policy. A renderer built with another policy
  (`WidthPolicy.ONE_COLUMN`, or an application's own) applies to text wrapping and clipping, not
  to those computed widths, so a widget mixing wide and narrow content can look ragged under it.
- Cancellation skips task bodies that have not started on the executor. For already-started tasks
  it is cooperative: a task that ignores interruption is not forcibly stopped; its result is
  suppressed and shutdown waits only for the configured grace period. User task code is not
  sandboxed.
- `ScrollView` is vertical and line-oriented. Content is published as pre-wrapped display rows; the
  pane clips a row that is too wide and does not reflow on resize, and there is no horizontal
  scrolling, scrollbar, selection, or mouse hit-testing.
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
   Keep the core free of third-party Java dependencies; Gson remains example-specific.
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

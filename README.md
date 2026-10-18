# Braxton

**A Model–Update–View (MVU) terminal UI framework for Java.**

[![Java](https://img.shields.io/badge/Java-25%2B-blue.svg)](#requirements)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Runtime dependencies](https://img.shields.io/badge/runtime%20dependencies-0-brightgreen.svg)](#design-principles)
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
- **Zero core runtime dependencies** — the library jar contains only Braxton classes and its fonts.

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
| Terminal | ANSI/VT-capable terminal; Unix sessions require `--enable-native-access=ALL-UNNAMED` |

Platform support is described in [Compatibility](#compatibility).

## Build from source

```bash
git clone https://github.com/dhoard/braxton.git
cd braxton

./mvnw clean verify     # compile, run the test suite, build the jar
./run-ai-harness.sh     # package, then launch the bundled AI Harness example
```

`./run-ai-harness.sh` runs `./mvnw clean package` and starts the harness from the packaged
`target/braxton-*.jar`; pass `--no-build` to reuse an existing jar. Gson and its annotation
companion are copied to `target/showcase-lib` and are never bundled in the Braxton jar.

On Windows, use a local Maven 3.9.16+ installation (`mvn clean verify`); the Bash launcher and the
`mvnw` script are Unix-only. The native console backend is selected automatically.

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
        return Transition.stay(new Model(0));
    }

    @Override
    public Transition<Model, Msg> update(Model model, Msg message) {
        return switch (message) {
            case Msg.Increment ignored -> Transition.stay(new Model(model.count() + 1));
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
./mvnw -q compile
java --enable-native-access=ALL-UNNAMED -cp target/classes demo.HelloProgram
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
   (viewport, terminal capabilities, monotonic start time, system properties).
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
child feature. Focus survives view rebuilds and sibling reordering; a target that disappears simply
receives nothing.

Normalized input is one of `KeyInput`, `PasteInput`, or `ResizeInput`. Printable keys carry their
text exactly once, and bracketed paste arrives as one atomic event that never triggers Enter or a
shortcut.

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
model. Task results, timer ticks, and clipboard reports arrive as ordinary messages, so `update` is
never re-entered. `Command.task` bodies are the documented impure boundary — they must not touch the
terminal, mutate captured state, or call `update`.

`Subscription` describes ongoing sources:

```java
Subscription.interval(SubscriptionId.of("tick"), Duration.ofMillis(50), Msg.Tick::new);
Subscription.resize(SubscriptionId.of("resize"), Msg.Resized::new);
Subscription.source(id, configuration, emitter -> closeable, mapper);
```

After every commit the runtime reconciles the description against the live set: unchanged
identity/configuration keeps the resource and replaces only the message mapper, changed or removed
sources are closed exactly once, and duplicate ids are rejected.

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
- editing never splits combining sequences, ZWJ emoji, or surrogate pairs;
- length limits truncate at a complete grapheme; unlimited is explicit;
- password/hidden editors mask output and refuse to copy by default;
- visual scroll-to-caret is derived, so drawing never mutates state.

## Widgets, assets, and clipboard

All widgets are immutable values or pure factories producing `StyledText`/`View`:

| Type | Purpose |
| --- | --- |
| `Markup` | Safe XML-like rich text (`span`, `p`, `a`, `ul`/`li`, `table`, `br`) |
| `Table` | Buffered column widths, optional border |
| `Box` | Border, title, responsive or fixed width |
| `Badge` | Pill segments with optional pathway arrows |
| `ProgressBar` | Active/inactive icons, separators, percentage |
| `Quote` | Colored bar and background |
| `Gradient` | Vertical, horizontal, or diagonal per-grapheme color |
| `Selector` / `TableSelector` | Immutable selection state and focusable views |
| `Animation` | Spinner and shimmer frames driven by model time |
| `FontLoader`, `FigletFont`, `Logo` | Bundled FIGlet fonts and styled logos |
| `ImageAsset`, `ImageView` | Explicit image loading and pixel-art rendering |

`Markup` disables DTDs and external entities, so untrusted text cannot fetch a file or URL.

**Clipboard and paste.** `PasteSanitizer` offers `NORMAL` (preserves indentation) and `AGGRESSIVE`
(trims and can collapse blank lines) modes. `ClipboardConfig` snapshots `braxton.clipboard.*`
properties per session. The in-process buffer is always the dependable destination; OSC 52 is
written at most once, through tmux-native or DCS-passthrough transport when configured.
`ClipboardResult` distinguishes stored, emitted, truncated, deduplicated, and internal-only
outcomes — OSC 52 has no acknowledgment, so Braxton never claims the operating-system clipboard
accepted a payload. Copy sequences share the frame output owner and cannot split a render.

## Runtime and terminal

`Application.run(program)` opens one `TerminalSession`, which owns raw mode and its exact saved
state, the alternate screen, cursor visibility, bracketed paste, optional mouse reporting, viewport
measurement, and a cancelable input read. Closing the session wakes a blocked reader without
closing `System.in`.

Defaults are configuration, not hidden constants:

| Setting | Default |
| --- | --- |
| Mailbox capacity | 4,096 non-coalescible envelopes |
| Concurrent one-shot effects (tasks + delays) | 64 |
| Ongoing sources | 64 |
| Paste payload / idle timeout / escape timeout / CSI length | 1 MiB / 250 ms / 30 ms / 64 bytes |
| Shutdown grace | 2 s |

`ExitResult` distinguishes a requested quit, clean end-of-input, and failure, and carries cleanup
failures without hiding the primary cause. Rendering is event-driven: an idle application does no
work, and an interval subscription drives animation only while it is needed.

The runtime runs on Java 25 virtual threads: the input reader, the resize poller, the timer worker,
the effect executor's tasks, and the terminal-restore shutdown hook are all virtual threads, so a
blocking read, sleep, or application task parks instead of holding a platform thread. The runtime
owner is the caller's thread; `Application.run` does not start or hijack it.

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
above the footer until the next action replaces it.
The harness is fully offline: it replays a sanitized bundled transcript and deterministic fallback
responses, with no model, provider, network, or user-session access.

## Architecture

| Package | Responsibility |
| --- | --- |
| `io.github.dhoard.braxton.app` | `Program`, `Transition`, `Application`, runtime options and environment |
| `io.github.dhoard.braxton.effect` | `Command`, `Subscription`, effect and source identities |
| `io.github.dhoard.braxton.input` | Value keys, modifiers, normalized events, bindings |
| `io.github.dhoard.braxton.view` | Immutable views, `Views`, `ElementId`, `FocusState`, `ScreenRenderer` |
| `io.github.dhoard.braxton.layout` | `Size`, `Viewport`, `Rect`, `Insets`, `Alignment`, `Border` |
| `io.github.dhoard.braxton.style` | `Color`, `Colors`, `Style`, `StyledText`, `TextMeasure`, `WidthPolicy` |
| `io.github.dhoard.braxton.widget` | Editor, markup, tables, boxes, badges, bars, quotes, gradients, selectors, animation |
| `io.github.dhoard.braxton.asset` | FIGlet fonts, logos, image assets and views |
| `io.github.dhoard.braxton.clipboard` | Configuration, sanitization, modes, results |
| `io.github.dhoard.braxton.terminal` | Backend, session, capabilities, options |
| `io.github.dhoard.braxton.internal` | Runtime loop, mailbox, effects, subscriptions, layout, cells, encoder, native sessions |

Dependencies point inward: examples depend on the public library, native code is reached only by
opening a real session, and the renderer performs no I/O. `internal` packages are not a supported
API surface.

## Design principles

- **State changes only through `update`.** Widget state is a value in the model; configuration is
  separate and immutable.
- **Effects are data with lifetimes.** Commands run once; subscriptions are reconciled, epoch-guarded,
  and closed deterministically.
- **Routing is structural.** Modal → capture → interceptor → focused → bubble, with duplicate
  bindings rejected at build time.
- **Rendering is safe by construction.** Content is control-character sanitized; only the encoder
  emits SGR/OSC 8 sequences.
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

The build must stay warning-free under `-Xlint:all`, the core must keep zero third-party runtime
dependencies, and the test suite must remain independent of a TTY, stdin, and raw mode. A headless
test run does not replace a manual check of a real terminal session.

MIT license headers are enforced by Spotless during `verify`. Dependency-update reports honor the
pre-release filter in `.versions-rules.xml`:

```bash
./mvnw versions:display-dependency-updates
```

## Compatibility

| Platform | Status |
| --- | --- |
| Linux / glibc x86_64 | Supported (native session backend) |
| Windows console | Implemented; validation on real hardware is pending |
| macOS / BSD | Rejected explicitly at startup |
| Java | 25 or newer |

Terminal sessions require `--enable-native-access=ALL-UNNAMED`. The Unix backend uses the
Linux/glibc x86_64 `termios` layout; other Unix ABIs are refused rather than guessed.

## Limitations

- Emoji width and variation-selector rendering vary between terminal emulators. Braxton pins and
  tests a documented policy; that is not a guarantee about every terminal.
- Terminals that encode Enter as LF cannot be distinguished from Ctrl+J; LF is treated as a newline.
- Mouse support covers wheel scrolling, not click-drag selection.
- Cancellation is cooperative: a task that ignores interruption is not forcibly stopped; its result
  is suppressed and shutdown waits only for the configured grace period. User task code is not
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
4. Do not add a third-party runtime dependency to the core. The optional example dependency must
   remain outside the published jar.
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

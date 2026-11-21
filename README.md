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

## Requirements

| Requirement | Version |
| --- | --- |
| JDK | **25 or newer** (the build targets bytecode 69) |
| Maven | **3.9.16 or newer** (the wrapper pins this version) |
| Native build | C11 compiler and target libc/SDK headers |
| Terminal | ANSI/VT-capable terminal; interactive runs require `--enable-native-access=ALL-UNNAMED` |

Platform support is described in [Compatibility](#compatibility). On macOS, use a JDK that matches
the host architecture (ARM64 or Intel x86_64): the native library is built for the host and loaded
into the JVM's own, so a Rosetta-mismatched JDK is rejected as an unsupported platform.


## Build from source

Braxton builds its own small POSIX terminal library from `src/main/native/terminal.c`.
A **C compiler** (`cc`, or an executable selected by `CC`) and platform development headers are
required alongside Java and Maven. On macOS install the Xcode Command Line Tools;
on Linux install a C toolchain and libc development headers. No terminal dependency needs a
separate build or installation.

`.mvn/jvm.config` opens the compiler packages required by Spotless to the Maven JVM. This lets the
formatter use those packages without its deprecated `sun.misc.Unsafe` fallback. These are build-tool
options; application launches need only the native-access option described below.

```bash
./build.sh                        # ./mvnw clean install, including the native library
./build.sh clean verify           # compile, run tests and package
./build.sh clean install -DskipTests
./build.sh clean                  # remove target/
./run-ai-harness.sh               # package and launch the AI Harness example
```

Maven compiles native code during `generate-resources`, so `./mvnw clean verify` also works
directly. The jar contains the binary for the build host under
`META-INF/braxton/native/<platform>/`, alongside its Braxton version metadata. Build separately on Linux and macOS (ARM64 and Intel); a jar from
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
`ComponentDriver`, `Pilot`, or `ElementScreen` without native access.


## Quick start

This complete two-counter application uses typed messages and pure updates. Press `l` or `r` to
increment a counter, and `q` to quit. The build compiles this example with warnings treated as errors
and enforces the 25-line budget, excluding imports and blank lines.

```java
package demo;

import io.github.dhoard.braxton.app.Application;
import io.github.dhoard.braxton.effect.Command;
import io.github.dhoard.braxton.input.Binding;
import io.github.dhoard.braxton.input.Key;
import io.github.dhoard.braxton.mvu.*;
import io.github.dhoard.braxton.ui.Element;
import io.github.dhoard.braxton.widget.Elements;
import java.util.List;

public final class Counters {
    record Model(int left, int right) {}
    enum Msg { LEFT, RIGHT, QUIT }
    public static Component<Model, Msg, Never> component() {
        return new Component<>() {
            public Update<Model, Msg, Never> init(InitContext ctx) { return Update.next(new Model(0, 0)); }
            public Update<Model, Msg, Never> update(Model m, Msg msg) {
                return switch (msg) {
                    case LEFT -> Update.next(new Model(m.left() + 1, m.right()));
                    case RIGHT -> Update.next(new Model(m.left(), m.right() + 1));
                    case QUIT -> Update.next(m, Command.quit());
                };
            }
            public Element<Msg> view(Model m, ViewContext ctx) {
                return Elements.column(Elements.text("left: " + m.left()), Elements.text("right: " + m.right()));
            }
            public List<Binding<Msg>> bindings(Model m) {
                return List.of(new Binding<>(Key.parse("l"), Msg.LEFT), new Binding<>(Key.parse("r"), Msg.RIGHT), new Binding<>(Key.parse("q"), Msg.QUIT));
            }
        };
    }
    public static void main(String[] args) { Application.run(component()); }
}
```

Save it as `demo/Counters.java`, then run:

```bash
./build.sh
javac -cp target/classes -d target/classes demo/Counters.java
java --enable-native-access=ALL-UNNAMED -cp "target/classes:target/showcase-lib/*" demo.Counters
```

`SimpleComponent.of(init, update, view)` assembles a root from three functions when application
bindings and subscriptions are unnecessary. Implement `Component` directly when those hooks are
part of the application, as above.


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


## Elements and layout

A view returns `ui.Element<Msg>`. `Elements` supplies text, row, column, padding, frame, stack,
modal, fixed and grow composition. The layout package adds flex, grid, dock, wrap, anchored overlays
and scrolling with minimum, maximum, fixed, percentage and fractional constraints.

Constraints go down, measured sizes come up, and parents place children. `ElementTree.layout`
caches measurements by element identity and available size within that frame.
`ElementTree.paint` writes through a clipped `render.Surface`. Empty and narrow bounds stay
nonnegative; wide graphemes are clipped whole. Public `Canvas` and `CellBuffer` expose cell painting
without depending on the application runtime.

A custom leaf implements `Widget<M, Msg>` and supplies its immutable model through `Element.Leaf`.
The widget owns measurement, painting and input interpretation; the surrounding tree owns placement
and clipping. Use `Interactive` for model-backed keyboard focus and pointer interaction. Compose
persistent widget instances when they keep pointer-capture state. See the public-API-only
[custom widget test](src/test/java/io/github/dhoard/braxton/ui/WidgetTest.java).

`ElementScreen.render(element, rows, cols)` paints through the production layout and renderer for
headless assertions. It requires no terminal or native access.

## Input, focus, and routing

The decoder produces normalized `InputEvent` values for keys, text, paste, pointers, terminal focus
and protocol replies. Keys include modifiers and enhanced-keyboard event metadata. `InputOptions`
bounds escape, CSI, paste and reply parsing; an incomplete sequence expires rather than blocking
ordinary input indefinitely. Protocol replies are separated from application keystrokes.

Focus belongs to widget models. `InteractionRouter` builds a focus walk from the placed tree;
`tab` and `shift+tab` move through focusable leaves, and modal layers restrict both focus and hit
testing. A component's `claims(model)` run before the tree; `bindings(model)` run after it declines
the gesture. `observed(event)` is the full-event observation hook. Bindings are exact gestures,
including modifiers. `KeyMap` and `Help` use the same action definitions.

Pointers are hit-tested against the committed placement and localized to the leaf's bounds.
A captured press keeps receiving drag events outside its original rectangle. Wheel routing follows
the pane under the pointer. Widgets emit typed messages; the owning update changes focus, selection
or scroll position. Enhanced keyboard and focus reporting are opt-in terminal presentation choices;
unsupported terminals may continue using ordinary VT input.

`ui.Cursor` describes native or virtual caret intent. Cursor location is resolved after placement;
native shape, color and blink depend on terminal support. A virtual caret is painted into cells,
so its animation needs application-driven ticks.

## Themes and stylesheets

`Theme` defines semantic tokens and supports live switching. Styles resolve in this order: widget
defaults, theme tokens, stylesheet rules, then explicit element style. Stylesheets match type,
class, id, state and descendant selectors, and report parse errors with line and column.
`StyledText` and `Style` carry colors, emphasis and links without embedding raw terminal controls.
The width policy uses pinned Unicode 16.0.0 data; text measurement, wrapping and painting share it.

## Widgets

Widgets expose immutable state and typed messages. Their updates return state, commands and typed
outputs; a parent decides how submission, selection, copying, cancellation or validation affects
its application. Configuration and data types that carry substantial policy live beside their
component rather than inside a large source file.

| Family | Components and behavior |
| --- | --- |
| Text editing | `Editor` for multiline editing, selection, word motion, wrapping, gutters and masking; `TextInput` for single-line editing, validation, completion and suggestions |
| Content | `TextViewport` for cell/word wrapping, panning, highlights and search navigation; `ScrollView` for indexed display rows, selection and a scrollbar; `Markdown` for bounded CommonMark/GFM projection with image/highlight hooks |
| Lists and files | `RichList` for fuzzy/prefix filtering, grouping, marking, paging and chrome; `FilePicker` for asynchronous bounded directory listing, navigation and checked selection |
| Tables and navigation | `DataTable` for sorting, filtering, marking, row/cell selection and column navigation; `Tabs`, `Paginator`, `Selector`, `TableSelector`, `Menu`, `CommandPalette`, `Tree`, `SplitPane`, `Scrollbar` |
| Forms | `Select`, `MultiSelect`, `Checkbox`, `RadioGroup`, `Form` and validation/error presentation |
| Feedback | `Toast`, `NotificationCenter`, `Dialog`, `LogViewer`, `Spinner`, `AnimatedProgress`, `Countdown`, `Stopwatch` |
| Charts | `LineChart`, `BarChart`, `Sparkline`, `Gauge`, `Calendar` |
| Display and assets | `Markup`, `Table`, `Box`, `Badge`, `Quote`, `Gradient`, `ProgressBar`, FIGlet fonts, logos and bounded immutable images |

Use `EditorConfig`, `TextInputConfig`, `FilePickerConfig`, `MarkdownConfig`, `DataTableConfig` and
`RichListConfig` for their policies. Table data uses `DataTableContent`, `DataTableModel` and
`DataTableMsg`; list data uses `RichListModel`, `RichListMsg`, `RichListOutput` and `RichListFilter`.
Smaller component-specific values remain nested, such as `Editor.Model` and `Tabs.Config`.
`RichListDefaultRenderer` implements the standard list row renderer. These are the current public
types; earlier nested spellings have no aliases.

A widget's component view can be mounted through `Mount` with a `ModelLens` and typed `OutputRoute`.
`Elements` also supplies leaf factories for direct composition. For complete examples of keys,
outputs, theme resolution, pointer behavior and narrow layouts, use the
[parity catalog](examples/parity/README.md), [capability matrix](examples/parity/PARITY.md) and
[widget sources](src/main/java/io/github/dhoard/braxton/widget).


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


## Line editing

`line` is the package for a program that reads one line at a time from a terminal - a shell, a REPL,
a configuration wizard - rather than owning the whole screen. It does not need the MVU runtime or the
element kernel; it sits on the public terminal API, and a stream-backed session drives it in a test
with no TTY and no native library.

**The editor is a value.** `LineEditor` holds one row of text, a caret, a mode, a Vi insert/normal
state and the kill buffer, and it changes only through typed messages:

```java
LineEditor editor = LineEditor.emacs();
editor = editor.update(new LineEditor.Msg.Insert("make test")).editor();

var result = editor.update(new LineEditor.Msg.Move(Motion.WORD_BACK));
assert result.editor().caret() == 5;
```

`update` is pure, a message that changes nothing returns the incoming instance, and the only outputs
are `Accepted`, `Canceled` and `Eof` - the three ways a line ends. `handle(InputEvent)` is the same
update with the keys already read: it inserts a typed character, applies the mode's chords, turns a
paste into one insertion of sanitized text (never a keystroke, so a pasted newline cannot submit the
line), and ignores a release, a resize, a pointer report or a terminal reply.

**Two modes.** `Mode.EMACS` is always inserting: `ctrl+a`/`ctrl+e`, `ctrl+b`/`ctrl+f`,
`alt+b`/`alt+f`, `ctrl+k`/`ctrl+u`/`ctrl+w`/`alt+d` to kill, `ctrl+y` to yank, `ctrl+t` to transpose.
`Mode.VI` starts inserting and leaves insert mode on `esc`, stepping the caret back one cluster where
Vi leaves it; normal mode answers `h l w b e 0 $`, `x X D C s`, `i a I A`, `p P`, and the operators
`d` and `c`, which wait for a motion (`dw`, `cw`, `d$`, `d0`) and take the whole line when pressed
twice (`dd`, `cc`). `keys(mode, state)` is the `KeyMap` a help view renders. Vi's letter commands are
matched against the typed character rather than a key identity, because the decoder reports a typed
upper-case letter as the lower-case key carrying that letter as its text - so `D` and `d` are one
gesture and only the text tells them apart. A terminal that reports a lone `esc` only after its
escape timeout therefore needs the `esc` press to be separated from the next key, which is how a
terminal reports a bare Escape in every program.

**Inline rendering.** `LineRenderer` paints the prompt and the line on one row of any `render.Surface`
and answers the caret's cell, and a completion menu on the row below it; a line wider than the row
scrolls, with the window always starting and ending on a cluster boundary, so a wide glyph is shown
whole or not at all. `LineReader` is the host:
it paints where the terminal's cursor already is, repaints as the line changes, survives a resize,
and leaves an accepted line on screen with the cursor on the line below it. It never clears anything
above its own row, so a shell's scrollback survives the session. It also asks for bracketed paste
while it reads and gives it back afterward; it never opens, closes or configures the session. A
caller that prints between reads writes its own `\r\n` with `write`: a raw terminal does not translate
`LF`, and `writeText` deliberately turns a `CR` into a space.

```java
try (TerminalSession session = TerminalSession.open()) {
    session.enterRawMode();
    var reader = LineReader.of(session).withMode(Mode.VI);
    LineEditor.Output line = reader.read("> ");
    if (line instanceof LineEditor.Output.Accepted accepted) {
        run(accepted.text());
    }
}
```

**History.** `History` is the ordered, bounded set of lines an application has
accepted: `History.inMemory()` and `History.of(lines)` keep it for one session, and
`History.load(path)` reads and writes a file. `recorded(line)` answers the history that includes the
line, leaves the value it was called on alone, and publishes the file before it returns; a blank line
is never recorded. Two writers appending at once cannot lose each other's work - an append re-reads the
file under an exclusive lock on a sibling `<name>.lock` file and publishes the merged result with an
atomic move - so a reader always sees one complete history, and the file and its lock are readable by
the user alone, because a history holds what a user typed.

```java
History history = History.load(Path.of(System.getProperty("user.home"), ".demo-history"));
var reader = LineReader.of(session).withHistory(history);
LineEditor.Output line = reader.read("> ");
if (line instanceof LineEditor.Output.Accepted accepted) {
    history = history.recorded(accepted.text());
    run(accepted.text());
}
```

The reader searches the history it is given and never records what it read: an accepted line is the
caller's to keep, because the caller knows whether the line is worth recalling and where the history
lives.

**Reverse search.** `ctrl+r` searches the history incrementally and `ctrl+s` walks the other way,
toward newer lines; in Vi normal mode `/` and `?` are the same two directions. Typed text narrows the
search, the matched line becomes the line being edited with the caret where the query matched,
`backspace` takes a character back off the query, `esc` or `ctrl+g` restore the line the search started
from, and `enter` ends the search on the line it found. `LineRenderer` paints the search prompt in place
of the caller's, so the row itself says what is being searched for:

```text
(reverse-i-search)`make': make test
```

`forward-i-search` names the other direction, and the same prompt with `failed` in front says that the
last step matched nothing. Enter ends the search without accepting the line; the next Enter accepts it,
because a search gesture must not run a command nobody has read in full yet.

**Completion.** `tab` asks a `Completer` for candidates for the token that ends at the caret - the run
of non-blank clusters before it, so `src/fo` is one token and a flag like `--out=x` is another - and a
candidate replaces that token whole. One candidate is inserted at once; two or more open a menu on the
row below the line, with the highlighted candidate bracketed and filled with the theme's selection:

```text
> git ch
[checkout]  cherry-pick  commit
```

`tab` and `down` step the highlight, `up` and `shift+tab` step it back, `enter` takes the highlighted
candidate without accepting the line - the next `enter` accepts it - and `esc` dismisses the menu and
leaves the line as it was typed. Any other key ends the menu and is then applied, so typing simply
carries on. The line above the menu is always what the user typed: nothing is inserted until a
candidate is chosen, which is also what lets the menu trust the token it recorded.

```java
Completer commands = (text, caret) -> Stream.of("checkout", "cherry-pick", "commit")
        .filter(name -> name.startsWith(text.substring(0, caret)))
        .toList();
LineEditor editor = LineEditor.emacs().withCompleter(commands);
```

**Highlighting.** A `Highlighter` answers one `style.Style` per cluster of the row, and `LineRenderer`
merges each one over the line's own style as it paints: a keyword can be bold, a string green or a
number dimmed, while the caret stays in its cell and the row keeps its width. A shorter answer leaves
the rest of the row in the theme's text style, and a null entry leaves its cluster alone. Highlighting
is presentation - it never edits the line, and it never sees a surface, a theme or a caret.

```java
Highlighter digits = text -> TextMeasure.graphemes(text).stream()
        .map(cluster -> cluster.matches("\\d") ? Style.fg(Color.GREEN) : null)
        .toList();
LineEditor editor = LineEditor.emacs().withText("select 1").withHighlighter(digits);
```

**Masking.** `withMasked(true)` paints one glyph per cluster in place of the text, which is what a
prompt for a password or a key wants. The line, the caret and every edit stay on the real text: the
caret is placed by counting the clusters before it, a wide cluster takes one mask cell, and `text()`
still answers what the user typed, so the caller reads the secret and the terminal never sees it.

```java
LineEditor.Output line = reader.read("Password: ", LineEditor.emacs().withMasked(true));
if (line instanceof LineEditor.Output.Accepted accepted) {
    authenticate(accepted.text());
}
```

A completer, a highlighter and a mask belong to the editor, so they reach `LineReader` through the
starting editor `read(prompt, editor)` takes; a masked editor declines completion and ignores
highlighting, because neither may show what the mask hides.

**Prompts.** Four prompts ask a program's next question in the same inline region, so a shell, a REPL
or a wizard can ask what to do next without taking the screen:

| Prompt | What it asks | What it answers |
| --- | --- | --- |
| `ConfirmPrompt` | a yes-or-no question, read as one line | `Optional<Boolean>` |
| `SelectPrompt` | one choice from a list | `Optional<String>` |
| `MultiSelectPrompt` | several choices from a list | `Optional<List<String>>` |
| `ProgressPrompt` | a bounded count, painted as a bar | the count it reached |

`Optional.empty()` always means the same thing - the question was abandoned or the input ended - so a
caller that overwrites a file cannot mistake an abandoned confirmation for a no.

A **confirmation** is a line rather than a single keystroke, for the reason every careful
command-line program reads one: a user who types `yes` at a one-key prompt answers three times and
leaves two keystrokes for whatever reads next, and a pasted answer behaves like a typed one. The
answer is `y`, `yes`, `n` or `no` in any case, a bare `enter` takes the default, and an answer it
cannot read asks the question again.

```java
boolean overwrite = ConfirmPrompt.of("Overwrite the report?").ask(reader).orElse(false);
```

A **select** prompt paints its question and a window of options where the terminal's cursor already
is, with the highlighted option carrying an arrow and the theme's selection:

```text
Pick a theme  (enter chooses, esc cancels)
❯ dark
  light
  high-contrast
```

`up`/`down` move the highlight, `home`/`end` take it to either end, `pageup`/`pagedown` move it a
window at a time, `enter` or `space` chooses, and `esc` or `ctrl+c` abandons the prompt. The window
follows the highlight, so a list longer than `withRows(n)` scrolls instead of being cut, and a
movement that cannot move changes nothing. A **multi-select** prompt is the same shape with a box per
option: `space` marks or unmarks the highlighted option and stays where it is, `enter` accepts the
marks, and the answer is every marked label in the list's order - an empty list is the real answer
"none", not a cancellation.

```java
String theme = SelectPrompt.of("Pick a theme", List.of("dark", "light")).ask(reader).orElse("dark");
List<String> targets = MultiSelectPrompt.of("Build which targets", names).ask(reader).orElse(List.of());
```

A **progress** prompt paints a name, a bounded count and a percentage in the same region, repainting
the row in place as the work moves and writing nothing when a step does not change it:

```text
Installing packages  ████████████░░░░  4/6   67%
```

```java
try (ProgressPrompt progress = ProgressPrompt.open(reader, "Installing packages", packages.size())) {
    for (String name : packages) {
        install(name);
        progress.step();
    }
}
```

On the way out the final row stays on screen - the result, the rule an accepted line follows - and
the caller's next output starts on the line below it.


## Transitions and reduced motion

`widget.Transition` is an immutable numeric timeline. Supply monotonic timestamps from
`Command.monotonicTime` and interval messages; neither transitions nor animation helpers read a
clock or start a timer. `retarget` starts at the value currently displayed, and `finishedAt` lets
an application remove its interval when the transition settles.

```java
var fade = Transition.between(0, 1, startedNanos, Duration.ofMillis(300))
        .withEasing(Transition.Easing.EASE_IN_OUT)
        .withReducedMotion(reducedMotion);
double opacity = fade.valueAt(nowNanos);
```

Endpoints must be finite, and duration must be nonnegative and fit in nanoseconds. A zero duration
or reduced motion selects the target immediately. Curves never overshoot. `Animation.Sequence`
prepares immutable styled frames at a positive cadence; `frameAt(elapsedNanos, true)` holds its
first frame. The `spinner` and `shimmer` helpers also accept a reduced-motion flag: a spinner holds
its first frame and a shimmer uses only its base style. Existing calls retain their normal motion.
The application owns the preference and drops unnecessary subscriptions; there is no global timer
or environment-based override. The showcase's entry `30` demonstrates these helpers and an image.


## Image output

`image.ImageOutput` encodes an immutable `ImageAsset` as Kitty graphics, an iTerm2 inline PNG,
Sixel, or upper half blocks. Image bytes never become application text or control syntax.

```java
var image = ImageOutput.of(ImageAsset.load(Path.of("picture.png"))).fit(80, 48);
// An MVU view: one cell holds two vertical pixels, giving at most 80 columns by 24 rows.
Element<Msg> picture = image.element();
// A standalone low-level host, outside an active MVU runtime:
try (var session = TerminalSession.open()) {
    image.write(session);
}
```

Dimensions passed to `fit` are **pixels**. Fitting preserves aspect ratio and never enlarges an image;
encoding rejects nonpositive dimensions, an axis above 4,096 pixels, more than 4,194,304 pixels,
or encoded output above 64 Mi characters. Use `withBackground(Color)` to choose how transparency
is composited in half blocks and Sixel. Kitty and PNG retain alpha; Sixel uses a 216-color cube.
PNG encoding uses memory rather than ImageIO's global disk cache.

`ImageCapabilities.detect` recognizes Kitty's terminal identity and iTerm2's `TERM_PROGRAM`.
Unknown terminals and multiplexers receive half blocks. Sixel requires explicit support or a
complete primary device-attributes reply containing feature 4, passed to `withDeviceAttributes`;
this helper does not issue queries or consume keys. A host that already negotiated support can
supply an `ImageCapabilities` value to `write`. Selection prefers Kitty, then iTerm2, then Sixel.
`NO_COLOR` and sessions without color force an uncolored text fallback. Native output writes at
the current cursor at the raster's native pixel size; callers own placement and subsequent redraws.
The composed `element()` always uses a clipped cell grid and paints only visible pixels; it never
reflows an image as text. Fit to the current viewport when resizing. Layout and ordinary frame diffs
continue to own the screen. There is no automatic image probing or extra runtime thread.


## Terminal queries and color profiles

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
capability, `?` for a color - and `Reply.Timeout` is silence, reported after the reply deadline.
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
- Canceling the effect, canceling its scope, or unmounting the component withdraws the query.

`Termcap` is closed: only `RGB`, `Tc`, `TN`, `Co` and `Ms` are requestable, so a reply can never be
interpreted for a name whose meaning this library has not decided. `RGB` and `Tc` both mean "this
terminal displays 24-bit color".

### Color profiles

`style.ColorProfile` is the amount of color a session puts on the wire: `NONE`, `ANSI16`,
`ANSI256` or `TRUECOLOR`. The same styled text encodes as 24-bit SGR, as a 256-color palette index,
as one of the classic sixteen codes, or as no color at all; bold, italic, underline and OSC 8
hyperlinks survive every profile, so a session that loses color loses only color.

A frame is encoded in the profile the session is presenting with:

- `RuntimeOptions.withColorProfile(...)` sets it for a run. The default, `ColorProfile.AUTO`,
  negotiates: no color when the terminal cannot address it or when `NO_COLOR` is set to any
  non-empty value, and truecolor otherwise - the output every earlier release emitted on a color
  terminal, so no existing application changes appearance.
- `Command.colorProfile(...)` switches it while running. The next frame repaints the whole screen,
  because every cell was written with the previous profile; `ColorProfile.AUTO` returns to the
  negotiated one.
- A termcap reply that confirms `RGB` or `Tc` widens a narrower profile. Evidence never narrows one,
  and an explicit profile is honored as given.

Downsampling to a palette is a documented rule rather than an accident. A color maps to the
nearest entry measured in CIE L\*a\*b\*, so a mid blue maps to blue and not to the cyan an RGB
channel distance would pick; an exact tie goes to the lower palette index, and the arithmetic is
`StrictMath`, so the same color maps to the same entry on every platform. In a 256-color session
only the 6x6x6 cube and the gray ramp are candidates, because a terminal may redefine its first
sixteen colors; a 16-color session has nothing but those sixteen to choose from. The mapping is
memoized within a bounded cache.

### Session facts

Some answers describe the session rather than a component: the profile in force, the terminal the
session opened, and the background color the terminal reported. `app.TerminalFacts` is that set, and
`Subscription.facts` delivers it:

```java
Subscription.facts(SubscriptionId.of("facts"), Msg.Facts::new)
```

The current facts arrive when the source starts, and again whenever one changes - a profile widened
by a capability reply, or a background color reported for the first time. `TerminalFacts.dark()`
answers the question a theme actually asks, through the same classification rule as
`Reply.ColorValue.dark()` and `Colors.isDark`: the BT.601 luma of the color, everything below the
halfway point counting as dark. A background nobody has asked for is `themeKnown() == false` rather
than a guessed theme.

Nothing is queried at startup. A session that wants terminal facts asks for them, and a terminal
that implements none of the queries simply answers nothing.


## Declarative terminal presentation

A frame draws cells; `app.TerminalPresentation` describes screen modes and terminal metadata.
Declare it through the component's model-dependent `presentation` hook:

```java
@Override
public TerminalPresentation presentation(Model model) {
    return TerminalPresentation.EMPTY
            .withTitle("braxton")
            .withMouseMode(MouseMode.CELL_MOTION)
            .withProgress(TerminalPresentation.Progress.normal(40))
            .withStatus(TerminalPresentation.Status.working("braxton", "Rendering"));
}
```

| Field | Meaning | Absent means |
| --- | --- | --- |
| `alternateScreen` | which screen buffer the frames are drawn on | the session's `TerminalOptions` |
| `mouseMode` | how much pointer input the terminal reports | the session's `TerminalOptions` |
| `bracketedPaste` | whether paste is bracketed | the session's `TerminalOptions` |
| `focusReporting` | whether focus changes are reported | the session's `TerminalOptions` |
| `title` | the window title | no title from this session |
| `foreground`, `background`, `cursorColor` | the terminal's own default colors | the terminal's configured colors |
| `progress` | a taskbar progress value: none, normal, error, indeterminate, paused | no progress |
| `status` | a program-status record: idle, working, blocked, done, error, clear | no record |

A session mode left out keeps the option the session opened with, so an application that declares no
presentation has exactly the behavior it had before this existed. Metadata left out means the
session presents none, so a title is popped, a color default is reset, a progress value is removed
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
focus modes, the popped title, the reset color defaults, the removed progress value and the cleared
status record.

The wire forms are the documented ones:

- `OSC 2` sets the title, and `CSI 22 ; 0 t` / `CSI 23 ; 0 t` (XTPUSHTITLE/XTPOPTITLE) save and
  restore the title the shell had. A title is bounded to 4,096 UTF-8 bytes on a grapheme boundary and
  every control character is removed, so a title cannot end the sequence it is written into and start
  another.
- `OSC 10`, `OSC 11` and `OSC 12` set the terminal's default text, background and cursor colors, and
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
applied (pointer, paste, focus, the window title, the terminal's default colors, progress, status,
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
canceled, when the component its steps belong to is removed, or when a step ends the program. The
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
no color: the session renders inline and asks for color only through
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
  closing a scene is the documented unmount: its scoped subscriptions and commands are canceled and
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

The widget gallery (`examples.parity.WidgetGalleryDemo`, entry `28`) is the milestone's other
composition proof: every widget added for the UI-capability milestone - the scrollbar and split pane,
the tree, the menu and command palette, the select, multi-select, checkbox, radio group and form, the
dialog, toast and notification center, the line chart, bar chart, sparkline and gauge, and the
calendar and log viewer - on one screen, in one model, under one key path. Nineteen widgets do not
fit on a terminal at a size worth reading, so the gallery is four pages of which one is painted at a
time; `alt+1` through `alt+4` choose the page and `tab` moves the keyboard between the specimens on
it. It is described in [the parity examples record](examples/parity/README.md#phase-28-widgetgallerydemo).

The reference-to-phase mapping - every behavior the reference example corpus demonstrates, and the
deliberate differences - is [the parity record](examples/parity/PARITY.md). `ParityAcceptanceTest`
reads that file and fails when a row names a demo, test or scenario that does not exist.


## Runtime and terminal

The MVU runtime owns one terminal, commits updates on one owner thread, and confines native calls
to the internal terminal transport. An idle application produces no recurring output. Dirty frames
are coalesced and paced at the configured ceiling; input and resize request immediate presentation.
Orderly exit flushes a pending frame before restoration.

Alternate-screen frames use cell-level ANSI diffs and scroll-region insert/delete operations where
applicable. Cleared tails are erased. Each nonempty frame uses one synchronized-update transaction;
a terminal that lacks that protocol may display intermediate output. Inline rendering repaints its
own live region and leaves durable lines above it intact. Output failure attempts to close the
transaction and flush, preserves the cause, and does not accept the failed frame as the baseline.

Defaults are configuration, not hidden constants:

| Setting | Default |
| --- | --- |
| Mailbox / one-shot effects / ongoing sources | 4,096 / 64 / 64 |
| Components / depth / owner-local dispatches per turn | 4,096 / 64 / 1,024 |
| Shutdown grace / frame ceiling | 2 s / 60 fps, configurable from 1 to 240 |
| Paste / paste idle / escape / CSI | 1 MiB / 250 ms / 30 ms / 64 bytes |
| Query deadline / outstanding queries / reply payload | 1 s / 16 / 4,096 characters |
| OSC 52 write / read / encoded read reply | 64 KiB / 1 MiB / 1,398,106 characters |
| Message filter / failure propagation / shutdown hook | none / `ExitResult` / installed |
| Session I/O / renderer / stream size | owned terminal / enabled / 24 rows by 80 columns |

`RuntimeOptions` controls these limits, color, clipboard, input and terminal presentation.
`IoOptions` supplies caller-owned streams, dimensions, renderer choice, environment overrides and
diagnostics. Stream and renderer-disabled sessions load no native library. The caller retains its
streams after a run; renderer-disabled sessions emit no frame or terminal-mode controls.
`ExitResult` separates quit, end-of-input, interruption and failure, with cleanup failures attached.

## Low-level terminal API

`terminal.TerminalSession` provides raw mode, normalized input and resize events, capabilities,
cursor movement and output without MVU. Close restores the session's terminal state. Its public
API exposes no FFM or native-service type. `TerminalSession.of(reader, writer, size)` creates a
stream session for tests or embedding.

```java
try (var session = TerminalSession.open()) {
    session.enterRawMode();
    session.writeLine("Ready");
    var event = session.read();
}
```

`writeText` and `writeLine` neutralize untrusted control characters. `write` accepts raw sequences
and belongs only to application-owned output. `Capabilities` is the shared terminal/MVU capability
value with a per-feature report. Recognized terminal identities safely infer optional features;
unknown VT types start with conservative facts. Optional probes have bounded deadlines. Unset or
`dumb` TERM produces a typed unsupported-terminal error. No controlling terminal produces a typed
no-terminal error, retained as the cause when opening a public session.

Interactive input can come from the controlling terminal when stdin is redirected, leaving the pipe
available to the application. External `SIGTSTP` restores terminal modes before stopping and
`SIGCONT` resumes and repaints. A process stopped by `SIGKILL` cannot restore terminal state.

Native extraction tries a private temporary directory first, then
`user.home/.local/share/braxton/native/<version>/<platform>/<content-hash>/`.
Cached publication uses locking, private staging and atomic replacement; mapped libraries and old
versions are retained. Cached bytes are checked against the packaged image; symlinks and writable
managed paths are rejected. Tests use owned temporary homes. The native
[implementation and verification record](src/main/native/README.md) describes the ABI and protocols.


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
rather than by a colored panel.
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
canceling it or missing the window also clears it.
The harness is fully offline: it replays a sanitized bundled transcript and deterministic fallback
responses, with no model, provider, network, or user-session access.


## Architecture

| Package | Responsibility |
| --- | --- |
| `terminal` | Sessions, terminal events, capabilities and typed errors |
| `render` | Public cell buffers, surfaces and canvas |
| `layout`, `style` | Geometry, constraints, containers, text, themes and stylesheets |
| `ui` | Elements, widget protocol, placement, interaction, cursors and debug snapshots |
| `app`, `mvu`, `effect` | Application control, typed component hierarchy, commands and subscriptions |
| `widget`, `line`, `image`, `asset` | Reusable components, line editing, graphics and assets |
| `testing` | Component driver, simulated-user Pilot and screen snapshots |
| `internal` | Runtime owner, mailbox, native transport, input decoder, encoder and text engines |

The public kernel does not import application, effect, MVU or widget packages. Internal services
implement those public contracts. `RuntimeTerminal` owns device acquisition, output and restoration;
`RuntimeInput` runs the decoder worker, `ProcessHandoff` owns child lifetime, and `RuntimeFrame`
resolves a cell frame. `RuntimeLoop` coordinates committed turns and resource lifetimes.
The dependency contract records reviewed imports and rejects new edges and upward kernel imports.
All hand-written production files stay at or below 1,200 lines.

## Testing and quality gates

Default tests require no TTY, native-access flag, external account or service. `ComponentDriver`
exercises pure updates and rendering; `Pilot` interprets supported command data, uses manual time,
and records screens and interaction traces. Unsupported external effects fail explicitly.

```bash
./mvnw -o -B -ntp test -Dtest='EditorTest,ParityAcceptanceTest'
./mvnw -o -B -ntp spotless:apply
./mvnw -o -B -ntp javadoc:javadoc  # strict API documentation check
./mvnw -o -B -ntp verify
./build.sh
```

The build enforces license headers, formatting, multiline block Javadocs, source size and dependency
contracts. Compiler warnings fail compilation; `verify` also generates API documentation with full
DocLint and treats Javadoc warnings as errors. Reports are under `target/reports/apidocs/`. JaCoCo requires
at least 90% total line coverage and 80% branch coverage, at least 80% terminal-internal line coverage,
100% line/branch coverage for `AnsiEncoder`, and 88% line/74% branch coverage for `RuntimeLoop`.
FFM/C execution is checked separately using owned PTYs; default tests exercise Java lifecycle policy
through a private transport boundary. Reports are under `target/site/jacoco/`.

Python with pyte is required for terminal emulation. The final developer acceptance commands are:

```bash
./build.sh
python3 tools/terminal/verify_terminal.py --parity-all --repeat 10 --pending-escape --harness
python3 tools/terminal/verify_native_lifecycle.py target/braxton-0.1.0.jar
python3 tools/terminal/verify_native_extraction.py target/braxton-0.1.0.jar
tools/verify_portability.sh
```

The portability script cross-compiles all four targets with warnings as errors. Cross-compilation
proves compilation, not runtime behavior. Terminal verification compiles its probes into owned
temporary directories and drives the packaged artifact. Run these checks on the actual target
before claiming current runtime verification there. `--parity-phase NN` selects one scenario.

Generate pinned Unicode data only with `tools/generate_unicode_widths.py`; bundled fonts and Unicode
licenses and provenance ship in the artifact. Build output remains under `target/`.


## Developer diagnostics

Enable `Command.debugOverlay(true)` from `init` or an update and disable it with
`Command.debugOverlay(false)`. The session-level overlay tints layout edges without replacing
source glyphs and paints a bottom panel with bounds, the active focused widget's placement path,
the last four message summaries and view/layout/paint time. It changes no focus, key or pointer
route and reserves no gesture. Message summaries use `toString`, are sanitized and limited to
160 characters; formatting failures appear as diagnostics. History is released when disabled.
The application owns disabling a session preference when leaving its inspector screen.

Timing excludes diagnostics, encoding and terminal I/O. Disabled sessions allocate no diagnostic
owner, build no snapshots, format no messages and sample no diagnostic clock. Renderer-disabled
runs ignore this presentation command and emit no overlay. The snapshot retains at most 256 unique layout rectangles and no application
model or tree. Bounds and the panel are clipped to the viewport; a tiny screen shows only what fits.
`ui.DebugOverlay.capture` and `paint` also work with owned surfaces in headless tests. `Pilot`
continues to reject presentation effects requiring a real runtime; it does not pretend to render
the runtime overlay. Showcase entry `31` demonstrates the feature over two ordinary widgets.


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

| Platform | Current verification scope |
| --- | --- |
| Linux x86_64 | Native build and owned PTY execution on this development host |
| Linux ARM64 | Cross-compilation; runtime execution pending on an ARM64 host |
| macOS ARM64 | Cross-compilation; historical native verification is recorded separately, current refactor runtime execution pending |
| macOS x86_64 | Cross-compilation; runtime execution pending on an Intel Mac |
| Windows and other combinations | Unsupported |

Interactive classpath launches require `--enable-native-access=ALL-UNNAMED`; a module-path launch
uses `--enable-native-access=io.github.dhoard.braxton`. The native library must match the JVM's OS
and architecture. The packaged jar contains only its build host's native binary. Missing platform
resources fail explicitly. Maven resolves the CommonMark parser and its extensions separately;
optional Gson dependencies belong to the AI Harness example.

### Source migration to 0.1.0

`0.1.0` is an intentional source and binary break. Recompile consumers against `Component<M, Msg,
Out>`, `Update<M, Msg, Out>`, the `Element`/`Widget` kernel and the current widget data/configuration
types listed above. Root output is `Never`; contexts live in `mvu`. Mount children through lenses
and typed routes, declare their active tree and subscriptions, and use scope-local effect IDs.
Use `terminal.Capabilities` throughout. Construct `RuntimeOptions` with its full current record
shape or start from `defaults()` and use the `with...` methods. `TerminalOptions` uses `MouseMode`
and explicit keyboard/focus reporting choices. No removed API adapter, alias or deprecated forwarder
is supplied.

## Limitations

- Unicode width and emoji presentation vary by terminal. Braxton tests its pinned width policy;
  it cannot make different terminal fonts and emulators display identical glyphs.
- A terminal may ignore cursor shape, hyperlinks, enhanced keyboard, synchronized output or graphics
  controls. Conservative feature selection and text fallbacks cover supported degradation paths.
  Native graphics display in physical Kitty/iTerm2/Sixel terminals requires separate visual testing.
- File listings are bounded snapshots. A choice is revalidated, but filesystem contents can change
  again after selection. Hidden names use the dot-prefix convention. Oversized directories report
  truncation and retain only the admitted entries.
- Table widths are prepared with immutable content. Replacing rows may measure the data set;
  ordinary frames, navigation and resize use the prepared widths. Tables pan rather than reflow.
- List filtering scans titles, and fuzzy ranking depends on the query. Very large collections may
  need application-side narrowing. Item height is a renderer policy, independent of viewport width.
- Markdown renders bounded input and never fetches or executes links, code or images. HTML is literal
  text. The bundled highlighter covers Java, JSON and POSIX shell. Hooks are application-owned.
- Scroll and viewport projection preserve their documented logical-row/line anchors; a resize need
  not keep the same word at the first visible cell. Word wrapping is an explicit viewport mode.
- Animations and diagnostics consume resources only when requested. Applications own reduced motion,
  animation subscriptions and sensitive message payloads shown by an enabled inspector.
- Running task cancellation is cooperative. A task that ignores interruption can outlive the
  configured shutdown grace. User task code is not sandboxed.
- Persistent line history uses bounded, locked updates. The caller owns history paths and policies;
  tests use temporary files. No prior history format migration is supplied.
- Job control requires a controlling foreground terminal and a job controller. Headless and stream
  sessions refuse suspension. Forced termination and failed output can prevent full restoration.


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

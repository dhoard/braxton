# Reference-to-phase parity

This record answers one question: for every behavior the reference implementation demonstrates, is
there an implemented phase, a demo, a deterministic regression test and a terminal scenario that
proves Braxton does the same thing - and where it does not, is the difference written down?

## Pinned comparison

| Component | Revision | Used as |
| --- | --- | --- |
| Bubble Tea | `6b30ecf3280d88d949dcc916bb91fc5e9d56f667` | the reference runtime and its example corpus |
| reference examples | `examples/go.mod` in that checkout | pins Bubbles 2.1.0, Lip Gloss 2.0.3, Glamour 2.0.0 |
| Braxton | this checkout | implementation under acceptance |

The comparison is between *behavior*, not dependencies: Braxton takes no dependency on any of them,
and the versions above say what the reference examples exercised when this matrix was written. A
reference revision that adds an example adds a row here before the phase can claim it.

## The matrix

`Kind` says what the reference example is:

- **component** - a widget whose behavior Braxton implements and tests directly.
- **runtime** - a runtime or terminal-integration behavior: a mode, a query, a lifecycle event.
- **application** - an application built *from* covered primitives rather than a widget. Braxton's
  answer is the composed workspace or the relevant phase demo, because what the reference shows is
  an arrangement of behavior this library already owns.
- **unsupported** - a deliberate policy difference, named below.

Two rows name a component rather than an example directory: the reference corpus has no clipboard
example even though it ships the clipboard component, and composition is what this repository's own
final phase adds. Every other row is one directory in the reference `examples` tree.

| Reference | Kind | Phase | Demo | Test | Scenario |
| --- | --- | --- | --- | --- | --- |
| `altscreen-toggle` | runtime | 20 | `examples.parity.TerminalPresentationDemo` | `ParityPresentationTest` | `20` |
| `autocomplete` | component | 05 | `examples.parity.TextInputDemo` | `ParityTextInputTest` | `05` |
| `canvas` | application | 12 | `examples.parity.ViewportDemo` | `ParityViewportTest` | `12` |
| `capability` | runtime | 18 | `examples.parity.TerminalQueryDemo` | `ParityTerminalQueryTest` | `18` |
| `cellbuffer` | application | 12 | `examples.parity.ViewportDemo` | `ParityViewportTest` | `12` |
| `chat` | application | 04 | `examples.parity.EditorDemo` | `ParityEditorTest` | `04` |
| `clickable` | component | 17 | `examples.parity.MouseDemo` | `ParityMouseTest` | `17` |
| `colorprofile` | component | 18 | `examples.parity.TerminalQueryDemo` | `ParityTerminalQueryTest` | `18` |
| `composable-views` | component | 16 | `examples.parity.TabsDemo` | `ParityTabsTest` | `16` |
| `cursor-style` | component | 03 | `examples.parity.CursorDemo` | `ParityCursorTest` | `03` |
| `debounce` | application | 22 | `examples.parity.ApplicationControlDemo` | `ParityControlTest` | `22` |
| `doom-fire` | application | 12 | `examples.parity.ViewportDemo` | `ParityViewportTest` | `12` |
| `dynamic-textarea` | component | 04 | `examples.parity.EditorDemo` | `ParityEditorTest` | `04` |
| `exec` | runtime | 21 | `examples.parity.ProcessHandoffDemo` | `ParityLifecycleTest` | `21` |
| `eyes` | application | 17 | `examples.parity.MouseDemo` | `ParityMouseTest` | `17` |
| `file-picker` | component | 14 | `examples.parity.FilePickerDemo` | `ParityFilePickerTest` | `14` |
| `focus-blur` | runtime | 02 | `examples.parity.KeyboardFocusDemo` | `ParityKeyboardTest` | `02` |
| `fullscreen` | runtime | 20 | `examples.parity.TerminalPresentationDemo` | `ParityPresentationTest` | `20` |
| `glamour` | component | 15 | `examples.parity.MarkdownDemo` | `ParityMarkdownTest` | `15` |
| `help` | component | 01 | `examples.parity.KeymapHelpDemo` | `ParityKeymapTest` | `01` |
| `http` | unsupported | - | - | - | - |
| `isbn-form` | application | 05 | `examples.parity.TextInputDemo` | `ParityTextInputTest` | `05` |
| `keyboard-enhancements` | runtime | 02 | `examples.parity.KeyboardFocusDemo` | `ParityKeyboardTest` | `02` |
| `list-default` | component | 11 | `examples.parity.RichListDemo` | `ParityListTest` | `11` |
| `list-fancy` | component | 11 | `examples.parity.RichListDemo` | `ParityListTest` | `11` |
| `list-simple` | component | 11 | `examples.parity.RichListDemo` | `ParityListTest` | `11` |
| `mouse` | component | 17 | `examples.parity.MouseDemo` | `ParityMouseTest` | `17` |
| `package-manager` | runtime | 23 | `examples.parity.InlineIoDemo` | `ParityIoTest` | `23` |
| `pager` | component | 12 | `examples.parity.ViewportDemo` | `ParityViewportTest` | `12` |
| `paginator` | component | 06 | `examples.parity.PaginatorDemo` | `ParityPaginatorTest` | `06` |
| `pipe` | runtime | 23 | `examples.parity.InlineIoDemo` | `ParityIoTest` | `23` |
| `prevent-quit` | runtime | 22 | `examples.parity.ApplicationControlDemo` | `ParityControlTest` | `22` |
| `print-key` | runtime | 02 | `examples.parity.KeyboardFocusDemo` | `ParityKeyboardTest` | `02` |
| `program-status` | runtime | 20 | `examples.parity.TerminalPresentationDemo` | `ParityPresentationTest` | `20` |
| `progress-animated` | component | 08 | `examples.parity.ProgressDemo` | `ParityProgressTest` | `08` |
| `progress-bar` | component | 08 | `examples.parity.ProgressDemo` | `ParityProgressTest` | `08` |
| `progress-download` | application | 08 | `examples.parity.ProgressDemo` | `ParityProgressTest` | `08` |
| `progress-static` | component | 08 | `examples.parity.ProgressDemo` | `ParityProgressTest` | `08` |
| `query-term` | runtime | 18 | `examples.parity.TerminalQueryDemo` | `ParityTerminalQueryTest` | `18` |
| `realtime` | runtime | 22 | `examples.parity.ApplicationControlDemo` | `ParityControlTest` | `22` |
| `result` | application | 01 | `examples.parity.KeymapHelpDemo` | `ParityKeymapTest` | `01` |
| `send-msg` | runtime | 22 | `examples.parity.ApplicationControlDemo` | `ParityControlTest` | `22` |
| `sequence` | runtime | 22 | `examples.parity.ApplicationControlDemo` | `ParityControlTest` | `22` |
| `set-terminal-color` | runtime | 20 | `examples.parity.TerminalPresentationDemo` | `ParityPresentationTest` | `20` |
| `set-window-title` | runtime | 20 | `examples.parity.TerminalPresentationDemo` | `ParityPresentationTest` | `20` |
| `simple` | application | 01 | `examples.parity.KeymapHelpDemo` | `ParityKeymapTest` | `01` |
| `space` | application | 12 | `examples.parity.ViewportDemo` | `ParityViewportTest` | `12` |
| `spinner` | component | 07 | `examples.parity.SpinnerDemo` | `ParitySpinnerTest` | `07` |
| `spinners` | component | 07 | `examples.parity.SpinnerDemo` | `ParitySpinnerTest` | `07` |
| `splash` | application | 09 | `examples.parity.CountdownDemo` | `ParityCountdownTest` | `09` |
| `split-editors` | component | 04 | `examples.parity.EditorDemo` | `ParityEditorTest` | `04` |
| `stopwatch` | component | 10 | `examples.parity.StopwatchDemo` | `ParityStopwatchTest` | `10` |
| `suspend` | runtime | 21 | `examples.parity.ProcessHandoffDemo` | `ParityLifecycleTest` | `21` |
| `table` | component | 13 | `examples.parity.TableDemo` | `ParityTableTest` | `13` |
| `table-resize` | component | 13 | `examples.parity.TableDemo` | `ParityTableTest` | `13` |
| `tabs` | component | 16 | `examples.parity.TabsDemo` | `ParityTabsTest` | `16` |
| `textarea` | component | 04 | `examples.parity.EditorDemo` | `ParityEditorTest` | `04` |
| `textinput` | component | 05 | `examples.parity.TextInputDemo` | `ParityTextInputTest` | `05` |
| `textinputs` | component | 05 | `examples.parity.TextInputDemo` | `ParityTextInputTest` | `05` |
| `timer` | component | 09 | `examples.parity.CountdownDemo` | `ParityCountdownTest` | `09` |
| `tui-daemon-combo` | application | 23 | `examples.parity.InlineIoDemo` | `ParityIoTest` | `23` |
| `vanish` | application | 10 | `examples.parity.StopwatchDemo` | `ParityStopwatchTest` | `10` |
| `views` | component | 16 | `examples.parity.TabsDemo` | `ParityTabsTest` | `16` |
| `window-size` | runtime | 02 | `examples.parity.KeyboardFocusDemo` | `ParityKeyboardTest` | `02` |
| `bubbles/clipboard` | component | 19 | `examples.parity.ClipboardDemo` | `ParityClipboardTest` | `19` |
| `tea.Program (composition)` | component | 24 | `examples.parity.WorkspaceDemo` | `ParityAcceptanceTest` | `24` |
| `bubbles (the component set)` | component | 28 | `examples.parity.WidgetGalleryDemo` | `ParityGalleryTest` | `28` |
| `prompts (confirm, select, multi-select, progress)` | component | 29 | `examples.parity.LinePromptsDemo` | `ParityLinePromptsTest` | `29` |
| `rich output (Braxton)` | application | 30 | `examples.parity.RichOutputDemo` | `ParityRichOutputTest` | `30` |
| `developer diagnostics (Braxton)` | application | 31 | `examples.parity.DebugOverlayDemo` | `ParityDebugOverlayTest` | `31` |

## Component gaps and deliberate differences

Every row above maps to behavior Braxton implements. The differences that remain are deliberate and
are recorded here rather than left implicit:

| Area | Reference | Braxton | Why |
| --- | --- | --- | --- |
| Network access | `http`, `progress-download` fetch over the network inside the example | No library primitive fetches anything. A command performs application work, so a download is a `Command.task` in application code - which is why `progress-download` maps to the progress phase rather than to a fetch API | Braxton owns the terminal and the loop, not an HTTP client; a network test would also be non-deterministic |
| Clipboard transport | OSC 52 through the terminal, plus a native clipboard package | `Command.copy`/`Command.readTerminalClipboard` and the in-process buffer; the transport is reported, never assumed | No platform clipboard is linked, so the session says which transport it used |
| Markdown dialect | Glamour's renderer and style set | Braxton's own renderer over CommonMark with its own themes | A renderer is a style decision, and the projection is deterministic and bounded |
| Inline region | `package-manager` uses `tea.Println` above a live region | The same `Command.println`, with the region's placement defined per session | The alternate screen has no scrollback, so `println` is an explicit no-op there |
| Windows | The reference runs there | Not supported; Linux x86_64/ARM64 and macOS ARM64 only | The owned POSIX backend is the supported surface, and native claims are only made for platforms that were actually run |
| Line editing and prompts | The pinned corpus is a full-screen component set with no line-oriented prompt of its own | The public `line` package: a one-row editor with history, search, completion, highlighting and masking, plus `ConfirmPrompt`, `SelectPrompt`, `MultiSelectPrompt` and `ProgressPrompt` in the inline region, phase 29 | A shell, a REPL or a wizard reads one line at a time and must not take the screen, so the inline region is the honest host; the prompts ask the same vocabulary the widget family paints, without an MVU runtime |
| Games and demos | `doom-fire`, `splash`, `vanish`, `space`, `eyes`, `cellbuffer` are canvas-style applications | Built from the covered primitives (`Views`, `TextMeasure`, commands, subscriptions) rather than a canvas widget | There is no retained canvas in the MVU model, and none of these examples need one |

## Evidence

| Row | Evidence |
| --- | --- |
| every `component` and `runtime` row | the named regression test class, and `python3 tools/terminal/verify_terminal.py --parity-phase NN` for the phase's own PTY scenario and packaged demo launch |
| every `application` row | the same phase's demo and test, which is what makes the primitives it uses true |
| the whole matrix | `ParityAcceptanceTest` reads this file and fails if a row names a demo, test or scenario that does not exist, or a phase the verifier or the probe does not dispatch |
| composition | phase `24` adds the catalog (`examples.parity.Showcase`) and the composed workspace (`examples.parity.WorkspaceDemo`) |
| the bundled widget set | phase `28` adds the gallery (`examples.parity.WidgetGalleryDemo`), which places every milestone-4 widget - the two scroll controls, the tree, the menu and palette, the four choice controls, the form, the dialog, toast and notification center, the four charts and the calendar - in one composed scene on four pages. Each widget's own suite and phase prove it alone; the gallery is the evidence that the set composes under one model, one key path and one theme store |
| line editing and prompts | phase `29` adds the prompts (`examples.parity.LinePromptsDemo`): a confirmation read as a line, a single choice, a set of marks and a bounded progress count, each painted in the terminal's own rows, with the four prompts' focused suites and `ParityLinePromptsTest` as the deterministic regression |
| rich output | phase `30` composes an image fallback with time-driven transitions and a stable reduced-motion presentation; `TransitionTest`, `ReducedMotionTest`, image codec tests and `ParityRichOutputTest` provide deterministic evidence |
| developer diagnostics | phase `31` verifies the runtime overlay over real widget interactions, including bounded message history, actual focus paths, geometry, frame timing, toggling and resize; headless snapshot, effect and runtime tests pin the underlying contracts |
| integrated run | `python3 tools/terminal/verify_terminal.py --parity-all` runs every scenario and every packaged demo launch together |

Nothing in this document is a claim about a future phase: a row exists only after its phase's demo,
test and scenario exist, and the acceptance test is what keeps that true.

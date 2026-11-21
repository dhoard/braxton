# Project-specific instructions and configuration

## Licensing

- Braxton's Java and native implementation are MIT licensed.
- Retain the bundled FIGlet fonts under their existing BSD terms and the Unicode
  column-width data under Unicode License V3. Preserve their copyright notices,
  licenses, and provenance in the source and distribution; do not relabel these
  assets as MIT. `LICENSE` covers Braxton's implementation, and `NOTICE` records
  the separately licensed components.

## Validation

- Keep build output under `target/`. Inspect JARs through read-only archive APIs or
  extract into an owned temporary directory; never unpack class/resource trees into
  the repository root.

- The final quality gate is `./build.sh`, run from the repository root. It runs
  `./mvnw clean install` by default; supplied Maven arguments are forwarded. Maven
  compiles the owned POSIX C terminal library during `generate-resources` and packages
  the host binary in the jar. A C compiler is required. Configure external SDLC validation
  to run `./build.sh`; it validates and installs the current artifact for local consumers.
- After code changes, run `./mvnw spotless:apply` and inspect its diff before the final
  gate. Preserve unrelated edits if formatting touches other files.
- If TUI code changes, also use pyte to verify the relevant terminal behavior. Use a
  Python environment containing pyte; if necessary, install it in a dedicated virtual
  environment during an authorized implementation stage. Do not claim terminal
  verification based solely on headless assertions or Maven success.
- Keep benchmarks opt-in. Terminal integration uses the project's own POSIX C library
  through Java 25 FFM. Target Linux (x86_64/ARM64) and macOS (ARM64/x86_64); Windows is unsupported.
  Use platform headers for native structures/constants. Keep native/service types out of
  the public MVU API. Do not copy or translate third-party terminal implementations;
  record specification sources in `src/main/native/README.md`.
- Native extraction first tries `java.io.tmpdir`, then falls back to
  `System.getProperty("user.home")/.local/share/braxton`. Preserve embedded version/platform/
  content-hash isolation, locked atomic publication, and existing mapped binaries. Native
  extraction tests must use owned temporary homes; never write the developer's real cache.
- Default Maven tests must remain native-access-free and TTY-free. Native PTY and pyte
  checks are opt-in developer verification, using owned processes/terminals only.
  Build artifacts contain the host binary; verify each target on its actual platform.

## Language and spelling

- Use United States English spellings in every artifact created or edited: identifiers, comments,
  Javadoc, log and exception messages, CLI and TUI text, configuration keys and values, test names,
  documentation, and plans.
- Use the US form wherever the variants differ. Recurring cases in this repository:
  `canceled`/`canceling` (but `cancellation` keeps the double `l`), `acknowledgment`, `labeled`,
  `behavior`, `color`, `analyze`, `initialize`, `normalize`.
- The rule is forward-looking. Existing non-US spellings are not mass-corrected on their own; correct
  the spelling of text you are already changing, and do not rename an unrelated public identifier,
  test name, or document heading merely to satisfy it.
- Do not change spellings that belong to something outside this project's control: upstream and
  third-party API names and identifiers, quoted external text or protocol fields, and vendored or
  generated trees (`.venv-pyte/`, `target/`, `dist/`).

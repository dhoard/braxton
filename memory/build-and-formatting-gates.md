# Build, formatting, and documentation gates

Historical findings from implementation/review sessions; verify against `PROJECT.md` and `pom.xml`.

## What the gates actually enforce

- The required gate is `./build.sh` (defaults to `clean install`). It builds/installs the vendored
  JLine tree at the version derived from `<revision>`, then runs `./mvnw clean install`. Braxton's
  compile, test, JaCoCo coverage, license-header (Spotless) and packaging steps run in that pass.
- `spotless-maven-plugin` is configured with a **license header only** - there is no formatter step.
  `spotless:apply` therefore never re-indents code, and `spotless:check` passing says nothing about
  formatting.
- The repository's code format is not Google Java Format. Running GJF (even `--aosp
  --skip-sorting-imports`) rewrites nearly every file because the project uses hand-wrapped Javadoc
  (one sentence fragment per line, not reflowed) and tolerates code lines up to roughly 100-120
  columns. Do not apply GJF repo-wide; match the surrounding file instead.
- Braxton compilation is expected to stay warning-free (`./mvnw clean compile` prints no `[WARNING]`
  for `src/`). Warnings from the vendored JLine build (native access, Corrupted channel, one upstream
  skipped `FfmTest`) are expected and are not Braxton failures.
- The documented TUI check is
  `python3 tools/terminal/verify_terminal.py --repeat N --pending-escape --harness`; it compiles
  `tools/terminal/TerminalProbe.java` (package `io.github.dhoard.braxton.internal.runtime`) against
  the packaged jar and needs `pyte`. It is opt-in and never part of the default Maven suite.

## Traps

- **javadoc caps warnings at 100.** `mvn javadoc:javadoc` reports at most 100 doclint warnings
  (`-Xmaxwarns` default), so the printed total stays exactly 100 while the set of reported files
  changes. Never treat "100 warnings" as a stable measurement or claim a reduction from the total;
  check which files appear. Fixing warnings in one package only exposes warnings from later ones.
- **`javadoc:javadoc` is incremental and silent on a second run.** After a run that produced
  warnings, a repeat invocation in the same `target/` can finish in about a second with no output.
  Use `clean javadoc:javadoc` when the warning set matters.
- **`-Xlint:serial` couples public value types to exceptions.** `ComponentFailure` extends
  `RuntimeException`, so its `ComponentPath` field must be serializable, which is why `ComponentPath`
  and `ComponentId` implement `java.io.Serializable`. Removing `Serializable` from them is a
  compile-warning regression in `ComponentFailure`, not a cleanup.
- `./build.sh clean` deletes the vendored JLine `target/` directories too, so the next gate rebuilds
  JLine from source (several minutes) even when Braxton is unchanged.

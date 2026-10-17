# AI Harness example transcript

`session.jsonl` is example data for the AI Harness showcase. It is not a live recording and is
never refreshed at runtime.

- The first turn ("u there?" → reasoning → assistant) is adapted from a local Amanda session log
  (`~/.amanda/.../session-log/*.log`) that was read during authoring.
- The second turn (project listing) is synthetic: it uses the same JSONL record shape but its
  content was written for the showcase.
- Session, input, turn, step, and tool-call identifiers were replaced with `demo-*` values.
- Absolute paths, host details, project-specific instructions, tool output, and any other local
  context were removed or replaced.
- The completed demo loads this file from the classpath. It does not read `~/.amanda`, an external
  session path, an environment variable, or any user state.

No credentials, tokens, or personal data are present.

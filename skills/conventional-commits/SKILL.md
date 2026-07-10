---
name: conventional-commits
description: Write clear Conventional Commits messages. Use when committing changes or asked to write a commit message.
triggers:
  - "commit message"
  - "write a commit"
---

# Conventional commits

Format: `type(scope): subject`, then a body that explains **why**, not what.

- **type**: `feat` | `fix` | `refactor` | `perf` | `test` | `docs` | `chore` | `build` | `ci`.
- **scope**: the area touched, e.g. `feat(auth):`. Optional but helpful.
- **subject**: imperative, ≤ ~50 chars, no trailing period. "add retry", not "added retry" or
  "adds retry".
- **body** (only when the "why" isn't obvious from the subject): what problem this solves and any
  non-obvious tradeoff. Wrap at ~72 cols. The diff already shows *what* changed — the message
  captures *why*.
- **breaking changes**: add a `BREAKING CHANGE:` footer.

One logical change per commit. If the subject needs "and", it's probably two commits.

Do not add tool/AI attribution or "Generated with" footers unless the project asks for them.

---
name: pr-review
description: Review a diff for correctness first, then cleanup. One line per finding, most-severe first. Use when reviewing a PR, diff, or branch.
triggers:
  - "review this"
  - "code review"
  - "review the diff"
---

# PR review

Correctness outranks style. Every finding must name a concrete failure — inputs/state → wrong
output/crash — not a preference.

**Pass 1 — correctness (the priority):**
- Read each changed hunk AND its enclosing function (bugs hide in unchanged lines the diff
  re-exposes).
- Hunt: inverted/off-by-one conditions, null/undefined deref, missing `await`, falsy-zero checks,
  wrong-variable copy-paste, swallowed errors, unescaped regex, resource leaks.
- **Removed-behavior**: for each deleted line, name the invariant it enforced and find where the new
  code re-establishes it. A dropped guard is a bug.
- **Contracts**: does a changed function's new signature/return/exception break a caller? Grep them.

**Pass 2 — cleanup (only after correctness):** duplicated logic that a helper already covers,
needless complexity, dead code. Name the simpler form.

**Reporting:** one line per finding — `path:line: <severity>: <problem>. <fix>.` Most-severe first.
Skip praise and pure formatting nits. If you can't name a failure scenario, it's not a finding.
State plainly when the diff is clean.

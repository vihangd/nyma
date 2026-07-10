# add-dir

Add extra directory roots to a session so the agent can see beyond the cwd repo.

## What it does

- `/add-dir <path>` — register an additional root (absolute or resolved from cwd).
- `/add-dir remove <path>` — drop a root.
- `/add-dir` (no args) — list active extra roots.
- Active roots are injected into context each run, so `read`/`grep`/`glob` (which accept absolute
  paths) can reach files in the added tree.

## Why

SOTA: "a repo boundary is a context wall" — cross-project visibility is high value (monorepo split,
a sibling library, a spec repo). The paired constraint is **scoping**: added roots are advisory
context, not an unbounded search — keep them few and specific so the agent doesn't drown.

Session-scoped; roots reset each launch.

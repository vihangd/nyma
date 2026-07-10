# todos

A session-scoped todo ledger for long-horizon task tracking.

## What it does

- `todo_write {todos: [{content, status}]}` — set/replace the full list (status:
  `pending` | `in_progress` | `completed`).
- `todo_read` — read the current list.
- A status-line segment shows progress (`☐ done/total`).
- The **open** items are injected into the system prompt each run; **completed items collapse** to a
  one-line count.

## Why

SOTA (2026): explicit plan tracking counters the main long-horizon failure modes — goal drift,
sub-goal incoherence, poor re-planning — for ~10+ pp on long-horizon benchmarks (Plan-and-Act, CUGA
task-ledger). Collapsing completed items follows HIPIF (fold finished sub-goals to cut long-context
interference).

Always on; the injection/segment are hidden until the agent writes a list. Keep exactly one item
`in_progress`; update as steps complete.

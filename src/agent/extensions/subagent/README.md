# subagent

Context-isolated delegation built on model roles.

Design follows the coding-agent consensus (fan-out vs RLM vs tiered researched): single-threaded
EDITS, isolated READ-ONLY exploration/verification. Subagent roles are read-only by default; the
editing `worker` role is opt-in. Recursion is capped at depth 1 structurally — children don't get
the `subagent` tool.

- Tool `subagent {agent, task, steps?}` — spawn an isolated child run; only its final answer
  returns to the parent context. `steps` is the child's tool-call budget (default 20); the
  parent is told to size it to the task (3-4 for a lookup, 8-12 for a multi-file change), and a
  per-item `steps` in `tasks:[…]` / `chain:[…]` overrides it. A child that runs out of steps
  still reports: the loop's `step-cap-report` gives it one tool-less call to say what it has.
- The tool description tells the parent that a subagent has no memory of the conversation, so
  every task must be self-contained, and never to hand two parallel subagents the same file.
  Both borrowed from [apprentice](https://github.com/skarnati20/apprentice).
- Command `/agents` — inspect available roles.
- Depends on `model-roles` for per-role model resolution. `/role lead` (model_roles) is the
  delegation-only primary that lives on this tool.

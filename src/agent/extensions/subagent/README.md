# subagent

Context-isolated delegation built on model roles.

Design follows the coding-agent consensus (fan-out vs RLM vs tiered researched): single-threaded
EDITS, isolated READ-ONLY exploration/verification. Subagent roles are read-only by default; the
editing `worker` role is opt-in. Recursion is capped at depth 1 structurally — children don't get
the `subagent` tool.

- Tool `subagent {role, task}` — spawn an isolated child run; only its final answer returns to
  the parent context.
- Command `/agents` — inspect available roles.
- Depends on `model-roles` for per-role model resolution.

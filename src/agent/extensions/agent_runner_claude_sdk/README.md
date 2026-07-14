# agent-runner-claude-sdk

In-process Claude Agent SDK runner for agent-shell. Registers the `:claude-sdk` backend using
`@anthropic-ai/claude-agent-sdk` (bundled binary) — no external ACP process. The connection map
matches the ACP path so all agent-shell UI plumbing (sessions, permission prompts, status) works
unchanged.

Depends on `agent-shell`. Select it like any other backend via the agent switcher.

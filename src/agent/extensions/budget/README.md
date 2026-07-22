# budget

Token budgets — Codex-style rollout caps. When a per-turn or per-session token cap is exceeded,
the in-flight run is aborted so a runaway loop can't burn tokens unattended (complements
`max-steps`, which bounds steps, not tokens).

## Config (`.nyma/settings.json#budget`)

```json
{ "budget": { "turn-tokens": 150000, "session-tokens": 2000000 } }
```

Off unless a cap is set. The turn counter resets on each run; the session counter never resets.
Exceeding either logs a warning and aborts via the run's AbortController — partial work is kept,
the agent just stops.

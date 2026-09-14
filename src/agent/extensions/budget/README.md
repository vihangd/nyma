# budget

Token budgets — Codex-style rollout caps. When a per-turn or per-session token cap is exceeded,
the in-flight run is aborted so a runaway loop can't burn tokens unattended (complements
`max-steps`, which bounds steps, not tokens).

## How to enable

There is no `budget.enabled` switch and no `--ext-budget` flag: the extension is on exactly when
you have set a cap. Put one in `.nyma/settings.json`:

```json
{ "budget": { "turn-tokens": 150000 } }
```

Any one of `turn-tokens`, `session-tokens` or `wall-seconds` arms it; with all three unset it
registers no listeners at all. `/extensions` lists budget as loaded either way — loaded with no cap
means loaded and doing nothing. Those three are the only keys read; anything else under `budget`
is ignored.

## Config (`.nyma/settings.json#budget`)

```json
{ "budget": { "turn-tokens": 150000, "session-tokens": 2000000 } }
```

Off unless a cap is set. Usage accumulates per step (`turn_end` fires on every streamText step),
so exceeding a cap aborts the run **between tool steps** — partial work is kept, the agent just
stops. The turn counter resets on each run; the session counter never resets (after the session
cap trips, each new submit is aborted at its first step boundary).

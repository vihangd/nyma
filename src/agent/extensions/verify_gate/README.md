# verify-gate

Verify-before-done: after any turn that edited files, run a configured quality command; if it
fails, the output is injected as a follow-up so the agent fixes the failures before stopping.

## How to enable

There is no `verify.enabled` switch and no `--ext-verify-gate` flag: the gate is on exactly when
`verify.cmd` is set. Put one in `.nyma/settings.json`:

```json
{ "verify": { "cmd": "bun test" } }
```

With `cmd` unset the extension loads and subscribes to nothing. `cmd`, `max-attempts` and
`timeout-ms` are the only keys read — if you write any other key under `verify`, the gate takes
that as a misspelling and says so once at session start, rather than staying silent about a
section you clearly meant to switch something on with.

## Why

Loop-engineering SOTA (2026): reliable agent loops need a deterministic check that can say *no* —
a test suite, a typecheck — not just the model's own judgment. Verifier-guided repair shows
dramatic gains where checks are strong.

## Config (`.nyma/settings.json#verify`)

```json
{ "verify": { "cmd": "bun test", "max-attempts": 2, "timeout-ms": 120000 } }
```

Off unless `cmd` is set. The gate runs after *every* edited turn — including the final fix attempt.
`max-attempts` only caps how many fix follow-ups are sent; once reached, a still-red run produces a
report-only follow-up ("do not edit further — summarize the failures") instead of another fix loop.
The follow-up explicitly forbids weakening tests to pass the gate.

# verify-gate

Verify-before-done: after any turn that edited files, run a configured quality command; if it
fails, the output is injected as a follow-up so the agent fixes the failures before stopping.

## Why

Loop-engineering SOTA (2026): reliable agent loops need a deterministic check that can say *no* —
a test suite, a typecheck — not just the model's own judgment. Verifier-guided repair shows
dramatic gains where checks are strong.

## Config (`.nyma/settings.json#verify`)

```json
{ "verify": { "cmd": "bun test", "max-attempts": 2, "timeout-ms": 120000 } }
```

Off unless `cmd` is set. `max-attempts` caps the fix loop so a stubbornly red suite can't spin
forever; after the cap the turn ends normally with the red output visible. The follow-up
explicitly forbids weakening tests to pass the gate.

# advisor

Model-as-critic: the executor model can call an `advisor` tool that consults a stronger reviewer
model over the full conversation transcript and returns a plan, correction, or stop signal.
Mirrors Anthropic's advisor tool (Claude API beta, April 2026); measured +2.7pp on SWE-bench
Multilingual at −11.9% cost (Sonnet + Opus advisor vs Sonnet alone).

- Tool `advisor` — invoked by the model when stuck or before big decisions.
- Command `/advisor` — configure/inspect.
- Reviewer model resolves via model roles; override in settings.

## Extended thinking

The advisor asks for extended thinking at level `high` by default, rather than inheriting
the session level. It does not go through `agent.loop`, so `/thinking` and `--thinking` do
not reach it — and a reviewer whose entire job is careful reasoning is the one call where
the session default of `off` is clearly wrong. It is a low-volume, high-stakes call, so the
extra tokens are worth spending.

Override per role, including turning it off:

```json
{ "roles": {
    "advisor": { "provider": "anthropic", "model": "claude-opus-5",
                 "thinking": "off" }
}}
```

Valid levels: `off`, `minimal`, `low`, `medium`, `high`, `xhigh`. An unrecognised value
falls back to `high` rather than being forwarded, since the provider would reject it.

Only providers with a reasoning dialect receive anything — Anthropic, Google, and OpenAI on
`/responses`. A model reached over a plain OpenAI-compatible endpoint gets no reasoning
parameter, because most models served that way reject one.

`maxOutputTokens` scales with the level. Anthropic counts reasoning against `max_tokens`, so
the flat 2048 cap would make a request with a 24k thinking budget invalid.

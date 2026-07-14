# advisor

Model-as-critic: the executor model can call an `advisor` tool that consults a stronger reviewer
model over the full conversation transcript and returns a plan, correction, or stop signal.
Mirrors Anthropic's advisor tool (Claude API beta, April 2026); measured +2.7pp on SWE-bench
Multilingual at −11.9% cost (Sonnet + Opus advisor vs Sonnet alone).

- Tool `advisor` — invoked by the model when stuck or before big decisions.
- Command `/advisor` — configure/inspect.
- Reviewer model resolves via model roles; override in settings.

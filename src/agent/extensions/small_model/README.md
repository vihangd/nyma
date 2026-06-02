# small-model

> Opt-in adaptation layer that makes small and local LLMs viable for real coding work — quality monitoring, per-model tuning profiles, session evidence, smart read capping, thinking budgets, and an autonomous supervisor.

## Why this exists

Coding agents are tuned for frontier models. Research shows scaffold–model fit moves a 9.7B model from **19% → 45%** on Aider Polyglot — not a better model, just a better-fitted scaffold. This extension adds that fit to nyma without changing anything for users running Sonnet/Opus.

Everything is **off by default**. Enabling it changes nothing on frontier models unless you also configure profiles. On small/local models it wires a two-tier quality loop, per-model tuning, and an evidence store that keeps context coherent across compaction.

## Quick start

```jsonc
// .nyma/settings.json
{
  "small-model": {
    "enabled": true
  }
}
```

Or for a single session:

```
nyma --ext-small-model --model ollama/qwen2.5-coder
```

## Tools

| Tool | What it does |
|---|---|
| `small-model__EvidenceAdd` | Store a key fact, finding, or progress note that persists across compaction |
| `small-model__EvidenceGet` | Retrieve a stored snippet by key |
| `small-model__EvidenceList` | List all evidence keys with previews |

## Modules

Each module has its own toggle. All are **off by default** when `enabled: true` — except `quality-monitor`, `evidence`, and `profiles` which default on.

### `quality-monitor` (default: on)

Detects and corrects three failure modes small models hit frequently, with **escalating nudges** (Forge pattern — polite → direct → aggressive across 3 tiers):

| Problem | Detection | Tier-1 response | Tier-3 response |
|---|---|---|---|
| Empty / whitespace turns | `stream_filter` abort | "Please continue or report findings" | "STOP. You MUST call a tool or respond() now." |
| Hallucinated tool names | middleware `:leave` | "Tool X doesn't exist. Available: …" | "STOP. You MUST call one of: …" |
| Exact repeat tool calls | middleware `:leave` (cumulative sig set) | "Already ran X. Try something different." | "STOP repeating X. Change the tool or finish." |

Each violation type tracks its own counter independently. Successful turns reset the counters. Quality signals are forwarded to the `supervisor` module for LLM-level escalation.

Also enforces a **turn budget** (`max-turns`, default 40). When the cap is hit, a follow-up message is injected telling the model to stop and report — preventing the CMU "context ceiling" failure mode where sequential reflection degrades past a plateau.

```jsonc
"quality-monitor": {
  "enabled": true,
  "max-turns": 40,
  "no-progress-streak": 3,
  "adaptive-temperature": true
}
```

### `profiles` (default: on)

Maps `"provider/model"` → tuning parameters. Applied automatically each turn via `model_resolve`, `before_provider_request`, and `tool_access_check`.

```jsonc
"profiles": {
  "enabled": true,
  "model-profiles": {
    "ollama/qwen2.5-coder": {
      "contextLimit": 32768,
      "thinking": "off",
      "temperature": 0.2,
      "resultCap": 6000,
      "allowedTools": ["read", "write", "edit", "bash", "glob", "grep"],
      "editStrategy": "patch"
    },
    "anthropic/claude-haiku-4-5": {
      "thinking": "low",
      "allowedTools": ["read", "write", "edit", "bash", "glob", "grep", "web_search"]
    }
  }
}
```

| Field | Effect |
|---|---|
| `thinking` | Sets thinking level (`off` / `low` / `medium` / `high` / `xhigh`) via `setThinkingLevel` |
| `temperature` | Injected into `providerOptions` via `before_provider_request` |
| `allowedTools` | Narrows the active tool set for this model (SOTA: tool-call accuracy degrades as toolset grows) |
| `editStrategy` | `"patch"` (default) or `"whole"` — Aider-style per-model edit format selection |

### `evidence` (default: on)

Per-session memory snippets that survive `token_suite` compaction. Use them to record goals, progress, errors, and todo items so the model doesn't lose task context across turns.

Evidence is injected into every system prompt via `before_agent_start`, so it persists through compaction even when old messages are dropped. Schema follows OpenHands' condenser principle: preserve **goals / progress / what's-left / todo**.

```jsonc
"evidence": {
  "enabled": true,
  "max-snippets": 20,
  "max-snippet-chars": 1024
}
```

Typical usage by the model:

```
EvidenceAdd  key="goal"      value="Refactor auth module to use JWT"
EvidenceAdd  key="progress"  value="auth/login.ts done; auth/session.ts next"
EvidenceAdd  key="todo"      value="session.ts, middleware.ts, tests"
EvidenceList  →  shows all snippets
```

### `read-guard` (default: off)

Overrides the built-in `read` tool: when a file exceeds `max-lines` (default 60), returns only the head with a "use grep/glob to find the relevant section, then re-read with a range" directive. Distinct from `token_suite`'s post-hoc truncation — this steers the model toward targeted reads *before* the tokens are spent.

```jsonc
"read-guard": {
  "enabled": true,
  "max-lines": 60
}
```

### `thinking-budget` (default: off)

Caps extended thinking tokens per turn (`thinkingBudget` in `providerOptions`). If the provider returns a budget-overflow error, retries once with thinking disabled. Most useful for models that support extended thinking on cloud providers.

```jsonc
"thinking-budget": {
  "enabled": true,
  "max-tokens": 8000,
  "retry-without-thinking": true
}
```

### `supervisor` (default: off)

Proactive babysitter — the missing half of the `advisor` extension. The advisor is reactive (the model must call it). The supervisor fires automatically on configurable triggers, calls the same `consult-advisor` path under the hood, and injects the returned guidance as a `steer` message.

Implements the **lead/worker split**: `settings.roles.advisor` (typically a stronger or cloud model) supervises the small local worker model.

Triggers:
- **Periodic** — every N turns (`every-n-turns`, default 8)
- **Quality signal** — when `quality_monitor` fires (inter-extension bus)
- **Pre-commit** — before any `git commit` / `git push` / `git merge` bash call
- **Validate failure** — when `validate_repair` reports a test failure (future module)

Bounded by `max-interventions` (default 3) to avoid the "context ceiling" of over-babysitting.

```jsonc
"supervisor": {
  "enabled": true,
  "every-n-turns": 8,
  "max-interventions": 3,
  "pre-commit": true
}
```

Requires the `advisor` extension to be loaded (declared in `dependsOn`).

## Full settings reference

```jsonc
{
  "small-model": {
    "enabled": false,                // master switch
    "quality-monitor": {
      "enabled": true,
      "max-turns": 40,
      "no-progress-streak": 3,
      "adaptive-temperature": true
    },
    "profiles": {
      "enabled": true,
      "model-profiles": {}           // "provider/model" → profile object
    },
    "evidence": {
      "enabled": true,
      "max-snippets": 20,
      "max-snippet-chars": 1024
    },
    "read-guard": {
      "enabled": false,
      "max-lines": 60
    },
    "thinking-budget": {
      "enabled": false,
      "max-tokens": 8000,
      "retry-without-thinking": true
    },
    "supervisor": {
      "enabled": false,
      "every-n-turns": 8,
      "max-interventions": 3,
      "pre-commit": true
    }
  }
}
```

### `respond-tool` (default: off)

Synthetic `respond` tool from [Forge](https://github.com/antoinezambelli/forge) — the technique that moved an 8B model from single-digit to **84%** on structured tool-calling benchmarks.

Small models (~8B) cannot reliably choose between returning bare text and calling a tool. Without guidance they frequently produce bare text when the agent loop expects a tool call, or call tools when they should be responding. Injecting `respond(message)` forces every output through a structured path: the model MUST either call a real tool or call `respond()` to reply.

**Nyma-native implementation** (cleaner than Forge's proxy approach):

1. `before_provider_request` — inject `respond` into the tools map
2. Middleware `:leave` — when `respond` fires, save the message arg and set a flag
3. Next `before_provider_request` — return `{:block true, :reason message}`, which the loop (loop.cljs:208–213) stores as a clean assistant message and emits `agent_end`

The `respond` tool call is stripped from message storage via `message_before_store`, so from the user's perspective the exchange looks like a normal text response.

```jsonc
"respond-tool": {
  "enabled": true
}
```

## Hooks used

| Event | Module | Behaviour |
|---|---|---|
| `before_agent_start` | `evidence` | Inject evidence block into system prompt |
| `stream_filter` | `quality-monitor` | Abort on empty/whitespace turns; reinject nudge |
| `after_provider_request` | `quality-monitor`, `supervisor` | Advance turn counter; periodic supervisor check-in |
| `model_resolve` | `profiles` | Apply thinking level for active model |
| `before_provider_request` | `profiles`, `thinking-budget` | Inject temperature; inject `thinkingBudget` |
| `tool_access_check` | `profiles` | Narrow active tool set per model profile |
| `permission_request` | `supervisor` | Pre-commit gate — advise before destructive git ops |
| `provider_error` | `thinking-budget` | Retry with thinking off on budget overflow |
| `agent_end` | `thinking-budget` | Reset retry flag |
| `small-model/quality-signal` | `supervisor` | Escalate LLM quality signal to advisor |
| middleware `:leave` | `quality-monitor`, `respond-tool` | Track tool-call signatures; flag repeats/hallucinations; capture respond() calls |

## Full settings reference

```jsonc
{
  "small-model": {
    // ... (existing keys) ...
    "respond-tool": {
      "enabled": false    // set true to force structured output mode
    }
  }
}
```

## Capabilities

`events`, `model`, `context`, `middleware`, `tools`, `tools-override`, `flags`, `commands`, `state`, `messages`

## What's not here (and why)

| Technique | Status | Reason |
|---|---|---|
| Tool-result truncation | **Exists** in `token_suite` | Don't rebuild |
| Context compaction / folding | **Exists** in `token_suite` | Don't rebuild |
| Model switching | **Exists** in `model_roles` | Don't rebuild |
| Rescue parsing (Qwen XML / Mistral bracket-tag) | **In `custom_provider_local`** | Lives at the provider layer; enable per-entry with `rescueParsing: true` |
| Guided/constrained decoding | **Deferred** | High value; needs provider `:fetch` hook + opt-in due to "Format Tax" |
| Edit→validate→repair loop | **Deferred** | SELF-REFINE pattern; needs configured test command |
| Step enforcer (required\_steps + prerequisites) | **Deferred** | Forge's `StepEnforcer` is the blueprint; maps well to nyma's `tool_access_check` |
| Apply-model delegation (Fast-Apply) | **Deferred** | Needs a separate fast-apply endpoint |
| Agentless pipeline mode | **Deferred** | Different scaffold; large scope |

## See also

- [`custom_provider_local/README.md`](../custom_provider_local/README.md) — pair extension for ollama / LM Studio
- [`advisor/`](../advisor/) — the `consult-advisor` implementation the supervisor reuses
- [`model_roles/`](../model_roles/) — complementary: name→model mapping (profiles = model→tuning)
- [`token_suite/`](../token_suite/) — context compression that evidence survives
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide

# custom-provider-groq

> Groq provider — OpenAI-compatible `/chat/completions` endpoint at LPU-accelerated inference speeds.

## What it does

Registers a `groq` provider routing to Groq's API (`https://api.groq.com/openai/v1`) via `@ai-sdk/openai`'s `createOpenAI().chat()`. Covers all current production + preview chat models. Audio and Whisper models are excluded (not chat-completions).

Signup is free. Free-tier keys work immediately — no credit card required.

## Setup

**Option 1 — environment variable (recommended):**

```bash
export GROQ_API_KEY=gsk_...
```

**Option 2 — interactive login:**

```
/login groq
```

Stores the key in `~/.nyma/credentials.json`. Get a key at [console.groq.com/keys](https://console.groq.com/keys).

**Custom base URL** (optional):

```bash
export GROQ_BASE_URL=https://api.groq.com/openai/v1   # default
```

## Models

Switch: `/model groq/<model-id>` — e.g. `/model groq/llama-3.3-70b-versatile`

### Production

| ID | Context | Reasoning | Notes |
|---|---|---|---|
| `llama-3.3-70b-versatile` | 128K | — | Groq's general-purpose workhorse; tool calling, parallel calls |
| `llama-3.1-8b-instant` | 128K | — | Fastest option; good for lightweight tasks |
| `openai/gpt-oss-120b` | 128K | ✓ | GPT-OSS 120B; reasoning via `reasoning_format`; sequential tools only |
| `openai/gpt-oss-20b` | 128K | ✓ | GPT-OSS 20B; same reasoning API; sequential tools only |
| `groq/compound` | 128K | — | Groq's agentic system; uses server-side tools — do not send your own `tools` array |
| `groq/compound-mini` | 128K | — | Lighter agentic system; same server-tool caveat |

### Preview

| ID | Context | Reasoning | Notes |
|---|---|---|---|
| `meta-llama/llama-4-scout-17b-16e-instruct` | 128K | — | Llama 4 Scout; parallel tool calling |
| `qwen/qwen3-32b` | 128K | ✓ | Qwen3 thinking; `reasoning_effort: none\|default` |
| `openai/gpt-oss-safeguard-20b` | 128K | ✓ | Safety/moderation model |

## Reasoning models

GPT-OSS and Qwen3-32B support extended reasoning. Control it with Groq's extra params if needed (not wired into nyma defaults today):

- `reasoning_format`: `raw` (think-tags inline — compatible with nyma's think-tag parser), `parsed` (structured `message.reasoning` field), `hidden`.
- `reasoning_effort`: `low`/`medium`/`high` for GPT-OSS; `none`/`default` for Qwen3-32B.
- **Do not combine `reasoning_format: raw` with `tools` or JSON mode** — Groq returns 400. Use `parsed` or `hidden` when tools are active.

## Rate limits (free tier)

Rate limits are per-model and per-organisation. Free-tier example for Llama 3.3 70B:

| Limit | Value |
|---|---|
| Requests/minute | 30 |
| Requests/day | 1,000 |
| Tokens/minute | 12,000 |
| Tokens/day | — |

Developer (paid) tier allows up to 1,000 RPM and 250K–300K TPM. See [console.groq.com/docs/rate-limits](https://console.groq.com/docs/rate-limits) for the full per-model table.

## Known quirks

- **Unsupported parameters** — sending `logprobs`, `logit_bias`, `top_logprobs`, or `messages[].name` returns 400.
- **`n > 1` not supported** — only one completion per request.
- **`temperature: 0`** is silently clamped to `1e-8` (Groq treats strict zero as an error internally but rounds rather than rejecting).
- **GPT-OSS tool calling is sequential** — parallel tool calls (`tool_choice: "required"` with multiple tools) are not supported.
- **`groq/compound` ignores user `tools`** — it runs a server-side tool stack; sending a `tools` array is a no-op or error.
- **Deprecated models** — llama-4-maverick (removed 2026-03-09), kimi-k2-instruct-0905 (removed 2026-04-15), deepseek-r1-distill-*, gemma*, llama3-*-8192. Do not use these IDs.

## Capabilities

`providers`, `model`

## See also

- [Groq model docs](https://console.groq.com/docs/models)
- [Groq OpenAI compatibility](https://console.groq.com/docs/openai)
- [Groq reasoning docs](https://console.groq.com/docs/reasoning)
- [Groq rate limits](https://console.groq.com/docs/rate-limits)
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — provider authoring guide

# custom-provider-openrouter

> OpenRouter provider — OpenAI-compatible `/chat/completions` endpoint, Bearer auth, curated free-model catalog.

## What it does

Registers an `openrouter` provider routing to OpenRouter (`https://openrouter.ai/api/v1`) via `@ai-sdk/openai`'s `createOpenAI().chat()` — same protocol and wire format as OpenAI's Chat Completions API. Includes OpenRouter's meta-router (`openrouter/free`) plus a curated list of free models.

Unlike OpenCode Zen, **OpenRouter's free tier requires no credit card** — sign up, create a key, use `:free` models immediately.

## Setup

**Option 1 — environment variable (recommended):**

```bash
export OPENROUTER_API_KEY=your-key-here
```

**Option 2 — interactive login:**

```
/login openrouter
```

Stores the key in `~/.nyma/credentials.json`. Get a key at [openrouter.ai/keys](https://openrouter.ai/keys).

**Optional environment overrides:**

```bash
export OPENROUTER_BASE_URL=https://openrouter.ai/api/v1   # default
export OPENROUTER_REFERER=https://my-app.example.com      # for attribution ranking
export OPENROUTER_TITLE="My App"                          # display name in OpenRouter dashboards
```

`HTTP-Referer` and `X-Title` are sent automatically; overriding them only matters if you fork nyma and want your own attribution.

## Models

Switch: `/model openrouter/<model-id>` — e.g. `/model openrouter/openrouter/free` (yes, the meta-router's wire id is literally `openrouter/free`).

All registered models are zero-cost per token.

| Model ID | Context | Notes |
|---|---|---|
| `openrouter/free` | 200K | Meta-router — picks a free model that supports the capabilities your request needs (tools, vision, structured output) |
| `openai/gpt-oss-120b:free` | 131K | OpenAI open-weight 120B; strong general-purpose + tool use |
| `openai/gpt-oss-20b:free` | 131K | Smaller, faster open-weight |
| `nvidia/nemotron-3-nano-30b-a3b:free` | 256K | Efficient MoE; long context |
| `nvidia/nemotron-3-super-120b-a12b:free` | 262K | Larger MoE with trial-use caveat (see quirks) |
| `qwen/qwen3-coder:free` | 262K | Best free coding model |
| `meta-llama/llama-3.3-70b-instruct:free` | 66K | Strong general-purpose |
| `z-ai/glm-4.5-air:free` | 131K | Reasoning/agentic |
| `google/gemma-4-31b-it:free` | 262K | Google open-weight instruct |
| `minimax/minimax-m2.5:free` | 205K | MiniMax reasoning model, free variant |

For the full catalog and capability flags (tools, vision, response_format), fetch `GET https://openrouter.ai/api/v1/models` — OpenRouter publishes live metadata there.

## Rate limits on free models

As of April 2026 (OpenRouter changes these periodically — check [openrouter.zendesk.com](https://openrouter.zendesk.com/) for current values):

- **20 requests/minute** on any `:free` model.
- **50 requests/day** on `:free` models with no purchased credits.
- **1,000 requests/day** on `:free` models once you've purchased ≥ $10 in credits.

Free models are not intended for production workloads — expect 429s under sustained load.

## Known quirks

- **Meta-router capability gating.** `openrouter/free` runs a preflight that inspects the request (does it use tools? vision? structured outputs?) and filters the pool accordingly. Requests that need a capability no currently-free model supports will fail.
- **Attribution headers.** nyma sends `HTTP-Referer` and `X-Title` so this app shows up properly in OpenRouter's ranking page. Override with `OPENROUTER_REFERER` / `OPENROUTER_TITLE` env vars if you fork.
- **`:free` is a wire-protocol suffix**, not a nyma convention — always include it when addressing free variants (`qwen/qwen3-coder:free` is different from `qwen/qwen3-coder`).

## Capabilities

`providers`, `model`

## See also

- [OpenRouter docs](https://openrouter.ai/docs)
- [OpenRouter free-models collection](https://openrouter.ai/collections/free-models)
- [OpenRouter free-models router](https://openrouter.ai/docs/guides/routing/routers/free-models-router)
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — provider authoring guide

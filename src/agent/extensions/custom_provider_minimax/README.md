# custom-provider-minimax

> MiniMax M2.x provider — OpenAI-compatible API, API key auth, designed for the MiniMax Coding / Token Plan.

## What it does

Registers a `minimax` provider that routes to MiniMax M2.x reasoning models via the OpenAI-compatible endpoint (`api.minimax.io/v1`). Designed for the **MiniMax M2 Coding / Token Plan** flat-rate subscription, though standard pay-as-you-go keys work too — the same endpoint accepts both, with quota enforced server-side per key type.

## Setup

**Option 1 — environment variable (recommended):**

```bash
export MINIMAX_API_KEY=your-key-here
```

**Option 2 — interactive login:**

```
/login minimax
```

Pastes the key into `~/.nyma/credentials.json`. Get your key from [platform.minimax.io](https://platform.minimax.io/) → Account → Token Plan.

**China region** (optional):

```bash
export MINIMAX_BASE_URL=https://api.minimaxi.com/v1
```

Default is the international endpoint (`api.minimax.io`). Note the different spelling: `minimaxi.com` (China) vs `minimax.io` (international).

## Models

All models share a **204,800-token context window** and are reasoning-capable.

| Model ID | Speed | Notes |
|---|---|---|
| `MiniMax-M2` | — | General agentic / reasoning baseline |
| `MiniMax-M2.1` | 60 tps | |
| `MiniMax-M2.1-highspeed` | 100 tps | |
| `MiniMax-M2.5` | 60 tps | |
| `MiniMax-M2.5-highspeed` | 100 tps | |
| `MiniMax-M2.7` | 60 tps | Latest model |
| `MiniMax-M2.7-highspeed` | 100 tps | |

Switch models:

```
/model minimax/MiniMax-M2.7
/model minimax/MiniMax-M2.7-highspeed
```

## Coding Plan rate limits

Limits apply per rolling 5-hour window, by subscription tier:

| Tier | Requests / 5 h |
|---|---|
| Starter | 1,500 |
| Plus | 4,500 |
| Max | 15,000 |
| Highspeed tiers | 4,500 – 30,000 |

## Known quirks

- **Temperature** must be in `(0.0, 1.0]` — not the wider `[0, 2]` range OpenAI allows. Nyma's default of `1.0` is fine; adjust only if you explicitly lower it.
- **`n` must be 1** — already enforced by how nyma calls the API.
- **`presence_penalty`, `frequency_penalty`, `logit_bias`** are unsupported and will be ignored or cause errors if sent.
- **Tool-call history** — the assistant message containing a tool call must be echoed back in conversation history before appending the tool result (required for reasoning continuity). Nyma already does this.

## Capabilities

`providers`, `model`

## See also

- [MiniMax OpenAI-compatible API docs](https://platform.minimax.io/docs/api-reference/text-openai-api)
- [MiniMax Token Plan docs](https://platform.minimax.io/docs/token-plan/intro)
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — provider authoring guide

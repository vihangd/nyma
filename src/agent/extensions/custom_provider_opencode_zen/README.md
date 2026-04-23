# custom-provider-opencode-zen

> OpenCode Zen gateway provider — OpenAI-compatible `/chat/completions` and `/responses` endpoints, Bearer auth, covers all 5 free models and every non-Claude/non-Gemini paid model in Zen's catalog.

## What it does

Registers an `opencode-zen` provider that routes to OpenCode Zen (`https://opencode.ai/zen/v1`) using two endpoint shapes: `/chat/completions` (for MiniMax, GLM, Kimi, Qwen, and the chat-completions free models) and `/responses` (for GPT-5.x, including the free `gpt-5-nano`). Both are wired via `@ai-sdk/openai`'s `createOpenAI` factory with `Authorization: Bearer` auth.

**Not covered by this extension:** Claude 4.x and Gemini 3.x listed in Zen's docs. Those model families use Anthropic Messages and Google generateContent protocols with different auth headers — they require separate extensions.

## Setup

**Option 1 — environment variable (recommended):**

```bash
export OPENCODE_ZEN_API_KEY=your-key-here
```

`OPENCODE_API_KEY` is also accepted as an alias (picked up automatically if set by the OpenCode CLI).

**Option 2 — interactive login:**

```
/login opencode-zen
```

Stores the key in `~/.nyma/credentials.json`. Get your key from the [OpenCode Zen dashboard](https://opencode.ai/auth) after signing up.

**Custom base URL** (optional):

```bash
export OPENCODE_ZEN_BASE_URL=https://opencode.ai/zen/v1
```

## Funded-account caveat

Free models are zero cost per token, but Zen still requires a funded account to issue a key:

> "Add $20 Pay as you go balance" (+$1.23 card processing fee)

You must top up at least once to receive an API key. "Free" means $0/token, not $0 signup.

## Models

Switch models: `/model opencode-zen/<id>` — e.g. `/model opencode-zen/minimax-m2.5-free`

### Free tier

| ID | Endpoint | Context | Input $/1M | Output $/1M |
|---|---|---|---|---|
| `gpt-5-nano` | `/responses` | 400K | Free | Free |
| `minimax-m2.5-free` | `/chat/completions` | 204K | Free | Free |
| `big-pickle` | `/chat/completions` | unpublished | Free | Free |
| `ling-2.6-flash` | `/chat/completions` | unpublished | Free | Free |
| `nemotron-3-super-free` | `/chat/completions` | unpublished | Free | Free |

### Paid — `/responses` (GPT-5.x)

| ID | Input $/1M | Output $/1M |
|---|---|---|
| `gpt-5` / `gpt-5-codex` | $1.07 | $8.50 |
| `gpt-5.1` / `gpt-5.1-codex` | $1.07 | $8.50 |
| `gpt-5.1-codex-max` | $1.25 | $10.00 |
| `gpt-5.1-codex-mini` | $0.25 | $2.00 |
| `gpt-5.2` / `gpt-5.2-codex` | $1.75 | $14.00 |
| `gpt-5.3-codex` / `gpt-5.3-codex-spark` | $1.75 | $14.00 |
| `gpt-5.4` | $2.50 | $15.00 |
| `gpt-5.4-mini` | $0.75 | $4.50 |
| `gpt-5.4-nano` | $0.20 | $1.25 |
| `gpt-5.4-pro` | $30.00 | $180.00 |

### Paid — `/chat/completions`

| ID | Context | Input $/1M | Output $/1M |
|---|---|---|---|
| `minimax-m2.5` / `minimax-m2.7` | 204K | $0.30 | $1.20 |
| `glm-5` | unpublished | $1.00 | $3.20 |
| `glm-5.1` | unpublished | $1.40 | $4.40 |
| `kimi-k2.5` (via Zen) | unpublished | $0.60 | $3.00 |
| `kimi-k2.6` (via Zen) | unpublished | $0.95 | $4.00 |
| `qwen3.5-plus` | unpublished | $0.20 | $1.20 |
| `qwen3.6-plus` | unpublished | $0.50 | $3.00 |

Context windows marked "unpublished" are not documented by Zen. Fetch `GET https://opencode.ai/zen/v1/models` for authoritative metadata.

## Known quirks

- **Free models collect feedback data.** Verbatim from Zen docs: "The team is using this time to collect feedback and improve the model." Applies to MiniMax M2.5 Free, Big Pickle, Ling 2.6 Flash, and Nemotron.
- **Nemotron restriction.** NVIDIA's caveat: "Trial use only — not for production or sensitive data."
- **Token-usage fields may be missing.** Zen's response transform occasionally drops `usage.input_tokens` and similar fields (reported in Zen GH issue #17411 for Claude-via-Zen; may also affect other families under load). Nyma's token accounting may show zeros for some responses.
- **Kimi/MiniMax models are also available as direct providers** (`kimi`, `minimax`) in nyma. Use those for production workloads to avoid Zen's transform layer.

## Capabilities

`providers`, `model`

## See also

- [OpenCode Zen docs](https://opencode.ai/docs/zen/)
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — provider authoring guide

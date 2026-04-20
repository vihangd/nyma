# custom-provider-claude-native

> Native Anthropic SDK provider — uses the `anthropic` package directly instead of going through OpenAI-compatible shims. Lower latency, no format translation overhead.

## What it does

Registers a `claude-native` provider that calls the Anthropic API via the official `@anthropic-ai/sdk` package. Use this provider when you want to run Claude models in nyma with full Anthropic-native streaming and the exact model IDs Anthropic publishes.

## Why use this instead of the built-in Claude support?

Nyma's default model handling routes through the Vercel AI SDK's OpenAI-compatible adapter. `claude-native` bypasses that layer and talks directly to Anthropic's HTTP API. In practice the difference is small, but it can matter for:

- **Exact streaming fidelity** — no format translation between Anthropic SSE and OpenAI delta format
- **Provider-specific fields** — any Anthropic-only response fields pass through unmodified
- **Debugging** — raw Anthropic payloads, no intermediate conversion

## Models

All models have a **200,000-token context window**.

| Model ID | Name |
|----------|------|
| `claude-haiku-4-5-20251001` | Claude Haiku 4.5 |
| `claude-sonnet-4-6` | Claude Sonnet 4.6 |
| `claude-opus-4-6` | Claude Opus 4.6 |
| `claude-opus-4-7` | Claude Opus 4.7 |

## Setup

**Option 1 — environment variable (recommended):**

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

**Option 2 — interactive login:**

```
/login anthropic
```

Saves the key to `~/.nyma/credentials.json` under the `"anthropic"` key. The extension reads from both the environment variable and the credentials file; the environment variable takes precedence.

## Using the provider

Once activated, select the provider and a model in your session:

```
/model claude-native/claude-sonnet-4-6
```

Or set it as the default in `~/.nyma/settings.json`:

```json
{
  "provider": "claude-native",
  "model": "claude-sonnet-4-6"
}
```

## Capabilities required

`providers`, `model`

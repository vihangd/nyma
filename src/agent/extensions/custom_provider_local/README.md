# custom-provider-local

> Generic provider for any local OpenAI-compatible inference server — ollama, LM Studio, llama.cpp, vLLM, and friends. No code required; configure in `settings.json`.

## What it does

Registers local inference servers as first-class nyma providers so you can use `--model ollama/qwen2.5-coder` or `--model lmstudio/devstral-small` exactly like a cloud model. Ships with presets for the two most common local servers; user entries from `settings.json` override or extend them.

All local servers are treated as OpenAI-compatible (`/v1/chat/completions`). API keys are optional — a placeholder is sent when no key is configured, since local servers ignore it.

## Quick start

### ollama (preset — no config needed)

```
# pull a model
ollama pull qwen2.5-coder

# run nyma against it
nyma --model ollama/qwen2.5-coder
```

### LM Studio (preset — no config needed)

Start LM Studio → Local Server → load a model → Start Server, then:

```
nyma --model lmstudio/devstral-small
```

## Built-in presets

| Provider | Base URL | Pre-registered models |
|---|---|---|
| `ollama` | `http://localhost:11434/v1` | `qwen2.5-coder`, `qwen3.5-9b`, `devstral`, `deepseek-coder`, `codellama` |
| `lmstudio` | `http://localhost:1234/v1` | `devstral-small`, `qwen3.6-35b-a3b` |

Pre-registered models only affect the model-picker autocomplete and context-window metadata. You can use **any model ID** the server serves — the `modelId` is passed directly to the endpoint.

## Custom configuration

Add entries to `settings.json` under `"local-models"`. User entries **override presets with the same name** and **add new providers**.

```jsonc
// .nyma/settings.json
{
  "local-models": [
    {
      "name":          "ollama",
      "baseUrl":       "http://localhost:11434/v1",
      "modelId":       "qwen2.5-coder",       // shown in model picker
      "contextWindow": 32768,
      "apiKeyEnv":     "OLLAMA_API_KEY"        // optional; ignored if unset
    },
    {
      "name":          "lmstudio",
      "baseUrl":       "http://localhost:1234/v1",
      "contextWindow": 32768
    },
    {
      "name":          "vllm",
      "baseUrl":       "http://gpu-box:8000/v1",
      "apiKeyEnv":     "VLLM_API_KEY",
      "contextWindow": 131072
    }
  ]
}
```

| Field | Required | Default | Description |
|---|---|---|---|
| `name` | yes | — | Provider name; used as the `--model <name>/<id>` prefix |
| `baseUrl` | yes | — | OpenAI-compatible base URL |
| `contextWindow` | no | 32768 | Context window for registered models |
| `apiKeyEnv` | no | — | Env var to read the API key from; placeholder used if missing |

## Usage with `--model`

```
nyma --model <provider-name>/<model-id>
```

Examples:

```
nyma --model ollama/qwen2.5-coder
nyma --model ollama/llama3.2:3b
nyma --model lmstudio/devstral-small
nyma --model vllm/mistral-nemo-12b
```

The `<model-id>` is passed verbatim to the server. For ollama, use the same tag you pulled with `ollama pull`.

## Rescue parsing (toolcall-adapter)

Local models frequently emit tool calls in non-standard formats that the AI SDK cannot parse. The bundled toolcall adapter normalises these transparently at the HTTP layer before the SDK sees them.

Ported from [Forge](https://github.com/antoinezambelli/forge) (MIT). Forge moves an 8B model from single-digit to 84% on tool-calling benchmarks largely through this technique.

**Supported formats:**

| Format | Example | Models |
|---|---|---|
| Qwen3-Coder XML | `<function=name><parameter=k>v</parameter></function>` | Qwen3-Coder series |
| Mistral bracket-tag | `[TOOL_CALLS]name{...}` | Devstral-Small-2, Mistral-Small-3.x |
| JSON in code fences | ` ```json\n{"tool":"name","args":{...}}``` ` | Many instruction-tuned models |
| Rehearsal syntax | `tool_name[ARGS]{...}` | Reasoning models (thinking leakage) |

**Enable per-provider** in `settings.json`:

```jsonc
{
  "local-models": [
    {
      "name":          "ollama",
      "baseUrl":       "http://localhost:11434/v1",
      "rescueParsing": true          // ← enable the adapter for this provider
    }
  ]
}
```

The adapter wraps the provider's `:fetch` function. It intercepts the SSE stream, detects bare-text responses with no native tool calls, attempts rescue parsing across all four formats, and injects synthetic `tool_calls` in OpenAI JSON format so the AI SDK processes them normally. Non-tool responses pass through unchanged.

## Pairing with `small-model`

For best results on small/local models, enable the [`small-model`](../small_model/README.md) extension alongside this one:

```jsonc
{
  "small-model": {
    "enabled": true,
    "profiles": {
      "model-profiles": {
        "ollama/qwen2.5-coder": {
          "thinking": "off",
          "temperature": 0.2,
          "allowedTools": ["read", "write", "edit", "bash", "glob", "grep"],
          "editStrategy": "patch"
        }
      }
    }
  }
}
```

## Recommended local models (2026)

| Model | Provider tag | Strengths | VRAM |
|---|---|---|---|
| Devstral Small 24B | `ollama/devstral` | Best overall coding on Ollama; agentic multi-file edits | ~16 GB |
| Qwen 3.5 9B | `ollama/qwen3.5-9b` | 45% Aider Polyglot on consumer GPU; fast | ~8 GB |
| Qwen 3.6 35B MoE | `lmstudio/qwen3.6-35b-a3b` | 78% Aider Polyglot; 3B active params, low throughput cost | ~24 GB |
| DeepSeek Coder | `ollama/deepseek-coder` | Strong on structured code; lean context needs | ~8 GB |

## LAN inference

If your GPU is on another machine, point `baseUrl` at its IP:

```jsonc
{
  "name": "remote-gpu",
  "baseUrl": "http://192.168.1.50:11434/v1"
}
```

Then `nyma --model remote-gpu/qwen2.5-coder` runs inference on the remote box while the CLI stays on your laptop.

## Capabilities

`providers`

## See also

- [`small_model/README.md`](../small_model/README.md) — quality / tuning layer that pairs with this one
- [`custom_provider_minimax/`](../custom_provider_minimax/) — cloud provider extension template this was patterned after
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide

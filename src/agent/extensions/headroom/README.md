# headroom

> ML context compression via the [Headroom](https://github.com/chopratejas/headroom) proxy — 60–95% further token reduction on top of `token_suite`.

## What it does

Registers a `context_assembly` hook at **priority 10**, running after all five `token_suite` handlers (95→70). Receives already-pruned messages and applies Headroom's compression algorithms on the residual — complementary double-savings.

**Compression algorithms (all run in the Python proxy):**
- **Kompress-base** — ONNX INT8 text model (84M ModernBERT, ~33MB), trained on agentic traces
- **SmartCrusher** — JSON-aware item selection (keeps errors, anomalies)
- **CodeCompressor** — AST-aware pruning (Python, JS, Go, Rust, Java, C++)
- **LogCompressor** — keeps errors/warnings, drops benign lines
- **SearchCompressor** — keeps top N + errors
- **DiffCompressor** — structured diff compression

RTK and lean-ctx are **completely unaffected** — RTK operates on bash commands (PreToolUse), lean-ctx overrides `read`/`edit` tools at the MCP layer. Headroom compresses LLM message *contents*. Different layers, complementary savings.

## Setup

```bash
# Install Python proxy (includes onnxruntime ONNX CPU backend, no torch required)
pip install "headroom-ai[proxy]"

# Start proxy
headroom proxy --port 8787

# Apple Silicon — CoreML backend for better performance
HEADROOM_KOMPRESS_BACKEND=onnx_coreml headroom proxy --port 8787
```

Enable in `.nyma/settings.json`:

```jsonc
{
  "headroom": {
    "enabled": true
  }
}
```

If the proxy is not running when Nyma starts, the extension logs one warning and skips compression silently — no errors, no broken sessions.

## Configuration

```jsonc
{
  "headroom": {
    "enabled": false,
    "proxyUrl": "http://localhost:8787",  // change if running proxy on another port
    "compressionThreshold": 0.5,          // activate above 50% context fill
    "minTokensToCompress": 8000,          // skip on short sessions (ONNX warmup)
    "algorithms": ["SmartCrusher", "CodeCompressor", "Kompress"],
    "disableCcr": true                    // see CCR section below
  }
}
```

## Commands

| Command | What it does |
|---|---|
| `/headroom-stats` | Show compression statistics — calls, tokens saved, ratio, errors |

## Provider compatibility

The extension calls the proxy as a **compression service** — all providers still connect to their actual endpoints directly.

| Provider | Works | Notes |
|---|---|---|
| omlx / ollama / lmstudio (local) | ✓ | Messages compressed before the call; proxy-as-forwarder would be circular |
| anthropic / claude-native | ✓ | Also works as true transparent proxy: `HTTPS_PROXY=http://localhost:8787` (adds CacheAligner + tool normalization) |
| opencode-zen / openrouter / deepseek / kimi / groq / minimax | ✓ | Also works as true transparent proxy: set `baseUrl` in `settings.json` |

## Transparent proxy mode (optional, cloud providers only)

For additional benefits (CacheAligner for KV cache hits, tool-definition normalization):

```bash
# Anthropic — zero code change
HTTPS_PROXY=http://localhost:8787 nyma

# OpenAI-compatible cloud providers — override baseUrl in .nyma/settings.json
# "local-models": [{"name": "openrouter", "baseUrl": "http://localhost:8787"}]
```

Not for local providers (omlx at 8000, ollama at 11434) — would create a circular loop.

## CCR (reversible compression) — deferred

When `disableCcr: false`, the proxy injects `<<ccr:HASH>>` markers where compression happened. The original content can be recovered via the `headroom_retrieve(hash)` tool.

To enable CCR, first run `headroom mcp install` — this registers the MCP tools including `headroom_retrieve` in `.mcp.json`, which `mcp_client` auto-discovers. Then set `"disableCcr": false` in settings.

## Capabilities

`events`, `commands`

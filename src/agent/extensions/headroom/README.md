# headroom

> ML context compression via the [Headroom](https://github.com/chopratejas/headroom) proxy — 60–95% further token reduction on top of `token_suite`, using a trained ONNX model alongside six content-aware algorithms.

## Why this exists

`token_suite` compresses context heuristically: masking stale observations, pruning low-priority messages, archiving old turns. Headroom adds a **trained ML layer** on top — after `token_suite` has pruned the conversation, Headroom compresses the residual using algorithms that understand content semantics (JSON structure, AST shape, log levels, diff hunks) and a ModernBERT-based text model that was trained specifically on agentic traces.

The two layers are complementary. `token_suite` removes messages; Headroom compresses the ones that survive.

Empirical savings (Headroom's published benchmarks):
| Session type | Input tokens | After Headroom | Saved |
|---|---|---|---|
| Code search (100 results) | 17,765 | 1,408 | **92%** |
| SRE incident debugging | 65,694 | 5,118 | **92%** |
| GitHub issue triage | 54,174 | 14,761 | **73%** |
| Codebase exploration | 78,502 | 41,254 | **47%** |

Accuracy is preserved: 97% on SQuAD v2 QA at 19% compression; 97% on BFCL tool-calling at 32% compression.

## How it works

```
User message → loop.cljs
  → context_assembly (priority 95-70): token_suite prunes stale/low-priority messages
  → context_assembly (priority 10):    headroom compresses the survivors
       ↓
  POST http://localhost:8787/headroom/compress
  { messages, model, algorithms, disableCcr }
       ↓
  Python proxy (Rust axum + Python runtime):
    1. ContentRouter detects type per message block (JSON / code / logs / text)
    2. Dispatches to appropriate algorithm:
         JSON tool results     → SmartCrusher (keeps errors, anomalies, rare values)
         Source code           → CodeCompressor (AST-aware, language-detected)
         Bash / log output     → LogCompressor (keeps ERROR/WARN, drops INFO)
         Search results        → SearchCompressor (top N + errors)
         Prose / explanations  → Kompress-base (ONNX INT8 text model)
    3. Token-validates: never returns output with >= input tokens
    4. Returns { messages, tokensSaved, compressionRatio }
       ↓
  Compressed messages returned to loop — feeds into streamText call
```

All ML runs in the Python proxy. The npm package `headroom-ai` is a client library only.

## Setup

### 1. Install the proxy

```bash
pip install "headroom-ai[proxy]"
```

`[proxy]` includes `onnxruntime` which runs Kompress-base in ONNX INT8 CPU mode (~200–500ms/call, ~33MB model). No torch required.

Optional — only for discrete GPU / PyTorch MPS:
```bash
pip install "headroom-ai[proxy,ml]"
```

### 2. Start the proxy

```bash
# Default
headroom proxy --port 8787

# Apple Silicon — CoreML backend (recommended, fastest, no torch needed)
HEADROOM_KOMPRESS_BACKEND=onnx_coreml headroom proxy --port 8787

# Run as persistent background daemon (survives terminal close)
headroom install persistent --supervisor launchd    # macOS
headroom install persistent --supervisor systemd    # Linux
```

### 3. Enable in Nyma

```jsonc
// .nyma/settings.json
{
  "headroom": {
    "enabled": true
  }
}
```

If the proxy is not running when Nyma starts, the extension logs one warning and skips compression silently — no errors, no broken sessions.

## Configuration

All keys under `"headroom"` in `.nyma/settings.json` or `~/.nyma/settings.json`:

```jsonc
{
  "headroom": {
    "enabled": false,

    // Proxy URL — change if you run the proxy on a different port or host
    "proxyUrl": "http://localhost:8787",

    // Only compress when context fill exceeds this fraction (0–1)
    // Avoids ONNX model warmup latency on short sessions
    "compressionThreshold": 0.5,

    // Minimum message tokens before compression is attempted
    "minTokensToCompress": 8000,

    // Which algorithms to request (proxy picks the best per content type)
    "algorithms": ["SmartCrusher", "CodeCompressor", "Kompress"],

    // Disable reversible CCR markers (safe default — see CCR section)
    "disableCcr": true
  }
}
```

### Available algorithms

| Algorithm | Best for | Notes |
|---|---|---|
| `SmartCrusher` | JSON tool results (large arrays, search hits) | Keeps errors, anomalies, rare values |
| `CodeCompressor` | Source code in messages | AST-aware; supports Python, JS, Go, Rust, Java, C++ |
| `LogCompressor` | Bash output, log files | Keeps ERROR/WARN lines, drops routine INFO |
| `SearchCompressor` | Web search / grep results | Keeps top N + error results |
| `DiffCompressor` | Git diffs, patch output | Preserves structure, compresses context lines |
| `Kompress` | Prose, explanations, mixed content | ONNX INT8 ModernBERT, trained on agentic traces |

## Commands

| Command | What it does |
|---|---|
| `/headroom-stats` | Show per-session stats: compressed turns, tokens saved, ratio, errors |

## Provider compatibility

The extension calls the proxy as a **compression service** — your actual LLM provider is called directly as before. No routing changes.

| Provider | Compress-service | True proxy mode |
|---|---|---|
| **omlx** (localhost:8000) | ✓ | Not recommended (circular: 8787→8000→8787) |
| **ollama** (localhost:11434) | ✓ | Not recommended (same circular issue) |
| **anthropic / claude-native** | ✓ | ✓ Optional: `HTTPS_PROXY=http://localhost:8787` — also adds CacheAligner and tool-definition normalization |
| **opencode-zen / openrouter / deepseek / kimi / groq / minimax** | ✓ | ✓ Optional: override `baseUrl` to `http://localhost:8787` in `settings.json` |

## Transparent proxy mode (cloud providers only, optional)

Running the proxy as a true HTTP reverse proxy adds two extra benefits:
- **CacheAligner**: normalizes system prompt and tool definitions across requests so provider KV caches actually hit
- **Tool-definition normalization**: alpha-sorts tool schemas so prefix hashes are stable

```bash
# Anthropic — zero code change needed
HTTPS_PROXY=http://localhost:8787 nyma --model anthropic/claude-sonnet-4-6

# Cloud OpenAI-compatible providers — override baseUrl per-provider:
# ~/.nyma/settings.json:
# {"local-models": [{"name": "openrouter", "baseUrl": "http://localhost:8787", ...}]}
```

## Interaction with other extensions

### token_suite
The two extensions are designed to stack. `token_suite` runs at priorities 95→70 (removes messages); Headroom runs at priority 10 (compresses survivors). Use both — they operate on different aspects of context.

### small_model
When `small_model` is enabled with a profile that includes `allowedTools`, the tool set is narrower → tool results are smaller → less for Headroom to compress, but Headroom's per-tool compression still applies to whatever remains. They compose cleanly.

### RTK (Rust Token Killer)
Zero interaction. RTK rewrites bash commands at the `PreToolUse` hook layer. Headroom compresses LLM message contents at the `context_assembly` layer. Different stages of the pipeline.

### lean-ctx
Zero interaction. lean-ctx overrides `read`/`edit` tools at the MCP layer to return smarter, more compact results. Headroom then compresses those compact results further in messages. Complementary double-savings.

## Hooks used

| Event | Priority | Behaviour |
|---|---|---|
| `context_assembly` | 10 | Compress messages via proxy; return `{messages: compressed}` |

## CCR — reversible compression (deferred)

When `disableCcr: false`, the proxy replaces compressed blocks with `<<ccr:HASH>>` markers and stores originals in local SQLite. The model can call `headroom_retrieve(hash)` to recover original content on demand.

To enable:
1. `headroom mcp install` — registers MCP tools in `.mcp.json`, auto-discovered by `mcp_client`
2. Set `"disableCcr": false` in settings

First slice ships with `disableCcr: true` — without the `headroom_retrieve` tool registered, CCR markers would be unrecoverable.

## Troubleshooting

**"proxy not reachable" warning on startup**
The proxy isn't running. Start it: `headroom proxy --port 8787`. For a persistent daemon: `headroom install persistent --supervisor launchd` (macOS) or `systemd` (Linux).

**`/headroom-stats` shows 0 calls despite being enabled**
Context fill is below the threshold (default 50%) or messages are below `minTokensToCompress` (default 8000 tokens). Lower the thresholds or run a longer session.

**Proxy is running but compression ratio is 1.0**
The proxy compressed the messages but found no savings (already-compact). This is expected for short tool results or small messages — the proxy never returns output with ≥ input tokens.

**ONNX warmup latency on first call**
Normal — Kompress-base loads the ONNX model on the first request (~1–3s). Subsequent calls are fast. Use CoreML on Apple Silicon to eliminate this: `HEADROOM_KOMPRESS_BACKEND=onnx_coreml`.

## Capabilities

`events`, `commands`

## See also

- [Headroom GitHub](https://github.com/chopratejas/headroom) — proxy source, benchmarks, algorithm details
- [`token_suite/`](../token_suite/) — the heuristic compression layer that runs before Headroom
- [`small_model/`](../small_model/) — per-model tuning that pairs well with Headroom for local models

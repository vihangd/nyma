# lsp-suite

> Language Server Protocol integration — hover, go-to-definition, find-references, document symbols, workspace symbols, and live diagnostics injected into every LLM turn.

## What it does

`lsp-suite` spawns per-language LSP servers on demand and exposes six code-intelligence tools to the LLM. File opens, edits, and writes are automatically synced to the running servers so hover and navigation work on the latest content — no manual re-indexing needed.

**Tools registered** (prefixed `lsp-suite__`):

| Tool | Description |
|------|-------------|
| `hover` | Type info, docs, and signature for a symbol at `file:line:col` |
| `goto_definition` | Jump to the definition site — returns `path:line:col` + preview |
| `find_references` | All reference sites — returns a list of `path:line:col` entries |
| `document_symbols` | Hierarchical symbol tree for a file (classes, functions, variables) |
| `workspace_symbols` | Search symbols across the entire workspace by name query |
| `get_diagnostics` | Current errors, warnings, and hints from all running servers |

All position arguments use **1-based** line and column numbers (same convention as grep output the LLM already sees).

**Diagnostics pipeline**: LSP servers push `textDocument/publishDiagnostics` notifications asynchronously. `lsp-suite` buffers them and injects a `## LSP Diagnostics` section into the system prompt at the start of every agent turn, so the model always sees fresh errors without having to call `get_diagnostics` explicitly.

**File sync**: `tool_complete` hooks detect `read`, `write`, `edit`, and `multi_edit` calls and send the corresponding `textDocument/didOpen`, `textDocument/didChange`, and `textDocument/didSave` notifications — no external file-watcher needed.

## Built-in language servers

Six servers are preconfigured. Any of them will be skipped silently if the binary is not on PATH.

| Language | Server | Extensions | Install |
|----------|--------|------------|---------|
| TypeScript / JavaScript | `typescript-language-server` | `.ts` `.tsx` `.js` `.jsx` `.mjs` `.cjs` | `npm i -g typescript-language-server typescript` |
| Python | `pyright-langserver` | `.py` `.pyi` | `npm i -g pyright` |
| Rust | `rust-analyzer` | `.rs` | `rustup component add rust-analyzer` |
| Go | `gopls` | `.go` | `go install golang.org/x/tools/gopls@latest` |
| Clojure / ClojureScript | `clojure-lsp` | `.clj` `.cljs` `.cljc` `.edn` | [clojure-lsp releases](https://github.com/clojure-lsp/clojure-lsp/releases) |
| Ruby | `ruby-lsp` | `.rb` | `gem install ruby-lsp` |

## Configuration

Override any built-in server or add a new one via the `lsp` key in `.nyma/settings.json` (project-level) or `~/.nyma/settings.json` (global). Project settings win.

```json
{
  "lsp": {
    "typescript": {
      "disabled": false,
      "command": ["typescript-language-server", "--stdio"],
      "env": { "NODE_OPTIONS": "--max-old-space-size=4096" },
      "initializationOptions": {},
      "startupTimeout": 10000,
      "maxRestarts": 3
    },
    "clojure-lsp": {
      "disabled": false,
      "command": ["clojure-lsp"]
    },
    "my-custom-server": {
      "command": ["my-lsp-server", "--stdio"],
      "extensions": [".foo", ".bar"]
    }
  }
}
```

**Disabling a server**: set `"disabled": true`. The extension still loads; tools return a friendly "no server configured" message for that file type.

## Crash recovery

Each LSP client follows a `stopped → starting → running → error → stopped` state machine. If a server crashes while running, it restarts automatically with exponential backoff (500 ms, 1 s, 2 s). After three failed restarts, the client enters a permanent error state and tools for that language fall back to "no server configured".

## Dependencies

`vscode-jsonrpc` is declared in `extension.json` and installed automatically when the extension loads.

## Capabilities required

`tools`, `events`, `middleware`, `session`

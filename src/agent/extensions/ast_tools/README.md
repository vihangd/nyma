# ast-tools

> AST-aware code search and rewrite via [ast-grep](https://ast-grep.github.io/) syntax patterns.

## What it does

Registers two LLM-callable tools — `ast_grep` and `ast_edit` — that drive the `sg` binary for **structural** code search and refactoring across languages. Patterns are matched against the syntax tree, not regex, so `console.log($$$)` matches every JavaScript call site regardless of whitespace, line wrapping, or argument shape. The agent uses these instead of `grep`/`sed` when it needs to find or rewrite code by syntactic structure.

The `sg` binary must be installed and on `PATH`. If absent, the tools return a one-line install hint instead of crashing the run.

## Tools

| Tool | What it does |
|---|---|
| `ast_grep` | Search code with AST patterns. Supports meta-variables (`$NAME`, `$$$ARGS`), language selection, file globs |
| `ast_edit` | Apply structural rewrites with capture groups. `pattern` → `rewrite` over the matching AST nodes |

Tool results are truncated via the standard `tool-result-policy` table (registered by [`token-suite`](../token_suite/)) — large search results are saved to `~/.nyma/tmp/` and the agent gets a summary.

## Capabilities

`tools`, `exec`

## See also

- [ast-grep documentation](https://ast-grep.github.io/) — pattern language, supported languages
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide

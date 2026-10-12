# mcp_client

Connects nyma to [Model Context Protocol](https://modelcontextprotocol.io) servers. Each server's
tools are registered as `mcp__<server>__<tool>` and appear to the model like any native tool.

## Commands

| Command | What it does |
|---|---|
| `/mcp-client__mcp-status` | Show MCP server connection state and tool counts |

## Configuring servers

Servers are read from `~/.nyma/mcp.json`, then `<project>/.nyma/mcp.json`, `.cursor/mcp.json` and
`.mcp.json` (later files win per server name; project files need folder trust). Standard MCP format:

```json
{
  "mcpServers": {
    "my-server": { "command": "some-mcp-binary", "args": ["stdio"], "env": {"TOKEN": "${MY_TOKEN}"} }
  }
}
```

Or from inside nyma — flags first, then the server's own command line, verbatim:

```
/mcp add my-server --env TOKEN=abc -- some-mcp-binary stdio       # → ~/.nyma/mcp.json
/mcp add docs --url https://mcp.example.com/mcp --project         # → ./.mcp.json
/mcp remove my-server
```

`/mcp add` refuses to touch a file that does not parse, keeps every other server, and the new
server starts with the next session.

### Per-server options (nyma-only keys, ignored by other clients)

| Key | Meaning |
|---|---|
| `startupTimeoutMs`, `callTimeoutMs` | Override the global 30 s for this server — e.g. a decompiler whose cold start takes minutes. `/mcp add --timeout` sets both. |
| `allowTools`, `denyTools` | Globs over the server's tool names. A denied (or not-allowed) tool is never registered, never found by `mcp_search`, and refused by `mcp_call`. `/mcp add --allow/--deny`. |

A tool the server annotates `destructiveHint: true` is treated as a shell-class action, so the
permission gate asks before it runs; one annotated `readOnlyHint: true` is treated as a read.

Global tuning lives under `.nyma/settings.json#mcp` (`startup-timeout-ms`, `call-timeout-ms`,
`max-restarts`, `shadow-tools`, `hidden-tools`, `tool-overrides`, `defer-tools`).

## Multimodal (image) tool results

MCP tools that return **image** content are passed through to the model as image parts (via the
shared `agent.multimodal/tool-model-output`), so a vision-capable model can *see* them. Text-only
results are unchanged. This is what enables the OfficeCLI render → look → fix loop below. (Needs a
vision-capable active model; the transcript keeps a short text summary, never the base64.)

## Worked example: OfficeCLI (office documents — the "cowork" use-case)

[OfficeCLI](https://github.com/iOfficeAI/OfficeCLI) gives the agent hands **and** eyes for
Word/Excel/PowerPoint: it edits OOXML directly and renders `.docx/.xlsx/.pptx` → PNG so the model can
verify layout.

1. **Install** the binary: `brew install officecli`, `npm i -g @officecli/officecli`, or
   `curl -fsSL https://raw.githubusercontent.com/iOfficeAI/OfficeCLI/main/install.sh | bash`.
2. **MCP (the "what")** — bare `officecli mcp` is the stdio server (`officecli mcp <target>` only
   registers it with other clients):
   ```
   /mcp add officecli -- officecli mcp
   ```
   It exposes one tool, `officecli`, taking the command line as a string or argv array.
3. **Skill (the "how")** — `officecli skills claude` writes `~/.claude/skills/officecli/SKILL.md`,
   which nyma discovers (so does the flat `~/.claude/skills/officecli.md` from OfficeCLI's manual
   `curl` install). Confirm it under `/skills`; `officecli skills install pitch-deck claude` and
   friends add the specialised ones.
4. **Eyes** — `view <file> screenshot` renders through a headless browser: it needs Chrome, Edge,
   Chromium or Firefox installed (or `pip install playwright && playwright install chromium`).
   Without one it answers `No headless browser available` and the loop is text-only.

**Loop:** edit via structured commands/JSON (cheap), then render → `view_image` the PNG to check it
looks right, and fix. Look sparingly — prefer text/structured reads for editing; use the image to
verify. The provider auto-downscales, so no manual resizing is needed.

## Worked example: reverse-engineering servers (REA and friends)

[REA](https://github.com/morluto/rea) ("Reverse Engineer Anything", npm `rea-agents`) is a stdio
MCP server with ~170 tools: static JS/Electron, .NET, Mach-O/PE/WASM inspection with nothing else
installed, and native decompilation through Hopper / Ghidra / IDA, Android through JADX, devices
through adb, pages through Chrome. Some of its tools **run the target or change a device**; it has
no approval step of its own. Recommended entry — static analysis only, compact schemas, a timeout
long enough for a cold decompiler:

```
/mcp add rea --env REA_MCP_INPUT_SCHEMA_PROFILE=compact --timeout 360000 \
  --deny '*adb*' --deny 'capture_process_scenario' --deny 'observe_native_*' \
  --deny 'trace_native_ui_action' --deny 'capture_*_scenario' \
  -- npx -y rea-agents@6.4.0 mcp
npx rea-agents setup --skill      # its workflow skill, into ~/.agents/skills — nyma reads it
```

Its catalog is far over the deferral threshold, so nyma offers it through `mcp_search`/`mcp_call`
rather than spending context on 170 schemas. Pin the version: it releases almost daily.

Single-engine alternatives, same pattern: [GhidraMCP](https://github.com/LaurieWired/GhidraMCP),
[pyghidra-mcp](https://github.com/clearbluejar/pyghidra-mcp) (headless),
[ida-pro-mcp](https://github.com/mrexodia/ida-pro-mcp), [radare2-mcp](https://github.com/radareorg/radare2-mcp)
(has a read-only mode), [jadx-mcp-server](https://github.com/zinja-coder/jadx-mcp-server), and
[chrome-devtools-mcp](https://github.com/ChromeDevTools/chrome-devtools-mcp) (pass
`--no-usage-statistics`; it reports usage by default).

**Use it on software you are entitled to analyse.** Licences often forbid reverse engineering, and
the legal exceptions are narrow (interoperability, security research). If the goal is to rebuild a
feature, keep it clean-room: have a subagent study the target and write a *behavioural* spec, then
implement from that spec in a fresh context that never saw the decompiled code.

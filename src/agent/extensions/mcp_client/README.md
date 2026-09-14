# mcp_client

Connects nyma to [Model Context Protocol](https://modelcontextprotocol.io) servers. Each server's
tools are registered as `mcp__<server>__<tool>` and appear to the model like any native tool.

## Commands

| Command | What it does |
|---|---|
| `/mcp-client__mcp-status` | Show MCP server connection state and tool counts |

## Configuring servers

Add a `.mcp.json` to your project root (standard MCP format):

```json
{
  "mcpServers": {
    "my-server": { "command": "some-mcp-binary", "args": ["stdio"], "env": {} }
  }
}
```

Tuning lives under `.nyma/settings.json#mcp` (`startup-timeout-ms`, `call-timeout-ms`,
`max-restarts`, `shadow-tools`, `hidden-tools`, `tool-overrides`).

## Multimodal (image) tool results

MCP tools that return **image** content are passed through to the model as image parts (via the
shared `agent.multimodal/tool-model-output`), so a vision-capable model can *see* them. Text-only
results are unchanged. This is what enables the OfficeCLI render → look → fix loop below. (Needs a
vision-capable active model; the transcript keeps a short text summary, never the base64.)

## Worked example: OfficeCLI (office documents — the "cowork" use-case)

[OfficeCLI](https://github.com/iOfficeAI/OfficeCLI) gives the agent hands **and** eyes for
Word/Excel/PowerPoint: it edits OOXML directly and renders `.docx/.xlsx/.pptx` → PNG so the model can
verify layout.

1. **Install** the binary:
   ```bash
   curl -fsSL https://d.officecli.ai/install.sh | bash   # macOS/Linux
   ```
2. **MCP (the "what")** — add to `.mcp.json`:
   ```json
   { "mcpServers": { "officecli": { "command": "officecli", "args": ["mcp"] } } }
   ```
   (Verify the stdio subcommand for your version: `officecli mcp --help`.)
3. **Skill (the "how")** — OfficeCLI auto-installs its `SKILL.md` into `.claude/skills`, which nyma
   discovers automatically (the L1→L2→L3 strategy, help-system, and `load_skill` routing to
   `pitch-deck` / `financial-model` / `data-dashboard` / `academic-paper` / `morph-ppt`). Confirm it
   shows up under `/skills`.

**Loop:** edit via structured commands/JSON (cheap), then render → `view_image` the PNG to check it
looks right, and fix. Look sparingly — prefer text/structured reads for editing; use the image to
verify. The provider auto-downscales, so no manual resizing is needed.

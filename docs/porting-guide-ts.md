# Porting Pi-Mono Extensions to Nyma (TypeScript)

This guide covers porting pi-mono TypeScript extensions to nyma.
Most pi-mono extensions work with minimal changes since nyma exposes
a compatible API surface.

## Quick Mapping

| Pi-Mono | Nyma | Notes |
|---------|------|-------|
| `import { ExtensionAPI } from "@mariozechner/pi-coding-agent"` | Not needed — API passed as argument | |
| `import { Type } from "@sinclair/typebox"` | `import { z } from "zod"` | Schema library change |
| `Type.Object({name: Type.String()})` | `z.object({name: z.string()})` | |
| `Type.Optional(Type.String())` | `z.string().optional()` | |
| `Type.Integer()` | `z.number().int()` | |
| `Type.Enum({A:"a",B:"b"})` | `z.enum(["a","b"])` | |
| `pi.on("event", handler)` | `api.on("event", handler)` | Identical |
| `pi.registerTool({name,description,parameters,execute})` | `api.registerTool(name, {description,parameters,execute})` | Name is separate arg |
| `pi.registerCommand("name", {handler})` | `api.registerCommand("name", {description,handler})` | Identical |
| `pi.sendMessage(msg)` | `api.sendMessage(msg)` | Identical |
| `pi.sendUserMessage(text)` | `api.sendUserMessage(text, {deliverAs:"steer"})` | Explicit deliverAs |
| `pi.appendEntry(type, data)` | `api.appendEntry(type, data)` | Identical |
| `pi.setModel(model)` | `api.setModel(model)` | Identical |
| `pi.exec(cmd, args)` | `api.exec(cmd, args)` | Identical |
| `ctx.ui.notify(msg, type)` | `ctx.ui.notify(msg, type)` | Identical |
| `ctx.ui.confirm(title, msg)` | `ctx.ui.confirm(msg)` | No title param |
| `ctx.ui.setStatus(id, text)` | `ctx.ui.setStatus(id, text)` | Identical |
| `ctx.ui.setWidget(id, lines)` | `ctx.ui.setWidget(id, lines, position)` | Identical |
| `ctx.isIdle()` | `ctx.isIdle()` | Identical |
| `ctx.abort()` | `ctx.abort()` | Identical |
| `ctx.compact()` | `ctx.compact()` | Identical |
| `return {block: true, reason: "..."}` | `return {block: true, reason: "..."}` | Identical |

## Example: Permission Gate

### Pi-Mono Original
```typescript
import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";

export default function (pi: ExtensionAPI) {
  pi.on("tool_call", async (event, ctx) => {
    if (event.toolName === "bash" && event.input.command?.includes("rm -rf")) {
      const ok = await ctx.ui.confirm("Dangerous!", "Allow rm -rf?");
      if (!ok) return { block: true, reason: "Blocked by user" };
    }
  });
}
```

### Nyma Port (TypeScript)
```typescript
export default function (api) {
  api.on("tool_call", async (event, ctx) => {
    if (event.toolName === "bash" && event.input?.command?.includes("rm -rf")) {
      const ok = await ctx.ui.confirm("Allow rm -rf?");
      if (!ok) return { block: true, reason: "Blocked by user" };
    }
  });
}
```

**Changes**: Remove type import, `pi` → `api`, `ctx.ui.confirm` takes 1 arg.

## Example: Custom Tool

### Pi-Mono Original
```typescript
import { Type } from "@sinclair/typebox";

pi.registerTool({
  name: "greet",
  label: "Greet",
  description: "Greet someone",
  parameters: Type.Object({
    name: Type.String({ description: "Name to greet" }),
  }),
  async execute(toolCallId, params, signal, onUpdate, ctx) {
    return {
      content: [{ type: "text", text: `Hello, ${params.name}!` }],
      details: {},
    };
  },
});
```

### Nyma Port (TypeScript)
```typescript
import { z } from "zod";

api.registerTool("greet", {
  description: "Greet someone",
  parameters: z.object({
    name: z.string().describe("Name to greet"),
  }),
  async execute(args, ctx) {
    return {
      content: [{ type: "text", text: `Hello, ${args.name}!` }],
      details: {},
    };
  },
});
```

**Changes**: TypeBox → Zod, name is first argument, execute takes `(args, ctx)`.

## Example: Status Line Widget

### Pi-Mono
```typescript
pi.on("agent_start", async (event, ctx) => {
  ctx.ui.setStatus("my-ext", "Processing...");
});
pi.on("agent_end", async (event, ctx) => {
  ctx.ui.setStatus("my-ext", "Done");
});
```

### Nyma (identical)
```typescript
api.on("agent_start", async (event, ctx) => {
  ctx.ui.setStatus("my-ext", "Processing...");
});
api.on("agent_end", async (event, ctx) => {
  ctx.ui.setStatus("my-ext", "Done");
});
```

## Schema Conversion Reference

| TypeBox | Zod |
|---------|-----|
| `Type.String()` | `z.string()` |
| `Type.Number()` | `z.number()` |
| `Type.Integer()` | `z.number().int()` |
| `Type.Boolean()` | `z.boolean()` |
| `Type.Optional(T)` | `T.optional()` |
| `Type.Array(T)` | `z.array(T)` |
| `Type.Object({...})` | `z.object({...})` |
| `Type.Enum({A:"a"})` | `z.enum(["a"])` |
| `.description("x")` | `.describe("x")` |

## Additional API Mappings

| Pi-Mono | Nyma | Notes |
|---------|------|-------|
| `ctx.ui.confirm(title, msg)` | `ctx.ui.confirm(title, msg)` | Both args supported |
| `ctx.ui.confirm(msg)` | `ctx.ui.confirm(msg)` | Single arg also works |
| `pi.registerFlag(name, config)` | `api.registerFlag(name, config)` | Identical |
| `pi.getFlag(name)` | `api.getFlag(name)` | Identical |
| `Type.Enum({A:"a",B:"b"})` | `z.enum(["a","b"])` | Enum conversion fixed |

## Nyma-Only Hooks

Nyma adds three hook points that have no pi-mono equivalent. Any pi-mono
extension can start using them after porting — they are purely additive.

### `ctx.modelId` inside tool `execute`

Every tool's `execute(args, ctx)` receives `ctx.modelId` — the active model ID
string. Use it to adapt tool behaviour to the model in play (truncation size,
top-k, verbosity). It is always a string; when no model is resolved yet the
value is `"unknown"`, so `.includes(...)` is safe without a null check.

```typescript
api.registerTool("search", {
  description: "Search the codebase",
  parameters: z.object({ query: z.string() }),
  execute: async ({ query }, ctx) => {
    const limit = ctx.modelId.includes("haiku") ? 5
                : ctx.modelId.includes("opus")  ? 30
                : 15;
    return await runSearch(query, { limit });
  }
});
```

### `before_message_send` — final message/system transform

Fires after `context_assembly` and before `before_provider_request`. Use it to
rewrite the messages array or system prompt as the very last step before the
LLM call. Return `{messages?, system?}` to replace either field. Return
`undefined`/`null` to pass through unchanged.

```typescript
api.on("before_message_send", (data, ctx) => {
  // data: { messages: Message[], system: string, model: string }
  if (data.messages.length > 40) {
    return { messages: data.messages.slice(-40) };
  }
  return null;
});
```

### `stream_filter` — abort + retry on bad output

Fires per text delta while the model is streaming. Inspect
`data.delta` (accumulated text) or `data.chunk` (this chunk); return
`{abort: true, reason?, inject?: Message[]}` to stop the stream and retry with
injected messages. Retry cap is 2; the 3rd attempt exits with whatever was
accumulated.

```typescript
const BANNED = [/\beval\(/, /sk-[A-Za-z0-9]{20,}/];

api.on("stream_filter", (data, ctx) => {
  const full = data.delta as string;
  if (BANNED.some((re) => re.test(full))) {
    return {
      abort:  true,
      reason: "tripped content filter",
      inject: [{
        role: "system",
        content: "Your previous output matched a forbidden pattern. Retry without it."
      }]
    };
  }
  return null;
});
```

Both hooks deliver `(data, ctx)` where `ctx` is the standard extension context
(same shape as every other `api.on` handler). Both are `emit-collect` events,
so multiple handlers merge last-writer-wins; attach a `priority` (third arg to
`api.on`) if you need a specific order.

### Timed Dialogs

Pi-mono extensions using `{timeout, signal}` options on dialogs work in nyma:

```typescript
// Works in both pi-mono and nyma
const ok = await ctx.ui.confirm("Deploy?", { timeout: 5000 });
const choice = await ctx.ui.select("Pick:", ["A", "B"], { signal: controller.signal });
```

## Nyma-Specific Advantages

When porting, you can optionally use nyma features not in pi-mono:

1. **Middleware interceptors**: Add `:enter`/`:leave`/`:error` stages
2. **Capability gating**: Declare required permissions in extension.json
3. **Namespace isolation**: Tool names auto-prefixed to prevent collisions
4. **Event-sourced state**: Custom reducers and state subscriptions
5. **Extension CLI flags**: `registerFlag`/`getFlag` with `--ext-*` CLI args
6. **Inter-extension events**: `api.events.on/off/emit` for cross-extension communication
7. **Dependency ordering**: `dependsOn` in extension.json for load order control
8. **NPM dependency declaration**: `dependencies` in extension.json — missing packages are auto-installed via `bun add` before your extension loads

## Extension Manifest Reference

A full `extension.json` with all supported fields:

```json
{
  "namespace": "my-ts-ext",
  "capabilities": [
    "tools", "commands", "shortcuts", "events", "messages", "state", "ui",
    "middleware", "exec", "spawn", "providers", "model", "session", "flags",
    "renderers", "context"
  ],
  "dependsOn": ["some-other-ext"],
  "dependencies": {
    "lodash": "^4.17.21"
  }
}
```

Use `"all"` instead of the list to grant everything (this is also the default
when `capabilities` is omitted).

| Field | Required | Description |
|---|---|---|
| `namespace` | No | Unique identifier; auto-derived from filename if omitted |
| `capabilities` | No | Restricts which API methods are callable; defaults to all |
| `dependsOn` | No | Other extension namespaces to load before this one |
| `dependencies` | No | npm packages to check/install before loading |

## Discovery Order

Extensions are loaded from three locations in this order:

1. `dist/agent/extensions/` — built-in extensions (pre-compiled with the project)
2. `~/.nyma/extensions/` — global user extensions
3. `.nyma/extensions/` — project-local user extensions

For multi-file extensions: create a directory with `extension.json` + an `index.ts` (or `index.mjs`) entry point. Only the `index.*` file is loaded by the extension system; other files are imported by your entry point normally.

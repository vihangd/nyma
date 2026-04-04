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

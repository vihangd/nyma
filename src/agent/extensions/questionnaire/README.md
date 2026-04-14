# questionnaire

> A tool the LLM can call to ask the user one or more structured questions mid-turn.

## What it does

Registers a `questionnaire` tool that lets the model pause and ask the user for clarifications, preferences, or structured input — **without ending the turn**. Each question can be either a **picker** (with optional `allowOther` for free text) or a **free-form text** field. Answers are returned to the model as a structured `{answers, text}` object.

Honors the abort signal so canceling the prompt aborts the run cleanly. Per-question `secret` flag masks input in the UI.

## Tools

| Tool | Description |
|---|---|
| `questionnaire` | Ask the user one or more questions and collect answers. Use for clarifications, preference choices, or gathering structured user input. |

### Tool input shape

```typescript
{
  questions: [
    {
      id:        "deploy-target",
      prompt:    "Where to deploy?",
      type:      "choice",
      choices:   ["staging", "production"],
      allowOther: false        // optional — let user type a custom answer
    },
    {
      id:     "notes",
      prompt: "Any extra notes?",
      type:   "text",
      secret: false            // optional — mask input
    }
  ]
}
```

## When the LLM should use it

- Model isn't sure which of N options the user meant
- Destructive operation needs explicit human consent
- Configuration value isn't in the project but is needed to proceed

## Capabilities

`tools`, `ui`

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide

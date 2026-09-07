# questionnaire

> A tool the LLM can call to ask the user one or more structured questions mid-turn.

## What it does

Registers a `questionnaire` tool that lets the model pause and ask the user for clarifications, preferences, or structured input — **without ending the turn**. Each question can be either a **picker** (with optional `allowOther` for free text) or a **free-form text** field. Answers reach the model as a text block — one `id: value` line per question
under a `User answered N question(s):` headline — with the structured answers
also on `details.answers`. (It used to return a bare `{answers, text}` object,
which `normalize-tool-result` did not recognise, so the model's copy was the
string `[object Object]`.)

Honors the abort signal so canceling the prompt aborts the run cleanly. Per-question `isSecret` masks the answer in the model-visible summary.

## Tools

| Tool | Description |
|---|---|
| `questionnaire` | Ask the user one or more questions and collect answers. Use for clarifications, preference choices, or gathering structured user input. |

### Tool input shape

```typescript
{
  questions: [
    {
      id:      "deploy-target",
      prompt:  "Where to deploy?",
      options: [                       // omit for a free-form text question
        { value: "staging" },
        { value: "production", label: "Production", recommended: true,
          description: "Shown under the label in the picker" }
      ],
      allowOther: false                // optional, default true — adds
                                       // "Type your own answer…" to the picker
    },
    {
      id:       "notes",
      prompt:   "Any extra notes?",     // no options → text input
      isSecret: false                   // optional — answer masked in the
                                        // model-visible summary
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

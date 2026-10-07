# desktop-notify

> Sends terminal desktop notifications when prompts take longer than expected.

## What it does

Watches turn-end and tool-complete events; when a prompt response takes longer than the threshold (default **3s**) or a tool runs past it, it raises a terminal notification. It also fires **immediately when a permission prompt is waiting** — a run blocked on an approval you have not seen — and rings the bell for that one. The escape sequence follows the terminal: **OSC 9** for iTerm2, **OSC 99** for Kitty, **OSC 777** otherwise (Ghostty, WezTerm). Terminals that support none of them ignore it.

Useful when you've kicked off a long agent run and tabbed away.

## Hooks

| Event | Behaviour |
|---|---|
| `turn_start` | Record the timestamp |
| `turn_end` | If `now - start > threshold`, emit a notification |
| `tool_complete` | Notify for tool calls > 10s |
| `session_end_summary` | Notify with the session summary (turns, cost) on exit |

## Settings

Read from `~/.nyma/settings.json` or `.nyma/settings.json`:

| Key | Type | Default | Description |
|---|---|---|---|
| `desktop-notify.enabled` | bool | `true` | Master switch |
| `desktop-notify.threshold-ms` | int | `3000` | Min prompt duration before notifying |

## Flags

- `--desktop-notify__enabled` — overrides the settings value at the CLI

## Capabilities

`events`, `flags`

## See also

- [OSC 777 notification spec](https://github.com/ghostty-org/ghostty/blob/main/docs/osc.md)
- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — extension authoring guide

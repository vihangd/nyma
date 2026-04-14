# desktop-notify

> Sends terminal desktop notifications when prompts take longer than expected.

## What it does

Watches turn-end and tool-complete events; when a prompt response takes longer than the threshold (default **3s**) or a tool runs more than **10s**, it emits an **OSC 777** escape sequence — the terminal-native "notify" protocol. Modern terminal emulators (Ghostty, iTerm2, WezTerm, Kitty) translate that into a real macOS / Linux notification. Terminals that don't support OSC 777 silently ignore it, so this extension is a safe no-op everywhere else.

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

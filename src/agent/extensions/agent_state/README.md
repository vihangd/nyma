# agent-state

Reports what nyma is doing to whatever is supervising it.

Off unless something is listening. With no configured command and no supervisor
detected in the environment, nothing subscribes and no process is ever spawned.

## herdr (auto-detected)

[herdr](https://herdr.dev) is a terminal workspace manager that runs several
coding agents at once and needs to know which pane is busy, idle, or waiting on
a human. It asks agents to report by running a CLI command, and supports agents
it has never heard of via `--source custom:<name>`.

When `HERDR_BIN_PATH` and `HERDR_PANE_ID` are both present — herdr sets them on
every pane it opens — nyma reports automatically. Nothing to configure:

```
pane report-agent <pane> --source custom:nyma --agent nyma --state working --seq 2
```

An agent that reports becomes a *lifecycle authority*, and herdr stops inferring
its state by scraping the terminal.

## Any other supervisor

The command is an argv template, so anything told about state by running a
program works:

```json
{"agent-state": {"command": ["my-supervisor", "--state", "{state}", "--message", "{message}"]}}
```

| placeholder | value |
|---|---|
| `{state}` | `idle`, `working`, or `blocked` |
| `{message}` | why it is blocked (the tool name); empty otherwise |
| `{seq}` | monotonic counter, so a late report can be discarded |
| `{agent}` | the agent name, `nyma` unless `"agent"` is set |

An argument whose placeholder resolves to empty is dropped along with the flag
before it, so a missing `--message` does not become an empty argument.

| setting | default |
|---|---|
| `command` | unset — auto-detect herdr |
| `enabled` | `true`; set `false` to stay silent even inside herdr |
| `agent` | `"nyma"` |

## States

| state | when |
|---|---|
| `idle` | at activation, and after each turn (`agent_end`) |
| `working` | a turn started (`agent_start`) |
| `blocked` | waiting on a human (`permission_request`) |
| release | teardown — reported once, however many shutdown events fire |

Reports are fire-and-forget: a supervisor that is gone, slow or broken never
stalls or fails a turn.

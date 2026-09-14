# Nyma Gateway

The **nyma gateway** runs nyma as a long-lived daemon behind chat channels.
Inbound messages from Telegram, Slack, HTTP webhooks, or email are routed to
per-conversation agent sessions, and the agent's streaming replies flow back
through the channel as typing indicators, message edits, or batched replies
depending on the platform.

```
 ┌─────────────┐                                  ┌──────────────────┐
 │  Telegram   │                                  │                  │
 │   Slack     │◄─── channel adapters ───►        │   nyma-gateway   │
 │   HTTP      │                                  │   daemon         │
 │   Email     │                                  │                  │
 └─────────────┘                                  └────────┬─────────┘
                                                           │
                                  ┌────────────────────────┼─────────────────┐
                                  │                        │                 │
                                  ▼                        ▼                 ▼
                            auth pipeline             session pool       agent session
                            (allow-lists,          (per conversation,  (same kernel as
                             rate limits,           serialized lanes)   nyma TUI —
                             custom checks)                             shares the
                                                                        agent.core/run
                                                                        loop and all
                                                                        extensions)
```

---

## Running the daemon

```bash
# Start with the default config (./gateway.json)
bun dist/gateway/entry.mjs

# Custom config path
bun dist/gateway/entry.mjs --config=/etc/nyma/bot.json

# Validate a config file and exit
bun dist/gateway/entry.mjs validate

# Run interactive setup flows for each channel that supports it
bun dist/gateway/entry.mjs setup

# Show help
bun dist/gateway/entry.mjs --help
```

After `bun link` (or global install) the same commands work as `nyma-gateway`
directly. The daemon blocks until `SIGINT` or `SIGTERM`, at which point it
clears the maintenance timer, calls `stop!` on every channel, and tears down
the ACP worker pool. It does **not** wait for in-flight turns to finish —
a message being handled at shutdown is dropped.

## Config file

`gateway.json` is a single JSON document. `${VAR}` tokens anywhere in a string
value are interpolated from `process.env` at load time.

```json
{
  "agent": {
    "model":                "claude-sonnet-4-6",
    "system-prompt":        "You are a helpful DevOps assistant.",
    "modes":                ["gateway"],
    "exclude-capabilities": ["execution"]
  },

  "gateway": {
    "streaming": { "policy": "debounce", "delay-ms": 400 },
    "session":   { "policy": "persistent", "idle-evict-ms": 3600000 },
    "dedup":     { "cache-ttl-ms": 300000 },
    "auth":      { "allowed-user-ids": ["U12345"], "allowed-channels": ["C99"] }
  },

  "channels": [
    {
      "type":   "telegram",
      "name":   "my-bot",
      "config": { "token": "${TELEGRAM_BOT_TOKEN}" }
    }
  ]
}
```

A `${VAR}` with no value in the environment is left **literally in place**,
not blanked — a missing `TELEGRAM_BOT_TOKEN` reaches the adapter as the string
`${TELEGRAM_BOT_TOKEN}`.

Gateway-level config keys use **kebab-case** (`system-prompt`, `idle-evict-ms`)
and are read with plain keyword lookups. Channel adapters are looser: slack,
email and telegram each accept a camelCase spelling of their own keys as a
fallback (`appToken`, `botToken`, …).

### `agent.*` — agent creation

| Key | Type | Purpose |
|---|---|---|
| `model` | string, **required** | Model ID the agent runs with (e.g. `claude-sonnet-4-6`) |
| `system-prompt` | string | Optional base system prompt; agent's own discovery pipeline still runs |
| `modes` | string[] | Tool mode filter — only tools declaring one of these modes are loaded. The built-in gateway tool set uses `"gateway"` |
| `exclude-capabilities` | string[] | Drops tools that declare any of these capabilities — typical values: `"execution"`, `"shell"`, `"filesystem"` |
| `require-capabilities` | string[] | Inverse filter — only loads tools declaring all of these capabilities |

### `gateway.streaming` — how partial replies reach the platform

One policy, configured globally in `gateway.streaming`, wraps the agent's
text-delta stream before it reaches the channel's `stream!` function. It is
**not** per-channel: every adapter gets the same policy.

| Policy | Latency | Call volume | Payload |
|---|---|---|---|
| `immediate` | Lowest | Highest | the raw delta |
| `debounce` *(default, 400 ms)* | ~`delay-ms` | Medium | the deltas buffered since the last flush |
| `throttle` | ~`interval-ms` (500 ms) | Bounded | the deltas buffered since the last emit |
| `batch-on-end` | Final only | 1 per turn | the whole response |

> **Known bug.** Only `batch-on-end` emits cumulative text. The other three
> emit *fragments*, while every adapter treats the payload as the complete
> message and overwrites what it sent before. So under the default `debounce`
> policy, Telegram and Slack edit the reply down to the latest fragment and
> `POST /message` returns only the last one. Until that is fixed,
> `batch-on-end` is the only policy that produces a correct reply.

Configuration is a map:

```jsonc
"streaming": { "policy": "debounce", "delay-ms": 250 }
"streaming": { "policy": "throttle", "interval-ms": 750 }
```

The bare-string form `"streaming": "debounce"` is **not** read — the loader
looks for a `policy` key on a map, so a string silently falls through to a
300 ms debounce.

### `gateway.session` — conversation retention

| Policy | Behaviour |
|---|---|
| `persistent` *(default)* | Never auto-evict. The SDK session (agent + history) stays in memory until the daemon exits. |
| `idle-evict` | Evict the whole session when `Date.now() - lastActive > idle-evict-ms`. Next message creates a fresh session. |
| `ephemeral` | After every successful run, clear the session's `:data` (agent session + history). Each inbound message sees a stateless agent. |
| `capped` | Nothing reads this value — it behaves exactly as `persistent`. Trimming is future work. |

`idle-evict-ms` defaults to 1 hour. Eviction runs on a 10-minute maintenance
timer alongside dedup-cache pruning.

### `gateway.dedup` — idempotency cache

Webhook deliveries (Slack events, Telegram retries) may redeliver the same
message. Adapters attach an `:event-id` to each inbound message and
`gateway.loop` checks it against the cache. TTL is 5 minutes by default,
configurable via `dedup.cache-ttl-ms`.

### `gateway.auth` — allow-list check

When either `allowed-user-ids` or `allowed-channels` is non-empty, an auth check
is automatically registered at startup. Requests whose user-id is not on
`allowed-user-ids` are denied with a `"User X not on allow-list"` reason.

> **`allowed-channels` does not work.** The check needs a `:channel-name` on
> the inbound request and no adapter sets one, so the channel list never
> denies anything. Use `allowed-user-ids`, or a custom check.

Custom auth checks can be added at runtime:

```clojure
((:add! (:auth-pipeline gw))
 (fn [req]
   (js/Promise.resolve
    (if (rate-limit-ok? (:user-id req))
      #js {:allow? true}
      #js {:allow? false :reason "rate limited"}))))
```

Check-fn return shape: `{allow?: bool, reason?: string}` (or nil = allow).
The pipeline short-circuits on the first deny. Handlers that throw default
to allow (logged to stderr).

### `gateway.projects` — multi-project routing (optional)

When `projects` is configured, the gateway agent gains a `run_in_project` tool
that dispatches the user's instructions to an ACP coding agent (Claude Code,
Gemini CLI, OpenCode, etc.) running inside the project's directory. The router
LLM picks `(project, agent)` from the natural-language inbound message; the
tool spawns or reuses an ACP worker via `agent-shell.api/run-prompt` and streams
the worker's output back through the conversation's response context.

```json
"gateway": {
  "projects": {
    "vyom":    { "root": "~/projects/pers/vyom", "agents": ["claude", "gemini"] },
    "nyma":    { "root": "~/projects/pers/nyma", "agents": ["claude"] },
    "scratch": { "root": "~/scratch",            "agents": ["claude", "opencode"] }
  },
  "default-agent": "claude"
}
```

- **`projects[name].root`** is `path.resolve`-d at load time; `~` is expanded.
  Chat-supplied project names are dictionary lookups — there are no path
  operations on user-supplied strings.
- **`projects[name].agents`** is a per-project allow-list. The tool rejects any
  `(project, agent)` combination not on the list, regardless of `default-agent`.
- **`default-agent`** fills in when `run_in_project` is called without an
  explicit `agent` argument. Defaults to `"claude"`.
- `run_in_project` declares the `execution` capability, so the sample
  `"exclude-capabilities": ["execution"]` above would drop this tool and with
  it the whole projects feature.

Sessions are pooled by `(agent, cwd)` — follow-up messages targeting the same
project reuse the same ACP subprocess and its in-process session, so the
underlying coding agent retains conversational memory across turns. Different
projects spin up parallel workers.

**v1 limitations:** there is no idle eviction for ACP subprocesses — they live
until gateway shutdown. Large project lists or long-running daemons will
accumulate workers. The gateway's existing `:stop!` tears down the entire pool
via `agent-shell.acp.pool/disconnect-all`, but a per-worker idle timer is
follow-up work.

### `channels[]` — platform adapters

Each entry is `{type, name, config}`. `name` must be unique. `type` is a
keyword registered via `register-channel-type!` — the four built-in adapters
self-register when `gateway.entry` loads, which is before any config is read.
An unknown type is warned about and skipped; startup fails only if *no*
channel instantiates.

## Built-in channel adapters

| Type | Capabilities | Key config |
|---|---|---|
| `telegram` | text, typing, attachments | `token` — bot token from @BotFather. Also `parse-mode` (default `"Markdown"`) and `timeout` (long-poll seconds, default 30) |
| `slack` | text, typing, threads | `app-token`, `bot-token` — xapp-/xoxb- tokens; uses Socket Mode (no public URL needed) |
| `http` | text | `port` (default `3000`), `host` (default `"0.0.0.0"`), `secret` (Bearer token), `timeout-ms` (default 120000) |
| `email` | text | `imap-host`, `smtp-host`, `user`, `password` (+ optional ports, `tls?`) |

All four accept `${VAR}` interpolation in their config values. Slack
(`@slack/socket-mode` + `@slack/web-api`) and email (`imap-simple`,
`nodemailer`, `mailparser`) lazy-load their SDKs and throw a `bun add <pkg>`
hint on first use if one is missing. Telegram needs no SDK — it calls the Bot
API with `fetch`. HTTP uses `Bun.serve` directly.

### Telegram

```json
{
  "type":   "telegram",
  "name":   "my-telegram",
  "config": { "token": "${TELEGRAM_BOT_TOKEN}" }
}
```

Long-polling via the Bot API. Conversation ID is `telegram:<chat_id>`, so
each chat becomes its own session lane. The adapter fires one `sendChatAction`
per turn and one per tool call; Telegram expires the indicator after about
five seconds, and nothing refreshes it. Photos and documents are downloaded
into the OS temp directory and never cleaned up.

### Slack

```json
{
  "type":   "slack",
  "name":   "ops-bot",
  "config": {
    "app-token": "${SLACK_APP_TOKEN}",
    "bot-token": "${SLACK_BOT_TOKEN}"
  }
}
```

Uses Socket Mode — no inbound webhook URL needed. Threads become part of the
conversation key when the incoming event carries a `thread_ts`, so a single
channel can host multiple parallel agent sessions.

On `:typing-start` the adapter posts a `_Thinking..._` placeholder message and
edits it as the reply streams.

`nyma-gateway setup` runs an `auth.test` against the **bot** token. The app
token is not validated.

### HTTP

```json
{
  "type":   "http",
  "name":   "webhook",
  "config": {
    "port":       3000,
    "host":       "0.0.0.0",
    "timeout-ms": 120000,
    "secret":     "${HTTP_AUTH_TOKEN}"
  }
}
```

Starts a `Bun.serve` HTTP server with four endpoints:

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/health`       | Returns `{status: "ok", channel: "<name>"}` |
| `POST` | `/message`       | Submit a message; waits for the full agent response before returning |
| `POST` | `/message/async` | Submit a message; returns `{job_id}` immediately |
| `GET`  | `/result/<job>`  | Poll for an async job result |

Request body for `/message` and `/message/async`:

```json
{
  "conversation_id": "thread-42",
  "text":            "what's the build status?",
  "user_id":         "alice",
  "event_id":        "optional-dedup-key"
}
```

- `conversation_id` (required; `conversationId` also accepted)
- `text` (required)
- `user_id` (optional; snake_case only)
- `event_id` (optional dedup key; snake_case only)

Missing required fields return `400`. `/message/async` answers `202`; a sync
request that outlives `timeout-ms` answers `504`. `GET /result/<job>` answers
`404 {status:"not_found"}` for an unknown or expired job — the async job store
keeps results for 5 minutes.

When `secret` is set, `POST /message` and `POST /message/async` must carry
`Authorization: Bearer <secret>` or the server returns `401`. `GET /health`
and `GET /result/<job>` are **not** authenticated, so anyone who can reach the
port and guess a job id can read that job's output.

### Email

```json
{
  "type":   "email",
  "name":   "support",
  "config": {
    "imap-host":   "imap.example.com",
    "imap-port":   993,
    "smtp-host":   "smtp.example.com",
    "smtp-port":   587,
    "smtp-secure": false,
    "user":        "bot@example.com",
    "password":    "${EMAIL_PASSWORD}",
    "from":        "bot@example.com",
    "mailbox":     "INBOX",
    "poll-ms":     30000
  }
}
```

Required: `imap-host`, `smtp-host`, `user`, `password`. Defaults:
`imap-port` 993, `smtp-port` 587, `smtp-secure` false (STARTTLS), `from`
same as `user`, `mailbox` `"INBOX"`, `poll-ms` 30000.

Polls an IMAP inbox for unread mail, marks processed messages as seen, and
sends replies via SMTP. Threading is preserved through `Message-ID` and
`In-Reply-To` headers; the conversation key is the thread ID, so follow-ups
land in the same agent session. The adapter buffers its own output and sends on
`:done`, so each email gets exactly one reply whatever the configured streaming
policy is.

---

## Gateway-only tools

When an agent runs under the gateway, it gets a small set of tools that let it
talk *through* the channel instead of to the local filesystem:

| Tool | Purpose |
|---|---|
| `send_message` | Emit an additional message into the current conversation (besides the main reply) |
| `typing_indicator` | Show/hide a typing indicator while a long operation runs |
| `conversation_info` | Read the conversation's metadata — channel name, user id, capabilities |
| `handoff_to_human` | **Inert.** Emits a `:handoff` meta op that no adapter implements; nothing is tagged and nothing stops, but the tool reports success. |
| `request_approval` | **Inert.** Emits an `:approval-request` that no adapter implements, so the result is always nil and the tool always answers "Denied — do not proceed". No human is ever asked. |
| `run_in_project` | Present only when `gateway.projects` is configured (see above). Declares the `execution` capability, so `"exclude-capabilities": ["execution"]` removes the entire projects feature. |

All six declare `:modes #{:gateway}` in their tool metadata, so they pass the
default `{"modes": ["gateway"]}` filter and **do not load** in the interactive
CLI. They are registered by `gateway.tools/register-tool-metadata!` at daemon
startup.

They are backed by the per-message `IResponseContext`, which the gateway loop
swaps into the shared tool-set before every `run`. This means the tools always
target the right conversation even under concurrent traffic.

---

## Writing a third-party channel adapter

A channel adapter is any value that implements the `IChannel` shape — either a
ClojureScript protocol implementation or a plain JS object with the required
keys. The gateway validator accepts either form.

### Required keys

| Key | Signature | Purpose |
|---|---|---|
| `name` | string | Unique channel identifier |
| `start!` | `(on-message-fn) → Promise<void>` | Begin accepting messages. Call `on-message-fn(inbound, response-ctx)` for each one |
| `stop!` | `() → Promise<void>` | Gracefully shut down |

Those three are what the validator checks. `capabilities` (a set or array of
`:text`, `:typing`, `:threads`, `:attachments`) is conventional but neither
required nor validated.

### Optional keys

| Key | Signature | Purpose |
|---|---|---|
| `setup!` | `() → Promise<void>` | Interactive OAuth / token entry, run via `nyma-gateway setup` |

### Inbound message shape

The `inbound` argument passed to `on-message-fn` is a map:

```clojure
{:event-id        "optional-dedup-key"    ;; used by the dedup cache
 :conversation-id "channel:chat-id"       ;; lane key — picks the session
 :user-id         "sender-id"             ;; for auth allow-lists
 :text            "the message body"
 :attachments     [{:local "/tmp/downloaded-file" :mime-type "image/jpeg"}]
 :raw             <original-platform-event>}
```

Telegram is the only adapter that produces attachments; it downloads the file
and reports the local path as `:local`.

### Response context shape

The `response-ctx` argument is an `IResponseContext`:

```clojure
{:conversation-id "channel:chat-id"
 :channel-name    "my-channel"
 :capabilities    #{:text :typing}
 :send!       (fn [content-map] ...)    ;; content-map: {:text, :markdown, :image-url, :file-path}
 :stream!     (fn [content-map] ...)    ;; SAME shape as send! — {:text "..."}, not a bare string
 :meta!       (fn [op args] ...)        ;; ops: :typing-start :typing-stop :tool-start :tool-end
                                        ;;      :done, plus :handoff and :approval-request from
                                        ;;      the gateway tools (no built-in adapter handles those)
 :interrupt!  (fn [reason] ...)         ;; declared and defaulted, but nothing in the gateway calls it
}
```

Build it with `gateway.protocols/make-response-context`. Do **not** wrap
`:stream!` in your own `create-streaming-policy`: `gateway.loop` already
applies the configured policy to whatever `:stream!` you supply, and a second
policy inside the adapter double-buffers it. None of the four built-in
adapters does this.

```clojure
(make-response-context
  {:conversation-id (str "my-chan:" chat-id)
   :channel-name    "my-chan"
   :capabilities    #{:text}
   :send!           (fn [content] (platform-send chat-id content))
   :stream!         (fn [content] (platform-edit chat-id (:text content)))
   :meta!           (fn [op _] (when (= op :done) (platform-finish chat-id)))})
```

### Registering the factory

The gateway calls your factory with `(factory name config-map)` once per
channels[] entry. Register it from your own entry point (or a custom
`nyma-gateway` wrapper):

```clojure
(ns my-gateway-entry
  (:require [gateway.core :as core]
            [my.channels.discord :as discord]))

(core/register-channel-type! :discord discord/create-discord-channel)

;; Then require gateway.entry after your registration so `discord` is in the
;; registry before `create-gateway` runs.
(require '[gateway.entry])
```

### Minimal factory skeleton

```clojure
(ns my.channels.discord
  (:require [gateway.protocols :as proto]))

(defn create-discord-channel [channel-name cfg]
  (let [token  (:token cfg)
        client (atom nil)
        running? (atom false)]
    {:name         channel-name
     :capabilities #{:text :typing}

     :start!
     ;; ^:async — js-await below compiles to a bare `await`, which the JS
     ;; engine rejects at parse time in a plain fn.
     (^:async fn [on-message-fn]
       (reset! running? true)
       ;; ... create a Discord client ...
       (let [c (discord-create-client token)]
         (reset! client c)
         (.on c "messageCreate"
              (fn [msg]
                (let [chat-id (.-channelId msg)
                      ctx (proto/make-response-context
                            {:conversation-id (str "discord:" chat-id)
                             :channel-name    channel-name
                             :capabilities    #{:text}
                             :send!    (fn [content] (.send c chat-id (:text content)))
                             ;; Same shape as send! — the gateway's streaming
                             ;; policy is applied outside the adapter.
                             :stream!  (fn [content] (.send c chat-id (:text content)))
                             :meta!    (fn [_op _args] nil)})]
                  (on-message-fn
                   {:event-id        (.-id msg)
                    :conversation-id (str "discord:" chat-id)
                    :user-id         (.-id (.-author msg))
                    :text            (.-content msg)
                    :raw             msg}
                   ctx))))
         (js-await (.login c token))
         nil))

     :stop!
     (fn []
       (reset! running? false)
       (when-let [c @client] (.destroy c))
       nil)

     :setup!
     (fn []
       (js/console.log "Discord setup: visit https://discord.com/developers/applications")
       nil)}))
```

### Validating a config

Before your start-up completes, you can validate the gateway section the daemon
will apply to your channel via `config/validate-config` and
`streaming/create-streaming-policy`. Both are pure and testable without network
IO — see `test/gateway_*.test.cljs` for patterns.

---

## Tests

Gateway logic has unit coverage under `test/`:

- `gateway_config.test.cljs` — `${VAR}` interpolation, validation, policy extraction
- `gateway_session_pool.test.cljs` — lane serialization order, cross-key concurrency, dedup cache, eviction policies
- `gateway_pipelines.test.cljs` — first-deny short-circuit, async checks, sync-throw handling
- `gateway_streaming.test.cljs` — all four policies' chunk/end state machines
- `gateway_core.test.cljs` — channel registry, allow-list auth check, `create-gateway` validation
- `gateway_projects.test.cljs` — project/agent resolution for `run_in_project`

Run just the gateway slice (after `npx squint compile`; `bun run test`
compiles first):

```bash
bun test dist/gateway_*.test.mjs
```

---

## Known gaps

Things the code does not do, listed so nobody has to rediscover them:

- **Fragment streaming.** Every policy but `batch-on-end` sends fragments to
  adapters that treat them as the whole message. See the streaming section.
- **`allowed-channels` is dead config** — no adapter supplies the field it
  matches on.
- **`handoff_to_human` and `request_approval` are inert** — no adapter
  implements the meta ops they emit.
- **`/health` and `GET /result/<job>` are unauthenticated** on the HTTP channel.
- **The approval pipeline is unwired.** `create-gateway` builds and exposes
  `:approval-pipeline`, and nothing ever consults it.
- **Shutdown does not drain** in-flight turns.
- **Session transcripts leak.** Every gateway conversation is written to
  `/tmp/nyma-sdk-session-<ts>.jsonl` and never removed. So are Telegram
  attachment downloads.
- **No idle eviction for ACP workers** (see `gateway.projects`).

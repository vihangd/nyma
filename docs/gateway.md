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
drains in-flight work and calls `stop!` on every channel.

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

All config keys use **kebab-case** (`system-prompt`, `idle-evict-ms`) — the
gateway reads them with plain Clojure keyword lookups and does not rewrite
camelCase. Channel-level keys inside `channels[].config` follow the same
convention per adapter (see each channel section below).

### `agent.*` — agent creation

| Key | Type | Purpose |
|---|---|---|
| `model` | string, **required** | Model ID the agent runs with (e.g. `claude-sonnet-4-6`) |
| `system-prompt` | string | Optional base system prompt; agent's own discovery pipeline still runs |
| `modes` | string[] | Tool mode filter — only tools declaring one of these modes are loaded. The built-in gateway tool set uses `"gateway"` |
| `exclude-capabilities` | string[] | Drops tools that declare any of these capabilities — typical values: `"execution"`, `"shell"`, `"filesystem"` |
| `require-capabilities` | string[] | Inverse filter — only loads tools declaring all of these capabilities |

### `gateway.streaming` — how partial replies reach the platform

Policies wrap the agent's text-delta stream before it hits the channel's
`stream!` function. All policies end up calling `send!`/`stream!` with
**complete text payloads**, never raw deltas — the channel adapter decides
whether to post a new message or edit an existing one.

| Policy | Latency | Call volume | Best for |
|---|---|---|---|
| `immediate` | Lowest | Highest | Fast-editing platforms (Slack, Telegram) — each chunk edits the same message in place |
| `debounce` *(default)* | ~`delayMs` ms | Medium | Good balance; flushes after N ms of silence |
| `throttle` | ~`intervalMs` ms | Bounded | Strict rate limits; leading-edge emit then cap at most one call per interval |
| `batch-on-end` | Final only | 1 per turn | Email, SMS, or any platform that cannot edit — emit once when the response is complete |

Either form works:

```jsonc
"streaming": "debounce"
"streaming": { "policy": "debounce", "delay-ms": 250 }
"streaming": { "policy": "throttle", "interval-ms": 750 }
```

### `gateway.session` — conversation retention

| Policy | Behaviour |
|---|---|
| `persistent` *(default)* | Never auto-evict. The SDK session (agent + history) stays in memory until the daemon exits. |
| `idle-evict` | Evict the whole session when `Date.now() - lastActive > idle-evict-ms`. Next message creates a fresh session. |
| `ephemeral` | After every successful run, clear the session's `:data` (agent session + history). Each inbound message sees a stateless agent. |
| `capped` | Like `persistent` but the per-session data atom is subject to trimming (future work). |

`idle-evict-ms` defaults to 1 hour. Eviction runs on a 10-minute maintenance
timer alongside dedup-cache pruning.

### `gateway.dedup` — idempotency cache

Webhook deliveries (Slack events, Telegram retries) may redeliver the same
message. Adapters feed an `event-id` into `mark-seen!`/`seen-event?` to
suppress duplicates. TTL is 5 minutes by default, configurable via
`dedup.cache-ttl-ms`.

### `gateway.auth` — allow-list check

When either `allowed-user-ids` or `allowed-channels` is non-empty, an auth check
is automatically registered at startup. Requests with a user-id or
channel-name not on the corresponding list are denied with a
`"User X not on allow-list"` style reason.

Custom auth checks can be added at runtime:

```clojure
((:add! (:auth-pipeline gw))
 (fn [req]
   (js/Promise.resolve
    (if (rate-limit-ok? (.-userId req))
      #js {:allow? true}
      #js {:allow? false :reason "rate limited"}))))
```

Check-fn return shape: `{allow?: bool, reason?: string}` (or nil = allow).
The pipeline short-circuits on the first deny. Handlers that throw default
to allow (logged to stderr).

### `channels[]` — platform adapters

Each entry is `{type, name, config}`. `name` must be unique. `type` is a
keyword that must have been registered via `register-channel-type!` before
the config is loaded — the four built-in adapters self-register from
`gateway.entry`.

## Built-in channel adapters

| Type | Capabilities | Streaming strategy | Key config |
|---|---|---|---|
| `telegram` | text, typing, attachments | Leading-edge immediate (edits the reply-in-place) | `token` — bot token from @BotFather |
| `slack` | text, typing, threads | Leading-edge immediate (edits the message) | `app-token`, `bot-token` — xapp-/xoxb- tokens; uses Socket Mode (no public URL needed) |
| `http` | text | Batched — final reply in HTTP response body | `port` (default `3000`), `host` (default `"0.0.0.0"`), `secret?` (Bearer token) |
| `email` | text | Batch-on-end (one reply per inbound message) | `imap-host`, `smtp-host`, `user`, `password` (+ optional ports/TLS flags) |

All four accept `${VAR}` interpolation in their config values, and all lazy-load
their underlying SDKs (`node-telegram-bot-api`, `@slack/socket-mode`,
`Bun.serve`, `imap-simple`+`nodemailer`) — if the dependency is missing the
gateway prints a `bun add <pkg>` hint on startup.

### Telegram

```json
{
  "type":   "telegram",
  "name":   "my-telegram",
  "config": { "token": "${TELEGRAM_BOT_TOKEN}" }
}
```

Long-polling via the Bot API. Conversation ID is `telegram:<chat_id>`, so
each chat becomes its own session lane. The adapter sets typing indicators
on every inbound message until the response is fully streamed.

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

Run `nyma-gateway setup` to verify both tokens are valid before starting the
daemon.

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

Missing required fields return `400`.

When `secret` is set, every request must include
`Authorization: Bearer <secret>` or the server returns `401`.

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
land in the same agent session. Streaming policy is `batch-on-end` — each
email gets exactly one reply, never partial updates.

---

## Gateway-only tools

When an agent runs under the gateway, it gets a small set of tools that let it
talk *through* the channel instead of to the local filesystem:

| Tool | Purpose |
|---|---|
| `send_message` | Emit an additional message into the current conversation (besides the main reply) |
| `typing_indicator` | Show/hide a typing indicator while a long operation runs |
| `conversation_info` | Read the conversation's metadata — channel name, user id, capabilities |
| `handoff_to_human` | Escalate: tag the conversation for human review and stop agent responses |
| `request_approval` | Ask the human operator to confirm a destructive action before executing it |

All five declare `:modes #{:gateway}` in their tool metadata, so they pass the
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
| `capabilities` | Set/Array of keywords or strings | Platform features: any of `:text`, `:typing`, `:threads`, `:attachments` |
| `start!` | `(on-message-fn) → Promise<void>` | Begin accepting messages. Call `on-message-fn(inbound, response-ctx)` for each one |
| `stop!` | `() → Promise<void>` | Gracefully shut down |

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
 :attachments     [{:url "..." :mime-type "..." :local-path "..."}]
 :raw             <original-platform-event>}
```

### Response context shape

The `response-ctx` argument is an `IResponseContext`:

```clojure
{:conversation-id "channel:chat-id"
 :channel-name    "my-channel"
 :capabilities    #{:text :typing}
 :send!       (fn [content-map] ...)    ;; content-map: {:text, :markdown, :image-url, :file-path}
 :stream!     (fn [chunk-string] ...)    ;; deliver a streaming text delta
 :meta!       (fn [op args] ...)         ;; ops: :typing-start :typing-stop :tool-start :tool-end :done
 :interrupt!  (fn [reason] ...)          ;; user interrupted → abort the run
}
```

You rarely build this by hand — use `gateway.protocols/make-response-context`
or compose with `gateway.streaming/create-streaming-policy`:

```clojure
(require '[gateway.streaming :as streaming])

(let [{:keys [on-chunk on-end]}
      (streaming/create-streaming-policy
        (fn [content-map] (platform-send chat-id content-map))
        :debounce)]
  (make-response-context
    {:conversation-id (str "my-chan:" chat-id)
     :channel-name    "my-chan"
     :capabilities    #{:text}
     :send!           (fn [c] (platform-send chat-id c))
     :stream!         on-chunk
     :meta!           (fn [op _] (when (= op :done) (on-end nil)))}))
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
  (:require [gateway.protocols :as proto]
            [gateway.streaming :as streaming]))

(defn create-discord-channel [channel-name cfg]
  (let [token  (:token cfg)
        client (atom nil)
        running? (atom false)]
    {:name         channel-name
     :capabilities #{:text :typing}

     :start!
     (fn [on-message-fn]
       (reset! running? true)
       ;; ... create a Discord client ...
       (let [c (discord-create-client token)]
         (reset! client c)
         (.on c "messageCreate"
              (fn [msg]
                (let [chat-id (.-channelId msg)
                      {:keys [on-chunk on-end]}
                      (streaming/create-streaming-policy
                       (fn [content] (.send c chat-id (:text content)))
                       :debounce)
                      ctx (proto/make-response-context
                            {:conversation-id (str "discord:" chat-id)
                             :channel-name    channel-name
                             :capabilities    #{:text}
                             :send!           (fn [c] (.send c chat-id (:text c)))
                             :stream!         on-chunk
                             :meta!           (fn [op _]
                                                (when (= op :done) (on-end nil)))})]
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

Run just the gateway slice:

```bash
bun test dist/gateway_*.test.mjs
```

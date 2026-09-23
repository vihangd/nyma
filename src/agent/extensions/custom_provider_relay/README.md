# custom-provider-relay

Register any OpenAI- or Anthropic-compatible **gateway** as a nyma provider from settings,
without writing code. Ships with presets for [yunwu.ai](https://yunwu.ai).

A gateway (relay, proxy, LLM gateway) fronts *other vendors'* models under those vendors'
own ids. "Gateway" here is about **what it serves, not where it lives** — a proxy you run on
`localhost` belongs here too, not in `custom-provider-local` (see
[Self-hosted gateways](#self-hosted-gateways)). That distinction is what separates the two
extensions, and this one deliberately does not extend the other:

- a missing key is a real error, not something to paper over with a placeholder — a gateway
  answers a dummy key with an opaque `401`;
- its prices are its own, so its models must not inherit the first-party rate for the same
  model id;
- its catalogue is large and changes without notice, so the model list is **discovered**
  rather than hardcoded.

## Quick start with yunwu

```bash
export YUNWU_API_KEY=sk-...     # or, inside nyma:  /login yunwu
```

```
/model yunwu/gpt-5.2              # OpenAI-compatible  → /v1/chat/completions
/model yunwu-claude/claude-opus-5 # Anthropic-native   → /v1/messages
```

Both presets share one credential, so a single `/login yunwu` covers them.

## Prompt caching: use `yunwu-claude` for Claude models

**Claude prompt caching only works on the `anthropic` variant.** This is not a preference;
both halves of the OpenAI-compatible path drop the breakpoints:

- `@ai-sdk/openai` has no representation for `cache_control`, so nyma's `token-suite`
  breakpoints are discarded before the request leaves the process;
- New API (which yunwu runs) parses `cache_control` on OpenAI-shaped content parts
  (`relaykit/dto/openai_request.go`) but its OpenAI→Claude request converter
  (`relayconvert/internal/oai_chat/to_claude_messages_req.go`) never reads it.

On the `anthropic` path the breakpoints survive end to end: `@ai-sdk/anthropic` emits them,
and New API's `ConvertClaudeRequest` returns the request unchanged.

Routing a Claude model through `yunwu` rather than `yunwu-claude` therefore means paying
full input price on every turn.

## Configuration

Add a `providers` array to `settings.json`. Entries with the same `name` as a preset
replace it.

```json
{
  "providers": [
    {
      "name":       "mygateway",
      "baseUrl":    "https://gateway.example.com/v1",
      "apiKeyEnv":  "MYGATEWAY_API_KEY",
      "api":        "openai-compatible",
      "include":    ["claude", "/^gpt-5/"],
      "exclude":    ["preview"],
      "discover":   true,
      "models":     [{ "id": "some-model", "contextWindow": 200000 }]
    }
  ]
}
```

| Key | Default | Meaning |
| --- | --- | --- |
| `name` | — | Provider name. Models are `name/<model-id>`. |
| `baseUrl` | — | Include the version segment, e.g. `.../v1`. |
| `apiKeyEnv` | — | Env var holding the key. |
| `credentialName` | `name` | Which `/login` entry to read, for gateways registered twice. |
| `api` | `openai-compatible` | Or `anthropic` (Messages API), or `openai-responses`. |
| `discover` | `true` | Fetch the model list from `GET <baseUrl>/models`. |
| `catalogUrl` | *(none)* | Absolute URL of a richer catalog to discover from instead. See below. |
| `group` | *(none)* | New API billing group the token was minted in (`Kiro-Claude-1`). Prices the catalogue from the relay's public `/api/pricing` and narrows it to what the group serves. See [billing groups](#openlux-billing-groups-kiro--codex). |
| `endpointTypes` | *(any)* | Required `supported_endpoint_types`, any-of. |
| `types` | *(any)* | Required `type`, any-of — keeps image/embedding models out of the picker. |
| `paidOnly` | `false` | Drop models the catalog prices at zero on both sides. |
| `availableOnly` | `true` | Drop models the catalog itself flags `available: false`. On by default, and safe: gateways that never report the field are untouched. Set `false` to list everything, working or not. |
| `rescueParsing` | `false` | Recover tool calls a model emits as prose instead of `tool_calls`. |
| `include` | *(all)* | Allow-list of substrings or `/regex/`, case-insensitive. |
| `exclude` | *(none)* | Subtracted after `include`. |
| `overheadTokens` | *(none)* | Tokens the gateway adds to every request (see below). |
| `models` | `[]` | Seed list, and per-model `contextWindow` / `cost` overrides. |

Credentials resolve as **env var → `/login` entry**. There is no fallback beyond that: a
missing key raises an error naming both, rather than sending a placeholder.

## Model discovery

Discovery follows Claude Code's gateway contract, including the parts that exist for safety:

- `GET <baseUrl>/models?limit=1000`, 3-second timeout;
- **redirects are treated as failure**, so the credential cannot leak to a redirect target;
- exactly one credential header;
- results cached to `~/.nyma/cache/models-<provider>.json`, 24h TTL;
- the cached list registers synchronously at startup, so the provider is usable
  immediately; a stale cache refreshes in the background and re-registers, which `/model`
  picks up without a restart;
- a failed refresh degrades to the cache, then to the seed list — never to nothing;
- **no retries.** Gateways commonly throttle repeated auth failures; yunwu answers a bad key
  with a 120-second `429`, so a retry loop turns one typo into a two-minute outage.

Set `NYMA_NO_MODEL_DISCOVERY=1` to suppress every network call this extension makes. The
test suite sets it via `scripts/test-preload.mjs`, so `bun test` never reaches a gateway even
on a machine that exports a real key.

Filtering matters at relay scale. A gateway can expose hundreds of models — including image,
audio and retrieval endpoints nyma cannot drive — which would bury the handful you use.

Prefer `endpointTypes` over `include`/`exclude` where the gateway supports it. New API
reports `supported_endpoint_types` per model, which is its own statement of what it will
serve rather than a guess from the name:

```
glm-4.7                  ["openai"]          ← kept
gemini-2.5-flash         ["gemini","openai"] ← kept
BAAI/bge-reranker-v2-m3  ["rerank"]          ← dropped
mj_inpaint               ["mj动作"]           ← dropped
gpt-4o-transcribe        ["语音转文字"]        ← dropped
wen-max-2025-01-25       []                  ← dropped, serves nothing
```

The field is a New API extension, not standard OpenAI, so models that don't declare it are
never filtered on it — a gateway that omits it isn't reduced to an empty catalogue.

### Per-model protocol dispatch

A gateway does not serve every model over every endpoint, so `api` is a default rather than
a rule: when a model declares its endpoint types, those decide the wire protocol.

```
gpt-5.2   ["openai","openai-response"]  → /v1/chat/completions
gpt-5.4   ["openai-response"]           → /v1/responses
glm-4.7   ["openai"]                    → /v1/chat/completions
```

This matters on yunwu: much of the GPT-5.x line — `gpt-5.4`, `gpt-5-pro`, `gpt-5-codex`,
`gpt-5.1-codex-max`, `gpt-5.2-pro` — is `/responses`-only and returns 404 on
`/v1/chat/completions`. An `anthropic` entry always stays on the Messages API, since Claude
ids advertise both and only that path preserves caching.

The `yunwu` preset therefore excludes `claude`: those ids serve both protocols, so without
it they would appear under both providers, and the copy under `yunwu` would silently lose
prompt caching.

### Context windows and prices

New API's `/v1/models` is OpenAI-shaped (`{id, object, owned_by}`) and carries neither a
context window nor pricing. Two consequences:

- **Context window** falls back to the vendor's own entry, since the relayed id *is* the
  vendor's id — `yunwu-claude/claude-opus-5` correctly reports 1M. Ids nyma doesn't
  recognise fall back to the 100k default; declare `contextWindow` in `models` to fix one.
- **Price** deliberately does *not* fall back. A relay charges its own rates, so showing the
  first-party number would be worse than showing none. `/model` leaves the column blank
  unless you declare `cost` explicitly — or the entry names a `group`, in which case the
  relay's own pricing sheet supplies every rate (next section).

## openlux billing groups (Kiro / Codex)

On a New API relay a **token belongs to a group**, chosen when the token is minted
(Console → Tokens → Add → 分组). A group is an upstream route with its own multiplier:
`Kiro-Claude-1` and `Codex-Gpt-1` ride the Kiro and Codex subscription CLIs and serve the
same model ids as the official-route groups (`Anthropic-Claude-1`, `Openai-Gpt-1`) at a
twelfth to a sixteenth of the price. The group is not selectable per request, so each one
is its own provider with its own token:

```
/login openlux-kiro          # or OPENLUX_KIRO_API_KEY=…
/model openlux-kiro/claude-sonnet-5

/login openlux-codex         # or OPENLUX_CODEX_API_KEY=…
/model openlux-codex/gpt-5.6-terra
```

Two presets ship. Neither shares the `openlux` credential: a shared token is a token in the
wrong group.

| Preset | Group | Wire | Serves (sheet, 2026-09-23) | USD per 1M in / out |
| --- | --- | --- | --- | --- |
| `openlux-kiro` | `Kiro-Claude-1` (×0.088) | anthropic | claude-sonnet-5, haiku-4-5, opus-5, opus-4-5…4-8, sonnet-4-5/4-6, fable-5, fable-5-1 | sonnet-5 0.18 / 0.88 · haiku 0.09 / 0.44 · opus-5 0.44 / 2.21 |
| `openlux-codex` | `Codex-Gpt-1` (×0.037) | openai (+responses) | gpt-5.6-terra, gpt-5.6-sol, gpt-5.5, gpt-5-codex, gpt-6-astra | terra 0.07 / 0.44 · gpt-5-codex 0.05 / 0.37 · sol 0.18 / 1.10 |

What `group` does at discovery: it fetches the relay's public `GET /api/pricing` (no key is
sent — the sheet is unauthenticated), prices every model the group serves as
`model_ratio × group_ratio × $2` per 1M input, `× completion_ratio` output, and cache read /
write from `cache_ratio` / `cache_creation_5m_ratio`; and it **keeps only the ids in the
group's `enable_groups`** — a group token's `/v1/models` lists the whole catalogue, and the
sheet is the only statement of which of those the token will actually be served. Prices
land in the model cache (keyed `<name>@<group>`, so changing or dropping the group is a cache
miss, not 24 hours of the wrong list), and the next start shows them before any refresh. A
sheet that is unavailable or does not know the group is warned about and degrades to ordinary
discovery: unfiltered, unpriced, never nothing.

To price the plain `openlux` / `openlux-claude` presets, tell nyma which group *your* token
is in — nyma cannot read it off the wire:

```json
{ "providers": [{ "name": "openlux-claude", "baseUrl": "https://api.openlux.ai/v1",
                  "api": "anthropic", "apiKeyEnv": "OPENLUX_API_KEY",
                  "endpointTypes": ["anthropic"], "group": "Anthropic-Claude-1" }] }
```

Caveats. Capacity is the subscription's: the `429 当前分组上游负载已饱和` note above is likelier
in these groups, and there is no retry-after. Prompt caching and thinking work as far as the
upstream CLI passes them through; the official route is the reference. Seed prices in the
presets are the sheet as of 2026-09-23; discovery overwrites them.

## Where the context windows come from

Discovery reads whatever the catalog declares: a window from `context_window`,
`context_length` or `max_model_len`, and a price from either `input_per_1m_usd` /
`output_per_1m_usd` (already USD per 1M) or `prompt` / `completion` (USD per *token*,
scaled up). New API declares neither, so for those relays the numbers still come from
the `models` entry you write by hand.

Where both exist, **discovery wins** and your declared entry fills only what the catalog
left out. That way a hand-typed window is a fallback rather than a permanent override,
and a stale one heals itself on the next refresh.

### `catalogUrl`

Some gateways publish a fuller catalog at a different path than their OpenAI surface.
Velona is the shipped example: `https://velona.in/v1/models` lists ids and nothing else,
while `https://velona.in/gateway/v1/models` carries real windows and prices. Point
`catalogUrl` at the richer one.

`catalogUrl` **must share an origin with `baseUrl`**, and is refused otherwise. Two
reasons: an entry naming another host would be handed this provider's credential (the leak
the redirect refusal exists to prevent, reached directly instead of through a 302) — and,
key or no key, whatever that host returned would be registered as this provider's context
windows and prices. A 4096-token window makes compaction thrash every turn; a fabricated
rate makes cost accounting lie.

## Velona (India)

Ships as a preset. [Velona](https://velona.in) bills in INR with UPI top-up and no
international card, and fronts ~419 models under their vendors' own ids.

```
export VELONA_API_KEY=...      # or: /login velona
nyma --model velona/qwen/qwen3.8-27b
```

Note the model spec splits on the **first** slash only, so `velona/qwen/qwen3.8-27b` is
provider `velona`, model `qwen/qwen3.8-27b`.

Discovery needs a key even though Velona's catalog endpoint is public — without one you
get the seeded list. Velona reports `capabilities`, not `supported_endpoint_types`, so
the preset filters on `types: ["text"]` instead of `endpointTypes`.

**Free-tier models are not available here.** `/v1` answers a zero-priced id with
`model_not_supported` and points at the native `/gateway/v1/inference/run` surface, which
is a different wire format. The preset therefore sets `paidOnly: true`. Note the marker is
the price, not a `:free` suffix — `stealth/ox-alpha`, the two `lyria` ids and
`openrouter/free` are all free without one.

**`qwen/qwen3.6-35b-a3b` is excluded.** It reasons its way to *"I should use the bash
tool"* and then ends the turn with `finish_reason: stop`, no `tool_calls` and no content —
every agent turn a silent no-op, which renders as thinking followed by an empty reply. It
is not the model: the same request with `tool_choice: "required"` calls tools normally, so
Velona's default `auto` path is dropping them. Forcing `required` would stop it ever
giving a final answer, so there is no fix from this side. OpenRouter documents the same
failure for this exact id on one of its backends.

**`rescueParsing` is on for Velona.** Models here emit a native tool call one turn
and print `<function=read>…</function>` as ordinary text the next — the run then stalls
with the markup rendered as an answer. The rescue parses those back into real tool calls
(Qwen XML, Mistral bracket, fenced JSON), withholds the markup so it never reaches the
message, and releases the text untouched when nothing parses.

Verified emitting tool calls on Velona: `qwen/qwen3.8-27b`, `z-ai/glm-5.3`,
`deepseek/deepseek-v4-pro-0813`, `google/gemini-3.7-flash`, `anthropic/claude-sonnet-5`,
`x-ai/grok-4.6`, `nvidia/nemotron-3-nano-30b-a3b`, `nvidia/nemotron-3.5-lightning`,
`nvidia/nemotron-3-super-120b-a12b`, `nvidia/nemotron-3-ultra-550b-a55b`.

The nemotron nano is the cheapest of these by an order of magnitude on input
($0.05/1M, 262K context) and drove a real multi-file agent turn.

## Adding another New API relay

yunwu is one of many [New API](https://github.com/QuantumNous/new-api) deployments; they all
expose the same endpoints. Point `baseUrl` at yours and both protocols work:

```json
{ "providers": [
  { "name": "myrelay",        "baseUrl": "https://relay.example/v1",
    "apiKeyEnv": "MYRELAY_KEY" },
  { "name": "myrelay-claude", "baseUrl": "https://relay.example/v1",
    "apiKeyEnv": "MYRELAY_KEY", "credentialName": "myrelay",
    "api": "anthropic", "include": ["claude"] }
]}
```

## Self-hosted gateways

A gateway you run yourself still belongs here, not in `custom-provider-local`. The deciding
question is never the hostname — it is whether the endpoint serves *other vendors'* models
under those vendors' own ids. If it does, registering it as a local provider writes bare-id
entries that **silently overwrite the first-party vendor's metadata**, because the last
registration wins. `unpriced: true` (which this extension always sets) is what keeps a
gateway's `claude-sonnet-4-5` from clobbering Anthropic's.

Nothing here requires `https` or a remote host: `baseUrl` is passed through verbatim, and the
same-origin check applies only to a custom [`catalogUrl`](#catalogurl).

Worked example — [FreeLLMAPI](https://github.com/tashfeenahmed/freellmapi), a self-hosted
router that fronts ~34 free-tier providers. **Check the port it actually bound** —
`3001` is the Docker default, but the desktop app uses `31415`; `lsof -nP -iTCP -sTCP:LISTEN |
grep -i freellm` settles it:

```json
{ "providers": [
  { "name":      "freellmapi",
    "baseUrl":   "http://127.0.0.1:31415/v1",
    "apiKeyEnv": "FREELLMAPI_API_KEY",
    "api":       "openai-compatible",
    "discover":  true,
    "include":   ["/^claude-/", "/^gemini-/", "gpt-oss", "llama"],
    "exclude":   ["preview", "vision", "embed"] }
]}
```

Its key is mandatory (`Bearer freellmapi-<key>`, minted in its dashboard), which is the other
reason `custom-provider-local` is wrong for it: that extension's `local-no-key` placeholder
would turn a missing key into an opaque `401`.

FreeLLMAPI discovers several hundred endpoints but flags most of them
`available: false` (`unavailable_reason: "no_key"`) — listed, but with no upstream credential
behind them. `availableOnly` is on by default and removes those; measured on one instance it
takes the picker from 248 entries to 41. Use `include`/`exclude` to narrow what remains.

Note that FreeLLMAPI serves some Anthropic-named ids that are **not** Anthropic models —
`claude-opus-4-5` is listed as *"Opus slot (auto-routed to a free model)"*. nyma shows that name
in `/model` beside the context window, and `unpriced` keeps such ids from overwriting the real
vendor's metadata. Read the description before assuming an id means what it says.

## Audit what a gateway injects before you trust it

A relay can prepend anything it likes to your requests, and you pay for it. Measured on
yunwu's Claude path: a request whose entire payload was the word `hi` billed **6,765 input
tokens**, of which 5,478 were cache reads on the very first call — a fixed prefix shared
across the gateway's users.

Asked plainly what product it is, the model answers: *"I'm Kiro, an AI-powered development
environment built by AWS."* Consistent with a relay reselling Kiro capacity and replaying
that client's system prompt so traffic looks native.

The injection is **not** protocol-specific. The same model over the OpenAI-compatible path
bills the identical 6,765 tokens with the identical 5,478-token cache read, so switching
`api` does not escape it.

Consequences worth knowing before pointing real work at any gateway:

- **Your system prompt is added to theirs, not substituted for it.** A ~220-token system
  prompt of our own grew the request by 475 tokens, so both are present — and nyma's
  instructions then compete with the injected persona's. Replies come back in the injected
  house style rather than nyma's.
- **Context accounting under-reports.** nyma estimates locally, and yunwu returns `404` for
  `/v1/messages/count_tokens`, so there is no way to see the real figure short of reading
  `usage` off a response.
- **The overhead is not a constant.** Observed 6,765 / 6,791 / 7,030 / 7,240 / 7,501 across
  five requests, with cache reads swinging 5,478 → 6,075.
- Cost is mostly cache reads, which bill at a fraction of fresh input — but it is not zero,
  and it is charged on every turn.

The cache split is what makes the `anthropic` variant worth using for Claude, and it is not
visible from the totals:

| path | input | cacheRead | cacheWrite | plain |
| --- | --- | --- | --- | --- |
| `anthropic` | 6,765 | 5,478 | 1,286 | 1 |
| `openai-compatible` | 6,765 | 5,478 | 0 | 1,287 |

Identical bills for one turn, but only the `anthropic` path writes to the cache — so on that
path a conversation's own history is cached and read back cheaply on later turns, while on
the OpenAI path nothing is ever cached and every turn pays fresh input for the lot. That is
why the `yunwu` preset excludes Claude ids: routing them there would pay the same injection
cost with none of the caching.

To check any gateway yourself, send a one-word prompt with no system prompt and read
`usage.inputTokens` off the response. Anything far above the size of what you sent is
injection.

### Telling nyma about it: `overheadTokens`

nyma estimates context locally, so an injected prefix is invisible to it and compaction
plans against a window it does not actually have. Declare the measured figure and it is
counted as used:

```json
{ "name": "yunwu-claude", "api": "anthropic",
  "baseUrl": "https://yunwu.ai/v1", "apiKeyEnv": "YUNWU_API_KEY",
  "overheadTokens": 6800 }
```

It is added to `tokenBudget.tokensUsed` and reported separately as
`tokenBudget.overheadTokens`, so headroom, compaction and priority assembly all allow for
it while a consumer can still tell how much of the total is not its own content. The
reported `contextWindow` is left alone — that is the model's real capacity, and shrinking it
would misreport the model rather than the gateway.

No preset declares a value. The figure belongs to an account and a moment in time, and a
stale hardcoded number would be worse than none — measure yours with the check above.

## Known limitations

- New API rewraps every upstream error as `{"error":{"type":"new_api_error"}}`, so error
  text from the underlying vendor does not survive. Nothing in nyma should match on upstream
  error wording through a relay.
- A gateway that buffers responses instead of streaming them will stall the UI. Relays vary;
  this is worth checking on first use.
- `/v1/responses` and Gemini's `/v1beta` route exist on yunwu but are not wired up here.

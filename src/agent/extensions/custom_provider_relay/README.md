# custom-provider-relay

Register any remote OpenAI- or Anthropic-compatible **gateway** as a nyma provider from
settings, without writing code. Ships with presets for [yunwu.ai](https://yunwu.ai).

A gateway (relay, proxy, LLM gateway) fronts *other vendors'* models under those vendors'
own ids. That is what separates this from `custom-provider-local`, which it deliberately
does not extend:

- a missing key is a real error, not something to paper over with a placeholder — a remote
  gateway answers a dummy key with an opaque `401`;
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
| `api` | `openai-compatible` | Or `anthropic` for the native Messages API. |
| `discover` | `true` | Fetch the model list from `GET <baseUrl>/models`. |
| `include` | *(all)* | Allow-list of substrings or `/regex/`, case-insensitive. |
| `exclude` | *(none)* | Subtracted after `include`. |
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
audio and embedding endpoints nyma cannot drive — which would bury the handful you use. The
`yunwu` preset excludes non-chat families; `yunwu-claude` includes only `claude`.

### Context windows and prices

New API's `/v1/models` is OpenAI-shaped (`{id, object, owned_by}`) and carries neither a
context window nor pricing. Two consequences:

- **Context window** falls back to the vendor's own entry, since the relayed id *is* the
  vendor's id — `yunwu-claude/claude-opus-5` correctly reports 1M. Ids nyma doesn't
  recognise fall back to the 100k default; declare `contextWindow` in `models` to fix one.
- **Price** deliberately does *not* fall back. A relay charges its own rates, so showing the
  first-party number would be worse than showing none. `/model` leaves the column blank
  unless you declare `cost` explicitly.

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

## Known limitations

- New API rewraps every upstream error as `{"error":{"type":"new_api_error"}}`, so error
  text from the underlying vendor does not survive. Nothing in nyma should match on upstream
  error wording through a relay.
- A gateway that buffers responses instead of streaming them will stall the UI. Relays vary;
  this is worth checking on first use.
- `/v1/responses` and Gemini's `/v1beta` route exist on yunwu but are not wired up here.

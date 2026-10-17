# codemode

One `codemode__script` tool. The model writes squint (ClojureScript) that calls tools as functions, and gets back only the last form. Fetch, filter and fetch-more becomes one round trip, and the intermediate data never enters the context.

```clojure
(let [ms (js-await (mail/search-emails {:from_contains "renga"}))]
  (->> (js-await (mail/get-email-data {:email_ids (map :email_id ms)}))
       (map (fn [m] {:date (:received m) :subject (:subject m)
                     :ask  (second (re-find #"Regarding [^:]+: ([^.]+)" (:html m)))}))))
```

Off by default:

```json
{ "codemode": { "enabled": true, "script-only": ["mcp__*"] } }
```

`script-only` takes tool-name patterns and withholds the matching tools from the direct tool list, so they can be reached only from scripts. `mcp_call` is always withheld. When a model is offered a tool directly, it calls it one step at a time and writes no script; the benchmark showed this happening.

## Names

| registered tool | in a script |
|---|---|
| `mcp__github__list_issues` | `github/list-issues` (or `github/list_issues`) |
| `git-tools__status` | `git-tools/status` |
| `read`, `grep`, … | `nyma/read`, `nyma/grep` |
| OpenAPI op `getPetById` of api `petstore` | `petstore/getPetById` |

Argument names are the tool's own, so `per_page` stays `per_page`. The system prompt carries a catalog of signatures for tools matching `codemode.catalog` (default `["mcp__*"]`) plus every API operation. It is capped at about 2k tokens, filled round-robin across namespaces. Any other registered tool can be called the same way.

## Safety

- **The script is gated like bash.** squint compiles code; it does not confine it. So `codemode__script` is in the exec category: it asks in default and accept-edits mode, runs in full-auto, and isn't offered in plan mode.
- **The prompt shows what the script will call:**
  ```
  Allow 'codemode__script'?  (exec)
  calls: mail/search-emails (read) · mail/get-email-data (read)
    (let [ms (js-await (mail/search-emails {:from_contains "renga"}))]
  ```
  `UNRESTRICTED: …` is appended when the source reaches past its functions: `js/` (except `js/Promise.all`), `.method`, `aget`, `"constructor"`, a JS global such as `Bun/spawn` or a bare `globalThis`. Strings and comments are not scanned as code. A computed key such as `(str "constr" "uctor")` still gets past these checks, which is why every script asks.
- **Only the tools the source names get bound.** They're preapproved for that run, so an approved script doesn't re-ask per call.
- **Each nested call still goes through the middleware pipeline under its own name** (`api.executeTool`). So `before_tool_call` hooks, `.nymaignore` and a per-tool deny all apply. Approval answers an ask; it never lifts a deny.
- **The script runs in a Worker.** The timeout (`timeout-ms`, 60 s) and Esc terminate it, even inside `(loop [] (recur))`, and abort the tool calls still in flight. That is for interruption, not isolation.
- **`codemode.apis` in a project's `.nyma/settings.json` is ignored until the folder is trusted.** It decides where `${ENV}` headers are sent.

## Results

- The last form, deeply awaited, with lazy seqs realized. An un-awaited `(map #(gh/get-issue {:number %}) ids)` runs in parallel and still comes back as data.
- `println` output goes into `logs:`, and survives a timeout.
- A tool result that is JSON text arrives parsed.
- A failed run says which call failed and lists every call with its status, so a retry knows what already ran:
  ```
  [Denied] Permission denied (in mail/delete-email)
  calls: mail/search-emails ok, mail/delete-email denied
  ```
  Error kinds: `ParseError`, `TrapLint` (squint traps such as `js->clj`, or `js-await` inside a fn given to `filter`/`reduce`, where the promise would be used as the value), `UnknownTool` (with did-you-mean), `ToolFailure`, `Denied`, `CallLimit` (`max-calls`, 50), `Timeout`, `Aborted`.
- Output over `max-output-chars` (20000) is cut, and the full text is written to a private temp file whose path is returned.

## APIs

```json
{ "codemode": { "enabled": true,
  "apis": { "petstore": { "spec": "https://petstore3.swagger.io/api/v3/openapi.json",
                          "base-url": "https://petstore3.swagger.io/api/v3",
                          "headers": { "Authorization": "Bearer ${PETSTORE_TOKEN}" } } } } }
```

- Each operation becomes a registered tool, so it gets the same pipeline and gate. GET/HEAD are read; the rest ask as writes.
- These tools are withheld from the direct tool list; only scripts call them.
- Path and query params go by name; the JSON body goes in `body`.
- Without `base-url`, the spec's first server URL is used, resolved against the spec's URL.
- Headers are expanded from the environment at call time and appear in no schema, description or result.
- The spec can be JSON or YAML, from a URL or a file.

## Benchmark

`bun bench/codemode/run.mjs <model> [runs] [scale]` runs an eM Client-shaped task against `bench/codemode/mail-mcp.mjs`, first with code mode off and then on.

On glm-5.3-flash at scale 6 (median of 3, all runs correct), code mode used:
- 11 steps against 19
- 137k input tokens against 257k
- 10 top-level calls against 36

At scale 1 there was nothing to save, and code mode did not win. See `docs/roadmap.md`, "Codemode, measured".

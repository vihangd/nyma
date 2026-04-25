# Plan: Porting 10 oh-my-pi UI Features into Nyma

**Date:** 2026-04-10

---

## 1. Exploration Notes

**UI shape.** The root component (`src/agent/ui/app.cljs`) renders: `Header → WidgetContainer(above) → ChatView → WidgetContainer(below) → [overlay] → Editor → [Notification] → Footer`. All UI files use `{:squint/extension "jsx"}` and must be required by callers with explicit `.jsx` extension (Squint convention from AGENTS.md). Header is a thin branded bar with model name. Footer is a static one-liner with hardcoded hints. The overlay system (`overlay.cljs`) already bridges the pi-mono `{render, onInput, dispose}` protocol via `CustomComponentAdapter`.

**Tool rendering.** `tool_status.cljs` already implements `group-messages`, `ToolGroupStatus`, `ToolStartStatus`, `ToolEndStatus`, and all the format helpers. The `.display` metadata hook (tested in `middleware.test.cljs`) lets tools attach `{formatArgs, formatResult, icon, verbosity}` fields propagated as `custom-*` keys on execution events. Feature #4 is ~80% done. Feature #9 extends this to a named renderer registry.

**Extension API.** `extensions.cljs` exposes `registerMentionProvider`, `registerBlockRenderer`, `registerMessageRenderer`, `setFooter`, `setHeader`, `setWidget`, `registerShortcut`, `ui.custom`, `ui.setStatus`. Features #1, #9, and #10 each need one new method. The ACP agent shell already contributes a custom status line via `setFooter` — the new #1 supersedes that pattern with a proper segment registry.

**Settings.** `settings/manager.cljs` merges global + project + runtime overrides. Extending `defaults` with new top-level keys is the established pattern.

**Keybindings.** `keybindings.cljs` loads `~/.nyma/keybindings.json` as flat `{key-combo → action-string}` and populates `(:shortcuts agent)`. No action-id abstraction, no conflict detection. Feature #5 builds the registry on top.

**Sessions.** `sessions/listing.cljs` has `list-sessions` (scans `.jsonl`, returns sorted metadata). `sessions/storage.cljs` has `recent-prompts` and `insert-prompt`. Both are available for the welcome screen and autocomplete.

**Test patterns.** Tests are `.cljs` in `test/`, run via `bun test`. Use `bun:test`. Async helpers use `defn ^:async` passed by name to `it`. Only pure exported functions are tested — React/Ink components are never rendered in tests. New tests must follow this convention. Import compiled exports directly: `["./agent/ui/tool_status.jsx" :refer [group_messages]]`.

**Squint constraints that affect this work.** Every JSX file required with `.jsx` extension; no `js->clj`; no callable maps/sets; props camelCase; `fn ^:async` broken (use `defn ^:async`); no `defmulti`.

**Overlaps with existing plans.** `docs/plan-tier1-features.md` covers `model_resolve` and hook additions — orthogonal. `docs/plan-extension-improvements.md` covers bash-suite emit-collect migration — orthogonal. No existing plan covers the 10 features here.

---

## 2. Shared Infrastructure

Build these first. Multiple features depend on each.

### SI-1: ~~ANSI-Safe Width Helpers Extension~~ — **DROPPED (2026-04-10)**

**Resolution of R1:** Nyma's UI is uniformly flexbox-based (`footer.cljs:11-17`, `header.cljs:7-15`, 12+ call sites in `tool_status.cljs`). The existing `ansi.cljs` module has `terminal-width`, `string-width`, `wrap-ansi`, `truncate-text` — no padding/slicing helpers, and none are needed. The status line (#1) will use Ink's `<Box justifyContent="space-between">` with `<Box flexGrow 1>` middle spacer, following the established footer pattern.

**No action required.** Keep `ansi.cljs` as-is.

### SI-2: Theme Palette Extension

**File to modify:** `src/agent/ui/themes.cljs`

Add to the `:colors` map in both `default-dark` and `default-light`:
- `:context-ok` — e.g., `"#9ece6a"` dark / `"#587539"` light
- `:context-warning` — e.g., `"#e0af68"` / `"#8c6c3e"`
- `:context-purple` — e.g., `"#bb9af7"` / `"#7847bd"`
- `:context-error` — e.g., `"#f7768e"` / `"#c64343"`

### SI-3: CollapsibleBlock Component

**File to create:** `src/agent/ui/collapsible_block.cljs`
(Use `{:squint/extension "jsx"}`, require as `"./collapsible_block.jsx"`)

Ink component `CollapsibleBlock`. Props: `header` (Ink element), `content` (Ink element), `expanded` (boolean), `onToggle` (fn). When `expanded` is false, renders only `header`. When true, renders a `Box flexDirection="column"` containing both. Export `CollapsibleBlock`.

### SI-4: Dynamic Border Component

**File to create:** `src/agent/ui/dynamic_border.cljs`
(Use `{:squint/extension "jsx"}`, require as `"./dynamic_border.jsx"`)

Ink component `DynamicBorder`. Props: `color`. Renders a full-width line of `─` characters computed from `(terminal-width)`. Export `DynamicBorder`.

### SI-5: Shared Time Utility

**File to create:** `src/agent/utils/time.cljs`

Extract `relative-time` from `src/agent/extensions/prompt_history/index.cljs` here. Then update `prompt_history/index.cljs` to require it from the shared location. Also update the welcome screen (feature #2) to use it.

---

## 3. Feature Plans

---

### Feature #5: Action-ID Keybinding Registry

**Build first.** #6 and #1 depend on it.

#### File to Create

**`src/agent/keybinding_registry.cljs`**

Pure logic (no JSX). Contains:

- **`default-actions`** — map from action-id string to `{:description string :default-keys [string] :category keyword}`. Initial set:
  - `"app.interrupt"` default `["escape"]` category `:agent`
  - `"app.help"` default `["?"]` category `:navigation`
  - `"app.history.search"` default `["ctrl+r"]` category `:navigation`
  - `"app.paste.expand"` default `["ctrl+o"]` category `:editor`
  - `"app.bash.expand"` default `["ctrl+o"]` category `:tools`
  - `"app.model.show"` default `["ctrl+l"]` category `:agent`
  - `"app.clear"` default `["ctrl+l"]` category `:session` (this conflicts with model.show — intentional placeholder, user must remap one)

- **`create-registry(user-overrides-map)`** → `{:actions map :user-overrides map :conflicts set}`. Computes conflicts at construction: any key-combo bound to more than one action is reported.

- **`combo-from-ink(input-str, key-obj)`** → canonical combo string. `ctrl+X`, `alt+X`, `"escape"`, `"return"`, or bare character. Exported for testing.

- **`matches?(registry, input-str, key-obj, action-id)`** → boolean. Checks user override first, then defaults.

- **`get-binding(registry, action-id)`** → combo string or nil. User override wins over first default key.

- **`detect-conflicts(actions-map, user-overrides)`** → seq of `{:key :action-ids}` where action-ids has count > 1.

- **`format-key-combo(combo-str)`** → display string. `"ctrl+r"` → `"^R"`, `"escape"` → `"esc"`, `"alt+f"` → `"M-f"`, single char → itself.

#### Files to Modify

**`src/agent/keybindings.cljs`** — After loading JSON, call `create-registry` and call `detect-conflicts`, logging conflicts via `js/console.warn`.

**`src/agent/core.cljs`** — Add `:keybinding-registry` key to the agent map.

**`src/agent/ui/app.cljs`** — In `useInput` handler, replace raw `(.-escape key)` and `(and (.-ctrl key) (= input "l"))` checks with `(matches? registry ...)` calls. Receive `registry` from `agent` props.

#### Test Plan

**File:** `test/keybinding_registry.test.cljs`

- `combo-from-ink` converts `ctrl+r`, `escape`, bare char correctly
- `matches?` true for default, true for override, false for unbound
- `detect-conflicts` empty when no overlap; finds conflict when two actions share a combo
- `get-binding` returns override when set, default otherwise
- `format-key-combo` for `"ctrl+r"`, `"escape"`, `"a"`

---

### Feature #6: Help Overlay + Key-Hint Helpers

**Depends on:** #5.

#### Files to Create

**`src/agent/ui/key_hints.cljs`** — Pure logic. Exports:
- `format-hint(registry, action-id, label)` → `"^R history"` or `"[?] label"` if unbound
- `hint-row(registry, action-id-label-pairs)` → concatenated hints separated by `"  "`
- `all-actions-grouped(registry)` → seq of `{:category :actions}` maps, grouped from `default-actions`

**`src/agent/ui/help_overlay.cljs`**
(Use `{:squint/extension "jsx"}`, require as `"./help_overlay.jsx"`)

Ink component `HelpOverlay`. Props: `registry`, `shortcuts` (extension shortcuts atom value), `onClose`. Renders sections by category, each action showing description and current binding. A second "Extension Shortcuts" section lists entries from `shortcuts`. Uses `useInput` with `escape` to call `onClose`. Export `HelpOverlay`.

#### Files to Modify

**`src/agent/ui/footer.cljs`** — Replace hardcoded hint string with `(hint-row registry [...])`. Add `registry` prop.

**`src/agent/ui/app.cljs`** — Add `(matches? registry input key "app.help")` branch in `useInput`. When `(and (empty? editor-value) (not streaming) (not overlay))`, open `HelpOverlay`. Pass `registry` to `Footer`.

#### Test Plan

**File:** `test/key_hints.test.cljs`

- `format-hint` with known binding → formatted string
- `format-hint` with nil binding → `"[?] label"`
- `hint-row` concatenates with correct separator
- `all-actions-grouped` groups by category, non-empty

---

### Feature #1: Pluggable Segmented Status Line

**Depends on:** #5, #6, SI-1, SI-2.

#### Files to Create

**`src/agent/ui/status_line_segments.cljs`** — Pure logic. The segment registry is an atom: `(atom {})`. Each entry: `{:id :render :category}`. The render function takes a context map, returns `{:content :visible}`.

Segment context map shape:
```
{:model-id :path :git-branch :git-status {:staged :unstaged :untracked}
 :pr-info {:number :url} :token-in :token-out :token-total :token-rate
 :cost-usd :ctx-used :ctx-window :time-spent-ms :session-id :hostname
 :cache-read :cache-write}
```

Built-in segment IDs: `model`, `path`, `git`, `pr`, `token-in`, `token-out`, `token-total`, `token-rate`, `cost`, `context-pct`, `context-total`, `time-spent`, `time`, `session`, `hostname`, `cache-read`, `cache-write`.

Export pure helper **`context-usage-level(ctx-used, ctx-window)`** → `:ok | :warning | :purple | :error`. Thresholds ported from oh-my-pi `context-thresholds.ts`:
- `:error` when pct ≥ 90% OR tokens ≥ 500,000
- `:purple` when pct ≥ 70% OR tokens ≥ 270,000
- `:warning` when pct ≥ 50% OR tokens ≥ 150,000
- `:ok` otherwise

(When window is known, the effective percent trigger for each level is `min(percentThreshold, tokenThreshold / window × 100)`. When window is zero or nil, only the percent threshold applies.)

Also export: `register-segment`, `unregister-segment`, `get-segment`, `segment-registry`, `builtin-segments` (vector).

**`src/agent/ui/status_line_presets.cljs`** — Pure data. Presets: `default`, `minimal`, `compact`, `full`, `nerd`, `ascii`, `custom`. Each has `:left-segments`, `:right-segments`, `:separator`, `:segment-options`. Export `get-preset(name)` (falls back to `"default"`).

Default preset left segments: `["model" "path" "git" "pr" "context-pct" "token-total" "cost"]`, right segments: `[]`, separator: `"powerline-thin"`.

**`src/agent/ui/status_line_separators.cljs`** — Pure data. Map from style keyword to `{:left :right}` glyph pairs. Styles: `:powerline`, `:powerline-thin`, `:slash`, `:ascii`, `:none`. Export `get-separator(style)`.

**`src/agent/ui/status_line.cljs`**
(Use `{:squint/extension "jsx"}`, require as `"./status_line.jsx"`)

Ink component `StatusLine`. Props: `agent`, `theme`, `settings`.

Uses `useStdout` for `columns`. Uses `useState(0)` for `tick` with a `useEffect` that runs `setInterval(fn [] (set-tick inc), 1000)`. Uses `useState` atoms for `git-branch`, `git-status`, `pr-info` (all updated asynchronously in effects).

Reads `(get-in settings [:status-line])` to get preset name and overrides. Resolves via `get-preset`. Builds segment context map from `agent` state + async cached values.

**Rendering strategy:** Build the full line as a single string using `pad-right` from SI-1. This avoids Ink flexbox limitations for fixed-width layouts. Left segment contents are concatenated with separator glyphs between them. Right segment contents are concatenated. The center gap is filled with spaces to total `(- columns 2)` visual columns. The whole line is rendered as a single `Text` node. (Colors for individual segments must be embedded as ANSI escape codes in the segment content strings, not as Ink `color` props.)

**Overflow handling:** Pure function `fit-segments(segments, max-width, context)` — calls each segment's render fn, measures content width via `string-width`, accumulates. When total exceeds `max-width`, drops from the right end of the left group first, then from the left end if still over. Returns the trimmed segment list.

**Git async effects:**
- Branch: subscribes to `"git_branch_changed"` event (emitted by core when `.git/HEAD` changes). Also reads branch once on mount via `(exec ["git" "rev-parse" "--abbrev-ref" "HEAD"])`.
- Git status: polled every 5s via `setInterval`, calls `(exec ["git" "status" "--porcelain"])`, parses output.
- PR info: fetched 2s after `git-branch` changes (debounce via `setTimeout` ref), calls `(exec ["gh" "pr" "view" "--json" "number,url"])`. On error, sets `pr-info` to nil and hides the segment.

Export `StatusLine`.

#### Files to Modify

**`src/agent/settings/manager.cljs`** — Add to `defaults`:
```clojure
:status-line {:preset "default" :left-segments nil :right-segments nil
              :separator nil :segment-options {}}
```

**`src/agent/ui/app.cljs`** — Replace `Header` import and usage with `StatusLine`. Pass `agent`, `theme`, and resolved settings.

**`src/agent/core.cljs`** — Start the `.git/HEAD` file watcher at agent creation. On HEAD change (100ms debounced), emit `"git_branch_changed"` via the event bus.

**`src/agent/extensions.cljs`** — Add `registerStatusSegment(id, render-fn)` and `unregisterStatusSegment(id)` to the API. Capability `:ui`.

#### Test Plan

**File:** `test/status_line.test.cljs`

- `get-preset "default"` returns non-empty `:left-segments`
- `get-preset "unknown"` falls back to `"default"`
- `get-separator :powerline` returns non-empty glyph strings
- `context-usage-level` for 0 used → `:ok`
- `context-usage-level` at 51%, large window → `:warning`
- `context-usage-level` at 71% → `:purple`
- `context-usage-level` at 91% → `:error`
- `context-usage-level` 150,001 tokens with window 1,000,000 → `:warning`
- `context-usage-level` 500,001 tokens → `:error`
- `register-segment` adds to registry atom; `get-segment` retrieves it
- `get-segment` returns nil for unknown id
- `builtin-segments` vector length equals 18

---

### Feature #2: Welcome Screen

**Depends on:** SI-2, SI-5, `sessions/listing.cljs` (already exists).

#### Files to Create

**`src/agent/ui/welcome.cljs`**
(Use `{:squint/extension "jsx"}`, require as `"./welcome.jsx"`)

Ink component `WelcomeScreen`. Props: `agent`, `theme`, `sessions-dir`.

Export pure function **`compute-layout(term-width)`** → `{:dual-column? bool :left-col int :right-col int}`. Dual-column when `term-width >= 70`. Left col is `min(26, floor(width × 0.35))` when dual; `width - 2` when single.

Uses `useStdout` for `columns`. Uses `useState` for `recent-sessions` populated in `useEffect` via `(list-sessions sessions-dir)`.

Layout when dual-column: two adjacent `Box` elements.
- Left: nyma ASCII logo (5 lines, ≤20 chars wide), bold "Welcome to nyma", current model name (from `agent`), provider name.
- Right: Tips panel (static list of `? # / ! $` key descriptions), extension count from `(:extension-loader agent)` if available, up to 3 recent sessions formatted as `"name  (Xh ago)"` using `relative-time`.

Export `WelcomeScreen`.

#### Files to Modify

**`src/agent/ui/app.cljs`** — In the render section, wrap `ChatView` in a conditional: `(if (and (empty? messages) (not streaming)) WelcomeScreen ChatView)`. Pass `sessions-dir` from `resources`.

**`src/agent/modes/interactive.cljs`** — Compute `sessions-dir` and add to `resources` map.

#### Test Plan

**File:** `test/welcome_screen.test.cljs`

- `compute-layout(80)` → `{:dual-column? true ...}`
- `compute-layout(60)` → `{:dual-column? false ...}`
- `compute-layout(0)` → does not throw
- `relative-time` tests (or handled in SI-5 test file): each bucket

---

### Feature #3: Word-Level Diff Renderer

**npm dependency:** Add `"diff": "^7.0.0"` to `package.json` and run `bun install`.

#### Files to Create

**`src/agent/ui/diff_renderer.cljs`** — Pure logic. Exports:

- **`visualize-indent(text)`** → string. Leading spaces → `"\x1b[2m·\x1b[22m"`. Leading tabs → `"\x1b[2m →\x1b[22m"`. Content after first non-whitespace passed through with tabs expanded to 4 spaces.

- **`render-intra-line-diff(old-content, new-content)`** → `{:removed-line :added-line}`. Calls `diffWords` from `diff` npm package. Wraps changed spans in ANSI inverse: `"\x1b[7m...\x1b[27m"`. Strips leading whitespace from first changed span in each line.

- **`parse-diff-line(line)`** → `{:prefix :line-num :content}` or nil. Recognizes `"+123|content"`, `"-123|content"`, `" 123|content"` formats.

- **`render-diff-block(diff-text)`** → seq of `{:type :line-num :content}`. Groups adjacent single-removed + single-added pairs and applies `render-intra-line-diff` on them.

**`src/agent/ui/diff_view.cljs`**
(Use `{:squint/extension "jsx"}`, require as `"./diff_view.jsx"`)

Ink component `DiffView`. Props: `diff-text`, `theme`. Calls `render-diff-block` in `useMemo`. Colors: added=`:success`, removed=`:error`, context=`:muted`. Each line is a `Text` element. Export `DiffView`.

#### Test Plan

**File:** `test/diff_renderer.test.cljs`

- `visualize-indent` with leading spaces: result contains `"·"` chars
- `visualize-indent` with leading tab: result contains `"→"`
- `visualize-indent` with no indent: unchanged
- `parse-diff-line` canonical `"+123|content"` → correct map
- `parse-diff-line` context `" 123|content"` → `:prefix " "`
- `parse-diff-line` non-diff line → nil
- `render-intra-line-diff` changed word wrapped in ANSI inverse
- `render-intra-line-diff` leading whitespace not highlighted
- `render-diff-block` 1:1 adjacent pair gets intra-line diff
- `render-diff-block` N:M pairs (N≠1 or M≠1) are not intra-line diffed

---

### Feature #4: Read-Tool Grouping Extensions

**Status:** Core grouping already in `tool_status.cljs`. Only two additions needed.

#### Files to Modify

**`src/agent/ui/tool_status.cljs`**

1. `tool-group-label` — after extracting the primary label, check `(get args :corrected-path)`. If present, append `" (was: original-value)"`.

2. `ToolGroupStatus` component — change each tree row to include a status icon. Items where `(seq (:result item))` is truthy get `"✓"`. Items where result is nil or empty get `"✖"`. Row format: `"├─ ✓ path"` or `"└─ ✖ path (was: corrected)"`.

#### Test Plan

**Extend:** `test/grouped_tool_display.test.cljs`

- `tool-group-label "read" {:path "b.cljs" :corrected-path "a.cljs"}` includes `"(was: a.cljs)"`
- Items with non-empty result render `"✓"` icon
- Items with nil result render `"✖"` icon

---

### Feature #9: Per-Tool Renderer Registry

**Depends on:** SI-3, #3, #8. Build after both #3 and #8 are done.

#### Files to Create

**`src/agent/ui/tool_renderer_registry.cljs`** — Pure logic. Registry atom: `(atom {})`. Each entry: `{:render-call fn :render-result fn :merge-call-and-result? bool :inline? bool}`. Exports: `register-renderer`, `unregister-renderer`, `get-renderer`, `fallback-renderer`.

**`src/agent/ui/json_tree.cljs`**
(Use `{:squint/extension "jsx"}`, require as `"./json_tree.jsx"`)

Ink component `JsonTree`. Props: `data`, `depth`, `max-depth` (default 4). Renders JS objects recursively with color-coded types. At `max-depth`, renders `"[...]"` in muted. Export `JsonTree`.

**`src/agent/ui/tool_execution.cljs`**
(Use `{:squint/extension "jsx"}`, require as `"./tool_execution.jsx"`)

Ink component `ToolExecution`. Props: `tool-name`, `args`, `result`, `duration`, `verbosity`, `theme`, `is-partial`, `expanded`, plus custom-* fields. Uses `useState(false)` for `expanded`. Uses `useInput` for `ctrl+o` → toggles `expanded`.

Delegates to `(get-renderer tool-name)` or `fallback-renderer`. When renderer has `:merge-call-and-result?` true, calls `:render-call` with all props. When false, calls `:render-call` for partial state and `:render-result` for complete state. When `:inline?` true, skips `CollapsibleBlock` wrapper. Otherwise wraps in `CollapsibleBlock` (SI-3) with one-line header from existing `format-one-line-args`/`format-one-line-result`. Export `ToolExecution`.

**`src/agent/ui/renderers/` directory:**

- `read_renderer.cljs` — `:render-call` shows path + range. `:render-result` shows line count. `:inline? true`.
- `edit_renderer.cljs` — `:render-call` shows path + summary. `:render-result` uses `DiffView` (#3).
- `write_renderer.cljs` — `:render-call` shows path. `:render-result` shows byte count. `:inline? true`.
- `bash_renderer.cljs` — `:render-call` and `:render-result` unified via `BashExecution` (#8). `:merge-call-and-result? true`.
- `grep_renderer.cljs` — `:render-result` shows match count + file list.
- `todo_write_renderer.cljs` — `:render-result` formats todos as checklist.

Each file self-registers by calling `(register-renderer "tool-name" {...})` at module top level.

#### Files to Modify

**`src/agent/ui/chat_view.cljs`** — In `MessageBubble`, replace `"tool-start"` and `"tool-end"` dispatch with `ToolExecution`. Keep `"tool-group"` dispatching to `ToolGroupStatus`. `ToolExecution` receives `is-partial true` for `tool-start` messages and `is-partial false` for `tool-end`.

**`src/agent/extensions.cljs`** — Add `registerToolRenderer(tool-name, renderer-def)` and `unregisterToolRenderer(tool-name)`. Capability `:ui`.

**`src/agent/modes/interactive.cljs`** — Require all renderer files to trigger their self-registration side effects.

#### Test Plan

**File:** `test/tool_renderer_registry.test.cljs`

- `register-renderer` adds entry to atom
- `get-renderer` returns nil for unknown name
- `get-renderer` returns registered entry
- `unregister-renderer` removes entry
- Second registration with same name overwrites first
- `fallback-renderer` has `:render-call` defined

---

### Feature #8: Bordered Streaming Bash/Python Execution

**Depends on:** SI-3, SI-4.

#### Files to Create

**`src/agent/ui/visual_truncate.cljs`** — Pure logic. Exports:
- `count-visual-rows(line-str, term-width)` → int. `ceil(string-width(line) / term-width)`, minimum 1.
- `truncate-to-visual-lines(lines-vec, max-rows, term-width)` → `{:visible-lines vec :hidden-count int}`. Accumulates visual row count, stops at `max-rows`.

**`src/agent/ui/bash_execution.cljs`**
(Use `{:squint/extension "jsx"}`, require as `"./bash_execution.jsx"`)

Ink component `BashExecution`. Props: `command`, `is-python`, `lines` (vec), `status` (`:running :complete :cancelled :error`), `exit-code`, `expanded`, `onToggle`, `is-active`, `theme`.

Module-level constants: `PREVIEW-LINES 20`, `STORED-LINES-CAP 100`.

When `expanded` is false: calls `truncate-to-visual-lines(lines, PREVIEW-LINES, columns)`. When true: shows all stored lines.

Layout:
1. `DynamicBorder` in theme border color
2. Header: `"$ command"` bold (or `">>> "` if `is-python`)
3. If `status = :running`: `Spinner` + muted `"esc to cancel"`
4. Display lines as `Text` elements
5. If `expanded` and `truncated-count > 0`: dim notice `"[... N more lines in tool result]"`
6. Status row: `"(exit 0)"` success, `"(exit N)"` error, `"(cancelled)"` warning
7. `DynamicBorder` again

Uses `useInput` when `is-active` true: `ctrl+o` → `onToggle`.

**Integration:** The bash tool attaches `verbosity: "streaming"` via `.display` metadata. The middleware propagates `custom-verbosity: "streaming"` on `tool_execution_start`. In `app.cljs`'s `on-start` handler, when `custom-verbosity` is `"streaming"`, store `{:bash-lines []}` in the message. In `on-update`, append new lines to `bash-lines` with 50ms debounce (use a `useRef`-stored timer id). The `BashExecution` component is the bash renderer's `render-call`. Export `BashExecution`.

#### Test Plan

**File:** `test/bash_execution.test.cljs`

- `count-visual-rows` for line shorter than width → 1
- `count-visual-rows` for line exactly 2× width → 2
- `count-visual-rows` for empty string → 1
- `truncate-to-visual-lines` 5 short lines with cap 10 → 5 visible, hidden=0
- `truncate-to-visual-lines` 30 short lines with cap 20 → 20 visible, hidden=10
- `truncate-to-visual-lines` 3 long (2× width) lines with cap 4 rows → 2 visible, hidden=1

---

### Feature #7: Bracketed-Paste Large-Paste Markers

**Dependencies:** None.

#### File to Create

**`src/agent/ui/bracketed_paste.cljs`** — Pure logic. Exports:

- `enable-bracketed-paste()` — writes `"\x1b[?2004h"` to `js/process.stdout` via `.write`.
- `disable-bracketed-paste()` — writes `"\x1b[?2004l"`.
- `create-paste-handler()` → `{:pastes (atom {}), :process fn}`. The `process` fn takes a data string → `{:handled bool :marker string|nil :remaining string}`. State machine with internal `:active` bool and `:buffer` string. On `"\x1b[200~"`: set active, clear buffer. When active: append to buffer. On `"\x1b[201~"`: store content in `:pastes` under incrementing ID, return marker `"[paste #N +X lines]"` and remaining. When not active and no start marker: return `{:handled false}`.
- `expand-paste-markers(text, pastes-map)` → string. Replaces all `[paste #N +X lines]` patterns with content from map. Unknown IDs are left unchanged.

#### Files to Modify

**`src/agent/ui/editor.cljs`** — Add `useRef` for paste-handler. In `useEffect` (empty deps): call `enable-bracketed-paste`, return `disable-bracketed-paste` as cleanup.

**`src/agent/ui/app.cljs`** — In the `ui.onTerminalInput` handler wiring, also call `paste-handler.process(data)`. If handled, insert the marker at the current cursor position via `setEditorValue`. In `handle-submit`: call `expand-paste-markers` on the text before passing to `do-submit`.

#### Test Plan

**File:** `test/bracketed_paste.test.cljs`

- `process` with plain text → `{:handled false}`
- `process` with complete paste sequence → marker returned, `:pastes` has one entry
- `process` with split sequence (start in call 1, end in call 2) → buffers, marker on call 2
- Marker format: `"[paste #1 +3 lines]"` for 3-line paste
- `expand-paste-markers` replaces marker with content
- `expand-paste-markers` leaves text without markers unchanged
- `expand-paste-markers` handles multiple markers in one string
- Second paste gets `#2`

---

### Feature #10: Combined Autocomplete Provider

**Depends on:** SI-1. Fuzzy scorer is fully independent.

#### Files to Create

**`src/agent/ui/fuzzy_scorer.cljs`** — Pure logic. Port of oh-my-pi's `fuzzy.ts`. Exports:

- `fuzzy-match(query, text)` → `{:matches bool :score number}`. Subsequence matching. Score is penalty-based (lower = better): consecutive char bonus, gap penalty, word-boundary bonus. Includes alpha-numeric swap tolerance.
- `fuzzy-filter(items, query, get-text-fn)` → sorted items. Multi-token support (split on whitespace, all tokens must match). Empty query returns all items unmodified.

**`src/agent/ui/autocomplete_provider.cljs`** — Pure logic. Exports:

- `create-provider-registry()` → `{:providers (atom {}), :register fn, :unregister fn, :complete-all fn}`.
- Provider shape: `{:id :trigger :priority :complete}`. Triggers: `"slash"`, `"at"`, `"path"`, `"any"`. `:complete(text, cursor-pos)` → `Promise<[{:label :value :description :replace-range}]>`.
- `complete-all(registry, text, cursor-pos)` → `Promise<[items]>`. Matches providers by trigger → runs with `Promise.all` → merges → extracts query → `fuzzy-filter` → returns sorted list.
- `readdir-cached(path)` → `Promise<[string]>`. Cache atom: `{path → {:entries :expires-at}}`. TTL 2000ms.

**Autocomplete overlay:** Reuse `create-picker` from `src/agent/ui/mention_picker.cljs` directly, passing merged+sorted results from `complete-all`. No new overlay component needed.

#### Files to Modify

**`src/agent/ui/app.cljs`** — Replace the current `useEffect` that watches `editor-value` for `@` ending with a general autocomplete effect:
1. Guard: `(and (not overlay) (not streaming) (not editor-hidden))`
2. Detect trigger: `/` prefix → slash, `@` suffix → at, `./` or ends-with `/` → path
3. 100ms debounce via `useRef`-stored `setTimeout` id
4. Call `complete-all(registry, editor-value, cursor-pos)`
5. On non-empty results: `set-overlay(create-picker(results, query, on-resolve))`
6. On-resolve inserts selected `:value` into editor and closes overlay

**`src/agent/extensions.cljs`** — Add `registerCompletionProvider(config)` and `unregisterCompletionProvider(id)`. Capability `:ui`.

**`src/agent/modes/interactive.cljs`** — Register three built-in providers at startup: slash (from `(:commands agent)`), at-file (from existing mention-files search), and path-prefix (from `readdir-cached`).

#### Test Plan

**File:** `test/fuzzy_scorer.test.cljs`

- `fuzzy-match "abc" "abc"` → matches true, score ~0
- `fuzzy-match "abc" "aXbXc"` → matches true, score > 0
- `fuzzy-match "" "anything"` → matches true, score 0
- `fuzzy-match "abcd" "abc"` → matches false
- `fuzzy-filter` sorts best-first
- `fuzzy-filter` multi-token: all tokens must match
- `fuzzy-filter` empty query returns all items

**File:** `test/autocomplete_provider.test.cljs`

- `complete-all` with `/` text calls only slash provider
- `complete-all` merges results from multiple applicable providers
- `complete-all` returns sorted results
- `readdir-cached` second call within TTL returns cached (mock `fs.readdir`)
- `readdir-cached` after TTL re-fetches

---

## 4. Dependency Graph and Build Order

```
SI-1 (ansi extend)   SI-2 (theme)   SI-5 (time util)
      |                    |               |
      |        ┌───────────┘               |
      |        |                           |
SI-3 (collapsible block)  SI-4 (dyn border)
      |                         |
      └──────────────┬──────────┘
                     |
           #8 (bash execution)      #3 (diff) ─── needs only npm dep
                     |                   |
                     └────────┬──────────┘
                              |
          #5 (keybinding registry)    #9 (renderer registry + ToolExecution + renderers)
                     |                         |
          #6 (help overlay)                    |
                     |    #2 (welcome) ── SI-2, SI-5
                     |
          #1 (status line) ── #5, #6, SI-1, SI-2
                     |
          #10 (autocomplete) ── SI-1; fuzzy scorer independent; integration after #1

#7 (bracketed paste) ── no dependencies, build anytime
#4 extensions ── no dependencies, tiny addition
```

**Strict sequence:**
1. SI-1, SI-2, SI-5 (parallel)
2. SI-3, SI-4 (parallel)
3. #5, #7, #3 (parallel — all independent)
4. #4 extensions (tiny, anytime after step 1)
5. #6 (after #5)
6. #2 (after SI-2, SI-5)
7. #8 (after SI-3, SI-4)
8. #9 (after #3 and #8)
9. #1 (after #5, #6, SI-1, SI-2)
10. #10 fuzzy scorer (anytime step 3+), provider registry + integration (after #1)

---

## 5. Milestones

### Milestone 1: Foundations + Quick Wins

**Features:** SI-1, SI-2, SI-5, #5, #6, #4 extensions, #7

After this milestone: `?` opens a keybinding help overlay. Footer shows dynamic hints that stay in sync with user keybinding overrides. Large paste content shows as `[paste #1 +N lines]` in the editor and expands on submit. Read-group tree shows `✓`/`✖` per item with corrected-path annotations. Nyma is shippable — no regressions.

### Milestone 2: Rich Execution Display

**Features:** SI-3, SI-4, #3, #8

After this milestone: bash execution renders with dynamic bordered live-scrolling output, `esc` to cancel, `ctrl+o` to expand. Edit tool results show word-level diffs with intra-line inverse highlighting.

### Milestone 3: Tool Renderer Registry + Autocomplete

**Features:** #9, #10

After this milestone: all tool calls flow through the renderer registry. Extensions can register custom renderers. Slash, `@file`, and path prefix completions work from the editor. Extensions can register completion providers.

### Milestone 4: Status Line + Welcome Screen

**Features:** #1, #2

After this milestone: the header is a full powerline-style status line with 18 segment types, presets (default/minimal/compact/full/nerd/ascii), extension-contributed segments, and user-configurable via settings. New sessions see a two-column welcome screen with recent sessions, tips, and model info.

---

## 6. Core vs. Extension Classification

Nyma's convention (from AGENTS.md and the existing `src/agent/extensions/` layout): **core** is anything required for a usable default experience, or anything that other core code depends on. **Extensions** are opt-in, capability-gated, and loaded via `extension-loader`. Extensions can contribute segments/renderers/completions via the registries in core — the registries themselves belong in core.

The decisive questions for each feature:
1. Does disabling it break the default UI? → core
2. Is it required by another core feature? → core
3. Is it self-contained and opt-in? → extension candidate
4. Does it live in the input/render hot path? → core (extensions can't safely intercept Ink input mid-render)

### Classification

| # | Feature | Placement | Reasoning |
|---|---|---|---|
| SI-1 | ANSI width helpers | **Core** (`src/agent/utils/ansi.cljs`) | Already core; pure util; needed by #1 and tests. |
| SI-2 | Theme palette | **Core** (`src/agent/ui/themes.cljs`) | Already core; tiny data-only addition. |
| SI-3 | CollapsibleBlock | **Core** (`src/agent/ui/`) | Reused by #9 renderers. Generic primitive. |
| SI-4 | DynamicBorder | **Core** (`src/agent/ui/`) | Reused by #8 and any custom renderer. Generic primitive. |
| SI-5 | Time util | **Core** (`src/agent/utils/time.cljs`) | Currently in `prompt_history` extension; promoting to core for reuse by welcome screen. |
| **#1** | Status line | **Split** — core runtime + registry, **core preset data**, extension-contributed segments | The `StatusLine` component, segment registry atom, preset resolver, separator table, and 18 built-in segments are **core** — they replace the Header and must render on first tick. The `registerStatusSegment` API (exposed on the extension surface in `extensions.cljs`) lets any extension contribute extra segments. The existing ACP `setFooter` segment should migrate from `agent_shell` extension to use the new registry. |
| **#2** | Welcome screen | **Core** (`src/agent/ui/welcome.cljs`) | Shown by default on empty session; no opt-in useful. Tips panel content is static — no extension hooks needed yet. |
| **#3** | Diff renderer | **Core** (`src/agent/ui/diff_renderer.cljs` + `diff_view.cljs`) | Used by the core `edit` tool renderer (#9). Must be available whenever edit tools exist. |
| **#4** | Read-tool grouping tweaks | **Core** (extends existing `tool_status.cljs`) | Already core; just tree-icon and corrected-path rendering tweaks. |
| **#5** | Keybinding registry | **Core** (`src/agent/keybinding_registry.cljs`) | Input-layer; hot path; extensions call into it via the existing `registerShortcut` API. The action-ID concept itself is a core primitive. |
| **#6** | Help overlay + hint helpers | **Core** (`src/agent/ui/key_hints.cljs` + `help_overlay.cljs`) | Footer (core) depends on `hint-row`. `?`-in-empty-editor is a core default binding. Help overlay lists both core *and* extension shortcuts. |
| **#7** | Bracketed-paste markers | **Core** (`src/agent/ui/bracketed_paste.cljs`) | Touches the editor's stdin path and the submit path — must be core. Not useful as opt-in. |
| **#8** | Bash execution component | **Core** (`src/agent/ui/bash_execution.cljs`) — but wired via the bash-suite extension | Component lives in core so any extension's streaming tool can reuse it. The `bash_renderer.cljs` that registers it against the `"bash"` tool name can live alongside the other core renderers since bash is a built-in tool. However, if/when the existing `bash_suite` extension defines its own streaming tools, those should register their own renderers using the registry. |
| **#9** | Tool renderer registry | **Split** — registry + fallback + built-in renderers in **core**; `registerToolRenderer` API for extensions | The registry atom, `ToolExecution` component, `JsonTree` fallback, and renderers for the six built-in tools (`read`, `edit`, `write`, `bash`, `grep`, `todo_write`) are **core**. Extensions register additional renderers via `extension.registerToolRenderer(...)`. This mirrors how `registerBlockRenderer` / `registerMessageRenderer` already work. |
| **#10** | Autocomplete provider | **Split** — registry + fuzzy scorer + 3 built-in providers in **core**; `registerCompletionProvider` API for extensions | The `fuzzy_scorer.cljs`, the provider registry, and the three built-in providers (slash, `@file`, path-prefix) are core. Extensions register extra providers. Nyma already has `registerMentionProvider` for a narrower use case — this should be generalized or sit alongside. |

### Why nothing here becomes a standalone extension

I considered packaging #2 (welcome), #6 (help overlay), or #9 (tool renderer registry) as extensions — `stats_dashboard` and `prompt_history` are precedents for UI-heavy extensions. Each was rejected:

- **#2** — extensions can't intercept the empty-state render. Would need a core hook anyway.
- **#6** — footer hints are rendered by core; making hint helpers an extension creates a circular dep.
- **#9** — #3, #4, #8 all depend on dispatching through the registry. A core registry is non-negotiable; a core-plus-registrar split is cleaner than a core-plus-extension split.

### Extensions that *should* grow from this plan

Once the core APIs are in place, these features unlock future extensions:
- **Nerd-font preset extension** — registers a `"nerd"` preset with glyph-heavy segments.
- **Cloud cost extension** — registers a `cost` segment that pulls from a remote billing API.
- **Kubernetes context segment** — registers a `k8s-context` segment showing current kubecontext.
- **LSP tool renderer extension** — registers renderers for `lsp_hover`, `lsp_definition`, `lsp_rename` once the `ast_tools` extension ships LSP tools.
- **Custom completion providers** — registers providers for JIRA ticket references, npm package names, etc.

These are not in the plan — they're the payoff once the plan ships.

### Migration note: existing ACP status line

The `agent_shell` extension currently contributes a status line via `setFooter`. After #1 ships:
1. Remove `setFooter` calls from `agent_shell/features/*`.
2. Add a new file `src/agent/extensions/agent_shell/features/status_segments.cljs` that registers the ACP-specific segments (`plan_mode`, `session_name`, etc.) via `registerStatusSegment`.
3. Delete the `setFooter` API from `extensions.cljs` entirely once no callers remain.

This is a clean regression-free migration because the new registry supersedes `setFooter` semantically.

---

## 7. Risks and Open Questions

**R1: Status line rendering strategy — string vs. multi-Box. ✅ RESOLVED 2026-04-10.**
Use Ink flexbox. The `footer.cljs:11-17` pattern already does "left group + flex-grow middle + right group" with `<Box flexGrow 1 flexShrink 1 overflow="hidden">` as the center spacer. The entire nyma UI uses this pattern; string-building with ANSI codes would be out of convention. SI-1 is dropped.

**R2: Git file watcher reliability on macOS.**
`fs.watch` on `.git/HEAD` fires multiple events per branch change and may miss some git operations (e.g., `git commit --amend`). A 100ms debounce is required. Reference implementation: `themes.cljs` uses the same `fs.watch` API. Manual testing with real git operations is required before shipping.

**R3: `gh pr view` subprocess availability.**
The PR segment must fail gracefully: catch subprocess errors, set `pr-info` to nil, hide segment (`visible: false`). Non-blocking async design is already specified. No decision needed — just implement the error handling path.

**R4: `diffWords` on code content.**
The `diff` library's word boundaries may not align perfectly with code tokens. This is an acceptable tradeoff (same as oh-my-pi). No action needed; document the limitation in source.

**R5: ToolExecution vs. existing ToolStartStatus/ToolEndStatus.**
`ToolExecution` (#9) unifies the two-component approach. The existing `ToolStartStatus`/`ToolEndStatus` are kept as the fallback renderer's internal implementation (called when no renderer is registered for a tool name). The chat_view dispatch point changes; the rendering logic in `tool_status.cljs` is preserved. This avoids big-bang replacement.

**R6: Bracketed paste and Ink stdin ownership. ✅ RESOLVED 2026-04-10.**
Verified: `src/agent/ui/app.cljs:240-244` shows `onTerminalInput` attaches `process.stdin.on("data", handler)` directly, independent of Ink's internal listener. Both listeners fire on the same event. The paste handler will use `ui.onTerminalInput` and receive raw bytes including `\x1b[200~...\x1b[201~` sequences. Listener registration order determines invocation order — register the paste handler early in `useEffect` so it fires before Ink parses the data.

**R7: Autocomplete timing vs. streaming.**
The autocomplete overlay must not open during LLM streaming. Preserve the guard `(and (not overlay) (not streaming) (not editor-hidden))` in the new trigger effect.

**R8: Status line position. ✅ RESOLVED 2026-04-10.**
Survey of 12 terminal tools (Claude Code, Codex CLI, Gemini CLI, oh-my-pi, Aider, tmux, Zellij, Neovim, Helix, Emacs, Amazon Q, Vim): 10/12 place live status at the bottom. All 4 surveyed AI coding agents with live status do so adjacent to the input caret. Decision: insert new `StatusLine` **between `WidgetContainer` (position "below") and `Editor`** in `app.cljs:432-445`. Keep both `Header` (brand) and `Footer` (keybinding hints + ephemeral status badges from `setStatus`). New vertical stack: Header → ChatView → WidgetContainer(below) → **StatusLine** → Editor → Notification → Footer. Status segments (model/git/pr/ctx/tokens/cost) live in `StatusLine`; ephemeral extension `setStatus` badges stay in `Footer`.

**R9: No Ink component test scaffolding.**
Nyma does not test React/Ink components. All new components have zero automated test coverage at the component level. This is by convention. If component rendering tests become a requirement, `ink-testing-library` would need to be added as a separate workstream.

**R10: `diff` npm package CJS/ESM interop.**
Bun handles CJS/ESM interop transparently for most packages. Test `["diff" :refer [diffWords]]` import early in #3 implementation. If it fails, either pin to `diff@7` (ESM native) or inline a simplified `diffWords` (~30 lines of pure JS logic in ClojureScript).

**R11: Token rate computation for status line.**
The `token-rate` segment needs a sliding-window tokens/second calculation. Implement as pure helper `token-rate-per-sec(events-vec)` where `events-vec` is `[{:ts :delta-tokens}]`. The `StatusLine` component accumulates this in a `useRef` from `tool_execution_end` events. The computation itself is a pure function suitable for unit testing.

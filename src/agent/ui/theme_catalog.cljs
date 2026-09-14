(ns agent.ui.theme-catalog
  "A bundled theme pack: well-known base16 palettes converted to nyma's theme
   schema. base16 (tinted-theming) is the de-facto standard — 16 semantic color
   slots (base00-0F) that map deterministically onto nyma's ~12 slots."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

;; ── base16 → nyma converter ───────────────────────────────────────

(defn base16->theme
  "Convert a base16 palette (map of \"base00\"..\"base0F\" → #hex) into a nyma
   theme map. Slot mapping follows the base16 spec: 08=red, 0A=yellow, 0B=green,
   0D=blue, 0E=purple, 02=selection/border, 03=comments/muted."
  [p]
  (let [c (fn [k] (get p k))]
    {:colors {:primary   (c "base0D")   ; blue
              :secondary (c "base0B")   ; green
              :error     (c "base08")   ; red
              :warning   (c "base0A")   ; yellow
              :success   (c "base0B")
              :muted     (c "base03")   ; comments
              :border    (c "base02")   ; selection bg
              :info      (c "base0C")   ; cyan
              :plan      (c "base0C")
              :editor-border {:off    (c "base02")
                              :low    (c "base0B")
                              :medium (c "base0A")
                              :high   (c "base08")}
              :context-ok      (c "base0B")
              :context-warning (c "base0A")
              :context-purple  (c "base0E")
              :context-error   (c "base08")}
     :icons  {:user "❯" :assistant "●" :tool "⚙" :error "✗"}}))

;; ── Curated base16 palettes ───────────────────────────────────────

(def ^:private palettes
  {"gruvbox-dark"
   {"base00" "#282828" "base01" "#3c3836" "base02" "#504945" "base03" "#665c54"
    "base04" "#bdae93" "base05" "#d5c4a1" "base06" "#ebdbb2" "base07" "#fbf1c7"
    "base08" "#fb4934" "base09" "#fe8019" "base0A" "#fabd2f" "base0B" "#b8bb26"
    "base0C" "#8ec07c" "base0D" "#83a598" "base0E" "#d3869b" "base0F" "#d65d0e"}
   "nord"
   {"base00" "#2e3440" "base01" "#3b4252" "base02" "#434c5e" "base03" "#4c566a"
    "base04" "#d8dee9" "base05" "#e5e9f0" "base06" "#eceff4" "base07" "#8fbcbb"
    "base08" "#bf616a" "base09" "#d08770" "base0A" "#ebcb8b" "base0B" "#a3be8c"
    "base0C" "#88c0d0" "base0D" "#81a1c1" "base0E" "#b48ead" "base0F" "#5e81ac"}
   "dracula"
   {"base00" "#282a36" "base01" "#363447" "base02" "#44475a" "base03" "#6272a4"
    "base04" "#62d6e8" "base05" "#f8f8f2" "base06" "#f0f1f4" "base07" "#ffffff"
    "base08" "#ff5555" "base09" "#ffb86c" "base0A" "#f1fa8c" "base0B" "#50fa7b"
    "base0C" "#8be9fd" "base0D" "#bd93f9" "base0E" "#ff79c6" "base0F" "#ff5555"}
   "tokyo-night"
   {"base00" "#1a1b26" "base01" "#16161e" "base02" "#2f3549" "base03" "#444b6a"
    "base04" "#787c99" "base05" "#a9b1d6" "base06" "#cbccd1" "base07" "#d5d6db"
    "base08" "#f7768e" "base09" "#ff9e64" "base0A" "#e0af68" "base0B" "#9ece6a"
    "base0C" "#2ac3de" "base0D" "#7aa2f7" "base0E" "#bb9af7" "base0F" "#cfc9c2"}
   "solarized-dark"
   {"base00" "#002b36" "base01" "#073642" "base02" "#586e75" "base03" "#657b83"
    "base04" "#839496" "base05" "#93a1a1" "base06" "#eee8d5" "base07" "#fdf6e3"
    "base08" "#dc322f" "base09" "#cb4b16" "base0A" "#b58900" "base0B" "#859900"
    "base0C" "#2aa198" "base0D" "#268bd2" "base0E" "#6c71c4" "base0F" "#d33682"}
   "catppuccin-mocha"
   {"base00" "#1e1e2e" "base01" "#181825" "base02" "#313244" "base03" "#45475a"
    "base04" "#585b70" "base05" "#cdd6f4" "base06" "#f5e0dc" "base07" "#b4befe"
    "base08" "#f38ba8" "base09" "#fab387" "base0A" "#f9e2af" "base0B" "#a6e3a1"
    "base0C" "#94e2d5" "base0D" "#89b4fa" "base0E" "#cba6f7" "base0F" "#f2cdcd"}
   "one-dark"
   {"base00" "#282c34" "base01" "#353b45" "base02" "#3e4451" "base03" "#545862"
    "base04" "#565c64" "base05" "#abb2bf" "base06" "#b6bdca" "base07" "#c8ccd4"
    "base08" "#e06c75" "base09" "#d19a66" "base0A" "#e5c07b" "base0B" "#98c379"
    "base0C" "#56b6c2" "base0D" "#61afef" "base0E" "#c678dd" "base0F" "#be5046"}
   "rose-pine"
   {"base00" "#191724" "base01" "#1f1d2e" "base02" "#26233a" "base03" "#6e6a86"
    "base04" "#908caa" "base05" "#e0def4" "base06" "#e0def4" "base07" "#524f67"
    "base08" "#eb6f92" "base09" "#f6c177" "base0A" "#ebbcba" "base0B" "#31748f"
    "base0C" "#9ccfd8" "base0D" "#c4a7e7" "base0E" "#f6c177" "base0F" "#524f67"}
   "everforest-dark"
   {"base00" "#2d353b" "base01" "#343f44" "base02" "#3d484d" "base03" "#859289"
    "base04" "#9da9a0" "base05" "#d3c6aa" "base06" "#e4e1cd" "base07" "#fdf6e3"
    "base08" "#e67e80" "base09" "#e69875" "base0A" "#dbbc7f" "base0B" "#a7c080"
    "base0C" "#83c092" "base0D" "#7fbbb3" "base0E" "#d699b6" "base0F" "#f85552"}
   "carbonfox"
   {"base00" "#161616" "base01" "#252525" "base02" "#353535" "base03" "#484848"
    "base04" "#7b7c7e" "base05" "#f2f4f8" "base06" "#b6b8bb" "base07" "#ffffff"
    "base08" "#ee5396" "base09" "#f6733d" "base0A" "#08bdba" "base0B" "#25be6a"
    "base0C" "#33b1ff" "base0D" "#78a9ff" "base0E" "#be95ff" "base0F" "#f6733d"}})

(def catalog
  "name → nyma theme map, converted from base16."
  (into {} (map (fn [[name p]] [name (base16->theme p)]) palettes)))

(defn theme-names
  "Bundled theme names."
  []
  (sort (keys catalog)))

(defn all-theme-names
  "Bundled + user theme names. `user-themes` is resources' discovered :themes."
  [user-themes]
  (sort (distinct (concat (keys catalog) (keys (or user-themes {}))))))

(defn pick-theme
  "Select a theme map by name from the bundled catalog then user themes;
   `default` fallback when name is missing/unknown. Uses separate lookups (no
   merge) so it's robust whether user-themes is a cljs map or a JS object."
  [name user-themes default]
  (or (when name (get catalog name))
      (when (and name user-themes) (get user-themes name))
      default))

(defn- settings-theme-name []
  (try
    (-> (fs/readFileSync (path/join (js/process.cwd) ".nyma" "settings.json") "utf8")
        js/JSON.parse (aget "theme"))
    (catch :default _ nil)))

(defn active-theme
  "Resolve the active theme at startup: `.nyma/settings.json#theme` name →
   bundled catalog / user themes, else `default`."
  [user-themes default]
  (pick-theme (settings-theme-name) user-themes default))

(defn set-theme-name!
  "Persist the chosen theme name to `.nyma/settings.json#theme`, creating/merging
   the file. Returns the name."
  [name]
  (let [dir (path/join (js/process.cwd) ".nyma")
        f   (path/join dir "settings.json")
        raw (try (js/JSON.parse (fs/readFileSync f "utf8")) (catch :default _ nil))
        ;; Only merge into a real JSON object; null/array/scalar → fresh object.
        cur (if (and (object? raw) (not (.isArray js/Array raw))) raw #js {})]
    (aset cur "theme" name)
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync f (js/JSON.stringify cur nil 2))
    name))

;; ── Live switching ─────────────────────────────────────────────────

(def current
  "The theme in effect. The TUI reads its colours through this (per render)
   rather than from the map it was constructed with, so `/theme` no longer
   needs a restart."
  (atom nil))

(def ^:private on-apply
  "What the TUI does after `current` changes: swap its atom, re-theme the
   pickers, drop the chat pane's line cache, request a frame. Set by
   interactive mode; nil elsewhere (print, rpc, tests)."
  (atom nil))

(defn on-apply!
  "Register the TUI's post-switch hook. One host per process."
  [f]
  (reset! on-apply f))

(defn activate!
  "Make `theme` the current one and run the host hook. Returns the theme."
  [theme]
  (reset! current theme)
  (when-let [f @on-apply] (f theme))
  theme)

(defn apply-theme!
  "Persist `name` to `.nyma/settings.json#theme` and switch to it now.
   Resolution is the same as at startup (bundled catalog, then user themes,
   else `default`)."
  [name user-themes default]
  (set-theme-name! name)
  (activate! (pick-theme name user-themes default)))

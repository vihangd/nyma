(ns test-util.mock-stdout
  "Mock stdout helper for real-Ink-render tests.

   `ink-testing-library` runs Ink in `debug: true` (see ink.js:255) which
   writes `fullStaticOutput + output` atomically every frame. `lastFrame()`
   then contains everything and cannot detect bugs where:
     - Static emits a large batch and scrolls the terminal
     - log-update erases too many lines due to dynamic-region height changes
     - Content is clipped by overflow but still present in the static slice

   This helper creates a stdout that:
     1. Captures every `write()` call into a `writes` buffer.
     2. Reports isTTY=true, rows/columns from caller.
     3. No-ops for event listeners (on/off/once/removeListener/emit).

   Pair it with `ink`'s real `render` (NOT ink-testing-library) to observe
   the true sequence of ANSI bytes Ink would emit to a terminal."
  (:require ["ink" :refer [render]]))

(defn make-mock-stdout
  "Return a plain JS object compatible with ink.render()'s `stdout` option.
   Writes are captured into `:writes` (a JS array). `rows`/`cols` default to
   20×80 — a small viewport makes it easy to trigger overflow behavior."
  [{:keys [rows cols] :or {rows 20 cols 80}}]
  (let [writes #js []]
    #js {:writes writes
         :rows rows
         :columns cols
         :isTTY true
         :write (fn [d]
                  (.push writes d)
                  true)
         :on (fn [& _] nil)
         :off (fn [& _] nil)
         :once (fn [& _] nil)
         :removeListener (fn [& _] nil)
         :emit (fn [& _] nil)}))

(defn mock-render
  "Render a React element via ink's real `render` function with a mock
   stdout attached. Returns `{:app app :stdout mock}` — call `(.unmount app)`
   when done and read captured writes via `(.-writes mock)`."
  [element {:keys [rows cols] :or {rows 20 cols 80}}]
  (let [mock (make-mock-stdout {:rows rows :cols cols})
        app  (render element #js {:stdout mock})]
    {:app app :stdout mock}))

(defn all-writes-string
  "Concatenate the mock stdout's captured writes into a single string
   (convenient for assertions like `(.includes all \"FOO\")`)."
  [mock]
  (.join (.-writes mock) ""))

(defn writes-since
  "Slice of the writes array from index `start` to the end. Useful for
   asserting only on the writes emitted during a specific rerender."
  [mock start]
  (.slice (.-writes mock) start))

(defn writes-length
  "Total number of write calls captured so far."
  [mock]
  (.-length (.-writes mock)))

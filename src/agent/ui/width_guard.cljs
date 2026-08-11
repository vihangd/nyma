(ns agent.ui.width-guard
  "Last line of defence between nyma's components and pi-tui's width check.

   pi-tui compares each rendered line against the terminal width and throws
   from inside its own render timer, having already called stop(), so an
   over-wide line ends the session rather than looking wrong. There is no
   error hook and no strict-width opt-out.

   Worse, that check only exists on the INCREMENTAL path (`tui.js:950` — the
   only occurrence in the file). `fullRender` writes lines unchecked, so an
   over-wide line on first render, on resize, or on a forced render does not
   throw at all: the terminal soft-wraps it, pi-tui's `previousLines` stops
   matching the physical rows, and the cursor arithmetic drifts. A crash
   handler can only catch the loud half. Clamping component output covers
   both, which is why this exists in addition to one.

   nyma has no chokepoint of its own — pi-tui's `Container.render`
   concatenates the base children itself — so the guard is applied to each
   child as it is attached.

   Clamping HERE is state-consistent: pi-tui stores `previousLines = newLines`,
   the same array its width check inspects, so a component returning already
   clamped lines keeps the diff, the cursor math and the emitted bytes in
   agreement. (Clamping inside pi-tui's own diff loop would not — the cache
   would disagree with the screen.) chat-pane has clamped this way for a
   while without disturbing the differential renderer."
  (:require [agent.ui.chat-renderer :refer [clamp-line]]))

(def clamps
  "Diagnostics: how many lines the guard has had to cut, and the last one it
   cut. A guard that silently fixes a real bug forever is how the `fullRender`
   path stayed invisible in the first place — if this count is non-zero, some
   component is still miscomputing its width."
  (atom {:count 0 :last nil}))

(defn- record! [component-name width line]
  (swap! clamps
         (fn [c]
           {:count (inc (:count c))
            :last  {:component component-name :width width :line line}})))

(defn guard-render!
  "Wrap `component`'s `render` IN PLACE so no line it returns can exceed the
   width it was handed. Returns the component.

   In place rather than wrapping in a new object: these components carry
   their own API beyond the pi-tui contract (`setState`, `setMessages`,
   `appendChunk`, …) and pi-tui's own `Editor` is a class instance whose
   `render` lives on the prototype. Replacing the property preserves identity
   and every other method."
  ([component] (guard-render! component nil))
  ([component component-name]
   (when component
     (let [orig (.-render component)]
       (when (fn? orig)
         (set! (.-render component)
               (fn [width]
                 (let [lines (.call orig component width)]
                   (if (js/Array.isArray lines)
                     (.map lines
                           (fn [l]
                             (let [clamped (clamp-line l width)]
                               (when-not (identical? clamped l)
                                 (record! component-name width l))
                               clamped)))
                     lines)))))))
   component))

(defn attach-guarded-children!
  "Attach each of `children` to `tui` with its render guarded.

   Every base child goes through here, so the guard cannot be bypassed by
   adding a fourth component and forgetting to wrap it — which is exactly how
   the status bar ended up as the one unguarded path."
  [tui children]
  (doseq [[nm child] children]
    (guard-render! child nm)
    (.addChild tui child))
  tui)

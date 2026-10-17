(ns agent.utils.html
  "HTML → markdown or text, and quoted history out of an email body.

   `html->text` and `strip-quotes` are regex-only and synchronous, so a
   code-mode script can call them inline. `html->markdown` uses turndown +
   linkedom, loaded on first use: as top-level requires they cost every entry
   point 26ms and 37MB of RSS (linkedom alone +29MB) for a path only web_fetch
   and scripts take."
  (:require [clojure.string :as str]))

(def ^:private html-mods (atom nil))

(defn ^:async load-html-mods!
  "Memoized {:parseHTML f :Turndown ctor}."
  []
  (or @html-mods
      (let [linkedom  (js-await (js/import "linkedom"))
            turndown  (js-await (js/import "turndown"))
            mods      {:parseHTML (.-parseHTML linkedom)
                       :Turndown  (or (.-default turndown) turndown)}]
        (reset! html-mods mods)
        mods)))

(defn html->markdown-sync
  "Markdown via turndown; nil until `load-html-mods!` has resolved."
  [html]
  (when-let [{:keys [parseHTML Turndown]} @html-mods]
    (.turndown (Turndown. #js {:headingStyle "atx" :codeBlockStyle "fenced"})
               (.-document (parseHTML (str html))))))

(defn ^:async html->markdown
  "Convert HTML to Markdown using turndown + linkedom, both loaded on demand."
  [html]
  (js-await (load-html-mods!))
  (html->markdown-sync html))

(defn html->text
  "Strip HTML to plain text. Regex-based, no external deps."
  [html]
  (-> (str html)
      (.replace (js/RegExp. "<script[^>]*>[\\s\\S]*?</script>" "gi") "")
      (.replace (js/RegExp. "<style[^>]*>[\\s\\S]*?</style>" "gi") "")
      (.replace (js/RegExp. "<noscript[^>]*>[\\s\\S]*?</noscript>" "gi") "")
      (.replace (js/RegExp. "<head[^>]*>[\\s\\S]*?</head>" "gi") "")
      (.replace (js/RegExp. "<(br|p|div|li|tr|h[1-6])[^>]*>" "gi") "\n")
      (.replace (js/RegExp. "</t[dh]>" "gi") "\t")
      (.replace (js/RegExp. "<[^>]+>" "g") "")
      (.replace (js/RegExp. "&nbsp;" "g") " ")
      (.replace (js/RegExp. "&lt;" "g") "<")
      (.replace (js/RegExp. "&gt;" "g") ">")
      (.replace (js/RegExp. "&quot;" "g") "\"")
      (.replace (js/RegExp. "&#39;|&apos;" "g") "'")
      (.replace (js/RegExp. "&#(\\d+);" "g")
                (fn [_ code] (js/String.fromCodePoint (js/parseInt code 10))))
      (.replace (js/RegExp. "&#x([0-9a-f]+);" "gi")
                (fn [_ code] (js/String.fromCodePoint (js/parseInt code 16))))
      ;; last, so `&amp;lt;` stays `&lt;`
      (.replace (js/RegExp. "&amp;" "g") "&")
      (.replace (js/RegExp. "[ \\t]+" "g") " ")
      (.replace (js/RegExp. "[ \\t]*\\n[ \\t]*" "g") "\n")
      (.replace (js/RegExp. "\\n{3,}" "g") "\n\n")
      (.trim)))

(def ^:private html-quote-starts
  "Where a client's quoted history begins in its HTML. Quoted history is
   the tail of a reply, so the body is cut at the first of these.
   ponytail: a `<blockquote>` the sender wrote on purpose goes too."
  [#"<blockquote"
   #"<div[^>]+class=\"[^\"]*gmail_quote"            ; Gmail
   #"<div[^>]+id=\"(divRplyFwdMsg|appendonsend)\""  ; Outlook
   #"<hr[^>]+id=\"stopSpelling\""                   ; Outlook.com
   #"<div[^>]+class=\"[^\"]*moz-cite-prefix"         ; Thunderbird
   #"<div[^>]+class=\"[^\"]*yahoo_quoted"])          ; Yahoo

(def ^:private text-quote-starts
  "Lines that start quoted history or a signature in plain text."
  [#"^On .{4,200}wrote:\s*$"
   #"^-{2,}\s*(Original Message|Forwarded message)\s*-{2,}"
   #"^_{10,}\s*$"                                   ; Outlook's rule before From:
   #"^From: .+\n(Sent|Date): "
   #"^-- $"])

(defn- cut-at [s res]
  (let [idx (keep (fn [re] (let [m (.exec (js/RegExp. (.-source re) "im") s)] (some-> m .-index))) res)]
    (if (seq idx) (subs s 0 (apply min idx)) s)))

(defn strip-quotes
  "The new part of an email body: quoted history, `>` lines and the
   signature removed. HTML in, text out; text in, text out. Heuristic — keep
   the raw body if the answer depends on what was quoted."
  [body]
  (let [s    (str body)
        html? (re-find #"<(html|body|div|p|br|table|span)\b" s)
        t    (if html? (html->text (cut-at s html-quote-starts)) s)]
    (->> (str/split-lines (cut-at t text-quote-starts))
         (remove #(re-find #"^\s*>" %))
         (str/join "\n")
         (str/trim))))

(defn truncate
  "`s` cut to `n` chars with a marker saying how much went."
  [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 n) "…[+" (- (count s) n) " chars]") s)))

(ns markdown-blocks.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.markdown-blocks :as mb]))

;;; ─── split-blocks ──────────────────────────────────────────────

(describe "split-blocks" (fn []
  (it "splits paragraphs at blank lines"
    (fn []
      (let [blocks (mb/split-blocks "Hello world\n\nSecond paragraph")]
        (-> (expect (count blocks)) (.toBeGreaterThanOrEqual 2))
        (-> (expect (:type (first blocks))) (.toBe "paragraph")))))

  (it "identifies code fences as separate blocks"
    (fn []
      (let [blocks (mb/split-blocks "Some text\n\n```js\nconsole.log('hi')\n```\n\nMore text")]
        (let [types (mapv :type blocks)]
          (-> (expect (some #(= % "code") types)) (.toBeTruthy))
          (-> (expect (some #(= % "paragraph") types)) (.toBeTruthy))))))

  (it "identifies headings as block boundaries"
    (fn []
      (let [blocks (mb/split-blocks "# Title\n\nBody text")]
        (-> (expect (:type (first blocks))) (.toBe "heading")))))

  (it "handles single block content (no boundaries)"
    (fn []
      (let [blocks (mb/split-blocks "Just one paragraph")]
        (-> (expect (count blocks)) (.toBe 1))
        (-> (expect (:type (first blocks))) (.toBe "paragraph")))))

  (it "handles empty string"
    (fn []
      (let [blocks (mb/split-blocks "")]
        (-> (expect (count blocks)) (.toBe 0)))))

  (it "handles nil"
    (fn []
      (let [blocks (mb/split-blocks nil)]
        (-> (expect (count blocks)) (.toBe 0)))))

  (it "handles incomplete code fence (streaming)"
    (fn []
      (let [blocks (mb/split-blocks "Some text\n\n```js\npartial code")]
        ;; Should not crash, may treat incomplete fence as paragraph or code
        (-> (expect (pos? (count blocks))) (.toBe true)))))

  (it "preserves lang field on code blocks"
    (fn []
      (let [blocks (mb/split-blocks "```python\nprint('hi')\n```")]
        (let [code-block (first (filter #(= (:type %) "code") blocks))]
          (-> (expect (some? code-block)) (.toBe true))
          (-> (expect (:lang code-block)) (.toBe "python"))))))))

;;; ─── render-block ──────────────────────────────────────────────

(describe "render-block" (fn []
  (it "renders a paragraph block with default renderer"
    (fn []
      (let [result (mb/render-block {:type "paragraph" :raw "Hello **world**"} {})]
        ;; Should contain the text (with or without ANSI codes)
        (-> (expect (.includes result "world")) (.toBe true)))))

  (it "delegates to custom renderer when matching type"
    (fn []
      (let [called (atom false)
            renderers {"paragraph" (fn [block]
                                     (reset! called true)
                                     (str "CUSTOM:" (:raw block)))}
            result (mb/render-block {:type "paragraph" :raw "test"} renderers)]
        (-> (expect @called) (.toBe true))
        (-> (expect result) (.toBe "CUSTOM:test")))))

  (it "delegates to custom renderer when matching type:lang"
    (fn []
      (let [renderers {"code:diff" (fn [block] (str "DIFF:" (:raw block)))}
            result (mb/render-block {:type "code" :raw "```diff\n+added\n```" :lang "diff"} renderers)]
        (-> (expect (.includes result "DIFF:")) (.toBe true)))))

  (it "falls back to default when custom renderer returns nil"
    (fn []
      (let [renderers {"paragraph" (fn [_block] nil)}
            result (mb/render-block {:type "paragraph" :raw "fallback"} renderers)]
        ;; Should use default renderer, so text should be present
        (-> (expect (.includes result "fallback")) (.toBe true)))))

  (it "falls back to default when custom renderer throws"
    (fn []
      (let [renderers {"paragraph" (fn [_block] (throw (js/Error. "boom")))}
            result (mb/render-block {:type "paragraph" :raw "safe"} renderers)]
        (-> (expect (.includes result "safe")) (.toBe true)))))))

;;; ─── incremental-render ────────────────────────────────────────

(describe "incremental-render" (fn []
  (it "renders all blocks on first call (no cache)"
    (fn []
      (let [result (mb/incremental-render "Hello\n\nWorld" nil {})]
        (-> (expect (.includes (:rendered result) "Hello")) (.toBe true))
        (-> (expect (.includes (:rendered result) "World")) (.toBe true))
        (-> (expect (count (:blocks result))) (.toBeGreaterThanOrEqual 2)))))

  (it "caches previous blocks — only re-renders last block"
    (fn []
      (let [render-count (atom 0)
            counting-renderers {"paragraph" (fn [block]
                                              (swap! render-count inc)
                                              (str (:raw block)))}
            ;; First render: all blocks
            cache1 (mb/incremental-render "Block A\n\nBlock B" nil counting-renderers)
            count1 @render-count
            ;; Second render: same first block, modified last block
            _      (reset! render-count 0)
            cache2 (mb/incremental-render "Block A\n\nBlock B extended" cache1 counting-renderers)
            count2 @render-count]
        ;; First render should have rendered both blocks
        (-> (expect count1) (.toBe 2))
        ;; Second render should only re-render the changed block (Block B extended)
        ;; Block A unchanged → reused from cache → only 1 render call
        (-> (expect count2) (.toBe 1)))))

  (it "re-renders when new block boundary appears"
    (fn []
      (let [cache1 (mb/incremental-render "Hello world" nil {})
            cache2 (mb/incremental-render "Hello world\n\nNew paragraph" cache1 {})]
        (-> (expect (count (:blocks cache2))) (.toBeGreaterThan (count (:blocks cache1)))))))

  (it "handles empty content"
    (fn []
      (let [result (mb/incremental-render "" nil {})]
        (-> (expect (:rendered result)) (.toBe "")))))

  (it "handles nil content"
    (fn []
      (let [result (mb/incremental-render nil nil {})]
        (-> (expect (:rendered result)) (.toBe "")))))))

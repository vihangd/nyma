(ns test-util.pdf-fixture
  "A real, minimal PDF built in memory — one page per string, Helvetica text —
   so PDF tests need no binary fixture checked in.")

(defn make-pdf
  "Buffer of a valid PDF with one page per entry of `pages`."
  [pages]
  (let [n       (count pages)
        font-id (+ 3 (* 2 n))
        objs    (concat
                 ["<< /Type /Catalog /Pages 2 0 R >>"
                  (str "<< /Type /Pages /Kids [" (.join (clj->js (map #(str (+ 3 (* 2 %)) " 0 R") (range n))) " ")
                       "] /Count " n " >>")]
                 (mapcat (fn [i text]
                           (let [stream (str "BT /F1 24 Tf 72 700 Td (" text ") Tj ET")]
                             [(str "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents " (+ 4 (* 2 i))
                                   " 0 R /Resources << /Font << /F1 " font-id " 0 R >> >> >>")
                              (str "<< /Length " (count stream) " >>\nstream\n" stream "\nendstream")]))
                         (range n) pages)
                 ["<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"])
        out     (atom "%PDF-1.4\n")
        offsets (atom [])]
    (doseq [[i o] (map-indexed vector objs)]
      (swap! offsets conj (count @out))
      (swap! out str (inc i) " 0 obj\n" o "\nendobj\n"))
    (let [xref (count @out)]
      (swap! out str "xref\n0 " (inc (count objs)) "\n0000000000 65535 f \n"
             (apply str (map #(str (.padStart (str %) 10 "0") " 00000 n \n") @offsets))
             "trailer\n<< /Size " (inc (count objs)) " /Root 1 0 R >>\nstartxref\n" xref "\n%%EOF\n")
      (js/Buffer.from @out "latin1"))))

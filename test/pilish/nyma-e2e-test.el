;;; nyma-e2e-test.el --- A real pilish chat buffer on nyma -*- lexical-binding: t; -*-

;;; Commentary:

;; pilish's own contracts stop at the RPC layer.  These drive what a person
;; uses: the chat and input buffers, with `pilish-send'.  Run by
;; scripts/test-pilish.sh, which sets `pilish-executable'.

;;; Code:

(require 'ert)
(require 'pilish)

(defun nyma-e2e--buffer (mode)
  "Return the first buffer in MODE."
  (seq-find (lambda (b) (with-current-buffer b (derived-mode-p mode)))
            (buffer-list)))

(defun nyma-e2e--wait (pred secs)
  "Poll PRED for up to SECS, and return its last value."
  (let ((end (+ (float-time) secs)))
    (while (and (not (funcall pred)) (< (float-time) end))
      (accept-process-output nil 0.1))
    (funcall pred)))

(defun nyma-e2e--text (chat)
  "Return the text of CHAT."
  (with-current-buffer chat (buffer-substring-no-properties (point-min) (point-max))))

(defun nyma-e2e--turn (chat prompt marker)
  "Send PROMPT, and wait until CHAT is idle and shows MARKER."
  (with-current-buffer (nyma-e2e--buffer 'pilish-input-mode)
    (erase-buffer)
    (insert prompt)
    (pilish-send))
  (should (nyma-e2e--wait
           (lambda ()
             (and (eq (buffer-local-value 'pilish--status chat) 'idle)
                  (string-match-p marker (nyma-e2e--text chat))))
           90)))

(ert-deftest nyma-e2e/chat-and-tool-turn ()
  "A chat turn and a tool turn render cleanly in the chat buffer."
  (let* ((dir (make-temp-file "pilish-nyma-" t))
         (default-directory (file-name-as-directory dir)))
    (with-temp-file (expand-file-name "note.txt" dir)
      (insert "MARKER_IN_FILE\n"))
    (pilish)
    (let ((chat (nyma-e2e--buffer 'pilish-chat-mode)))
      (should chat)
      (should (nyma-e2e--wait
               (lambda () (eq (buffer-local-value 'pilish--status chat) 'idle)) 15))
      (nyma-e2e--turn chat "Reply with the single word PONG and nothing else." "PONG")
      (nyma-e2e--turn chat "Use the read tool on note.txt, then reply with its contents."
                      "MARKER_IN_FILE")
      (let ((text (nyma-e2e--text chat)))
        (should-not (string-match-p "\e\\[" text))
        (should-not (string-match-p "</?think" text)))
      (should (buffer-local-value 'pilish--process chat))
      (pilish-set-session-name "Renamed")
      (should (nyma-e2e--wait
               (lambda () (equal (buffer-local-value 'pilish--session-name chat) "Renamed"))
               10)))))

(provide 'nyma-e2e-test)
;;; nyma-e2e-test.el ends here

#!/usr/bin/env bash
# pilish (the Emacs frontend) against ./nyma: its own contract suite, then a real
# chat buffer. Opt-in: it calls your default model. Runs in a throwaway HOME so
# test sessions stay out of ~/.nyma; settings are copied, credentials linked.
set -euo pipefail

ref=a01a0f7
root=$(cd "$(dirname "$0")/.." && pwd)
nyma=${NYMA_BIN:-$root/nyma}
cache=${NYMA_PILISH_CACHE:-$HOME/.cache/nyma/pilish}
src=$cache/src
home=$cache/home

[ -x "$nyma" ] || { echo "no $nyma: run bun run bundle" >&2; exit 2; }
command -v emacs >/dev/null || { echo "emacs not found" >&2; exit 2; }

if [ ! -d "$src/.git" ]; then
  mkdir -p "$cache"
  git clone -q https://github.com/dnouri/pilish "$src"
fi
git -C "$src" checkout -q "$ref"

mkdir -p "$home/.nyma"
rm -rf "$home/.nyma/sessions"
[ -f "$HOME/.nyma/settings.json" ] && cp "$HOME/.nyma/settings.json" "$home/.nyma/settings.json"
[ -f "$HOME/.nyma/credentials.json" ] && ln -sf "$HOME/.nyma/credentials.json" "$home/.nyma/credentials.json"

cd "$src"
make deps >/dev/null
elpa=$(ls -d "$src"/.cache/elpa/* | head -1)
load=(--batch -Q -L . -L test -L "$root/test/pilish"
      --eval "(setq package-user-dir \"$elpa\")"
      --eval "(require 'package)" --eval "(package-initialize)"
      --eval "(setq load-path (cons (expand-file-name \".\") load-path))"
      --eval "(add-to-list 'treesit-extra-load-path (expand-file-name \"~/.emacs.d/tree-sitter\"))")

export HOME=$home PI_RUN_INTEGRATION=1 PI_INTEGRATION_BACKENDS=real
[ -d "$home/.emacs.d/tree-sitter" ] || emacs "${load[@]}" -l scripts/install-ts-grammars.el >/dev/null 2>&1

# Left out, on purpose: both assert pi's session file, not the protocol.
#   first-user-persists: pi creates the file lazily; nyma creates it at start.
#   name-persists-across-session-file: greps for pi's `session_info` entry.
emacs "${load[@]}" -l pilish -l pilish-integration-test -l nyma-e2e-test \
  --eval "(setq pilish-executable '(\"$nyma\") pilish-extra-args '(\"--mode\" \"pi-rpc\" \"--trust\"))" \
  --eval "(setq pilish-test-rpc-timeout 30)" \
  --eval "(ert-run-tests-batch-and-exit '(and (or \"/real\$\" \"^nyma-e2e/\") (not (or \"first-user-persists\" \"name-persists-across-session-file\"))))" \
  </dev/null

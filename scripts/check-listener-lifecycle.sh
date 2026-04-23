#!/usr/bin/env bash
# check-listener-lifecycle.sh
#
# Scans src/ for event-bus :on registrations that have a js-await between the
# on and the corresponding off without a protecting try/finally.
#
# This is the pattern that caused the message_update handler-leak bug (e882aed):
#
#   ((:on events) "some-event" h)
#   (js-await (do-something))        ;; if this throws...
#   ((:off events) "some-event" h)   ;; ...this never runs → handler leaks
#
# The fix is always:
#   ((:on events) "some-event" h)
#   (try (js-await ...) (finally ((:off events) "some-event" h)))
#
# This script is a belt-and-braces reminder for reviewers; the real regression
# safety net is test/handler_lifecycle.test.cljs + :handler-count on the bus.
# Run with || true in CI so it never blocks a build — it's advisory only.
#
# Usage: scripts/check-listener-lifecycle.sh [src-dir]
#        Defaults to src/ relative to the repo root.

set -euo pipefail

SRC="${1:-src}"
ISSUES=0

# Walk every .cljs file under SRC
while IFS= read -r -d '' file; do
  content="$(cat "$file")"

  # Quick pre-filter: skip files with no :on / js-await combination
  if ! grep -q '(:on ' "$file" 2>/dev/null; then continue; fi
  if ! grep -q 'js-await' "$file" 2>/dev/null; then continue; fi

  # Look for the danger pattern: a (:on ...) registration followed within
  # 10 lines by a js-await, without an intervening (try or useEffect.
  # We use awk for a simple sliding-window check.
  result=$(awk '
    /\(:on [^)]*events/ {
      in_on = 1
      on_line = NR
      window = ""
    }
    in_on {
      window = window "\n" $0
      if (/useEffect/ || /\(try/) {
        in_on = 0   # protected — useEffect cleanup or explicit try
      }
      if (/js-await/ && in_on) {
        print FILENAME ":" on_line ": unguarded :on before js-await (line " NR ")"
        in_on = 0
        found = 1
      }
      if (NR - on_line > 10) { in_on = 0 }  # window expired
    }
    END { exit (found ? 1 : 0) }
  ' "$file" 2>/dev/null || true)

  if [[ -n "$result" ]]; then
    echo "$result"
    ISSUES=$((ISSUES + 1))
  fi
done < <(find "$SRC" -name "*.cljs" -print0)

if [[ $ISSUES -eq 0 ]]; then
  echo "check-listener-lifecycle: no unguarded :on/js-await pairs found in $SRC"
  exit 0
else
  echo ""
  echo "check-listener-lifecycle: $ISSUES file(s) may have unguarded handlers."
  echo "Verify each is either protected by try/finally or inside a useEffect."
  exit 1
fi

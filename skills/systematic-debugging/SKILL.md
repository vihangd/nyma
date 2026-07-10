---
name: systematic-debugging
description: A disciplined method for finding root causes instead of guess-patching. Use when a bug is non-obvious, a test fails for unclear reasons, or a fix didn't work.
triggers:
  - "debug"
  - "why is this failing"
  - "root cause"
---

# Systematic debugging

Guess-patching wastes turns and hides the real cause. Work the loop:

1. **Reproduce reliably.** Find the smallest input/state that triggers it. If you can't reproduce
   it, you can't confirm a fix — get a reliable repro first.
2. **Read the actual error.** Quote the real message/stack. Don't paraphrase it into what you
   expect. The line it points at is a fact.
3. **Isolate.** Bisect: which commit, which input, which branch? Remove variables until the failure
   surface is one function.
4. **Form ONE hypothesis** about the root cause, and predict what you'd observe if it's true.
5. **Test the hypothesis** with the cheapest observation (a log, a value, a unit check) — not by
   editing the fix. Confirm or refute before changing code.
6. **Fix the cause, not the symptom.** A `try/catch` that swallows the error, or a special-case for
   the one input, usually means you fixed a symptom. Ask: what invariant was violated?
7. **Verify** the repro now passes AND you didn't break the general case. Add one runnable check so
   the bug can't silently return.

If two hypotheses fit the evidence, the one you can DISPROVE fastest goes first.

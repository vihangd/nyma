---
name: test-first
description: Write a failing check before the fix, and leave one runnable check behind for non-trivial logic. Use when fixing a bug or adding behavior.
triggers:
  - "write a test"
  - "add a test"
  - "tdd"
---

# Test first

A fix without a check is unverified; a bug without a regression test comes back.

1. **Before fixing a bug**, write the smallest check that FAILS because of the bug. Watch it fail
   for the right reason — a test that passes before your fix proves nothing.
2. **For new behavior**, encode the acceptance criterion as a check first; let it drive the API.
3. **Assert on behavior, not implementation.** Test the observable output/contract, so a refactor
   that keeps behavior doesn't break the test.
4. **One runnable check minimum** for any non-trivial logic (a branch, a loop, a parser, a
   money/security path) — the smallest thing that fails if the logic breaks. Trivial one-liners
   don't need a test.
5. **Cover the edges you reasoned about**: empty, zero, nil/None, boundary, and the error path —
   not just the happy path.
6. **Run it and read the output.** "Tests pass" is only true if you saw them pass; quote the result.

Match the project's existing test style and framework — don't introduce a new one.

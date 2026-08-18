# bench — an Aider-Polyglot subset runner

Nyma had no measured numbers. Every small-model claim in its READMEs was borrowed
from [little-coder's](https://github.com/itayinbarr/little-coder) results. This
is the smallest harness that fixes that: it runs real exercises, grades them with
the exercises' own tests, and writes a result file you can diff against the next
one.

## What the number is and is not

**It is a baseline for nyma against itself** — the thing that makes "did that
change help?" answerable.

**It is not comparable to published scores.** little-coder's 45.56% is the full
225-task Polyglot set across six languages. This runs a Python subset (34
exercises, sampled). Different, easier population. Every result file repeats this
in a `comparability` field so a number can't drift loose from its caveat.

## Setup

```bash
git clone --depth 1 https://github.com/Aider-AI/polyglot-benchmark bench/tasks
```

`bench/tasks/` is gitignored — nothing is vendored. Python exercises need only a
`python3`: all 34 use stdlib `unittest`, so there is nothing to install.
JavaScript exercises each carry their own jest + babel `package.json`; until you
run `npm install` in one, it is reported **skipped**, never failed.

## Running

```bash
bun bench/run.mjs --count 20 --seed 7 --model build --label baseline
bun bench/run.mjs --only python/wordy --model build      # one task
bun bench/run.mjs --trials 3 --count 10 --model build    # mean ± spread
bun bench/run.mjs --diff bench/results/A.json bench/results/B.json
```

`--model` takes a **role name** (`fast`, `build`, `advisor`) and resolves it
through your settings, or an explicit `provider/model`. An unresolvable role is a
hard error — silently falling back to the default model would attribute the score
to the wrong one.

Each task runs in a temp copy with `-p --output-format json --no-session
--permission-mode full-auto`. The bound is wall-clock (`--timeout-ms`, default
300s); there is no `--max-steps` CLI flag, so the agent's settings default (100)
applies.

## What it refuses to do

- **Benchmark the wrong build.** `./nyma` loads 2 extensions, `dist/agent/cli.mjs`
  loads ~39. Both run and answer; only one is the agent you mean. The runner
  aborts unless it is pointed at the dist entry, and records the count.
- **Grade against the answer key.** `.meta/` (the reference solution) is excluded
  from the working copy.
- **Accept a rewritten test file.** The test file is hashed before and after; if
  the agent edited it, the task is `error`, not `pass`.
- **Score what never ran.** Missing toolchain → `skip`. Timeout → `timeout`.
  Agent crashed or the model was rejected → `error`. Only real attempts land in
  the percentage denominator; skips are reported beside it, never inside it.
- **Report one number for a stochastic process.** `--trials N` gives mean ±
  half-range; a single trial reports no spread rather than a fake zero.

## Result files

`bench/results/<iso>-<label>.json` plus `latest.json`, committed on purpose — the
history is the point. Each carries the git SHA, agent command and extension
count, role → resolved model, seed, task list, per-task
`{status, durationMs, tokens, costUsd}`, and the aggregate.

## Proving the harness rather than the model

`bench/stub-agent.mjs` is a fake agent driven by `NYMA_BENCH_STUB`:

```bash
NYMA_BENCH_STUB=solve bun bench/run.mjs --agent-cmd "bun $PWD/bench/stub-agent.mjs" --count 5   # 100%
NYMA_BENCH_STUB=break bun bench/run.mjs --agent-cmd "bun $PWD/bench/stub-agent.mjs" --count 5   # 0%
NYMA_BENCH_STUB=cheat bun bench/run.mjs --agent-cmd "bun $PWD/bench/stub-agent.mjs" --count 3   # all error
```

`solve` writes the reference solution, `break` writes something that cannot pass,
`cheat` overwrites the test file. If those three do not produce 100% / 0% / all
`error`, the harness is broken regardless of what any real run says.

The pure half (`scoring.mjs`: discovery, seeded selection, output classification,
aggregation, diffing) is unit-tested in `test/bench_scoring.test.cljs` inside the
normal suite.

# nyma on Terminal-Bench (Harbor)

`nyma_agent.py` is a [Harbor](https://harborframework.com) installed agent. It
uploads a prebuilt nyma Linux binary into each task container — matching the
container's architecture and libc — and runs one headless turn:

```
nyma -p --output-format stream-json --permission-mode full-auto --no-session \
     --max-steps 200 --model <provider/model> "<task instruction>"
```

The transcript lands in the trial's `agent/nyma.jsonl`.

## Run

```bash
uv tool install harbor
bun run bundle:linux-x64 && bun run bundle:linux-arm64     # + :linux-x64-musl / :linux-arm64-musl for Alpine images
export ANTHROPIC_API_KEY=...                                # or the key for whichever provider you pass to -m
PYTHONPATH=bench/terminal-bench harbor run \
  -d terminal-bench/terminal-bench -l 20 -n 4 \
  -m anthropic/claude-sonnet-5-5 \
  --agent-import-path nyma_agent:Nyma -o bench/results/tb
```

Datasets on the hub: `terminal-bench/terminal-bench` (66 tasks), `terminal-bench/terminal-bench-2`
and `-2-1` (89). `-l` caps the task count, `-t` runs one task, `-n` sets concurrency.

## Host requirement: real x86-64, or Rosetta

Task images are linux/amd64. On an Apple Silicon Mac, Docker runs them under
**qemu user emulation**, and Bun (JavaScriptCore) aborts there at startup —
`ASSERTION FAILED: MemoryExhaustion … qemu: uncaught target signal 6`
(smoke run, 2026-10-07: install and upload worked; the binary died in 56 ms
before any model call). Run the benchmark on an x86-64 Linux host, or enable
Rosetta for amd64 containers in Docker/Rancher Desktop (VZ + Rosetta), which
runs the x64 binary natively translated instead of under qemu.

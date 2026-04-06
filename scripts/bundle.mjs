#!/usr/bin/env bun
// Standalone binary builder — wraps the bun build CLI.
//
// Usage:
//   bun scripts/bundle.mjs [target] [outfile]
//
// Defaults: target=bun-darwin-arm64, outfile=nyma

const target  = process.argv[2] ?? "bun-darwin-arm64";
const outfile = process.argv[3] ?? "nyma";

console.log(`Building ${outfile} for ${target}...`);

const proc = Bun.spawn(
  ["bun", "build", "./dist/agent/cli.mjs",
   "--compile",
   `--target=${target}`,
   `--outfile=${outfile}`],
  { stdout: "inherit", stderr: "inherit" }
);

const code = await proc.exited;
if (code !== 0) process.exit(code);

console.log(`Done: ./${outfile}`);

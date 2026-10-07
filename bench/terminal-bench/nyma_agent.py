"""Harbor installed-agent adapter for nyma (Terminal-Bench and other Harbor datasets).

Uploads a prebuilt nyma Linux binary into the task container — the one
matching its architecture and libc — and runs one headless turn:

    nyma -p --output-format stream-json --permission-mode full-auto \
         --model <provider/model> "<instruction>"

Build the binaries first (from the repo root):
    bun run bundle:linux-x64 && bun run bundle:linux-arm64 \
      && bun run bundle:linux-x64-musl && bun run bundle:linux-arm64-musl

Run (from the repo root, see README.md here):
    harbor run -d terminal-bench@4.0 -l 20 -n 4 -m anthropic/claude-sonnet-5-5 \
      --agent-import-path nyma_agent:Nyma   # with PYTHONPATH=bench/terminal-bench
"""

import os
import shlex
from pathlib import Path
from typing import override

from harbor.agents.installed.base import BaseInstalledAgent, with_prompt_template
from harbor.agents.model_connection import ModelConnectionSpec
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext

REPO = Path(__file__).resolve().parents[2]
BIN_DIR = Path(os.environ.get("NYMA_BIN_DIR", REPO))
REMOTE_BIN = "/usr/local/bin/nyma"


class Nyma(BaseInstalledAgent):
    MODEL_CONNECTION = ModelConnectionSpec(passthrough=True)

    @staticmethod
    @override
    def name() -> str:
        return "nyma"

    @override
    def get_version_command(self) -> str | None:
        return f"{REMOTE_BIN} --version"

    @override
    async def install(self, environment: BaseEnvironment) -> None:
        probe = await environment.exec(
            "uname -m; ls /lib/ld-musl-* >/dev/null 2>&1 && echo musl || echo glibc"
        )
        out = (getattr(probe, "stdout", "") or "").split()
        arch = "arm64" if out and out[0] in ("aarch64", "arm64") else "x64"
        libc = "-musl" if "musl" in out else ""
        local = BIN_DIR / f"nyma-linux-{arch}{libc}"
        if not local.exists():
            raise FileNotFoundError(
                f"{local} missing — run `bun run bundle:linux-{arch}{libc}` in {REPO}"
            )
        await environment.upload_file(local, REMOTE_BIN)
        await environment.exec(f"chmod +x {REMOTE_BIN} && {REMOTE_BIN} --version")

    @override
    def populate_context_post_run(self, context: AgentContext) -> None:
        pass

    @override
    @with_prompt_template
    async def run(
        self, instruction: str, environment: BaseEnvironment, context: AgentContext
    ) -> None:
        if not self.model_name or "/" not in self.model_name:
            raise ValueError("model must be provider/model, e.g. anthropic/claude-sonnet-5-5")
        await self.exec_as_agent(
            environment,
            command=(
                f"{REMOTE_BIN} -p --output-format stream-json --permission-mode full-auto "
                f"--no-session --max-steps 200 --model {shlex.quote(self.model_name)} "
                f"{shlex.quote(instruction)} 2>&1 | tee /logs/agent/nyma.jsonl"
            ),
            env={**self.model_connection.env, "NYMA_NO_MODEL_DISCOVERY": "1"},
        )

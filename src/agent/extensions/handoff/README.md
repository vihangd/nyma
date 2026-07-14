# handoff

Session-to-session brief passing — Amp's alternative to `/compact`.

`/handoff` makes one model call over the transcript and writes a purpose-built brief (Goal /
State / Decisions / Next steps / Key files) to `.nyma/handoff.md`. Any new session in the same
project injects that brief into the system prompt until it's overwritten or `/handoff clear`ed.

Why not just compact: a handoff brief is written *for continuation* — it preserves intent and
next actions, where mechanical summarization preserves whatever happened to be said. Amp's team
reports handoff effectively replaced compaction for them.

Flow: `/handoff` → `/new` → keep working; the new session starts from "Next steps".

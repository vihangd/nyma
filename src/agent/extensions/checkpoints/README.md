# checkpoints

File checkpoints + `/rewind` — a trust net for accept-edits/full-auto modes.

Before any editing tool (`write`/`edit`/`multi_edit`) touches a file, the file's **pre-turn**
state is captured (content, or "didn't exist"). Checkpoints group by turn.

- `/rewind` — restore every file the last turn edited; repeat to go further back.
- `/rewind list` — show rewind points.

Session-scoped and in-memory: gone on exit, not a VCS. Files over 2 MB are skipped rather than
held in memory. Restores that recreate a deleted state remove the file again.

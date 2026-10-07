# checkpoints

File checkpoints + `/rewind` — a trust net for accept-edits/full-auto modes.

Before any editing tool (`write`/`edit`/`multi_edit`) touches a file, the file's **pre-turn**
state is captured (content, or "didn't exist"). Checkpoints group by turn.

- `/rewind` — restore every file the last turn edited; repeat to go further back.
- `/rewind list` — show rewind points.
- `/diff` — everything this session changed, ready to review before a commit: `git diff HEAD` for the files nyma's tools wrote **or a bash command changed** (`sed -i`, formatters — reported by bash_suite), plus files it created that git does not track yet. Your own edits elsewhere are left out. Outside a git repo it lists the files.

Session-scoped and in-memory: gone on exit, not a VCS. Files over 2 MB are skipped rather than
held in memory. Restores that recreate a deleted state remove the file again.

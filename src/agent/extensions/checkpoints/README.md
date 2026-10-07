# checkpoints

Per-turn file checkpoints, `/undo`, and `/diff` — a trust net for accept-edits/full-auto modes.

Before any editing tool (`write`/`edit`/`multi_edit`) touches a file, the file's **pre-turn**
state is captured (content, or "didn't exist"). Files a **bash command** changed (`sed -i`,
formatters — reported by bash_suite) are captured too when their prior state is knowable: a file
that was clean or absent before the command restores to HEAD's content or is removed again; one
that already had uncommitted changes is not restorable and is left alone.

- `/undo` (`/rewind`) — pick a rewind point; everything from that turn on is undone. With one
  point, or as `/rewind last`, it undoes the newest turn.
- `/rewind list` — show rewind points.
- `/diff` — everything this session changed, ready to review before a commit: `git diff HEAD` for
  the files nyma's tools wrote or a bash command changed, plus files it created that git does not
  track yet. Your own edits elsewhere are left out. Outside a git repo it lists the files.

Rewind points persist per session under `~/.nyma/checkpoints/<project>/<session>/` — never in the
repo — so they survive a restart and follow `/resume`. The last 20 turns are kept; files over 2 MB
are skipped.

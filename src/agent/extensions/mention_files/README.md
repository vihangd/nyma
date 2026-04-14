# mention-files

> `@filename` autocomplete for project files in the editor.

## What it does

Registers a **mention provider** keyed on `@`. When the user types `@foo` in the editor, this extension searches the project for matching file paths via `Bun.Glob` and returns up to 50 suggestions, each with a directory hint. The provider respects `.gitignore` and excludes common noise folders out of the box: `node_modules`, `.git`, `dist`, `.nyma`, etc.

Selected entries are inserted as **context references** so the agent receives the file content alongside the prompt — not just the literal `@filename` text.

## How to use

In the editor, type `@` followed by part of a file name. A picker overlay appears with the matches; press `Tab` / `Enter` to insert.

## Capabilities

`ui`

## See also

- [`docs/extension-guide-cljs.md`](../../../../docs/extension-guide-cljs.md) — mention provider section

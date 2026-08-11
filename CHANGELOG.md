<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Confer Changelog

## [Unreleased]
### Added
- Tool window wrapping the `claude` CLI as a subprocess, with a JCEF-based streaming chat panel
- `Shift+Alt+C` context injection (`@path#Lstart-Lend`) from the editor selection
- Native diff viewer with Keep/Revert for Write/Edit tool calls
- Diagnostics injection (`⚡` toggle) from the active file's IDE-reported warnings/errors
- Permission-mode selector, model switcher, and persisted binary-path/settings UI
- Full UI redesign matching the VS Code Claude Code extension: markdown rendering, per-tool-type
  blocks (Read/Grep/Glob one-liners, Bash exit badges, Edit/Write diff headers), thinking blocks
  with collapse/expand-all (`Ctrl+O`), turn-summary line, cost/budget bar, context-window meter
- Real fuzzy `@`-mention file/folder search, gitignore-aware
- Live editor-selection indicator with a hide/show toggle, auto-merged into every prompt
- Drag-and-drop file attachments
- Session history browser (search, group by time, rename, soft-delete), backed by the CLI's own
  `~/.claude/projects/*.jsonl` transcripts, with best-effort AI-generated titles
- Multi-session tabs — sidebar, editor tab, and detached window each get an independent `claude`
  process and history instead of sharing one
- Editable diffs: edits made before accepting are fed back to Claude on the next turn instead of
  silently diverging from what the CLI believes is on disk
- A local IDE MCP server (`~/.claude/ide/<port>.lock` discovery, matching the official VS Code
  extension's mechanism) exposing a real `getDiagnostics` tool and `openDiff`/`close_tab` for
  genuine pre-execution diff approval, verified against the installed CLI's own protocol rather
  than assumed
- Plan mode: plans open as a full Markdown document in an editor tab; approving reads back any
  edits you made as feedback instead of a fixed acknowledgement
- "Not logged in" detection with a one-click `/login` action, and a `/logout` menu item
- `--worktree`/`-w` flag support for running a session in an isolated git worktree
- `/usage`, `/mcp`, and `/plugins` overlays showing real session-local cost/tool-use tracking and
  live MCP server status from the CLI's own event stream

### Fixed
- `session_id` was read from the wrong JSON key, breaking session resume
- `tool_result` events (`type:"user"` in the CLI's stream-json output) were silently dropped —
  Bash output and other tool results now reach the UI
- A `WriteCommandAction` was being invoked from a JCEF background thread instead of the EDT

[Unreleased]: https://github.com/towhid7667/Confer/commits/main

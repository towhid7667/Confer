# Feature Requirements — VS Code Claude Code parity for JetBrains

Grouped by area, with ✅ = you have it, 🔧 = partially, ❌ = missing.

## 1. Entry points & panel placement
- ✅ Icon in Editor Toolbar (top-right of editor) — `OpenConferAction`, `EditorContextBarMenu` placement (only visible when a file is open, matching VS Code's own equivalent)
- ✅ Status Bar item ("✱ Claude Code") — `ConferStatusBarWidget`, global, works with no file open, animates while Claude is working
- 🔧 Activity-Bar-equivalent sessions list — the Session History overlay (search/rename/delete, Phase 1) plus multi-session tabs (Phase 1) cover this in spirit; not a literal always-visible sidebar list
- 🔧 Tool window placement — VS Code lets you drag Claude to sidebar / editor area. JetBrains: register the tool window as anchorable + support "open as editor tab" via `FileEditorProvider` with a virtual file
- ✅ Open in New Tab / New Window (each with independent history + context) — Phase 1 `ClaudeSessionManager` refactor
- ❌ Status dot on tab icon: blue = permission pending, orange = finished while hidden
- ❌ URI handler equivalent (`vscode://anthropic.claude-code/open?prompt=…&session=…`) — JetBrains: `JBProtocolCommand` (`jetbrains://idea/claude?prompt=…&session=…`)

## 2. Auth
- ❌ Dedicated sign-in screen on first open — not built; `/login` runs through the normal chat pipe instead (see below), which triggers the CLI's own real browser OAuth flow
- ✅ Detect "not logged in" and offer sign-in — verified there's no structured auth-error event (confirmed via testing: these always arrive as plain assistant text, e.g. "Not logged in… Please run /login", "Your session has expired…"), so detection is a `/login` substring check on finished text blocks (`maybeShowSignInAction`), showing a "Run /login" button that sends it as a prompt
- ✅ Logout action — overflow menu item sends `/logout` as a prompt
- 🔧 `DISABLE_LOGIN_COMMAND` (real env var name, verified via binary strings — ROADMAP's original `disableLoginPrompt` guess was wrong) — already achievable today via the existing generic environment-variables settings field, no dedicated UI built
- ✅ Env var inheritance from shell (`ANTHROPIC_API_KEY`) — already done, `EnvironmentUtil.getEnvironmentMap()` in `ClaudeProcess`

## 3. Prompt box
- ✅ Permission mode selector (Manual/default, Plan, acceptEdits) — add `bypassPermissions` gated behind an `allowDangerouslySkipPermissions` setting
- 🔧 Slash command menu — you have skills/commands; VS Code's `/` menu also includes: attach files, switch model, toggle extended thinking, `/usage`, `/mcp`, `/plugins`, `/compact`, hooks, memory, permissions, General Config
- ❌ Context-window usage indicator + auto-compact + manual `/compact`
- 🔧 Extended thinking — collapsed blocks + `Ctrl+O` expand/collapse-all already work; no UI to toggle extended-thinking mode itself on/off
- ✅ Multi-line input via `Shift+Enter`; `useCtrlEnterToSend` option — already correctly wired (`$prompt` keydown handler)
- ✅ Model switcher — `window.__modelBridge__` → `ClaudeSettings.model`

## 4. Context / file referencing
- ✅ `@path#Lstart-Lend` insertion from selection (`Shift+Alt+C`; VS Code uses `Alt+K`)
- ✅ `@`-mention autocomplete with **fuzzy matching**, gitignore-aware — 6455fef
- ✅ **Implicit selection context** — on by default, confirmed; hide/show toggle now persists across restarts (Phase 1e)
- ✅ Selection indicator in prompt footer showing line count, with an eye-slash toggle to hide selection from Claude — `#sel-indicator`/`$selEye`/`selectionHidden`
- ✅ Drag-and-drop files into prompt box as attachments; X to remove — already implemented (`attachments` array, `dragover` handling)
- ❌ `@terminal:name` — include a terminal tab's output in the prompt
- ❌ PDF page-range reading
- ❌ `respectGitIgnore` for file searches
- ❌ `@browser` / Chrome integration (low priority for JetBrains — no equivalent extension)

## 5. Editing & diffs
- ✅ Native diff viewer with Accept/Reject
- ❌ **Editable diff** — user can modify proposed content in the diff before accepting; Claude must be *told* it was modified (feed edited content back as the tool result)
- ✅ `autosave` — `ClaudeSettings.autosave`, used in the send handler
- ❌ Permission prompt inline in chat (accept / reject / "tell Claude what to do instead" free-text)

## 6. Plan mode
- ✅ Plan opens as a **full Markdown document** in an editor tab — `openPlanDocument`, `LightVirtualFile("Plan.md",...)`
- ✅ Inline comments on the plan doc, fed back as feedback before Claude proceeds — Approve now reads back the (possibly user-edited) document text and sends it as feedback if it differs from the original, instead of a fixed "looks good" message. Not a dedicated gutter/comment-thread UI — the document itself is the feedback surface.
- ✅ Approve / reject plan — `chat.html` `.plan-row`, wired to `window.__planReadyBridge__`

## 7. Sessions
- ✅ **Bug: capture `session_id`** (fixed in 6455fef)
- ❌ Session history browser: search by keyword, group by time (Today / Yesterday / Last 7 days)
- ❌ Resume session (`--resume <id>`) with full message history replay
- ❌ AI-generated session titles from first message
- ❌ Rename / delete session
- ❌ Reopen most recently closed session (`Ctrl+Shift+T`)
- ❌ Remote tab — resume Claude Code on the web sessions
- ❌ Multi-session tabs, each with own process/history

## 8. Checkpoints / rewind
- ❌ Hover a message → rewind menu (button already scaffolded in `chat.html` as `.tb-rewind`, disabled "coming soon"):
  - Fork conversation from here (keep code)
  - Rewind code to here (keep conversation)
  - Fork conversation **and** rewind code
- **Verified groundwork for a future attempt** (2026-08-11): real transcript `.jsonl` lines carry `uuid`/`parentUuid` per message; the CLI records `type:"file-history-snapshot"` events keyed by `messageId`; there's a `checkpoints` boolean setting (in the CLI's own shared `settings.json` — don't duplicate); the real slash command is `/rewind`, and there's an undocumented `--rewind-files <user-message-uuid>` CLI flag. Tested `/rewind` directly (both `-p` and persistent stream-json mode, matching how `ClaudeProcess` runs it) and got `"/rewind isn't available in this environment"`, with `rewind` absent from the CLI's own reported `slash_commands` list — inconclusive (may be an artifact of testing from within a nested Claude session, not necessarily true for a real desktop user) but real enough that wiring the button blind wasn't worth the risk. Left honestly disabled rather than shipping something unverified.

## 9. Tool result rendering
- ✅ Tool-use blocks inline, diff rendering for Edit/Write, JSON for others
- ✅ **Bug: tool_result events (`type: "user"`) unhandled** — fixed in 6455fef, Bash output etc. now reaches UI.
- ✅ Markdown rendering in chat (code blocks w/ syntax highlighting) — 6455fef
- ❌ Long-running/background process progress in status bar

## 10. IDE MCP server (the big architectural one)
VS Code runs a **local MCP server** the CLI connects to. Protocol verified against a live lock file and the installed `claude` binary's own string constants (not guessed) — see `mcp/` package:
- ✅ Bind `127.0.0.1` on random high port, random auth token per activation, lock file in `~/.claude/ide/` (0600 in 0700 dir), started eagerly on project open via `ConferIdeStartupActivity` — this is what makes `/ide` work from an external terminal too
- ✅ `mcp__ide__getDiagnostics` — real MCP tool (`ConferIdeMcpServer`), reusing `DiagnosticsCollector`; the old prompt-prepending path (`⚡` toggle) is kept as a separate, faster manual option
- ❌ `mcp__ide__executeCode` — Jupyter cell execution (skip unless you support notebooks)
- 🔧 Internal RPCs: `openDiff`/`close_tab` (real verified names/params) implemented — native diff viewer + notification Accept/Reject give genuine pre-execution gating, replacing the post-hoc Keep/Revert card for IDE-initiated edits. A "read selection"/"save file" RPC pair may also exist in the real protocol but wasn't found in this session's verification pass — not implemented, not blocking.
- ✅ `Read` deny-rule respect — `.env`-pattern files excluded from `@`-mention search and auto-selection context (`isDenyRuleFile`)

## 11. MCP management
- 🔧 `/mcp` dialog exists (`showMcpOverlay`), showing the real server list + status dots from the CLI's own `init` event (now includes Confer's own `mcp/` server once connected) — enable/disable, reconnect, and OAuth management are still not wired
- ❌ (Add servers still goes through CLI `claude mcp add` — acceptable)

## 12. Plugins
- 🔧 `/plugins` overlay exists (`showPluginsOverlay`) — an honest mock pointing to the CLI directly, no in-app management
- ❌ Install scope picker: user / project / local
- ❌ "Restart to apply" banner

## 13. Usage & account
- 🔧 `/usage` dialog exists (`showUsageOverlay`) — real session-local cost + tool-use tracking; account/plan/reset-timer data isn't available without direct API access Confer doesn't have, so it's honestly scoped to what's real
- ❌ Usage attribution: per skill / subagent / plugin / MCP server; flag behaviors ≥10% (cache misses, long context, parallelism) with tips
- ❌ Day / Week toggle
- ✅ (You have a cost/budget bar — keep, but this is a superset)

## 14. Settings
Extension-level settings to port to a JetBrains `Configurable`:
- `useTerminal` (launch CLI in terminal instead of panel)
- `initialPermissionMode` ✅ (persisted)
- `preferredLocation` (sidebar vs tab)
- `autosave`
- `useCtrlEnterToSend`
- `enableNewConversationShortcut`
- `enableReopenClosedSessionShortcut`
- `hideOnboarding`
- `respectGitIgnore`
- `usePythonEnvironment` (activate project's Python SDK env for the Claude process)
- `environmentVariables`
- `disableLoginPrompt`
- `allowDangerouslySkipPermissions`
- `claudeProcessWrapper` (binary path) — ✅ persistence + `ClaudeConfigurable` settings UI (6455fef)
- Plus: `~/.claude/settings.json` is **shared with the CLI** — do not duplicate hooks/permissions/MCP config into plugin state

## 15. Commands & shortcuts (map to JetBrains actions)
| VS Code | Shortcut | JetBrains equivalent |
|---|---|---|
| Focus Input | `Ctrl+Esc` | Toggle focus editor ↔ chat |
| Open in Side Bar | — | Tool window |
| Open in Terminal | — | Run `claude` in IDE terminal |
| Open in New Tab | `Ctrl+Shift+Esc` | Editor-tab session |
| Open in New Window | — | |
| New Conversation | `Ctrl+N` | |
| Reopen Closed Session | `Ctrl+Shift+T` | |
| Insert @-Mention | `Alt+K` | ✅ (yours: `Shift+Alt+C`) |
| Show Logs | — | Debug log view |
| Logout | — | |

## 16. Onboarding
- ❌ "Learn Claude Code" checklist after sign-in, with "Show me" walkthroughs; dismissible; `hideOnboarding` setting
- ❌ Walkthrough command

## 17. Git / worktrees
- ✅ `--worktree` / `-w` flag support for parallel isolated sessions — `ClaudeSettings.useWorktree`/`worktreeName`, `ClaudeConfigurable` UI, wired into `ClaudeProcess`

---

## Suggested priority for your next milestones

**P0 — done:** `session_id` key bug; `tool_result` handling; settings UI for binary path (all fixed/shipped in 6455fef).
**P1 — done:** implicit selection context polish; editable diff feedback loop; session history CRUD; multi-session tabs (each tab now owns an independent `ClaudeSession`/process). (markdown rendering, `@` fuzzy autocomplete, model switcher, autosave, selection indicator, and plan approve/reject were already ✅ from 6455fef.)
**P2 — done:** IDE MCP server (`mcp/` package) — verified wire protocol (WebSocket, lock file schema, auth header, tool names), real `getDiagnostics` tool, `openDiff`/`close_tab` pre-execution diff gating, `.env` deny-rule. `mcp__ide__executeCode` intentionally skipped (no notebook support).
**P3 — mostly done (2026-08-11):** plan-doc inline comments; auth "not logged in" detection + logout; `/mcp` Reconnect wired; worktree flag; entry points and several smaller items turned out already done and were corrected above (editor toolbar icon, status bar widget, drag-and-drop, multi-line input, selection indicator). Checkpoints/rewind explicitly **not** implemented — investigated thoroughly (see §8), left honestly disabled pending real-world verification that `/rewind` works outside a nested-agent test context. Still genuinely missing: `/usage`/`/plugins` full management UI (overlays exist, honestly scoped to what's real); URI handler; `@terminal:name`; PDF page-range reading; onboarding is a checklist overlay already, no walkthrough animations; usage attribution breakdown.
**P4:** JetBrains Marketplace publish prep — signing/verifier Gradle config unwired despite CI already expecting the secrets; `plugin.xml` description and `README.md` still template boilerplate; `CHANGELOG.md` empty.
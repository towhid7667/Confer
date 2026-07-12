# Feature Requirements — VS Code Claude Code parity for JetBrains

Grouped by area, with ✅ = you have it, 🔧 = partially, ❌ = missing.

## 1. Entry points & panel placement
- ❌ Icon in Editor Toolbar (top-right of editor) — opens Claude
- ❌ Status Bar item ("✱ Claude Code") — works with no file open
- ❌ Activity-Bar-equivalent sessions list (JetBrains: tool window with a session list; click to open a session)
- 🔧 Tool window placement — VS Code lets you drag Claude to sidebar / editor area. JetBrains: register the tool window as anchorable + support "open as editor tab" via `FileEditorProvider` with a virtual file
- ❌ Open in New Tab / New Window (each with independent history + context)
- ❌ Status dot on tab icon: blue = permission pending, orange = finished while hidden
- ❌ URI handler equivalent (`vscode://anthropic.claude-code/open?prompt=…&session=…`) — JetBrains: `JBProtocolCommand` (`jetbrains://idea/claude?prompt=…&session=…`)

## 2. Auth
- ❌ Sign-in screen on first open, browser OAuth flow (`/login`)
- ❌ Detect "Not logged in · Please run /login" and re-show sign-in
- ❌ Logout action
- ❌ `disableLoginPrompt` setting for third-party providers (Bedrock / Vertex / Foundry)
- ❌ Env var inheritance from shell (`ANTHROPIC_API_KEY`)

## 3. Prompt box
- ✅ Permission mode selector (Manual/default, Plan, acceptEdits) — add `bypassPermissions` gated behind an `allowDangerouslySkipPermissions` setting
- 🔧 Slash command menu — you have skills/commands; VS Code's `/` menu also includes: attach files, switch model, toggle extended thinking, `/usage`, `/mcp`, `/plugins`, `/compact`, hooks, memory, permissions, General Config
- ❌ Context-window usage indicator + auto-compact + manual `/compact`
- ❌ Extended thinking toggle; thinking rendered as collapsed blocks; expand/collapse-all shortcut (VS Code: `Ctrl+O`)
- ❌ Multi-line input via `Shift+Enter`; `useCtrlEnterToSend` option
- ❌ Model switcher

## 4. Context / file referencing
- ✅ `@path#Lstart-Lend` insertion from selection (`Shift+Alt+C`; VS Code uses `Alt+K`)
- ❌ `@`-mention autocomplete with **fuzzy matching** over files *and folders* (trailing `/` for folders)
- ❌ **Implicit selection context** — the active file path + current selection are sent automatically with every prompt, without an explicit @-mention
- ❌ Selection indicator in prompt footer showing line count, with an eye-slash toggle to hide selection from Claude
- ❌ Drag-and-drop files into prompt box as attachments; X to remove
- ❌ `@terminal:name` — include a terminal tab's output in the prompt
- ❌ PDF page-range reading
- ❌ `respectGitIgnore` for file searches
- ❌ `@browser` / Chrome integration (low priority for JetBrains — no equivalent extension)

## 5. Editing & diffs
- ✅ Native diff viewer with Accept/Reject
- ❌ **Editable diff** — user can modify proposed content in the diff before accepting; Claude must be *told* it was modified (feed edited content back as the tool result)
- ❌ `autosave` — save dirty files before Claude reads/writes them
- ❌ Permission prompt inline in chat (accept / reject / "tell Claude what to do instead" free-text)

## 6. Plan mode
- ❌ Plan opens as a **full Markdown document** in an editor tab
- ❌ Inline comments on the plan doc, fed back as feedback before Claude proceeds
- ❌ Approve / reject plan

## 7. Sessions
- 🔧 **Bug: capture `session_id`** (you have `sessionId` — fix)
- ❌ Session history browser: search by keyword, group by time (Today / Yesterday / Last 7 days)
- ❌ Resume session (`--resume <id>`) with full message history replay
- ❌ AI-generated session titles from first message
- ❌ Rename / delete session
- ❌ Reopen most recently closed session (`Ctrl+Shift+T`)
- ❌ Remote tab — resume Claude Code on the web sessions
- ❌ Multi-session tabs, each with own process/history

## 8. Checkpoints / rewind
- ❌ Hover a message → rewind menu:
  - Fork conversation from here (keep code)
  - Rewind code to here (keep conversation)
  - Fork conversation **and** rewind code

## 9. Tool result rendering
- ✅ Tool-use blocks inline, diff rendering for Edit/Write, JSON for others
- 🔧 **Bug: tool_result events (`type: "user"`) unhandled** — Bash output etc. never reaches UI. Fix.
- ❌ Markdown rendering in chat (code blocks w/ syntax highlighting)
- ❌ Long-running/background process progress in status bar

## 10. IDE MCP server (the big architectural one)
VS Code runs a **local MCP server** the CLI connects to. You need a JetBrains equivalent, or you lose the features it powers:
- ❌ Bind `127.0.0.1` on random high port, random auth token per activation, lock file in `~/.claude/ide/` (0600 in 0700 dir) — this is what makes `/ide` work from an external terminal too
- ❌ `mcp__ide__getDiagnostics` — expose `DaemonCodeAnalyzer` errors/warnings as an MCP tool (you currently prepend them to the prompt; the real design is a tool Claude *calls*)
- ❌ `mcp__ide__executeCode` — Jupyter cell execution (skip unless you support notebooks)
- ❌ Internal RPCs: open diff, read selection, save file — filtered from the model's tool list
- ❌ `Read` deny-rule respect (e.g. `.env` excluded from selection + open-file context)

## 11. MCP management
- ❌ `/mcp` dialog: enable/disable servers, reconnect, OAuth auth management
- ❌ (Add servers still goes through CLI `claude mcp add` — acceptable)

## 12. Plugins
- ❌ `/plugins` GUI: Plugins tab (installed w/ toggles, available w/ Install) + Marketplaces tab (add by GitHub repo/URL/local path, refresh, remove)
- ❌ Install scope picker: user / project / local
- ❌ "Restart to apply" banner

## 13. Usage & account
- ❌ `/usage` dialog: account, plan, session + weekly usage bars, reset timers
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
- `claudeProcessWrapper` (binary path) — ❌ you have persistence but no UI
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
- ❌ `--worktree` / `-w` flag support for parallel isolated sessions

---

## Suggested priority for your next milestones

**P0 (fix now):** `session_id` key bug; `tool_result` handling; settings UI for binary path.
**P1 (core parity):** markdown rendering; implicit selection context + toggle; `@` fuzzy autocomplete; editable diff; session history + resume; multi-session tabs; context-window indicator.
**P2 (the differentiator):** IDE MCP server (`getDiagnostics` as a real tool, diff RPC, selection RPC, `~/.claude/ide/` lock file) — this also makes `/ide` from the terminal work, which is what makes the plugin feel native.
**P3:** checkpoints/rewind; plan-as-document with inline comments; `/usage`; `/plugins` GUI; `/mcp` dialog; auth/sign-in flow; extended thinking; onboarding.
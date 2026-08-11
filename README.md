# Confer

![Build](https://github.com/towhid7667/Confer/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

Confer brings [Claude Code](https://code.claude.com/docs/en/overview) into JetBrains IDEs as a native chat panel, wrapping the `claude` CLI as a subprocess instead of shelling out to a terminal.

- **Streaming chat** with markdown rendering, collapsible thinking blocks, and inline tool-use/diff cards
- **Native diff review** for every edit, with a real pre-execution approval flow for IDE-initiated edits via a local MCP server
- **Multi-session tabs** — open in the sidebar, an editor tab, or a detached window, each with its own independent Claude process and history
- **Context injection** from your editor selection (`Shift+Alt+C`), fuzzy `@`-mention file search, and automatic active-selection context
- **Session history** with search, rename, and delete, backed by the CLI's own session transcripts
- **Plan mode** that opens as a full Markdown document you can edit and send back as feedback before approving
- Permission modes, model switching, and a live cost/context-window meter

(This section and `plugin.xml`'s `<description>` are maintained together by hand, not auto-synced — keep both in mind when updating either.)

Requires the [Claude Code CLI](https://code.claude.com/docs/en/overview) installed and authenticated (`claude` on your `PATH`, or point Confer at it directly in **Settings → Confer**).

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Confer"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/towhid7667/Confer/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## Usage

- Open the **Confer** tool window (right sidebar) to start chatting.
- `Shift+Alt+C` on a selection (or with a file open, no selection) injects a `@path#Lstart-Lend` reference into the chat input.
- Type `@` in the prompt box to fuzzy-search project files/folders; `/` for skills and built-in commands (`/usage`, `/mcp`, `/plugins`, `/compact`...).
- Use the overflow menu (⋯) to open the current session in an editor tab or a detached window — each gets its own independent `claude` process.
- **Settings → Confer** configures the CLI binary path, model, permission mode, worktree usage, and environment variables. `~/.claude/settings.json` is shared directly with the CLI and isn't duplicated here.

## How it works

Confer spawns `claude` with `--output-format stream-json --input-format stream-json`, parsing the CLI's own event stream to drive the UI — no reimplementation of Claude Code's behavior. Diagnostics and pre-execution diff approval for IDE-initiated edits go through a local MCP server Confer runs and advertises via `~/.claude/ide/<port>.lock`, the same discovery mechanism the official VS Code extension uses.

See [ROADMAP.md](./ROADMAP.md) for the full feature-parity tracking against the VS Code Claude Code extension, and [CHANGELOG.md](./CHANGELOG.md) for release history.

---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template

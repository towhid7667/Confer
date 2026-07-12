package com.github.towhid7667.confer.claude

sealed class ClaudeEvent {

    data class McpServer(val name: String, val status: String)

    data class Init(
        val sessionId: String,
        val model: String,
        val permissionMode: String,
        val mcpServers: List<McpServer>,
    ) : ClaudeEvent()

    data class TextDelta(val text: String) : ClaudeEvent()

    data class ThinkingDelta(val text: String) : ClaudeEvent()

    /** A content block ended; blocks are sequential within a turn, so the UI resolves whichever one is currently open. */
    object BlockStop : ClaudeEvent()

    /** A tool_use block started streaming, before its input is fully known — lets the UI show a running/spinner state immediately. */
    data class ToolStart(
        val id: String,
        val toolName: String,
    ) : ClaudeEvent()

    data class ToolUse(
        val id: String,
        val toolName: String,
        val inputJson: String,
    ) : ClaudeEvent()

    data class ToolResult(
        val toolUseId: String,
        val isError: Boolean,
        val content: String,
    ) : ClaudeEvent()

    data class TurnEnd(
        val sessionId: String,
        val isError: Boolean,
        val totalCostUsd: Double?,
        val durationMs: Long?,
        val contextTokensUsed: Long?,
        val contextWindow: Long?,
    ) : ClaudeEvent()

    data class Error(val message: String) : ClaudeEvent()

    data class Unknown(val raw: String) : ClaudeEvent()
}

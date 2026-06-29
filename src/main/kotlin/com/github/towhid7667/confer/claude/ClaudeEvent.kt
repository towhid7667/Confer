package com.github.towhid7667.confer.claude

sealed class ClaudeEvent {

    data class Init(
        val sessionId: String,
        val model: String,
        val permissionMode: String,
    ) : ClaudeEvent()

    data class TextDelta(val text: String) : ClaudeEvent()

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
    ) : ClaudeEvent()

    data class Error(val message: String) : ClaudeEvent()

    data class Unknown(val raw: String) : ClaudeEvent()
}

package com.github.towhid7667.confer.claude

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ClaudeEventParser {

    fun parse(line: String): ClaudeEvent = try {
        dispatch(JsonParser.parseString(line).asJsonObject, line)
    } catch (_: Exception) {
        ClaudeEvent.Unknown(line)
    }

    private fun dispatch(json: JsonObject, raw: String): ClaudeEvent =
        when (json.str("type")) {
            "system" -> parseSystem(json, raw)
            "assistant" -> parseAssistant(json, raw)
            "user" -> parseUser(json, raw)
            "stream_event" -> parseStreamEvent(json, raw)
            "result" -> parseResult(json)
            else -> ClaudeEvent.Unknown(raw)
        }

    private fun parseSystem(json: JsonObject, raw: String): ClaudeEvent {
        if (json.str("subtype") != "init") return ClaudeEvent.Unknown(raw)
        val mcpServers = json.getAsJsonArray("mcp_servers")?.mapNotNull { el ->
            val obj = el.asJsonObject
            val name = obj.str("name") ?: return@mapNotNull null
            ClaudeEvent.McpServer(name, obj.str("status") ?: "unknown")
        } ?: emptyList()
        return ClaudeEvent.Init(
            sessionId = json.str("session_id") ?: "",
            model = json.str("model") ?: "unknown",
            permissionMode = json.str("permissionMode") ?: "default",
            mcpServers = mcpServers,
        )
    }

    /**
     * Only handles tool_use here: its full input is only available in this complete-block event.
     * Text and thinking content is intentionally NOT re-emitted here — it already streamed via
     * stream_event/content_block_delta, and re-emitting it here would duplicate it in the UI.
     */
    private fun parseAssistant(json: JsonObject, raw: String): ClaudeEvent {
        val message = json.getAsJsonObject("message") ?: return ClaudeEvent.Unknown(raw)
        val content = message.getAsJsonArray("content") ?: return ClaudeEvent.Unknown(raw)
        for (element in content) {
            val obj = element.asJsonObject
            if (obj.str("type") != "tool_use") continue
            return ClaudeEvent.ToolUse(
                id = obj.str("id") ?: "",
                toolName = obj.str("name") ?: "",
                inputJson = obj.get("input")?.toString() ?: "{}",
            )
        }
        return ClaudeEvent.Unknown(raw)
    }

    private fun parseUser(json: JsonObject, raw: String): ClaudeEvent {
        val message = json.getAsJsonObject("message") ?: return ClaudeEvent.Unknown(raw)
        val content = message.getAsJsonArray("content") ?: return ClaudeEvent.Unknown(raw)
        for (element in content) {
            val obj = element.asJsonObject
            if (obj.str("type") != "tool_result") continue
            return ClaudeEvent.ToolResult(
                toolUseId = obj.str("tool_use_id") ?: "",
                isError = obj.get("is_error")?.asBoolean ?: false,
                content = obj.get("content")?.let { toolResultText(it) } ?: "",
            )
        }
        return ClaudeEvent.Unknown(raw)
    }

    private fun toolResultText(element: JsonElement): String = when {
        element.isJsonNull -> ""
        element.isJsonPrimitive -> element.asString
        element.isJsonArray -> element.asJsonArray.joinToString("\n") { item ->
            item.asJsonObject.str("text") ?: item.toString()
        }
        else -> element.toString()
    }

    /**
     * Real CLI output nests partial-message events as {"type":"stream_event","event":{"type":"content_block_start|content_block_delta|content_block_stop",...}} —
     * NOT as a flat top-level "content_block_delta", which is what the previous version of this parser assumed (and which the CLI never actually emits).
     */
    private fun parseStreamEvent(json: JsonObject, raw: String): ClaudeEvent {
        val event = json.getAsJsonObject("event") ?: return ClaudeEvent.Unknown(raw)
        return when (event.str("type")) {
            "content_block_start" -> parseBlockStart(event, raw)
            "content_block_delta" -> parseBlockDelta(event, raw)
            "content_block_stop" -> ClaudeEvent.BlockStop
            else -> ClaudeEvent.Unknown(raw)
        }
    }

    private fun parseBlockStart(event: JsonObject, raw: String): ClaudeEvent {
        val block = event.getAsJsonObject("content_block") ?: return ClaudeEvent.Unknown(raw)
        if (block.str("type") != "tool_use") return ClaudeEvent.Unknown(raw)
        return ClaudeEvent.ToolStart(
            id = block.str("id") ?: "",
            toolName = block.str("name") ?: "",
        )
    }

    private fun parseBlockDelta(event: JsonObject, raw: String): ClaudeEvent {
        val delta = event.getAsJsonObject("delta") ?: return ClaudeEvent.Unknown(raw)
        return when (delta.str("type")) {
            "text_delta" -> delta.str("text")?.takeIf { it.isNotEmpty() }
                ?.let { ClaudeEvent.TextDelta(it) } ?: ClaudeEvent.Unknown(raw)
            "thinking_delta" -> delta.str("thinking")?.takeIf { it.isNotEmpty() }
                ?.let { ClaudeEvent.ThinkingDelta(it) } ?: ClaudeEvent.Unknown(raw)
            else -> ClaudeEvent.Unknown(raw)
        }
    }

    /** Context-window usage is a genuine approximation from the CLI's own usage/modelUsage payload, not a made-up number. */
    private fun parseResult(json: JsonObject): ClaudeEvent {
        val usage = json.getAsJsonObject("usage")
        val tokensUsed = usage?.let {
            (it.get("input_tokens")?.asLong ?: 0L) +
                (it.get("cache_creation_input_tokens")?.asLong ?: 0L) +
                (it.get("cache_read_input_tokens")?.asLong ?: 0L)
        }
        val contextWindow = json.getAsJsonObject("modelUsage")
            ?.entrySet()
            ?.firstNotNullOfOrNull { it.value.asJsonObject.get("contextWindow")?.asLong }

        return ClaudeEvent.TurnEnd(
            sessionId = json.str("session_id") ?: "",
            isError = json.get("is_error")?.asBoolean ?: false,
            totalCostUsd = json.get("total_cost_usd")?.asDouble,
            durationMs = json.get("duration_ms")?.asLong,
            contextTokensUsed = tokensUsed,
            contextWindow = contextWindow,
        )
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}

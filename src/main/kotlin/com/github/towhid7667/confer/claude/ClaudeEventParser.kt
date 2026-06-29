package com.github.towhid7667.confer.claude

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
            "content_block_delta" -> parseContentBlockDelta(json, raw)
            "result" -> parseResult(json)
            else -> ClaudeEvent.Unknown(raw)
        }

    private fun parseSystem(json: JsonObject, raw: String): ClaudeEvent {
        if (json.str("subtype") != "init") return ClaudeEvent.Unknown(raw)
        return ClaudeEvent.Init(
            sessionId = json.str("sessionId") ?: "",
            model = json.str("model") ?: "unknown",
            permissionMode = json.str("permissionMode") ?: "default",
        )
    }

    private fun parseAssistant(json: JsonObject, raw: String): ClaudeEvent {
        val message = json.getAsJsonObject("message") ?: return ClaudeEvent.Unknown(raw)
        val content = message.getAsJsonArray("content") ?: return ClaudeEvent.Unknown(raw)
        for (element in content) {
            val obj = element.asJsonObject
            when (obj.str("type")) {
                "text" -> {
                    val text = obj.str("text") ?: ""
                    if (text.isNotEmpty()) return ClaudeEvent.TextDelta(text)
                }
                "tool_use" -> return ClaudeEvent.ToolUse(
                    id = obj.str("id") ?: "",
                    toolName = obj.str("name") ?: "",
                    inputJson = obj.get("input")?.toString() ?: "{}",
                )
            }
        }
        return ClaudeEvent.Unknown(raw)
    }

    private fun parseContentBlockDelta(json: JsonObject, raw: String): ClaudeEvent {
        val delta = json.getAsJsonObject("delta") ?: return ClaudeEvent.Unknown(raw)
        if (delta.str("type") != "text_delta") return ClaudeEvent.Unknown(raw)
        val text = delta.str("text") ?: ""
        return if (text.isNotEmpty()) ClaudeEvent.TextDelta(text) else ClaudeEvent.Unknown(raw)
    }

    private fun parseResult(json: JsonObject): ClaudeEvent =
        ClaudeEvent.TurnEnd(
            sessionId = json.str("session_id") ?: "",
            isError = json.get("is_error")?.asBoolean ?: false,
            totalCostUsd = json.get("total_cost_usd")?.asDouble,
        )

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString
}

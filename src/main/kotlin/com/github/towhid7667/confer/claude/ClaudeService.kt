package com.github.towhid7667.confer.claude

import com.github.towhid7667.confer.settings.ClaudeSettings
import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class ClaudeService(private val project: Project) : Disposable {

    private val logger = thisLogger()
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<ClaudeEventListener>()
    private var process: ClaudeProcess? = null
    var currentSessionId: String? = null
        private set

    fun addListener(listener: ClaudeEventListener) = listeners.add(listener)

    fun removeListener(listener: ClaudeEventListener) = listeners.remove(listener)

    fun sendPrompt(text: String) {
        ensureRunning()
        // ⚠️ Verify: exact input schema for --input-format stream-json against current Claude Code docs.
        val contentJson = gson.toJson(text)
        val message = """{"type":"user","message":{"role":"user","content":$contentJson}}"""
        process?.writeMessage(message)
    }

    fun stop() {
        process?.sendInterrupt()
        process = null
        currentSessionId = null
    }

    private fun ensureRunning() {
        if (process != null) return
        val settings = ClaudeSettings.getInstance()
        val workDir = project.basePath ?: System.getProperty("user.home")
        try {
            val p = ClaudeProcess(settings.claudeBinaryPath, workDir, settings.permissionMode)
            process = p
            p.start { line ->
                val event = ClaudeEventParser.parse(line)
                logger.debug("claude event: $event")
                if (event is ClaudeEvent.Init) currentSessionId = event.sessionId
                fireEvent(event)
            }
        } catch (e: Exception) {
            logger.error("Failed to start claude process at '${settings.claudeBinaryPath}'", e)
            process = null
            fireEvent(ClaudeEvent.Error("Cannot start claude: ${e.message}"))
        }
    }

    private fun fireEvent(event: ClaudeEvent) {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it.onEvent(event) }
        }
    }

    override fun dispose() {
        process?.dispose()
        process = null
    }
}

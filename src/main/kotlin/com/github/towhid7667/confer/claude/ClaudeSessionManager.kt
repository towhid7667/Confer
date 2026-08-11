package com.github.towhid7667.confer.claude

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns one [ClaudeSession] per open Confer tab/window, keyed by tab id, so each tab gets an
 * independent `claude` process and conversation history instead of sharing a single process.
 */
@Service(Service.Level.PROJECT)
class ClaudeSessionManager(private val project: Project) : Disposable {

    private val sessions = ConcurrentHashMap<String, ClaudeSession>()

    fun getOrCreateSession(tabId: String): ClaudeSession =
        sessions.getOrPut(tabId) {
            ClaudeSession(project).also { Disposer.register(this, it) }
        }

    /** Stops and discards the session for a closed tab so its process doesn't leak. */
    fun closeSession(tabId: String) {
        sessions.remove(tabId)?.let { Disposer.dispose(it) }
    }

    override fun dispose() {
        sessions.clear()
    }

    companion object {
        /** The tool window's always-present panel uses this fixed id; it's never closed via [closeSession]. */
        const val DEFAULT_TAB_ID = "main"

        fun newTabId(): String = java.util.UUID.randomUUID().toString()
    }
}

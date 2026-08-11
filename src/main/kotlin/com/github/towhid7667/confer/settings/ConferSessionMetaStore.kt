package com.github.towhid7667.confer.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Plugin-owned metadata for the CLI's own on-disk session transcripts
 * (`~/.claude/projects/<encoded>/` — one `.jsonl` file per session). We never mutate those
 * files — a session is "deleted" by hiding it here, and a custom title overrides the preview.
 */
class SessionMeta {
    var sessionId: String = ""
    var customTitle: String? = null
    var hidden: Boolean = false
    var titleRequested: Boolean = false
}

@Service(Service.Level.PROJECT)
@State(name = "ConferSessionMeta", storages = [Storage("confer-sessions.xml")])
class ConferSessionMetaStore : PersistentStateComponent<ConferSessionMetaStore.State> {

    class State {
        var entries: MutableList<SessionMeta> = mutableListOf()
        var lastClosedSessionId: String? = null
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    private fun entryFor(sessionId: String): SessionMeta =
        myState.entries.find { it.sessionId == sessionId }
            ?: SessionMeta().also { it.sessionId = sessionId; myState.entries.add(it) }

    fun rename(sessionId: String, title: String) {
        entryFor(sessionId).customTitle = title.trim().takeIf { it.isNotEmpty() }
    }

    fun hide(sessionId: String) {
        entryFor(sessionId).hidden = true
    }

    fun customTitle(sessionId: String): String? =
        myState.entries.find { it.sessionId == sessionId }?.customTitle

    fun isHidden(sessionId: String): Boolean =
        myState.entries.find { it.sessionId == sessionId }?.hidden == true

    /** True the first time this is called for a session id, false afterwards — guards one-shot title generation. */
    fun claimTitleGeneration(sessionId: String): Boolean {
        val entry = entryFor(sessionId)
        if (entry.titleRequested) return false
        entry.titleRequested = true
        return true
    }

    fun markClosed(sessionId: String) {
        myState.lastClosedSessionId = sessionId
    }

    fun lastClosedSessionId(): String? = myState.lastClosedSessionId

    companion object {
        fun getInstance(project: Project): ConferSessionMetaStore = project.service()
    }
}

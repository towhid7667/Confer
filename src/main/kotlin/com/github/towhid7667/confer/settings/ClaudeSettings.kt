package com.github.towhid7667.confer.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "ConferSettings",
    storages = [Storage("confer.xml")],
)
class ClaudeSettings : PersistentStateComponent<ClaudeSettings.State> {

    data class State(
        var claudeBinaryPath: String = "claude",
        // ⚠️ Verify mode strings against `claude --help` (--permission-mode flag)
        var permissionMode: String = "default",
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var claudeBinaryPath: String
        get() = myState.claudeBinaryPath
        set(value) { myState.claudeBinaryPath = value }

    var permissionMode: String
        get() = myState.permissionMode
        set(value) { myState.permissionMode = value }

    companion object {
        fun getInstance(): ClaudeSettings = ApplicationManager.getApplication().service()
    }
}

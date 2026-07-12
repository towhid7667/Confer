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
        var permissionMode: String = "default",
        var model: String = "default",
        var useCtrlEnterToSend: Boolean = false,
        var respectGitIgnore: Boolean = true,
        var allowDangerouslySkipPermissions: Boolean = false,
        var hideOnboarding: Boolean = false,
        var autosave: Boolean = false,
        /** One "KEY=VALUE" pair per line; merged into the claude process environment. */
        var environmentVariables: String = "",
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

    var model: String
        get() = myState.model
        set(value) { myState.model = value }

    var useCtrlEnterToSend: Boolean
        get() = myState.useCtrlEnterToSend
        set(value) { myState.useCtrlEnterToSend = value }

    var respectGitIgnore: Boolean
        get() = myState.respectGitIgnore
        set(value) { myState.respectGitIgnore = value }

    var allowDangerouslySkipPermissions: Boolean
        get() = myState.allowDangerouslySkipPermissions
        set(value) { myState.allowDangerouslySkipPermissions = value }

    var hideOnboarding: Boolean
        get() = myState.hideOnboarding
        set(value) { myState.hideOnboarding = value }

    var autosave: Boolean
        get() = myState.autosave
        set(value) { myState.autosave = value }

    var environmentVariables: String
        get() = myState.environmentVariables
        set(value) { myState.environmentVariables = value }

    fun parsedEnvironmentVariables(): Map<String, String> =
        environmentVariables.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            .filter { it.first.isNotEmpty() }
            .toMap()

    companion object {
        fun getInstance(): ClaudeSettings = ApplicationManager.getApplication().service()
    }
}

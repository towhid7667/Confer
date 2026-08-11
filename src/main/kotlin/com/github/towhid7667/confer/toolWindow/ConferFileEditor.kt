package com.github.towhid7667.confer.toolWindow

import com.github.towhid7667.confer.claude.ClaudeSessionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** Hosts a ConferChatPanel bound to this file's session, inside a regular editor tab. */
class ConferFileEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    private val sessionId = (file as? ConferSessionVirtualFile)?.sessionId ?: ClaudeSessionManager.DEFAULT_TAB_ID
    private val panel = ConferChatPanel(project, sessionId)

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName(): String = "Confer"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file

    /** Non-default sessions are scoped to this tab's lifetime — closing the tab ends the process. */
    override fun dispose() {
        if (sessionId != ClaudeSessionManager.DEFAULT_TAB_ID) {
            project.service<ClaudeSessionManager>().closeSession(sessionId)
        }
    }
}

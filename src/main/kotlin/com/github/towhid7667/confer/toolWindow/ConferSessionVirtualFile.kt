package com.github.towhid7667.confer.toolWindow

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightVirtualFile

/**
 * Identifies which [com.github.towhid7667.confer.claude.ClaudeSession] an editor-tab panel binds
 * to. Equality is by [sessionId] alone so [com.intellij.openapi.fileEditor.FileEditorManager]
 * reuses the existing tab instead of opening a duplicate when the same session is requested again.
 */
class ConferSessionVirtualFile(
    val sessionId: String,
    name: String = "Confer Chat",
) : LightVirtualFile(name, PlainTextFileType.INSTANCE, "") {
    override fun isWritable(): Boolean = false
    override fun equals(other: Any?): Boolean =
        other is ConferSessionVirtualFile && other.sessionId == sessionId
    override fun hashCode(): Int = sessionId.hashCode()
}

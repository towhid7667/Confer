package com.github.towhid7667.confer.toolWindow

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class ConferFileEditorProvider : FileEditorProvider {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is ConferSessionVirtualFile
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ConferFileEditor(project, file)
    override fun getEditorTypeId(): String = "confer-chat-editor"
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

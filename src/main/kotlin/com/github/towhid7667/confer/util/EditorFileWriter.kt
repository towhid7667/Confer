package com.github.towhid7667.confer.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Writes `content` to `filePath` on the EDT via a [WriteCommandAction]. Callers that trigger this
 * from a background thread (JCEF query handlers, the MCP server's WebSocket callbacks) don't run
 * on the EDT, and `WriteCommandAction` requires it, so this always hops over via `invokeLater`.
 */
fun writeFileContentOnEdt(project: Project, filePath: String, content: String, commandName: String) {
    ApplicationManager.getApplication().invokeLater {
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
            ?: return@invokeLater

        WriteCommandAction.runWriteCommandAction(project, commandName, null, Runnable {
            val doc = FileDocumentManager.getInstance().getDocument(vFile)
            if (doc != null) {
                doc.setText(content)
            } else {
                vFile.setBinaryContent(content.toByteArray(Charsets.UTF_8))
            }
        })
    }
}

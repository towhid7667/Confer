package com.github.towhid7667.confer.actions

import com.github.towhid7667.confer.toolWindow.ConferChatPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.wm.ToolWindowManager

class InjectContextAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor  = e.getData(CommonDataKeys.EDITOR) ?: return
        val file    = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val selection = editor.selectionModel
        val reference = if (selection.hasSelection()) {
            val doc       = editor.document
            val startLine = doc.getLineNumber(selection.selectionStart) + 1
            val endLine   = doc.getLineNumber(
                (selection.selectionEnd - 1).coerceAtLeast(selection.selectionStart),
            ) + 1
            "@${file.path}#L$startLine-L$endLine"
        } else {
            "@${file.path}"
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Confer")
            ?: return

        toolWindow.show {
            val panel = toolWindow.contentManager.getContent(0)
                ?.component as? ConferChatPanel
            panel?.injectContext(reference)
        }
    }
}

package com.github.towhid7667.confer.diagnostics

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

object DiagnosticsCollector {

    /** Returns a formatted diagnostics block for the active editor file, or null if none. */
    @Suppress("UnstableApiUsage")
    fun collect(project: Project): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val file   = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val doc    = editor.document

        val highlights: List<HighlightInfo> = runReadAction {
            DaemonCodeAnalyzerImpl.getHighlights(doc, HighlightSeverity.WARNING, project)
        }

        if (highlights.isEmpty()) return null

        val sb = StringBuilder("Diagnostics for ${file.path}:\n")
        highlights
            .sortedBy { it.startOffset }
            .forEach { h ->
                val line = doc.getLineNumber(h.startOffset) + 1
                val sev  = h.severity.myName.uppercase()
                val desc = h.description ?: "(no description)"
                sb.appendLine("  Line $line [$sev]: $desc")
            }
        return sb.toString().trimEnd()
    }
}

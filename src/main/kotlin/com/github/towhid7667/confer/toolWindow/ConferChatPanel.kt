package com.github.towhid7667.confer.toolWindow

import com.github.towhid7667.confer.claude.ClaudeEvent
import com.github.towhid7667.confer.claude.ClaudeEventListener
import com.github.towhid7667.confer.claude.ClaudeService
import com.github.towhid7667.confer.diagnostics.DiagnosticsCollector
import com.github.towhid7667.confer.settings.ClaudeSettings
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JPanel

// ⚠️ Verify tool names against stream-json output from your Claude Code CLI version.
private val WRITE_TOOLS = setOf("Write", "Edit", "write_file", "str_replace_editor", "create_file")

private data class PendingEdit(val filePath: String, val proposedContent: String)

class ConferChatPanel(private val project: Project) : JBPanel<ConferChatPanel>(BorderLayout()) {

    private val gson             = Gson()
    private var browser: JBCefBrowser? = null
    private val pendingEdits     = mutableMapOf<String, PendingEdit>()
    private var includeDiagnostics = false

    init {
        if (!JBCefApp.isSupported()) {
            add(
                JBLabel("JCEF is not available in this IDE build — cannot render chat UI."),
                BorderLayout.CENTER,
            )
        } else {
            initBrowser()
        }
    }

    private fun initBrowser() {
        val b        = JBCefBrowser()
        browser      = b
        val svc      = project.service<ClaudeService>()
        val settings = ClaudeSettings.getInstance()

        Disposer.register(project, b)

        val sendQuery   = JBCefJSQuery.create(b as JBCefBrowserBase)
        val stopQuery   = JBCefJSQuery.create(b as JBCefBrowserBase)
        val acceptQuery = JBCefJSQuery.create(b as JBCefBrowserBase)
        val rejectQuery = JBCefJSQuery.create(b as JBCefBrowserBase)

        sendQuery.addHandler   { text   -> svc.sendPrompt(buildPromptWithContext(text)); null }
        stopQuery.addHandler   { _      -> svc.stop();                                   null }
        acceptQuery.addHandler { toolId -> acceptPendingEdit(toolId);                    null }
        rejectQuery.addHandler { toolId -> pendingEdits.remove(toolId);                  null }

        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                cefBrowser.executeJavaScript("window.__sendBridge__=function(t){${sendQuery.inject("t")}};",       "", 0)
                cefBrowser.executeJavaScript("window.__stopBridge__=function(){${stopQuery.inject("'stop'")}};",   "", 0)
                cefBrowser.executeJavaScript("window.__acceptBridge__=function(id){${acceptQuery.inject("id")}};", "", 0)
                cefBrowser.executeJavaScript("window.__rejectBridge__=function(id){${rejectQuery.inject("id")}};", "", 0)
            }
        }, b.cefBrowser)

        val listener = ClaudeEventListener { event -> handleIncomingEvent(event) }
        svc.addListener(listener)
        Disposer.register(b, Disposable { svc.removeListener(listener) })

        add(buildToolbar(svc, settings), BorderLayout.NORTH)
        add(b.component, BorderLayout.CENTER)
        b.loadHTML(loadHtml())
    }

    private fun buildToolbar(svc: ClaudeService, settings: ClaudeSettings): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 8, 3)).apply {
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())

            add(JBLabel("Mode:"))
            add(ComboBox(arrayOf("default", "acceptEdits", "plan")).apply {
                selectedItem = settings.permissionMode
                addActionListener {
                    val chosen = selectedItem as String
                    if (chosen != settings.permissionMode) {
                        settings.permissionMode = chosen
                        svc.stop() // next sendPrompt restarts with new mode
                    }
                }
            })

            add(JBCheckBox("Diagnostics").apply {
                toolTipText = "Prepend active-file errors/warnings to each prompt"
                addActionListener { includeDiagnostics = isSelected }
            })
        }

    /** Pre-fills the chat textarea with a @file#L reference from the editor action. */
    fun injectContext(reference: String) {
        browser?.cefBrowser?.executeJavaScript(
            "window.injectContext(${gson.toJson(reference)});",
            "", 0,
        )
    }

    private fun buildPromptWithContext(text: String): String {
        if (!includeDiagnostics) return text
        val diag = DiagnosticsCollector.collect(project) ?: return text
        return "$diag\n\n$text"
    }

    private fun handleIncomingEvent(event: ClaudeEvent) {
        if (event is ClaudeEvent.ToolUse && event.toolName in WRITE_TOOLS) {
            interceptWriteTool(event)
        }
        toJs(event)?.let { browser?.cefBrowser?.executeJavaScript(it, "", 0) }
    }

    private fun interceptWriteTool(event: ClaudeEvent.ToolUse) {
        val pending = buildPendingEdit(event) ?: return
        pendingEdits[event.id] = pending
        showDiff(event.toolName, pending)
    }

    private fun buildPendingEdit(event: ClaudeEvent.ToolUse): PendingEdit? = try {
        val input    = JsonParser.parseString(event.inputJson).asJsonObject
        val filePath = input.get("file_path")?.asString ?: return null

        val proposed = when (event.toolName) {
            "Write", "write_file", "create_file" -> {
                input.get("content")?.asString ?: return null
            }
            "Edit", "str_replace_editor" -> {
                val vf  = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
                val src = vf.contentsToByteArray().toString(Charsets.UTF_8)
                val old = input.get("old_string")?.asString ?: return null
                val new = input.get("new_string")?.asString ?: ""
                src.replace(old, new)
            }
            else -> return null
        }

        PendingEdit(filePath, proposed)
    } catch (_: Exception) {
        null
    }

    private fun showDiff(toolName: String, pending: PendingEdit) {
        val vFile    = LocalFileSystem.getInstance().findFileByPath(pending.filePath)
        val fileType = vFile?.fileType ?: PlainTextFileType.INSTANCE
        val original = vFile?.contentsToByteArray()?.toString(Charsets.UTF_8) ?: ""

        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Claude [$toolName]: ${pending.filePath.substringAfterLast('/')}",
            factory.create(original, fileType),
            factory.create(pending.proposedContent, fileType),
            "Current",
            "Proposed by Claude",
        )

        DiffManager.getInstance().showDiff(project, request)
    }

    private fun acceptPendingEdit(toolId: String) {
        val pending = pendingEdits.remove(toolId) ?: return
        val vFile   = LocalFileSystem.getInstance().findFileByPath(pending.filePath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(pending.filePath)
            ?: return

        WriteCommandAction.runWriteCommandAction(project, "Claude: Apply Edit", null, Runnable {
            val doc = FileDocumentManager.getInstance().getDocument(vFile)
            if (doc != null) {
                doc.setText(pending.proposedContent)
            } else {
                vFile.setBinaryContent(pending.proposedContent.toByteArray(Charsets.UTF_8))
            }
        })
    }

    private fun toJs(event: ClaudeEvent): String? = when (event) {
        is ClaudeEvent.TextDelta -> jsCall("text", event.text)
        is ClaudeEvent.ToolUse  -> jsCall(
            "tool",
            gson.toJson(
                mapOf(
                    "name"   to event.toolName,
                    "input"  to event.inputJson,
                    "toolId" to event.id,
                    "isEdit" to (event.toolName in WRITE_TOOLS),
                ),
            ),
        )
        is ClaudeEvent.TurnEnd  -> jsCall("end",  gson.toJson(mapOf("cost" to event.totalCostUsd)))
        is ClaudeEvent.Error    -> jsCall("error", event.message)
        else                    -> null
    }

    private fun jsCall(type: String, payload: String): String =
        "window.receiveEvent(${gson.toJson(type)},${gson.toJson(payload)});"

    private fun loadHtml(): String =
        javaClass.getResource("/com/github/towhid7667/confer/chat.html")
            ?.readText(Charsets.UTF_8)
            ?: "<html><body style='color:red;font-family:sans-serif;padding:12px'>" +
               "Error: chat.html not found in plugin resources.</body></html>"
}

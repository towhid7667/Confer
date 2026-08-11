package com.github.towhid7667.confer.toolWindow

import com.github.towhid7667.confer.claude.ClaudeEvent
import com.github.towhid7667.confer.claude.ClaudeEventListener
import com.github.towhid7667.confer.claude.ClaudeSessionManager
import com.github.towhid7667.confer.diagnostics.DiagnosticsCollector
import com.github.towhid7667.confer.settings.ClaudeConfigurable
import com.github.towhid7667.confer.settings.ClaudeSettings
import com.github.towhid7667.confer.settings.ConferSessionMetaStore
import com.github.towhid7667.confer.util.writeFileContentOnEdt
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.util.EnvironmentUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import java.io.File
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import javax.swing.JFrame

private val EXCLUDED_DIRS = setOf(".git", "node_modules", "build", "out", "dist", ".idea", ".gradle", "target")

/** `.env`-pattern files are never surfaced in context-injection UI (search results, auto-selection context). */
private fun isDenyRuleFile(name: String): Boolean = name == ".env" || name.startsWith(".env.")
private const val FILE_SEARCH_VISIT_CAP = 4000
private const val FILE_SEARCH_RESULT_CAP = 30

private val WRITE_TOOLS = setOf("Write", "Edit", "write_file", "str_replace_editor", "create_file")

private data class PendingEdit(
    val filePath: String,
    val proposedContent: String,
    val originalContent: String,
    val toolName: String,
)

class ConferChatPanel(
    private val project: Project,
    private val sessionId: String = ClaudeSessionManager.DEFAULT_TAB_ID,
) : JBPanel<ConferChatPanel>(BorderLayout()) {

    private val gson         = Gson()
    private var browser: JBCefBrowser? = null
    private val pendingEdits = mutableMapOf<String, PendingEdit>()
    private var includeDiagnostics = false
    private var lastSentPrompt: String? = null
    private val pendingEditNotes = mutableListOf<String>()
    private var planVFile: LightVirtualFile? = null
    private var originalPlanText: String = ""

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
        val svc       = project.service<ClaudeSessionManager>().getOrCreateSession(sessionId)
        val settings  = ClaudeSettings.getInstance()
        val metaStore = ConferSessionMetaStore.getInstance(project)

        Disposer.register(project, b)

        val sendQuery       = JBCefJSQuery.create(b as JBCefBrowserBase)
        val stopQuery       = JBCefJSQuery.create(b as JBCefBrowserBase)
        val keepQuery       = JBCefJSQuery.create(b as JBCefBrowserBase)
        val revertQuery     = JBCefJSQuery.create(b as JBCefBrowserBase)
        val modeQuery       = JBCefJSQuery.create(b as JBCefBrowserBase)
        val diagQuery       = JBCefJSQuery.create(b as JBCefBrowserBase)
        val addContextQuery = JBCefJSQuery.create(b as JBCefBrowserBase)
        val openMentionQuery = JBCefJSQuery.create(b as JBCefBrowserBase)
        val viewDiffQuery    = JBCefJSQuery.create(b as JBCefBrowserBase)
        val fileSearchQuery  = JBCefJSQuery.create(b as JBCefBrowserBase)
        val modelQuery       = JBCefJSQuery.create(b as JBCefBrowserBase)
        val newSessionQuery  = JBCefJSQuery.create(b as JBCefBrowserBase)
        val editorTabQuery   = JBCefJSQuery.create(b as JBCefBrowserBase)
        val newWindowQuery   = JBCefJSQuery.create(b as JBCefBrowserBase)
        val settingsQuery    = JBCefJSQuery.create(b as JBCefBrowserBase)
        val sessionHistoryQuery = JBCefJSQuery.create(b as JBCefBrowserBase)
        val resumeSessionQuery  = JBCefJSQuery.create(b as JBCefBrowserBase)
        val planQuery           = JBCefJSQuery.create(b as JBCefBrowserBase)
        val dismissOnboardingQuery = JBCefJSQuery.create(b as JBCefBrowserBase)
        val renameSessionQuery  = JBCefJSQuery.create(b as JBCefBrowserBase)
        val deleteSessionQuery  = JBCefJSQuery.create(b as JBCefBrowserBase)
        val reopenLastClosedQuery = JBCefJSQuery.create(b as JBCefBrowserBase)
        val selectionHiddenQuery  = JBCefJSQuery.create(b as JBCefBrowserBase)
        val reconnectMcpQuery     = JBCefJSQuery.create(b as JBCefBrowserBase)
        val planApproveQuery      = JBCefJSQuery.create(b as JBCefBrowserBase)

        sendQuery.addHandler       { text   ->
            if (settings.autosave) {
                ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
            }
            lastSentPrompt = text
            svc.sendPrompt(buildPromptWithContext(text))
            null
        }
        stopQuery.addHandler       { _      -> svc.stop();                                   null }
        keepQuery.addHandler { json ->
            try {
                val d = JsonParser.parseString(json).asJsonObject
                applyPendingEdit(d.get("toolId").asString, d.get("content").asString)
            } catch (_: Exception) { /* malformed payload from JS, ignore */ }
            null
        }
        revertQuery.addHandler     { toolId -> revertPendingEdit(toolId);                    null }
        modeQuery.addHandler       { mode   -> settings.permissionMode = mode; svc.stop();   null }
        diagQuery.addHandler       { value  -> includeDiagnostics = (value == "1");          null }
        addContextQuery.addHandler { _      -> injectActiveEditorContext();                   null }
        openMentionQuery.addHandler { ref   -> openMention(ref);                             null }
        viewDiffQuery.addHandler    { toolId -> pendingEdits[toolId]?.let { showDiff(it.toolName, it) }; null }
        fileSearchQuery.addHandler  { query ->
            browser?.cefBrowser?.executeJavaScript(
                "window.receiveFileSearchResults(${gson.toJson(searchFilesJson(query))});", "", 0,
            )
            null
        }
        modelQuery.addHandler       { model -> settings.model = model; svc.stop();           null }
        newSessionQuery.addHandler  { _      ->
            svc.currentSessionId?.let { metaStore.markClosed(it) }
            svc.stop()
            pendingEdits.clear()
            null
        }
        editorTabQuery.addHandler   { _      -> openInEditorTab();                           null }
        newWindowQuery.addHandler   { _      -> openInNewWindow();                           null }
        settingsQuery.addHandler    { _      -> openSettings();                              null }
        sessionHistoryQuery.addHandler { _ ->
            browser?.cefBrowser?.executeJavaScript(
                "window.receiveSessionHistory(${gson.toJson(sessionHistoryJson(metaStore))});", "", 0,
            )
            null
        }
        resumeSessionQuery.addHandler  { id ->
            svc.resume(id)
            pendingEdits.clear()
            browser?.cefBrowser?.executeJavaScript(
                "window.loadHistoricalTranscript(${gson.toJson(loadSessionTranscript(id))});", "", 0,
            )
            null
        }
        planQuery.addHandler           { md  -> openPlanDocument(md);                        null }
        dismissOnboardingQuery.addHandler { _ -> settings.hideOnboarding = true;              null }
        renameSessionQuery.addHandler { json ->
            try {
                val d = JsonParser.parseString(json).asJsonObject
                metaStore.rename(d.get("sessionId").asString, d.get("title").asString)
            } catch (_: Exception) { /* malformed payload from JS, ignore */ }
            browser?.cefBrowser?.executeJavaScript(
                "window.receiveSessionHistory(${gson.toJson(sessionHistoryJson(metaStore))});", "", 0,
            )
            null
        }
        deleteSessionQuery.addHandler { id ->
            metaStore.hide(id)
            if (svc.currentSessionId == id) svc.stop()
            browser?.cefBrowser?.executeJavaScript(
                "window.receiveSessionHistory(${gson.toJson(sessionHistoryJson(metaStore))});", "", 0,
            )
            null
        }
        selectionHiddenQuery.addHandler { value -> settings.selectionContextHidden = (value == "1"); null }
        reconnectMcpQuery.addHandler { _ ->
            // No per-server reconnect exists over stream-json — resuming the session is the only
            // lever, but it does re-establish MCP connections (including our own IDE server) fresh.
            svc.currentSessionId?.let { svc.resume(it) } ?: svc.stop()
            null
        }
        planApproveQuery.addHandler { _ ->
            val text = buildPlanApprovalText()
            browser?.cefBrowser?.executeJavaScript(
                "window.receivePlanFeedback(${gson.toJson(mapOf("text" to text))});", "", 0,
            )
            null
        }
        reopenLastClosedQuery.addHandler { _ ->
            val id = metaStore.lastClosedSessionId()
            if (id != null) {
                svc.resume(id)
                pendingEdits.clear()
                browser?.cefBrowser?.executeJavaScript(
                    "window.loadHistoricalTranscript(${gson.toJson(loadSessionTranscript(id))});", "", 0,
                )
            }
            null
        }

        b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                cefBrowser.executeJavaScript("window.__sendBridge__=function(t){${sendQuery.inject("t")}};",                "", 0)
                cefBrowser.executeJavaScript("window.__stopBridge__=function(){${stopQuery.inject("'stop'")}};",             "", 0)
                cefBrowser.executeJavaScript("window.__keepBridge__=function(json){${keepQuery.inject("json")}};",           "", 0)
                cefBrowser.executeJavaScript("window.__revertBridge__=function(id){${revertQuery.inject("id")}};",           "", 0)
                cefBrowser.executeJavaScript("window.__modeBridge__=function(m){${modeQuery.inject("m")}};",                 "", 0)
                cefBrowser.executeJavaScript("window.__diagBridge__=function(v){${diagQuery.inject("v")}};",                 "", 0)
                cefBrowser.executeJavaScript("window.__addContextBridge__=function(){${addContextQuery.inject("'add'")}};",  "", 0)
                cefBrowser.executeJavaScript("window.__openMentionBridge__=function(r){${openMentionQuery.inject("r")}};",   "", 0)
                cefBrowser.executeJavaScript("window.__viewDiffBridge__=function(id){${viewDiffQuery.inject("id")}};",       "", 0)
                cefBrowser.executeJavaScript("window.__modelBridge__=function(m){${modelQuery.inject("m")}};",               "", 0)
                cefBrowser.executeJavaScript("window.__newSessionBridge__=function(){${newSessionQuery.inject("'new'")}};",  "", 0)
                cefBrowser.executeJavaScript("window.__openEditorTabBridge__=function(){${editorTabQuery.inject("'et'")}};", "", 0)
                cefBrowser.executeJavaScript("window.__openNewWindowBridge__=function(){${newWindowQuery.inject("'nw'")}};", "", 0)
                cefBrowser.executeJavaScript("window.__openSettingsBridge__=function(){${settingsQuery.inject("'s'")}};",    "", 0)
                cefBrowser.executeJavaScript("window.__resumeSessionBridge__=function(id){${resumeSessionQuery.inject("id")}};", "", 0)
                cefBrowser.executeJavaScript("window.__planReadyBridge__=function(md){${planQuery.inject("md")}};",          "", 0)
                cefBrowser.executeJavaScript("window.__dismissOnboardingBridge__=function(){${dismissOnboardingQuery.inject("'d'")}};", "", 0)
                cefBrowser.executeJavaScript("window.__fileSearchBridge__=function(q){${fileSearchQuery.inject("q")}};", "", 0)
                cefBrowser.executeJavaScript("window.__sessionHistoryBridge__=function(){${sessionHistoryQuery.inject("'sh'")}};", "", 0)
                cefBrowser.executeJavaScript("window.__renameSessionBridge__=function(json){${renameSessionQuery.inject("json")}};", "", 0)
                cefBrowser.executeJavaScript("window.__deleteSessionBridge__=function(id){${deleteSessionQuery.inject("id")}};", "", 0)
                cefBrowser.executeJavaScript("window.__reopenLastClosedBridge__=function(){${reopenLastClosedQuery.inject("'r'")}};", "", 0)
                cefBrowser.executeJavaScript("window.__selectionHiddenBridge__=function(v){${selectionHiddenQuery.inject("v")}};", "", 0)
                cefBrowser.executeJavaScript("window.__reconnectMcpBridge__=function(){${reconnectMcpQuery.inject("'r'")}};", "", 0)
                cefBrowser.executeJavaScript("window.__planApproveBridge__=function(){${planApproveQuery.inject("'p'")}};", "", 0)
                cefBrowser.executeJavaScript(
                    "window.initSettings(${
                        gson.toJson(
                            mapOf(
                                "mode" to settings.permissionMode,
                                "diag" to false,
                                "model" to settings.model,
                                "ctrlEnterToSend" to settings.useCtrlEnterToSend,
                                "allowBypass" to settings.allowDangerouslySkipPermissions,
                                "hideOnboarding" to settings.hideOnboarding,
                                "selectionHidden" to settings.selectionContextHidden,
                            ),
                        )
                    });",
                    "", 0,
                )
                cefBrowser.executeJavaScript(
                    "window.setSkills(${loadSkills()});",
                    "", 0,
                )
            }
        }, b.cefBrowser)

        val listener = ClaudeEventListener { event -> handleIncomingEvent(event) }
        svc.addListener(listener)
        Disposer.register(b, Disposable { svc.removeListener(listener) })

        val busConnection = ApplicationManager.getApplication().messageBus.connect(b)
        busConnection.subscribe(LafManagerListener.TOPIC, LafManagerListener { pushTheme() })
        busConnection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { pushTheme() })
        busConnection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val editor = (event.newEditor as? TextEditor)?.editor
                    if (editor != null) pushSelectionIndicator(editor) else clearSelectionIndicator()
                }
            },
        )
        EditorFactory.getInstance().eventMulticaster.addSelectionListener(
            object : SelectionListener {
                override fun selectionChanged(event: SelectionEvent) {
                    if (event.editor != FileEditorManager.getInstance(project).selectedTextEditor) return
                    pushSelectionIndicator(event.editor)
                }
            },
            b,
        )

        add(b.component, BorderLayout.CENTER)
        b.loadHTML(loadHtml())
    }

    private fun currentThemeName(): String = if (JBColor.isBright()) "light" else "dark"

    private fun pushTheme() {
        val theme = currentThemeName()
        browser?.cefBrowser?.executeJavaScript(
            "document.documentElement.setAttribute('data-theme',${gson.toJson(theme)});",
            "", 0,
        )
    }

    fun injectContext(reference: String) {
        browser?.cefBrowser?.executeJavaScript(
            "window.injectContext(${gson.toJson(reference)});",
            "", 0,
        )
    }

    private fun injectActiveEditorContext() {
        ApplicationManager.getApplication().invokeLater {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
            val vFile  = FileDocumentManager.getInstance().getFile(editor.document) ?: return@invokeLater
            if (isDenyRuleFile(vFile.name)) return@invokeLater
            val sel    = editor.selectionModel
            val doc    = editor.document
            val startLine = doc.getLineNumber(sel.selectionStart) + 1
            val endLine   = doc.getLineNumber(
                (sel.selectionEnd - 1).coerceAtLeast(sel.selectionStart),
            ) + 1
            injectContext("@${vFile.path}#L$startLine-L$endLine")
        }
    }

    private fun pushSelectionIndicator(editor: Editor) {
        val vFile = FileDocumentManager.getInstance().getFile(editor.document)
        val sel = editor.selectionModel
        if (vFile == null || !sel.hasSelection() || isDenyRuleFile(vFile.name)) {
            clearSelectionIndicator()
            return
        }
        val doc = editor.document
        val startLine = doc.getLineNumber(sel.selectionStart) + 1
        val endLine = doc.getLineNumber((sel.selectionEnd - 1).coerceAtLeast(sel.selectionStart)) + 1
        val payload = mapOf(
            "ref" to "@${vFile.path}#L$startLine-L$endLine",
            "fileName" to vFile.name,
            "lines" to (endLine - startLine + 1),
        )
        browser?.cefBrowser?.executeJavaScript(
            "window.setSelectionIndicator(${gson.toJson(payload)});",
            "", 0,
        )
    }

    private fun clearSelectionIndicator() {
        browser?.cefBrowser?.executeJavaScript("window.setSelectionIndicator(null);", "", 0)
    }

    /** Fuzzy file/folder search for @-mention autocomplete, scoped to the project and capped for large trees. */
    private fun searchFilesJson(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "[]"
        val basePath = project.basePath ?: return "[]"
        val base = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return "[]"
        val settings = ClaudeSettings.getInstance()
        val changeListManager = if (settings.respectGitIgnore) ChangeListManager.getInstance(project) else null

        data class Match(val path: String, val name: String, val isDir: Boolean, val score: Int)

        val results = mutableListOf<Match>()
        var visited = 0

        fun walk(dir: VirtualFile) {
            for (child in dir.children) {
                if (visited++ > FILE_SEARCH_VISIT_CAP) return
                if (child.isDirectory && child.name in EXCLUDED_DIRS) continue
                if (!child.isDirectory && isDenyRuleFile(child.name)) continue
                if (changeListManager?.isIgnoredFile(child) == true) continue

                val score = fuzzyScore(q, child.name)
                if (score > 0) {
                    val rel = (VfsUtilCore.getRelativePath(child, base) ?: child.name) + if (child.isDirectory) "/" else ""
                    results += Match(rel, child.name, child.isDirectory, score)
                }
                if (child.isDirectory) walk(child)
            }
        }

        ReadAction.run<Exception> { walk(base) }

        val top = results
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.path.length })
            .take(FILE_SEARCH_RESULT_CAP)
        return gson.toJson(top.map { mapOf("path" to it.path, "name" to it.name, "isDir" to it.isDir) })
    }

    private fun fuzzyScore(query: String, name: String): Int {
        val q = query.lowercase()
        val n = name.lowercase()
        if (n.startsWith(q)) return 100 - n.length
        var qi = 0
        for (c in n) { if (qi < q.length && c == q[qi]) qi++ }
        return if (qi == q.length) 50 - n.length else 0
    }

    /**
     * `~/.claude/projects/<cwd with every non-alphanumeric character replaced by "-">/`.
     * Not just "/" — the CLI also collapses "_" (and presumably any other separator) to "-",
     * e.g. `/home/x/test_projects/foo` -> `-home-x-test-projects-foo`. Confirmed against real
     * session directories on disk, not guessed.
     */
    private fun sessionsDir(): File? {
        val basePath = project.basePath ?: return null
        val encoded = basePath.replace(Regex("[^a-zA-Z0-9]"), "-")
        return File(System.getProperty("user.home"), ".claude/projects/$encoded")
    }

    private fun sessionHistoryJson(metaStore: ConferSessionMetaStore): String {
        val dir = sessionsDir()
        if (dir == null || !dir.isDirectory) return "[]"
        val sessions = dir.listFiles { f -> f.extension == "jsonl" }
            ?.mapNotNull { file ->
                val id = file.nameWithoutExtension
                if (metaStore.isHidden(id)) return@mapNotNull null
                val preview = firstUserMessagePreview(file) ?: return@mapNotNull null
                val title = metaStore.customTitle(id) ?: preview
                mapOf("id" to id, "preview" to preview, "title" to title, "timestamp" to file.lastModified())
            }
            ?.sortedByDescending { it["timestamp"] as Long }
            ?: emptyList()
        return gson.toJson(sessions)
    }

    private fun firstUserMessagePreview(file: File): String? {
        try {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val json = try { JsonParser.parseString(line).asJsonObject } catch (_: Exception) { continue }
                    if (json.get("type")?.asString != "user") continue
                    val content = json.getAsJsonObject("message")?.get("content") ?: continue
                    val text = when {
                        content.isJsonPrimitive -> content.asString
                        content.isJsonArray -> content.asJsonArray
                            .firstOrNull { it.asJsonObject.get("type")?.asString == "text" }
                            ?.asJsonObject?.get("text")?.asString
                        else -> null
                    } ?: continue
                    if (text.isBlank()) continue
                    return text.take(140)
                }
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }

    /** Reads a past session's full transcript and reduces it to renderable user/assistant text turns (tool calls omitted for now — text-only replay). */
    private fun loadSessionTranscript(sessionId: String): String {
        val dir = sessionsDir() ?: return "[]"
        val file = File(dir, "$sessionId.jsonl")
        if (!file.isFile) return "[]"

        val messages = mutableListOf<Map<String, String>>()
        try {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val json = try { JsonParser.parseString(line).asJsonObject } catch (_: Exception) { continue }
                    val type = json.get("type")?.asString
                    if (type != "user" && type != "assistant") continue
                    val content = json.getAsJsonObject("message")?.get("content") ?: continue
                    val text = extractTextContent(content) ?: continue
                    if (text.isBlank()) continue
                    messages += mapOf("role" to type, "text" to text)
                }
            }
        } catch (_: Exception) {
            return "[]"
        }
        return gson.toJson(messages)
    }

    private fun extractTextContent(content: JsonElement): String? = when {
        content.isJsonPrimitive -> content.asString
        content.isJsonArray -> content.asJsonArray
            .mapNotNull { el ->
                val obj = el.asJsonObject
                obj.get("text")?.asString?.takeIf { obj.get("type")?.asString == "text" }
            }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
        else -> null
    }

    private fun openPlanDocument(markdown: String) {
        ApplicationManager.getApplication().invokeLater {
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension("md")
            val vFile = LightVirtualFile("Plan.md", fileType, markdown)
            planVFile = vFile
            originalPlanText = markdown
            FileEditorManager.getInstance(project).openFile(vFile, true)
        }
    }

    /**
     * Approve reads back whatever is currently in the Plan.md editor tab — if the user added
     * inline notes there before approving, those are fed back as feedback instead of the generic
     * "looks good" message, closing the "inline comments on the plan doc" gap without needing a
     * dedicated comment-thread UI: the plan document itself is the feedback surface.
     */
    private fun buildPlanApprovalText(): String {
        val currentText = planVFile?.let { FileDocumentManager.getInstance().getDocument(it)?.text }
            ?: originalPlanText
        return if (currentText.trim() != originalPlanText.trim()) {
            "I've reviewed and edited the plan document with inline notes — please read them and " +
                "incorporate this feedback before proceeding:\n\n$currentText"
        } else {
            "The plan looks good — please proceed."
        }
    }

    /** Each new tab gets an independent session — its own process and conversation history. */
    private fun openInEditorTab() {
        ApplicationManager.getApplication().invokeLater {
            val newSessionId = ClaudeSessionManager.newTabId()
            FileEditorManager.getInstance(project).openFile(ConferSessionVirtualFile(newSessionId), true)
        }
    }

    /** Each new window gets an independent session; the session is closed when the window closes. */
    private fun openInNewWindow() {
        ApplicationManager.getApplication().invokeLater {
            val newSessionId = ClaudeSessionManager.newTabId()
            val frame = JFrame("Confer")
            frame.contentPane.add(ConferChatPanel(project, newSessionId))
            frame.setSize(420, 640)
            frame.setLocationRelativeTo(null)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.addWindowListener(object : java.awt.event.WindowAdapter() {
                override fun windowClosed(e: java.awt.event.WindowEvent) {
                    project.service<ClaudeSessionManager>().closeSession(newSessionId)
                }
            })
            frame.isVisible = true
        }
    }

    private fun openSettings() {
        ApplicationManager.getApplication().invokeLater {
            ShowSettingsUtil.getInstance().editConfigurable(project, ClaudeConfigurable())
        }
    }

    private fun openMention(ref: String) {
        ApplicationManager.getApplication().invokeLater {
            val body = ref.removePrefix("@")
            val hashIdx = body.indexOf("#L")
            val path = if (hashIdx >= 0) body.substring(0, hashIdx) else body
            val startLine = if (hashIdx >= 0) {
                body.substring(hashIdx + 2).substringBefore('-').toIntOrNull()
            } else null

            val vFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return@invokeLater
            OpenFileDescriptor(project, vFile, (startLine ?: 1) - 1, 0).navigate(true)
        }
    }

    private fun buildPromptWithContext(text: String): String {
        val parts = mutableListOf<String>()
        if (pendingEditNotes.isNotEmpty()) {
            parts += pendingEditNotes
            pendingEditNotes.clear()
        }
        if (includeDiagnostics) {
            DiagnosticsCollector.collect(project)?.let { parts += it }
        }
        parts += text
        return parts.joinToString("\n\n")
    }

    private fun handleIncomingEvent(event: ClaudeEvent) {
        if (event is ClaudeEvent.ToolUse && event.toolName in WRITE_TOOLS) {
            interceptWriteTool(event)
        }
        if (event is ClaudeEvent.TurnEnd) {
            maybeGenerateTitle(event.sessionId)
        }
        toJs(event)?.let { browser?.cefBrowser?.executeJavaScript(it, "", 0) }
    }

    /** Best-effort, one-shot AI title for a fresh session, generated from its first prompt via `claude -p`. */
    private fun maybeGenerateTitle(sessionId: String?) {
        val id = sessionId ?: return
        val prompt = lastSentPrompt ?: return
        val metaStore = ConferSessionMetaStore.getInstance(project)
        if (!metaStore.claimTitleGeneration(id)) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val settings = ClaudeSettings.getInstance()
                val cmd = GeneralCommandLine(settings.claudeBinaryPath)
                    .withParameters(
                        "-p",
                        "Summarize the following request as a 4-6 word conversation title. " +
                            "Reply with only the title, no quotes, no trailing punctuation.\n\n$prompt",
                    )
                    .withWorkDirectory(project.basePath ?: System.getProperty("user.home"))
                    .withEnvironment(EnvironmentUtil.getEnvironmentMap())
                    .withCharset(Charsets.UTF_8)
                val output = ExecUtil.execAndGetOutput(cmd, 15_000)
                val title = output.stdout.trim().trim('"').take(60)
                if (title.isNotEmpty()) {
                    metaStore.rename(id, title)
                    ApplicationManager.getApplication().invokeLater {
                        browser?.cefBrowser?.executeJavaScript(
                            "window.receiveSessionHistory(${gson.toJson(sessionHistoryJson(metaStore))});", "", 0,
                        )
                    }
                }
            } catch (_: Exception) {
                // Best-effort — the preview-based title stays as the fallback.
            }
        }
    }

    private fun interceptWriteTool(event: ClaudeEvent.ToolUse) {
        val pending = buildPendingEdit(event) ?: return
        pendingEdits[event.id] = pending
        showDiff(event.toolName, pending)
    }

    private fun buildPendingEdit(event: ClaudeEvent.ToolUse): PendingEdit? {
        try {
            val input    = JsonParser.parseString(event.inputJson).asJsonObject
            val filePath = input.get("file_path")?.asString ?: return null

            val original: String
            val proposed: String
            when (event.toolName) {
                "Write", "write_file", "create_file" -> {
                    val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
                    original = vf?.contentsToByteArray()?.toString(Charsets.UTF_8) ?: ""
                    proposed = input.get("content")?.asString ?: return null
                }
                "Edit", "str_replace_editor" -> {
                    val vf  = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return null
                    original = vf.contentsToByteArray().toString(Charsets.UTF_8)
                    val old = input.get("old_string")?.asString ?: return null
                    val new = input.get("new_string")?.asString ?: ""
                    proposed = original.replace(old, new)
                }
                else -> return null
            }

            return PendingEdit(filePath, proposed, original, event.toolName)
        } catch (_: Exception) {
            return null
        }
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

    /**
     * "Keep": writes `content` (the proposed content, possibly edited by the user in the review card)
     * to the file. If the user changed it from what Claude proposed, queue a note so the next prompt
     * tells Claude what actually landed on disk — otherwise the CLI's own belief about the file
     * silently diverges from reality.
     */
    private fun applyPendingEdit(toolId: String, content: String) {
        val pending = pendingEdits.remove(toolId) ?: return
        if (content != pending.proposedContent) {
            pendingEditNotes += "Note: before accepting, I modified your proposed edit to " +
                "${pending.filePath}. The file now actually contains:\n\n```\n$content\n```"
        }
        writeFileContent(pending.filePath, content, "Claude: Keep Edit")
    }

    /** "Revert": the CLI already wrote `proposedContent` to disk by the time this card is shown, so this restores the pre-edit content we captured. */
    private fun revertPendingEdit(toolId: String) {
        val pending = pendingEdits.remove(toolId) ?: return
        writeFileContent(pending.filePath, pending.originalContent, "Claude: Revert Edit")
    }

    private fun writeFileContent(filePath: String, content: String, commandName: String) =
        writeFileContentOnEdt(project, filePath, content, commandName)

    private fun toJs(event: ClaudeEvent): String? = when (event) {
        is ClaudeEvent.Init -> jsCall(
            "init",
            gson.toJson(
                mapOf(
                    "model" to event.model,
                    "permissionMode" to event.permissionMode,
                    "mcpServers" to event.mcpServers.map { mapOf("name" to it.name, "status" to it.status) },
                ),
            ),
        )
        is ClaudeEvent.TextDelta     -> jsCall("text", event.text)
        is ClaudeEvent.ThinkingDelta -> jsCall("thinking", event.text)
        ClaudeEvent.BlockStop        -> jsCall("blockStop", "")
        is ClaudeEvent.ToolStart -> jsCall(
            "toolStart",
            gson.toJson(mapOf("name" to event.toolName, "toolId" to event.id)),
        )
        is ClaudeEvent.ToolUse  -> jsCall(
            "tool",
            gson.toJson(
                mapOf(
                    "name"   to event.toolName,
                    "input"  to event.inputJson,
                    "toolId" to event.id,
                    "isEdit" to (event.toolName in WRITE_TOOLS),
                    "proposedContent" to pendingEdits[event.id]?.proposedContent,
                    "filePath" to pendingEdits[event.id]?.filePath,
                ),
            ),
        )
        is ClaudeEvent.ToolResult -> jsCall(
            "toolResult",
            gson.toJson(
                mapOf(
                    "toolId"  to event.toolUseId,
                    "isError" to event.isError,
                    "content" to event.content,
                ),
            ),
        )
        is ClaudeEvent.TurnEnd  -> jsCall(
            "end",
            gson.toJson(
                mapOf(
                    "cost" to event.totalCostUsd,
                    "durationMs" to event.durationMs,
                    "contextTokensUsed" to event.contextTokensUsed,
                    "contextWindow" to event.contextWindow,
                ),
            ),
        )
        is ClaudeEvent.Error    -> jsCall("error", event.message)
        else                    -> null
    }

    private fun jsCall(type: String, payload: String): String =
        "window.receiveEvent(${gson.toJson(type)},${gson.toJson(payload)});"

    private fun loadSkills(): String {
        val dir = File(System.getProperty("user.home"), ".claude/skills")
        if (!dir.exists() || !dir.isDirectory) return "[]"
        val list = dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { skillDir ->
                val md = File(skillDir, "SKILL.md")
                if (!md.exists()) return@mapNotNull null
                val desc = md.readLines()
                    .firstOrNull { " - " in it }
                    ?.substringAfterLast(" - ")
                    ?.trim()
                    ?.take(80)
                    ?: skillDir.name
                mapOf("cmd" to "/${skillDir.name}", "desc" to desc)
            }
            ?: emptyList()
        return gson.toJson(list)
    }

    private fun loadHtml(): String {
        val html = javaClass.getResource("/com/github/towhid7667/confer/chat.html")
            ?.readText(Charsets.UTF_8)
            ?: return "<html><body style='color:red;font-family:sans-serif;padding:12px'>" +
                "Error: chat.html not found in plugin resources.</body></html>"
        return html.replaceFirst("<html lang=\"en\">", "<html lang=\"en\" data-theme=\"${currentThemeName()}\">")
    }
}

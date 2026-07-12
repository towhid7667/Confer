package com.github.towhid7667.confer.toolWindow

import com.github.towhid7667.confer.claude.ClaudeEvent
import com.github.towhid7667.confer.claude.ClaudeEventListener
import com.github.towhid7667.confer.claude.ClaudeService
import com.github.towhid7667.confer.diagnostics.DiagnosticsCollector
import com.github.towhid7667.confer.settings.ClaudeConfigurable
import com.github.towhid7667.confer.settings.ClaudeSettings
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
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
private const val FILE_SEARCH_VISIT_CAP = 4000
private const val FILE_SEARCH_RESULT_CAP = 30

private val WRITE_TOOLS = setOf("Write", "Edit", "write_file", "str_replace_editor", "create_file")

private data class PendingEdit(
    val filePath: String,
    val proposedContent: String,
    val originalContent: String,
    val toolName: String,
)

class ConferChatPanel(private val project: Project) : JBPanel<ConferChatPanel>(BorderLayout()) {

    private val gson         = Gson()
    private var browser: JBCefBrowser? = null
    private val pendingEdits = mutableMapOf<String, PendingEdit>()
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

        sendQuery.addHandler       { text   ->
            if (settings.autosave) {
                ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
            }
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
        newSessionQuery.addHandler  { _      -> svc.stop(); pendingEdits.clear();             null }
        editorTabQuery.addHandler   { _      -> openInEditorTab();                           null }
        newWindowQuery.addHandler   { _      -> openInNewWindow();                           null }
        settingsQuery.addHandler    { _      -> openSettings();                              null }
        sessionHistoryQuery.addHandler { _ ->
            browser?.cefBrowser?.executeJavaScript(
                "window.receiveSessionHistory(${gson.toJson(sessionHistoryJson())});", "", 0,
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
        if (vFile == null || !sel.hasSelection()) {
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

    private fun sessionHistoryJson(): String {
        val dir = sessionsDir()
        if (dir == null || !dir.isDirectory) return "[]"
        val sessions = dir.listFiles { f -> f.extension == "jsonl" }
            ?.mapNotNull { file ->
                val preview = firstUserMessagePreview(file) ?: return@mapNotNull null
                mapOf("id" to file.nameWithoutExtension, "preview" to preview, "timestamp" to file.lastModified())
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
            FileEditorManager.getInstance(project).openFile(vFile, true)
        }
    }

    private fun openInEditorTab() {
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(ConferSessionVirtualFile(), true)
        }
    }

    private fun openInNewWindow() {
        ApplicationManager.getApplication().invokeLater {
            val frame = JFrame("Confer")
            frame.contentPane.add(ConferChatPanel(project))
            frame.setSize(420, 640)
            frame.setLocationRelativeTo(null)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
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

    /** "Keep": writes `content` (the proposed content, possibly edited by the user in the review card) to the file. */
    private fun applyPendingEdit(toolId: String, content: String) {
        val pending = pendingEdits.remove(toolId) ?: return
        writeFileContent(pending.filePath, content, "Claude: Keep Edit")
    }

    /** "Revert": the CLI already wrote `proposedContent` to disk by the time this card is shown, so this restores the pre-edit content we captured. */
    private fun revertPendingEdit(toolId: String) {
        val pending = pendingEdits.remove(toolId) ?: return
        writeFileContent(pending.filePath, pending.originalContent, "Claude: Revert Edit")
    }

    /** JCEF query handlers run on a background thread, not the EDT — WriteCommandAction requires the EDT, so this must hop over explicitly. */
    private fun writeFileContent(filePath: String, content: String, commandName: String) {
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

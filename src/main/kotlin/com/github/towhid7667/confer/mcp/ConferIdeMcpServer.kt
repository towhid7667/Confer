package com.github.towhid7667.confer.mcp

import com.github.towhid7667.confer.diagnostics.DiagnosticsCollector
import com.github.towhid7667.confer.util.writeFileContentOnEdt
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.java_websocket.WebSocket
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val MCP_PROTOCOL_VERSION = "2024-11-05"
private const val AUTH_HEADER = "X-Claude-Code-Ide-Authorization"

/**
 * The IDE side of Claude Code's IDE integration: a WebSocket server the `claude` CLI connects to
 * (discovered via [IdeLockFile]) speaking MCP JSON-RPC. Exposes `getDiagnostics` and the
 * `openDiff`/`close_tab` pre-execution diff-approval flow. Protocol details (transport, auth
 * header, tool/RPC names, param names, result codes) were verified against a live lock file and
 * the installed `claude` binary's own string constants — not guessed.
 */
class ConferIdeMcpServer(
    private val project: Project,
    private val authToken: String,
    port: Int,
) : WebSocketServer(InetSocketAddress(InetAddress.getLoopbackAddress(), port)) {

    private val gson = Gson()
    private val startLatch = CountDownLatch(1)
    private val pendingDiffTabs = ConcurrentHashMap<String, PendingDiff>()

    private class PendingDiff(
        val requestId: JsonElement,
        val conn: WebSocket,
        val filePath: String,
        val content: String,
        var notification: Notification? = null,
        val resolved: AtomicBoolean = AtomicBoolean(false),
    )

    /** Blocks briefly until the server has actually bound and is accepting connections. */
    fun awaitStarted(timeoutSeconds: Long = 5) {
        startLatch.await(timeoutSeconds, TimeUnit.SECONDS)
    }

    override fun onStart() {
        connectionLostTimeout = 60
        startLatch.countDown()
    }

    /** Rejects the WebSocket upgrade at the handshake level if the auth token doesn't match. */
    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket,
        draft: org.java_websocket.drafts.Draft,
        request: ClientHandshake,
    ): ServerHandshakeBuilder {
        if (request.getFieldValue(AUTH_HEADER) != authToken) {
            throw InvalidDataException(CloseFrame.POLICY_VALIDATION, "unauthorized")
        }
        return super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            handleMessage(conn, JsonParser.parseString(message).asJsonObject)
        } catch (e: Exception) {
            thisLogger().debug("Confer IDE MCP server: malformed message ignored", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        thisLogger().warn("Confer IDE MCP server error", ex)
    }

    private fun handleMessage(conn: WebSocket, req: JsonObject) {
        val method = req.get("method")?.asString ?: return
        val id = req.get("id")
        val params = req.getAsJsonObject("params")

        when (method) {
            "initialize" -> respond(conn, id, initializeResult())
            "notifications/initialized" -> {}
            "tools/list" -> respond(conn, id, toolsListResult())
            "resources/list" -> respond(conn, id, mapOf("resources" to emptyList<Any>()))
            "prompts/list" -> respond(conn, id, mapOf("prompts" to emptyList<Any>()))
            "tools/call" -> handleToolCall(conn, id, params)
            else -> if (id != null) respondError(conn, id, -32601, "Method not found: $method")
        }
    }

    private fun handleToolCall(conn: WebSocket, id: JsonElement?, params: JsonObject?) {
        if (id == null || params == null) return
        val name = params.get("name")?.asString
        val args = params.getAsJsonObject("arguments") ?: JsonObject()
        when (name) {
            "getDiagnostics" -> handleGetDiagnostics(conn, id)
            "openDiff" -> handleOpenDiff(conn, id, args)
            "close_tab" -> handleCloseTab(conn, id, args)
            else -> respondError(conn, id, -32602, "Unknown tool: $name")
        }
    }

    private fun handleGetDiagnostics(conn: WebSocket, id: JsonElement) {
        ApplicationManager.getApplication().invokeLater {
            val text = DiagnosticsCollector.collect(project) ?: "No diagnostics found for the active file."
            respondToolResult(conn, id, text)
        }
    }

    private fun handleOpenDiff(conn: WebSocket, id: JsonElement, args: JsonObject) {
        val newPath = args.get("new_file_path")?.asString
        if (newPath == null) {
            respondError(conn, id, -32602, "new_file_path is required")
            return
        }
        val newContent = args.get("new_file_contents")?.asString ?: ""
        val oldPath = args.get("old_file_path")?.asString ?: newPath
        val tabName = args.get("tab_name")?.asString ?: newPath.substringAfterLast('/')

        ApplicationManager.getApplication().invokeLater {
            val vFile = LocalFileSystem.getInstance().findFileByPath(oldPath)
            val oldContent = vFile?.contentsToByteArray()?.toString(Charsets.UTF_8) ?: ""
            val fileType = vFile?.fileType ?: PlainTextFileType.INSTANCE

            val request = SimpleDiffRequest(
                "Claude wants to edit: $tabName",
                DiffContentFactory.getInstance().create(oldContent, fileType),
                DiffContentFactory.getInstance().create(newContent, fileType),
                "Current",
                "Proposed by Claude",
            )
            DiffManager.getInstance().showDiff(project, request)

            val pending = PendingDiff(id, conn, newPath, newContent)
            pendingDiffTabs[tabName] = pending

            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup("Confer")
                .createNotification("Claude wants to edit $tabName", NotificationType.INFORMATION)
            notification.addAction(object : NotificationAction("Accept") {
                override fun actionPerformed(e: AnActionEvent, n: Notification) {
                    resolveDiff(tabName, "FILE_SAVED", writeFile = true)
                }
            })
            notification.addAction(object : NotificationAction("Reject") {
                override fun actionPerformed(e: AnActionEvent, n: Notification) {
                    resolveDiff(tabName, "DIFF_REJECTED", writeFile = false)
                }
            })
            pending.notification = notification
            notification.notify(project)
        }
    }

    private fun handleCloseTab(conn: WebSocket, id: JsonElement, args: JsonObject) {
        val tabName = args.get("tab_name")?.asString
        if (tabName == null) {
            respondError(conn, id, -32602, "tab_name is required")
            return
        }
        resolveDiff(tabName, "TAB_CLOSED", writeFile = false)
        // close_tab always acknowledges, even if the tab had already been resolved by Accept/Reject.
        respondToolResult(conn, id, "TAB_CLOSED")
    }

    /** Only the first resolution (Accept, Reject, or close_tab) wins; later ones are no-ops. */
    private fun resolveDiff(tabName: String, resultText: String, writeFile: Boolean) {
        val pending = pendingDiffTabs.remove(tabName) ?: return
        if (!pending.resolved.compareAndSet(false, true)) return
        if (writeFile) {
            writeFileContentOnEdt(project, pending.filePath, pending.content, "Claude: Apply Edit ($tabName)")
        }
        respondToolResult(pending.conn, pending.requestId, resultText)
        ApplicationManager.getApplication().invokeLater { pending.notification?.expire() }
    }

    private fun initializeResult(): Map<String, Any> = mapOf(
        "protocolVersion" to MCP_PROTOCOL_VERSION,
        "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
        "serverInfo" to mapOf("name" to "confer-ide", "version" to "1.0.0"),
    )

    private fun toolsListResult(): Map<String, Any> = mapOf(
        "tools" to listOf(
            mapOf(
                "name" to "getDiagnostics",
                "description" to "Get language diagnostics (errors/warnings) from the IDE for the active file.",
                "inputSchema" to mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
            ),
            mapOf(
                "name" to "openDiff",
                "description" to "Show a diff in the IDE for the user to review, and wait for them to accept or reject it.",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "old_file_path" to mapOf("type" to "string"),
                        "new_file_path" to mapOf("type" to "string"),
                        "new_file_contents" to mapOf("type" to "string"),
                        "tab_name" to mapOf("type" to "string"),
                    ),
                    "required" to listOf("new_file_path", "new_file_contents", "tab_name"),
                ),
            ),
            mapOf(
                "name" to "close_tab",
                "description" to "Close a previously opened diff tab in the IDE.",
                "inputSchema" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("tab_name" to mapOf("type" to "string")),
                    "required" to listOf("tab_name"),
                ),
            ),
        ),
    )

    private fun respondToolResult(conn: WebSocket, id: JsonElement, text: String) =
        respond(conn, id, mapOf("content" to listOf(mapOf("type" to "text", "text" to text))))

    private fun respond(conn: WebSocket, id: JsonElement?, result: Any) {
        val obj = JsonObject()
        obj.addProperty("jsonrpc", "2.0")
        obj.add("id", id ?: JsonNull.INSTANCE)
        obj.add("result", gson.toJsonTree(result))
        conn.send(gson.toJson(obj))
    }

    private fun respondError(conn: WebSocket, id: JsonElement?, code: Int, message: String) {
        val obj = JsonObject()
        obj.addProperty("jsonrpc", "2.0")
        obj.add("id", id ?: JsonNull.INSTANCE)
        val err = JsonObject()
        err.addProperty("code", code)
        err.addProperty("message", message)
        obj.add("error", err)
        conn.send(gson.toJson(obj))
    }
}

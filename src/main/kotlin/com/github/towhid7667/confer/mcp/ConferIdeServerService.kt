package com.github.towhid7667.confer.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64

/**
 * Owns the project's local IDE MCP server: starts it lazily on first use, writes the discovery
 * lock file the `claude` CLI scans for, and tears both down when the project closes.
 */
@Service(Service.Level.PROJECT)
class ConferIdeServerService(private val project: Project) : Disposable {

    private var server: ConferIdeMcpServer? = null
    private var lockFile: File? = null

    @Synchronized
    fun ensureStarted(): Boolean {
        if (server != null) return true
        val workspaceFolder = project.basePath ?: return false

        return try {
            val port = findFreePort()
            val token = generateToken()
            val ideName = ApplicationNamesInfo.getInstance().fullProductName

            val s = ConferIdeMcpServer(project, token, port)
            s.start()
            s.awaitStarted()

            lockFile = IdeLockFile.write(port, token, workspaceFolder, ideName)
            server = s
            true
        } catch (e: Exception) {
            thisLogger().warn("Failed to start Confer IDE MCP server", e)
            false
        }
    }

    private fun findFreePort(): Int =
        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun dispose() {
        try {
            server?.stop(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        server = null
        lockFile?.let { IdeLockFile.delete(it) }
        lockFile = null
    }
}

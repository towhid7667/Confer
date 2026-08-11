package com.github.towhid7667.confer.mcp

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Writes/removes the discovery file the `claude` CLI scans for at `~/.claude/ide/<port>.lock`.
 * Schema and permissions verified against a live lock file written by the real IDE integration
 * on this machine — not guessed: `{pid, workspaceFolders, ideName, transport, runningInWindows,
 * authToken}`, directory mode 0700, file mode 0600.
 */
object IdeLockFile {

    private val gson = Gson()

    fun write(port: Int, authToken: String, workspaceFolder: String, ideName: String): File {
        val dir = File(System.getProperty("user.home"), ".claude/ide")
        dir.mkdirs()
        setPosixPermissionsIfSupported(dir, "rwx------")

        val file = File(dir, "$port.lock")
        val payload = linkedMapOf(
            "pid" to ProcessHandle.current().pid(),
            "workspaceFolders" to listOf(workspaceFolder),
            "ideName" to ideName,
            "transport" to "ws",
            "runningInWindows" to (System.getProperty("os.name")?.lowercase()?.contains("windows") == true),
            "authToken" to authToken,
        )
        file.writeText(gson.toJson(payload), Charsets.UTF_8)
        setPosixPermissionsIfSupported(file, "rw-------")
        return file
    }

    fun delete(file: File) {
        file.delete()
    }

    private fun setPosixPermissionsIfSupported(file: File, posix: String) {
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(posix))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows) — best effort only.
        }
    }
}

package com.github.towhid7667.confer.mcp

import com.google.gson.JsonParser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val AUTH_HEADER = "X-Claude-Code-Ide-Authorization"
private const val TEST_TOKEN = "test-token-123"

/**
 * Exercises [ConferIdeMcpServer] over a real WebSocket connection (using the same client/server
 * library pair), verifying the auth handshake and MCP JSON-RPC dispatch actually work end-to-end
 * — not just that the code compiles.
 */
class ConferIdeMcpServerTest : BasePlatformTestCase() {

    private fun findFreePort(): Int = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }

    /**
     * [ConferIdeMcpServer] answers some tool calls via `ApplicationManager.invokeLater` (EDT),
     * which only runs if the EDT's event queue is pumped — real running IDEs do this
     * continuously, but this test's synchronous wait doesn't, so we pump it ourselves while
     * polling for the WebSocket response.
     */
    private fun pollWithEdtPump(queue: ArrayBlockingQueue<String>, timeoutSeconds: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            queue.poll()?.let { return it }
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        return queue.poll()
    }

    private class RecordingClient(uri: URI, headers: Map<String, String>) : WebSocketClient(uri, headers) {
        val messages = ArrayBlockingQueue<String>(16)
        val closed = ArrayBlockingQueue<Int>(1)
        override fun onOpen(handshakedata: ServerHandshake?) {}
        override fun onMessage(message: String) { messages.put(message) }
        override fun onClose(code: Int, reason: String?, remote: Boolean) { closed.offer(code) }
        override fun onError(ex: Exception?) {}
    }

    fun testUnauthorizedConnectionIsRejected() {
        val port = findFreePort()
        val server = ConferIdeMcpServer(project, TEST_TOKEN, port)
        server.start()
        server.awaitStarted()
        try {
            val client = RecordingClient(URI("ws://127.0.0.1:$port"), mapOf(AUTH_HEADER to "wrong-token"))
            client.connectBlocking(5, TimeUnit.SECONDS)
            assertFalse("connection with a wrong auth token must not be accepted", client.isOpen)
        } finally {
            server.stop(1000)
        }
    }

    fun testInitializeAndToolsListOverRealSocket() {
        val port = findFreePort()
        val server = ConferIdeMcpServer(project, TEST_TOKEN, port)
        server.start()
        server.awaitStarted()
        try {
            val client = RecordingClient(URI("ws://127.0.0.1:$port"), mapOf(AUTH_HEADER to TEST_TOKEN))
            assertTrue("connection with the correct auth token must be accepted", client.connectBlocking(5, TimeUnit.SECONDS))

            client.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05"}}""")
            val initResponse = JsonParser.parseString(client.messages.poll(5, TimeUnit.SECONDS)).asJsonObject
            assertEquals(1, initResponse.get("id").asInt)
            assertEquals("2024-11-05", initResponse.getAsJsonObject("result").get("protocolVersion").asString)

            client.send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            val toolsResponse = JsonParser.parseString(client.messages.poll(5, TimeUnit.SECONDS)).asJsonObject
            val toolNames = toolsResponse.getAsJsonObject("result").getAsJsonArray("tools")
                .map { it.asJsonObject.get("name").asString }
                .toSet()
            assertEquals(setOf("getDiagnostics", "openDiff", "close_tab"), toolNames)

            client.closeBlocking()
        } finally {
            server.stop(1000)
        }
    }

    fun testGetDiagnosticsToolCallReturnsTextContent() {
        val port = findFreePort()
        val server = ConferIdeMcpServer(project, TEST_TOKEN, port)
        server.start()
        server.awaitStarted()
        try {
            val client = RecordingClient(URI("ws://127.0.0.1:$port"), mapOf(AUTH_HEADER to TEST_TOKEN))
            client.connectBlocking(5, TimeUnit.SECONDS)

            client.send(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getDiagnostics","arguments":{}}}""",
            )
            val response = JsonParser.parseString(pollWithEdtPump(client.messages, 5)).asJsonObject
            val content = response.getAsJsonObject("result").getAsJsonArray("content")
            assertEquals("text", content[0].asJsonObject.get("type").asString)
            assertTrue(content[0].asJsonObject.get("text").asString.isNotEmpty())

            client.closeBlocking()
        } finally {
            server.stop(1000)
        }
    }
}

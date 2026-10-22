package app.cmuxpocket

import androidx.lifecycle.viewModelScope
import app.cmuxpocket.transport.ConnectionStatus
import app.cmuxpocket.ui.TerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundRecoveryTest {
    private lateinit var server: MockWebServer
    private val connections = AtomicInteger()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private val workspaceTree = """
        {"workspaces":[{"id":"ws-main","key":"ws-main","name":"cmux-main","order":0,
          "active_on_host":true,"surfaces":[{"id":"surf-1","type":"terminal","title":"zsh"}]}]}
    """.trimIndent()

    /**
     * One Gateway socket. [answerRpc] lets a case go silent after bootstrap, which is what a
     * socket suspended in the background looks like from the app's side.
     */
    private fun gatewaySocket(
        onBootstrapComplete: () -> Unit = {},
        answerRpc: () -> Boolean = { true },
        onOpenSocket: (WebSocket) -> Unit = {},
    ): MockResponse {
        var sawWorkspaceList = false
        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                connections.incrementAndGet()
                onOpenSocket(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = Json.parseToJsonElement(text).jsonObject
                if (request["type"]?.jsonPrimitive?.content == "auth") {
                    webSocket.send("""{"type":"auth_ok"}""")
                    return
                }
                val id = request["id"] ?: return
                if (!answerRpc()) return
                val method = request["method"]?.jsonPrimitive?.content
                val result = when (method) {
                    "mobile.host.status" -> buildJsonObject {
                        put("mac_display_name", "cmux Host")
                        put("gateway_version", "0.2.0")
                        put("protocol_version", "2.0.0")
                        put("capabilities", buildJsonArray { })
                        put(
                            "cmux_status",
                            buildJsonObject {
                                put("installed", true)
                                put("running", true)
                                put("can_wake", true)
                            }
                        )
                    }
                    "mobile.workspace.list" -> Json.parseToJsonElement(workspaceTree).jsonObject
                    else -> buildJsonObject { }
                }
                webSocket.send(
                    buildJsonObject {
                        put("id", id)
                        put("result", result)
                    }.toString()
                )
                if (method == "mobile.workspace.list" && !sawWorkspaceList) {
                    sawWorkspaceList = true
                    onBootstrapComplete()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        })
    }

    private fun TerminalViewModel.connectToServer() {
        connect("127.0.0.1", server.port, "test-only-token")
    }

    @Test
    fun healthyForegroundProbeDoesNotReconnect() = runBlocking {
        server.enqueue(gatewaySocket())
        server.start()
        val model = TerminalViewModel()
        try {
            model.connectToServer()
            withTimeout(8_000) {
                model.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
            withTimeout(8_000) { model.workspaces.first { it.isNotEmpty() } }

            model.onAppForegrounded()
            delay(500)

            assertEquals(1, connections.get())
            assertEquals(ConnectionStatus.CONNECTED, model.connectionStatus.value)
        } finally {
            model.disconnect()
            model.viewModelScope.cancel()
        }
    }

    @Test
    fun foregroundProbeReplacesASilentSocket() = runBlocking {
        var silent = false
        server.enqueue(
            gatewaySocket(
                onBootstrapComplete = { silent = true },
                answerRpc = { !silent },
            )
        )
        server.enqueue(gatewaySocket())
        server.start()
        val model = TerminalViewModel()
        try {
            model.connectToServer()
            withTimeout(8_000) {
                model.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
            withTimeout(8_000) { model.workspaces.first { it.isNotEmpty() } }

            // The socket is dead but its status still says CONNECTED, as after a frozen process.
            model.onAppForegrounded()

            withTimeout(12_000) {
                while (connections.get() < 2) delay(50)
            }
            withTimeout(8_000) {
                model.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
            assertEquals(2, connections.get())
        } finally {
            model.disconnect()
            model.viewModelScope.cancel()
        }
    }

    @Test
    fun foregroundReconnectsBeforeTheScheduledBackoff() = runBlocking {
        var activeSocket: WebSocket? = null
        server.enqueue(gatewaySocket(onOpenSocket = { activeSocket = it }))
        server.enqueue(gatewaySocket())
        server.start()
        val model = TerminalViewModel()
        try {
            model.connectToServer()
            withTimeout(8_000) {
                model.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
            withTimeout(8_000) { model.workspaces.first { it.isNotEmpty() } }

            // The Gateway disappears while the app is in the background: the client schedules a
            // 1s retry, which the foreground event must not wait for.
            activeSocket?.close(1000, "gateway stopping")
            withTimeout(8_000) {
                model.connectionStatus.first { it != ConnectionStatus.CONNECTED }
            }

            val startedAt = System.nanoTime()
            model.onAppForegrounded()
            withTimeout(900) {
                model.connectionStatus.first { it == ConnectionStatus.CONNECTED }
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue("foreground recovery took ${elapsedMs}ms", elapsedMs < 900)
            assertEquals(2, connections.get())
        } finally {
            model.disconnect()
            model.viewModelScope.cancel()
        }
    }
}

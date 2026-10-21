package app.cmuxpocket

import androidx.lifecycle.viewModelScope
import app.cmuxpocket.ui.ConnectionStep
import app.cmuxpocket.ui.TerminalViewModel
import app.cmuxpocket.ui.verifyGatewayConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionReadinessTest {
    @Test
    fun missingWorkspacesCannotPassAsAnEmptySuccessfulHandshake() = runBlocking {
        var lastStep: ConnectionStep? = null
        try {
            verifyGatewayConnection(
                callRpc = { Json.parseToJsonElement("{}") },
                subscribe = {},
                onStep = { lastStep = it },
            )
            fail("Missing workspaces must fail")
        } catch (_: IllegalArgumentException) {
            assertEquals(ConnectionStep.WORKSPACES, lastStep)
        }
    }

    @Test
    fun authenticatedSocketWithDeniedCmuxShowsTheFailedWorkspaceStep() = runBlocking {
        withGateway(workspaceError = true) { model ->
            val result = withTimeout(8_000) { model.connectionReadiness.first { it.problem != null } }
            assertFalse(result.ready)
            assertEquals(ConnectionStep.WORKSPACES, result.step)
            assertTrue(result.summary.contains("cmux workspaces are unavailable"))
            assertTrue(result.help.contains("cmux terminal"))
        }
    }

    @Test
    fun unansweredWorkspaceRequestShowsFailureInsteadOfSpinningForever() = runBlocking {
        withGateway(ignoreWorkspaces = true) { model ->
            val result = withTimeout(8_000) { model.connectionReadiness.first { it.problem != null } }
            assertEquals(ConnectionStep.WORKSPACES, result.step)
            assertFalse(result.ready)
        }
    }

    @Test
    fun subscribingBeforeBaselinePreventsADiscoveryGap() = runBlocking {
        val operations = mutableListOf<String>()
        verifyGatewayConnection(
            callRpc = { method ->
                operations.add(method)
                Json.parseToJsonElement(if (method == "mobile.workspace.list") "{\"workspaces\":[]}" else "{}")
            },
            subscribe = { operations.add("subscribe") },
            onStep = {},
        )
        assertEquals(listOf("mobile.host.status", "subscribe", "mobile.workspace.list"), operations)
    }

    @Test
    fun successfulHandshakeReportsCountsAndAcceptsAnEmptyCmux() = runBlocking {
        withGateway { model ->
            val result = withTimeout(8_000) { model.connectionReadiness.first { it.ready } }
            assertEquals(0, result.workspaceCount)
            assertEquals(0, result.terminalCount)
            assertTrue(result.help.contains("Open or create a terminal"))
        }
    }

    @Test
    fun rejectedTokenShowsAuthenticationFailureAndStopsRetrying() = runBlocking {
        withGateway(rejectToken = true) { model ->
            val result = withTimeout(8_000) { model.connectionReadiness.first { it.problem != null } }
            assertEquals(ConnectionStep.AUTHENTICATION, result.step)
            assertEquals("Gateway rejected the token", result.problem)
            assertFalse(result.ready)
        }
    }

    private suspend fun withGateway(
        workspaceError: Boolean = false,
        rejectToken: Boolean = false,
        ignoreWorkspaces: Boolean = false,
        check: suspend (TerminalViewModel) -> Unit,
    ) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = Json.parseToJsonElement(text).jsonObject
                if (request["type"]?.jsonPrimitive?.content == "auth") {
                    webSocket.send(if (rejectToken) "{\"type\":\"auth_error\"}" else "{\"type\":\"auth_ok\"}")
                    return
                }
                val method = request["method"]?.jsonPrimitive?.content
                if (ignoreWorkspaces && method == "mobile.workspace.list") return
                val result: JsonElement = Json.parseToJsonElement(
                    when (method) {
                        "mobile.workspace.list" -> "{\"workspaces\":[]}"
                        else -> "{}"
                    }
                )
                webSocket.send(buildJsonObject {
                    put("id", request["id"]!!)
                    if (workspaceError && method == "mobile.workspace.list") {
                        put("error", buildJsonObject { put("code", -32000); put("message", "cmux tree failed") })
                    } else {
                        put("result", result)
                    }
                }.toString())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        server.start()
        val model = TerminalViewModel()
        try {
            model.connect("127.0.0.1", server.port, "test-only-token")
            check(model)
        } finally {
            model.disconnect()
            model.viewModelScope.cancel()
            server.shutdown()
            Dispatchers.resetMain()
        }
    }
}

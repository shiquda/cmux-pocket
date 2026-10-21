package app.cmuxpocket

import androidx.lifecycle.viewModelScope
import app.cmuxpocket.ui.CmuxWakeState
import app.cmuxpocket.ui.TerminalViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CmuxWakeFlowTest {
    private lateinit var server: MockWebServer

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

    @Test
    fun stoppedCmuxShowsWakeStateAndResumesAfterTimedSync() = runBlocking {
        var cmuxRunning = false
        val statusJson = {
            buildJsonObject {
                put("mac_display_name", "cmux Host")
                put("gateway_version", "0.2.0")
                put("protocol_version", "2.0.0")
                put(
                    "capabilities",
                    kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("mobile.host.wake_cmux.v1"))
                    },
                )
                put(
                    "cmux_status",
                    buildJsonObject {
                        put("installed", true)
                        put("running", cmuxRunning)
                        put("can_wake", true)
                    },
                )
            }
        }
        val workspaceTree = """
            {"workspaces":[{"id":"ws-main","key":"ws-main","name":"cmux-main","order":0,
              "active_on_host":true,"surfaces":[{"id":"surf-1","type":"terminal","title":"zsh"}]}]}
        """.trimIndent()

        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val request = Json.parseToJsonElement(text).jsonObject
                if (request["type"]?.jsonPrimitive?.content == "auth") {
                    webSocket.send("""{"type":"auth_ok"}""")
                    return
                }
                val method = request["method"]?.jsonPrimitive?.content
                val id = request["id"]!!
                when (method) {
                    "mobile.events.subscribe" -> webSocket.send(
                        buildJsonObject {
                            put("id", id)
                            put("result", buildJsonObject { })
                        }.toString()
                    )
                    "mobile.workspace.list" -> {
                        if (!cmuxRunning) {
                            webSocket.send(
                                buildJsonObject {
                                    put("id", id)
                                    put("error", buildJsonObject {
                                        put("code", -32000)
                                        put("message", "Socket not found")
                                    })
                                }.toString()
                            )
                        } else {
                            webSocket.send(
                                buildJsonObject {
                                    put("id", id)
                                    put("result", Json.parseToJsonElement(workspaceTree).jsonObject)
                                }.toString()
                            )
                        }
                    }
                    "mobile.host.wake_cmux" -> {
                        cmuxRunning = true
                        webSocket.send(
                            buildJsonObject {
                                put("id", id)
                                put("result", buildJsonObject {
                                    put("success", true)
                                    put("status", "launching")
                                })
                            }.toString()
                        )
                        // The Gateway broadcasts the sync once cmux is answering.
                        webSocket.send(
                            buildJsonObject {
                                put("event", "workspace.tree")
                                put("data", buildJsonObject {
                                    put("action", "sync")
                                    put("workspaces", Json.parseToJsonElement(workspaceTree).jsonObject["workspaces"]!!)
                                })
                            }.toString()
                        )
                    }
                    "mobile.host.status" -> webSocket.send(
                        buildJsonObject {
                            put("id", id)
                            put("result", statusJson())
                        }.toString()
                    )
                    "mobile.surface.focus" -> webSocket.send(
                        buildJsonObject {
                            put("id", id)
                            put("result", buildJsonObject { put("status", "ok") })
                        }.toString()
                    )
                    else -> webSocket.send(
                        buildJsonObject {
                            put("id", id)
                            put("result", buildJsonObject { })
                        }.toString()
                    )
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        server.start()

        val model = TerminalViewModel()
        try {
            model.connect("127.0.0.1", server.port, "test-only-token")

            val stopped = withTimeout(8_000) {
                model.connectionReadiness.first { it.ready && it.hostStatus?.cmuxRunning == false }
            }
            assertTrue(stopped.ready)
            assertNull(stopped.problem)
            assertEquals("Mac online · cmux not running", stopped.summary)
            assertTrue(model.workspaces.value.isEmpty())
            assertEquals(CmuxWakeState.IDLE, model.wakeState.value)

            model.wakeCmux()
            withTimeout(8_000) {
                model.workspaces.first { it.isNotEmpty() }
            }
            withTimeout(8_000) {
                model.wakeState.first { it == CmuxWakeState.IDLE }
            }
            val readiness = withTimeout(8_000) {
                model.connectionReadiness.first { it.hostStatus?.cmuxRunning == true }
            }
            assertEquals(1, readiness.workspaceCount)
            assertFalse(readiness.cmuxStopped)
        } finally {
            model.disconnect()
            model.viewModelScope.cancel()
        }
    }
}

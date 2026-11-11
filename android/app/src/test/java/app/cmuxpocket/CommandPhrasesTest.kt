package app.cmuxpocket

import androidx.lifecycle.viewModelScope
import app.cmuxpocket.ui.CommandPhrase
import app.cmuxpocket.ui.TerminalViewModel
import app.cmuxpocket.ui.decodeCommandPhrases
import app.cmuxpocket.ui.deleteCommandPhrase
import app.cmuxpocket.ui.encodeCommandPhrases
import app.cmuxpocket.ui.moveCommandPhrase
import app.cmuxpocket.ui.upsertCommandPhrase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.Collections

class CommandPhrasesTest {

    @Test
    fun roundTripPreservesTrailingWhitespaceMultilineAndOrder() {
        val phrases = listOf(
            CommandPhrase(id = "1", title = "Continue", content = "继续推进\n先跑单测再总结", autoEnter = true),
            CommandPhrase(id = "2", title = "", content = "git commit -m \"", autoEnter = false),
            CommandPhrase(id = "3", title = "Go parent", content = "cd ", autoEnter = false)
        )
        val encoded = encodeCommandPhrases(phrases)
        val decoded = decodeCommandPhrases(encoded)
        assertEquals(phrases, decoded)
    }

    @Test
    fun decodeHandlesNullBlankCorruptAndBlankContentEntries() {
        assertTrue(decodeCommandPhrases(null).isEmpty())
        assertTrue(decodeCommandPhrases("   ").isEmpty())
        assertTrue(decodeCommandPhrases("{not valid json").isEmpty())

        val jsonWithBlankAndUnknownField = """
            [
              {"id":"a","title":"Good","content":"git status","autoEnter":true,"extraField":42},
              {"id":"b","title":"Empty","content":"   ","autoEnter":true}
            ]
        """.trimIndent()
        val decoded = decodeCommandPhrases(jsonWithBlankAndUnknownField)
        assertEquals(1, decoded.size)
        assertEquals("a", decoded[0].id)
        assertEquals("git status", decoded[0].content)
    }

    @Test
    fun upsertUpdatesInPlaceTrimsTitleAndPreservesContentWhitespace() {
        val first = CommandPhrase(id = "1", title = "Status", content = "git status", autoEnter = true)
        val second = CommandPhrase(id = "2", title = "Commit", content = "git commit -m \"", autoEnter = false)
        val initial = listOf(first, second)

        val editedFirst = first.copy(title = "  Git Status  ", content = "git status -sb ", autoEnter = false)
        val afterEdit = upsertCommandPhrase(initial, editedFirst)
        assertEquals(2, afterEdit.size)
        assertEquals("Git Status", afterEdit[0].title)
        assertEquals("git status -sb ", afterEdit[0].content)
        assertEquals(false, afterEdit[0].autoEnter)
        assertEquals("2", afterEdit[1].id)

        val afterBlankRejected = upsertCommandPhrase(afterEdit, CommandPhrase(id = "3", title = "Bad", content = " \n "))
        assertEquals(afterEdit, afterBlankRejected)
    }

    @Test
    fun deleteAndMoveRespectBoundaries() {
        val a = CommandPhrase(id = "a", title = "A", content = "a")
        val b = CommandPhrase(id = "b", title = "B", content = "b")
        val c = CommandPhrase(id = "c", title = "C", content = "c")
        val list = listOf(a, b, c)

        assertEquals(listOf(b, a, c), moveCommandPhrase(list, "b", -1))
        assertEquals(listOf(a, c, b), moveCommandPhrase(list, "b", +1))
        // Boundary clamps are no-ops
        assertEquals(list, moveCommandPhrase(list, "a", -1))
        assertEquals(list, moveCommandPhrase(list, "c", +1))
        assertEquals(list, moveCommandPhrase(list, "missing", -1))
        assertEquals(list, moveCommandPhrase(list, "b", 0))
        assertEquals(listOf(a), moveCommandPhrase(listOf(a), "a", -1))

        assertEquals(listOf(a, c), deleteCommandPhrase(list, "b"))
        assertEquals(list, deleteCommandPhrase(list, "missing"))
    }

    @Test
    fun chipTextUsesTrimmedTitleOrSingleLineCondensedContent() {
        assertEquals(
            "Run Tests",
            CommandPhrase(title = "  Run Tests  ", content = "./gradlew test").chipText()
        )
        assertEquals(
            "line1 line2",
            CommandPhrase(title = "  ", content = "line1\n\nline2").chipText()
        )
        assertEquals(
            "cd",
            CommandPhrase(title = "", content = "cd ").chipText()
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CommandPhraseSendTest {
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

    private data class RecordedInput(
        val surfaceId: String,
        val text: String,
        val kind: String
    )

    private fun startMockGateway(
        recordedInputs: MutableList<RecordedInput>,
        delayFirstTextAckMs: Long = 0L,
        failTextRpc: Boolean = false
    ) {
        val workspaceTree = """
            {"workspaces":[{"id":"ws-1","key":"ws-1","name":"main","order":0,
              "active_on_host":true,"surfaces":[{"id":"surf-1","type":"terminal","title":"zsh"}]}]}
        """.trimIndent()
        val hostStatus = """
            {"mac_display_name":"Mac","gateway_version":"0.2.0","protocol_version":"2.0.0",
              "capabilities":[],"cmux_status":{"installed":true,"running":true,"can_wake":false}}
        """.trimIndent()

        var delayedOnce = false
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
                    "mobile.host.status" -> webSocket.send("""{"id":$id,"result":$hostStatus}""")
                    "mobile.events.subscribe" -> webSocket.send("""{"id":$id,"result":{}}""")
                    "mobile.workspace.list" -> webSocket.send("""{"id":$id,"result":$workspaceTree}""")
                    "mobile.surface.focus" -> webSocket.send("""{"id":$id,"result":{"status":"ok"}}""")
                    "mobile.terminal.replay" -> webSocket.send(
                        """{"id":$id,"result":{"surface_id":"surf-1","state_seq":1,"columns":80,"rows":24,"full":true}}"""
                    )
                    "mobile.terminal.input" -> {
                        val params = request["params"]!!.jsonObject
                        val input = RecordedInput(
                            surfaceId = params["surface_id"]!!.jsonPrimitive.content,
                            text = params["text"]!!.jsonPrimitive.content,
                            kind = params["input_kind"]!!.jsonPrimitive.content
                        )
                        recordedInputs.add(input)
                        if (failTextRpc && input.kind == "text") {
                            webSocket.send("""{"id":$id,"error":{"code":-32000,"message":"input failed"}}""")
                            return
                        }
                        if (delayFirstTextAckMs > 0L && input.kind == "text" && !delayedOnce) {
                            delayedOnce = true
                            Thread {
                                Thread.sleep(delayFirstTextAckMs)
                                webSocket.send("""{"id":$id,"result":{}}""")
                            }.start()
                        } else {
                            webSocket.send("""{"id":$id,"result":{}}""")
                        }
                    }
                    else -> webSocket.send("""{"id":$id,"result":{}}""")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }))
        server.start()
    }

    @Test
    fun sendCommandPhraseSerializesTextThenEnterAndAcrossRapidTaps() = runBlocking {
        val recorded = Collections.synchronizedList(mutableListOf<RecordedInput>())
        startMockGateway(recorded, delayFirstTextAckMs = 60L)

        val model = TerminalViewModel()
        try {
            model.connect("127.0.0.1", server.port, "test-token")
            withTimeout(8_000) {
                model.selectedSurfaceId.first { it == "surf-1" }
            }

            // Rapidly fire two auto-enter phrases and one insert-only phrase
            model.sendCommandPhrase("git status", autoEnter = true)
            model.sendCommandPhrase("echo ok", autoEnter = true)
            model.sendCommandPhrase("cd ", autoEnter = false)

            withTimeout(8_000) {
                while (recorded.size < 5) {
                    delay(20)
                }
            }
            // Allow any stray trailing keystroke a moment to show up if buggy
            delay(100)

            assertEquals(
                listOf(
                    RecordedInput("surf-1", "git status", "text"),
                    RecordedInput("surf-1", "\r", "key"),
                    RecordedInput("surf-1", "echo ok", "text"),
                    RecordedInput("surf-1", "\r", "key"),
                    RecordedInput("surf-1", "cd ", "text")
                ),
                recorded.toList()
            )
        } finally {
            model.disconnect()
            model.viewModelScope.cancel()
        }
    }

    @Test
    fun sendCommandPhraseSkipsEnterWhenTextRpcFailsAndIgnoresEmptyOrNoSurface() = runBlocking {
        val recorded = Collections.synchronizedList(mutableListOf<RecordedInput>())
        startMockGateway(recorded, failTextRpc = true)

        val model = TerminalViewModel()
        try {
            // Before connect, no selected surface -> no-op
            model.sendCommandPhrase("ls", autoEnter = true)
            assertTrue(recorded.isEmpty())

            model.connect("127.0.0.1", server.port, "test-token")
            withTimeout(8_000) {
                model.selectedSurfaceId.first { it == "surf-1" }
            }

            // Empty content -> no-op
            model.sendCommandPhrase("", autoEnter = true)
            assertTrue(recorded.isEmpty())

            // Text fails -> Enter must NOT be sent
            model.sendCommandPhrase("git status", autoEnter = true)
            withTimeout(8_000) {
                while (recorded.isEmpty()) {
                    delay(20)
                }
            }
            delay(120)

            assertEquals(
                listOf(RecordedInput("surf-1", "git status", "text")),
                recorded.toList()
            )
        } finally {
            model.disconnect()
            model.viewModelScope.cancel()
        }
    }
}

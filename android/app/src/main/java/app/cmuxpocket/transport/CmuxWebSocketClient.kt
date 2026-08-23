package app.cmuxpocket.transport

import app.cmuxpocket.engine.NumericTraceHelper
import app.cmuxpocket.protocol.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    ERROR
}

class CmuxWebSocketClient(
    private val scope: CoroutineScope,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : WebSocketListener() {

    private val tag = "CmuxWebSocketClient"

    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()

    private val _statusFlow = MutableStateFlow<ConnectionStatus>(ConnectionStatus.DISCONNECTED)
    val statusFlow: StateFlow<ConnectionStatus> = _statusFlow.asStateFlow()

    private val _renderGridEvents = MutableSharedFlow<RenderFrameEnvelope>(extraBufferCapacity = 128)
    val renderGridEvents: SharedFlow<RenderFrameEnvelope> = _renderGridEvents.asSharedFlow()

    private val _workspaceEvents = MutableSharedFlow<JsonObject>(extraBufferCapacity = 16)
    val workspaceEvents: SharedFlow<JsonObject> = _workspaceEvents.asSharedFlow()

    private data class InboundMessage(
        val text: String,
        val receivedNanos: Long
    )

    private val inboundChannel = Channel<InboundMessage>(capacity = 128)
    private val traceIdGenerator = AtomicLong(1L)

    init {
        scope.launch(Dispatchers.Default) {
            for (msg in inboundChannel) {
                processInboundMessage(msg.text, msg.receivedNanos)
            }
        }
    }

    private var authToken: String = ""
    private var isAuthCompleted = false

    fun connect(url: String, token: String) {
        this.authToken = token
        this.isAuthCompleted = false
        _statusFlow.value = ConnectionStatus.CONNECTING
        Log.i(tag, "Connecting to gateway...")

        val request = Request.Builder().url(url).build()
        webSocket = okHttpClient.newWebSocket(request, this)
    }

    fun disconnect() {
        Log.i(tag, "Disconnecting...")
        webSocket?.close(1000, "Normal Closure")
        webSocket = null
        isAuthCompleted = false
        _statusFlow.value = ConnectionStatus.DISCONNECTED
        pendingRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(Exception("WebSocket disconnected"))
        }
        pendingRequests.clear()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.i(tag, "onOpen: WebSocket connection open. Sending auth frame...")
        _statusFlow.value = ConnectionStatus.AUTHENTICATING
        val authReq = AuthRequest(type = "auth", token = authToken, clientId = "android-${UUID.randomUUID()}")
        val authJson = json.encodeToString(AuthRequest.serializer(), authReq)
        webSocket.send(authJson)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val receivedNanos = System.nanoTime()
        runBlocking {
            inboundChannel.send(InboundMessage(text, receivedNanos))
        }
    }

    private suspend fun processInboundMessage(text: String, receivedNanos: Long) {
        try {
            val jsonObject = json.parseToJsonElement(text).jsonObject

            // 1. Handle Auth Response
            if (!isAuthCompleted) {
                val msgType = jsonObject["type"]?.jsonPrimitive?.content
                if (msgType == "auth_ok") {
                    isAuthCompleted = true
                    Log.i(tag, "Auth success! Changing status to CONNECTED")
                    _statusFlow.value = ConnectionStatus.CONNECTED
                    return
                } else if (msgType == "auth_error") {
                    Log.e(tag, "Auth error received from server")
                    _statusFlow.value = ConnectionStatus.ERROR
                    disconnect()
                    return
                }
            }

            // 2. Handle Event Push
            val eventName = jsonObject["event"]?.jsonPrimitive?.content
            if (eventName != null) {
                val eventData = jsonObject["data"]
                if (eventName == "terminal.render_grid" && eventData != null) {
                    val frame = json.decodeFromJsonElement(MobileTerminalRenderGridFrame.serializer(), eventData)
                    val decodedNanos = System.nanoTime()
                    val traceId = traceIdGenerator.getAndIncrement()
                    NumericTraceHelper.logReceive(
                        traceId = traceId,
                        surfaceId = frame.surfaceId,
                        stateSeq = frame.stateSeq,
                        full = frame.full,
                        receivedNanos = receivedNanos
                    )
                    NumericTraceHelper.logDecode(
                        traceId = traceId,
                        surfaceId = frame.surfaceId,
                        stateSeq = frame.stateSeq,
                        receivedNanos = receivedNanos,
                        decodedNanos = decodedNanos
                    )
                    val envelope = RenderFrameEnvelope(
                        traceId = traceId,
                        frame = frame,
                        receivedNanos = receivedNanos,
                        decodedNanos = decodedNanos
                    )
                    _renderGridEvents.emit(envelope)
                } else if ((eventName == "workspace.tree" || eventName == "mobile.sync.delta") && eventData != null) {
                    _workspaceEvents.emit(eventData.jsonObject)
                }
                return
            }

            // 3. Handle RPC Response
            val id = jsonObject["id"]?.jsonPrimitive?.content
            if (id != null) {
                val deferred = pendingRequests.remove(id)
                val result = jsonObject["result"]
                if (result != null) {
                    deferred?.complete(result)
                } else {
                    val error = jsonObject["error"]
                    deferred?.completeExceptionally(Exception("RPC Error: $error"))
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error handling message", e)
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e(tag, "onFailure: WebSocket error", t)
        _statusFlow.value = ConnectionStatus.ERROR
        pendingRequests.forEach { (_, deferred) ->
            deferred.completeExceptionally(t)
        }
        pendingRequests.clear()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.i(tag, "onClosed: code=$code, reason=$reason")
        _statusFlow.value = ConnectionStatus.DISCONNECTED
    }

    suspend fun callRpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement {
        val reqId = UUID.randomUUID().toString()
        val rpcReq = JsonRpcRequest(id = reqId, method = method, params = params)
        val deferred = CompletableDeferred<JsonElement>()
        pendingRequests[reqId] = deferred

        val jsonStr = json.encodeToString(JsonRpcRequest.serializer(), rpcReq)
        Log.d(tag, "callRpc [$method] id=$reqId")
        val sent = webSocket?.send(jsonStr) ?: false
        if (!sent) {
            pendingRequests.remove(reqId)
            throw Exception("Failed to send RPC request: WebSocket not connected")
        }

        return withTimeout(5000) {
            deferred.await()
        }
    }

    suspend fun sendTerminalInput(surfaceId: String, text: String) {
        val params = buildJsonObject {
            put("surface_id", surfaceId)
            put("text", text)
            put("client_id", "android-client")
        }
        callRpc("mobile.terminal.input", params)
    }

    suspend fun scrollTerminal(surfaceId: String, deltaLines: Double, col: Int = 0, row: Int = 0) {
        val params = buildJsonObject {
            put("surface_id", surfaceId)
            put("delta_lines", deltaLines)
            put("col", col)
            put("row", row)
            put("client_id", "android-client")
        }
        callRpc("mobile.terminal.scroll", params)
    }

    suspend fun subscribeEvents(topics: List<String>) {
        val params = buildJsonObject {
            put("topics", buildJsonArray {
                topics.forEach { add(JsonPrimitive(it)) }
            })
            put("stream_id", UUID.randomUUID().toString())
        }
        callRpc("mobile.events.subscribe", params)
    }

    suspend fun createWorkspace(
        name: String,
        initialTerminal: Boolean = true,
        mutationId: String = UUID.randomUUID().toString()
    ): WorkspaceCreateResponse {
        val params = buildJsonObject {
            put("name", name)
            if (initialTerminal) {
                put("initial_surface", buildJsonObject {
                    put("type", "terminal")
                })
            }
            put("mutation_id", mutationId)
        }
        val result = callRpc("mobile.workspace.create", params)
        return json.decodeFromJsonElement(WorkspaceCreateResponse.serializer(), result)
    }

    suspend fun selectWorkspace(workspaceKey: String) {
        val params = buildJsonObject {
            put("workspace_key", workspaceKey)
            put("workspace_id", workspaceKey)
        }
        callRpc("mobile.workspace.select", params)
    }

    suspend fun createSurface(
        workspaceKey: String,
        title: String? = null,
        mutationId: String = UUID.randomUUID().toString()
    ): SurfaceCreateResponse {
        val params = buildJsonObject {
            put("workspace_key", workspaceKey)
            put("workspace_id", workspaceKey)
            put("type", "terminal")
            if (title != null) put("title", title)
            put("mutation_id", mutationId)
        }
        val result = callRpc("mobile.surface.create", params)
        return json.decodeFromJsonElement(SurfaceCreateResponse.serializer(), result)
    }

    suspend fun closeSurface(
        surfaceId: String,
        workspaceKey: String? = null,
        mutationId: String = UUID.randomUUID().toString()
    ): SurfaceCloseResponse {
        val params = buildJsonObject {
            put("surface_id", surfaceId)
            if (workspaceKey != null) put("workspace_key", workspaceKey)
            put("mutation_id", mutationId)
        }
        val result = callRpc("mobile.surface.close", params)
        return json.decodeFromJsonElement(SurfaceCloseResponse.serializer(), result)
    }

    suspend fun focusSurface(surfaceId: String?) {
        val params = buildJsonObject {
            if (surfaceId != null) {
                put("surface_id", surfaceId)
            } else {
                put("surface_id", JsonNull)
            }
            put("client_id", "android-client")
        }
        try {
            callRpc("mobile.surface.focus", params)
        } catch (e: Exception) {
            Log.w(tag, "focusSurface failed non-fatally", e)
        }
    }

    suspend fun requestReplay(
        surfaceId: String,
        maxScrollbackRows: Int = 500,
        screenAnchor: String? = "screen"
    ) {
        val params = buildJsonObject {
            put("surface_id", surfaceId)
            put("client_id", "android-client")
            put("max_scrollback_rows", maxScrollbackRows)
            if (screenAnchor != null) {
                put("anchor", screenAnchor)
            }
        }
        try {
            callRpc("mobile.terminal.replay", params)
        } catch (e: Exception) {
            Log.w(tag, "requestReplay failed", e)
        }
    }
}

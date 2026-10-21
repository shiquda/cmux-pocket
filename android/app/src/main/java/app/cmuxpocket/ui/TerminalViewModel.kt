package app.cmuxpocket.ui

import app.cmuxpocket.engine.*
import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.protocol.AgentSessionCompleted
import app.cmuxpocket.protocol.GatewayHostStatus
import app.cmuxpocket.protocol.WorkspaceInfo
import app.cmuxpocket.protocol.WorkspaceListResponse
import app.cmuxpocket.protocol.WorkspaceSelection
import app.cmuxpocket.transport.CmuxWebSocketClient
import app.cmuxpocket.transport.ConnectionStatus
import app.cmuxpocket.transport.ConnectionFailure
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.ConcurrentHashMap

enum class AppSyncPhase {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    SYNCING,
    READY
}

/** Progress of a remote cmux launch requested from the terminal stage. */
enum class CmuxWakeState {
    IDLE,
    WAKING,
    SYNCING,
    FAILED
}

class TerminalViewModel : ViewModel() {

    private val tag = "TerminalViewModel"
    val sessionStore = SurfaceSessionStore()
    val mutationTracker = MutationTracker()
    val wsClient = CmuxWebSocketClient(viewModelScope)
    private val json = Json { ignoreUnknownKeys = true }

    val connectionStatus: StateFlow<ConnectionStatus> = wsClient.statusFlow

    private val _syncPhase = MutableStateFlow(AppSyncPhase.DISCONNECTED)
    val syncPhase: StateFlow<AppSyncPhase> = _syncPhase.asStateFlow()

    private val _statusMessage = MutableStateFlow<String>("Ready to connect")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _connectionReadiness = MutableStateFlow(ConnectionReadiness())
    val connectionReadiness: StateFlow<ConnectionReadiness> = _connectionReadiness.asStateFlow()

    private val _wakeState = MutableStateFlow(CmuxWakeState.IDLE)
    val wakeState: StateFlow<CmuxWakeState> = _wakeState.asStateFlow()

    private val _hostStatusRefreshing = MutableStateFlow(false)
    val hostStatusRefreshing: StateFlow<Boolean> = _hostStatusRefreshing.asStateFlow()
    private var wakeTimeoutJob: Job? = null

    private val _pendingImportUri = MutableStateFlow<String?>(null)
    val pendingImportUri: StateFlow<String?> = _pendingImportUri.asStateFlow()

    fun setPendingImportUri(uri: String?) {
        _pendingImportUri.value = uri
    }

    fun clearPendingImportUri() {
        _pendingImportUri.value = null
    }
    private val _workspaces = MutableStateFlow<List<WorkspaceInfo>>(emptyList())
    private val _agentSessionCompletions = MutableSharedFlow<AgentSessionCompleted>(extraBufferCapacity = 32)
    val agentSessionCompletions: SharedFlow<AgentSessionCompleted> = _agentSessionCompletions.asSharedFlow()
    val workspaces: StateFlow<List<WorkspaceInfo>> = _workspaces.asStateFlow()

    private val _selectedWorkspaceKey = MutableStateFlow<String?>(null)
    val selectedWorkspaceKey: StateFlow<String?> = _selectedWorkspaceKey.asStateFlow()

    private val _selectedSurfaceId = MutableStateFlow<String?>(null)
    val selectedSurfaceId: StateFlow<String?> = _selectedSurfaceId.asStateFlow()

    private val _activeScreenState = MutableStateFlow(TerminalScreenState())
    val activeScreenState: StateFlow<TerminalScreenState> = _activeScreenState.asStateFlow()

    val activeWorkspace: StateFlow<WorkspaceInfo?> = combine(_workspaces, _selectedWorkspaceKey) { list, key ->
        list.firstOrNull { it.stableKey == key } ?: list.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val activeSurfaces: StateFlow<List<SurfaceInfo>> = activeWorkspace.map { ws ->
        ws?.surfaces ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeSurface: StateFlow<SurfaceInfo?> = combine(activeSurfaces, _selectedSurfaceId) { surfaces, surfId ->
        surfaces.firstOrNull { it.id == surfId } ?: surfaces.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _phoneWidthStates = MutableStateFlow<Map<String, PhoneWidthState>>(emptyMap())
    val phoneWidthStates: StateFlow<Map<String, PhoneWidthState>> = _phoneWidthStates.asStateFlow()

    private val _gatewayCapabilities = MutableStateFlow<Set<String>>(emptySet())
    val gatewayCapabilities: StateFlow<Set<String>> = _gatewayCapabilities.asStateFlow()

    /** Last authoritative geometry observed while this client pinned nothing. */
    private val macBasis = mutableMapOf<String, Pair<Int, Int>>()
    /** Frames received before this deadline may still show the phone grid. */
    private val macBasisInvalidUntil = mutableMapOf<String, Long>()
    private val widthMetrics = mutableMapOf<String, TerminalWidthMetrics>()
    private val widthRequestJobs = mutableMapOf<String, Job>()
    private val widthConfirmedAtNanos = mutableMapOf<String, Long>()
    private var lastViewportGeneration = 0L

    private var currentScreenCollectorJob: Job? = null
    private var currentBootstrapJob: Job? = null
    private var scrollDeliveryJob: Job? = null
    private var pendingScrollSurfaceId: String? = null
    private var pendingScrollLines = 0.0
    private val awaitingReplaySurfaces = ConcurrentHashMap<String, Boolean>()
    private var connectionGeneration: Long = 0
    private data class ConnectionTarget(
        val host: String,
        val port: Int,
        val token: String
    )

    private var lastConnectionTarget: ConnectionTarget? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var manualDisconnect = true
    private var connectionValidationError: String? = null
    private var pendingNotificationWorkspaceId: String? = null
    private var pendingNotificationSurfaceId: String? = null

    init {
        // 1. Collect RenderGrid delta/full frames, validate consistency and route
        viewModelScope.launch {
            wsClient.renderGridEvents.collect { envelope ->
                val frame = envelope.frame
                val result = sessionStore.routeFrame(envelope)
                when (result) {
                    FrameApplyResult.BASELINE_APPLIED -> {
                        // Baseline successfully restored -> release recovery barrier single-flight lock
                        awaitingReplaySurfaces.remove(frame.surfaceId)
                        if (frame.surfaceId == _selectedSurfaceId.value) {
                            sessionStore.getSession(frame.surfaceId)?.let { session ->
                                _activeScreenState.value = session.engine.screenState.value
                            }
                        }
                    }
                    FrameApplyResult.DELTA_APPLIED -> {
                        if (frame.surfaceId == _selectedSurfaceId.value) {
                            sessionStore.getSession(frame.surfaceId)?.let { session ->
                                _activeScreenState.value = session.engine.screenState.value
                            }
                        }
                    }
                    FrameApplyResult.DUPLICATE -> {
                        Log.d(tag, "Dropped duplicate frame for ${frame.surfaceId}, seq=${frame.stateSeq}")
                    }
                    FrameApplyResult.NEED_REPLAY -> {
                        Log.w(tag, "Gap, epoch mismatch, or history jump on ${frame.surfaceId}, requesting single-flight replay")
                        if (frame.surfaceId == _selectedSurfaceId.value) {
                            sessionStore.getSession(frame.surfaceId)?.let { session ->
                                _activeScreenState.value = session.engine.screenState.value
                            }
                        }
                        requestSingleFlightReplay(frame.surfaceId)
                    }
                }
                if (result == FrameApplyResult.BASELINE_APPLIED) {
                    onAuthoritativeFrame(
                        surfaceId = frame.surfaceId,
                        columns = frame.columns,
                        rows = frame.rows,
                        receivedAtNanos = envelope.receivedNanos,
                    )
                }
            }
        }

        viewModelScope.launch {
            wsClient.workspaceEvents.collect { data ->
                val decision = mutationTracker.handleWorkspaceEvent(data, connectionGeneration, json)
                when (decision) {
                    is MutationEventDecision.ApplyFullWorkspaces -> {
                        updateWorkspaces(decision.workspaces)
                    }
                    is MutationEventDecision.SuppressReload -> {
                        Log.d(tag, "Suppressed duplicate reload for matching action: ${decision.actionType}")
                    }
                    is MutationEventDecision.TriggerReload -> {
                        Log.d(tag, "Triggering workspace reload: reason=${decision.reason.name}")
                        reloadWorkspaces()
                    }
                }
            }
        }

        viewModelScope.launch {
            wsClient.agentCompletionEvents.collect { data ->
                runCatching {
                    json.decodeFromJsonElement(AgentSessionCompleted.serializer(), data)
                }.onSuccess { completion ->
                    _agentSessionCompletions.emit(completion)
                }.onFailure { error ->
                    Log.w(tag, "Ignoring malformed agent completion event", error)
                }
            }
        }

        // 2. Watch connection status & advance lifecycle phases
        viewModelScope.launch {
            wsClient.statusFlow.collect { status ->
                when (status) {
                    ConnectionStatus.CONNECTING -> {
                        _connectionReadiness.value = _connectionReadiness.value.copy(
                            step = ConnectionStep.NETWORK,
                            started = true,
                            problem = null,
                        )
                        _syncPhase.value = AppSyncPhase.CONNECTING
                        _statusMessage.value = "Connecting..."
                    }
                    ConnectionStatus.AUTHENTICATING -> {
                        _connectionReadiness.value = _connectionReadiness.value.copy(
                            step = ConnectionStep.AUTHENTICATION,
                            started = true,
                            problem = null,
                        )
                        _syncPhase.value = AppSyncPhase.AUTHENTICATING
                        _statusMessage.value = "Authenticating..."
                    }
                    ConnectionStatus.CONNECTED -> {
                        reconnectAttempt = 0
                        reconnectJob?.cancel()
                        reconnectJob = null
                        _syncPhase.value = AppSyncPhase.SYNCING
                        _statusMessage.value = "Syncing workspaces..."
                        connectionGeneration++
                        awaitingReplaySurfaces.clear()
                        mutationTracker.reset(connectionGeneration)
                        subscribeAndBootstrap(connectionGeneration)
                    }
                    ConnectionStatus.ERROR -> {
                        currentBootstrapJob?.cancel()
                        connectionGeneration++
                        val authFailed = wsClient.connectionFailure == ConnectionFailure.AUTHENTICATION
                        val authTimedOut = wsClient.connectionFailure == ConnectionFailure.AUTHENTICATION_TIMEOUT
                        if (authFailed) manualDisconnect = true
                        _connectionReadiness.value = _connectionReadiness.value.copy(
                            step = if (authFailed || authTimedOut) ConnectionStep.AUTHENTICATION else ConnectionStep.NETWORK,
                            started = true,
                            problem = when (wsClient.connectionFailure) {
                                ConnectionFailure.AUTHENTICATION -> "Gateway rejected the token"
                                ConnectionFailure.AUTHENTICATION_TIMEOUT -> "Gateway did not answer authentication"
                                ConnectionFailure.TLS -> "Could not establish a trusted HTTPS connection"
                                ConnectionFailure.DNS -> "Gateway hostname could not be resolved"
                                else -> "Cannot reach the Gateway"
                            },
                        )
                        _syncPhase.value = AppSyncPhase.DISCONNECTED
                        _statusMessage.value = _connectionReadiness.value.summary
                        awaitingReplaySurfaces.clear()
                        mutationTracker.clear()
                        handleConnectionLost()
                        scheduleReconnect()
                    }
                    ConnectionStatus.DISCONNECTED -> {
                        currentBootstrapJob?.cancel()
                        connectionGeneration++
                        if (connectionValidationError != null || _connectionReadiness.value.started) {
                            _connectionReadiness.value = _connectionReadiness.value.copy(
                                started = true,
                                problem = connectionValidationError ?: "Gateway disconnected",
                            )
                        }
                        _syncPhase.value = AppSyncPhase.DISCONNECTED
                        _statusMessage.value = connectionValidationError ?: "Disconnected"
                        awaitingReplaySurfaces.clear()
                        mutationTracker.clear()
                        handleConnectionLost()
                        scheduleReconnect()
                    }
                }
            }
        }

        // 3. React to selected surface changes
        viewModelScope.launch {
            _selectedSurfaceId.collect { surfaceId ->
                currentScreenCollectorJob?.cancel()
                if (surfaceId != null) {
                    val session = sessionStore.getOrCreateSession(surfaceId)
                    _activeScreenState.value = session.engine.screenState.value
                    currentScreenCollectorJob = viewModelScope.launch {
                        session.engine.screenState.collect { state ->
                            _activeScreenState.value = state
                        }
                    }
                } else {
                    _activeScreenState.value = TerminalScreenState()
                }
            }
        }
    }

    private fun requestSingleFlightReplay(surfaceId: String) {
        if (awaitingReplaySurfaces.putIfAbsent(surfaceId, true) == null) {
            sessionStore.getSession(surfaceId)?.engine?.markAwaitingReplay()
            viewModelScope.launch {
                try {
                    wsClient.requestReplay(surfaceId)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to request replay for $surfaceId", e)
                    awaitingReplaySurfaces.remove(surfaceId)
                }
            }
        }
    }

    fun connect(host: String = "127.0.0.1", port: Int = 8088, token: String = "") {
        manualDisconnect = true
        connectionValidationError = null
        reconnectJob?.cancel()
        reconnectJob = null
        wsClient.disconnect()
        _connectionReadiness.value = ConnectionReadiness()
        _wakeState.value = CmuxWakeState.IDLE
        wakeTimeoutJob?.cancel()
        lastConnectionTarget = ConnectionTarget(host, port, token)
        reconnectAttempt = 0
        manualDisconnect = false
        connectTarget(lastConnectionTarget!!)
    }

    private fun connectTarget(target: ConnectionTarget) {
        try {
            val url = ConnectionEndpoint.websocketUrl(target.host, target.port)
            _statusMessage.value = "Connecting to gateway..."
            wsClient.connect(url, target.token)
        } catch (e: IllegalArgumentException) {
            manualDisconnect = true
            connectionValidationError = e.message ?: "Invalid connection endpoint"
            wsClient.disconnect()
            _syncPhase.value = AppSyncPhase.DISCONNECTED
            _statusMessage.value = connectionValidationError!!
            _connectionReadiness.value = ConnectionReadiness(started = true, problem = connectionValidationError)
        }
    }

    private fun scheduleReconnect() {
        val target = lastConnectionTarget ?: return
        if (manualDisconnect || reconnectJob?.isActive == true) return
        if (reconnectAttempt >= ReconnectPolicy.maxAttempts) {
            _statusMessage.value = "Reconnect paused; tap Reconnect to retry"
            return
        }

        val attempt = reconnectAttempt++
        val delayMs = ReconnectPolicy.delayMillis(attempt)
        _statusMessage.value = "Disconnected. Retrying in ${delayMs / 1_000}s..."
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            if (!manualDisconnect && lastConnectionTarget == target) {
                _statusMessage.value = "Retrying connection (${attempt + 1}/${ReconnectPolicy.maxAttempts})..."
                connectTarget(target)
            }
            reconnectJob = null
        }
    }

    fun scrollTerminal(deltaLines: Double) {
        val surfaceId = _selectedSurfaceId.value ?: return
        if (deltaLines == 0.0) return
        if (pendingScrollSurfaceId != surfaceId) {
            pendingScrollSurfaceId = surfaceId
            pendingScrollLines = 0.0
        }
        pendingScrollLines += deltaLines
        if (scrollDeliveryJob?.isActive == true) return

        scrollDeliveryJob = viewModelScope.launch {
            while (pendingScrollLines != 0.0) {
                val targetSurfaceId = pendingScrollSurfaceId ?: break
                val lines = pendingScrollLines
                pendingScrollLines = 0.0
                try {
                    wsClient.scrollTerminal(targetSurfaceId, lines)
                } catch (e: Exception) {
                    Log.w(tag, "Failed to scroll surface $targetSurfaceId", e)
                }
            }
        }
    }

    fun wakeCmux() {
        if (_wakeState.value == CmuxWakeState.WAKING || _wakeState.value == CmuxWakeState.SYNCING) return
        val host = _connectionReadiness.value.hostStatus ?: return
        if (!host.cmuxInstalled || !host.canWake) return

        _wakeState.value = CmuxWakeState.WAKING
        _statusMessage.value = "Waking cmux on Mac..."
        viewModelScope.launch {
            try {
                wsClient.wakeCmux()
                // A workspace sync can arrive before this coroutine resumes; the
                // tree collector already settled the state in that case.
                if (_wakeState.value != CmuxWakeState.WAKING) return@launch
                _wakeState.value = CmuxWakeState.SYNCING
                _statusMessage.value = "Waiting for cmux to start..."
                wakeTimeoutJob?.cancel()
                wakeTimeoutJob = viewModelScope.launch {
                    delay(WAKE_TIMEOUT_MS)
                    if (_wakeState.value == CmuxWakeState.WAKING || _wakeState.value == CmuxWakeState.SYNCING) {
                        _wakeState.value = CmuxWakeState.FAILED
                        _statusMessage.value = "cmux did not respond; retry from the wake card"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Failed to wake cmux", e)
                _wakeState.value = CmuxWakeState.FAILED
                _statusMessage.value = "Failed to wake cmux: ${e.message}"
            }
        }
    }

    /** Re-reads `mobile.host.status` without reconnecting (version card and upgrade guide). */
    fun refreshHostStatus() {
        if (_hostStatusRefreshing.value) return
        viewModelScope.launch {
            _hostStatusRefreshing.value = true
            try {
                val status = wsClient.callRpc("mobile.host.status") as? JsonObject ?: return@launch
                _connectionReadiness.value = _connectionReadiness.value.copy(
                    hostStatus = decodeHostStatus(status),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Failed to refresh host status", e)
                _statusMessage.value = "Unable to read Gateway version: ${e.message}"
            } finally {
                _hostStatusRefreshing.value = false
            }
        }
    }

    fun disconnect() {
        manualDisconnect = true
        connectionValidationError = null
        lastConnectionTarget = null
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        wakeTimeoutJob?.cancel()
        _wakeState.value = CmuxWakeState.IDLE
        wsClient.disconnect()
        awaitingReplaySurfaces.clear()
        mutationTracker.clear()
        handleConnectionLost()
    }

    private suspend fun refreshWorkspacesInternal(generation: Long = connectionGeneration): List<WorkspaceInfo>? {
        return try {
            val wsElement = wsClient.callRpc("mobile.workspace.list")
            if (generation == connectionGeneration) {
                val listResp = json.decodeFromJsonElement(WorkspaceListResponse.serializer(), wsElement)
                updateWorkspaces(listResp.workspaces)
                listResp.workspaces
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to refresh workspaces", e)
            null
        }
    }

    fun reloadWorkspaces() {
        val gen = connectionGeneration
        viewModelScope.launch {
            refreshWorkspacesInternal(gen)
        }
    }

    private fun subscribeAndBootstrap(generation: Long) {
        currentBootstrapJob?.cancel()
        currentBootstrapJob = viewModelScope.launch {
            var handshakeStatus: GatewayHostStatus? = null
            try {
                val handshake = verifyGatewayConnection(
                    callRpc = { method -> wsClient.callRpc(method) },
                    subscribe = {
                        wsClient.subscribeEvents(listOf("terminal.render_grid", "mobile.sync.delta", "workspace.tree", "agent.session.completed"))
                    },
                    onStep = { step ->
                        _connectionReadiness.value = _connectionReadiness.value.copy(
                            step = step,
                            started = true,
                            problem = null,
                        )
                    },
                    onHostStatus = { status ->
                        handshakeStatus = status
                        _connectionReadiness.value = _connectionReadiness.value.copy(hostStatus = status)
                    },
                    onCapabilities = { capabilities ->
                        if (generation == connectionGeneration) {
                            _gatewayCapabilities.value = capabilities
                        }
                    },
                )
                if (generation == connectionGeneration) {
                    updateWorkspaces(handshake.workspaces, syncReconciledFocus = false)
                    _selectedSurfaceId.value?.let { surfaceId ->
                        wsClient.focusSurface(surfaceId)
                        requestSingleFlightReplay(surfaceId)
                    }
                    _syncPhase.value = AppSyncPhase.READY
                    _statusMessage.value = "Ready"
                    _connectionReadiness.value = ConnectionReadiness(
                        step = ConnectionStep.READY,
                        started = true,
                        workspaceCount = handshake.workspaces.size,
                        terminalCount = handshake.workspaces.sumOf { ws -> ws.surfaces.count { it.type == "terminal" } },
                        hostStatus = handshake.hostStatus,
                    )
                    Log.i(tag, "Bootstrap complete for generation $generation! Total workspaces=${handshake.workspaces.size}")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == connectionGeneration) {
                    Log.e(tag, "Failed in bootstrap", e)
                    val host = handshakeStatus ?: _connectionReadiness.value.hostStatus
                    if (host != null && host.cmuxInstalled && !host.cmuxRunning) {
                        // The Gateway is healthy; a stopped cmux app is a recoverable
                        // state with its own wake card, not a red connection failure.
                        updateWorkspaces(emptyList(), syncReconciledFocus = false)
                        _syncPhase.value = AppSyncPhase.READY
                        _statusMessage.value = "Mac online · cmux not running"
                        _connectionReadiness.value = _connectionReadiness.value.copy(
                            step = ConnectionStep.READY,
                            started = true,
                            problem = null,
                            workspaceCount = 0,
                            terminalCount = 0,
                            hostStatus = host,
                        )
                    } else {
                        _syncPhase.value = AppSyncPhase.DISCONNECTED
                        _statusMessage.value = "Bootstrap failed: ${e.message}"
                        _connectionReadiness.value = _connectionReadiness.value.failed(
                            when (_connectionReadiness.value.step) {
                                ConnectionStep.WORKSPACES -> "Gateway connected, but cmux workspaces are unavailable"
                                ConnectionStep.GATEWAY -> "Gateway did not complete the readiness handshake"
                                else -> "Workspace synchronization failed"
                            }
                        )
                    }
                }
            }
        }
    }

    fun updateWorkspaces(list: List<WorkspaceInfo>, syncReconciledFocus: Boolean = true) {
        val prevSurfaceId = _selectedSurfaceId.value
        _workspaces.value = list
        if (_wakeState.value != CmuxWakeState.IDLE) {
            wakeTimeoutJob?.cancel()
            _wakeState.value = CmuxWakeState.IDLE
            refreshHostStatus()
        }
        if (_connectionReadiness.value.step == ConnectionStep.READY) {
            _connectionReadiness.value = _connectionReadiness.value.copy(
                workspaceCount = list.size,
                terminalCount = list.sumOf { ws -> ws.surfaces.count { it.type == "terminal" } },
            )
        }
        if (list.isNotEmpty() && _connectionReadiness.value.cmuxStopped) {
            // cmux came back without another bootstrap; refresh the reported host state.
            refreshHostStatus()
        }
        list.forEach { ws ->
            sessionStore.syncFromSurfaces(ws.surfaces, ws.stableKey)
        }
        val (newWorkspaceKey, newSurfaceId) = WorkspaceSelection.reconcile(
            workspaces = _workspaces.value,
            selectedWorkspaceKey = _selectedWorkspaceKey.value,
            selectedSurfaceId = _selectedSurfaceId.value,
        )
        _selectedWorkspaceKey.value = newWorkspaceKey
        _selectedSurfaceId.value = newSurfaceId
        applyPendingNotificationNavigation()

        // When enabled, sync focus to gateway whenever selection changes (including null -> new and old -> null/new)
        if (syncReconciledFocus && prevSurfaceId != newSurfaceId) {
            prevSurfaceId?.let { releasePhoneWidth(it) }
            viewModelScope.launch {
                try {
                    wsClient.focusSurface(newSurfaceId)
                    if (newSurfaceId != null) {
                        requestSingleFlightReplay(newSurfaceId)
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to update focus on selection reconciliation", e)
                }
            }
        }
    }

    /**
     * User-initiated workspace selection (dispatches focus and sync).
     */
    fun selectWorkspace(workspaceKey: String) {
        _selectedWorkspaceKey.value = workspaceKey
        val ws = _workspaces.value.firstOrNull { it.stableKey == workspaceKey }
        val firstSurface = ws?.surfaces?.firstOrNull { it.id == _selectedSurfaceId.value }
            ?: ws?.surfaces?.firstOrNull { it.type == "terminal" }
            ?: ws?.surfaces?.firstOrNull()
        userSelectSurface(firstSurface?.id)
    }

    /**
     * User-initiated surface selection (dispatches remote focus & single-flight replay).
     */
    fun selectSurface(surfaceId: String?) {
        userSelectSurface(surfaceId)
    }

    fun navigateToSurface(workspaceId: String?, surfaceId: String) {
        pendingNotificationWorkspaceId = workspaceId
        pendingNotificationSurfaceId = surfaceId
        applyPendingNotificationNavigation()
    }

    private fun applyPendingNotificationNavigation() {
        val surfaceId = pendingNotificationSurfaceId ?: return
        val workspace = _workspaces.value.firstOrNull { ws ->
            (pendingNotificationWorkspaceId == null || ws.id == pendingNotificationWorkspaceId || ws.stableKey == pendingNotificationWorkspaceId) &&
                ws.surfaces.any { it.id == surfaceId }
        } ?: return
        pendingNotificationWorkspaceId = null
        pendingNotificationSurfaceId = null
        _selectedWorkspaceKey.value = workspace.stableKey
        userSelectSurface(surfaceId)
    }

    private fun userSelectSurface(surfaceId: String?) {
        val previousSurfaceId = _selectedSurfaceId.value
        if (previousSurfaceId != null && previousSurfaceId != surfaceId) {
            releasePhoneWidth(previousSurfaceId)
        }
        if (surfaceId == null) {
            _selectedSurfaceId.value = null
            viewModelScope.launch {
                try {
                    wsClient.focusSurface(null)
                } catch (e: Exception) {
                    Log.w(tag, "focusSurface null error", e)
                }
            }
            return
        }
        _selectedSurfaceId.value = surfaceId
        viewModelScope.launch {
            try {
                wsClient.focusSurface(surfaceId)
                requestSingleFlightReplay(surfaceId)
            } catch (e: Exception) {
                Log.w(tag, "focusSurface error", e)
            }
        }
    }

    fun createWorkspace(name: String? = null, initialTerminal: Boolean = true) {
        val gen = connectionGeneration
        val mutationId = java.util.UUID.randomUUID().toString()
        mutationTracker.registerTicket(mutationId, gen, "workspace_created")
        viewModelScope.launch {
            try {
                _statusMessage.value = "Creating workspace..."
                val resp = wsClient.createWorkspace(name, initialTerminal, mutationId)
                mutationTracker.onRpcSuccess(mutationId, gen)
                refreshWorkspacesInternal(gen)
                resp.workspace?.let { newWs ->
                    selectWorkspace(newWs.stableKey)
                }
                _statusMessage.value = "Workspace created"
            } catch (e: Exception) {
                mutationTracker.onRpcFailure(mutationId, gen)
                Log.e(tag, "Failed to create workspace", e)
                _statusMessage.value = "Failed to create workspace: ${e.message}"
            }
        }
    }

    fun renameWorkspace(workspaceKey: String, newName: String) {
        val gen = connectionGeneration
        val mutationId = java.util.UUID.randomUUID().toString()
        mutationTracker.registerTicket(mutationId, gen, "workspace_renamed")
        viewModelScope.launch {
            try {
                _statusMessage.value = "Renaming workspace..."
                wsClient.renameWorkspace(workspaceKey, newName, mutationId)
                mutationTracker.onRpcSuccess(mutationId, gen)
                refreshWorkspacesInternal(gen)
                _statusMessage.value = "Workspace renamed"
            } catch (e: Exception) {
                mutationTracker.onRpcFailure(mutationId, gen)
                Log.e(tag, "Failed to rename workspace", e)
                _statusMessage.value = "Failed to rename workspace: ${e.message}"
            }
        }
    }

    fun closeWorkspace(workspaceKey: String) {
        val gen = connectionGeneration
        val mutationId = java.util.UUID.randomUUID().toString()
        mutationTracker.registerTicket(mutationId, gen, "workspace_closed")
        viewModelScope.launch {
            try {
                _statusMessage.value = "Closing workspace..."
                wsClient.closeWorkspace(workspaceKey, mutationId)
                mutationTracker.onRpcSuccess(mutationId, gen)
                refreshWorkspacesInternal(gen)
                _statusMessage.value = "Workspace closed"
            } catch (e: Exception) {
                mutationTracker.onRpcFailure(mutationId, gen)
                Log.e(tag, "Failed to close workspace", e)
                _statusMessage.value = "Failed to close workspace: ${e.message}"
            }
        }
    }

    fun createSurface(workspaceKey: String? = _selectedWorkspaceKey.value, title: String? = null) {
        val targetKey = workspaceKey ?: return
        val gen = connectionGeneration
        val mutationId = java.util.UUID.randomUUID().toString()
        mutationTracker.registerTicket(mutationId, gen, "surface_created")
        viewModelScope.launch {
            try {
                _statusMessage.value = "Creating terminal tab..."
                val resp = wsClient.createSurface(targetKey, title, mutationId)
                mutationTracker.onRpcSuccess(mutationId, gen)
                val newSurf = resp.surface
                val newSurfaceId = newSurf?.id
                val newWorkspaceKey = newSurf?.workspaceKey?.ifEmpty { targetKey } ?: targetKey

                if (newSurfaceId != null) {
                    _selectedWorkspaceKey.value = newWorkspaceKey
                    _selectedSurfaceId.value = newSurfaceId
                }
                refreshWorkspacesInternal(gen)
                if (newSurfaceId != null) {
                    _selectedWorkspaceKey.value = newWorkspaceKey
                    _selectedSurfaceId.value = newSurfaceId
                    try {
                        wsClient.focusSurface(newSurfaceId)
                        requestSingleFlightReplay(newSurfaceId)
                    } catch (e: Exception) {
                        Log.w(tag, "focusSurface error", e)
                    }
                }
                _statusMessage.value = "Terminal tab created"
            } catch (e: Exception) {
                mutationTracker.onRpcFailure(mutationId, gen)
                Log.e(tag, "Failed to create surface", e)
                _statusMessage.value = "Failed to create terminal tab: ${e.message}"
            }
        }
    }

    fun closeSurface(surfaceId: String) {
        val gen = connectionGeneration
        val mutationId = java.util.UUID.randomUUID().toString()
        mutationTracker.registerTicket(mutationId, gen, "surface_closed")
        viewModelScope.launch {
            try {
                _statusMessage.value = "Closing tab..."
                wsClient.closeSurface(surfaceId, _selectedWorkspaceKey.value, mutationId)
                mutationTracker.onRpcSuccess(mutationId, gen)
                releasePhoneWidth(surfaceId)
                sessionStore.removeSession(surfaceId)
                refreshWorkspacesInternal(gen)
                _statusMessage.value = "Tab closed"
            } catch (e: Exception) {
                mutationTracker.onRpcFailure(mutationId, gen)
                Log.e(tag, "Failed to close surface", e)
                _statusMessage.value = "Failed to close tab: ${e.message}"
            }
        }
    }

    fun renameSurface(surfaceId: String, newTitle: String) {
        val gen = connectionGeneration
        val mutationId = java.util.UUID.randomUUID().toString()
        mutationTracker.registerTicket(mutationId, gen, "surface_renamed")
        viewModelScope.launch {
            try {
                _statusMessage.value = "Renaming tab..."
                wsClient.renameSurface(surfaceId, newTitle, _selectedWorkspaceKey.value, mutationId)
                mutationTracker.onRpcSuccess(mutationId, gen)
                sessionStore.getSession(surfaceId)?.title = newTitle
                refreshWorkspacesInternal(gen)
                _statusMessage.value = "Tab renamed"
            } catch (e: Exception) {
                mutationTracker.onRpcFailure(mutationId, gen)
                Log.e(tag, "Failed to rename surface", e)
                _statusMessage.value = "Failed to rename tab: ${e.message}"
            }
        }
    }

    fun sendInput(text: String) {
        val surfaceId = _selectedSurfaceId.value ?: return
        viewModelScope.launch {
            try {
                wsClient.sendTerminalInput(surfaceId, text)
            } catch (e: Exception) {
                Log.e(tag, "Failed to send input to surface $surfaceId", e)
            }
        }
    }

    fun clearScrollback(surfaceId: String? = null) {
        sessionStore.clearScrollback(surfaceId)
        val activeId = _selectedSurfaceId.value
        if (surfaceId == null || surfaceId == activeId) {
            activeId?.let { id ->
                sessionStore.getSession(id)?.let { session ->
                    _activeScreenState.value = session.engine.screenState.value
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Phone-width terminal layout
    // ------------------------------------------------------------------

    /**
     * Enables or releases this client's viewport report for one surface.
     *
     * Enabling is explicit per surface and never restored silently: switching
     * surfaces, backgrounding the app, losing the connection or turning the
     * mode off releases the report so the host can return to the laptop grid.
     */
    fun setPhoneWidthEnabled(surfaceId: String, enabled: Boolean) {
        if (!enabled) {
            releasePhoneWidth(surfaceId)
            return
        }
        if (surfaceId.isBlank()) return
        val current = _phoneWidthStates.value[surfaceId]
        if (current?.enabled == true) return
        if (!viewportResizeSupported()) {
            setPhoneWidthState(
                surfaceId,
                PhoneWidthState(phase = PhoneWidthPhase.UNSUPPORTED, notice = PhoneWidthNotice.UNSUPPORTED)
            )
            return
        }
        val baseline = baselineFor(surfaceId)
        if (baseline == null) {
            setPhoneWidthState(
                surfaceId,
                PhoneWidthState(phase = PhoneWidthPhase.FAILED, notice = PhoneWidthNotice.NO_BASELINE)
            )
            return
        }
        // Without real font/canvas metrics a request would be a guess (or the
        // computer's own grid), so refuse instead of pretending to adapt.
        val target = computePhoneWidthTarget(widthMetrics[surfaceId], baseline.second)
        if (target == null) {
            setPhoneWidthState(
                surfaceId,
                PhoneWidthState(phase = PhoneWidthPhase.FAILED, notice = PhoneWidthNotice.NO_BASELINE)
            )
            return
        }
        val state = PhoneWidthState(
            phase = PhoneWidthPhase.REQUESTING,
            basisColumns = baseline.first,
            basisRows = baseline.second,
            targetColumns = target.first,
            targetRows = target.second,
        )
        setPhoneWidthState(surfaceId, state)
        sendPhoneWidthReport(surfaceId, target.first, target.second, force = true)
    }

    /** Manual refit from the width panel, using the latest canvas metrics. */
    fun reapplyPhoneWidth(surfaceId: String) {
        val state = _phoneWidthStates.value[surfaceId]
        if (state?.enabled != true) {
            setPhoneWidthEnabled(surfaceId, true)
            return
        }
        val target = computePhoneWidthTarget(widthMetrics[surfaceId], state.basisRows)
            ?: return
        setPhoneWidthState(
            surfaceId,
            state.copy(targetColumns = target.first, targetRows = target.second)
        )
        sendPhoneWidthReport(surfaceId, target.first, target.second, force = true)
    }

    /** Releases every surface's report (app backgrounded, connection ending). */
    fun releaseAllPhoneWidths() {
        val enabledSurfaces = _phoneWidthStates.value
            .filterValues { it.enabled || it.phase == PhoneWidthPhase.RESTORING }
            .keys
            .toList()
        enabledSurfaces.forEach { releasePhoneWidth(it) }
    }

    /** Font size or canvas size changed; refit after the gesture settles. */
    fun updatePhoneWidthMetrics(
        surfaceId: String,
        metrics: TerminalWidthMetrics?,
        autoRefitPaused: Boolean = false,
    ) {
        if (metrics == null) {
            widthMetrics.remove(surfaceId)
        } else {
            widthMetrics[surfaceId] = metrics
        }
        val state = _phoneWidthStates.value[surfaceId] ?: return
        if (!state.enabled || metrics == null || autoRefitPaused) return
        val target = computePhoneWidthTarget(metrics, state.basisRows ?: state.basisColumns) ?: return
        if (target.first == state.targetColumns && target.second == state.targetRows) return
        setPhoneWidthState(
            surfaceId,
            state.copy(targetColumns = target.first, targetRows = target.second)
        )
        schedulePhoneWidthReport(surfaceId, target.first, target.second)
    }

    private fun viewportResizeSupported(): Boolean =
        _gatewayCapabilities.value.contains("terminal.viewport.v1")

    private fun baselineFor(surfaceId: String): Pair<Int, Int>? =
        macBasis[surfaceId]
            ?: _activeScreenState.value
                .takeIf { it.surfaceId == surfaceId && it.columns > 0 && it.rows > 0 }
                ?.let { it.columns to it.rows }

    private fun onAuthoritativeFrame(
        surfaceId: String,
        columns: Int,
        rows: Int,
        receivedAtNanos: Long,
    ) {
        if (columns <= 0 || rows <= 0) return
        val state = _phoneWidthStates.value[surfaceId]
        if (state == null || (!state.enabled && state.phase != PhoneWidthPhase.RESTORING)) {
            // Frames right after a release can still carry the narrowed grid;
            // only later full baselines are trustworthy as the computer basis.
            val invalidUntil = macBasisInvalidUntil[surfaceId] ?: 0L
            if (receivedAtNanos >= invalidUntil) {
                macBasis[surfaceId] = columns to rows
                macBasisInvalidUntil.remove(surfaceId)
            }
            return
        }
        if (!state.enabled) return
        val confirmedAfter = widthConfirmedAtNanos[surfaceId] ?: 0L
        val updated = phoneWidthStateWithFrame(
            state = state,
            columns = columns,
            rows = rows,
            receivedAtNanos = receivedAtNanos,
            confirmedAfterNanos = confirmedAfter,
        )
        if (updated != state) {
            setPhoneWidthState(surfaceId, updated)
        }
    }

    private fun schedulePhoneWidthReport(surfaceId: String, columns: Int, rows: Int) {
        widthRequestJobs.remove(surfaceId)?.cancel()
        widthRequestJobs[surfaceId] = viewModelScope.launch {
            // A pinch emits one font change per sample; only the last target is
            // worth a resize, so the debounce doubles as gesture coalescing.
            delay(PHONE_WIDTH_DEBOUNCE_MS)
            sendPhoneWidthReport(surfaceId, columns, rows)
        }
    }

    private fun sendPhoneWidthReport(
        surfaceId: String,
        columns: Int,
        rows: Int,
        force: Boolean = false,
    ) {
        val state = _phoneWidthStates.value[surfaceId] ?: return
        if (!state.enabled) return
        val targetColumns = columns.coerceIn(PHONE_WIDTH_MIN_COLUMNS, PHONE_WIDTH_MAX_COLUMNS)
        val targetRows = rows.coerceIn(PHONE_WIDTH_MIN_ROWS, PHONE_WIDTH_MAX_ROWS)
        if (!force &&
            state.phase == PhoneWidthPhase.ACTIVE &&
            state.effectiveColumns == targetColumns &&
            state.effectiveRows == targetRows
        ) {
            return
        }
        val generation = nextViewportGeneration()
        widthConfirmedAtNanos.remove(surfaceId)
        setPhoneWidthState(
            surfaceId,
            state.copy(
                phase = PhoneWidthPhase.REQUESTING,
                targetColumns = targetColumns,
                targetRows = targetRows,
                generation = generation,
                notice = PhoneWidthNotice.NONE,
            )
        )
        viewModelScope.launch {
            try {
                wsClient.requestViewportReport(surfaceId, targetColumns, targetRows, generation)
                widthConfirmedAtNanos[surfaceId] = System.nanoTime()
                delay(PHONE_WIDTH_CONFIRM_GRACE_MS)
                val latest = _phoneWidthStates.value[surfaceId] ?: return@launch
                if (latest.generation != generation || latest.phase != PhoneWidthPhase.REQUESTING) {
                    return@launch
                }
                // No matching baseline arrived in the grace window. The host
                // still owns the grid; report what it actually shows instead of
                // claiming the requested size.
                val screen = _activeScreenState.value
                if (screen.surfaceId == surfaceId && screen.columns > 0) {
                    setPhoneWidthState(
                        surfaceId,
                        latest.copy(
                            phase = PhoneWidthPhase.ACTIVE,
                            effectiveColumns = screen.columns,
                            effectiveRows = screen.rows,
                        )
                    )
                } else {
                    setPhoneWidthState(
                        surfaceId,
                        latest.copy(phase = PhoneWidthPhase.FAILED, notice = PhoneWidthNotice.REQUEST_FAILED)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Viewport report failed for $surfaceId", e)
                val latest = _phoneWidthStates.value[surfaceId]
                if (latest != null && latest.generation == generation) {
                    setPhoneWidthState(
                        surfaceId,
                        latest.copy(phase = PhoneWidthPhase.FAILED, notice = PhoneWidthNotice.REQUEST_FAILED)
                    )
                }
            }
        }
    }

    private fun releasePhoneWidth(surfaceId: String) {
        widthRequestJobs.remove(surfaceId)?.cancel()
        val state = _phoneWidthStates.value[surfaceId] ?: return
        if (!state.enabled && state.phase != PhoneWidthPhase.RESTORING) {
            setPhoneWidthState(surfaceId, PhoneWidthState())
            return
        }
        val generation = nextViewportGeneration()
        setPhoneWidthState(surfaceId, state.copy(phase = PhoneWidthPhase.RESTORING, generation = generation))
        viewModelScope.launch {
            val cleared = runCatching {
                wsClient.clearViewportReport(surfaceId, generation)
            }.isSuccess
            macBasis.remove(surfaceId)
            macBasisInvalidUntil[surfaceId] = System.nanoTime() + MAC_BASIS_INVALIDATE_NANOS
            // Stop renewing either way: the Gateway drops the lease before it
            // asks the host, so a failed clear still expires within the host's
            // five-second report TTL.
            setPhoneWidthState(
                surfaceId,
                PhoneWidthState(
                    phase = PhoneWidthPhase.OFF,
                    notice = if (cleared) PhoneWidthNotice.NONE else PhoneWidthNotice.CLEAR_UNCONFIRMED,
                )
            )
        }
    }

    private fun handleConnectionLost() {
        widthRequestJobs.values.forEach { it.cancel() }
        widthRequestJobs.clear()
        widthConfirmedAtNanos.clear()
        macBasis.clear()
        macBasisInvalidUntil.clear()
        widthMetrics.clear()
        _gatewayCapabilities.value = emptySet()
        _phoneWidthStates.value = emptyMap()
    }

    private fun nextViewportGeneration(): Long {
        lastViewportGeneration = maxOf(System.currentTimeMillis(), lastViewportGeneration + 1)
        return lastViewportGeneration
    }

    private fun setPhoneWidthState(surfaceId: String, state: PhoneWidthState) {
        val next = _phoneWidthStates.value.toMutableMap()
        if (state.phase == PhoneWidthPhase.OFF && state.notice == PhoneWidthNotice.NONE) {
            next.remove(surfaceId)
        } else {
            next[surfaceId] = state
        }
        _phoneWidthStates.value = next
    }

    private companion object {
        const val PHONE_WIDTH_DEBOUNCE_MS = 300L
        const val PHONE_WIDTH_CONFIRM_GRACE_MS = 1_200L
        const val MAC_BASIS_INVALIDATE_NANOS = 700_000_000L
    }
}

/** How long the wake card waits for the Gateway's `workspace.tree` sync. */
private const val WAKE_TIMEOUT_MS = 15_000L

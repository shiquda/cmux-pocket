package app.cmuxpocket.ui

import app.cmuxpocket.engine.*
import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.protocol.WorkspaceInfo
import app.cmuxpocket.protocol.WorkspaceListResponse
import app.cmuxpocket.protocol.WorkspaceSelection
import app.cmuxpocket.transport.CmuxWebSocketClient
import app.cmuxpocket.transport.ConnectionStatus
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.ConcurrentHashMap

enum class AppSyncPhase {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    SYNCING,
    READY
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

    private val _workspaces = MutableStateFlow<List<WorkspaceInfo>>(emptyList())
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

    private var currentScreenCollectorJob: Job? = null
    private var currentBootstrapJob: Job? = null
    private var scrollDeliveryJob: Job? = null
    private var pendingScrollSurfaceId: String? = null
    private var pendingScrollLines = 0.0
    private val awaitingReplaySurfaces = ConcurrentHashMap<String, Boolean>()
    private var connectionGeneration: Long = 0

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

        // 2. Watch connection status & advance lifecycle phases
        viewModelScope.launch {
            wsClient.statusFlow.collect { status ->
                when (status) {
                    ConnectionStatus.CONNECTING -> {
                        _syncPhase.value = AppSyncPhase.CONNECTING
                        _statusMessage.value = "Connecting..."
                    }
                    ConnectionStatus.AUTHENTICATING -> {
                        _syncPhase.value = AppSyncPhase.AUTHENTICATING
                        _statusMessage.value = "Authenticating..."
                    }
                    ConnectionStatus.CONNECTED -> {
                        _syncPhase.value = AppSyncPhase.SYNCING
                        _statusMessage.value = "Syncing workspaces..."
                        connectionGeneration++
                        awaitingReplaySurfaces.clear()
                        mutationTracker.reset(connectionGeneration)
                        subscribeAndBootstrap(connectionGeneration)
                    }
                    ConnectionStatus.ERROR -> {
                        _syncPhase.value = AppSyncPhase.DISCONNECTED
                        _statusMessage.value = "Connection Error"
                        awaitingReplaySurfaces.clear()
                        mutationTracker.clear()
                    }
                    ConnectionStatus.DISCONNECTED -> {
                        _syncPhase.value = AppSyncPhase.DISCONNECTED
                        _statusMessage.value = "Disconnected"
                        awaitingReplaySurfaces.clear()
                        mutationTracker.clear()
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
        val url = ConnectionEndpoint.websocketUrl(host, port)
        _statusMessage.value = "Connecting to gateway..."
        wsClient.connect(url, token)
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

    fun disconnect() {
        wsClient.disconnect()
        awaitingReplaySurfaces.clear()
        mutationTracker.clear()
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
            try {
                wsClient.subscribeEvents(listOf("terminal.render_grid", "mobile.sync.delta", "workspace.tree"))
                val wsElement = wsClient.callRpc("mobile.workspace.list")
                if (generation == connectionGeneration) {
                    val listResp = json.decodeFromJsonElement(WorkspaceListResponse.serializer(), wsElement)
                    updateWorkspaces(listResp.workspaces, syncReconciledFocus = false)
                    _selectedSurfaceId.value?.let { surfaceId ->
                        wsClient.focusSurface(surfaceId)
                        requestSingleFlightReplay(surfaceId)
                    }
                    _syncPhase.value = AppSyncPhase.READY
                    _statusMessage.value = "Ready"
                    Log.i(tag, "Bootstrap complete for generation $generation! Total workspaces=${listResp.workspaces.size}")
                }
            } catch (e: Exception) {
                if (generation == connectionGeneration) {
                    Log.e(tag, "Failed in bootstrap", e)
                    _syncPhase.value = AppSyncPhase.DISCONNECTED
                    _statusMessage.value = "Bootstrap failed: ${e.message}"
                }
            }
        }
    }

    fun updateWorkspaces(list: List<WorkspaceInfo>, syncReconciledFocus: Boolean = true) {
        val prevSurfaceId = _selectedSurfaceId.value
        _workspaces.value = list
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

        // When enabled, sync focus to gateway whenever selection changes (including null -> new and old -> null/new)
        if (syncReconciledFocus && prevSurfaceId != newSurfaceId) {
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

    private fun userSelectSurface(surfaceId: String?) {
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

    fun createWorkspace(name: String, initialTerminal: Boolean = true) {
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
}

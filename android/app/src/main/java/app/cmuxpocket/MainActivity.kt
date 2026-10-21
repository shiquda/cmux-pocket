package app.cmuxpocket

import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.protocol.WorkspaceInfo
import app.cmuxpocket.transport.ConnectionStatus
import app.cmuxpocket.ui.*
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.res.Configuration
import android.content.Context
import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.inputmethod.InputMethodManager
import java.lang.ref.WeakReference
import kotlin.math.roundToInt
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private val viewModel: TerminalViewModel by viewModels()
    private val settingsManager by lazy { SettingsManager(this) }
    private var inPictureInPicture by mutableStateOf(false)
    private var floatingWindowEnabled = false
    private var floatingWindowAutoEnter = true
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /**
     * Applies the in-app dictionary choice to every resource lookup in this activity. The stored
     * choice is read once in [CmuxPocketApp] so this never touches the keystore on the startup path.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLanguage(AppLanguageStore.current))
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipActivity = WeakReference(this)
        ContextCompat.startForegroundService(
            this,
            Intent(this, ConnectionKeepAliveService::class.java)
        )
        AgentCompletionNotifications.createChannel(this)
        requestNotificationPermissionIfNeeded()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.agentSessionCompletions.collect { completion ->
                    // The foreground UI answers completions with a haptic pulse or an in-app
                    // capsule; a system notification on top of that would double the alert.
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@collect
                    AgentCompletionNotifications.show(
                        this@MainActivity,
                        completion,
                        sessionNotificationLabel(completion, viewModel.workspaces.value)
                    )
                }
            }
        }
        handleDeepLinkIntent(intent)
        handleNotificationIntent(intent)
        syncFloatingWindowSettings()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                viewModel.activeSurface.collect { updatePictureInPictureParams() }
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                viewModel.onAppForegrounded()
            }
        })
        setContent {
            CmuxAppRoot(
                viewModel = viewModel,
                isInPictureInPicture = inPictureInPicture,
                onEnterFloatingWindow = ::enterFloatingWindow,
                onFloatingWindowSettingsChanged = ::onFloatingWindowSettingsChanged
            )
        }
    }

    override fun onResume() {
        super.onResume()
        syncFloatingWindowSettings()
        updatePictureInPictureParams()
    }

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            floatingWindowEnabled && floatingWindowAutoEnter
        ) {
            enterFloatingWindow()
        }
        super.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onDestroy() {
        if (pipActivity?.get() === this) {
            pipActivity = null
        }
        super.onDestroy()
    }

    private fun syncFloatingWindowSettings() {
        floatingWindowEnabled = settingsManager.floatingWindowEnabled
        floatingWindowAutoEnter = settingsManager.floatingWindowAutoEnter
    }

    private fun onFloatingWindowSettingsChanged() {
        syncFloatingWindowSettings()
        updatePictureInPictureParams()
    }

    private fun enterFloatingWindow() {
        if (isInPictureInPictureMode) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        if (viewModel.activeSurface.value == null) {
            Toast.makeText(this, getString(R.string.floating_window_no_surface), Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
        try {
            enterPictureInPictureMode(buildPictureInPictureParams())
        } catch (error: IllegalStateException) {
            Log.w("MainActivity", "Unable to enter picture-in-picture", error)
        }
    }

    private fun buildPictureInPictureParams(): PictureInPictureParams {
        val ratio = settingsManager.pipAspectRatio
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
            .setActions(listOf(buildPipSizeAction(ratio)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(
                floatingWindowEnabled &&
                    floatingWindowAutoEnter &&
                    viewModel.activeSurface.value != null
            )
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun buildPipSizeAction(currentRatio: Float): RemoteAction {
        val next = PipSizePreset.nextAfter(currentRatio)
        val intent = Intent(this, PipActionReceiver::class.java)
            .setAction(PipActionReceiver.ACTION_CYCLE_SIZE)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_pip_size),
            getString(R.string.floating_window_size_action),
            getString(R.string.floating_window_size_action_desc, next.label),
            pendingIntent
        )
    }

    private fun updatePictureInPictureParams() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        runCatching { setPictureInPictureParams(buildPictureInPictureParams()) }
            .onFailure { Log.w("MainActivity", "Unable to update picture-in-picture params", it) }
    }

    internal fun onPipSizeChanged() {
        updatePictureInPictureParams()
    }

    companion object {
        private var pipActivity: WeakReference<MainActivity>? = null

        fun notifyPipSizeChanged() {
            val activity = pipActivity?.get() ?: return
            activity.runOnUiThread { activity.onPipSizeChanged() }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val surfaceId = intent?.getStringExtra(AgentCompletionNotifications.extraSurfaceId) ?: return
        val workspaceId = intent.getStringExtra(AgentCompletionNotifications.extraWorkspaceId)
        android.util.Log.d("CmuxPocket", "Notification tap → workspace=$workspaceId surface=$surfaceId")
        viewModel.navigateToSurface(workspaceId, surfaceId)
        intent.removeExtra(AgentCompletionNotifications.extraSurfaceId)
        intent.removeExtra(AgentCompletionNotifications.extraWorkspaceId)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme?.equals("cmux-pocket", ignoreCase = true) == true) {
            viewModel.setPendingImportUri(uri.toString())
            intent.data = null
        }
    }
}

@Composable
fun CmuxAppRoot(
    viewModel: TerminalViewModel,
    isInPictureInPicture: Boolean,
    onEnterFloatingWindow: () -> Unit,
    onFloatingWindowSettingsChanged: () -> Unit
) {
    val context = LocalContext.current
    val settingsManager = remember { CmuxPocketApp.settingsManager }
    val pendingImportUri by viewModel.pendingImportUri.collectAsState()
    var importPayload by remember { mutableStateOf<CmuxImportPayload?>(null) }
    var importErrorMessage by remember { mutableStateOf<String?>(null) }
    var showQrScanner by remember { mutableStateOf(false) }

    LaunchedEffect(pendingImportUri) {
        val rawUri = pendingImportUri
        if (rawUri != null) {
            try {
                importPayload = CmuxImportUri.parse(rawUri)
                importErrorMessage = null
            } catch (e: Exception) {
                importPayload = null
                importErrorMessage = e.message ?: context.getString(R.string.main_invalid_config_link)
            }
        }
    }

    var themeMode by remember { mutableStateOf(settingsManager.themeMode) }
    var selectedThemeHex by remember { mutableStateOf(settingsManager.terminalBgTheme) }
    var hostInput by remember { mutableStateOf(settingsManager.host) }
    var portInput by remember { mutableStateOf(settingsManager.port.toString()) }
    var tokenInput by remember { mutableStateOf(settingsManager.token) }
    var fontSizeSp by remember { mutableStateOf(settingsManager.fontSizeSp) }
    var floatingWindowEnabled by remember { mutableStateOf(settingsManager.floatingWindowEnabled) }
    CmuxAppTheme(themeMode = themeMode) {
        if (isInPictureInPicture) {
            FloatingTerminalView(
                viewModel = viewModel,
                themeHex = selectedThemeHex,
                fontSizeSp = settingsManager.pipFontSizeSp
            )
            return@CmuxAppTheme
        }
        CmuxApp(
            viewModel = viewModel,
            settingsManager = settingsManager,
            hostInput = hostInput,
            portInput = portInput,
            tokenInput = tokenInput,
            fontSizeSp = fontSizeSp,
            selectedThemeHex = selectedThemeHex,
            themeMode = themeMode,
            floatingWindowEnabled = floatingWindowEnabled,
            onEnterFloatingWindow = onEnterFloatingWindow,
            onFloatingWindowSettingsChanged = {
                floatingWindowEnabled = settingsManager.floatingWindowEnabled
                onFloatingWindowSettingsChanged()
            },
            onScanQrClick = { showQrScanner = true },

            onHostChange = {
                hostInput = it
                settingsManager.host = it
            },
            onPortChange = {
                portInput = it
                settingsManager.port = it.toIntOrNull() ?: 8088
            },
            onTokenChange = {
                tokenInput = it
                settingsManager.token = it
            },
            onFontSizeChange = {
                fontSizeSp = it
                settingsManager.fontSizeSp = it
            },
            onThemeHexChange = {
                selectedThemeHex = it
                settingsManager.terminalBgTheme = it
            },
            onThemeModeChange = {
                themeMode = it
                settingsManager.themeMode = it
            },
        )

        importPayload?.let { payload ->
            ImportPreviewDialog(
                payload = payload,
                settingsManager = settingsManager,
                onDismiss = {
                    importPayload = null
                    viewModel.clearPendingImportUri()
                },
                onImportConfirmed = { profile, connectImmediately ->
                    settingsManager.upsertProfile(profile)
                    settingsManager.activeProfileId = profile.id
                    hostInput = profile.host
                    portInput = profile.port.toString()
                    tokenInput = profile.token
                    settingsManager.host = profile.host
                    settingsManager.port = profile.port
                    settingsManager.token = profile.token
                    importPayload = null
                    viewModel.clearPendingImportUri()
                    if (connectImmediately) {
                        viewModel.connect(profile.host, profile.port, profile.token)
                    }
                }
            )
        }

        importErrorMessage?.let { errMsg ->
            ImportErrorDialog(
                errorMessage = errMsg,
                onDismiss = {
                    importErrorMessage = null
                    viewModel.clearPendingImportUri()
                }
            )
        }

        if (showQrScanner) {
            QrScannerDialog(
                onDismiss = { showQrScanner = false },
                onQrCodeDetected = { qrText ->
                    showQrScanner = false
                    viewModel.setPendingImportUri(qrText)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CmuxApp(
    viewModel: TerminalViewModel,
    settingsManager: SettingsManager,
    hostInput: String,
    portInput: String,
    tokenInput: String,
    fontSizeSp: Float,
    selectedThemeHex: String,
    themeMode: ThemeMode,
    floatingWindowEnabled: Boolean,
    onEnterFloatingWindow: () -> Unit,
    onFloatingWindowSettingsChanged: () -> Unit,

    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onThemeHexChange: (String) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onScanQrClick: () -> Unit = {},
) {
    val theme = CmuxTheme.colors
    val status by viewModel.connectionStatus.collectAsState(initial = ConnectionStatus.DISCONNECTED)
    val statusMsg by viewModel.statusMessage.collectAsState()
    val connectionReadiness by viewModel.connectionReadiness.collectAsState()
    val wakeState by viewModel.wakeState.collectAsState()
    val refreshingHostStatus by viewModel.hostStatusRefreshing.collectAsState()
    val workspaces by viewModel.workspaces.collectAsState()
    val activeWorkspace by viewModel.activeWorkspace.collectAsState()
    val activeSurfaces by viewModel.activeSurfaces.collectAsState()
    val selectedSurfaceId by viewModel.selectedSurfaceId.collectAsState()

    val displaySurfaceId = selectedSurfaceId.orEmpty()
    val displayPort = portInput.toIntOrNull() ?: 8088
    val terminalDisplayState = remember(hostInput, displayPort, displaySurfaceId) {
        mutableStateOf(settingsManager.terminalDisplay(hostInput, displayPort, displaySurfaceId, fontSizeSp))
    }
    var terminalDisplay by terminalDisplayState
    // Persist the first view too, so changing another terminal's size does not change this one.
    LaunchedEffect(hostInput, displayPort, displaySurfaceId, terminalDisplay) {
        kotlinx.coroutines.delay(200)
        settingsManager.saveTerminalDisplay(hostInput, displayPort, displaySurfaceId, terminalDisplay)
    }
    DisposableEffect(hostInput, displayPort, displaySurfaceId) {
        onDispose {
            settingsManager.saveTerminalDisplay(hostInput, displayPort, displaySurfaceId, terminalDisplayState.value)
        }
    }
    val changeTerminalDisplay: (TerminalDisplayPreference) -> Unit = { terminalDisplay = it.normalized() }


    var isSidebarExpanded by remember { mutableStateOf(false) }
    var showSessionSurfaceDrawer by remember { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showKeyboardPanel by remember { mutableStateOf(false) }
    var pendingCloseSurface by remember { mutableStateOf<SurfaceInfo?>(null) }
    var pendingCloseWorkspace by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var renamingWorkspace by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var renamingSurface by remember { mutableStateOf<SurfaceInfo?>(null) }

    fun requestCloseSurface(surfaceId: String) {
        val surface = activeSurfaces.firstOrNull { it.id == surfaceId } ?: return
        val isActive = surface.id == selectedSurfaceId
        val isRunning = surface.agentState == "working" || surface.agentState == "needs_input"
        if (isActive || isRunning) {
            pendingCloseSurface = surface
        } else {
            viewModel.closeSurface(surfaceId)
        }
    }

    fun requestCloseWorkspace(workspaceKey: String) {
        val ws = workspaces.firstOrNull { it.stableKey == workspaceKey } ?: return
        val isActive = ws.stableKey == activeWorkspace?.stableKey
        if (isActive) {
            pendingCloseWorkspace = ws
        } else {
            viewModel.closeWorkspace(workspaceKey)
        }
    }
    var terminalInputView by remember { mutableStateOf<TerminalInputView?>(null) }
    val context = LocalContext.current

    // Completion feedback for the foreground: a pulse for the session on screen, a capsule for
    // every other one. While the app is backgrounded MainActivity posts a system notification
    // instead, so the two paths never alert the same event twice.
    var completionQueue by remember { mutableStateOf<List<SessionCompletionNotice>>(emptyList()) }
    val activeCompletion = completionQueue.firstOrNull()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSurfaces by rememberUpdatedState(activeSurfaces)
    val currentWorkspaces by rememberUpdatedState(workspaces)
    val currentSelectedSurfaceId by rememberUpdatedState(selectedSurfaceId)
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.agentSessionCompletions.collect { completion ->
                if (isCurrentSession(completion, currentSelectedSurfaceId, currentSurfaces)) {
                    if (settingsManager.vibrateOnSessionComplete) {
                        SessionCompletionFeedback.vibrate(context)
                    }
                } else {
                    val notice = sessionCompletionNotice(completion, currentWorkspaces)
                    completionQueue = completionQueue.filterNot { it.surfaceId == notice.surfaceId } + notice
                }
            }
        }
    }
    val activeCompletionKey = activeCompletion?.key
    LaunchedEffect(activeCompletionKey) {
        if (activeCompletionKey == null) return@LaunchedEffect
        kotlinx.coroutines.delay(6000)
        completionQueue = completionQueue.drop(1)
    }
    val openCompletion: (SessionCompletionNotice) -> Unit = { notice ->
        viewModel.navigateToSurface(notice.workspaceId, notice.surfaceId)
        completionQueue = completionQueue.filterNot { it.key == notice.key }
    }

    // Stable callbacks for the accessory bar: it sits inside the scope that recomposes on every
    // rendered terminal frame, so lambdas allocated per composition would drag all 14 keys
    // through recomposition on each frame.
    val accessorySendKey: (String) -> Unit = remember { { key -> viewModel.sendInput(key) } }
    val accessoryOpenKeyboardPanel: () -> Unit = remember { { showKeyboardPanel = true } }
    val accessoryOpenSettings: () -> Unit = remember { { showSettingsDialog = true } }
    val settingsOnDismiss: () -> Unit = remember { { showSettingsDialog = false } }
    val settingsOnClearScrollback: () -> Unit = remember { { viewModel.clearScrollback() } }
    val settingsOnConnect: () -> Unit = remember(hostInput, portInput, tokenInput) {
        { viewModel.connect(hostInput, portInput.toIntOrNull() ?: 8088, tokenInput) }
    }
    val currentOnFontSizeChange by rememberUpdatedState(onFontSizeChange)
    val currentTerminalDisplay by rememberUpdatedState(terminalDisplay)
    val settingsOnFontSizeChange: (Float) -> Unit = remember {
        { size ->
            currentOnFontSizeChange(size)
            changeTerminalDisplay(currentTerminalDisplay.copy(fontSizeSp = size, fitWidth = false, panX = 0f, panY = null))
        }
    }

    // Auto connect on launch with saved configuration
    LaunchedEffect(Unit) {
        viewModel.connect(hostInput, portInput.toIntOrNull() ?: 8088, tokenInput)
    }

    LaunchedEffect(status) {
        if (status == ConnectionStatus.ERROR) {
            Toast.makeText(context, context.getString(R.string.main_connection_error), Toast.LENGTH_SHORT).show()
        }
    }

    // Orientation is derived from the real window size rather than LocalConfiguration: the
    // activity handles configuration changes itself, so a rotation must not depend on the
    // configuration CompositionLocal being refreshed.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        if (isLandscape) {
            // LANDSCAPE LAYOUT: Collapsible Left Sidebar + Full Height Terminal Stage
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(theme.background)
            ) {
                LandscapeSidebar(
                    workspaces = workspaces,
                    activeWorkspace = activeWorkspace,
                    activeSurfaces = activeSurfaces,
                    selectedSurfaceId = selectedSurfaceId,
                    connectionStatus = status,
                    isExpanded = isSidebarExpanded,
                    onToggleExpand = { isSidebarExpanded = !isSidebarExpanded },
                    onSelectWorkspace = { key -> viewModel.selectWorkspace(key) },
                    onSelectSurface = { id -> viewModel.selectSurface(id) },
                    onCloseSurface = { id -> requestCloseSurface(id) },
                    onNewWorkspace = { viewModel.createWorkspace() },
                    onNewSurface = { viewModel.createSurface(activeWorkspace?.stableKey) },
                    onRenameWorkspace = { ws -> renamingWorkspace = ws },
                    onCloseWorkspace = { key -> requestCloseWorkspace(key) },
                    onRenameSurface = { surf -> renamingSurface = surf },
                    onSettingsClick = { showSettingsDialog = true },
                    onFloatingWindowClick = if (floatingWindowEnabled) onEnterFloatingWindow else null
                )
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    ConnectionReadinessNotice(
                        readiness = connectionReadiness,
                        onOpenSettings = { showSettingsDialog = true },
                        modifier = Modifier.statusBarsPadding()
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        TerminalStage(
                            surfaces = activeSurfaces,
                            screenStateFlow = viewModel.activeScreenState,
                            connectionStatus = status,
                            connectionReadiness = connectionReadiness,
                            wakeState = wakeState,
                            onWakeCmux = viewModel::wakeCmux,
                            displayPreference = terminalDisplay,
                            onDisplayPreferenceChange = changeTerminalDisplay,
                            themeHex = selectedThemeHex,
                            onTapCanvas = {
                                terminalInputView?.requestKeyboard()
                            },
                            onTerminalScroll = viewModel::scrollTerminal,
                            onNewSurfaceClick = { viewModel.createSurface(activeWorkspace?.stableKey) },
                            onSendKey = { key -> viewModel.sendInput(key) },
                            onClearScreen = { viewModel.clearScrollback() },
                            renderEnabled = !showSettingsDialog
                        )

                        // D2 breadcrumb pill: same entry point in landscape
                        FloatingIslandPill(
                            connectionStatus = status,
                            activeWorkspace = activeWorkspace,
                            activeSurface = activeSurfaces.firstOrNull { it.id == selectedSurfaceId } ?: activeSurfaces.firstOrNull(),
                            surfaceCount = activeSurfaces.size,
                            onClick = { showSessionSurfaceDrawer = true },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 8.dp)
                        )

                        SessionCompletionBannerHost(
                            notice = activeCompletion,
                            extraCount = (completionQueue.size - 1).coerceAtLeast(0),
                            onOpen = openCompletion,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 52.dp)
                        )
                    }

                    AccessoryKeyboardBar(
                        onSendKey = accessorySendKey,
                        onOpenKeyboardPanel = accessoryOpenKeyboardPanel,
                        onFloatingWindowClick = if (floatingWindowEnabled) onEnterFloatingWindow else null,
                        onSettingsClick = accessoryOpenSettings
                    )
                }
            }
        } else {
            // PORTRAIT LAYOUT: Proposal C (Terminal Pure · 极致沉浸悬浮岛)
            // 0dp fixed top bar consumption: terminal canvas 直接贴顶
            Scaffold(
                bottomBar = {
                        AccessoryKeyboardBar(
                            onSendKey = accessorySendKey,
                            onOpenKeyboardPanel = accessoryOpenKeyboardPanel,
                            onFloatingWindowClick = if (floatingWindowEnabled) onEnterFloatingWindow else null,
                            onSettingsClick = accessoryOpenSettings
                        )
                },
                containerColor = theme.background
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // Full Viewport Terminal Stage
                    TerminalStage(
                        surfaces = activeSurfaces,
                        screenStateFlow = viewModel.activeScreenState,
                        connectionStatus = status,
                        connectionReadiness = connectionReadiness,
                        wakeState = wakeState,
                        onWakeCmux = viewModel::wakeCmux,
                        displayPreference = terminalDisplay,
                        onDisplayPreferenceChange = changeTerminalDisplay,
                        themeHex = selectedThemeHex,
                        onTapCanvas = {
                            terminalInputView?.requestKeyboard()
                        },
                        onTerminalScroll = viewModel::scrollTerminal,
                        onNewSurfaceClick = { viewModel.createSurface(activeWorkspace?.stableKey) },
                        onSendKey = { key -> viewModel.sendInput(key) },
                        onClearScreen = { viewModel.clearScrollback() },
                        renderEnabled = !showSettingsDialog
                    )

                    // Minimalist frosted Floating Island Pill overlaid at top center
                    FloatingIslandPill(
                        connectionStatus = status,
                        activeWorkspace = activeWorkspace,
                        activeSurface = activeSurfaces.firstOrNull { it.id == selectedSurfaceId } ?: activeSurfaces.firstOrNull(),
                        surfaceCount = activeSurfaces.size,
                        onClick = { showSessionSurfaceDrawer = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 8.dp)
                    )

                    SessionCompletionBannerHost(
                        notice = activeCompletion,
                        extraCount = (completionQueue.size - 1).coerceAtLeast(0),
                        onOpen = openCompletion,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 52.dp)
                    )
                }
            }
        }

        // Single, stable root-level TerminalImeBridge instance
        TerminalImeBridge(
            onSendText = { text -> viewModel.sendInput(text) },
            onDelete = { viewModel.sendInput("\u007f") },
            onEnter = { viewModel.sendInput("\n") },
            onViewReady = { terminalInputView = it },
            modifier = Modifier.size(1.dp)
        )

        // Shared Sheets & Dialogs
        if (showKeyboardPanel) {
            ComputerKeyboardPanel(
                onSendKey = { key -> viewModel.sendInput(key) },
                onDismissRequest = { showKeyboardPanel = false },
                isLandscape = isLandscape
            )
        }

        if (showSessionSurfaceDrawer) {
            SessionSurfaceDrawer(
                workspaces = workspaces,
                activeWorkspace = activeWorkspace,
                surfaces = activeSurfaces,
                selectedSurfaceId = selectedSurfaceId,
                hostLabel = hostInput,
                onSelectSurface = { surfaceId -> viewModel.selectSurface(surfaceId) },
                onCloseSurface = { surfaceId -> requestCloseSurface(surfaceId) },
                onNewSurfaceClick = { viewModel.createSurface(activeWorkspace?.stableKey) },
                onSelectWorkspace = { key -> viewModel.selectWorkspace(key) },
                onNewWorkspaceClick = { viewModel.createWorkspace() },
                onRenameSurface = { surf -> renamingSurface = surf },
                onRenameWorkspace = { ws -> renamingWorkspace = ws },
                onCloseWorkspace = { key -> requestCloseWorkspace(key) },
                onSettingsClick = { showSettingsDialog = true },
                onDismissRequest = { showSessionSurfaceDrawer = false }
            )
        }



        pendingCloseSurface?.let { surface ->
            val running = surface.agentState == "working" || surface.agentState == "needs_input"
            AlertDialog(
                onDismissRequest = { pendingCloseSurface = null },
                title = { Text(stringResource(R.string.main_close_tab_title)) },
                text = {
                    Text(
                        if (running) {
                            stringResource(R.string.main_close_tab_running, surface.displayTitle)
                        } else {
                            stringResource(R.string.main_close_active_tab_desc, surface.displayTitle)
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val id = surface.id
                            pendingCloseSurface = null
                            viewModel.closeSurface(id)
                        }
                    ) {
                        Text(stringResource(R.string.main_close))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCloseSurface = null }) {
                        Text(stringResource(R.string.main_cancel))
                    }
                }
            )
        }

        pendingCloseWorkspace?.let { ws ->
            AlertDialog(
                onDismissRequest = { pendingCloseWorkspace = null },
                title = { Text(stringResource(R.string.main_close_active_workspace_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.main_close_active_workspace_desc,
                            ws.name,
                            ws.surfaces.size
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val key = ws.stableKey
                            pendingCloseWorkspace = null
                            viewModel.closeWorkspace(key)
                        }
                    ) {
                        Text(stringResource(R.string.main_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCloseWorkspace = null }) {
                        Text(stringResource(R.string.main_cancel))
                    }
                }
            )
        }

        renamingWorkspace?.let { ws ->
            RenameWorkspaceDialog(
                initialName = ws.name,
                onDismissRequest = { renamingWorkspace = null },
                onConfirm = { newName ->
                    renamingWorkspace = null
                    viewModel.renameWorkspace(ws.stableKey, newName)
                }
            )
        }

        renamingSurface?.let { surf ->
            RenameSurfaceDialog(
                initialTitle = surf.displayTitle,
                onDismissRequest = { renamingSurface = null },
                onConfirm = { newTitle ->
                    renamingSurface = null
                    viewModel.renameSurface(surf.id, newTitle)
                }
            )
        }

        // Full-screen settings page (never a floating overlay over the terminal).
        if (showSettingsDialog) {
            BackHandler(onBack = settingsOnDismiss)
            SettingsScreen(
                hostInput = hostInput,
                portInput = portInput,
                tokenInput = tokenInput,
                fontSizeSp = terminalDisplay.fontSizeSp,
                selectedThemeHex = selectedThemeHex,
                themeMode = themeMode,
                connectionStatus = status,
                connectionReadiness = connectionReadiness,
                settingsManager = settingsManager,


                onHostChange = onHostChange,
                onPortChange = onPortChange,
                onTokenChange = onTokenChange,
                onFontSizeChange = settingsOnFontSizeChange,
                onThemeChange = onThemeHexChange,
                onThemeModeChange = onThemeModeChange,
                onConnectClick = settingsOnConnect,
                onScanQrClick = onScanQrClick,
                onClearScrollback = settingsOnClearScrollback,
                onRecheckGatewayVersion = viewModel::refreshHostStatus,
                isRefreshingGatewayVersion = refreshingHostStatus,
                onFloatingWindowSettingsChanged = onFloatingWindowSettingsChanged,
                onDismissRequest = settingsOnDismiss
            )
        }
    }

}

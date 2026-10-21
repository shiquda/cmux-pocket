package app.cmuxpocket.ui

import app.cmuxpocket.R
import androidx.compose.ui.res.stringResource
import app.cmuxpocket.engine.TerminalScreenState
import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.transport.ConnectionStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalStage(
    surfaces: List<SurfaceInfo>,
    activeScreenState: TerminalScreenState? = null,
    screenStateFlow: StateFlow<TerminalScreenState>? = null,
    connectionStatus: ConnectionStatus,
    connectionReadiness: ConnectionReadiness = ConnectionReadiness(),
    wakeState: CmuxWakeState = CmuxWakeState.IDLE,
    onWakeCmux: () -> Unit = {},
    onTapCanvas: () -> Unit,
    onTerminalScroll: (Double) -> Unit,
    onNewSurfaceClick: () -> Unit,
    modifier: Modifier = Modifier,
    displayPreference: TerminalDisplayPreference = TerminalDisplayPreference(),
    onDisplayPreferenceChange: (TerminalDisplayPreference) -> Unit = {},
    themeHex: String = "#1E1E1E",
    onSendKey: (String) -> Unit = {},
    onClearScreen: () -> Unit = {},
    renderEnabled: Boolean = true,
    phoneWidth: PhoneWidthState? = null,
    viewportResizeSupported: Boolean = false,
    onPhoneWidthEnabledChange: (Boolean) -> Unit = {},
    onPhoneWidthReapply: () -> Unit = {},
    onPhoneWidthMetrics: (TerminalWidthMetrics?, Boolean) -> Unit = { _, _ -> },
) {
    val theme = CmuxTheme.colors
    val stageBg = try {
        Color(android.graphics.Color.parseColor(themeHex))
    } catch (_: Exception) {
        theme.background
    }

    val effectiveScreenState by if (screenStateFlow != null) {
        if (renderEnabled) {
            screenStateFlow.collectAsState()
        } else {
            remember { mutableStateOf(screenStateFlow.value) }
        }
    } else {
        rememberUpdatedState(activeScreenState ?: TerminalScreenState())
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(stageBg)
    ) {
        if (surfaces.isEmpty()) {
            val host = connectionReadiness.hostStatus
            if (connectionStatus == ConnectionStatus.CONNECTED &&
                host != null && host.cmuxInstalled && !host.cmuxRunning
            ) {
                CmuxStoppedCard(
                    canWake = host.canWake,
                    wakeState = wakeState,
                    onWakeCmux = onWakeCmux,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                )
            } else {
                // Empty Workspace state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = theme.onSurfaceVariant,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.stage_no_tabs),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.stage_no_tabs_hint),
                        fontSize = 13.sp,
                        color = theme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onNewSurfaceClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.primary,
                            contentColor = theme.onPrimary
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.stage_add_tab), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Active Terminal Canvas
            TerminalCanvas(
                screenState = effectiveScreenState,
                renderEnabled = renderEnabled,
                onTap = onTapCanvas,
                onTerminalScroll = onTerminalScroll,
                displayPreference = displayPreference,
                onDisplayPreferenceChange = onDisplayPreferenceChange,
                themeHex = themeHex,
                onSendKey = onSendKey,
                onClearScreen = onClearScreen,
                modifier = Modifier.fillMaxSize(),
                phoneWidth = phoneWidth,
                viewportResizeSupported = viewportResizeSupported,
                onPhoneWidthEnabledChange = onPhoneWidthEnabledChange,
                onPhoneWidthReapply = onPhoneWidthReapply,
                onPhoneWidthMetrics = onPhoneWidthMetrics,
            )
        }

        // Offline / Reconnecting Banner
        if (connectionStatus == ConnectionStatus.CONNECTING || connectionStatus == ConnectionStatus.AUTHENTICATING) {
            Surface(
                color = theme.scrim,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = theme.accentOrange
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.stage_syncing),
                        fontSize = 11.sp,
                        color = theme.accentOrange
                    )
                }
            }
        }
    }
}

/**
 * Empty stage shown when the Gateway is reachable but cmux.app is not running.
 * Offers a single remote wake action with an explicit progress/error state.
 */
@Composable
private fun CmuxStoppedCard(
    canWake: Boolean,
    wakeState: CmuxWakeState,
    onWakeCmux: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = CmuxTheme.colors
    val busy = wakeState == CmuxWakeState.WAKING || wakeState == CmuxWakeState.SYNCING

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Computer,
            contentDescription = null,
            tint = theme.accentOrange,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(theme.accentOrange, RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.stage_cmux_stopped_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = theme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.stage_cmux_stopped_body),
            fontSize = 13.sp,
            color = theme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))

        if (canWake) {
            Button(
                onClick = onWakeCmux,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.primary,
                    contentColor = theme.onPrimary,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = theme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(
                        when (wakeState) {
                            CmuxWakeState.WAKING -> R.string.stage_waking_cmux
                            CmuxWakeState.SYNCING -> R.string.stage_waiting_cmux
                            CmuxWakeState.FAILED -> R.string.stage_wake_retry
                            CmuxWakeState.IDLE -> R.string.stage_wake_cmux
                        }
                    ),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.stage_wake_background_hint),
                fontSize = 11.sp,
                color = theme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            Text(
                text = stringResource(R.string.stage_wake_unavailable),
                fontSize = 12.sp,
                color = theme.accentOrange,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        if (wakeState == CmuxWakeState.FAILED) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.stage_wake_timeout),
                fontSize = 12.sp,
                color = theme.accentRed,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

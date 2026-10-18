package app.cmuxpocket.ui

import app.cmuxpocket.R
import androidx.compose.ui.res.stringResource
import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.protocol.WorkspaceInfo
import app.cmuxpocket.transport.ConnectionStatus
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Proposal D2: breadcrumb pill over the terminal viewport.
 *
 * `[status dot] workspace › #N session ▾ [surface count]`, one single tap target that opens the
 * unified [SessionSurfaceDrawer]. Fixed height consumption stays 0dp; the terminal renders
 * underneath and the pill floats above it.
 */
@Composable
fun FloatingIslandPill(
    connectionStatus: ConnectionStatus,
    activeWorkspace: WorkspaceInfo?,
    activeSurface: SurfaceInfo?,
    surfaceCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors

    val isWorking = connectionStatus == ConnectionStatus.CONNECTED &&
        (activeSurface?.agentState.equals("working", ignoreCase = true) ||
            activeSurface?.agentState.equals("busy", ignoreCase = true) ||
            activeSurface?.attention == true)

    // Pulse animation for Agent Working state
    val infiniteTransition = rememberInfiniteTransition(label = "island_pulse")
    val workingPulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val workingPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Blink animation for Connecting / Reconnecting state
    val connectingBlinkAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink_alpha"
    )

    val connectingLabel = stringResource(R.string.island_connecting)
    val disconnectedLabel = stringResource(R.string.island_disconnected)

    val workspaceName = activeWorkspace?.name?.takeIf { it.isNotBlank() } ?: "cmux"
    val surfaceNumber = if (activeSurface != null) "#${activeSurface.tabIndex + 1}" else null
    val surfaceTitle = activeSurface?.displayTitle ?: "terminal"

    // [dot colour, dot alpha, dot scale, breadcrumb text or status label, status-only]
    val state = when {
        connectionStatus == ConnectionStatus.CONNECTING ||
            connectionStatus == ConnectionStatus.AUTHENTICATING -> {
            IslandState(theme.accentYellow, connectingBlinkAlpha, 1.0f, connectingLabel, true)
        }
        connectionStatus == ConnectionStatus.ERROR ||
            connectionStatus == ConnectionStatus.DISCONNECTED -> {
            IslandState(theme.accentRed, 1.0f, 1.0f, disconnectedLabel, true)
        }
        isWorking -> {
            IslandState(theme.accentOrange, workingPulseAlpha, workingPulseScale, surfaceTitle, false)
        }
        else -> {
            IslandState(theme.accentGreen, 1.0f, 1.0f, surfaceTitle, false)
        }
    }

    Box(
        modifier = modifier
            .widthIn(max = 268.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(theme.islandBg)
            .border(1.dp, theme.islandBorder, RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(state.dotScale)
                    .clip(CircleShape)
                    .background(state.dotColor.copy(alpha = state.dotAlpha))
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (state.statusOnly) {
                Text(
                    text = state.title,
                    color = theme.onIsland,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                // Workspace › #N session
                Text(
                    text = workspaceName,
                    color = theme.onIslandMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Text(
                    text = " › ",
                    color = theme.onIslandMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                if (surfaceNumber != null) {
                    Text(
                        text = "$surfaceNumber ",
                        color = theme.onIsland,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                Text(
                    text = state.title,
                    color = theme.onIsland,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Dropdown chevron
            Text(
                text = "▾",
                color = theme.onIslandMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )

            if (surfaceCount > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(theme.primary.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = surfaceCount.toString(),
                        color = theme.primary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private data class IslandState(
    val dotColor: Color,
    val dotAlpha: Float,
    val dotScale: Float,
    val title: String,
    val statusOnly: Boolean
)

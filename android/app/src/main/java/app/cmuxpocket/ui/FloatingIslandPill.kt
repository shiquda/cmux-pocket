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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Proposal C: Minimalist Floating Island Pill (Panic Prompt 3 / Blink Shell style).
 * Overlaid at the top of the terminal viewport with 0dp fixed height consumption.
 */
@Composable
fun FloatingIslandPill(
    connectionStatus: ConnectionStatus,
    activeWorkspace: WorkspaceInfo?,
    activeSurface: SurfaceInfo?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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

    val (dotColor, dotAlpha, dotScale, titleText) = when {
        connectionStatus == ConnectionStatus.CONNECTING || connectionStatus == ConnectionStatus.AUTHENTICATING -> {
            Quad(Color(0xFFFFD60A), connectingBlinkAlpha, 1.0f, connectingLabel)
        }
        connectionStatus == ConnectionStatus.ERROR || connectionStatus == ConnectionStatus.DISCONNECTED -> {
            Quad(Color(0xFFFF453A), 1.0f, 1.0f, disconnectedLabel)
        }
        isWorking -> {
            val wsName = activeWorkspace?.name?.takeIf { it.isNotBlank() } ?: "cmux"
            val surfNum = if (activeSurface != null) "#${activeSurface.tabIndex + 1} " else ""
            val surfTitle = activeSurface?.displayTitle ?: "terminal"
            Quad(Color(0xFFFF9F0A), workingPulseAlpha, workingPulseScale, "$wsName: $surfNum$surfTitle")
        }
        else -> {
            val wsName = activeWorkspace?.name?.takeIf { it.isNotBlank() } ?: "cmux"
            val surfNum = if (activeSurface != null) "#${activeSurface.tabIndex + 1} " else ""
            val surfTitle = activeSurface?.displayTitle ?: "terminal"
            Quad(Color(0xFF30D158), 1.0f, 1.0f, "$wsName: $surfNum$surfTitle")
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xDD1C1C1E))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(dotScale)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = dotAlpha))
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Title
            Text(
                text = titleText,
                color = Color.White,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Dropdown chevron
            Text(
                text = "▾",
                color = Color(0xFF8E8E93),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

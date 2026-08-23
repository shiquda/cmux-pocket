package app.cmuxpocket.ui

import app.cmuxpocket.engine.TerminalScreenState
import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.transport.ConnectionStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TerminalStage(
    surfaces: List<SurfaceInfo>,
    activeScreenState: TerminalScreenState,
    connectionStatus: ConnectionStatus,
    onTapCanvas: () -> Unit,
    onTerminalScroll: (Double) -> Unit,
    onNewSurfaceClick: () -> Unit,
    modifier: Modifier = Modifier,
    userFontSizeSp: Float = 14.5f,
    themeHex: String = "#1E1E1E"
) {
    val theme = CmuxTheme.colors
    val stageBg = try {
        Color(android.graphics.Color.parseColor(themeHex))
    } catch (_: Exception) {
        theme.background
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(stageBg)
    ) {
        if (surfaces.isEmpty()) {
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
                    text = "No Active Terminal Tabs",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Create a new tab to start running commands",
                    fontSize = 13.sp,
                    color = theme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Button(
                    onClick = onNewSurfaceClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (theme.isDark) Color(0xFF00FF7F) else Color(0xFF007AFF),
                        contentColor = Color.Black
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
                    Text("Add Terminal Tab", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // Active Terminal Canvas
            TerminalCanvas(
                screenState = activeScreenState,
                onTap = onTapCanvas,
                onTerminalScroll = onTerminalScroll,
                userFontSizeSp = userFontSizeSp,
                themeHex = themeHex,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Offline / Reconnecting Banner
        if (connectionStatus == ConnectionStatus.CONNECTING || connectionStatus == ConnectionStatus.AUTHENTICATING) {
            Surface(
                color = Color(0xB3212121),
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
                        color = Color(0xFFFFB300)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Syncing with cmux host...",
                        fontSize = 11.sp,
                        color = Color(0xFFFFB300)
                    )
                }
            }
        }
    }
}

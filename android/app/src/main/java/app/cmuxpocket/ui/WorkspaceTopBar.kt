package app.cmuxpocket.ui

import app.cmuxpocket.protocol.WorkspaceInfo
import app.cmuxpocket.transport.ConnectionStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WorkspaceTopBar(
    workspace: WorkspaceInfo?,
    connectionStatus: ConnectionStatus,
    statusMessage: String,
    onWorkspaceClick: () -> Unit,
    onNewSurfaceClick: () -> Unit,
    onConnectSettingsClick: () -> Unit,
    onReconnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = theme.surface,
        contentColor = theme.onSurface
    ) {
        Column {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onWorkspaceClick)
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Workspace",
                        tint = theme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = workspace?.name ?: "cmux Workspaces",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Workspace",
                                tint = theme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (connectionStatus == ConnectionStatus.CONNECTED) {
                            WorkspaceMetaLine(
                                tabCount = workspace?.surfaces?.size ?: 0,
                                path = workspace?.pathLabel.orEmpty(),
                                color = theme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = statusMessage,
                                fontSize = 10.sp,
                                color = theme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onNewSurfaceClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New Tab",
                        tint = if (theme.isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                    )
                }
                IconButton(
                    onClick = onReconnectClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reconnect",
                        tint = theme.onSurface
                    )
                }
                IconButton(
                    onClick = onConnectSettingsClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    val statusColor = when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                        ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING -> Color(0xFFFF9800)
                        ConnectionStatus.ERROR -> Color(0xFFF44336)
                        ConnectionStatus.DISCONNECTED -> Color.Gray
                    }
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = theme.onSurface
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp)
                                .background(statusColor, shape = CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WorkspaceMetaLine(
    tabCount: Int,
    path: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (tabCount == 1) "1 tab" else "$tabCount tabs",
            fontSize = 10.sp,
            color = color,
            maxLines = 1
        )
        if (path.isNotBlank()) {
            Text(
                text = path,
                fontSize = 10.sp,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
    }
}

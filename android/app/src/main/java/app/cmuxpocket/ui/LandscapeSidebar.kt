package app.cmuxpocket.ui

import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.protocol.WorkspaceInfo
import app.cmuxpocket.transport.ConnectionStatus
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LandscapeSidebar(
    workspaces: List<WorkspaceInfo>,
    activeWorkspace: WorkspaceInfo?,
    activeSurfaces: List<SurfaceInfo>,
    selectedSurfaceId: String?,
    connectionStatus: ConnectionStatus,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onSelectSurface: (String) -> Unit,
    onCloseSurface: (String) -> Unit,
    onNewWorkspace: () -> Unit,
    onNewSurface: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val sidebarWidth by animateDpAsState(
        targetValue = if (isExpanded) 220.dp else 52.dp,
        animationSpec = tween(durationMillis = 180),
        label = "sidebarWidth"
    )

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(sidebarWidth),
        color = theme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 4.dp, horizontal = if (isExpanded) 6.dp else 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: 100% Clickable 48dp Top Bar for Collapse / Expand Toggle
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isExpanded) theme.surfaceVariant else Color.Transparent)
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isExpanded) {
                    // EXPANDED HEADER: Back Chevron + "cmux Pocket" + Status Dot
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(theme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "Collapse Sidebar",
                                    tint = theme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "cmux Pocket",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.onSurface
                            )
                        }

                        val statusColor = when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                            ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING -> Color(0xFFFF9800)
                            ConnectionStatus.ERROR -> Color(0xFFF44336)
                            ConnectionStatus.DISCONNECTED -> Color.Gray
                        }
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(10.dp)
                                .background(statusColor, shape = CircleShape)
                        )
                    }
                } else {
                    // COLLAPSED HEADER: Centered Large Menu Icon (100% clickable 52x48dp area)
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Expand Sidebar",
                            tint = theme.onSurface,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = theme.divider, modifier = Modifier.padding(vertical = 4.dp))

            // Main Content Area with Smooth Crossfade
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Crossfade(
                    targetState = isExpanded,
                    animationSpec = tween(150),
                    label = "sidebarContent"
                ) { expanded ->
                    if (expanded) {
                        // EXPANDED VIEW: Full workspace and surface list
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            item(key = "hdr_workspaces") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("WORKSPACES", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.onSurfaceVariant)
                                    IconButton(onClick = onNewWorkspace, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Add, contentDescription = "New Workspace", tint = if (theme.isDark) Color(0xFF81C784) else Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            items(workspaces, key = { "ws_${it.stableKey}" }) { ws ->
                                val isWsSelected = ws.stableKey == activeWorkspace?.stableKey
                                val bg = if (isWsSelected) theme.surfaceVariant else Color.Transparent

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(bg)
                                        .clickable { onSelectWorkspace(ws.stableKey) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = if (isWsSelected) theme.primary else theme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ws.name,
                                            fontSize = 12.sp,
                                            fontWeight = if (isWsSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isWsSelected) theme.onSurface else theme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        WorkspaceMetaLine(
                                            tabCount = ws.surfaces.size,
                                            path = ws.pathLabel,
                                            color = theme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            item(key = "hdr_tabs") {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("TABS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.onSurfaceVariant)
                                    IconButton(onClick = onNewSurface, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Add, contentDescription = "New Tab", tint = if (theme.isDark) Color(0xFF81C784) else Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }

                            items(activeSurfaces, key = { "surf_${it.id}" }) { surf ->
                                val isSurfSelected = surf.id == selectedSurfaceId
                                val surfBg = if (isSurfSelected) theme.tabActiveBg else Color.Transparent

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(surfBg)
                                        .clickable { onSelectSurface(surf.id) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = null,
                                        tint = if (isSurfSelected) (if (theme.isDark) Color(0xFF00FF7F) else Color(0xFF007AFF)) else theme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = surf.displayTitle,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSurfSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSurfSelected) theme.onSurface else theme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )

                                    // Agent Attention / Activity Dot
                                    if (surf.attention || surf.agentState != null) {
                                        val dotColor = when (surf.agentState) {
                                            "working" -> Color(0xFF2196F3)
                                            "needs_input" -> Color(0xFFFF9800)
                                            "done" -> Color(0xFF4CAF50)
                                            else -> Color(0xFFFF5722)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 4.dp)
                                                .size(7.dp)
                                                .background(dotColor, shape = CircleShape)
                                        )
                                    }

                                    if (activeSurfaces.size > 1) {
                                        IconButton(
                                            onClick = { onCloseSurface(surf.id) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close",
                                                tint = theme.onSurfaceVariant,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // COLLAPSED VIEW: Clean icon rail with 48dp touch targets
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(activeSurfaces, key = { "collapsed_surf_${it.id}" }) { surf ->
                                val isSurfSelected = surf.id == selectedSurfaceId
                                val iconBg = if (isSurfSelected) theme.tabActiveBg else Color.Transparent

                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(iconBg)
                                        .clickable { onSelectSurface(surf.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Terminal,
                                        contentDescription = surf.displayTitle,
                                        tint = if (isSurfSelected) (if (theme.isDark) Color(0xFF00FF7F) else Color(0xFF007AFF)) else theme.onSurfaceVariant,
                                        modifier = Modifier.size(22.dp)
                                    )

                                    if (surf.attention || surf.agentState != null) {
                                        val dotColor = when (surf.agentState) {
                                            "working" -> Color(0xFF2196F3)
                                            "needs_input" -> Color(0xFFFF9800)
                                            "done" -> Color(0xFF4CAF50)
                                            else -> Color(0xFFFF5722)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp)
                                                .size(6.dp)
                                                .background(dotColor, shape = CircleShape)
                                        )
                                    }
                                }
                            }

                            item(key = "collapsed_add") {
                                IconButton(
                                    onClick = onNewSurface,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add Tab",
                                        tint = if (theme.isDark) Color(0xFF81C784) else Color(0xFF2E7D32),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = theme.divider, modifier = Modifier.padding(vertical = 4.dp))

            // Bottom Actions: Settings with 48dp Touch Target
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = theme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

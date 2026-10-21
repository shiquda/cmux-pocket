package app.cmuxpocket.ui

import app.cmuxpocket.R
import androidx.compose.ui.res.stringResource
import app.cmuxpocket.protocol.SurfaceInfo
import app.cmuxpocket.protocol.WorkspaceInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSurfaceDrawer(
    workspaces: List<WorkspaceInfo>,
    activeWorkspace: WorkspaceInfo?,
    surfaces: List<SurfaceInfo>,
    selectedSurfaceId: String?,
    hostLabel: String,
    onSelectSurface: (String) -> Unit,
    onCloseSurface: (String) -> Unit,
    onNewSurfaceClick: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onNewWorkspaceClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = theme.containerBg,
        contentColor = theme.onContainer,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(theme.controlBorder)
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Header: Title & Close / Settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.drawer_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.onContainer
                    )
                    Text(
                        text = stringResource(R.string.drawer_host_summary, hostLabel, workspaces.size),
                        fontSize = 12.sp,
                        color = theme.onContainerMuted,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Settings icon button
                    IconButton(
                        onClick = {
                            onDismissRequest()
                            onSettingsClick()
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(theme.controlBg)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.kb_settings),
                            tint = theme.onContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Close icon button
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(theme.controlBg)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.main_close),
                            tint = theme.onContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: Surface cards grid
                item {
                    Column {
                        Text(
                            text = stringResource(R.string.drawer_active_surfaces),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.onContainerMuted,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(bottom = 8.dp, start = 2.dp)
                        )

                        // 2-column grid layout for surfaces
                        val surfacePairs = surfaces.chunked(2)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (pair in surfacePairs) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (surface in pair) {
                                        SurfaceCard(
                                            surface = surface,
                                            isSelected = surface.id == selectedSurfaceId,
                                            onClick = {
                                                onSelectSurface(surface.id)
                                                onDismissRequest()
                                            },
                                            onClose = { onCloseSurface(surface.id) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (pair.size == 1) {
                                        // Place the new surface button next to the odd item
                                        NewSurfaceCard(
                                            onClick = {
                                                onDismissRequest()
                                                onNewSurfaceClick()
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            // If even count, add new surface button as its own row
                            if (surfaces.size % 2 == 0) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    NewSurfaceCard(
                                        onClick = {
                                            onDismissRequest()
                                            onNewSurfaceClick()
                                        },
                                        modifier = Modifier.weight(0.5f)
                                    )
                                    Spacer(modifier = Modifier.weight(0.5f))
                                }
                            }
                        }
                    }
                }

                // Section 2: Workspace Switcher
                item {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp, start = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.drawer_workspace_switcher),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.onContainerMuted,
                                letterSpacing = 0.5.sp
                            )

                            Text(
                                text = stringResource(R.string.drawer_new),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.primary,
                                modifier = Modifier.clickable {
                                    onDismissRequest()
                                    onNewWorkspaceClick()
                                }
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            workspaces.forEach { ws ->
                                val isCurrent = ws.stableKey == activeWorkspace?.stableKey
                                WorkspaceCard(
                                    workspace = ws,
                                    isCurrent = isCurrent,
                                    onClick = {
                                        onSelectWorkspace(ws.stableKey)
                                        onDismissRequest()
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SurfaceCard(
    surface: SurfaceInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val isWorking = surface.agentState.equals("working", ignoreCase = true) ||
        surface.agentState.equals("busy", ignoreCase = true) ||
        surface.attention

    val cardBg = if (isSelected) theme.primary.copy(alpha = 0.15f) else theme.cardBg
    val cardBorder = if (isSelected) theme.primary else theme.cardBorder

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.5.dp, cardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "#${surface.tabIndex + 1} ${surface.displayTitle}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = theme.onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(4.dp))

                // Agent status badge
                if (isWorking) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(theme.accentOrange.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.drawer_state_working),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.accentOrange
                        )
                    }
                } else if (surface.dead) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(theme.accentRed.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.drawer_state_dead),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.accentRed
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(theme.controlBg)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.drawer_state_idle),
                            fontSize = 10.sp,
                            color = theme.onContainerMuted
                        )
                    }
                }
            }

            // Preview line
            val previewText = surface.cwd?.takeIf { it.isNotBlank() } ?: surface.type
            Text(
                text = previewText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = theme.onContainerMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NewSurfaceCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = 62.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.cardBg)
            .border(1.dp, theme.primary.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = theme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.drawer_new_surface),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = theme.primary
            )
        }
    }
}

@Composable
private fun WorkspaceCard(
    workspace: WorkspaceInfo,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    val theme = CmuxTheme.colors
    val cardBg = if (isCurrent) theme.accentGreen.copy(alpha = 0.13f) else theme.cardBg
    val cardBorder = if (isCurrent) theme.accentGreen.copy(alpha = 0.4f) else theme.cardBorder

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = if (isCurrent) theme.accentGreen else theme.onContainerMuted,
                modifier = Modifier.size(20.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column {
                Text(
                    text = if (isCurrent) stringResource(R.string.drawer_workspace_current, workspace.name) else workspace.name,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.onContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.drawer_workspace_meta,
                        workspace.surfaces.size,
                        workspace.pathLabel.ifBlank { stringResource(R.string.drawer_default_path) }
                    ),
                    fontSize = 11.sp,
                    color = theme.onContainerMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (isCurrent) {
            Text(
                text = "● " + stringResource(R.string.drawer_connected),
                fontSize = 12.sp,
                color = theme.accentGreen,
                fontWeight = FontWeight.Medium
            )
        } else {
            Text(
                text = stringResource(R.string.drawer_switch),
                fontSize = 12.sp,
                color = theme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

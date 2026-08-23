package app.cmuxpocket.ui

import app.cmuxpocket.protocol.SurfaceInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SurfaceTabBar(
    surfaces: List<SurfaceInfo>,
    selectedSurfaceId: String?,
    onSelectSurface: (String) -> Unit,
    onCloseSurface: (String) -> Unit,
    onNewSurface: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (surfaces.isEmpty()) {
        return
    }

    val theme = CmuxTheme.colors
    var dropdownExpanded by remember { mutableStateOf(false) }

    val selectedIndex = surfaces.indexOfFirst { it.id == selectedSurfaceId }.let {
        if (it == -1) 0 else it
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        color = theme.tabRowBg
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                containerColor = theme.tabRowBg,
                contentColor = theme.onSurface,
                edgePadding = 4.dp,
                indicator = { tabPositions ->
                    if (tabPositions.isNotEmpty() && selectedIndex < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                            color = if (theme.isDark) Color(0xFF00FF7F) else Color(0xFF007AFF),
                            height = 2.5.dp
                        )
                    }
                },
                divider = {}
            ) {
                surfaces.forEachIndexed { index, surface ->
                    val isSelected = index == selectedIndex
                    val tabBg = if (isSelected) theme.tabActiveBg else Color.Transparent

                    Tab(
                        selected = isSelected,
                        onClick = { onSelectSurface(surface.id) },
                        modifier = Modifier
                            .height(48.dp)
                            .padding(horizontal = 2.dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(tabBg),
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = "Terminal",
                                    tint = if (isSelected) (if (theme.isDark) Color(0xFF00FF7F) else Color(0xFF007AFF)) else theme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = surface.displayTitle,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) theme.onSurface else theme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 130.dp)
                                )

                                // Agent Activity & Attention Dot
                                if (surface.attention || surface.agentState != null) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val dotColor = when (surface.agentState) {
                                        "working" -> Color(0xFF2196F3)
                                        "needs_input" -> Color(0xFFFF9800)
                                        "done" -> Color(0xFF4CAF50)
                                        else -> Color(0xFFFF5722)
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(dotColor, shape = CircleShape)
                                    )
                                }

                                // Close action keeps the app-wide 48dp touch target.
                                if (surfaces.size > 1) {
                                    IconButton(
                                        onClick = { onCloseSurface(surface.id) },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close Tab",
                                            tint = theme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // Dropdown menu button with 48dp touch target
            Box {
                IconButton(
                    onClick = { dropdownExpanded = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "Surfaces Menu",
                        tint = theme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.background(theme.surface)
                ) {
                    surfaces.forEach { surface ->
                        val isSelected = surface.id == selectedSurfaceId
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = if (theme.isDark) Color(0xFF00FF7F) else Color(0xFF007AFF),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = surface.displayTitle,
                                        color = if (isSelected) theme.onSurface else theme.onSurfaceVariant,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            onClick = {
                                dropdownExpanded = false
                                onSelectSurface(surface.id)
                            },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }

                    HorizontalDivider(color = theme.divider)

                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New Tab",
                                    tint = if (theme.isDark) Color(0xFF81C784) else Color(0xFF2E7D32),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "New Tab",
                                    color = theme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        },
                        onClick = {
                            dropdownExpanded = false
                            onNewSurface()
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}

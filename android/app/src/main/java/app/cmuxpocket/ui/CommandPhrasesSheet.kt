package app.cmuxpocket.ui

import app.cmuxpocket.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

/**
 * In-place 48dp quick phrase bar swapped into the terminal text controls slot (Way A).
 *
 * Keeps zero extra vertical footprint: `✕` on the left returns to the text-size controls,
 * the scrollable chip strip sends on tap (long-press opens quick edit/reorder/delete actions),
 * and `+` / list buttons on the right open the editor dialog or full bottom sheet.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalQuickPhraseBar(
    phrases: List<CommandPhrase>,
    enabled: Boolean,
    onCloseBar: () -> Unit,
    onSendPhrase: (content: String, autoEnter: Boolean) -> Unit,
    onAddPhraseClick: () -> Unit,
    onEditPhraseClick: (CommandPhrase) -> Unit,
    onDeletePhrase: (String) -> Unit,
    onMovePhrase: (id: String, delta: Int) -> Unit,
    onOpenFullSheet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val closeLabel = stringResource(R.string.phrases_close_bar_cd)
    val addLabel = stringResource(R.string.phrases_add_cd)
    val openListLabel = stringResource(R.string.phrases_open_list_cd)
    val listState = rememberLazyListState()
    var knownIds by remember { mutableStateOf(phrases.map { it.id }) }

    LaunchedEffect(phrases) {
        val nextIds = phrases.map { it.id }
        if (nextIds.size == knownIds.size + 1 &&
            knownIds.isNotEmpty() &&
            nextIds.dropLast(1) == knownIds
        ) {
            listState.animateScrollToItem(phrases.lastIndex)
        }
        knownIds = nextIds
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onCloseBar)
                .semantics { contentDescription = closeLabel },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(theme.controlBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = theme.onContainer,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))
        VerticalDivider(
            color = theme.controlBorder,
            modifier = Modifier.height(20.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart
        ) {
            if (phrases.isEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(theme.primary.copy(alpha = if (enabled) 0.12f else 0.05f))
                        .border(
                            width = 1.dp,
                            color = theme.primary.copy(alpha = if (enabled) 0.45f else 0.2f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable(enabled = enabled, role = Role.Button, onClick = onAddPhraseClick)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = if (enabled) theme.primary else theme.onContainerMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = stringResource(R.string.phrases_add_first_chip),
                            color = if (enabled) theme.primary else theme.onContainerMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            } else {
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(phrases, key = { _, phrase -> phrase.id }) { index, phrase ->
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            Box(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(theme.cardBg)
                                    .border(1.dp, theme.controlBorder, RoundedCornerShape(16.dp))
                                    .combinedClickable(
                                        enabled = enabled,
                                        onClick = { onSendPhrase(phrase.content, phrase.autoEnter) },
                                        onLongClick = { menuExpanded = true }
                                    )
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = phrase.chipText(),
                                        color = if (enabled) theme.onContainer else theme.onContainerMuted,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 160.dp)
                                    )
                                    if (phrase.autoEnter) {
                                        Text(
                                            text = "⏎",
                                            color = if (enabled) theme.accentGreenInk else theme.onContainerMuted,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                modifier = Modifier.background(theme.containerBg)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(
                                                if (phrase.autoEnter) R.string.phrases_action_insert_only
                                                else R.string.phrases_action_send_with_enter
                                            ),
                                            color = theme.onContainer
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                                            contentDescription = null,
                                            tint = theme.onContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onSendPhrase(phrase.content, !phrase.autoEnter)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.phrases_action_edit), color = theme.onContainer) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = null,
                                            tint = theme.onContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onEditPhraseClick(phrase)
                                    }
                                )
                                if (index > 0) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.phrases_action_move_earlier), color = theme.onContainer) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = null,
                                                tint = theme.onContainer,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onMovePhrase(phrase.id, -1)
                                        }
                                    )
                                }
                                if (index < phrases.lastIndex) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.phrases_action_move_later), color = theme.onContainer) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                tint = theme.onContainer,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onMovePhrase(phrase.id, +1)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_delete), color = theme.accentRed) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = theme.accentRed,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onDeletePhrase(phrase.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        VerticalDivider(
            color = theme.controlBorder,
            modifier = Modifier.height(20.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))

        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onAddPhraseClick)
                .semantics { contentDescription = addLabel },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(theme.primary.copy(alpha = if (enabled) 0.14f else 0.06f))
                    .border(
                        width = 1.dp,
                        color = theme.primary.copy(alpha = if (enabled) 0.45f else 0.2f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = if (enabled) theme.primary else theme.onContainerMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(2.dp))

        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, role = Role.Button, onClick = onOpenFullSheet)
                .semantics { contentDescription = openListLabel },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(theme.controlBg)
                    .border(1.dp, theme.controlBorder, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = null,
                    tint = if (enabled) theme.onContainer else theme.onContainerMuted,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

/**
 * Level 2 bottom sheet for browsing full/multi-line phrases, adding, editing,
 * reordering, and one-tap sending with automatic dismissal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPhrasesSheet(
    phrases: List<CommandPhrase>,
    isLandscape: Boolean,
    onSendPhrase: (content: String, autoEnter: Boolean) -> Unit,
    onAddPhraseClick: () -> Unit,
    onEditPhraseClick: (CommandPhrase) -> Unit,
    onDeletePhrase: (String) -> Unit,
    onMovePhrase: (id: String, delta: Int) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = isLandscape)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = theme.containerBg,
        contentColor = theme.onContainer,
        scrimColor = theme.scrim,
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
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = theme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.phrases_sheet_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.onContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(theme.primary)
                            .clickable(role = Role.Button, onClick = onAddPhraseClick)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = theme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.phrases_add_button),
                                color = theme.onPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    CircleIconButton(
                        icon = Icons.Default.Close,
                        contentDescription = stringResource(R.string.main_close),
                        onClick = onDismissRequest,
                        size = 34.dp
                    )
                }
            }

            if (phrases.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(theme.cardBg)
                        .border(1.dp, theme.cardBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(theme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = theme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.phrases_empty_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.onContainer,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.phrases_empty_desc),
                        fontSize = 13.sp,
                        color = theme.onContainerMuted,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Button(
                        onClick = onAddPhraseClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.primary,
                            contentColor = theme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.phrases_add_first_button),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(phrases, key = { _, phrase -> phrase.id }) { index, phrase ->
                        CommandPhraseCard(
                            phrase = phrase,
                            canMoveUp = index > 0,
                            canMoveDown = index < phrases.lastIndex,
                            onSend = {
                                onSendPhrase(phrase.content, phrase.autoEnter)
                                onDismissRequest()
                            },
                            onAlternateSend = {
                                onSendPhrase(phrase.content, !phrase.autoEnter)
                                onDismissRequest()
                            },
                            onEdit = { onEditPhraseClick(phrase) },
                            onMoveUp = { onMovePhrase(phrase.id, -1) },
                            onMoveDown = { onMovePhrase(phrase.id, +1) },
                            onDelete = { onDeletePhrase(phrase.id) }
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.phrases_sheet_hint),
                    fontSize = 11.sp,
                    color = theme.onContainerMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 18.dp)
                )
            }
        }
    }
}

@Composable
private fun CommandPhraseCard(
    phrase: CommandPhrase,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSend: () -> Unit,
    onAlternateSend: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val theme = CmuxTheme.colors
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(theme.cardBg)
            .border(1.dp, theme.cardBorder, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onSend)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (phrase.title.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = phrase.title.trim(),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.onContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    PhraseModeBadge(autoEnter = phrase.autoEnter)
                }
                Text(
                    text = phrase.content,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = theme.onContainerMuted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                PhraseModeBadge(autoEnter = phrase.autoEnter)
                Text(
                    text = phrase.content,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = theme.onContainer,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.phrases_more_actions_cd),
                    tint = theme.onContainerMuted,
                    modifier = Modifier.size(18.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(theme.containerBg)
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                if (phrase.autoEnter) R.string.phrases_action_insert_only
                                else R.string.phrases_action_send_with_enter
                            ),
                            color = theme.onContainer
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                            contentDescription = null,
                            tint = theme.onContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onAlternateSend()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.phrases_action_edit), color = theme.onContainer) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = theme.onContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onEdit()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.phrases_action_move_up),
                            color = if (canMoveUp) theme.onContainer else theme.onContainerMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = null,
                            tint = if (canMoveUp) theme.onContainer else theme.onContainerMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    enabled = canMoveUp,
                    onClick = {
                        menuExpanded = false
                        onMoveUp()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.phrases_action_move_down),
                            color = if (canMoveDown) theme.onContainer else theme.onContainerMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = null,
                            tint = if (canMoveDown) theme.onContainer else theme.onContainerMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    enabled = canMoveDown,
                    onClick = {
                        menuExpanded = false
                        onMoveDown()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_delete), color = theme.accentRed) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = theme.accentRed,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun PhraseModeBadge(autoEnter: Boolean) {
    val theme = CmuxTheme.colors
    val bgColor = if (autoEnter) {
        theme.accentGreen.copy(alpha = 0.16f)
    } else {
        theme.accentBlue.copy(alpha = 0.16f)
    }
    val textColor = if (autoEnter) theme.accentGreenInk else theme.accentBlueInk

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(
                if (autoEnter) R.string.phrases_badge_auto_enter
                else R.string.phrases_badge_insert_only
            ),
            color = textColor,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Add / Edit dialog for a [CommandPhrase]. Preserves intentional leading or trailing
 * whitespace in command content (for example `"cd "` or `"git commit -m \""`).
 */
@Composable
fun CommandPhraseEditorDialog(
    initialPhrase: CommandPhrase?,
    onDismissRequest: () -> Unit,
    onConfirm: (CommandPhrase) -> Unit
) {
    val theme = CmuxTheme.colors
    var title by remember(initialPhrase?.id) { mutableStateOf(initialPhrase?.title.orEmpty()) }
    var content by remember(initialPhrase?.id) { mutableStateOf(initialPhrase?.content.orEmpty()) }
    var autoEnter by remember(initialPhrase?.id) { mutableStateOf(initialPhrase?.autoEnter ?: true) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = theme.onContainer,
        unfocusedTextColor = theme.onContainer,
        focusedBorderColor = theme.primary,
        unfocusedBorderColor = theme.divider,
        focusedLabelColor = theme.primary,
        unfocusedLabelColor = theme.onContainerMuted,
        focusedPlaceholderColor = theme.onContainerMuted,
        unfocusedPlaceholderColor = theme.onContainerMuted,
        cursorColor = theme.primary
    )

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = theme.containerBg,
        titleContentColor = theme.onContainer,
        textContentColor = theme.onContainer,
        title = {
            Text(
                text = stringResource(
                    if (initialPhrase == null) R.string.phrases_dialog_add_title
                    else R.string.phrases_dialog_edit_title
                ),
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.phrases_field_title_label)) },
                    placeholder = { Text(stringResource(R.string.phrases_field_title_placeholder)) },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.phrases_field_content_label)) },
                    placeholder = { Text(stringResource(R.string.phrases_field_content_placeholder)) },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { autoEnter = !autoEnter }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.phrases_field_auto_enter),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = theme.onContainer
                        )
                        Text(
                            text = stringResource(
                                if (autoEnter) R.string.phrases_field_auto_enter_on_desc
                                else R.string.phrases_field_auto_enter_off_desc
                            ),
                            fontSize = 12.sp,
                            color = theme.onContainerMuted
                        )
                    }
                    Switch(
                        checked = autoEnter,
                        onCheckedChange = { autoEnter = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = theme.onPrimary,
                            checkedTrackColor = theme.primary,
                            uncheckedThumbColor = theme.onSurfaceVariant,
                            uncheckedTrackColor = theme.surfaceVariant
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (content.isNotBlank()) {
                        onConfirm(
                            CommandPhrase(
                                id = initialPhrase?.id ?: UUID.randomUUID().toString(),
                                title = title.trim(),
                                content = content,
                                autoEnter = autoEnter
                            )
                        )
                    }
                },
                enabled = content.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = theme.primary,
                    contentColor = theme.onPrimary
                )
            ) {
                Text(stringResource(R.string.phrases_dialog_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = theme.onContainerMuted)
            ) {
                Text(stringResource(R.string.phrases_dialog_cancel))
            }
        }
    )
}

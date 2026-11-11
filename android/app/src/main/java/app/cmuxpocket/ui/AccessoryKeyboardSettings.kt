package app.cmuxpocket.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cmuxpocket.R
import app.cmuxpocket.protocol.KeyboardModifierController
import app.cmuxpocket.protocol.ModifierKey
import app.cmuxpocket.protocol.SpecialKey
import app.cmuxpocket.protocol.TerminalKeyEncoder
import app.cmuxpocket.protocol.TerminalModifiers

@Composable
internal fun accessoryBarSummary(keys: List<AccessoryKeyItem>): String = when {
    keys.isEmpty() -> stringResource(R.string.settings_kb_summary_empty)
    AccessoryKeyCatalog.isDefaultLayout(keys) -> stringResource(R.string.settings_kb_summary_default, keys.size)
    else -> stringResource(R.string.settings_kb_summary_custom, keys.size)
}

@OptIn(ExperimentalLayoutApi::class)
internal fun LazyListScope.keyboardBarSection(
    keys: List<AccessoryKeyItem>,
    onKeysChange: (List<AccessoryKeyItem>) -> Unit,
    onResetDefaults: () -> Unit,
    onOpenPicker: () -> Unit
) {
    item(key = "kb_preview") {
        KeyboardBarPreviewCard(
            keys = keys,
            onResetDefaults = onResetDefaults
        )
    }

    item(key = "kb_picker_cta") {
        PickFromKeyboardCard(onClick = onOpenPicker)
    }

    item(key = "kb_current_header") {
        val theme = CmuxTheme.colors
        Text(
            text = stringResource(R.string.settings_kb_current_header, keys.size).uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = theme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }

    item(key = "kb_current_card") {
        CurrentKeysCard(
            keys = keys,
            onMoveKey = { from, to -> onKeysChange(AccessoryKeyCatalog.moveKey(keys, from, to)) },
            onRemoveKey = { index -> onKeysChange(AccessoryKeyCatalog.removeKeyAt(keys, index)) },
            onRestoreDefaults = onResetDefaults
        )
    }

    item(key = "kb_presets_header") {
        val theme = CmuxTheme.colors
        Text(
            text = stringResource(R.string.settings_kb_presets_header).uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = theme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )
    }

    item(key = "kb_presets_card") {
        CommonPresetsCard(
            activeKeys = keys,
            onTogglePreset = { preset ->
                onKeysChange(AccessoryKeyCatalog.togglePreset(keys, preset))
            }
        )
    }
}

@Composable
private fun KeyboardBarPreviewCard(
    keys: List<AccessoryKeyItem>,
    onResetDefaults: () -> Unit
) {
    val theme = CmuxTheme.colors
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    val isDefault = remember(keys) { AccessoryKeyCatalog.isDefaultLayout(keys) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_kb_preview_title),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.onContainer
                )
                TextButton(
                    onClick = { showResetDialog = true },
                    enabled = !isDefault,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_kb_reset),
                        fontSize = 12.sp,
                        color = if (!isDefault) theme.primary else theme.onContainerMuted.copy(alpha = 0.5f)
                    )
                }
            }

            // Realistic miniature accessory bar preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.accessoryBg)
                    .border(1.dp, theme.divider, RoundedCornerShape(8.dp))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Keyboard,
                        contentDescription = null,
                        tint = theme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                VerticalDivider(
                    color = theme.controlBorder,
                    modifier = Modifier
                        .height(18.dp)
                        .padding(horizontal = 2.dp)
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (keys.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_kb_preview_empty),
                            fontSize = 11.5.sp,
                            color = theme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    } else {
                        keys.forEach { item ->
                            AccessoryKeyButton(
                                text = item.label,
                                style = item.style
                            )
                        }
                    }
                }

                VerticalDivider(
                    color = theme.controlBorder,
                    modifier = Modifier
                        .height(18.dp)
                        .padding(horizontal = 2.dp)
                )

                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = theme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.settings_kb_preview_desc, keys.size),
                fontSize = 12.sp,
                color = theme.onContainerMuted
            )
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.settings_kb_reset_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = { Text(stringResource(R.string.settings_kb_reset_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        onResetDefaults()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                ) {
                    Text(stringResource(R.string.settings_kb_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
            containerColor = theme.containerBg,
            titleContentColor = theme.onContainer,
            textContentColor = theme.onContainer
        )
    }
}

@Composable
private fun PickFromKeyboardCard(onClick: () -> Unit) {
    val theme = CmuxTheme.colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsIconTile(icon = Icons.Default.Keyboard)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_kb_pick_title),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.onContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_kb_pick_desc),
                    fontSize = 12.sp,
                    color = theme.onContainerMuted
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = theme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CurrentKeysCard(
    keys: List<AccessoryKeyItem>,
    onMoveKey: (fromIndex: Int, toIndex: Int) -> Unit,
    onRemoveKey: (index: Int) -> Unit,
    onRestoreDefaults: () -> Unit
) {
    val theme = CmuxTheme.colors
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
    ) {
        if (keys.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_kb_empty_title),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.onContainer
                )
                Text(
                    text = stringResource(R.string.settings_kb_empty_desc),
                    fontSize = 12.sp,
                    color = theme.onContainerMuted
                )
                OutlinedButton(onClick = onRestoreDefaults) {
                    Text(stringResource(R.string.settings_kb_restore_defaults))
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                keys.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = theme.cardBorder,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.defaultMinSize(minWidth = 46.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            AccessoryKeyButton(
                                text = item.label,
                                style = item.style
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = describeAccessoryKey(item, context),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = theme.onContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onMoveKey(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.settings_kb_move_earlier),
                                tint = if (index > 0) theme.onContainerMuted else theme.onContainerMuted.copy(alpha = 0.25f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onMoveKey(index, index + 1) },
                            enabled = index < keys.lastIndex,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.settings_kb_move_later),
                                tint = if (index < keys.lastIndex) theme.onContainerMuted else theme.onContainerMuted.copy(alpha = 0.25f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { onRemoveKey(index) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.settings_kb_remove_key),
                                tint = theme.onContainerMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommonPresetsCard(
    activeKeys: List<AccessoryKeyItem>,
    onTogglePreset: (AccessoryKeyItem) -> Unit
) {
    val theme = CmuxTheme.colors
    var showFunctionKeys by rememberSaveable { mutableStateOf(false) }

    val essential = remember {
        AccessoryKeyCatalog.commonPresets.filter { it.category == AccessoryPresetCategory.ESSENTIAL_AND_NAVIGATION }
    }
    val combos = remember {
        AccessoryKeyCatalog.commonPresets.filter { it.category == AccessoryPresetCategory.CONTROL_AND_COMBOS }
    }
    val symbols = remember {
        AccessoryKeyCatalog.commonPresets.filter { it.category == AccessoryPresetCategory.SYMBOLS }
    }
    val fnKeys = remember {
        AccessoryKeyCatalog.commonPresets.filter { it.category == AccessoryPresetCategory.FUNCTION_KEYS }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = theme.cardBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_kb_presets_hint),
                fontSize = 12.sp,
                color = theme.onContainerMuted
            )

            PresetCategoryBlock(
                title = stringResource(R.string.settings_kb_cat_essential),
                options = essential,
                activeKeys = activeKeys,
                onTogglePreset = onTogglePreset
            )

            HorizontalDivider(color = theme.cardBorder)

            PresetCategoryBlock(
                title = stringResource(R.string.settings_kb_cat_combos),
                options = combos,
                activeKeys = activeKeys,
                onTogglePreset = onTogglePreset
            )

            HorizontalDivider(color = theme.cardBorder)

            PresetCategoryBlock(
                title = stringResource(R.string.settings_kb_cat_symbols),
                options = symbols,
                activeKeys = activeKeys,
                onTogglePreset = onTogglePreset
            )

            HorizontalDivider(color = theme.cardBorder)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFunctionKeys = !showFunctionKeys }
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_kb_cat_function),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.onContainer
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (showFunctionKeys) R.string.settings_kb_collapse_fn
                                else R.string.settings_kb_expand_fn
                            ),
                            fontSize = 12.sp,
                            color = theme.primary
                        )
                        Icon(
                            imageVector = if (showFunctionKeys) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = theme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (showFunctionKeys) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        fnKeys.forEach { option ->
                            PresetChip(
                                option = option,
                                selected = AccessoryKeyCatalog.containsKey(activeKeys, option.item),
                                onClick = { onTogglePreset(option.item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetCategoryBlock(
    title: String,
    options: List<AccessoryPresetOption>,
    activeKeys: List<AccessoryKeyItem>,
    onTogglePreset: (AccessoryKeyItem) -> Unit
) {
    val theme = CmuxTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = theme.onContainer
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                PresetChip(
                    option = option,
                    selected = AccessoryKeyCatalog.containsKey(activeKeys, option.item),
                    onClick = { onTogglePreset(option.item) }
                )
            }
        }
    }
}

@Composable
private fun PresetChip(
    option: AccessoryPresetOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val theme = CmuxTheme.colors
    val hintRes = presetHintRes(option.item.canonicalId)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) theme.primary.copy(alpha = 0.16f) else theme.surfaceVariant,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) theme.primary else theme.controlBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = option.item.label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = if (selected) theme.primary else theme.onSurface
            )
            if (hintRes != null) {
                Text(
                    text = stringResource(hintRes),
                    fontSize = 11.sp,
                    color = if (selected) theme.primary.copy(alpha = 0.85f) else theme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = theme.primary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

private enum class KeyPickerTab(@StringRes val titleRes: Int) {
    QWERTY(R.string.settings_kb_picker_tab_qwerty),
    SYMBOLS(R.string.settings_kb_picker_tab_symbols),
    SPECIAL(R.string.settings_kb_picker_tab_special)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardKeyPickerSheet(
    keys: List<AccessoryKeyItem>,
    onKeysChange: (List<AccessoryKeyItem>) -> Unit,
    onDismissRequest: () -> Unit
) {
    val theme = CmuxTheme.colors
    val context = LocalContext.current
    val controller = remember { KeyboardModifierController() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val stripScrollState = rememberScrollState()

    var tab by rememberSaveable { mutableStateOf(KeyPickerTab.QWERTY) }
    var selectedModifiers by remember { mutableStateOf(emptySet<ModifierKey>()) }
    var combinationMode by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf<String?>(null) }
    var previousSize by remember { mutableIntStateOf(keys.size) }

    LaunchedEffect(keys.size) {
        if (keys.size > previousSize) {
            stripScrollState.animateScrollTo(stripScrollState.maxValue)
        }
        previousSize = keys.size
    }

    fun syncController() {
        selectedModifiers = controller.modifiers
        combinationMode = controller.combinationMode
    }

    val activeMods = TerminalModifiers(
        ctrl = ModifierKey.CONTROL in selectedModifiers,
        shift = ModifierKey.SHIFT in selectedModifiers,
        alt = ModifierKey.OPTION in selectedModifiers,
        meta = ModifierKey.COMMAND in selectedModifiers
    )

    fun tryAddPrintable(rawText: String) {
        if (!TerminalKeyEncoder.supportsPrintableModifiers(rawText, activeMods)) {
            val previewLabel = AccessoryKeyCatalog.formatPrintableLabel(rawText, activeMods)
            feedbackText = context.getString(R.string.settings_kb_picker_unsupported, previewLabel)
            return
        }
        val candidate = AccessoryKeyCatalog.fromPrintable(rawText, activeMods)
        val (updated, added) = AccessoryKeyCatalog.addIfAbsent(keys, candidate)
        if (added) {
            onKeysChange(updated)
            feedbackText = context.getString(R.string.settings_kb_picker_added, candidate.label)
            controller.onKeySent()
            syncController()
        } else {
            feedbackText = context.getString(R.string.settings_kb_picker_duplicate, candidate.label)
        }
    }

    fun tryAddSpecial(key: SpecialKey) {
        if (!TerminalKeyEncoder.supportsSpecialModifiers(key, activeMods)) {
            val previewLabel = AccessoryKeyCatalog.formatSpecialLabel(key, activeMods)
            feedbackText = context.getString(R.string.settings_kb_picker_unsupported, previewLabel)
            return
        }
        val candidate = AccessoryKeyCatalog.fromSpecial(key, activeMods)
        val (updated, added) = AccessoryKeyCatalog.addIfAbsent(keys, candidate)
        if (added) {
            onKeysChange(updated)
            feedbackText = context.getString(R.string.settings_kb_picker_added, candidate.label)
            controller.onKeySent()
            syncController()
        } else {
            feedbackText = context.getString(R.string.settings_kb_picker_duplicate, candidate.label)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = theme.surface,
        contentColor = theme.onSurface,
        scrimColor = theme.scrim
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isLandscape = maxWidth > maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_kb_picker_title),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.settings_kb_picker_count, keys.size),
                            fontSize = 11.5.sp,
                            color = theme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text(
                            text = stringResource(R.string.settings_kb_picker_done),
                            fontWeight = FontWeight.SemiBold,
                            color = theme.primary
                        )
                    }
                }

                // Active bar chip strip (tap any chip to remove)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.accessoryBg)
                        .border(1.dp, theme.divider, RoundedCornerShape(8.dp))
                        .horizontalScroll(stripScrollState)
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (keys.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_kb_preview_empty),
                            fontSize = 11.5.sp,
                            color = theme.onSurfaceVariant
                        )
                    } else {
                        keys.forEachIndexed { index, item ->
                            Surface(
                                onClick = { onKeysChange(AccessoryKeyCatalog.removeKeyAt(keys, index)) },
                                shape = RoundedCornerShape(6.dp),
                                color = when (item.style) {
                                    AccessoryKeyStyle.SPECIAL -> theme.accentOrange.copy(alpha = 0.2f)
                                    AccessoryKeyStyle.PRIMARY -> theme.primary.copy(alpha = 0.16f)
                                    AccessoryKeyStyle.NORMAL -> theme.accessoryKeyBg
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = item.label,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        color = when (item.style) {
                                            AccessoryKeyStyle.SPECIAL -> theme.accentOrangeInk
                                            AccessoryKeyStyle.PRIMARY -> theme.primary
                                            AccessoryKeyStyle.NORMAL -> theme.accessoryKeyText
                                        },
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.settings_kb_remove_key),
                                        tint = theme.onSurfaceVariant,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Armed combination / action feedback banner
                val armedText = remember(activeMods) { formatArmedModifiers(activeMods) }
                Text(
                    text = when {
                        !activeMods.none() -> stringResource(R.string.settings_kb_picker_hint_armed, armedText)
                        feedbackText != null -> feedbackText!!
                        else -> stringResource(R.string.settings_kb_picker_hint_idle)
                    },
                    fontSize = 12.sp,
                    color = if (!activeMods.none()) theme.primary else theme.onSurfaceVariant,
                    fontWeight = if (!activeMods.none()) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2
                )

                // Modifier toggles + hold switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PickerModifierButton("⌃ Ctrl", ModifierKey.CONTROL in selectedModifiers) {
                        controller.toggle(ModifierKey.CONTROL)
                        syncController()
                    }
                    PickerModifierButton("⇧ Shift", ModifierKey.SHIFT in selectedModifiers) {
                        controller.toggle(ModifierKey.SHIFT)
                        syncController()
                    }
                    PickerModifierButton("⌥ Opt", ModifierKey.OPTION in selectedModifiers) {
                        controller.toggle(ModifierKey.OPTION)
                        syncController()
                    }
                    PickerModifierButton("⌘ Cmd", ModifierKey.COMMAND in selectedModifiers) {
                        controller.toggle(ModifierKey.COMMAND)
                        syncController()
                    }
                    if (isLandscape) {
                        Row(
                            modifier = Modifier
                                .height(42.dp)
                                .padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_kb_picker_hold_mods),
                                fontSize = 11.5.sp,
                                color = theme.onSurfaceVariant
                            )
                            Switch(
                                checked = combinationMode,
                                onCheckedChange = {
                                    controller.setCombinationMode(it)
                                    syncController()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = theme.onPrimary,
                                    checkedTrackColor = theme.primary,
                                    uncheckedThumbColor = theme.onSurfaceVariant,
                                    uncheckedTrackColor = theme.surfaceVariant
                                )
                            )
                        }
                    }
                }

                if (!isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.panel_combination_mode),
                            fontSize = 12.5.sp,
                            color = theme.onSurfaceVariant
                        )
                        Switch(
                            checked = combinationMode,
                            onCheckedChange = {
                                controller.setCombinationMode(it)
                                syncController()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = theme.onPrimary,
                                checkedTrackColor = theme.primary,
                                uncheckedThumbColor = theme.onSurfaceVariant,
                                uncheckedTrackColor = theme.surfaceVariant
                            )
                        )
                    }
                }

                // Keyboard page tabs
                TabRow(
                    selectedTabIndex = tab.ordinal,
                    containerColor = theme.surface,
                    contentColor = theme.primary
                ) {
                    KeyPickerTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            modifier = Modifier.height(42.dp),
                            text = {
                                Text(
                                    text = stringResource(entry.titleRes),
                                    fontSize = 13.sp,
                                    color = if (tab == entry) theme.primary else theme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }

                when (tab) {
                    KeyPickerTab.QWERTY -> PickerQwertyPage(
                        activeKeys = keys,
                        activeMods = activeMods,
                        onPrintable = ::tryAddPrintable,
                        onSpecial = ::tryAddSpecial
                    )

                    KeyPickerTab.SYMBOLS -> PickerSymbolsPage(
                        activeKeys = keys,
                        activeMods = activeMods,
                        onPrintable = ::tryAddPrintable,
                        onSpecial = ::tryAddSpecial
                    )

                    KeyPickerTab.SPECIAL -> PickerSpecialPage(
                        columns = if (isLandscape) 8 else 6,
                        activeKeys = keys,
                        activeMods = activeMods,
                        onSpecial = ::tryAddSpecial
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScope.PickerModifierButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val theme = CmuxTheme.colors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) theme.primary else theme.accessoryKeyBg,
            contentColor = if (active) theme.onPrimary else theme.accessoryKeyText
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier
            .weight(1f)
            .height(42.dp)
    ) {
        Text(text = label, fontSize = 11.5.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun RowScope.PickerKeyButton(
    label: String,
    weight: Float = 1f,
    isAdded: Boolean = false,
    isSupported: Boolean = true,
    onClick: () -> Unit
) {
    val theme = CmuxTheme.colors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isAdded) theme.primary.copy(alpha = 0.2f) else theme.accessoryKeyBg,
            contentColor = if (isAdded) theme.primary else theme.accessoryKeyText
        ),
        border = if (isAdded) BorderStroke(1.dp, theme.primary) else null,
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        modifier = Modifier
            .weight(weight)
            .height(46.dp)
            .alpha(if (isSupported) 1f else 0.45f)
    ) {
        Text(text = label, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun PickerKeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun PickerQwertyPage(
    activeKeys: List<AccessoryKeyItem>,
    activeMods: TerminalModifiers,
    onPrintable: (String) -> Unit,
    onSpecial: (SpecialKey) -> Unit
) {
    val digits = remember { listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0") }
    val row1 = remember { listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p") }
    val row2 = remember { listOf("a", "s", "d", "f", "g", "h", "j", "k", "l") }
    val row3 = remember { listOf("z", "x", "c", "v", "b", "n", "m") }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PickerKeyRow {
            digits.forEach { d ->
                val supported = TerminalKeyEncoder.supportsPrintableModifiers(d, activeMods)
                val added = supported && AccessoryKeyCatalog.containsKey(
                    activeKeys,
                    AccessoryKeyCatalog.fromPrintable(d, activeMods)
                )
                PickerKeyButton(label = d, isAdded = added, isSupported = supported) {
                    onPrintable(d)
                }
            }
        }
        PickerKeyRow {
            row1.forEach { ch ->
                val display = if (activeMods.shift) ch.uppercase() else ch
                val supported = TerminalKeyEncoder.supportsPrintableModifiers(ch, activeMods)
                val added = supported && AccessoryKeyCatalog.containsKey(
                    activeKeys,
                    AccessoryKeyCatalog.fromPrintable(ch, activeMods)
                )
                PickerKeyButton(label = display, isAdded = added, isSupported = supported) {
                    onPrintable(ch)
                }
            }
        }
        PickerKeyRow {
            row2.forEach { ch ->
                val display = if (activeMods.shift) ch.uppercase() else ch
                val supported = TerminalKeyEncoder.supportsPrintableModifiers(ch, activeMods)
                val added = supported && AccessoryKeyCatalog.containsKey(
                    activeKeys,
                    AccessoryKeyCatalog.fromPrintable(ch, activeMods)
                )
                PickerKeyButton(label = display, isAdded = added, isSupported = supported) {
                    onPrintable(ch)
                }
            }
            val bsSupported = TerminalKeyEncoder.supportsSpecialModifiers(SpecialKey.BACKSPACE, activeMods)
            val bsAdded = bsSupported && AccessoryKeyCatalog.containsKey(
                activeKeys,
                AccessoryKeyCatalog.fromSpecial(SpecialKey.BACKSPACE, activeMods)
            )
            PickerKeyButton(label = "⌫", isAdded = bsAdded, isSupported = bsSupported) {
                onSpecial(SpecialKey.BACKSPACE)
            }
        }
        PickerKeyRow {
            row3.forEach { ch ->
                val display = if (activeMods.shift) ch.uppercase() else ch
                val supported = TerminalKeyEncoder.supportsPrintableModifiers(ch, activeMods)
                val added = supported && AccessoryKeyCatalog.containsKey(
                    activeKeys,
                    AccessoryKeyCatalog.fromPrintable(ch, activeMods)
                )
                PickerKeyButton(label = display, isAdded = added, isSupported = supported) {
                    onPrintable(ch)
                }
            }
            val spaceSupported = TerminalKeyEncoder.supportsPrintableModifiers(" ", activeMods)
            val spaceAdded = spaceSupported && AccessoryKeyCatalog.containsKey(
                activeKeys,
                AccessoryKeyCatalog.fromPrintable(" ", activeMods)
            )
            PickerKeyButton(
                label = "Space",
                weight = 2.4f,
                isAdded = spaceAdded,
                isSupported = spaceSupported
            ) {
                onPrintable(" ")
            }
            val enterSupported = TerminalKeyEncoder.supportsSpecialModifiers(SpecialKey.ENTER, activeMods)
            val enterAdded = enterSupported && AccessoryKeyCatalog.containsKey(
                activeKeys,
                AccessoryKeyCatalog.fromSpecial(SpecialKey.ENTER, activeMods)
            )
            PickerKeyButton(
                label = "⏎",
                weight = 1.6f,
                isAdded = enterAdded,
                isSupported = enterSupported
            ) {
                onSpecial(SpecialKey.ENTER)
            }
        }
    }
}

@Composable
private fun PickerSymbolsPage(
    activeKeys: List<AccessoryKeyItem>,
    activeMods: TerminalModifiers,
    onPrintable: (String) -> Unit,
    onSpecial: (SpecialKey) -> Unit
) {
    val rows = remember {
        listOf(
            listOf("|", "/", "-", "~", "\\", "_", ".", ",", ":", ";"),
            listOf("'", "\"", "`", "$", "&", "*", "?", "!", "@", "#"),
            listOf("[", "]", "{", "}", "(", ")", "<", ">", "=", "+")
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            PickerKeyRow {
                row.forEach { sym ->
                    val supported = TerminalKeyEncoder.supportsPrintableModifiers(sym, activeMods)
                    val added = supported && AccessoryKeyCatalog.containsKey(
                        activeKeys,
                        AccessoryKeyCatalog.fromPrintable(sym, activeMods)
                    )
                    PickerKeyButton(label = sym, isAdded = added, isSupported = supported) {
                        onPrintable(sym)
                    }
                }
            }
        }
        PickerKeyRow {
            listOf("%", "^").forEach { sym ->
                val supported = TerminalKeyEncoder.supportsPrintableModifiers(sym, activeMods)
                val added = supported && AccessoryKeyCatalog.containsKey(
                    activeKeys,
                    AccessoryKeyCatalog.fromPrintable(sym, activeMods)
                )
                PickerKeyButton(label = sym, isAdded = added, isSupported = supported) {
                    onPrintable(sym)
                }
            }
            val tabSupported = TerminalKeyEncoder.supportsSpecialModifiers(SpecialKey.TAB, activeMods)
            val tabAdded = tabSupported && AccessoryKeyCatalog.containsKey(
                activeKeys,
                AccessoryKeyCatalog.fromSpecial(SpecialKey.TAB, activeMods)
            )
            PickerKeyButton(
                label = "TAB",
                weight = 2f,
                isAdded = tabAdded,
                isSupported = tabSupported
            ) {
                onSpecial(SpecialKey.TAB)
            }
            val spaceSupported = TerminalKeyEncoder.supportsPrintableModifiers(" ", activeMods)
            val spaceAdded = spaceSupported && AccessoryKeyCatalog.containsKey(
                activeKeys,
                AccessoryKeyCatalog.fromPrintable(" ", activeMods)
            )
            PickerKeyButton(
                label = "Space",
                weight = 4f,
                isAdded = spaceAdded,
                isSupported = spaceSupported
            ) {
                onPrintable(" ")
            }
            Spacer(modifier = Modifier.weight(2f))
        }
    }
}

@Composable
private fun PickerSpecialPage(
    columns: Int,
    activeKeys: List<AccessoryKeyItem>,
    activeMods: TerminalModifiers,
    onSpecial: (SpecialKey) -> Unit
) {
    val keys = remember {
        listOf(
            "F1" to SpecialKey.F1,
            "F2" to SpecialKey.F2,
            "F3" to SpecialKey.F3,
            "F4" to SpecialKey.F4,
            "F5" to SpecialKey.F5,
            "F6" to SpecialKey.F6,
            "F7" to SpecialKey.F7,
            "F8" to SpecialKey.F8,
            "F9" to SpecialKey.F9,
            "F10" to SpecialKey.F10,
            "F11" to SpecialKey.F11,
            "F12" to SpecialKey.F12,
            "Ins" to SpecialKey.INSERT,
            "Del" to SpecialKey.DELETE,
            "Home" to SpecialKey.HOME,
            "End" to SpecialKey.END,
            "PgUp" to SpecialKey.PAGE_UP,
            "PgDn" to SpecialKey.PAGE_DOWN,
            "↑" to SpecialKey.UP,
            "↓" to SpecialKey.DOWN,
            "←" to SpecialKey.LEFT,
            "→" to SpecialKey.RIGHT,
            "ESC" to SpecialKey.ESCAPE,
            "TAB" to SpecialKey.TAB
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.chunked(columns).forEach { rowKeys ->
            PickerKeyRow {
                rowKeys.forEach { (label, specialKey) ->
                    val supported = TerminalKeyEncoder.supportsSpecialModifiers(specialKey, activeMods)
                    val added = supported && AccessoryKeyCatalog.containsKey(
                        activeKeys,
                        AccessoryKeyCatalog.fromSpecial(specialKey, activeMods)
                    )
                    PickerKeyButton(label = label, isAdded = added, isSupported = supported) {
                        onSpecial(specialKey)
                    }
                }
                repeat(columns - rowKeys.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun formatArmedModifiers(mods: TerminalModifiers): String = buildList {
    if (mods.ctrl) add("Ctrl")
    if (mods.shift) add("Shift")
    if (mods.alt) add("Opt")
    if (mods.meta) add("Cmd")
}.joinToString(" + ")

private fun describeAccessoryKey(item: AccessoryKeyItem, context: Context): String {
    val resId = knownDescriptionRes(item.canonicalId)
    if (resId != null) return context.getString(resId)
    return item.label
}

@StringRes
private fun knownDescriptionRes(canonicalId: String?): Int? = when (canonicalId) {
    "special:ESCAPE" -> R.string.settings_kb_desc_esc
    "special:ENTER" -> R.string.settings_kb_desc_enter
    "special:TAB" -> R.string.settings_kb_desc_tab
    "special:TAB:s" -> R.string.settings_kb_desc_shift_tab
    "printable:space" -> R.string.settings_kb_desc_space
    "special:BACKSPACE" -> R.string.settings_kb_desc_backspace
    "special:UP" -> R.string.settings_kb_desc_up
    "special:DOWN" -> R.string.settings_kb_desc_down
    "special:LEFT" -> R.string.settings_kb_desc_left
    "special:RIGHT" -> R.string.settings_kb_desc_right
    "special:HOME" -> R.string.settings_kb_desc_home
    "special:END" -> R.string.settings_kb_desc_end
    "special:PAGE_UP" -> R.string.settings_kb_desc_pgup
    "special:PAGE_DOWN" -> R.string.settings_kb_desc_pgdn
    "special:DELETE" -> R.string.settings_kb_desc_del
    "special:INSERT" -> R.string.settings_kb_desc_ins
    "printable:c:c" -> R.string.settings_kb_desc_ctrl_c
    "printable:d:c" -> R.string.settings_kb_desc_ctrl_d
    "printable:z:c" -> R.string.settings_kb_desc_ctrl_z
    "printable:l:c" -> R.string.settings_kb_desc_ctrl_l
    "printable:a:c" -> R.string.settings_kb_desc_ctrl_a
    "printable:e:c" -> R.string.settings_kb_desc_ctrl_e
    "printable:r:c" -> R.string.settings_kb_desc_ctrl_r
    "printable:w:c" -> R.string.settings_kb_desc_ctrl_w
    "printable:u:c" -> R.string.settings_kb_desc_ctrl_u
    "printable:k:c" -> R.string.settings_kb_desc_ctrl_k
    "printable:b:c" -> R.string.settings_kb_desc_ctrl_b
    "printable:o:c" -> R.string.settings_kb_desc_ctrl_o
    "special:LEFT:a" -> R.string.settings_kb_desc_opt_left
    "special:RIGHT:a" -> R.string.settings_kb_desc_opt_right
    "special:LEFT:c" -> R.string.settings_kb_desc_ctrl_left
    "special:RIGHT:c" -> R.string.settings_kb_desc_ctrl_right
    "special:BACKSPACE:a" -> R.string.settings_kb_desc_opt_bs
    "special:BACKSPACE:c" -> R.string.settings_kb_desc_ctrl_bs
    else -> null
}

@StringRes
private fun presetHintRes(canonicalId: String?): Int? = when (canonicalId) {
    "printable:c:c" -> R.string.settings_kb_hint_ctrl_c
    "printable:d:c" -> R.string.settings_kb_hint_ctrl_d
    "printable:z:c" -> R.string.settings_kb_hint_ctrl_z
    "printable:l:c" -> R.string.settings_kb_hint_ctrl_l
    "printable:a:c" -> R.string.settings_kb_hint_ctrl_a
    "printable:e:c" -> R.string.settings_kb_hint_ctrl_e
    "printable:r:c" -> R.string.settings_kb_hint_ctrl_r
    "printable:w:c" -> R.string.settings_kb_hint_ctrl_w
    "printable:u:c" -> R.string.settings_kb_hint_ctrl_u
    "printable:k:c" -> R.string.settings_kb_hint_ctrl_k
    "printable:b:c" -> R.string.settings_kb_hint_ctrl_b
    "printable:o:c" -> R.string.settings_kb_hint_ctrl_o
    "special:LEFT:a", "special:LEFT:c" -> R.string.settings_kb_hint_word_back
    "special:RIGHT:a", "special:RIGHT:c" -> R.string.settings_kb_hint_word_fwd
    "special:BACKSPACE:a", "special:BACKSPACE:c" -> R.string.settings_kb_hint_del_word
    else -> null
}

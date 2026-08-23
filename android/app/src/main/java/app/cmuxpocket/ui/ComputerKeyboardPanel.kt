package app.cmuxpocket.ui

import app.cmuxpocket.protocol.KeyboardModifierController
import app.cmuxpocket.protocol.ModifierKey
import app.cmuxpocket.protocol.SpecialKey
import app.cmuxpocket.protocol.TerminalKeyEncoder
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class KeyboardPage(val title: String) {
    QWERTY("QWERTY"),
    SPECIAL("Special")
}

/**
 * Built-in computer keyboard panel presented as a modal bottom sheet.
 *
 * Two pages (QWERTY and Special), a modifier row (Control, Shift, Option,
 * Command) with one-shot behavior by default and a Combination Mode switch
 * that keeps modifiers latched until toggled. Key presses are encoded with
 * [TerminalKeyEncoder] and delivered through the existing sendInput path.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComputerKeyboardPanel(
    onSendKey: (String) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val controller = remember { KeyboardModifierController() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var page by remember { mutableStateOf(KeyboardPage.QWERTY) }
    var selectedModifiers by remember { mutableStateOf(emptySet<ModifierKey>()) }
    var combinationMode by remember { mutableStateOf(false) }

    fun syncController() {
        selectedModifiers = controller.modifiers
        combinationMode = controller.combinationMode
    }

    fun sendPrintable(text: String) {
        val encoded = TerminalKeyEncoder.encodePrintable(text, controller.currentModifiers())
        controller.onKeySent()
        syncController()
        onSendKey(encoded)
    }

    fun sendSpecial(key: SpecialKey) {
        val encoded = TerminalKeyEncoder.encodeSpecial(key, controller.currentModifiers())
        controller.onKeySent()
        syncController()
        onSendKey(encoded)
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = theme.surface,
        contentColor = theme.onSurface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp)
        ) {
            // Match the compact desktop-keyboard reference: tabs and close share one row.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TabRow(
                    selectedTabIndex = page.ordinal,
                    containerColor = theme.surface,
                    contentColor = theme.primary,
                    modifier = Modifier.weight(1f)
                ) {
                    KeyboardPage.entries.forEach { entry ->
                        Tab(
                            selected = page == entry,
                            onClick = { page = entry },
                            modifier = Modifier.height(48.dp),
                            text = {
                                Text(
                                    text = entry.title,
                                    color = if (page == entry) theme.primary else theme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Keyboard",
                        tint = theme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Modifier row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PanelModifierKey(label = "⌃ Ctrl", active = ModifierKey.CONTROL in selectedModifiers) {
                    controller.toggle(ModifierKey.CONTROL)
                    syncController()
                }
                PanelModifierKey(label = "⇧ Shift", active = ModifierKey.SHIFT in selectedModifiers) {
                    controller.toggle(ModifierKey.SHIFT)
                    syncController()
                }
                PanelModifierKey(label = "⌥ Opt", active = ModifierKey.OPTION in selectedModifiers) {
                    controller.toggle(ModifierKey.OPTION)
                    syncController()
                }
                PanelModifierKey(label = "⌘ Cmd", active = ModifierKey.COMMAND in selectedModifiers) {
                    controller.toggle(ModifierKey.COMMAND)
                    syncController()
                }

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .height(48.dp)
                            .padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Combo",
                            fontSize = 12.sp,
                            color = theme.onSurfaceVariant
                        )
                        Switch(
                            checked = combinationMode,
                            onCheckedChange = {
                                controller.setCombinationMode(it)
                                syncController()
                            }
                        )
                    }
                }
            }

            if (!isLandscape) {

                // Combination Mode: latched modifiers instead of one-shot
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Combination Mode",
                        fontSize = 13.sp,
                        color = theme.onSurfaceVariant
                    )
                    Switch(
                        checked = combinationMode,
                        onCheckedChange = {
                            controller.setCombinationMode(it)
                            syncController()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            when (page) {
                KeyboardPage.QWERTY -> QwertyPage(
                    onLetter = { sendPrintable(it) },
                    onBackspace = { sendSpecial(SpecialKey.BACKSPACE) },
                    onSpace = { sendPrintable(" ") },
                    onEnter = { sendSpecial(SpecialKey.ENTER) }
                )
                KeyboardPage.SPECIAL -> SpecialPage(
                    columns = if (isLandscape) 8 else 6,
                    onSpecial = { sendSpecial(it) }
                )
            }
        }
    }
}

@Composable
private fun RowScope.PanelModifierKey(
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
            .height(48.dp)
    ) {
        Text(text = label, fontSize = 12.sp)
    }
}

@Composable
private fun RowScope.PanelKey(
    label: String,
    weight: Float = 1f,
    onClick: () -> Unit
) {
    val theme = CmuxTheme.colors
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = theme.accessoryKeyBg,
            contentColor = theme.accessoryKeyText
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        modifier = Modifier
            .weight(weight)
            .height(48.dp)
    ) {
        Text(text = label, fontSize = 13.sp)
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun QwertyPage(
    onLetter: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onEnter: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        val row3 = listOf("z", "x", "c", "v", "b", "n", "m")

        KeyRow {
            digits.forEach { PanelKey(label = it) { onLetter(it) } }
        }

        KeyRow {
            row1.forEach { PanelKey(label = it) { onLetter(it) } }
        }
        KeyRow {
            row2.forEach { PanelKey(label = it) { onLetter(it) } }
            PanelKey(label = "⌫") { onBackspace() }
        }
        KeyRow {
            row3.forEach { PanelKey(label = it) { onLetter(it) } }
            PanelKey(label = "Space", weight = 2.5f) { onSpace() }
            PanelKey(label = "⏎", weight = 1.8f) { onEnter() }
        }
    }
}

@Composable
private fun SpecialPage(
    columns: Int,
    onSpecial: (SpecialKey) -> Unit
) {
    val keys: List<Pair<String, SpecialKey>> = listOf(
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

    Column(modifier = Modifier.fillMaxWidth()) {
        keys.chunked(columns).forEach { rowKeys ->
            KeyRow {
                rowKeys.forEach { (label, key) ->
                    PanelKey(label = label) { onSpecial(key) }
                }
                // Keep the final row's keys at grid size instead of stretching
                repeat(columns - rowKeys.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

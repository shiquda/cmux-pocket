package app.cmuxpocket.ui

import app.cmuxpocket.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Proposal C/D2: 48dp single-row compact accessory keyboard bar.
 * Contains: ESC, CTRL, ALT, TAB, ^C, |, /, -, ~, ↑, ↓, ←, → plus the keyboard-panel and
 * settings shortcuts. The `Tabs (N)` entry was removed in D2 — session switching lives in the
 * floating breadcrumb pill — and the reclaimed width is returned to the key row.
 */
@Composable
fun AccessoryKeyboardBar(
    onSendKey: (String) -> Unit,
    onOpenKeyboardPanel: () -> Unit,
    onFloatingWindowClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val scrollState = rememberScrollState()

    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }

    fun sendWithModifiers(raw: String) {
        var result = raw
        if (isCtrlActive) {
            if (raw.length == 1 && raw[0].uppercaseChar() in 'A'..'Z') {
                val code = (raw[0].uppercaseChar().code - 'A'.code + 1)
                result = code.toChar().toString()
            }
            isCtrlActive = false
        }
        if (isAltActive) {
            result = "\u001b$result"
            isAltActive = false
        }
        onSendKey(result)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.accessoryBg)
            .navigationBarsPadding()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Built-in computer keyboard panel launcher
        IconButton(
            onClick = onOpenKeyboardPanel,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = stringResource(R.string.kb_open_panel),
                tint = theme.primary,
                modifier = Modifier.size(20.dp)
            )
        }

        VerticalDivider(
            color = theme.controlBorder,
            modifier = Modifier
                .height(20.dp)
                .padding(horizontal = 3.dp)
        )

        // Scrollable keys section
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyButton(text = "ESC", isPrimary = true) {
                onSendKey("\u001b")
                isCtrlActive = false
                isAltActive = false
            }

            KeyButton(
                text = "CTRL",
                isHighlighted = isCtrlActive,
                isPrimary = true
            ) {
                isCtrlActive = !isCtrlActive
            }

            KeyButton(
                text = "ALT",
                isHighlighted = isAltActive,
                isPrimary = true
            ) {
                isAltActive = !isAltActive
            }

            KeyButton(text = "TAB") { sendWithModifiers("\t") }

            KeyButton(
                text = "^C",
                isSpecial = true
            ) {
                onSendKey("\u0003")
                isCtrlActive = false
                isAltActive = false
            }

            KeyButton(text = "|") { sendWithModifiers("|") }
            KeyButton(text = "/") { sendWithModifiers("/") }
            KeyButton(text = "-") { sendWithModifiers("-") }
            KeyButton(text = "~") { sendWithModifiers("~") }

            KeyButton(text = "↑") { onSendKey("\u001b[A") }
            KeyButton(text = "↓") { onSendKey("\u001b[B") }
            KeyButton(text = "←") { onSendKey("\u001b[D") }
            KeyButton(text = "→") { onSendKey("\u001b[C") }
        }

        VerticalDivider(
            color = theme.controlBorder,
            modifier = Modifier
                .height(20.dp)
                .padding(horizontal = 3.dp)
        )

        // Right side: floating window shortcut plus settings (Tabs (N) removed in D2)
        if (onFloatingWindowClick != null) {
            IconButton(
                onClick = onFloatingWindowClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PictureInPictureAlt,
                    contentDescription = stringResource(R.string.floating_window_open),
                    tint = theme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.kb_settings),
                tint = theme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun KeyButton(
    text: String,
    isPrimary: Boolean = false,
    isSpecial: Boolean = false,
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    val theme = CmuxTheme.colors

    val bg = when {
        isHighlighted -> theme.primary
        isSpecial -> theme.accentOrange.copy(alpha = 0.2f)
        isPrimary -> theme.primary.copy(alpha = 0.15f)
        else -> theme.accessoryKeyBg
    }

    val textColor = when {
        isHighlighted -> theme.onPrimary
        isSpecial -> theme.accentOrangeInk
        isPrimary -> theme.primary
        else -> theme.accessoryKeyText
    }

    // A plain clickable box rather than an M3 Button: the row scrolls horizontally, so every
    // frame lays out and draws all keys, and each Button's Surface/ripple/elevation nodes make
    // that visibly janky. LocalIndication still supplies the ripple, so feedback is unchanged.
    Box(
        modifier = Modifier
            .height(34.dp)
            .defaultMinSize(minWidth = 40.dp)
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.1.sp
        )
    }
}

package app.cmuxpocket.ui

import app.cmuxpocket.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Proposal C: 48dp single-row compact accessory keyboard bar.
 * Contains: ESC, CTRL, ALT, TAB, ^C, |, /, -, ~, ↑, ↓, ←, →, plus Tabs (N) and Settings shortcut entries.
 */
@Composable
fun AccessoryKeyboardBar(
    onSendKey: (String) -> Unit,
    onOpenKeyboardPanel: () -> Unit,
    onTabsClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    surfaceCount: Int = 1,
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
            .height(48.dp)
            .background(Color(0xFF141416))
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
                tint = Color(0xFF0A84FF),
                modifier = Modifier.size(20.dp)
            )
        }

        VerticalDivider(
            color = Color(0x33FFFFFF),
            modifier = Modifier
                .height(20.dp)
                .padding(horizontal = 3.dp)
        )

        // Scrollable keys section
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
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
            color = Color(0x33FFFFFF),
            modifier = Modifier
                .height(20.dp)
                .padding(horizontal = 3.dp)
        )

        // Right side double entries: Tabs (N) + Settings
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onTabsClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0x330A84FF),
                    contentColor = Color(0xFF0A84FF)
                ),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                    .height(34.dp)
                    .defaultMinSize(minWidth = 54.dp)
            ) {
                Text(
                    text = stringResource(R.string.kb_tabs, surfaceCount),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.kb_settings),
                    tint = Color(0xFF8E8E93),
                    modifier = Modifier.size(18.dp)
                )
            }
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
    val bg = when {
        isHighlighted -> Color(0xFF0A84FF)
        isSpecial -> Color(0x33FF9F0A)
        isPrimary -> Color(0x260A84FF)
        else -> Color(0xFF2C2C2E)
    }

    val textColor = when {
        isHighlighted -> Color.White
        isSpecial -> Color(0xFFFF9F0A)
        isPrimary -> Color(0xFF0A84FF)
        else -> Color(0xFFE5E5EA)
    }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = textColor
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier
            .height(34.dp)
            .defaultMinSize(minWidth = 36.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    }
}

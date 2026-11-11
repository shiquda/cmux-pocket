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
 * Compact single-row accessory keyboard bar.
 * Renders the user's configured [keys] (defaulting to ESC, Enter, TAB, ^C, arrows, |, /, -, ~)
 * flanked by fixed shortcuts for the computer-keyboard panel, optional floating window, and settings.
 */
@Composable
fun AccessoryKeyboardBar(
    onSendKey: (String) -> Unit,
    onOpenKeyboardPanel: () -> Unit,
    keys: List<AccessoryKeyItem> = AccessoryKeyCatalog.defaultKeys(),
    onFloatingWindowClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(theme.accessoryBg)
            .navigationBarsPadding()
            .height(44.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Built-in computer keyboard panel launcher
        IconButton(
            onClick = onOpenKeyboardPanel,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = stringResource(R.string.kb_open_panel),
                tint = theme.primary,
                modifier = Modifier.size(19.dp)
            )
        }

        VerticalDivider(
            color = theme.controlBorder,
            modifier = Modifier
                .height(18.dp)
                .padding(horizontal = 2.dp)
        )

        // Scrollable keys section
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            keys.forEach { item ->
                key(item.id) {
                    val onKeyClick = remember(item.sequence, onSendKey) {
                        { onSendKey(item.sequence) }
                    }
                    AccessoryKeyButton(
                        text = item.label,
                        style = item.style,
                        onClick = onKeyClick
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

        // Right side: floating window shortcut plus settings
        if (onFloatingWindowClick != null) {
            IconButton(
                onClick = onFloatingWindowClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PictureInPictureAlt,
                    contentDescription = stringResource(R.string.floating_window_open),
                    tint = theme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.kb_settings),
                tint = theme.primary,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

@Composable
internal fun AccessoryKeyButton(
    text: String,
    style: AccessoryKeyStyle = AccessoryKeyStyle.NORMAL,
    isHighlighted: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val theme = CmuxTheme.colors

    val bg = when {
        isHighlighted -> theme.primary
        style == AccessoryKeyStyle.SPECIAL -> theme.accentOrange.copy(alpha = 0.2f)
        style == AccessoryKeyStyle.PRIMARY -> theme.primary.copy(alpha = 0.15f)
        else -> theme.accessoryKeyBg
    }

    val textColor = when {
        isHighlighted -> theme.onPrimary
        style == AccessoryKeyStyle.SPECIAL -> theme.accentOrangeInk
        style == AccessoryKeyStyle.PRIMARY -> theme.primary
        else -> theme.accessoryKeyText
    }

    // A plain clickable box rather than an M3 Button: the row scrolls horizontally, so every
    // frame lays out and draws all keys, and each Button's Surface/ripple/elevation nodes make
    // that visibly janky. LocalIndication still supplies the ripple, so feedback is unchanged.
    Box(
        modifier = Modifier
            .height(30.dp)
            .defaultMinSize(minWidth = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.1.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

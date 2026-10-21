package app.cmuxpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Circular icon action whose hit area equals the drawn disc.
 *
 * A Material 3 [androidx.compose.material3.IconButton] inflates its minimum interactive size to
 * 48dp regardless of the requested visual size, so a 32dp icon button with a circular background
 * renders as a 48dp disc that overlaps adjacent content and sibling buttons. This keeps the two
 * sizes the same.
 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    val theme = CmuxTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(theme.controlBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = theme.onContainer,
            modifier = Modifier.size((size * 0.45f).coerceIn(14.dp, 20.dp))
        )
    }
}

package app.cmuxpocket.ui

import app.cmuxpocket.R
import app.cmuxpocket.engine.TerminalCell
import app.cmuxpocket.engine.TerminalScreenState
import app.cmuxpocket.transport.ConnectionStatus
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/**
 * Compact, read-only terminal surface shown while the app is in picture-in-picture.
 * It deliberately renders a wrapped tail instead of squeezing the full grid into
 * a small window: the font size stays user-controlled and the newest output wins.
 */
@Composable
fun FloatingTerminalView(
    viewModel: TerminalViewModel,
    themeHex: String,
    fontSizeSp: Float
) {
    val screenState by viewModel.activeScreenState.collectAsState()
    val surface by viewModel.activeSurface.collectAsState()
    val workspace by viewModel.activeWorkspace.collectAsState()
    val status by viewModel.connectionStatus.collectAsState(initial = ConnectionStatus.DISCONNECTED)
    val stageBg = remember(themeHex) {
        try {
            Color(AndroidColor.parseColor(themeHex))
        } catch (_: Exception) {
            Color(0xFF1E1E1E)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(stageBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val statusColor = when (status) {
                ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                ConnectionStatus.CONNECTING, ConnectionStatus.AUTHENTICATING -> Color(0xFFFF9800)
                ConnectionStatus.ERROR -> Color(0xFFF44336)
                ConnectionStatus.DISCONNECTED -> Color.Gray
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(statusColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = listOfNotNull(
                    workspace?.name,
                    surface?.displayTitle
                ).joinToString(" · ").ifBlank { "cmux Pocket" },
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE6E6E6),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val agentState = surface?.agentState
            if (agentState == "working" || agentState == "needs_input") {
                Text(
                    text = if (agentState == "working") "working" else "input",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (agentState == "working") Color(0xFFFFB300) else Color(0xFF64B5F6)
                )
            }
        }

        PipTerminalTailCanvas(
            screenState = screenState,
            fontSizeSp = fontSizeSp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
    }
}

/**
 * Draws the newest terminal lines, wrapped to the available width and bottom-aligned.
 * Shared by the PiP window and the settings preview so both stay visually identical.
 */
@Composable
fun PipTerminalTailCanvas(
    screenState: TerminalScreenState,
    fontSizeSp: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
    val typeface = remember(context) { FontLoader.getMapleMonoTypeface(context) }
    val textPaint = remember(typeface) {
        Paint().apply {
            isAntiAlias = true
            this.typeface = typeface
        }
    }
    val bgPaint = remember {
        Paint().apply { style = Paint.Style.FILL }
    }
    val defaultBgColor = remember(screenState.terminalBackground) {
        try {
            AndroidColor.parseColor(screenState.terminalBackground)
        } catch (_: Exception) {
            AndroidColor.parseColor("#1E1E1E")
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val horizontalPadding = 6.dp.toPx()
        val verticalPadding = 4.dp.toPx()
        val contentWidth = (size.width - horizontalPadding * 2f).coerceAtLeast(1f)
        val contentHeight = (size.height - verticalPadding * 2f).coerceAtLeast(1f)

        textPaint.textSize = fontSizePx
        val cellWidth = textPaint.measureText("M").coerceAtLeast(1f)
        val maxColumns = floor(contentWidth / cellWidth).toInt().coerceAtLeast(1)
        val fontMetrics = textPaint.fontMetrics
        // Maple Mono's font metrics include generous line gaps; using them verbatim
        // wastes roughly a third of the tiny PiP window and leaves only one or two
        // agent lines visible. 1.4em keeps glyphs from touching while showing more rows.
        val rowHeight = (fontSizePx * 1.4f).coerceAtLeast(fontSizePx)
        val maxLines = floor(contentHeight / rowHeight).toInt().coerceAtLeast(1)

        val grid = screenState.grid
        if (grid.isEmpty()) {
            textPaint.color = AndroidColor.parseColor("#888888")
            textPaint.isFakeBoldText = false
            textPaint.isUnderlineText = false
            nativeCanvas.drawText(
                "Waiting for terminal…",
                horizontalPadding,
                verticalPadding + rowHeight,
                textPaint
            )
            return@Canvas
        }

        val lines = tailPipLines(wrapPipGrid(grid, maxColumns), maxLines)
        val blockHeight = lines.size * rowHeight
        var y = verticalPadding + (contentHeight - blockHeight).coerceAtLeast(0f)

        for (line in lines) {
            var x = horizontalPadding
            for (cell in line) {
                val cellWidthPx = cellWidth * cell.width.coerceAtLeast(1)
                if (cell.background != screenState.terminalBackground) {
                    val bg = try {
                        AndroidColor.parseColor(cell.background)
                    } catch (_: Exception) {
                        defaultBgColor
                    }
                    if (bg != defaultBgColor) {
                        bgPaint.color = bg
                        nativeCanvas.drawRect(x, y, x + cellWidthPx, y + rowHeight, bgPaint)
                    }
                }
                if (cell.text.isNotBlank()) {
                    textPaint.color = try {
                        AndroidColor.parseColor(cell.foreground)
                    } catch (_: Exception) {
                        AndroidColor.parseColor("#D4D4D4")
                    }
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.isUnderlineText = cell.underline
                    nativeCanvas.drawText(
                        cell.text,
                        x,
                        y + (rowHeight - (fontMetrics.bottom - fontMetrics.top)) / 2f - fontMetrics.top,
                        textPaint
                    )
                }
                x += cellWidthPx
            }
            y += rowHeight
        }
        textPaint.isFakeBoldText = false
        textPaint.isUnderlineText = false
    }
}

@Composable
fun PipWindowPreview(
    aspectRatio: Float,
    fontSizeSp: Float,
    modifier: Modifier = Modifier
) {
    val theme = CmuxTheme.colors
    val previewState = remember { pipPreviewState() }
    val preset = PipSizePreset.fromRatio(aspectRatio)
    val ratioLabel = preset?.let { "${it.label} · ${it.ratioLabel}" }
        ?: stringResource(R.string.settings_floating_window_custom)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.settings_floating_window_preview), fontSize = 11.sp, color = theme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
            Text(ratioLabel, fontSize = 11.sp, color = theme.primary, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E1E1E))
                .border(1.dp, theme.divider, RoundedCornerShape(10.dp))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color(0xFF4CAF50), shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        "cmux-main · zsh",
                        fontSize = 9.sp,
                        color = Color(0xFFE6E6E6),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                PipTerminalTailCanvas(
                    screenState = previewState,
                    fontSizeSp = fontSizeSp,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

private fun pipPreviewState(): TerminalScreenState {
    val green = "#00FF7F"
    val source = listOf(
        "$ npm run build" to green,
        "✓ 42 tests passed" to green,
        "✓ build completed in 3.2s" to green,
        "$ git status" to green,
        "On branch main" to "#D4D4D4",
        "nothing to commit, working tree clean" to "#D4D4D4",
        "$ " to green
    )
    val grid = source.map { (line, color) ->
        line.map { char -> TerminalCell(text = char.toString(), foreground = color) }
    }
    return TerminalScreenState(
        surfaceId = "preview",
        columns = 80,
        rows = grid.size,
        grid = grid
    )
}

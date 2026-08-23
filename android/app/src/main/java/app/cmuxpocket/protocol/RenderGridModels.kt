package app.cmuxpocket.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MobileTerminalRenderGridFrame(
    @SerialName("format") val format: String = "cmux.render-grid.v1",
    @SerialName("surface_id") val surfaceId: String,
    @SerialName("state_seq") val stateSeq: Long,
    @SerialName("render_epoch") val renderEpoch: String? = null,
    @SerialName("render_revision") val renderRevision: Long? = null,
    @SerialName("columns") val columns: Int,
    @SerialName("rows") val rows: Int,
    @SerialName("full") val full: Boolean = false,
    @SerialName("cleared_rows") val clearedRows: List<Int> = emptyList(),
    @SerialName("cursor") val cursor: Cursor? = null,
    @SerialName("styles") val styles: List<Style> = emptyList(),
    @SerialName("row_spans") val rowSpans: List<RowSpan> = emptyList(),
    @SerialName("active_screen") val activeScreen: String? = "primary",
    @SerialName("history_rows") val historyRows: Long? = null,
    @SerialName("row_space_revision") val rowSpaceRevision: Long? = null,
    @SerialName("scrollback_rows") val scrollbackRows: Int? = null,
    @SerialName("scrollback_spans") val scrollbackSpans: List<RowSpan> = emptyList(),
    @SerialName("terminal_background") val terminalBackground: String? = "#1E1E1E",
    @SerialName("terminal_foreground") val terminalForeground: String? = "#D4D4D4"
)

@Serializable
data class Cursor(
    @SerialName("row") val row: Int,
    @SerialName("column") val column: Int,
    @SerialName("visible") val visible: Boolean = true,
    @SerialName("style") val style: String = "block",
    @SerialName("blinking") val blinking: Boolean = false
)

@Serializable
data class Style(
    @SerialName("id") val id: Int,
    @SerialName("foreground") val foreground: String? = null,
    @SerialName("background") val background: String? = null,
    @SerialName("bold") val bold: Boolean = false,
    @SerialName("italic") val italic: Boolean = false,
    @SerialName("underline") val underline: Boolean = false,
    @SerialName("inverse") val inverse: Boolean = false
)

@Serializable
data class RowSpan(
    @SerialName("row") val row: Int,
    @SerialName("column") val column: Int,
    @SerialName("style_id") val styleId: Int = 0,
    @SerialName("text") val text: String,
    @SerialName("cell_width") val cellWidth: Int? = null
)

data class RenderFrameEnvelope(
    val traceId: Long,
    val frame: MobileTerminalRenderGridFrame,
    val receivedNanos: Long,
    val decodedNanos: Long
)

package app.cmuxpocket.ui

import app.cmuxpocket.protocol.SurfaceInfo

/**
 * Normalized agent lifecycle for one surface.
 *
 * The Gateway sends one canonical spelling (`working`, `needs_input`, `idle`)
 * and omits the field when it cannot confirm the state. Older Gateways pass
 * cmux's raw values through instead, so both spellings are accepted here and
 * every UI surface reads this one mapping.
 */
enum class SurfaceAgentStatus {
    WORKING,
    NEEDS_INPUT,
    IDLE,
    UNKNOWN,
    DEAD;

    /** True while the agent is mid-turn or waiting on the user. */
    val isBusy: Boolean get() = this == WORKING || this == NEEDS_INPUT
}

/**
 * Maps a surface's wire fields onto [SurfaceAgentStatus].
 *
 * A missing state is [SurfaceAgentStatus.UNKNOWN], never idle: an agent without
 * a confirmed state must not look finished. `attention` alone means the agent
 * wants the user, which is the needs-input state.
 */
fun SurfaceInfo.agentStatus(): SurfaceAgentStatus {
    if (dead) return SurfaceAgentStatus.DEAD
    val normalized = agentState?.trim()?.lowercase()?.replace('-', '_')
    return when (normalized) {
        "working", "busy", "launching" -> SurfaceAgentStatus.WORKING
        "needs_input", "needsinput", "waiting" -> SurfaceAgentStatus.NEEDS_INPUT
        "idle", "ended", "done" -> SurfaceAgentStatus.IDLE
        else -> if (attention) SurfaceAgentStatus.NEEDS_INPUT else SurfaceAgentStatus.UNKNOWN
    }
}

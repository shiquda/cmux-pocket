package app.cmuxpocket.engine

import app.cmuxpocket.protocol.MobileTerminalRenderGridFrame
import app.cmuxpocket.protocol.RenderFrameEnvelope
import app.cmuxpocket.protocol.SurfaceInfo
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

data class SurfaceSession(
    val surfaceId: String,
    val engine: RenderGridStateEngine = RenderGridStateEngine(),
    var title: String? = null,
    var type: String = "terminal",
    var workspaceKey: String? = null,
    var agentState: String? = null,
    var attention: Boolean = false
) {
    val screenState: StateFlow<TerminalScreenState> get() = engine.screenState
}

class SurfaceSessionStore {

    private val sessions = ConcurrentHashMap<String, SurfaceSession>()

    fun getOrCreateSession(
        surfaceId: String,
        title: String? = null,
        type: String = "terminal",
        workspaceKey: String? = null
    ): SurfaceSession {
        return sessions.computeIfAbsent(surfaceId) { id ->
            SurfaceSession(
                surfaceId = id,
                title = title,
                type = type,
                workspaceKey = workspaceKey
            )
        }.also { session ->
            if (title != null) session.title = title
            if (workspaceKey != null) session.workspaceKey = workspaceKey
            session.type = type
        }
    }

    fun syncFromSurfaces(surfaces: List<SurfaceInfo>, workspaceKey: String?) {
        surfaces.forEach { info ->
            val session = getOrCreateSession(
                surfaceId = info.id,
                title = info.title,
                type = info.type,
                workspaceKey = workspaceKey ?: info.workspaceKey
            )
            session.agentState = info.agentState
            session.attention = info.attention
        }
    }

    fun getSession(surfaceId: String): SurfaceSession? {
        return sessions[surfaceId]
    }

    fun removeSession(surfaceId: String): SurfaceSession? {
        return sessions.remove(surfaceId)
    }

    fun routeFrame(envelope: RenderFrameEnvelope): FrameApplyResult {
        val session = getOrCreateSession(envelope.frame.surfaceId)
        return session.engine.applyFrame(envelope)
    }

    fun routeFrame(frame: MobileTerminalRenderGridFrame): FrameApplyResult {
        val session = getOrCreateSession(frame.surfaceId)
        return session.engine.applyFrame(frame)
    }

    fun clear() {
        sessions.clear()
    }
}

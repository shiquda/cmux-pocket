package app.cmuxpocket.engine

import app.cmuxpocket.protocol.WorkspaceInfo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

enum class ReloadReason {
    PAYLOAD_DECODE_ERROR,
    UNKNOWN_MUTATION,
    ACTION_MISMATCH,
    EXPIRED_TICKET,
    GENERATION_MISMATCH,
    GENERIC_EVENT_NO_MUTATION_ID
}

data class MutationTicket(
    val mutationId: String,
    val generation: Long,
    val actionType: String,
    val createdAtNanos: Long
)

sealed interface MutationEventDecision {
    data class ApplyFullWorkspaces(val workspaces: List<WorkspaceInfo>) : MutationEventDecision
    data class SuppressReload(val actionType: String) : MutationEventDecision
    data class TriggerReload(val reason: ReloadReason) : MutationEventDecision
}

class MutationTracker(
    private val ticketTtlNanos: Long = DEFAULT_TICKET_TTL_NANOS,
    private val nowNanosProvider: () -> Long = { System.nanoTime() }
) {
    companion object {
        const val DEFAULT_TICKET_TTL_NANOS = 30_000_000_000L // 30 seconds
        const val MAX_CAPACITY = 256
    }

    private val pendingTickets = ConcurrentHashMap<String, MutationTicket>()

    fun pruneExpired(nowNanos: Long = nowNanosProvider()) {
        pendingTickets.entries.removeIf { (_, ticket) ->
            nowNanos - ticket.createdAtNanos > ticketTtlNanos
        }
    }

    fun registerTicket(
        mutationId: String,
        generation: Long,
        actionType: String = "mutation",
        createdAtNanos: Long = nowNanosProvider()
    ) {
        pruneExpired(createdAtNanos)
        if (pendingTickets.size >= MAX_CAPACITY) {
            val oldest = pendingTickets.values.minByOrNull { it.createdAtNanos }
            if (oldest != null) {
                pendingTickets.remove(oldest.mutationId)
            }
        }
        pendingTickets[mutationId] = MutationTicket(
            mutationId = mutationId,
            generation = generation,
            actionType = actionType,
            createdAtNanos = createdAtNanos
        )
    }

    fun onRpcSuccess(mutationId: String, generation: Long) {
        pruneExpired()
        val ticket = pendingTickets[mutationId]
        if (ticket != null && ticket.generation != generation) {
            pendingTickets.remove(mutationId)
        }
    }

    fun onRpcFailure(mutationId: String, generation: Long) {
        pendingTickets.remove(mutationId)
        pruneExpired()
    }

    fun reset(currentGeneration: Long) {
        pendingTickets.entries.removeIf { it.value.generation < currentGeneration }
        pruneExpired()
    }

    fun clear() {
        pendingTickets.clear()
    }

    fun hasPendingTicket(mutationId: String): Boolean {
        pruneExpired()
        return pendingTickets.containsKey(mutationId)
    }

    fun getPendingTicketCount(): Int {
        pruneExpired()
        return pendingTickets.size
    }

    fun handleWorkspaceEvent(
        data: JsonObject,
        currentGeneration: Long,
        json: Json,
        nowNanos: Long = nowNanosProvider()
    ): MutationEventDecision {
        val mutationId = data["mutation_id"]?.jsonPrimitive?.contentOrNull
            ?: data["mutationId"]?.jsonPrimitive?.contentOrNull

        val eventAction = data["action"]?.jsonPrimitive?.contentOrNull
            ?: data["type"]?.jsonPrimitive?.contentOrNull
            ?: data["mutation_action"]?.jsonPrimitive?.contentOrNull
            ?: data["op"]?.jsonPrimitive?.contentOrNull

        val workspacesEl = data["workspaces"]
        if (workspacesEl != null) {
            // Full workspaces payload always applies
            if (mutationId != null) {
                pendingTickets.remove(mutationId)
            }
            pruneExpired(nowNanos)
            return try {
                val list = json.decodeFromJsonElement(
                    ListSerializer(WorkspaceInfo.serializer()),
                    workspacesEl
                )
                MutationEventDecision.ApplyFullWorkspaces(list)
            } catch (_: Exception) {
                MutationEventDecision.TriggerReload(ReloadReason.PAYLOAD_DECODE_ERROR)
            }
        }

        // Generic event (without full workspaces array)
        if (mutationId == null) {
            pruneExpired(nowNanos)
            return MutationEventDecision.TriggerReload(ReloadReason.GENERIC_EVENT_NO_MUTATION_ID)
        }

        val ticket = pendingTickets[mutationId]
        if (ticket == null) {
            pruneExpired(nowNanos)
            return MutationEventDecision.TriggerReload(ReloadReason.UNKNOWN_MUTATION)
        }

        if (ticket.generation != currentGeneration) {
            pendingTickets.remove(mutationId)
            pruneExpired(nowNanos)
            return MutationEventDecision.TriggerReload(ReloadReason.GENERATION_MISMATCH)
        }

        if (nowNanos - ticket.createdAtNanos > ticketTtlNanos) {
            pendingTickets.remove(mutationId)
            pruneExpired(nowNanos)
            return MutationEventDecision.TriggerReload(ReloadReason.EXPIRED_TICKET)
        }

        // Exact suppression requires matching expected action when event specifies action
        if (eventAction != null && eventAction != ticket.actionType) {
            pendingTickets.remove(mutationId)
            pruneExpired(nowNanos)
            return MutationEventDecision.TriggerReload(ReloadReason.ACTION_MISMATCH)
        }

        // Exact match confirmed -> consume ticket once and suppress duplicate reload
        pendingTickets.remove(mutationId)
        pruneExpired(nowNanos)
        return MutationEventDecision.SuppressReload(ticket.actionType)
    }
}

package app.cmuxpocket

import app.cmuxpocket.engine.MutationEventDecision
import app.cmuxpocket.engine.MutationTracker
import app.cmuxpocket.engine.ReloadReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class MutationTrackerTest {

    private lateinit var tracker: MutationTracker
    private val json = Json { ignoreUnknownKeys = true }
    private val clock = AtomicLong(1_000_000_000L)

    @Before
    fun setUp() {
        clock.set(1_000_000_000L)
        tracker = MutationTracker(
            ticketTtlNanos = 30_000_000_000L, // 30s TTL
            nowNanosProvider = { clock.get() }
        )
    }

    @Test
    fun testMatchingGenericEventSuppressesReloadOnce() {
        val mutationId = "mut-123"
        val gen = 1L
        tracker.registerTicket(mutationId, gen, "surface_created")

        assertTrue(tracker.hasPendingTicket(mutationId))
        assertEquals(1, tracker.getPendingTicketCount())

        val eventData = buildJsonObject {
            put("mutation_id", mutationId)
            put("action", "surface_created")
        }

        // First matching event: suppresses reload and consumes ticket
        val decision1 = tracker.handleWorkspaceEvent(eventData, gen, json)
        assertTrue(decision1 is MutationEventDecision.SuppressReload)
        assertEquals("surface_created", (decision1 as MutationEventDecision.SuppressReload).actionType)
        assertFalse(tracker.hasPendingTicket(mutationId))
        assertEquals(0, tracker.getPendingTicketCount())

        // Duplicate/subsequent event with same mutationId: now treated as unknown/foreign -> triggers reload
        val decision2 = tracker.handleWorkspaceEvent(eventData, gen, json)
        assertTrue(decision2 is MutationEventDecision.TriggerReload)
        assertEquals(ReloadReason.UNKNOWN_MUTATION, (decision2 as MutationEventDecision.TriggerReload).reason)
    }

    @Test
    fun testActionMismatchTriggersReload() {
        val mutationId = "mut-mismatch"
        val gen = 1L
        tracker.registerTicket(mutationId, gen, "surface_created")

        val eventData = buildJsonObject {
            put("mutation_id", mutationId)
            put("action", "workspace_created") // Mismatched action
        }

        val decision = tracker.handleWorkspaceEvent(eventData, gen, json)
        assertTrue(decision is MutationEventDecision.TriggerReload)
        assertEquals(ReloadReason.ACTION_MISMATCH, (decision as MutationEventDecision.TriggerReload).reason)
        assertFalse(tracker.hasPendingTicket(mutationId))
    }

    @Test
    fun testExpiredTicketTriggersReloadAndPrunes() {
        val mutationId = "mut-expire"
        val gen = 1L
        tracker.registerTicket(mutationId, gen, "surface_created")

        // Advance deterministic clock beyond 30s TTL
        clock.addAndGet(31_000_000_000L)

        val eventData = buildJsonObject {
            put("mutation_id", mutationId)
            put("action", "surface_created")
        }

        val decision = tracker.handleWorkspaceEvent(eventData, gen, json)
        assertTrue(decision is MutationEventDecision.TriggerReload)
        assertEquals(ReloadReason.EXPIRED_TICKET, (decision as MutationEventDecision.TriggerReload).reason)
        assertFalse(tracker.hasPendingTicket(mutationId))
    }

    @Test
    fun testFullWorkspacesPayloadAlwaysApplies() {
        val mutationId = "mut-full"
        val gen = 1L
        tracker.registerTicket(mutationId, gen, "workspace_created")

        val eventData = buildJsonObject {
            put("mutation_id", mutationId)
            put("workspaces", buildJsonArray {
                add(buildJsonObject {
                    put("id", "ws-1")
                    put("name", "Workspace 1")
                })
            })
        }

        val decision = tracker.handleWorkspaceEvent(eventData, gen, json)
        assertTrue(decision is MutationEventDecision.ApplyFullWorkspaces)
        val full = decision as MutationEventDecision.ApplyFullWorkspaces
        assertEquals(1, full.workspaces.size)
        assertEquals("ws-1", full.workspaces[0].id)
        assertEquals("Workspace 1", full.workspaces[0].name)

        // Ticket consumed so it doesn't leak
        assertFalse(tracker.hasPendingTicket(mutationId))
    }

    @Test
    fun testForeignOrUnknownMutationIdTriggersReload() {
        val gen = 1L
        tracker.registerTicket("mut-local", gen, "surface_created")

        // Event from another client / Mac (foreign mutation ID)
        val foreignEvent = buildJsonObject {
            put("mutation_id", "mut-foreign-from-mac")
        }
        val decisionForeign = tracker.handleWorkspaceEvent(foreignEvent, gen, json)
        assertTrue(decisionForeign is MutationEventDecision.TriggerReload)
        assertEquals(ReloadReason.UNKNOWN_MUTATION, (decisionForeign as MutationEventDecision.TriggerReload).reason)

        // Generic event with no mutationId at all
        val noIdEvent = buildJsonObject {
            put("some_key", "some_value")
        }
        val decisionNoId = tracker.handleWorkspaceEvent(noIdEvent, gen, json)
        assertTrue(decisionNoId is MutationEventDecision.TriggerReload)
        assertEquals(ReloadReason.GENERIC_EVENT_NO_MUTATION_ID, (decisionNoId as MutationEventDecision.TriggerReload).reason)

        // Local ticket still intact
        assertTrue(tracker.hasPendingTicket("mut-local"))
    }

    @Test
    fun testEventBeforeResponseRace() {
        val mutationId = "mut-race-1"
        val gen = 1L
        tracker.registerTicket(mutationId, gen, "surface_created")

        // 1. Generic broadcast event arrives BEFORE RPC returns
        val eventData = buildJsonObject {
            put("mutation_id", mutationId)
            put("action", "surface_created")
        }
        val decision = tracker.handleWorkspaceEvent(eventData, gen, json)
        assertTrue(decision is MutationEventDecision.SuppressReload)
        assertEquals("surface_created", (decision as MutationEventDecision.SuppressReload).actionType)
        assertFalse(tracker.hasPendingTicket(mutationId))

        // 2. RPC response returns afterwards
        tracker.onRpcSuccess(mutationId, gen)
        assertEquals(0, tracker.getPendingTicketCount())
    }

    @Test
    fun testResponseBeforeEventRace() {
        val mutationId = "mut-race-2"
        val gen = 1L
        tracker.registerTicket(mutationId, gen, "surface_created")

        // 1. RPC response returns first
        tracker.onRpcSuccess(mutationId, gen)
        assertTrue(tracker.hasPendingTicket(mutationId))

        // 2. Generic broadcast event arrives later
        val eventData = buildJsonObject {
            put("mutation_id", mutationId)
            put("action", "surface_created")
        }
        val decision = tracker.handleWorkspaceEvent(eventData, gen, json)
        assertTrue(decision is MutationEventDecision.SuppressReload)
        assertEquals("surface_created", (decision as MutationEventDecision.SuppressReload).actionType)
        assertFalse(tracker.hasPendingTicket(mutationId))
    }

    @Test
    fun testConcurrentMutationsTrackedIndependently() {
        val gen = 1L
        val mutA = "mut-concurrent-A"
        val mutB = "mut-concurrent-B"
        val mutC = "mut-concurrent-C"

        tracker.registerTicket(mutA, gen, "surface_created")
        tracker.registerTicket(mutB, gen, "workspace_created")
        tracker.registerTicket(mutC, gen, "surface_closed")
        assertEquals(3, tracker.getPendingTicketCount())

        // Event for B arrives
        val decB = tracker.handleWorkspaceEvent(buildJsonObject {
            put("mutation_id", mutB)
            put("action", "workspace_created")
        }, gen, json)
        assertTrue(decB is MutationEventDecision.SuppressReload)
        assertEquals("workspace_created", (decB as MutationEventDecision.SuppressReload).actionType)
        assertFalse(tracker.hasPendingTicket(mutB))
        assertTrue(tracker.hasPendingTicket(mutA))
        assertTrue(tracker.hasPendingTicket(mutC))

        // Event for A arrives
        val decA = tracker.handleWorkspaceEvent(buildJsonObject {
            put("mutation_id", mutA)
            put("action", "surface_created")
        }, gen, json)
        assertTrue(decA is MutationEventDecision.SuppressReload)
        assertFalse(tracker.hasPendingTicket(mutA))
        assertTrue(tracker.hasPendingTicket(mutC))

        // Event for C arrives
        val decC = tracker.handleWorkspaceEvent(buildJsonObject {
            put("mutation_id", mutC)
            put("action", "surface_closed")
        }, gen, json)
        assertTrue(decC is MutationEventDecision.SuppressReload)
        assertFalse(tracker.hasPendingTicket(mutC))
        assertEquals(0, tracker.getPendingTicketCount())
    }

    @Test
    fun testGenerationResetClearsOldTickets() {
        val mutGen1 = "mut-gen-1"
        tracker.registerTicket(mutGen1, 1L, "surface_created")
        assertTrue(tracker.hasPendingTicket(mutGen1))

        // Connection generation increments (reconnect)
        tracker.reset(2L)
        assertFalse(tracker.hasPendingTicket(mutGen1))

        // Late event from old generation should trigger reload in new generation
        val eventData = buildJsonObject {
            put("mutation_id", mutGen1)
        }
        val decision = tracker.handleWorkspaceEvent(eventData, 2L, json)
        assertTrue(decision is MutationEventDecision.TriggerReload)
        assertEquals(ReloadReason.UNKNOWN_MUTATION, (decision as MutationEventDecision.TriggerReload).reason)
    }

    @Test
    fun testRpcFailureRemovesTicket() {
        val mutationId = "mut-fail"
        val gen = 1L
        tracker.registerTicket(mutationId, gen, "surface_created")
        assertTrue(tracker.hasPendingTicket(mutationId))

        tracker.onRpcFailure(mutationId, gen)
        assertFalse(tracker.hasPendingTicket(mutationId))

        // Any subsequent event triggers reload
        val eventData = buildJsonObject {
            put("mutation_id", mutationId)
        }
        val decision = tracker.handleWorkspaceEvent(eventData, gen, json)
        assertTrue(decision is MutationEventDecision.TriggerReload)
        assertEquals(ReloadReason.UNKNOWN_MUTATION, (decision as MutationEventDecision.TriggerReload).reason)
    }

    @Test
    fun testCapacityPruningBoundedStorage() {
        val gen = 1L
        for (i in 0 until 300) {
            tracker.registerTicket("mut-$i", gen, "action-$i")
        }
        // Must be capped at MAX_CAPACITY (256)
        assertTrue(tracker.getPendingTicketCount() <= MutationTracker.MAX_CAPACITY)
    }
}

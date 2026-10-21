package app.cmuxpocket

import app.cmuxpocket.protocol.GatewayCompatibility
import app.cmuxpocket.protocol.GatewayHostStatus
import app.cmuxpocket.protocol.GatewayVersions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCompatibilityTest {
    private val wakeCapability = listOf(GatewayVersions.WAKE_CAPABILITY)

    @Test
    fun matchingVersionsAndWakeCapabilityAreCompatible() {
        val host = GatewayHostStatus(
            gatewayVersion = "0.2.0",
            protocolVersion = "2.0.0",
            capabilities = wakeCapability,
            canWake = true,
        )
        assertEquals(GatewayCompatibility.MATCHED, GatewayVersions.evaluate("0.2.0", host))
        assertFalse(GatewayCompatibility.MATCHED.isProblem)
    }

    @Test
    fun missingWakeCapabilityMarksOlderGatewayAsOutdated() {
        val host = GatewayHostStatus(
            gatewayVersion = "0.1.9",
            protocolVersion = "2.0.0",
            capabilities = emptyList(),
        )
        assertEquals(GatewayCompatibility.GATEWAY_OUTDATED, GatewayVersions.evaluate("0.2.0", host))
    }

    @Test
    fun newerProtocolMajorIsIncompatible() {
        val host = GatewayHostStatus(
            gatewayVersion = "0.3.0",
            protocolVersion = "3.0.0",
            capabilities = wakeCapability,
            canWake = true,
        )
        assertEquals(GatewayCompatibility.INCOMPATIBLE, GatewayVersions.evaluate("0.2.0", host))
    }

    @Test
    fun newerGatewaySuggestsUpdatingTheClient() {
        val host = GatewayHostStatus(
            gatewayVersion = "0.3.0",
            protocolVersion = "2.0.0",
            capabilities = wakeCapability,
            canWake = true,
        )
        assertEquals(GatewayCompatibility.CLIENT_OUTDATED, GatewayVersions.evaluate("0.2.0", host))
    }

    @Test
    fun olderGatewayWithWakeSupportIsOutdated() {
        val host = GatewayHostStatus(
            gatewayVersion = "0.1.10",
            protocolVersion = "2.0.0",
            capabilities = wakeCapability,
            canWake = true,
        )
        assertEquals(GatewayCompatibility.GATEWAY_OUTDATED, GatewayVersions.evaluate("0.2.0", host))
    }

    @Test
    fun unknownHostStatusIsNotAClaimedMatch() {
        assertEquals(GatewayCompatibility.UNKNOWN, GatewayVersions.evaluate("0.2.0", null))
        assertEquals(
            GatewayCompatibility.UNKNOWN,
            GatewayVersions.evaluate("0.2.0", GatewayHostStatus()),
        )
    }

    @Test
    fun versionComparisonIsNumericComponentWise() {
        assertTrue(GatewayVersions.compareVersions("0.10.0", "0.9.9") > 0)
        assertEquals(0, GatewayVersions.compareVersions("v0.2", "0.2.0"))
        assertTrue(GatewayVersions.compareVersions("0.2.0-beta.1", "0.2.0") == 0)
        assertEquals(2, GatewayVersions.majorOf("2.1.3"))
    }
}

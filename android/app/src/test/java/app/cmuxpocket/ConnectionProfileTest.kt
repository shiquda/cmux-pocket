package app.cmuxpocket

import app.cmuxpocket.ui.ConnectionProfile
import app.cmuxpocket.ui.ConnectionEndpoint
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionProfileTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun testProfileRoundTrip() {
        val profile = ConnectionProfile(
            id = "lan-1",
            name = "Studio Mac",
            host = "studio.test",
            port = 8088,
            token = "",
            lastUsedAt = 42L
        )
        val decoded = json.decodeFromString<ConnectionProfile>(json.encodeToString(profile))
        assertEquals(profile, decoded)
        assertEquals("studio.test:8088", decoded.endpointLabel())
    }

    @Test
    fun testUsbIsBuiltInAndUserHostsAreNot() {
        val usb = ConnectionProfile.usb()
        val lan = ConnectionProfile(name = "Studio Mac", host = "studio.test")
        assertTrue(usb.isBuiltIn)
        assertEquals(ConnectionProfile.USB_ID, usb.id)
        assertEquals("USB", usb.name)
        assertEquals("127.0.0.1:8088", usb.endpointLabel())
        assertTrue(!lan.isBuiltIn)
    }

    @Test
    fun testUserProfileListRoundTrip() {
        val profiles = listOf(
            ConnectionProfile.usb(),
            ConnectionProfile(name = "Studio Mac", host = "studio.test", port = 8088)
        )
        val decoded = json.decodeFromString<List<ConnectionProfile>>(json.encodeToString(profiles))
        assertEquals(2, decoded.size)
        assertTrue(decoded.any { it.isBuiltIn })
        assertTrue(decoded.any { it.host == "studio.test" })
    }

    @Test
    fun testWebSocketEndpointSupportsCloudflareTunnelUrls() {
        assertEquals(
            "wss://pocket.example.com",
            ConnectionEndpoint.websocketUrl("https://pocket.example.com/", 8088)
        )
        assertEquals(
            "wss://pocket.example.com/ws",
            ConnectionEndpoint.websocketUrl("wss://pocket.example.com/ws", 8088)
        )
        assertEquals(
            "wss://pocket.example.com",
            ConnectionEndpoint.websocketUrl("pocket.example.com", 443)
        )
        assertEquals(
            "wss://studio.test:8088",
            ConnectionEndpoint.websocketUrl("studio.test", 8088)
        )
        assertEquals(
            "ws://127.0.0.1:8088",
            ConnectionEndpoint.websocketUrl("127.0.0.1", 8088)
        )
        assertThrows(IllegalArgumentException::class.java) {
            ConnectionEndpoint.websocketUrl("ws://studio.test:8088", 8088)
        }
    }
}

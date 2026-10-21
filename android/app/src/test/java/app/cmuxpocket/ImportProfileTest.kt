package app.cmuxpocket

import app.cmuxpocket.ui.CmuxImportPayload
import app.cmuxpocket.ui.ConnectionProfile
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ImportProfileTest {

    @Test
    fun convertsPayloadToConnectionProfile() {
        val payload = CmuxImportPayload(
            version = "1",
            host = "mac.example.ts.net",
            port = 443,
            tls = true,
            token = "secret1234567890",
            name = "Work Mac",
            isLoopback = false
        )

        val profile = payload.toConnectionProfile()
        assertEquals("Work Mac", profile.name)
        assertEquals("mac.example.ts.net", profile.host)
        assertEquals(443, profile.port)
        assertEquals("secret1234567890", profile.token)
        assertFalse(profile.isBuiltIn)
    }

    @Test
    fun detectsExistingProfileByHostAndPort() {
        val existingId = UUID.randomUUID().toString()
        val existingProfiles = listOf(
            ConnectionProfile.usb(),
            ConnectionProfile(
                id = existingId,
                name = "Office Mac",
                host = "mac.office.ts.net",
                port = 443,
                token = "old-token"
            )
        )

        val payload = CmuxImportPayload(
            version = "1",
            host = "mac.office.ts.net",
            port = 443,
            tls = true,
            token = "new-token",
            name = "Office Mac",
            isLoopback = false
        )

        val matched = existingProfiles.firstOrNull {
            it.host.equals(payload.host, ignoreCase = true) && it.port == payload.port
        }
        assertNotNull(matched)
        assertEquals(existingId, matched!!.id)
        assertEquals("Office Mac", matched.name)

        // Updating existing profile preserves ID
        val updatedProfile = matched.copy(token = payload.token)
        assertEquals(existingId, updatedProfile.id)
        assertEquals("new-token", updatedProfile.token)

        // Saving as new creates a different ID
        val newProfile = ConnectionProfile(
            name = "Office Mac (New)",
            host = payload.host,
            port = payload.port,
            token = payload.token
        )
        assertNotEquals(existingId, newProfile.id)
        assertEquals(payload.token, newProfile.token)
    }

    @Test
    fun tokenMaskingPreventsSecretLeakage() {
        val longTokenPayload = CmuxImportPayload(
            version = "1",
            host = "mac.ts.net",
            port = 443,
            tls = true,
            token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            name = "Mac",
            isLoopback = false
        )
        val masked = longTokenPayload.maskedToken()
        assertEquals("0123••••cdef", masked)
        assertFalse(masked.contains("abcdef0123456789"))

        val shortTokenPayload = CmuxImportPayload(
            version = "1",
            host = "127.0.0.1",
            port = 8088,
            tls = false,
            token = "short",
            name = "USB",
            isLoopback = true
        )
        assertEquals("••••••••", shortTokenPayload.maskedToken())
    }

    @Test
    fun loopbackPayloadGeneratesCorrectWebsocketUrl() {
        val payload = CmuxImportPayload(
            version = "1",
            host = "127.0.0.1",
            port = 8088,
            tls = false,
            token = "token",
            name = "USB / Local",
            isLoopback = true
        )
        assertEquals("ws://127.0.0.1:8088", payload.websocketUrl())
    }
}

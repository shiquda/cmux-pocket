package app.cmuxpocket

import app.cmuxpocket.ui.ConnectionProfile
import app.cmuxpocket.ui.encodeProfilesBackup
import app.cmuxpocket.ui.mergeImportedProfiles
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBackupTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val profiles = listOf(
        ConnectionProfile.usb(token = "usb-token"),
        ConnectionProfile(
            id = "office",
            name = "Office Mac",
            host = "mac.office.ts.net",
            port = 443,
            token = "office-token",
            lastUsedAt = 42L
        )
    )

    private fun decode(encoded: String): List<ConnectionProfile> =
        json.decodeFromString(encoded)

    @Test
    fun exportWithoutCredentialsBlanksEveryToken() {
        val decoded = decode(encodeProfilesBackup(profiles, includeCredentials = false))

        assertEquals(listOf("", ""), decoded.map { it.token })
        assertEquals(listOf("USB", "Office Mac"), decoded.map { it.name })
        assertEquals(listOf("127.0.0.1", "mac.office.ts.net"), decoded.map { it.host })
    }

    @Test
    fun exportWithCredentialsKeepsEveryToken() {
        val decoded = decode(encodeProfilesBackup(profiles, includeCredentials = true))

        assertEquals(listOf("usb-token", "office-token"), decoded.map { it.token })
    }

    @Test
    fun credentialFreeImportKeepsStoredTokens() {
        val imported = decode(encodeProfilesBackup(profiles, includeCredentials = false))

        val merged = mergeImportedProfiles(imported, profiles)

        assertEquals(listOf("usb-token", "office-token"), merged.map { it.token })
    }

    @Test
    fun credentialFreeImportLeavesNewProfileWithoutAToken() {
        val fresh = ConnectionProfile(
            id = "trip",
            name = "Travel Mac",
            host = "mac.travel.ts.net",
            port = 443
        )

        val merged = mergeImportedProfiles(
            listOf(fresh.copy(token = "")),
            existing = profiles
        )

        assertEquals(1, merged.size)
        assertEquals("", merged.single().token)
        assertEquals("mac.travel.ts.net", merged.single().host)
    }

    @Test
    fun importWithCredentialsReplacesTheStoredToken() {
        val rotated = profiles.map { if (it.id == "office") it.copy(token = "rotated") else it }

        val merged = mergeImportedProfiles(rotated, profiles)

        assertEquals("rotated", merged.first { it.id == "office" }.token)
        assertNotEquals("office-token", merged.first { it.id == "office" }.token)
        assertTrue(merged.first { it.id == ConnectionProfile.USB_ID }.token.isNotBlank())
    }
}

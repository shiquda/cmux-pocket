package app.cmuxpocket

import app.cmuxpocket.ui.CmuxImportUri
import org.junit.Assert.*
import org.junit.Test

class CmuxImportUriTest {

    @Test
    fun parsesValidTailscaleTlsUri() {
        val uri = "cmux-pocket://import?v=1&host=mac.example.ts.net&port=443&tls=true&token=0123456789abcdef0123456789abcdef&name=My%20Mac"
        val payload = CmuxImportUri.parse(uri)

        assertEquals("1", payload.version)
        assertEquals("mac.example.ts.net", payload.host)
        assertEquals(443, payload.port)
        assertTrue(payload.tls)
        assertEquals("0123456789abcdef0123456789abcdef", payload.token)
        assertEquals("My Mac", payload.name)
        assertFalse(payload.isLoopback)
        assertEquals("wss://mac.example.ts.net", payload.websocketUrl())
        assertEquals("0123••••cdef", payload.maskedToken())
    }

    @Test
    fun parsesLoopbackUriWithCleartextAllowed() {
        val uri = "cmux-pocket://import?v=1&host=127.0.0.1&port=8088&tls=false&token=testtoken"
        val payload = CmuxImportUri.parse(uri)

        assertEquals("1", payload.version)
        assertEquals("127.0.0.1", payload.host)
        assertEquals(8088, payload.port)
        assertFalse(payload.tls)
        assertTrue(payload.isLoopback)
        assertEquals("USB / Local", payload.name)
        assertEquals("ws://127.0.0.1:8088", payload.websocketUrl())
    }

    @Test
    fun acceptsConnectAndSetupActions() {
        val connectUri = "cmux-pocket://connect?v=1&host=mac.example.ts.net&token=abc"
        val setupUri = "cmux-pocket://setup?v=1&host=mac.example.ts.net&token=abc"

        val p1 = CmuxImportUri.parse(connectUri)
        val p2 = CmuxImportUri.parse(setupUri)

        assertEquals("mac.example.ts.net", p1.host)
        assertEquals("mac.example.ts.net", p2.host)
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val uri = "cmux-pocket://import?v=2&host=mac.ts.net&token=abc"
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CmuxImportUri.parse(uri)
        }
        assertTrue(ex.message!!.contains("Unsupported configuration version"))
    }

    @Test
    fun rejectsCleartextNonLoopback() {
        val uri = "cmux-pocket://import?v=1&host=remote.host.com&port=8088&tls=false&token=abc"
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CmuxImportUri.parse(uri)
        }
        assertTrue(ex.message!!.contains("Cleartext WebSocket is only allowed for loopback connections"))
    }

    @Test
    fun rejectsInvalidScheme() {
        val uri = "https://import?v=1&host=mac.ts.net&token=abc"
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CmuxImportUri.parse(uri)
        }
        assertTrue(ex.message!!.contains("Invalid scheme"))
    }

    @Test
    fun rejectsMissingRequiredParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            CmuxImportUri.parse("cmux-pocket://import?host=mac.ts.net&token=abc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CmuxImportUri.parse("cmux-pocket://import?v=1&token=abc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CmuxImportUri.parse("cmux-pocket://import?v=1&host=mac.ts.net")
        }
    }

    @Test
    fun rejectsInvalidPortNumbers() {
        assertThrows(IllegalArgumentException::class.java) {
            CmuxImportUri.parse("cmux-pocket://import?v=1&host=mac.ts.net&port=70000&token=abc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CmuxImportUri.parse("cmux-pocket://import?v=1&host=mac.ts.net&port=0&token=abc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CmuxImportUri.parse("cmux-pocket://import?v=1&host=mac.ts.net&port=abc&token=abc")
        }
    }

    @Test
    fun decodesUrlEncodedValues() {
        val uri = "cmux-pocket://import?v=1&host=mac%2Eexample%2Ets%2Enet&token=tok%20en%2B123&name=%E6%88%91%E7%9A%84Mac"
        val payload = CmuxImportUri.parse(uri)

        assertEquals("mac.example.ts.net", payload.host)
        assertEquals("tok en+123", payload.token)
        assertEquals("我的Mac", payload.name)
    }
}

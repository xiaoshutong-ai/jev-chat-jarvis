package com.jev.probe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopBridgeClientTest {
    private val valid = """
        {
          "version":1,
          "generation":"0123456789abcdef",
          "account_ref":"desktop-1",
          "cursor":2,
          "gap":false,
          "has_more":false,
          "items":[{
            "account_ref":"desktop-1",
            "session_id":"wxid-a",
            "message_id":"mid-1",
            "display_name":"Alice",
            "direction":"other",
            "text":"Hello",
            "time_ms":1000000
          }]
        }
    """.trimIndent()

    @Test fun strictParsingPreservesSessionAndAccount() {
        val response = DesktopBridgeClient.parseResponse(valid, "desktop-1")
        assertEquals("0123456789abcdef", response.generation)
        assertEquals(2L, response.cursor)
        assertEquals("wxid-a", response.messages.single().sessionId)
        assertEquals("mid-1", response.messages.single().messageId)
        assertEquals(DesktopMessageIndex.Direction.OTHER, response.messages.single().direction)
        assertFalse(response.gap)
    }

    @Test fun rejectsForeignAccountAndWrongTypeInsteadOfGuessing() {
        expectInvalid { DesktopBridgeClient.parseResponse(valid, "other-account") }
        expectInvalid { DesktopBridgeClient.parseResponse(valid.replace("\"version\":1", "\"version\":2"), "desktop-1") }
        expectInvalid { DesktopBridgeClient.parseResponse(valid.replace("\"direction\":\"other\"", "\"direction\":\"unknown\""), "desktop-1") }
        expectInvalid { DesktopBridgeClient.parseResponse(valid.replace("\"session_id\":\"wxid-a\"", "\"session_id\":true"), "desktop-1") }
        expectInvalid { DesktopBridgeClient.parseResponse(valid.replace("\"time_ms\":1000000", "\"time_ms\":1.25"), "desktop-1") }
    }

    @Test fun validatesLoopbackAndSecureRemoteEndpoints() {
        assertTrue(DesktopBridgeClient.isEndpointAllowed("http://127.0.0.1:8766"))
        assertTrue(DesktopBridgeClient.isEndpointAllowed("https://relay.example.com"))
        assertFalse(DesktopBridgeClient.isEndpointAllowed("http://192.168.1.88:8766"))
        assertFalse(DesktopBridgeClient.isEndpointAllowed("http://example.com"))
        assertFalse(DesktopBridgeClient.isEndpointAllowed("http://user:password@127.0.0.1"))
        assertFalse(DesktopBridgeClient.isEndpointAllowed("https://relay.example.com?token=abc"))
        assertFalse(DesktopBridgeClient.isEndpointAllowed("javascript:alert(1)"))
    }

    @Test fun refusesWeakPairingTokenAndInvalidProfile() {
        expectInvalid {
            DesktopBridgeClient(DesktopBridgeClient.Config("http://127.0.0.1:8766", "short", "desktop-1", "com.tencent.mm@UserHandle{0}"))
        }
        expectInvalid {
            DesktopBridgeClient(DesktopBridgeClient.Config("http://127.0.0.1:8766", "x".repeat(40), "desktop-1", "unknown"))
        }
    }

    private fun expectInvalid(block: () -> Unit) {
        var threw = false
        try { block() } catch (expected: IllegalArgumentException) { threw = true }
        assertTrue("must reject invalid relay response", threw)
    }
}

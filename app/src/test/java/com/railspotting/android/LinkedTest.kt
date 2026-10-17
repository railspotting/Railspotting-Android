package com.railspotting.android

import com.railspotting.android.signin.Hello
import com.railspotting.android.signin.SignIn
import com.railspotting.android.store.Linked
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkedTest {
    // contract v1.7, 1.4: the link result
    private val result = JSONObject("""
        {"device_id": "rs-0a1b2c3d4e5f", "receiver_id": 42, "receiver_name": "Ann's phone", "kind": "mobile",
         "commissioning": "provisional", "account": {"email": "ann@example.com", "name": "Ann"}, "relinked": false}
    """.trimIndent())

    @Test
    fun keepsTheLinkResult() {
        val l = Linked.fromResult(result)!!
        assertEquals("rs-0a1b2c3d4e5f", l.deviceId)
        assertEquals("ann@example.com", l.email)
        assertTrue(l.provisional)
        assertEquals(l, Linked.fromJson(l.toJson()))
        assertFalse(Linked.fromResult(JSONObject(result.toString()).put("commissioning", "live"))!!.provisional)
        assertNull(Linked.fromResult(JSONObject("""{"receiver_name": "x"}""")))
        assertNull(Linked.fromJson("{broken"))
    }

    @Test
    fun helloNonceGoesStale() {
        val h = Hello.parse(JSONObject("""{"google_client_id": "c", "nonce": "n", "nonce_expires_in": 300,
            "terms_url": "https://example.test/terms", "privacy_url": "https://example.test/privacy"}"""))
        assertEquals("https://example.test/terms", h.termsUrl)
        assertTrue(h.fresh(h.at + 200_000))
        assertFalse(h.fresh(h.at + 250_000))
    }

    @Test
    fun errorsReadPlainly() {
        fun msg(s: String) = SignIn.message(JSONObject(s))
        assertTrue(msg("""{"ok": false, "status": 409, "error": "email_taken"}""").contains("Ask an admin"))
        assertTrue(msg("""{"ok": false, "status": 429, "error": "rate_limited", "retry_after": 30}""").contains("30 seconds"))
        assertTrue(msg("""{"ok": false, "status": null, "error": "network", "message": "x"}""").contains("internet"))
        assertEquals("Sign-in failed (500 internal).", msg("""{"ok": false, "status": 500, "error": "internal"}"""))
    }
}

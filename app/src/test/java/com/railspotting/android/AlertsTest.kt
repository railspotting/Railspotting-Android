package com.railspotting.android

import com.railspotting.android.alerts.NearbyEot
import com.railspotting.android.alerts.OwnEot
import com.railspotting.android.core.Heard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private val t0 = 1_791_034_500.0

    private fun eot(id: String, at: Double, unit: Long?, open: Boolean = false, kind: String = "telemetry") = Heard(
        eventId = id, freq = 457.9375, label = "EOT", kind = kind, open = open, startTs = at, endTs = at + 0.3,
        peakRssi = 400, decoded = unit?.let { "EOT unit $it, 88 psi, moving" } ?: "", unitId = unit,
        brakePipePsi = unit?.let { 88L }, moving = unit?.let { true },
    )

    @Test
    fun eachUnitOncePerQuarterHour() {
        val a = OwnEot()
        // the status lists newest first
        assertEquals(listOf("e1"), a.fresh(listOf(eot("e1", t0, 41234))).map { it.eventId })
        // the same unit a minute later: no alert; nothing is repeated either
        assertTrue(a.fresh(listOf(eot("e2", t0 + 60, 41234), eot("e1", t0, 41234))).isEmpty())
        // another unit: yes
        assertEquals(listOf("e3"), a.fresh(listOf(eot("e3", t0 + 90, 50001))).map { it.eventId })
        // the first unit again after 15 minutes: yes
        assertEquals(listOf("e4"), a.fresh(listOf(eot("e4", t0 + 15 * 60, 41234))).map { it.eventId })
    }

    @Test
    fun signalsWithoutADecodeAndWhatIsNotEot() {
        val a = OwnEot()
        assertEquals(1, a.fresh(listOf(eot("s1", t0, null))).size)
        assertTrue(a.fresh(listOf(eot("s2", t0 + 120, null))).isEmpty())
        assertEquals(1, a.fresh(listOf(eot("s3", t0 + 301, null))).size)
        // still on the air, or a voice call: not yet / never
        assertTrue(a.fresh(listOf(eot("o1", t0 + 400, 777, open = true))).isEmpty())
        assertTrue(a.fresh(listOf(eot("v1", t0 + 400, null, kind = "voice"))).isEmpty())
        // closed later: now it alerts
        assertEquals(1, a.fresh(listOf(eot("o1", t0 + 400, 777))).size)
    }

    @Test
    fun nearbyAlertsReadPlainly() {
        val a = NearbyEot.parse(JSONObject("""{"id": 538690, "title": "Train near Maple Ave Siding",
            "message": "End-of-train signal heard: 3 bursts in 2 minutes", "crossing": "Elm St",
            "receiver": "Maple Ave Siding", "distance_km": 11.0}"""))
        assertEquals("EOT 7 mi away", a.headline)
        assertEquals("Near Elm St, heard by Maple Ave Siding", a.where)
        val b = NearbyEot.parse(JSONObject("""{"id": 1, "title": "Train nearby", "message": "", "crossing": null,
            "receiver": "Maple Ave Siding", "distance_km": 0.2}"""))
        assertEquals("EOT 1 mi away", b.headline)
        assertEquals("Heard by Maple Ave Siding", b.where)
    }
}

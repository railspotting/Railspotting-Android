package com.railspotting.android

import com.railspotting.android.car.NearCrossing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearCrossingTest {
    // the shape of the core's nearby_crossings
    private val sample = """
        {"ok": true, "crossings": [
          {"id": "900001A", "street": "Elm Street", "city": "Springfield", "railroad": "NS", "lat": 34.92, "lon": -82.3,
           "status": "nearby", "label": "Moving train", "status_at": "2026-10-14T13:00:00Z", "distance_km": 1.6},
          {"id": "X1", "street": null, "city": null, "railroad": null, "lat": 34.9, "lon": -82.31,
           "status": "quiet", "label": "Quiet", "status_at": null, "distance_km": 8.0}]}
    """.trimIndent()

    @Test
    fun readsTheCoresList() {
        val (a, b) = NearCrossing.list(sample)
        assertEquals("Elm Street (NS)", a.title)
        assertTrue(a.busy)
        assertEquals(1.0, a.miles, 0.01)
        assertEquals("Crossing X1", b.title)
        assertFalse(b.busy)
    }

    @Test
    fun nothingOnErrors() {
        assertTrue(NearCrossing.list("""{"ok": false, "error": "network"}""").isEmpty())
        assertTrue(NearCrossing.list("not json").isEmpty())
    }
}

package com.railspotting.android

import com.railspotting.android.trains.NearTrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NearTrainTest {
    @Test
    fun readsTheList() {
        val (list, err) = NearTrain.list("""{"ok": true, "trains": [
            {"id": 812, "unit": "76520", "railroad": "NS", "motion": "stopped", "last": 1000.0, "distance_km": 1.3,
             "crossing": {"id": "716000A", "name": "Main St"}},
            {"id": 0, "unit": "x"},
            {"id": 900, "unit": "11111", "railroad": null, "motion": "moving", "last": 400.0, "distance_km": 4.0}]}""")
        assertNull(err)
        assertEquals(listOf(812L, 900L), list!!.map { it.id })
        assertEquals("NS · EOT 76520", list[0].title)
        assertEquals("stopped · 0.8 mi · 3 min ago, near Main St", list[0].detail(1180.0))
        assertEquals("EOT 11111", list[1].title)
        assertEquals("moving · 2.5 mi · 2 h ago", list[1].detail(400.0 + 7300))
    }

    @Test
    fun failuresCarryTheMessage() {
        val (list, err) = NearTrain.list("""{"ok": false, "message": "RailSpotting doesn't take train reports yet"}""")
        assertNull(list)
        assertEquals("RailSpotting doesn't take train reports yet", err)
        assertEquals("No answer from RailSpotting", NearTrain.list("nope").second)
    }
}

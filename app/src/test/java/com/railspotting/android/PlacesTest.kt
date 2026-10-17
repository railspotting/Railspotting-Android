package com.railspotting.android

import com.railspotting.android.trains.PlaceLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    @Test
    fun linesAndCrossings() {
        val lines = PlaceLine.list("""{"ok": true, "lines": [
            {"railroad": "NS", "subdivision": "Main Line", "crossings": [
              {"id": "900001A", "street": "Elm St", "city": "Springfield", "milepost": "12.3", "distance_km": 0.161},
              {"id": "900002B", "street": "", "city": "Springfield", "milepost": "", "distance_km": 0.5}]},
            {"railroad": "CSX", "subdivision": "", "crossings": []}]}""")
        assertEquals(1, lines.size) // a line without crossings is left out
        assertEquals("NS · Main Line", lines[0].label)
        assertEquals("Elm St, MP 12.3 · 0.1 mi", lines[0].crossings[0].label)
        assertEquals("Crossing 900002B · 0.3 mi", lines[0].crossings[1].label)
    }

    @Test
    fun nothingWhenTheCoreFails() {
        assertTrue(PlaceLine.list("""{"ok": false, "error": "offline"}""").isEmpty())
        assertTrue(PlaceLine.list("nope").isEmpty())
    }
}

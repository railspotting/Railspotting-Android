package com.railspotting.android

import com.railspotting.android.core.Itc
import com.railspotting.android.core.Status
import com.railspotting.android.service.modeListening
import com.railspotting.android.store.Prefs
import com.railspotting.android.ui.MODE_NAMES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItcTest {
    // the core's radio status in ITC mode (radio::itc_status), with a made-up unit and base
    private val sample = """
        {"listening": true,
         "radio": {"mode": "itc", "attached": true, "connected": true, "tuner": "R820T", "channels": [],
                   "itc": {"channels": [102, 114], "messages": 1234, "by_type": {"WIU": 1000}, "last_message_s": 1.0,
                           "bases": [{"radio": "123456", "local_channel": 114, "level_db": -61.2, "heard_s": 4.0}],
                           "units": [{"wiu": "1234560ff0", "codes": [8, 15], "aspects": ["Approach", null],
                                      "previous_codes": [3, 15], "changed_s": 12.0, "heard_s": 2.0, "channel": 114,
                                      "level_db": -60.2},
                                     {"wiu": "1234560ff1", "codes": [3], "aspects": ["Clear"], "previous_codes": null,
                                      "changed_s": 300.0, "heard_s": 3.0, "channel": 114, "level_db": -70.0}],
                           "changes": [{"wiu": "1234560ff0", "t": 1000.0, "codes": [8, 15], "aspects": ["Approach", null],
                                        "previous_codes": [3, 15], "channel": 114, "ago_s": 12.0}],
                           "error": null}}}
    """.trimIndent()

    @Test
    fun readsTheItcStatus() {
        val s = Status.parse(sample)
        assertEquals("itc", s.radio.mode)
        val itc = s.radio.itc!!
        assertEquals(listOf(102, 114), itc.channels)
        assertEquals(1234L, itc.messages)
        assertEquals("123456", itc.bases.single().radio)
        assertEquals(114, itc.bases.single().localChannel)
        val (moved, steady) = itc.units
        assertEquals("123456 0ff0", moved.name)
        assertEquals("Approach, code 15", moved.state)
        assertEquals(listOf(3, 15), moved.previous)
        assertNull(steady.previous)
        assertEquals("Clear", steady.state)
        assertEquals(listOf(3, 15), itc.changes.single().previous)
        assertEquals("", itc.error)
        // other modes have no ITC part
        assertNull(Status.parse("""{"radio": {"mode": "eot"}}""").radio.itc)
    }

    @Test
    fun namesAndChoices() {
        assertEquals("code 13, Clear", Itc.state(listOf(13, 3), listOf(null, "Clear")))
        assertEquals("no devices", Itc.state(listOf(), listOf()))
        assertEquals("Listening to ITC (220 MHz)", modeListening("itc"))
        assertEquals("Listening for EOT", modeListening("eot"))
        assertEquals(Prefs.MODES, MODE_NAMES.map { it.first })
        assertTrue("itc" in Prefs.MODES)
    }
}

package com.railspotting.android

import com.railspotting.android.core.PlanInfo
import com.railspotting.android.core.Status
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanInfoTest {
    @Test
    fun todaysHeadendNamesNoPlan() {
        val p = Status.parse("""{"running": true, "plan": {"channels": 9, "config_version": "41.7", "by_location": false}}""").plan
        assertEquals("9 channels", p.text)
        assertFalse(p.byLocation)
    }

    @Test
    fun plansByLocationAreNamed() {
        val p = PlanInfo.parse(JSONObject("""{"id": 2, "name": "NS Charlotte", "region": "ns-clt", "source": "location",
            "channels": 1, "by_location": true}"""))
        assertEquals("NS Charlotte, 1 channel", p.text)
        assertTrue(p.byLocation)
        assertEquals("location", p.source)
        assertEquals("0 channels", PlanInfo.parse(null).text)
    }
}

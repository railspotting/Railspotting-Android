package com.railspotting.android

import com.railspotting.android.location.Fixes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FixesTest {
    @Test
    fun aFixAsTheContractHasIt() {
        val o = JSONObject(Fixes.json(34.8526, -82.394, 12f, 1_791_034_502_100L, 18.2f, 92f)!!)
        assertEquals(34.8526, o.getDouble("lat"), 0.0)
        assertEquals(-82.394, o.getDouble("lon"), 0.0)
        assertEquals(12.0, o.getDouble("accuracy_m"), 0.0)
        assertEquals(1_791_034_502.1, o.getDouble("ts"), 1e-6)
        assertEquals(18.2, o.getDouble("speed_mps"), 1e-4)
        assertEquals(92.0, o.getDouble("bearing"), 0.0)
    }

    @Test
    fun noAccuracyNoFix() {
        assertNull(Fixes.json(1.0, 2.0, null, 1_791_034_502_000L, null, null))
        assertNull(Fixes.json(1.0, 2.0, Float.NaN, 1_791_034_502_000L, null, null))
    }

    @Test
    fun speedAndBearingOnlyWhenKnown() {
        val o = JSONObject(Fixes.json(1.0, 2.0, 5f, 1_791_034_502_000L, null, Float.NaN)!!)
        assertFalse(o.has("speed_mps"))
        assertFalse(o.has("bearing"))
    }
}

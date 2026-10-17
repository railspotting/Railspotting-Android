package com.railspotting.android

import com.railspotting.android.store.Prefs
import com.railspotting.android.ui.gainLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainTest {
    @Test
    fun offeredGains() {
        assertTrue(Prefs.DEFAULT_GAIN in Prefs.GAINS)
        assertEquals("auto", Prefs.GAINS.first())
        // every number is within what the core accepts (0 to 50 dB)
        Prefs.GAINS.drop(1).forEach { assertTrue(it.toDouble() in 0.0..50.0) }
    }

    @Test
    fun labels() {
        assertEquals("Automatic", gainLabel("auto"))
        assertEquals("40.2 dB (default)", gainLabel("40.2"))
        assertEquals("20.7 dB", gainLabel("20.7"))
    }
}

package com.railspotting.android

import com.railspotting.android.ui.switchEnabled
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenSwitchTest {
    @Test
    fun usableWithADongleOrWhileListening() {
        assertFalse(switchEnabled(listening = false, dongle = false))
        assertTrue(switchEnabled(listening = false, dongle = true))
        // unplugged while listening: still turns off
        assertTrue(switchEnabled(listening = true, dongle = false))
    }
}

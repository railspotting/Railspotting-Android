package com.railspotting.android

import com.railspotting.android.ui.switchLabel
import com.railspotting.android.usb.Reconnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectTest {
    @Test
    fun reopensAfterQuietThenBacksOff() {
        val r = Reconnect()
        // seen on 17 Oct: connected, then no samples; the core retried on a dead connection
        assertFalse(r.due(attached = true, streaming = false, nowMs = 0))
        assertFalse(r.due(true, false, 14_999))
        assertTrue(r.due(true, false, 15_000))
        assertEquals(1, r.tries)
        // the next try waits twice as long
        assertFalse(r.due(true, false, 15_000 + 29_999))
        assertTrue(r.due(true, false, 15_000 + 30_000))
        assertEquals(2, r.tries)
    }

    @Test
    fun capsAtFiveMinutesAndResetsOnData() {
        val r = Reconnect()
        var t = 0L
        r.due(true, false, t)
        repeat(10) {
            t += Reconnect.MAX_MS
            assertTrue(r.due(true, false, t))
        }
        assertFalse("never more than 5 minutes apart", r.due(true, false, t + Reconnect.MAX_MS - 1))
        // data flows again: back to the first wait
        assertFalse(r.due(true, true, t))
        assertEquals(0, r.tries)
        assertFalse(r.due(true, false, t + 1))
        assertTrue(r.due(true, false, t + 1 + Reconnect.FIRST_MS))
    }

    @Test
    fun notAttachedIsNotADeadConnection() {
        val r = Reconnect()
        // waiting for USB access, or no dongle: nothing to reopen
        assertFalse(r.due(attached = false, streaming = false, nowMs = 0))
        assertFalse(r.due(false, false, 10 * Reconnect.MAX_MS))
    }

    @Test
    fun switchSaysWhatTheDongleIsDoing() {
        assertEquals("Listening", switchLabel(listening = true, dongle = true, streaming = true, attached = true))
        assertEquals("No data", switchLabel(true, dongle = true, streaming = false, attached = true))
        assertEquals("Connecting", switchLabel(true, dongle = true, streaming = false, attached = false))
        assertEquals("No dongle", switchLabel(true, dongle = false, streaming = false, attached = false))
        assertEquals("Off", switchLabel(false, dongle = true, streaming = false, attached = false))
        assertEquals("No dongle", switchLabel(false, dongle = false, streaming = false, attached = false))
    }
}

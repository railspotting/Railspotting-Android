package com.railspotting.android

import com.railspotting.android.usb.RtlIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtlIdsTest {
    @Test
    fun knowsTheCommonDongles() {
        assertTrue(RtlIds.isDongle(0x0BDA, 0x2838)) // RTL-SDR Blog, Nooelec and most others
        assertTrue(RtlIds.isDongle(0x0BDA, 0x2832))
        assertFalse(RtlIds.isDongle(0x10C4, 0xEA60)) // the SR30C's cable is not a dongle
        // librtlsdr's whole list, as in usb_dongle.xml
        assertEquals(42, RtlIds.known.size)
    }
}

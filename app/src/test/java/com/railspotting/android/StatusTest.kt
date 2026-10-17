package com.railspotting.android

import com.railspotting.android.core.Status
import com.railspotting.android.ui.LivePlayer
import com.railspotting.android.ui.describe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusTest {
    // the shape of the core's Agent::status
    private val sample = """
        {"running": true, "device_id": "rs-0a1b2c3d4e5f", "online": true, "last_ok_age_s": 12, "last_error": null,
         "signed_out": null, "listening": true,
         "radio": {"mode": "voice", "attached": true, "connected": true, "live": true, "tuner": "R820T",
                   "error": null, "window_mhz": [159.8, 162.2],
                   "now_playing": {"freq": 160.95, "label": "NS road", "since": 1791034500.0, "event_id": "e2"},
                   "channels": [{"freq": 160.95, "label": "NS road", "kind": "voice", "receiving": true}]},
         "share": false, "fix_age_s": 75, "fixes_sent": 4, "fixes_pending": 0, "outbox_depth": 2, "events_uploaded": 9,
         "recent_events": [
           {"event_id": "e2", "state": "open", "start_ts": 1791034500.0, "end_ts": null, "freq": 160.95,
            "kind": "voice", "label": "NS road", "peak_rssi": 420},
           {"event_id": "e1", "state": "closed", "start_ts": 1791034402.1, "end_ts": 1791034403.0, "freq": 457.9375,
            "kind": "telemetry", "label": "EOT", "peak_rssi": 400, "decoded": "EOT unit 41234, 88 psi, moving",
            "telemetry": {"unit_id": 41234, "brake_pipe_psi": 88, "motion": true}}],
         "clock_correction_s": 0}
    """.trimIndent()

    @Test
    fun readsTheCoresStatus() {
        val s = Status.parse(sample)
        assertTrue(s.running && s.online && s.listening)
        assertEquals("voice", s.radio.mode)
        assertTrue(s.radio.connected && s.radio.live)
        assertEquals("R820T", s.radio.tuner)
        assertEquals("", s.radio.error)
        assertEquals("e2", s.radio.playing!!.eventId)
        assertTrue(s.radio.channels.single().receiving)
        assertFalse(s.share)
        assertEquals(75.0, s.fixAgeS!!, 0.0)
        assertNull(s.signedOut)
        val (call, eot) = s.heard
        assertTrue(call.open && !call.telemetry)
        assertNull(call.endTs)
        assertEquals(41234L, eot.unitId)
        assertEquals(88L, eot.brakePipePsi)
        assertEquals(true, eot.moving)
        assertEquals("EOT unit 41234, 88 psi, moving", eot.decoded)
    }

    @Test
    fun describesWhatWasHeard() {
        val (call, eot) = Status.parse(sample).heard
        assertEquals("Voice call, on the air 0:12", describe(call, call.startTs + 12))
        assertEquals("EOT unit 41234, 88 psi, moving", describe(eot, 0.0))
        assertEquals("EOT signal, not decoded", describe(eot.copy(decoded = ""), 0.0))
        assertEquals("Voice call, 1:05", describe(call.copy(open = false, endTs = call.startTs + 65), 0.0))
    }

    @Test
    fun revokedAndStopped() {
        assertEquals("revoked", Status.parse("""{"running": false, "signed_out": "revoked"}""").signedOut)
        val s = Status.parse("""{"running": false}""")
        assertFalse(s.running)
        assertNull(s.fixAgeS)
        assertFalse(s.radio.attached)
        assertEquals("eot", s.radio.mode)
        assertFalse(Status.parse("not json").running)
        assertFalse(Status.parse(null).running)
    }

    @Test
    fun agesAndClocks() {
        assertEquals("none yet", Status.age(null))
        assertEquals("12 s ago", Status.age(12.0))
        assertEquals("3 min ago", Status.age(200.0))
        assertEquals("2 h ago", Status.age(7300.0))
        assertEquals("0:00", Status.clock(-3.0))
        assertEquals("3:05", Status.clock(185.0))
    }

    @Test
    fun liveAudioIsLiftedNotClipped() {
        assertEquals(0f, LivePlayer.shape(0f), 0f)
        assertTrue(LivePlayer.shape(0.1f) > 0.25f)
        assertTrue(LivePlayer.shape(10f) <= 1f)
        assertTrue(LivePlayer.shape(-10f) >= -1f)
    }
}

package com.railspotting.android.usb

/**
 * When to open the dongle's USB connection afresh. The core retries on the connection it was
 * given, which can't recover after a USB I/O error or a port reset (Android's USB protection
 * cuts USB data while the phone is locked), so the app reopens it: first after 15 s without
 * data, then at growing intervals up to 5 minutes, and from the start again once data flows.
 */
class Reconnect(private val firstMs: Long = FIRST_MS, private val maxMs: Long = MAX_MS) {
    private var since: Long? = null
    private var waitMs = firstMs
    var tries = 0
        private set

    /**
     * @param attached the core has the dongle's connection
     * @param streaming samples are arriving
     * @return true when the connection should be opened afresh now
     */
    fun due(attached: Boolean, streaming: Boolean, nowMs: Long): Boolean {
        if (streaming || !attached) {
            if (streaming) reset()
            return false
        }
        val start = since ?: nowMs.also { since = it }
        if (nowMs - start < waitMs) return false
        tries++
        since = nowMs
        waitMs = (waitMs * 2).coerceAtMost(maxMs)
        return true
    }

    /** No data now, but not for long enough to reopen: the switch says so. */
    fun waiting(attached: Boolean, streaming: Boolean) = attached && !streaming

    private fun reset() {
        since = null
        waitMs = firstMs
        tries = 0
    }

    companion object {
        const val FIRST_MS = 15_000L
        const val MAX_MS = 300_000L
    }
}

package com.railspotting.android.location

import org.json.JSONObject

/** A GPS fix as the contract's LocationFix (v1.7, 2). The core checks ranges and decides what to send. */
object Fixes {
    /** A fix from an Android location, or null without a usable accuracy. */
    fun of(l: android.location.Location): String? = json(
        l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, l.time,
        if (l.hasSpeed()) l.speed else null, if (l.hasBearing()) l.bearing else null,
    )

    /**
     * @param timeMs when the fix was taken (Location.getTime), Unix milliseconds
     * @return the fix, or null without a usable accuracy
     */
    fun json(lat: Double, lon: Double, accuracyM: Float?, timeMs: Long, speedMps: Float?, bearing: Float?): String? {
        if (accuracyM == null || accuracyM.isNaN() || accuracyM < 0f) return null
        val o = JSONObject()
            .put("lat", lat)
            .put("lon", lon)
            .put("accuracy_m", accuracyM.toDouble())
            .put("ts", timeMs / 1000.0)
        speedMps?.takeIf { !it.isNaN() }?.let { o.put("speed_mps", it.toDouble()) }
        bearing?.takeIf { !it.isNaN() }?.let { o.put("bearing", it.toDouble()) }
        return o.toString()
    }
}

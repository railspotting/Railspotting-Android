package com.railspotting.android.trains

import org.json.JSONException
import org.json.JSONObject

/** A train the headend knows near the phone (proposed v1.13, GET /trains/nearby). */
data class NearTrain(
    val id: Long,
    val unit: String,
    val railroad: String,
    val motion: String,
    /** When it was last heard, Unix seconds. */
    val last: Double,
    val distanceKm: Double,
    val crossing: String,
) {
    /** "NS · EOT 76520" */
    val title: String get() = listOf(railroad, "EOT $unit").filter { it.isNotBlank() }.joinToString(" · ")

    /** "stopped · 0.8 mi · 3 min ago, near Brook Glenn Rd" */
    fun detail(nowS: Double): String {
        val mins = ((nowS - last) / 60).toInt().coerceAtLeast(0)
        val ago = if (mins < 1) "just now" else if (mins < 120) "$mins min ago" else "${mins / 60} h ago"
        val mi = "%.1f mi".format(distanceKm / 1.609344)
        return listOf(motion, mi, ago).filter { it.isNotBlank() }.joinToString(" · ") +
            if (crossing.isNotBlank()) ", near $crossing" else ""
    }

    companion object {
        /** The core's answer: `{"ok": true, "trains": [...]}`; null with the message on failure. */
        fun list(json: String): Pair<List<NearTrain>?, String?> {
            val o = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null to "No answer from RailSpotting"
            }
            if (!o.optBoolean("ok")) return null to o.optString("message", "Could not look for trains")
            val a = o.optJSONArray("trains") ?: return listOf<NearTrain>() to null
            val trains = (0 until a.length()).mapNotNull { i ->
                val t = a.optJSONObject(i) ?: return@mapNotNull null
                val id = t.optLong("id", -1)
                if (id <= 0) return@mapNotNull null
                fun str(k: String) = if (t.isNull(k)) "" else t.optString(k)
                val crossing = t.optJSONObject("crossing")?.optString("name") ?: str("crossing")
                NearTrain(id, str("unit"), str("railroad"), str("motion"), t.optDouble("last", 0.0),
                    t.optDouble("distance_km", 0.0), crossing)
            }
            return trains to null
        }
    }
}

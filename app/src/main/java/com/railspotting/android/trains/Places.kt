package com.railspotting.android.trains

import org.json.JSONException
import org.json.JSONObject

/** A crossing a report can be placed at (proposed v1.14). */
data class PlaceCrossing(val id: String, val street: String, val city: String, val milepost: String, val distanceKm: Double) {
    /** "Elm St, MP 12.3 · 0.1 mi" */
    val label: String get() {
        val name = street.ifBlank { "Crossing $id" }
        val mp = if (milepost.isNotBlank()) ", MP $milepost" else ""
        return "$name$mp · ${"%.1f".format(distanceKm / 1.609344)} mi"
    }
}

/** A railroad line near the phone: its railroad and subdivision, with its crossings, nearest first. */
data class PlaceLine(val railroad: String, val subdivision: String, val crossings: List<PlaceCrossing>) {
    /** "NS · Main Line" */
    val label: String get() = listOf(railroad, subdivision).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Unnamed line" }

    companion object {
        /** The core's answer, `{"ok": true, "lines": [...]}`; empty when there is none. */
        fun list(json: String): List<PlaceLine> {
            val o = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return listOf()
            }
            if (!o.optBoolean("ok")) return listOf()
            val a = o.optJSONArray("lines") ?: return listOf()
            return (0 until a.length()).mapNotNull { i ->
                val l = a.optJSONObject(i) ?: return@mapNotNull null
                val cs = l.optJSONArray("crossings") ?: return@mapNotNull null
                val crossings = (0 until cs.length()).mapNotNull { j ->
                    val c = cs.optJSONObject(j) ?: return@mapNotNull null
                    val id = c.optString("id")
                    if (id.isBlank()) null
                    else PlaceCrossing(id, c.optString("street"), c.optString("city"), c.optString("milepost"), c.optDouble("distance_km", 0.0))
                }
                if (crossings.isEmpty()) null else PlaceLine(l.optString("railroad"), l.optString("subdivision"), crossings)
            }
        }

        /** Track numbers offered (Main 1 to Main 4); the core takes up to 9. */
        val TRACKS = (1..4).toList()
    }
}

package com.railspotting.android.car

import org.json.JSONObject

/** A crossing near the phone, as the core lists them for the car (train activity first). */
data class NearCrossing(
    val id: String,
    val street: String,
    val city: String,
    val railroad: String,
    val lat: Double,
    val lon: Double,
    val status: String,
    val label: String,
    val km: Double,
) {
    /** Trains now or lately: the rows and markers that stand out. */
    val busy: Boolean get() = status in BUSY

    val miles: Double get() = km / KM_PER_MILE

    /** "Elm Street (NS)" */
    val title: String get() = street.ifEmpty { "Crossing $id" } + if (railroad.isNotEmpty()) " ($railroad)" else ""

    companion object {
        const val KM_PER_MILE = 1.609344
        val BUSY = setOf("nearby", "recent", "earlier")

        fun list(json: String): List<NearCrossing> {
            val o = try {
                JSONObject(json)
            } catch (_: org.json.JSONException) {
                return listOf()
            }
            if (!o.optBoolean("ok")) return listOf()
            val a = o.optJSONArray("crossings") ?: return listOf()
            return (0 until a.length()).mapNotNull { i ->
                val c = a.optJSONObject(i) ?: return@mapNotNull null
                fun str(k: String) = if (c.isNull(k)) "" else c.optString(k)
                NearCrossing(str("id"), str("street"), str("city"), str("railroad"), c.optDouble("lat"), c.optDouble("lon"),
                    str("status"), str("label"), c.optDouble("distance_km", 0.0))
            }
        }
    }
}

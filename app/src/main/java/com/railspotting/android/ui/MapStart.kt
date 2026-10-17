package com.railspotting.android.ui

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Where the live map opens: on the phone, about a mile across. The map page keeps its position
 * in local storage as `rs-map-pos` (`{lat, lon, zoom}`, docs/embed-map.md) and opens there, so the
 * app writes it before the page's scripts run.
 */
object MapStart {
    const val MILE_M = 1609.344
    const val ORIGIN = "https://railspotting.com"
    /** How long the map waits for the phone's position before opening without it. */
    const val WAIT_MS = 3_500L
    /** Metres per pixel at zoom 0 on the equator (Web Mercator, 256-pixel tiles). */
    private const val M_PER_PX_Z0 = 156_543.03392

    /** The whole-number zoom at which `widthPx` CSS pixels span about `spanM` metres at `lat`. */
    fun zoomFor(lat: Double, widthPx: Double, spanM: Double = MILE_M): Int {
        val z = ln(M_PER_PX_Z0 * cos(Math.toRadians(lat)) * widthPx / spanM) / ln(2.0)
        return z.roundToInt().coerceIn(3, 19)
    }

    /**
     * Whether the page may have the phone's position (its Here button): only the map's own site,
     * and only while the app has location permission. The page moves the map with it, in the web
     * view; it isn't sent anywhere.
     */
    fun shareLocation(origin: String?, permitted: Boolean): Boolean =
        permitted && origin?.trimEnd('/') == ORIGIN

    /** The script that sets the start position (runs before the page's own scripts). */
    fun script(lat: Double, lon: Double, zoom: Int): String {
        val pos = JSONObject().put("lat", round5(lat)).put("lon", round5(lon)).put("zoom", zoom)
        return "try { localStorage.setItem(\"rs-map-pos\", ${JSONObject.quote(pos.toString())}); } catch (e) {}"
    }

    // the page keeps five decimals (about a metre)
    private fun round5(x: Double) = Math.round(x * 100_000) / 100_000.0
}

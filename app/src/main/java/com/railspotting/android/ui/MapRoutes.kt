package com.railspotting.android.ui

import java.net.URI

/**
 * Where links go from the Live map tab, as the headend's docs/embed-map.md asks: the web view
 * loads only https://railspotting.com/embed/map (any query) and the YouTube frames it embeds;
 * every other navigation and every new window opens in the browser, where the site's sign-in
 * works (Google does not allow sign-in inside an app's web view).
 *
 * The page's own requests (/static/, /api/public/, map tiles) are not navigations and need no
 * rule here. Same rules as the macOS app's apps/desktop/src/site.rs.
 */
object MapRoutes {
    const val MAP = "https://railspotting.com/embed/map"

    enum class Route { Stay, Browser, Drop }

    private fun parse(url: String): URI? = try {
        URI(url)
    } catch (_: Exception) {
        null
    }

    private fun https(u: URI, host: String) = u.scheme == "https" && u.port == -1 && u.host == host

    private fun web(u: URI?) = if (u?.scheme == "http" || u?.scheme == "https") Route.Browser else Route.Drop

    fun isMap(url: String): Boolean = parse(url)?.let { https(it, "railspotting.com") && it.rawPath == "/embed/map" } == true

    private fun isVideoFrame(u: URI) = https(u, "www.youtube-nocookie.com") && (u.rawPath ?: "").startsWith("/embed/")

    /** A navigation in the web view: the main frame, or a sub-frame. */
    fun navigation(url: String, mainFrame: Boolean): Route {
        if (url == "about:blank") return Route.Stay
        val u = parse(url) ?: return Route.Drop
        if (mainFrame) return if (isMap(url)) Route.Stay else web(u)
        return if (isVideoFrame(u) || isMap(url)) Route.Stay else Route.Drop
    }

    /** A new window (links on the page open new tabs): always the browser. */
    fun newWindow(url: String): Route = web(parse(url))
}

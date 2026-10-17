package com.railspotting.android

import com.railspotting.android.ui.MapRoutes
import com.railspotting.android.ui.MapRoutes.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/** The same cases as the macOS app's site.rs tests (docs/embed-map.md in the headend). */
class MapRoutesTest {
    @Test
    fun loadsThePageAndItsVideoFramesOnly() {
        for (u in listOf("https://railspotting.com/embed/map", "https://railspotting.com/embed/map?region=x&at=2#y", "about:blank")) {
            assertEquals(u, Route.Stay, MapRoutes.navigation(u, mainFrame = true))
        }
        assertEquals(Route.Stay, MapRoutes.navigation("https://www.youtube-nocookie.com/embed/abc123?start=4", mainFrame = false))
        for (u in listOf("https://railspotting.com/", "https://railspotting.com/embed/mapx", "https://railspotting.com/embed/",
                         "https://www.railspotting.com/embed/map", "http://railspotting.com/embed/map",
                         "https://railspotting.com:8443/embed/map", "https://railspotting.com/trains",
                         "https://accounts.google.com/o/oauth2/auth", "https://www.youtube.com/watch?v=abc",
                         "https://railspotting.com.evil.example/embed/map", "https://www.youtube-nocookie.com/embed/abc")) {
            assertEquals(u, Route.Browser, MapRoutes.navigation(u, mainFrame = true))
        }
        assertEquals(Route.Drop, MapRoutes.navigation("mailto:x@example.com", mainFrame = true))
        assertEquals(Route.Drop, MapRoutes.navigation("https://example.com/frame", mainFrame = false))
    }

    @Test
    fun newWindowsGoToTheBrowser() {
        assertEquals(Route.Browser, MapRoutes.newWindow("https://railspotting.com/embed/map"))
        assertEquals(Route.Browser, MapRoutes.newWindow("https://railspotting.com/trains/123"))
        assertEquals(Route.Drop, MapRoutes.newWindow("javascript:alert(1)"))
    }
}

package com.railspotting.android

import com.railspotting.android.ui.MapStart
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStartTest {
    @Test
    fun aMileAcrossThePhone() {
        // a Pixel's 411 CSS pixels at Greenville's latitude: zoom 15 (about 1.6 km across)
        assertEquals(15, MapStart.zoomFor(34.85, 411.0))
        // a wider screen zooms in one step to keep about a mile
        assertEquals(16, MapStart.zoomFor(34.85, 800.0))
        // further north the same zoom covers less ground
        assertTrue(MapStart.zoomFor(60.0, 411.0) <= MapStart.zoomFor(34.85, 411.0))
        // never outside the page's limits
        assertEquals(19, MapStart.zoomFor(0.0, 1e9))
        assertEquals(3, MapStart.zoomFor(0.0, 0.001))
    }

    @Test
    fun theScriptSetsThePagesRememberedPosition() {
        val s = MapStart.script(34.8526012345, -82.3940098765, 15)
        assertTrue(s.startsWith("try { localStorage.setItem(\"rs-map-pos\", ") && s.endsWith("); } catch (e) {}"))
        // the stored value is the JSON the page reads back (key order does not matter)
        val quoted = s.substringAfter("\"rs-map-pos\", ").substringBeforeLast("); } catch")
        val pos = JSONObject(JSONTokener(quoted).nextValue() as String)
        assertEquals(34.8526, pos.getDouble("lat"), 0.0)
        assertEquals(-82.39401, pos.getDouble("lon"), 0.0)
        assertEquals(15, pos.getInt("zoom"))
    }

    @Test
    fun hereOnlyForTheMapWithPermission() {
        assertTrue(MapStart.shareLocation("https://railspotting.com/", true))
        assertTrue(MapStart.shareLocation("https://railspotting.com", true))
        assertFalse(MapStart.shareLocation("https://railspotting.com/", false))
        assertFalse(MapStart.shareLocation("https://example.com/", true))
        assertFalse(MapStart.shareLocation("http://railspotting.com/", true))
        assertFalse(MapStart.shareLocation("https://railspotting.com.example.com/", true))
        assertFalse(MapStart.shareLocation(null, true))
    }

    @Test
    fun hereAsksForLocationWhenTheAppHasNone() {
        val site = "https://railspotting.com/"
        assertEquals(MapStart.Here.Grant, MapStart.here(site, permitted = true, canAsk = true))
        // a fresh install has no location permission until listening starts: ask, don't refuse
        assertEquals(MapStart.Here.Ask, MapStart.here(site, permitted = false, canAsk = true))
        assertEquals(MapStart.Here.Refuse, MapStart.here(site, permitted = false, canAsk = false))
        // another site never gets the position, permission or not
        assertEquals(MapStart.Here.Refuse, MapStart.here("https://example.com/", permitted = true, canAsk = true))
        assertEquals(MapStart.Here.Refuse, MapStart.here(null, permitted = true, canAsk = true))
    }
}

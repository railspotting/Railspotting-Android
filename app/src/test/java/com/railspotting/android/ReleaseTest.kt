package com.railspotting.android

import com.railspotting.android.update.Release
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    private val sha = "a".repeat(64)
    private val apk = "https://github.com/railspotting/Railspotting-Android/releases/download/v0.2.0/railspotting-0.2.0.apk"

    private fun json(code: Any = 5, name: String = "0.2.0", url: String = apk, hash: String = sha) =
        """{"version_code": $code, "version_name": "$name", "apk": "$url", "sha256": "$hash", "notes": "Fixes"}"""

    @Test
    fun aGoodRelease() {
        val r = Release.parse(json())!!
        assertEquals(5L, r.versionCode)
        assertEquals("0.2.0", r.versionName)
        assertEquals("Fixes", r.notes)
        assertTrue(r.newerThan(4))
        assertFalse(r.newerThan(5))
    }

    @Test
    fun onlyFromThisRepositorysReleases() {
        assertNull(Release.parse(json(url = "https://example.com/railspotting.apk")))
        assertNull(Release.parse(json(url = "https://github.com/someone/else/releases/download/v1/x.apk")))
        assertNull(Release.parse(json(url = "http://github.com/railspotting/Railspotting-Android/releases/download/v1/x.apk")))
        assertNull(Release.parse(json(url = Release.APK_PREFIX + "../../evil/x.apk")))
    }

    @Test
    fun badFieldsAreRefused() {
        assertNull(Release.parse("not json"))
        assertNull(Release.parse(json(code = 0)))
        assertNull(Release.parse(json(code = "\"five\"")))
        assertNull(Release.parse(json(name = "")))
        assertNull(Release.parse(json(hash = "abc")))
        // upper-case hex is fine: compared in lower case
        assertEquals(sha, Release.parse(json(hash = "A".repeat(64)))!!.sha256)
    }
}

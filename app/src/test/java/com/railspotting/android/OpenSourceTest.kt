package com.railspotting.android

import com.railspotting.android.ui.OpenSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The open source list the app ships (tools/open_source.py writes it). */
class OpenSourceTest {
    private val assets = File("src/main/assets")
    private val sections = OpenSource.parse(File(assets, OpenSource.ASSET).readText())

    @Test
    fun theDriverIsListedWithItsLicenceTexts() {
        val driver = sections.first { it.title == "Radio driver" }.items
        val rtl = driver.first { it.name == "librtlsdr" }
        assertEquals("GPL-2.0-or-later", rtl.license)
        assertTrue(rtl.note.contains("rtlsdr_open_sys_dev"))
        for (o in driver) {
            assertTrue("${o.name} has no licence text", o.text.isNotEmpty())
            assertTrue("${o.text} is missing", File(assets, o.text).readText().contains("GNU"))
        }
    }

    @Test
    fun everyEntryHasALicenceAndALink() {
        val all = sections.flatMap { it.items }
        assertTrue("only ${all.size} entries", all.size > 100)
        for (o in all) {
            assertTrue(o.name, o.license.isNotEmpty())
            assertTrue(o.name, o.url.startsWith("https://"))
        }
        // our own crates are one entry, not one per crate
        assertTrue(all.none { it.name.startsWith("railspotting-") })
    }
}

package com.railspotting.android

import com.railspotting.android.store.Sealed
import com.railspotting.android.store.SeedBox
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.KeyGenerator

/** The sealing with a software AES key; on the phone the key lives in the Android Keystore. */
class SeedBoxTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SeedBox { key }
    private val seed = ByteArray(32) { it.toByte() }

    @Test
    fun sealsAndOpens() {
        val s = box.seal(seed)
        assertNotEquals(seed.toList(), s.data.toList())
        val back = Sealed.decode(s.encode())!!
        assertArrayEquals(seed, box.open(back))
    }

    @Test
    fun aChangedSealDoesNotOpen() {
        val s = box.seal(seed)
        s.data[0] = (s.data[0].toInt() xor 1).toByte()
        try {
            box.open(s)
            fail("opened a tampered seal")
        } catch (_: javax.crypto.AEADBadTagException) {
        }
    }

    @Test
    fun badEncodings() {
        assertNull(Sealed.decode(null))
        assertNull(Sealed.decode("nodot"))
        assertNull(Sealed.decode("!!.??"))
    }
}

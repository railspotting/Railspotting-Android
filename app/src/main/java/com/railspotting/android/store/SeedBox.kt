package com.railspotting.android.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A sealed seed: AES-GCM ciphertext and its IV. */
class Sealed(val iv: ByteArray, val data: ByteArray) {
    fun encode(): String = b64(iv) + "." + b64(data)

    companion object {
        fun decode(s: String?): Sealed? {
            val parts = s?.split('.') ?: return null
            if (parts.size != 2) return null
            return try {
                Sealed(unb64(parts[0]), unb64(parts[1]))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        // java.util.Base64, not android.util's, so the unit tests can run it
        private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
        private fun unb64(s: String) = java.util.Base64.getDecoder().decode(s)
    }
}

/**
 * Keeps the receiver's private key (its Ed25519 seed) encrypted at rest with an AES-GCM key that
 * never leaves the Android Keystore. Android 10 to 12 cannot hold an Ed25519 key in the Keystore
 * itself, so this works the same on every version the app supports.
 */
class SeedBox(private val key: () -> SecretKey) {
    fun seal(seed: ByteArray): Sealed {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        return Sealed(c.iv, c.doFinal(seed))
    }

    fun open(s: Sealed): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, s.iv))
        return c.doFinal(s.data)
    }

    companion object {
        const val ALIAS = "railspotting.receiver.seed"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val STORE = "AndroidKeyStore"

        /** The Keystore key, made on first use. */
        fun keystoreKey(): SecretKey {
            val ks = KeyStore.getInstance(STORE).apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
            gen.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return gen.generateKey()
        }

        fun deleteKeystoreKey() {
            try {
                KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS)
            } catch (_: Exception) {
                // already gone
            }
        }
    }
}

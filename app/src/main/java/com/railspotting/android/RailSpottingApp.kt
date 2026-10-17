package com.railspotting.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.railspotting.android.core.Native
import com.railspotting.android.service.ListenService
import com.railspotting.android.store.Prefs
import com.railspotting.android.store.SeedBox
import java.io.File

/** Addresses the app uses. The API base is the contract's; the pages are the public site's. */
object Urls {
    const val SERVER = "https://api.railspotting.com"
    const val ACCOUNT = "https://railspotting.com/account"
    const val SITE = "https://railspotting.com/"
}

class RailSpottingApp : Application() {
    lateinit var prefs: Prefs
        private set

    /** The receiver's folder: outbox, cached plan, scanner state. */
    val stateDir: File get() = File(filesDir, "receiver")

    private val box = SeedBox(SeedBox::keystoreKey)

    /** The app is on screen (for the heartbeat's `foreground`). */
    @Volatile var foreground = false

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        Native.init(BuildConfig.DEBUG, File(filesDir, "logs").absolutePath)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, getString(R.string.channel_listening), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.channel_listening_about) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRAINS, getString(R.string.channel_trains), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = getString(R.string.channel_trains_about) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACCOUNT, getString(R.string.channel_account), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = getString(R.string.channel_account_about) },
        )
    }

    /** The receiver's key seed, made and sealed on first use. Wipe the copy when done. */
    @Synchronized
    fun seed(): ByteArray {
        prefs.sealedSeed?.let { sealed ->
            try {
                return box.open(sealed)
            } catch (_: Exception) {
                // the Keystore key is gone (data restored elsewhere, or reset): start over
            }
        }
        return newSeed()
    }

    /** Replace the key (a new install's, or after `409 key_in_use`). */
    @Synchronized
    fun newSeed(): ByteArray {
        val seed = Native.newSeed()
        prefs.sealedSeed = box.seal(seed)
        return seed
    }

    /**
     * Sign out: stop listening, then forget the key, the device ID and the receiver's folder
     * (contract v1.7, 1.5). The receiver stays on the account for the next sign-in.
     */
    fun signOut(reason: String? = null) {
        ListenService.stop(this)
        Thread {
            Native.forget(stateDir.absolutePath)
        }.start()
        prefs.clear()
        SeedBox.deleteKeystoreKey()
        prefs.signedOutReason = reason
    }

    companion object {
        const val CHANNEL_LISTENING = "listening"
        const val CHANNEL_ACCOUNT = "account"
        const val CHANNEL_TRAINS = "trains"

        fun of(context: Context) = context.applicationContext as RailSpottingApp
    }
}

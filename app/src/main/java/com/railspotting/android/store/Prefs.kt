package com.railspotting.android.store

import android.content.Context
import androidx.core.content.edit

/** What the app keeps between runs. Nothing here is backed up (data_extraction_rules.xml). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("receiver", Context.MODE_PRIVATE)

    var sealedSeed: Sealed?
        get() = Sealed.decode(sp.getString(SEED, null))
        set(v) = sp.edit { if (v == null) remove(SEED) else putString(SEED, v.encode()) }

    var linked: Linked?
        get() = Linked.fromJson(sp.getString(LINKED, null))
        set(v) = sp.edit { if (v == null) remove(LINKED) else putString(LINKED, v.toJson()) }

    /** Sharing with the public map: on by default (contract v1.7, 6). The app's setting wins. */
    var share: Boolean
        get() = sp.getBoolean(SHARE, true)
        set(v) = sp.edit { putBoolean(SHARE, v) }

    /**
     * The dongle's mode: "eot" (end-of-train, the default, as on fixed receivers), "voice" (road
     * channels) or "itc" (220 MHz PTC, kept on the phone).
     */
    var mode: String
        get() = sp.getString(MODE, "eot").takeIf { it in MODES } ?: "eot"
        set(v) = sp.edit { putString(MODE, v) }

    /** The dongle's tuner gain: "auto" or dB as text, one of [GAINS]. */
    var gain: String
        get() = sp.getString(GAIN, DEFAULT_GAIN).takeIf { it in GAINS } ?: DEFAULT_GAIN
        set(v) = sp.edit { putString(GAIN, v) }

    /**
     * Keep the screen on while listening with the app on screen. A locked phone can cut USB:
     * Android's USB protection (Advanced Protection) turns USB data off while it is locked.
     */
    var stayAwake: Boolean
        get() = sp.getBoolean(STAY_AWAKE, false)
        set(v) = sp.edit { putBoolean(STAY_AWAKE, v) }

    /** Alert when this phone's dongle hears an end-of-train device. */
    var alertOwnEot: Boolean
        get() = sp.getBoolean(ALERT_OWN, true)
        set(v) = sp.edit { putBoolean(ALERT_OWN, v) }

    /** Alert when another receiver hears an end-of-train device within [nearbyMiles]. */
    var alertNearbyEot: Boolean
        get() = sp.getBoolean(ALERT_NEARBY, true)
        set(v) = sp.edit { putBoolean(ALERT_NEARBY, v) }

    var nearbyMiles: Int
        get() = sp.getInt(NEARBY_MILES, 50).takeIf { it in RADII } ?: 50
        set(v) = sp.edit { putInt(NEARBY_MILES, v) }

    /** Install new versions from the app's GitHub releases by themselves (release builds). */
    var autoUpdate: Boolean
        get() = sp.getBoolean(AUTO_UPDATE, true)
        set(v) = sp.edit { putBoolean(AUTO_UPDATE, v) }

    /** When the app last looked for an update, Unix milliseconds. */
    var lastUpdateCheck: Long
        get() = sp.getLong(LAST_UPDATE_CHECK, 0L)
        set(v) = sp.edit { putLong(LAST_UPDATE_CHECK, v) }

    /** What the last update check or install said, for the menu. */
    var updateStatus: String?
        get() = sp.getString(UPDATE_STATUS, null)
        set(v) = sp.edit { if (v == null) remove(UPDATE_STATUS) else putString(UPDATE_STATUS, v) }

    /** A checked update waiting for listening to stop (its APK in the cache). */
    var pendingUpdate: String?
        get() = sp.getString(PENDING_UPDATE, null)
        set(v) = sp.edit { if (v == null) remove(PENDING_UPDATE) else putString(PENDING_UPDATE, v) }

    /** Why the headend signed this phone out, to tell the owner on the sign-in screen. */
    var signedOutReason: String?
        get() = sp.getString(SIGNED_OUT, null)
        set(v) = sp.edit { if (v == null) remove(SIGNED_OUT) else putString(SIGNED_OUT, v) }

    /** Forget the key, the device and the settings (sign out). */
    fun clear() = sp.edit { clear() }

    companion object {
        /** The radii the app offers for nearby alerts, in miles. */
        val RADII = listOf(10, 25, 50, 100)

        /**
         * Gains the app offers (the R820T tuner's own steps). Phones default higher than fixed
         * receivers' 20.7 dB: a small antenna leaves weak EOT bursts with bit errors.
         */
        val GAINS = listOf("auto", "20.7", "29.7", "36.4", "40.2", "44.5", "49.6")
        const val DEFAULT_GAIN = "40.2"

        private const val SEED = "seed"
        private const val LINKED = "linked"
        private const val SHARE = "share"
        private const val SIGNED_OUT = "signed_out"
        private const val MODE = "mode"
        val MODES = listOf("voice", "eot", "itc")
        private const val GAIN = "gain"
        private const val STAY_AWAKE = "stay_awake"
        private const val AUTO_UPDATE = "auto_update"
        private const val LAST_UPDATE_CHECK = "last_update_check"
        private const val UPDATE_STATUS = "update_status"
        private const val PENDING_UPDATE = "pending_update"
        private const val ALERT_OWN = "alert_own_eot"
        private const val ALERT_NEARBY = "alert_nearby_eot"
        private const val NEARBY_MILES = "nearby_miles"
    }
}

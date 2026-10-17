package com.railspotting.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.railspotting.android.BuildConfig
import com.railspotting.android.R
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.Urls
import com.railspotting.android.alerts.NearbyEot
import com.railspotting.android.alerts.OwnEot
import com.railspotting.android.alerts.TrainAlerts
import com.railspotting.android.core.Native
import com.railspotting.android.core.Status
import com.railspotting.android.location.Fixes
import com.railspotting.android.location.LocationFeed
import com.railspotting.android.ui.MainActivity
import com.railspotting.android.usb.Dongle
import com.railspotting.android.usb.Reconnect
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listening: the receiver core, the RTL-SDR dongle and GPS, in a foreground service with a
 * persistent notification. Nothing runs outside it: no radio, no location and no headend calls
 * while the owner has not started listening.
 *
 * GPS is on only while the dongle is streaming (the contract's "listening"); the service keeps
 * heartbeats going while it waits for a dongle.
 */
class ListenService : Service() {
    private lateinit var app: RailSpottingApp
    private lateinit var usb: Dongle
    private lateinit var gps: LocationFeed
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var lastNote = ""
    private val ownEot = OwnEot()
    private val reconnect = Reconnect()
    private lateinit var bg: ExecutorService
    /** Status calls into the core, off the main thread; one at a time. */
    private lateinit var statusBg: ExecutorService
    private val asking = AtomicBoolean(false)
    /** "Keep the screen on while listening": a dim-screen lock, renewed each tick. */
    private var awake: PowerManager.WakeLock? = null
    private var lastNearby = 0L
    private val tick = object : Runnable {
        override fun run() {
            onTick()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = RailSpottingApp.of(this)
        usb = Dongle(this) { main.post { onTick() } }
        gps = LocationFeed(this)
        bg = Executors.newSingleThreadExecutor()
        statusBg = Executors.newSingleThreadExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundNow()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DONGLE) usb.poll()
        if (!started) begin()
        return START_NOT_STICKY
    }

    /** Location type when location is allowed (it must be, to listen); the USB device type as well once the dongle is open. */
    private fun startForegroundNow(): Boolean {
        var types = 0
        if (gps.permitted()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (usb.connected) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (types == 0) {
            Log.w(TAG, "not listening: location is not allowed")
            return false
        }
        return try {
            ServiceCompat.startForeground(this, NOTE_ID, note("Starting"), types)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start listening: ${e.message}")
            false
        }
    }

    private fun begin() {
        val linked = app.prefs.linked ?: run {
            stopSelf()
            return
        }
        Native.setMode(app.prefs.mode)
        Native.setGain(app.prefs.gain)
        val seed = app.seed()
        val err = try {
            Native.start(Urls.SERVER, app.stateDir.absolutePath, seed, linked.deviceId, BuildConfig.VERSION_NAME, app.prefs.share)
        } finally {
            seed.fill(0)
        }
        if (err.isNotEmpty()) {
            Log.e(TAG, "receiver did not start: $err")
            stopSelf()
            return
        }
        started = true
        running = true
        usb.start()
        // the channel list for where the phone is now (the core sends the fix only when the
        // headend takes one while not listening)
        bg.execute {
            val at = gps.whereabouts() ?: return@execute
            Native.refreshPlan(Fixes.of(at) ?: "{}")
        }
        main.post(tick)
    }

    /**
     * Ask the core for its status off the main thread (a slow answer must never freeze the app),
     * then act on it on the main thread. A tick while the last question is open is skipped.
     */
    private fun onTick() {
        if (!started || !asking.compareAndSet(false, true)) return
        try {
            statusBg.execute {
                val st = try {
                    Native.setPhone(phoneState())
                    Status.parse(Native.status())
                } finally {
                    asking.set(false)
                }
                main.post { onStatus(st) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            asking.set(false) // stopping
        }
    }

    private fun onStatus(st: Status) {
        if (!started) return
        keepScreenOn(app.prefs.stayAwake)
        st.signedOut?.let {
            signedOutByServer(it)
            return
        }
        usb.poll()
        // no data from the dongle: open its connection afresh, at growing intervals
        if (reconnect.due(st.radio.attached, st.radio.connected, System.currentTimeMillis())) {
            usb.reconnect("no data for a while (try ${reconnect.tries})" +
                if (st.radio.error.isNotEmpty()) ": ${st.radio.error}" else "")
        }
        if (st.listening) gps.start() else gps.stop()
        alerts(st)
        val text = when {
            st.listening -> (if (st.radio.mode == "voice") "Listening to road channels" else "Listening for EOT") +
                (if (st.online) "" else " (offline, events kept)")
            st.radio.attached && reconnect.tries > 0 -> "No data from the dongle; reconnecting"
            st.radio.attached -> "Dongle connected, starting"
            else -> usb.status
        }
        if (text != lastNote) {
            lastNote = text
            if (usb.connected) startForegroundNow() // add the USB type once the cable is open
            getSystemService(NotificationManager::class.java).notify(NOTE_ID, note(text))
        }
    }

    /**
     * Hold or let go of the screen. The lock is taken with a timeout and renewed on each tick, so
     * it lapses on its own if the service is gone. The dim-screen lock is deprecated in favour of
     * an activity's FLAG_KEEP_SCREEN_ON, which only works while the app shows; this has to work
     * from the background too (the app sets the flag as well while it shows).
     */
    @Suppress("DEPRECATION")
    private fun keepScreenOn(on: Boolean) {
        if (!on) {
            awake?.takeIf { it.isHeld }?.release()
            return
        }
        val lock = awake ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "RailSpotting:listening")
            .also { it.setReferenceCounted(false); awake = it }
        lock.acquire(AWAKE_MS)
    }

    /** EOT alerts: what this phone decoded, and other receivers' within the chosen radius. */
    private fun alerts(st: Status) {
        val mine = ownEot.fresh(st.heard) // always, so turning alerts on later does not replay
        if (app.prefs.alertOwnEot) mine.forEach { TrainAlerts.own(this, it) }
        val now = System.currentTimeMillis()
        if (!app.prefs.alertNearbyEot || now - lastNearby < NEARBY_EVERY_MS) return
        lastNearby = now
        val km = app.prefs.nearbyMiles * NearbyEot.KM_PER_MILE
        bg.execute {
            val at = gps.whereabouts() ?: return@execute
            val r = JSONObject(Native.nearbyEot(at.latitude, at.longitude, km))
            if (!r.optBoolean("ok")) {
                Log.i(TAG, "nearby EOT check failed: ${r.optString("error")}")
                return@execute
            }
            val list = r.optJSONArray("alerts") ?: return@execute
            val found = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(NearbyEot::parse) }
            main.post { found.forEach { TrainAlerts.nearby(this, it) } }
        }
    }

    private fun phoneState(): String {
        val bat = ContextCompat.registerReceiver(this, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        val o = JSONObject().put("share", app.prefs.share).put("foreground", app.foreground)
        if (bat != null) {
            val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) o.put("battery_pct", (level * 100 / scale).coerceIn(0, 100))
            val plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            o.put("charging", plugged != 0)
        }
        return o.toString()
    }

    /** Another phone took this account's receiver over (`403 revoked`): stop and forget everything. */
    private fun signedOutByServer(reason: String) {
        Log.w(TAG, "signed out by the headend: $reason")
        started = false
        main.removeCallbacks(tick)
        gps.stop()
        usb.stop()
        app.signOut(reason)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, RailSpottingApp.CHANNEL_ACCOUNT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Signed out")
            .setContentText("This receiver is now on another phone. Sign in again to bring it back.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(SIGNED_OUT_ID, n)
        stopSelf()
    }

    override fun onDestroy() {
        keepScreenOn(false)
        main.removeCallbacks(tick)
        bg.shutdownNow()
        statusBg.shutdownNow()
        gps.stop()
        usb.stop()
        running = false
        if (started) {
            started = false
            // stopping waits for a last upload; never on the main thread
            Thread({ Native.stop() }, "receiver-stop").start()
        }
        super.onDestroy()
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, RailSpottingApp.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("RailSpotting receiver")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop listening", stop)
            .build()
    }

    companion object {
        private const val TAG = "RailSpotting"
        private const val NOTE_ID = 1
        private const val SIGNED_OUT_ID = 2
        private const val TICK_MS = 2000L
        /** The screen lock lapses this long after the last tick. */
        private const val AWAKE_MS = 60_000L
        private const val NEARBY_EVERY_MS = 60_000L
        private const val ACTION_STOP = "com.railspotting.android.STOP"
        private const val ACTION_DONGLE = "com.railspotting.android.DONGLE"

        /** The service is running (this process). */
        @Volatile var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java))
        }

        /** A dongle was plugged in (the activity got the USB intent). */
        fun dongleAttached(context: Context) {
            if (running) context.startService(Intent(context, ListenService::class.java).setAction(ACTION_DONGLE))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }
    }
}

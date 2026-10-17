package com.railspotting.android.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import com.railspotting.android.core.Native
import java.util.concurrent.TimeUnit

/**
 * Fixes from the fused location provider, on only while listening. The core decides which fixes
 * go to the headend (after 100 m moved, at least every 120 s, from /config), so this asks for
 * fixes often enough to notice a 100 m move at road speed, and lets the provider batch them for
 * up to 30 s to spare the battery.
 */
class LocationFeed(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    var running = false
        private set

    /** The newest fix seen while listening. */
    @Volatile var last: Location? = null
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.locations.forEach { send(it) }
            r.lastLocation?.let { last = it }
        }
    }

    fun permitted() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running || !permitted()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMaxUpdateDelayMillis(BATCH_MS)
            .build()
        try {
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            running = true
        } catch (_: SecurityException) {
            running = false
        }
    }

    fun stop() {
        if (!running) return
        client.removeLocationUpdates(callback)
        running = false
    }

    /**
     * Where the phone is, for nearby alerts: the newest fix while listening, else the provider's
     * last known position (no new GPS work). Stays on the phone. Blocks: off the main thread.
     */
    // permitted() checks the permission; a revoke in between throws, which is caught
    @SuppressLint("MissingPermission")
    fun whereabouts(): Location? {
        last?.let { if (System.currentTimeMillis() - it.time < 10 * 60_000) return it }
        if (!permitted()) return null
        return try {
            Tasks.await(client.lastLocation, 10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * A fresh, precise fix for an owner's refresh (up to `waitMs`), else the newest known one.
     * Blocks: off the main thread.
     */
    @SuppressLint("MissingPermission") // checked by permitted(); a revoke in between is caught
    fun freshFix(waitMs: Long = 6_000): Location? {
        if (!permitted()) return null
        return try {
            val req = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(15_000)
                .setDurationMillis(waitMs)
                .build()
            Tasks.await(client.getCurrentLocation(req, null), waitMs + 1_000, TimeUnit.MILLISECONDS) ?: whereabouts()
        } catch (_: Exception) {
            whereabouts()
        }
    }

    private fun send(l: Location) {
        val fix = Fixes.json(
            l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, l.time,
            if (l.hasSpeed()) l.speed else null, if (l.hasBearing()) l.bearing else null,
        ) ?: return
        Native.addFix(fix)
    }

    private companion object {
        const val INTERVAL_MS = 10_000L
        const val MIN_INTERVAL_MS = 5_000L
        const val BATCH_MS = 30_000L
    }
}

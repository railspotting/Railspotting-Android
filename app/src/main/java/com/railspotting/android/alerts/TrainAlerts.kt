package com.railspotting.android.alerts

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationManagerCompat
import com.railspotting.android.car.CarService
import androidx.core.app.NotificationCompat
import com.railspotting.android.R
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.core.Heard
import com.railspotting.android.ui.MainActivity
import org.json.JSONObject

/**
 * Which end-of-train signals this phone heard are worth an alert. An EOT repeats about once a
 * minute while the train passes, so each unit alerts once per [UNIT_GAP_S], and signals that did
 * not decode once per [SIGNAL_GAP_S].
 */
class OwnEot {
    private val seen = HashSet<String>()
    private val last = HashMap<String, Double>()

    /** The heard events to alert on now (each event at most once). */
    fun fresh(heard: List<Heard>): List<Heard> = heard
        .filter { it.telemetry && !it.open && !it.burst && it.eventId !in seen }
        .sortedBy { it.startTs }
        .filter { h ->
            seen += h.eventId
            val key = h.unitId?.let { "unit $it" } ?: "signal"
            val gap = if (h.unitId != null) UNIT_GAP_S else SIGNAL_GAP_S
            val due = last[key]?.let { h.startTs - it >= gap } ?: true
            if (due) last[key] = h.startTs
            due
        }

    companion object {
        const val UNIT_GAP_S = 15 * 60.0
        const val SIGNAL_GAP_S = 5 * 60.0
    }
}

/** An EOT alert from another receiver near the phone (from the core's nearbyEot). */
data class NearbyEot(val id: Long, val title: String, val message: String, val crossing: String, val receiver: String,
                     val miles: Double) {
    companion object {
        const val KM_PER_MILE = 1.609344

        fun parse(o: JSONObject): NearbyEot = NearbyEot(
            id = o.optLong("id"),
            title = o.optString("title"),
            message = o.optString("message"),
            crossing = if (o.isNull("crossing")) "" else o.optString("crossing"),
            receiver = if (o.isNull("receiver")) "" else o.optString("receiver"),
            miles = o.optDouble("distance_km", 0.0) / KM_PER_MILE,
        )
    }

    /** "EOT 7 mi away" */
    val headline: String get() = "EOT %d mi away".format(Math.round(miles).coerceAtLeast(1))

    /** "Near Elm St, heard by Maple Ave Siding" */
    val where: String get() = listOfNotNull(
        crossing.takeIf { it.isNotEmpty() }?.let { "Near $it" },
        receiver.takeIf { it.isNotEmpty() }?.let { "heard by $it" },
    ).joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** Posts train alerts; tapping one opens the Activity tab. */
object TrainAlerts {
    private var nextId = 100

    private fun open(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 3, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun post(ctx: Context, title: String, text: String, detail: String) {
        val n = NotificationCompat.Builder(ctx, RailSpottingApp.CHANNEL_TRAINS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(ctx))
            .setAutoCancel(true)
            // in the car (Android Auto): a heads-up alert that opens the Nearby screen
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setContentIntent(CarPendingIntent.getCarApp(ctx, 4,
                        Intent(Intent.ACTION_VIEW).setComponent(ComponentName(ctx, CarService::class.java)), 0))
                    .build(),
            )
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(nextId++, n)
    }

    fun own(ctx: Context, h: Heard) {
        val text = h.decoded.ifEmpty { "End-of-train signal on %.4f MHz".format(h.freq) }
        post(ctx, "EOT heard by this phone", text, text + "\nStrength " + h.peakRssi)
    }

    fun nearby(ctx: Context, a: NearbyEot) {
        post(ctx, a.headline, a.where.ifEmpty { a.title }, listOf(a.where, a.message).filter { it.isNotEmpty() }.joinToString("\n"))
    }
}

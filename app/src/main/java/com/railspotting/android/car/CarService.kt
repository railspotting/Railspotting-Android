package com.railspotting.android.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.railspotting.android.R
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.alerts.NearbyEot
import com.railspotting.android.core.Native
import com.railspotting.android.location.LocationFeed
import com.railspotting.android.service.ListenService
import com.railspotting.android.ui.donglePlugged
import java.util.concurrent.Executors

/**
 * Android Auto: the Nearby crossings screen. Auto allows only its own templates in the car, so
 * this is the points-of-interest list with a map the car draws: the crossings near the phone
 * from the public live map's feed, train activity first, and a Listening button.
 */
class CarService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        // test builds take any host (the desktop head unit); release builds only Google's Android Auto
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(R.array.car_hosts)
            .build()
    }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = NearbyScreen(carContext)
    }
}

/** The crossings near the phone, refreshed every minute while the screen shows. */
class NearbyScreen(car: CarContext) : Screen(car) {
    private val app = RailSpottingApp.of(car)
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var crossings: List<NearCrossing>? = null
    @Volatile private var problem: String? = null
    /** Where the phone is: the map centers here, even with no crossings to show. */
    @Volatile private var here: CarLocation? = null
    private val refresh = object : Runnable {
        override fun run() {
            load()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                main.post(refresh)
            }

            override fun onStop(owner: LifecycleOwner) {
                main.removeCallbacks(refresh)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                bg.shutdownNow()
            }
        })
    }

    private fun load() {
        bg.execute {
            val at = LocationFeed(carContext).whereabouts()
            if (at != null) here = CarLocation.create(at.latitude, at.longitude)
            if (at == null) {
                problem = "Waiting for the phone's location"
            } else {
                val km = app.prefs.nearbyMiles * NearbyEot.KM_PER_MILE
                val list = NearCrossing.list(Native.nearbyCrossings(at.latitude, at.longitude, km))
                crossings = list
                problem = if (list.isEmpty()) "No crossings within ${app.prefs.nearbyMiles} miles on the public map" else null
            }
            main.post { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val b = PlaceListMapTemplate.Builder()
            .setTitle("Nearby crossings")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(ActionStrip.Builder().addAction(listenAction()).build())
            .setCurrentLocationEnabled(LocationFeed(carContext).permitted())
        here?.let { b.setAnchor(Place.Builder(it).build()) }
        val list = crossings
        if (list == null) return b.setLoading(true).build()
        val items = ItemList.Builder()
        problem?.let { items.setNoItemsMessage(it) }
        list.take(MAX_ROWS).forEach { c -> items.addItem(row(c)) }
        return b.setItemList(items.build()).build()
    }

    private fun row(c: NearCrossing): Row {
        // "Moving train · 0.8 mi", the distance in the car's own units
        val text = SpannableString("${c.label} · ")
        val withDistance = SpannableString(text.toString() + " ")
        withDistance.setSpan(DistanceSpan.create(Distance.create(c.miles, Distance.UNIT_MILES)),
            text.length, text.length + 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        val marker = PlaceMarker.Builder().setColor(if (c.busy) CarColor.YELLOW else CarColor.DEFAULT).build()
        return Row.Builder()
            .setTitle(c.title)
            .addText(withDistance)
            .setMetadata(Metadata.Builder().setPlace(Place.Builder(CarLocation.create(c.lat, c.lon)).setMarker(marker).build()).build())
            .build()
    }

    /** Listening on or off, as the phone's switch: only with a dongle plugged in (or to turn it off). */
    private fun listenAction(): Action {
        val on = ListenService.running
        return Action.Builder()
            .setTitle(if (on) "Stop listening" else "Listen")
            .setOnClickListener {
                when {
                    on -> ListenService.stop(carContext)
                    !donglePlugged(carContext) -> CarToast.makeText(carContext, "Plug in an RTL-SDR dongle", CarToast.LENGTH_LONG).show()
                    !LocationFeed(carContext).permitted() -> CarToast.makeText(carContext, "Allow location on the phone first", CarToast.LENGTH_LONG).show()
                    else -> try {
                        ListenService.start(carContext)
                    } catch (_: Exception) {
                        CarToast.makeText(carContext, "Start listening on the phone", CarToast.LENGTH_LONG).show()
                    }
                }
                main.postDelayed({ invalidate() }, 1000)
            }
            .build()
    }

    private companion object {
        const val REFRESH_MS = 60_000L
        /** Auto shows about six rows while driving. */
        const val MAX_ROWS = 6
    }
}

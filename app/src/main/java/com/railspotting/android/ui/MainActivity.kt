package com.railspotting.android.ui

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.location.LocationFeed
import com.railspotting.android.service.ListenService
import com.railspotting.android.update.Updater

class MainActivity : ComponentActivity() {
    private val app get() = RailSpottingApp.of(this)

    /** A tab asked for by a notification, until the screen opens it. */
    private var openTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onUsb(intent)
        openTab = intent?.getStringExtra(EXTRA_TAB)
        setContent { RsTheme { AppScreen(this, app, openTab) { openTab = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        onUsb(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { openTab = it }
    }

    override fun onStart() {
        super.onStart()
        app.foreground = true
        Updater.maybeCheck(this)
    }

    override fun onStop() {
        app.foreground = false
        super.onStop()
    }

    /**
     * The dongle was plugged in and Android opened the app for it. When signed in and location
     * is allowed, that starts listening: plugging the dongle in is the owner's way of asking.
     */
    private fun onUsb(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED || app.prefs.linked == null) return
        when {
            ListenService.running -> ListenService.dongleAttached(this)
            LocationFeed(this).permitted() -> ListenService.start(this)
        }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_ACTIVITY = "activity"
    }
}

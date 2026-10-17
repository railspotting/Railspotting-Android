package com.railspotting.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import com.railspotting.android.RailSpottingApp

/** Android's answer to an update install (Updater.install). */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = RailSpottingApp.of(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // the first self-update: Android asks the owner
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { Updater.askToConfirm(context, it) }
                app.prefs.updateStatus = "Confirm the install in Android's prompt (or its notification)"
            }
            PackageInstaller.STATUS_SUCCESS -> app.prefs.updateStatus = "Updated"
            else -> {
                Log.w("RailSpotting", "update install: status $status $message")
                app.prefs.updateStatus = "The update was not installed" + if (message.isNotEmpty()) ": $message" else ""
            }
        }
    }
}

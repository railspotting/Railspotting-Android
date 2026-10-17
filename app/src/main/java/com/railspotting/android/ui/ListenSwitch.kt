package com.railspotting.android.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import com.railspotting.android.service.ListenService
import com.railspotting.android.usb.RtlIds

/** The header's right-hand control (the listening switch); null where there is none. */
val LocalHeaderAction = compositionLocalOf<(@Composable () -> Unit)?> { null }

/** The word under the switch: what the dongle is actually doing while listening is on. */
fun switchLabel(listening: Boolean, dongle: Boolean, streaming: Boolean, attached: Boolean): String = when {
    !listening -> if (dongle) "Off" else "No dongle"
    streaming -> "Listening"
    !dongle -> "No dongle"
    attached -> "No data"
    else -> "Connecting"
}

/** A dongle is plugged in (permission or not). */
fun donglePlugged(ctx: Context): Boolean =
    ctx.getSystemService(UsbManager::class.java)?.deviceList?.values?.any { RtlIds.isDongle(it.vendorId, it.productId) } == true

/**
 * Whether the listening switch can be used: with a dongle plugged in, or while listening, so it
 * can always be turned off.
 */
fun switchEnabled(listening: Boolean, dongle: Boolean) = listening || dongle

private enum class Ask { None, Location, Notifications }

/**
 * Listening on or off, at the top right. Turning it on asks for precise location (and, on
 * Android 13 and later, notifications) first, each with a word on why.
 */
@Composable
fun ListenSwitch(listening: Boolean, dongle: Boolean, streaming: Boolean = true, attached: Boolean = true) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf(Ask.None) }

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun needNotifications() = Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)
    fun tell(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    fun start() = ListenService.start(ctx)

    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { start() }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (needNotifications()) ask = Ask.Notifications else start()
        } else {
            tell("Listening needs precise location. Allow it in the app's settings to listen.")
        }
    }

    val enabled = switchEnabled(listening, dongle)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // a tap on the greyed-out switch says why
        modifier = if (enabled) Modifier else Modifier.clickable { tell("Plug in an RTL-SDR dongle to listen.") },
    ) {
        Switch(
            checked = listening,
            enabled = enabled,
            onCheckedChange = { on ->
                when {
                    !on -> ListenService.stop(ctx)
                    !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> ask = Ask.Location
                    needNotifications() -> ask = Ask.Notifications
                    else -> start()
                }
            },
            modifier = Modifier.semantics { contentDescription = "Listening" },
        )
        // on but no samples: say so, rather than "Listening"
        val stalled = listening && !streaming
        Text(
            switchLabel(listening, dongle, streaming, attached),
            style = MaterialTheme.typography.labelSmall,
            color = when {
                stalled -> MaterialTheme.colorScheme.error
                listening -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }

    when (ask) {
        Ask.Location -> Explain(
            title = "Location while listening",
            text = "RailSpotting sends this phone's position while it listens, so it knows which crossings the " +
                "dongle can hear. Your position is never public, and location is used only while listening.",
            onOk = {
                ask = Ask.None
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            onCancel = { ask = Ask.None },
        )
        Ask.Notifications -> Explain(
            title = "A notification while listening",
            text = "Android shows a notification while the phone listens, with a button to stop.",
            onOk = {
                ask = Ask.None
                if (Build.VERSION.SDK_INT >= 33) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else start()
            },
            onCancel = {
                ask = Ask.None
                start()
            },
        )
        Ask.None -> {}
    }
}

@Composable
private fun Explain(title: String, text: String, onOk: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onOk) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Not now") } },
    )
}

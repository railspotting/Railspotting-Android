package com.railspotting.android.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.railspotting.android.BuildConfig
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.Urls
import com.railspotting.android.store.Linked
import com.railspotting.android.store.Prefs
import com.railspotting.android.trains.ReportTrainDialog
import com.railspotting.android.update.Updater

/** Opens the menu; null when there is none (signed out). The header's logo uses it. */
val LocalOpenMenu = compositionLocalOf<(() -> Unit)?> { null }

/** The menu behind the logo: the receiver, alert settings, sign out. */
@Composable
fun MenuSheet(activity: Activity, app: RailSpottingApp, linked: Linked, onClose: () -> Unit, onSignedOut: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    val reports by app.trainReports
    ModalDrawerSheet {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // the logo sits where it does on the page, so tapping it again closes the menu
            Header(onLogo = onClose, logoLabel = "Close the menu", action = null)
            HorizontalDivider()
            ReceiverInfo(activity, linked)
            HorizontalDivider()
            if (reports) {
                OutlinedButton(onClick = { reporting = true }, modifier = Modifier.fillMaxWidth()) { Text("Report a train") }
                HorizontalDivider()
            }
            ListeningSettings(app)
            HorizontalDivider()
            AlertSettings(app)
            HorizontalDivider()
            UpdateSettings(app)
            HorizontalDivider()
            TextButton(onClick = { about = true }) { Text("About") }
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        }
    }
    if (about) AboutScreen(activity) { about = false }
    if (reporting) ReportTrainDialog(app) { reporting = false }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Sign out?") },
            text = { Text("This stops listening and forgets this phone's key. Your receiver stays on your account: " +
                "signing in again, on this or any phone, takes it back.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    app.signOut()
                    onSignedOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MenuTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/** Who is signed in, the receiver's name, and provisional or live. */
@Composable
private fun ReceiverInfo(activity: Activity, linked: Linked) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MenuTitle("Receiver")
        Text(linked.receiverName.ifEmpty { "Your phone" }, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold)
        Text("Signed in as ${linked.email.ifEmpty { linked.accountName }}", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = if (linked.provisional) MaterialTheme.colorScheme.surfaceVariant else Amber,
                shape = RoundedCornerShape(50),
            ) {
                Text(if (linked.provisional) "Provisional" else "Live", Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                    color = if (linked.provisional) MaterialTheme.colorScheme.onSurfaceVariant else Tile, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text("as of sign-in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (linked.provisional) {
            Text("A provisional receiver is private. When you are ready, apply for go-live under My receivers; " +
                "an admin approves it.", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { activity.openWeb(Urls.ACCOUNT) }) { Text("Open My receivers") }
    }
}

/** Updates from the app's GitHub releases (update/Updater.kt). */
@Composable
private fun UpdateSettings(app: RailSpottingApp) {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(app.prefs.autoUpdate) }
    var status by remember { mutableStateOf(app.prefs.updateStatus) }
    var checking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MenuTitle("Updates")
        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Install new versions automatically", Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it; app.prefs.autoUpdate = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = !checking, onClick = {
                checking = true
                Updater.checkNow(ctx) {
                    ContextCompat.getMainExecutor(ctx).execute {
                        checking = false
                        status = app.prefs.updateStatus
                    }
                }
            }) { Text(if (checking) "Checking…" else "Check now") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
        }
        Text("New versions come from the app's releases on GitHub. They install when the phone isn't " +
            "listening; Android asks you to confirm the first one.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** How the phone behaves while listening. */
@Composable
private fun ListeningSettings(app: RailSpottingApp) {
    var awake by remember { mutableStateOf(app.prefs.stayAwake) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MenuTitle("Listening")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep the screen on while RailSpotting is open", Modifier.weight(1f))
            Switch(checked = awake, onCheckedChange = { awake = it; app.prefs.stayAwake = it })
        }
        Text("While listening with RailSpotting on screen, the phone doesn't lock. On phones with USB " +
            "protection (Advanced Protection) locking cuts the dongle off; Android lets an app keep the " +
            "screen on only while it is showing. Uses more battery: best on a charger or in a car mount.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** End-of-train alerts: from this phone, and from receivers nearby. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertSettings(app: RailSpottingApp) {
    var own by remember { mutableStateOf(app.prefs.alertOwnEot) }
    var nearby by remember { mutableStateOf(app.prefs.alertNearbyEot) }
    var miles by remember { mutableIntStateOf(app.prefs.nearbyMiles) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MenuTitle("Alerts")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("End-of-train heard by this phone", Modifier.weight(1f))
            Switch(checked = own, onCheckedChange = { own = it; app.prefs.alertOwnEot = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("End-of-train heard by other receivers within $miles miles", Modifier.weight(1f))
            Switch(checked = nearby, onCheckedChange = { nearby = it; app.prefs.alertNearbyEot = it })
        }
        if (nearby) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Prefs.RADII.forEachIndexed { i, r ->
                    SegmentedButton(selected = miles == r, onClick = { miles = r; app.prefs.nearbyMiles = r },
                        shape = SegmentedButtonDefaults.itemShape(i, Prefs.RADII.size)) { Text("$r mi") }
                }
            }
        }
        Text("Alerts come while listening. Nearby alerts use the public live map, checked every minute; your " +
            "position is only compared on this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

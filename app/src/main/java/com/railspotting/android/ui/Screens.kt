package com.railspotting.android.ui

import android.app.Activity
import android.content.Intent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.railspotting.android.R
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.core.Native
import com.railspotting.android.core.Status
import com.railspotting.android.service.ListenService
import com.railspotting.android.signin.Hello
import com.railspotting.android.signin.Outcome
import com.railspotting.android.signin.SignIn
import com.railspotting.android.store.Linked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab { Receiver, Map, Activity }

@Composable
fun AppScreen(activity: Activity, app: RailSpottingApp, openTab: String? = null, onTabOpened: () -> Unit = {}) {
    var linked by remember { mutableStateOf(app.prefs.linked) }
    var status by remember { mutableStateOf(Status()) }
    var listening by remember { mutableStateOf(ListenService.running) }
    var dongle by remember { mutableStateOf(false) }
    var stayAwake by remember { mutableStateOf(app.prefs.stayAwake) }
    // the app opens on the live map; before sign-in, on the sign-in screen
    var tab by rememberSaveable { mutableStateOf(if (app.prefs.linked != null) Tab.Map else Tab.Receiver) }
    LaunchedEffect(openTab) {
        if (openTab == MainActivity.TAB_ACTIVITY) tab = Tab.Activity
        if (openTab != null) onTabOpened()
    }
    val ctx = LocalContext.current
    // made on first use and kept, so the map keeps its place between tabs
    val map = remember { lazy { newMapView(ctx).also { startMap(it, ctx) } } }
    DisposableEffect(Unit) {
        onDispose { if (map.isInitialized()) map.value.destroy() }
    }
    // the Saw a train button: ask the headend once signed in, whether listening or not
    LaunchedEffect(linked?.deviceId) {
        if (linked != null) withContext(Dispatchers.IO) { app.refreshTrainReports() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            linked = app.prefs.linked
            listening = ListenService.running
            dongle = donglePlugged(ctx)
            stayAwake = app.prefs.stayAwake
            // off the main thread: a slow answer from the core must never freeze the screen
            status = if (listening) withContext(Dispatchers.Default) { Status.parse(Native.status()) } else Status()
            delay(1000)
        }
    }
    // while RailSpotting shows. Android ignores an app's own screen lock from the background
    // (seen on 17 Oct: held for an hour, the phone timed out and locked anyway), so this is all
    // an app can do
    val keepOn = listening && stayAwake
    DisposableEffect(keepOn) {
        if (keepOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val l = linked
    val openMenu: (() -> Unit)? = if (l != null) ({ scope.launch { drawer.open() } }) else null
    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        // on the map, sideways swipes pan it; the logo still opens the menu elsewhere
        gesturesEnabled = l != null && (drawer.isOpen || tab != Tab.Map),
        drawerContent = {
            if (l != null) {
                MenuSheet(activity, app, l, onClose = { scope.launch { drawer.close() } }) {
                    scope.launch { drawer.close() }
                    linked = null
                }
            }
        },
    ) {
        CompositionLocalProvider(
            LocalOpenMenu provides openMenu,
            // the listening switch, once signed in
            LocalHeaderAction provides if (l != null) ({ ListenSwitch(listening, dongle, streaming = status.radio.connected, attached = status.radio.attached) }) else null,
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == Tab.Receiver, onClick = { tab = Tab.Receiver },
                            icon = { Icon(painterResource(R.drawable.ic_notify), contentDescription = null) },
                            label = { Text("Receiver") },
                        )
                        NavigationBarItem(
                            selected = tab == Tab.Map, onClick = { tab = Tab.Map },
                            icon = { Icon(painterResource(R.drawable.ic_tab_map), contentDescription = null) },
                            label = { Text("Live map") },
                        )
                        NavigationBarItem(
                            selected = tab == Tab.Activity, onClick = { tab = Tab.Activity },
                            icon = { Icon(painterResource(R.drawable.ic_tab_activity), contentDescription = null) },
                            label = { Text("Activity") },
                        )
                    }
                },
            ) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                    when (tab) {
                        Tab.Map -> Column(Modifier.fillMaxSize()) {
                            // the same banner as the other tabs; its logo opens the menu
                            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { Header() }
                            LiveMap(map.value, Modifier.fillMaxWidth().weight(1f))
                        }
                        Tab.Activity -> ActivityScreen(app, status, listening)
                        Tab.Receiver -> {
                            val l = linked
                            if (l == null) {
                                SignInScreen(activity, app) { linked = it }
                            } else {
                                StatusScreen(activity, app, status, listening)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The logo and name. Where there is a menu (signed in), the logo opens it; in the menu, the
 * same logo closes it again.
 */
@Composable
internal fun Header(
    onLogo: (() -> Unit)? = LocalOpenMenu.current,
    logoLabel: String = "Open the menu",
    action: (@Composable () -> Unit)? = LocalHeaderAction.current,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            if (onLogo != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = logoLabel) { onLogo() }
            else Modifier,
        ) {
            Surface(color = Tile, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(44.dp)) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text("RailSpotting", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Mobile receiver", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Spacer(Modifier.weight(1f))
            action()
        }
    }
}

@Composable
internal fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Header()
        content()
    }
}

@Composable
internal fun Section(title: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

internal fun Activity.openWeb(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}

// sign in ---------------------------------------------------------------------------------------

@Composable
fun SignInScreen(activity: Activity, app: RailSpottingApp, onLinked: (Linked) -> Unit) {
    val scope = rememberCoroutineScope()
    val signIn = remember { SignIn(app) }
    var hello by remember { mutableStateOf<Hello?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val reason = remember { app.prefs.signedOutReason }
    LaunchedEffect(Unit) {
        signIn.hello().onSuccess { hello = it }.onFailure { error = it.message }
    }
    Page {
        if (reason != null) {
            Section {
                Text(
                    if (reason == "revoked") "You were signed out because this receiver is now on another phone. " +
                        "Sign in again to bring it back to this one."
                    else "RailSpotting signed this phone out ($reason). Sign in again to continue.",
                )
            }
        }
        Section("Turn this phone into a receiver") {
            Text("Plug an RTL-SDR dongle into this phone with a USB OTG adapter and it listens for trains while you " +
                "travel: end-of-train devices, decoded, or the road channels, with live listen. It sends what it hears, " +
                "and where the phone is, to RailSpotting.")
            Text("Your position and your receiver's name are never public. A new receiver stays private until you " +
                "apply for go-live and an admin approves it.")
        }
        Section("Sign in") {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(80) }, singleLine = true,
                label = { Text("Receiver name (optional)") },
                supportingText = { Text("Only you and the admins see it. Leave it empty for \"your first name's phone\".") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Signing in creates a RailSpotting account if you do not have one. By signing in you agree to the " +
                "Terms and the Privacy policy.", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { activity.openWeb(hello?.termsUrl ?: "https://railspotting.com/terms") }) { Text("Terms") }
                TextButton(onClick = { activity.openWeb(hello?.privacyUrl ?: "https://railspotting.com/privacy") }) { Text("Privacy") }
            }
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        when (val o = signIn.run(activity, hello, name.trim())) {
                            is Outcome.Done -> {
                                app.prefs.linked = o.linked
                                app.prefs.signedOutReason = null
                                onLinked(o.linked)
                            }
                            is Outcome.Failed -> error = o.message
                            Outcome.Cancelled -> {}
                        }
                        hello = null // its nonce is used up
                        signIn.hello().onSuccess { hello = it }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Sign in with Google")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// signed in -------------------------------------------------------------------------------------


@Composable
fun StatusScreen(activity: Activity, app: RailSpottingApp, st: Status, listening: Boolean) {
    var share by remember { mutableStateOf(app.prefs.share) }
    var showLog by remember { mutableStateOf(false) }

    Page {
        Section("Listening") {
            Fact("Running", if (listening) "Yes" else "No")
            Fact("Online", if (!listening) "No (not running)" else if (st.online) "Yes" else "Not yet")
            Fact("Listening", if (st.listening) "Yes" else "No")
            Fact("Dongle", when {
                st.radio.connected -> (when (st.radio.mode) { "voice" -> "Road channels"; "itc" -> "ITC (220 MHz)"; else -> "EOT" }) +
                    (if (st.radio.tuner.isNotEmpty()) ", ${st.radio.tuner}" else "")
                st.radio.attached -> "Starting"
                listening -> "Not connected"
                else -> "-"
            })
            Fact("Last fix", if (listening) Status.age(st.fixAgeS) else "-")
            if (listening) {
                Fact("Events sent", st.eventsUploaded.toString())
                if (st.outboxDepth > 0) Fact("Waiting to send", st.outboxDepth.toString())
            }
            Text("The switch at the top right turns listening on and off; it works once a dongle is plugged in. " +
                "Listening powers the dongle and uses GPS, so it uses battery. GPS is on only while the dongle is " +
                "connected. Turn it off when you do not need it, and keep the phone charging on long drives.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section("Sharing") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add to the public map", Modifier.weight(1f))
                Switch(checked = share, onCheckedChange = {
                    share = it
                    app.prefs.share = it
                })
            }
            Text(if (share) "Trains this phone hears can show on the public map, once the receiver is live. " +
                "Your position and name never do."
            else "What this phone hears stays private to you and the admins.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section {
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide the log" else "Show the log") }
            if (showLog) {
                HorizontalDivider()
                SelectionContainer {
                    Text(Native.log().ifEmpty { "Nothing yet." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}


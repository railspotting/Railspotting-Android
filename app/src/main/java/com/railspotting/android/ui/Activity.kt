package com.railspotting.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.core.Heard
import com.railspotting.android.core.Native
import com.railspotting.android.core.PlanInfo
import com.railspotting.android.core.Status
import com.railspotting.android.location.Fixes
import com.railspotting.android.location.LocationFeed
import com.railspotting.android.trains.ReportTrainDialog
import com.railspotting.android.store.Prefs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * What this phone hears: the dongle's mode (road channels or EOT), live listen, calls on the air
 * at the top, and everything heard since listening started, with EOT packets decoded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(app: RailSpottingApp, st: Status, listening: Boolean) {
    var mode by remember { mutableStateOf(app.prefs.mode) }
    var live by rememberSaveable { mutableStateOf(false) }
    val player = remember { LivePlayer() }
    // live listen plays only while this screen shows it, in voice mode
    DisposableEffect(live, mode) {
        if (live && mode == "voice") player.start() else player.stop()
        onDispose { player.stop() }
    }
    fun choose(m: String) {
        if (m == mode) return
        mode = m
        app.prefs.mode = m
        Native.reopen.execute { Native.setMode(m) }
    }

    var reporting by remember { mutableStateOf(false) }
    val reports = remember { Native.trainReports(app.stateDir.absolutePath) }
    if (reporting) ReportTrainDialog(app) { reporting = false }

    Page {
        if (reports) {
            Section("Saw a train?") {
                Text("Tell RailSpotting about it: locomotives, cars, a note or a video, on a train it already " +
                    "knows near you or a new one.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { reporting = true }) { Text("Report a train") }
            }
        }
        Section("Radio") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = mode == "voice", onClick = { choose("voice") },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Road channels") }
                SegmentedButton(selected = mode == "eot", onClick = { choose("eot") },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("End-of-train") }
            }
            Text(
                if (mode == "voice") "The dongle watches the road channels near you. Turn on live listen to hear calls."
                else "The dongle listens for end-of-train devices and decodes them: unit, brake pipe pressure, motion.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    !listening -> "Start listening on the Receiver tab to use the dongle."
                    st.radio.connected -> "Dongle streaming" + (if (st.radio.tuner.isNotEmpty()) " (${st.radio.tuner})" else "")
                    st.radio.attached -> "Dongle starting"
                    else -> "Plug in an RTL-SDR dongle."
                },
                fontWeight = FontWeight.Medium,
            )
            if (listening && st.radio.error.isNotEmpty()) {
                Text(st.radio.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            GainChoice(app)
            HorizontalDivider()
            ChannelList(st, listening)
        }

        if (mode == "voice") {
            Section("Live listen") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Play calls as they happen", Modifier.weight(1f))
                    Switch(checked = live, onCheckedChange = { live = it })
                }
                Text("The call on the air plays through the speaker or headphones. When it ends, the next call " +
                    "takes over. Between calls it is quiet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // calls on the air come to the front
        // interference across the band (many channels at once) is not traffic
        val onAir = st.heard.filter { it.open && !it.burst }
        if (onAir.isNotEmpty()) {
            Section("On the air") {
                onAir.forEach { HeardRow(it, playing = live && st.radio.playing?.eventId == it.eventId) }
            }
        }

        Section("Heard") {
            val done = st.heard.filter { !it.open && !it.burst }
            val noise = st.heard.count { it.burst && !it.open }
            if (noise > 0) {
                Text("$noise interference ${if (noise == 1) "burst" else "bursts"} hidden: many channels at once, " +
                    "often from the phone itself. Keep the antenna away from the phone.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (done.isEmpty()) {
                Text(if (listening) "Nothing yet. Signals show here as the dongle hears them."
                    else "Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            done.forEachIndexed { i, h ->
                if (i > 0) HorizontalDivider()
                HeardRow(h, playing = false)
            }
        }

        if (listening && st.radio.channels.isNotEmpty()) {
            Section("Watching") {
                st.radio.channels.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(c.receiving)
                        Spacer(Modifier.width(8.dp))
                        Text("%.4f".format(c.freq), fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text(c.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Dot(on: Boolean) {
    Surface(Modifier.size(10.dp).clip(CircleShape),
        color = if (on) Amber else MaterialTheme.colorScheme.surfaceVariant) {}
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)

internal fun timeOf(ts: Double): String =
    Instant.ofEpochMilli((ts * 1000).toLong()).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormat)

/** What a heard event says, in a line: the decode for EOT, how long for a call. */
internal fun describe(h: Heard, now: Double): String = when {
    h.telemetry && h.decoded.isNotEmpty() -> h.decoded
    h.telemetry && h.open -> "EOT signal, on the air"
    h.telemetry -> "EOT signal, not decoded"
    h.open -> "Voice call, on the air " + Status.clock(now - h.startTs)
    else -> "Voice call, " + Status.clock((h.endTs ?: h.startTs) - h.startTs)
}

@Composable
private fun HeardRow(h: Heard, playing: Boolean) {
    val now = System.currentTimeMillis() / 1000.0
    val bg = if (h.open) Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        else Modifier
    Column(bg.fillMaxWidth().padding(vertical = 6.dp, horizontal = if (h.open) 8.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (h.open) {
                Dot(true)
                Spacer(Modifier.width(8.dp))
            }
            Text("%.4f".format(h.freq), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(h.label.ifEmpty { if (h.telemetry) "EOT" else "Channel" }, Modifier.weight(1f))
            Text(timeOf(h.startTs), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(describe(h, now) + if (playing) "  (playing)" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (h.telemetry && h.decoded.isNotEmpty()) FontWeight.Medium else FontWeight.Normal,
            color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** The channel list in use, and a button to fetch the one for where the phone is now. */
@Composable
private fun ChannelList(st: Status, listening: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Channel list", fontWeight = FontWeight.Medium)
            Text(
                if (!listening) "Turn listening on to load the channel list" else st.plan.text,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(
            enabled = listening && !busy,
            onClick = {
                busy = true
                note = null
                scope.launch {
                    note = withContext(Dispatchers.IO) { refreshChannels(ctx) }
                    busy = false
                }
            },
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Refresh")
        }
    }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (listening && !st.plan.byLocation) {
        Text("RailSpotting does not pick channels by location yet: refresh fetches the list the headend has for this " +
            "receiver now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A fresh fix, then the core's refresh: what to tell the owner. Blocks. */
private fun refreshChannels(ctx: android.content.Context): String {
    val at = LocationFeed(ctx).freshFix()
    val r = JSONObject(Native.refreshPlan(at?.let { Fixes.of(it) } ?: "{}"))
    if (!r.optBoolean("ok")) return "Could not refresh: " + r.optString("message", r.optString("error"))
    val plan = PlanInfo.parse(r.optJSONObject("plan"))
    // the headend takes one refresh per 30 s
    if (!r.isNull("note") && r.optString("note").isNotEmpty()) return r.optString("note") + ": " + plan.text
    return (if (r.optBoolean("moved")) "Moved: " else "Up to date: ") + plan.text +
        if (at == null) " (no location yet)" else ""
}

/** "40.2" as "40.2 dB"; "auto" as "Automatic". */
fun gainLabel(g: String) = if (g == "auto") "Automatic" else "$g dB" + if (g == Prefs.DEFAULT_GAIN) " (default)" else ""

/** The dongle's gain: more for a small antenna, less near a strong transmitter. */
@Composable
private fun GainChoice(app: RailSpottingApp) {
    var gain by remember { mutableStateOf(app.prefs.gain) }
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Gain")
            Text("Raise it if bursts are heard but not decoded; lower it if everything is noisy.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            TextButton(onClick = { open = true }) { Text(gainLabel(gain)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Prefs.GAINS.forEach { g ->
                    DropdownMenuItem(text = { Text(gainLabel(g)) }, onClick = {
                        open = false
                        gain = g
                        app.prefs.gain = g
                        Native.reopen.execute { Native.setGain(g) }
                    })
                }
            }
        }
    }
}

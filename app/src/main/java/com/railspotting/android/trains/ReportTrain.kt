package com.railspotting.android.trains

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.Urls
import com.railspotting.android.core.Native
import com.railspotting.android.location.Fixes
import com.railspotting.android.location.LocationFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where the form is: finding the phone and the trains near it, filling in, sending, done. */
private sealed interface Step {
    data object NeedLocation : Step
    data object Looking : Step
    /**
     * `place`: the headend takes a chosen crossing and track (v1.14); `lines` are those within
     * half a mile, which may be none.
     */
    data class Form(val fix: String, val trains: List<NearTrain>, val lookError: String?,
                    val place: Boolean = false, val lines: List<PlaceLine> = listOf()) : Step
    data class Done(val url: String?) : Step
}

/**
 * Report a train by hand (proposed v1.13): the details the website's "Add detail" form takes,
 * on a train RailSpotting already knows near the phone, or on none. Signed in only: the report
 * is signed with this phone's key, for its owner's account.
 */
@Composable
fun ReportTrainDialog(app: RailSpottingApp, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val linked = app.prefs.linked
    var step by remember { mutableStateOf<Step>(if (LocationFeed(ctx).permitted()) Step.Looking else Step.NeedLocation) }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (LocationFeed(ctx).permitted()) step = Step.Looking
    }

    LaunchedEffect(step) {
        if (step != Step.Looking || linked == null) return@LaunchedEffect
        step = withContext(Dispatchers.IO) {
            val at = LocationFeed(ctx).freshFix() ?: LocationFeed(ctx).whereabouts()
            val fix = at?.let { Fixes.of(it) }
            if (at == null || fix == null) return@withContext Step.Form("{}", listOf(), "The phone's position is not known yet")
            val seed = app.seed()
            val answer = try {
                Native.nearbyTrains(Urls.SERVER, seed, linked.deviceId, at.latitude, at.longitude)
            } finally {
                seed.fill(0)
            }
            val (trains, err) = NearTrain.list(answer)
            // the lines and crossings within half a mile, when the headend takes a chosen one
            val place = Native.trainReportPlace(app.stateDir.absolutePath)
            val lines = if (place) PlaceLine.list(Native.reportPlaces(at.latitude, at.longitude)) else listOf()
            Step.Form(fix, trains.orEmpty(), err, place, lines)
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Report a train", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text(if (step is Step.Done) "Close" else "Cancel") }
                }
                when (val s = step) {
                    Step.NeedLocation -> {
                        Text("A report says where the train was: it needs this phone's position. Your position is " +
                            "used for the report and the trains near you, and is not shown to anyone.")
                        Button(onClick = {
                            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }) { Text("Allow location") }
                    }
                    Step.Looking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text("Looking for trains near you…")
                    }
                    is Step.Form -> ReportForm(s) { input ->
                        val seed = app.seed()
                        val answer = try {
                            withContext(Dispatchers.IO) {
                                Native.reportTrain(Urls.SERVER, seed, linked?.deviceId.orEmpty(), input.toString(), s.fix)
                            }
                        } finally {
                            seed.fill(0)
                        }
                        val o = JSONObject(answer)
                        if (o.optBoolean("ok")) {
                            step = Step.Done(o.optString("url").ifBlank { null })
                            null
                        } else {
                            o.optString("message", "Not sent")
                        }
                    }
                    is Step.Done -> {
                        Text("Thanks: your report is on RailSpotting.", style = MaterialTheme.typography.titleMedium)
                        s.url?.let { url ->
                            TextButton(onClick = {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                            }) { Text("Open the train") }
                        }
                        Button(onClick = onClose) { Text("Done") }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (linked == null) onClose() } // signed out meanwhile
}

/** The fields, as on the website's train page. `send` answers null when sent, else what's wrong. */
@Composable
private fun ReportForm(s: Step.Form, send: suspend (JSONObject) -> String?) {
    val scope = rememberCoroutineScope()
    // -1: no train (not listed, or no ID); else an index into s.trains
    var pick by remember { mutableIntStateOf(if (s.trains.isEmpty()) -1 else 0) }
    var locomotives by remember { mutableStateOf("") }
    var cars by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // where: the nearest line and crossing to start with; the track only when the owner knows it
    var line by remember { mutableIntStateOf(0) }
    var crossing by remember { mutableIntStateOf(0) }
    var track by remember { mutableStateOf<Int?>(null) }
    val now = System.currentTimeMillis() / 1000.0

    Text("Train", style = MaterialTheme.typography.titleMedium)
    when {
        s.lookError != null -> Text(s.lookError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        s.trains.isEmpty() -> Text("No trains RailSpotting knows of near you right now.", style = MaterialTheme.typography.bodySmall)
    }
    s.trains.forEachIndexed { i, t ->
        Row(Modifier.fillMaxWidth().clickable { pick = i }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = pick == i, onClick = { pick = i })
            Column {
                Text(t.title, fontWeight = FontWeight.Medium)
                Text(t.detail(now), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Row(Modifier.fillMaxWidth().clickable { pick = -1 }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = pick == -1, onClick = { pick = -1 })
        Text(if (s.trains.isEmpty()) "A train RailSpotting doesn't know" else "Not listed, or I don't know which")
    }
    HorizontalDivider()
    if (s.place && s.lines.isEmpty()) {
        Text("Where", style = MaterialTheme.typography.titleMedium)
        Text("No crossings within half a mile of you. The report uses your position; RailSpotting " +
            "places it at the nearest crossing.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
    }
    if (s.lines.isNotEmpty()) {
        Text("Where", style = MaterialTheme.typography.titleMedium)
        Choice("Line", s.lines.map { it.label }, line) {
            line = it
            crossing = 0
        }
        val crossings = s.lines.getOrNull(line)?.crossings.orEmpty()
        Choice("Crossing", crossings.map { it.label }, crossing) { crossing = it }
        val tracks = listOf("Not sure") + PlaceLine.TRACKS.map { "Main $it" }
        Choice("Track", tracks, track ?: 0) { track = if (it == 0) null else PlaceLine.TRACKS[it - 1] }
        Text("Lines and crossings within half a mile of you.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
    }
    OutlinedTextField(locomotives, { locomotives = it.replace("\n", " ").take(200) }, Modifier.fillMaxWidth(),
        label = { Text("Locomotives") }, placeholder = { Text("NS 4321, NS 1234") }, singleLine = true)
    OutlinedTextField(cars, { v -> cars = v.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(),
        label = { Text("Cars") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    OutlinedTextField(body, { body = it.take(1000) }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 3)
    OutlinedTextField(link, { link = it.trim().take(600) }, Modifier.fillMaxWidth(), label = { Text("Video or photos link") },
        placeholder = { Text("YouTube or Google Photos") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    Text("Add at least one detail. Reports appear under your name, as notes do on the website.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = !sending, modifier = Modifier.fillMaxWidth(), onClick = {
        sending = true
        problem = null
        val input = JSONObject()
            .put("locomotives", locomotives).put("cars", cars).put("body", body).put("link", link)
        if (pick >= 0) input.put("train_id", s.trains[pick].id)
        s.lines.getOrNull(line)?.crossings?.getOrNull(crossing)?.let { input.put("crossing_id", it.id) }
        track?.let { input.put("track", it) }
        scope.launch {
            problem = send(input)
            sending = false
        }
    }) { Text(if (sending) "Sending…" else "Send report") }
}

/** A labelled dropdown: the choice shows on a button that opens the list. */
@Composable
private fun Choice(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.3f))
        Column(Modifier.weight(0.7f)) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.getOrElse(selected) { "" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = {
                        open = false
                        onSelect(i)
                    })
                }
            }
        }
    }
}


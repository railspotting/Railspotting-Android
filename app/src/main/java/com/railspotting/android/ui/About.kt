package com.railspotting.android.ui

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.railspotting.android.BuildConfig
import org.json.JSONObject

/** One piece of open source software in the app. */
data class OssItem(val name: String, val version: String, val license: String, val url: String,
                   val note: String = "", val text: String = "")

data class OssSection(val title: String, val items: List<OssItem>)

/** The list tools/open_source.py writes (assets/open_source.json). */
object OpenSource {
    const val ASSET = "open_source.json"

    fun parse(json: String): List<OssSection> {
        val sections = JSONObject(json).optJSONArray("sections") ?: return listOf()
        return (0 until sections.length()).mapNotNull { i ->
            val s = sections.optJSONObject(i) ?: return@mapNotNull null
            val items = s.optJSONArray("items")
            OssSection(s.optString("title"), (0 until (items?.length() ?: 0)).mapNotNull { j ->
                items?.optJSONObject(j)?.let {
                    OssItem(it.optString("name"), it.optString("version"), it.optString("license"), it.optString("url"),
                        it.optString("note"), it.optString("text"))
                }
            })
        }
    }
}

/** Full screen: the app's version, and the open source software it uses, with links and licences. */
@Composable
fun AboutScreen(activity: Activity, onClose: () -> Unit) {
    val sections = remember {
        try {
            OpenSource.parse(activity.assets.open(OpenSource.ASSET).bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            listOf()
        }
    }
    var licence by remember { mutableStateOf<OssItem?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Header(onLogo = null, action = { TextButton(onClick = onClose) { Text("Close") } })
                    }
                }
                item {
                    Text("About", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})" +
                        if (BuildConfig.DEBUG) ", test build" else "")
                    TextButton(onClick = { activity.openWeb("https://railspotting.com/") }) { Text("railspotting.com") }
                    HorizontalDivider()
                    Text("Open source software", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp))
                    Text("RailSpotting is built with the software below. Tap one to see its source.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                sections.forEach { section ->
                    item {
                        Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                    }
                    items(section.items) { o -> OssRow(activity, o) { licence = o } }
                }
                item { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
    licence?.let { o -> LicenceText(activity, o) { licence = null } }
}

@Composable
private fun OssRow(activity: Activity, o: OssItem, showLicence: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { activity.openWeb(o.url) }.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.name, fontWeight = FontWeight.Medium)
            if (o.version.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(o.version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(o.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (o.note.isNotEmpty()) Text(o.note, style = MaterialTheme.typography.bodySmall)
        if (o.text.isNotEmpty()) {
            TextButton(onClick = showLicence, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Read the licence")
            }
        }
    }
}

/** A licence's full text, shipped with the app (assets/licenses). */
@Composable
private fun LicenceText(activity: Activity, o: OssItem, onClose: () -> Unit) {
    val text = remember(o.text) {
        try {
            activity.assets.open(o.text).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            "See ${o.url}"
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${o.name}: ${o.license}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

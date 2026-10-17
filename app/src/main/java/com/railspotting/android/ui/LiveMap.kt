package com.railspotting.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.railspotting.android.location.LocationFeed

/**
 * The headend's live map page for apps (docs/embed-map.md). It is public and keeps no state but
 * the remembered map position in local storage, so the web view needs JavaScript, DOM storage and
 * the phone's position for the Here button, and nothing else: no cookies, no file access.
 *
 * No `?receiver=`: the page marks a receiver only when it is on the public map, and a phone
 * never is (contract v1.7, 6), so the device ID would only travel for nothing.
 */
@SuppressLint("SetJavaScriptEnabled")
fun newMapView(context: Context): WebView = WebView(context).apply {
    // A web view sized to its content gets a zero-height viewport, and the page lays itself
    // out in 100dvh: it must fill its space.
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setSupportMultipleWindows(true)
        javaScriptCanOpenWindowsAutomatically = false
        setGeolocationEnabled(true)
    }
    webViewClient = MapClient()
    webChromeClient = object : WebChromeClient() {
        // target="_blank" links: catch the address in a throwaway view, then hand it to the browser
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val catcher = WebView(view.context)
            catcher.webViewClient = WindowCatcher(view.context, isUserGesture)
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        // the Here button: the page asks for the phone's position (MapStart.shareLocation).
        // Not remembered, so a later change to the app's permission counts.
        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            val permitted = LocationFeed(context).permitted()
            callback.invoke(origin, MapStart.shareLocation(origin, permitted), false)
            if (!permitted) Toast.makeText(context, "Allow location for RailSpotting in Android settings to use Here.", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Load the map, opened on the phone about a mile across (MapStart) when its position comes
 * within a few seconds; else as the page last was. The position goes into the page's own
 * storage on this phone, nowhere else.
 */
@SuppressLint("MissingPermission") // checked: LocationFeed.permitted, and a refusal is caught
fun startMap(view: WebView, context: Context) {
    var done = false
    fun load(at: Location?) {
        if (done) return
        done = true
        if (at != null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val dm = context.resources.displayMetrics
            val zoom = MapStart.zoomFor(at.latitude, (dm.widthPixels / dm.density).toDouble())
            view.tag = WebViewCompat.addDocumentStartJavaScript(
                view, MapStart.script(at.latitude, at.longitude, zoom), setOf(MapStart.ORIGIN))
        }
        view.loadUrl(MapRoutes.MAP)
    }
    // never wait long for a fix
    Handler(Looper.getMainLooper()).postDelayed({ load(null) }, MapStart.WAIT_MS)
    if (!LocationFeed(context).permitted()) {
        load(null)
        return
    }
    val client = LocationServices.getFusedLocationProviderClient(context)
    val req = CurrentLocationRequest.Builder()
        .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        .setMaxUpdateAgeMillis(60_000)
        .setDurationMillis(MapStart.WAIT_MS - 500)
        .build()
    try {
        client.getCurrentLocation(req, null).addOnCompleteListener { t ->
            val fresh = if (t.isSuccessful) t.result else null
            if (fresh != null) load(fresh)
            else client.lastLocation.addOnCompleteListener { l -> load(if (l.isSuccessful) l.result else null) }
        }
    } catch (_: SecurityException) {
        load(null)
    }
}

/** The map's own client: which navigations stay (MapRoutes), the start script, a dead renderer. */
// lint does not see the Kotlin override of onRenderProcessGone below (AGP 9.4)
@SuppressLint("MissingOnRenderProcessGone")
private class MapClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (MapRoutes.navigation(url, request.isForMainFrame)) {
            MapRoutes.Route.Stay -> false
            MapRoutes.Route.Browser -> {
                openInBrowser(view.context, url)
                true
            }
            MapRoutes.Route.Drop -> true
        }
    }

    // the start position applies to the first load only; after that the page keeps its own
    override fun onPageFinished(view: WebView, url: String?) {
        (view.tag as? ScriptHandler)?.remove()
        view.tag = null
    }

    // the web renderer can be killed under memory pressure; without this the app goes too
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = mapGone(view)
}

/** A new window the page asked for (target="_blank"): its address goes to the browser. */
// as MapClient: onRenderProcessGone is overridden below
@SuppressLint("MissingOnRenderProcessGone")
private class WindowCatcher(private val context: Context, private val userGesture: Boolean) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (MapRoutes.newWindow(url) == MapRoutes.Route.Browser && userGesture) openInBrowser(context, url)
        view.destroy()
        return true
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.destroy()
        return true
    }
}

/**
 * The map's renderer died. The web view can't be used again; take it out of the screen so the
 * rest of the app carries on (the map comes back on the next start).
 */
private fun mapGone(view: WebView): Boolean {
    android.util.Log.w("RailSpotting", "live map renderer stopped; the map returns when the app restarts")
    (view.parent as? ViewGroup)?.removeView(view)
    view.destroy()
    return true
}

private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
        // no browser installed
    }
}

/** The live map, reusing one web view so switching tabs keeps its place. */
@Composable
fun LiveMap(view: WebView, modifier: Modifier = Modifier) {
    AndroidView(
        factory = {
            (view.parent as? ViewGroup)?.removeView(view)
            view
        },
        modifier = modifier,
    )
}

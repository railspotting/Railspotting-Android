package com.railspotting.android.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.railspotting.android.BuildConfig
import com.railspotting.android.R
import com.railspotting.android.RailSpottingApp
import com.railspotting.android.service.ListenService
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Updates the app from its GitHub releases, without a store. Release builds look once a day
 * (and when asked), download a newer APK, check it, and install it through Android's
 * installer, never while listening: a pending update waits for listening to stop.
 *
 * Android asks the owner to confirm the first update (a notification). After that the app is
 * the installer of its own updates, and Android 12 and later let them install without asking.
 */
object Updater {
    private const val TAG = "RailSpotting"
    private const val CHECK_EVERY_MS = 24 * 3600 * 1000L
    private const val NOTE_ID = 40
    const val ACTION_RESULT = "com.railspotting.android.UPDATE_RESULT"

    private val bg = Executors.newSingleThreadExecutor()

    /** Automatic checks: release builds only, at most once a day. */
    fun maybeCheck(context: Context) {
        val app = RailSpottingApp.of(context)
        if (BuildConfig.DEBUG || !app.prefs.autoUpdate) return
        if (System.currentTimeMillis() - app.prefs.lastUpdateCheck < CHECK_EVERY_MS) return
        checkNow(context)
    }

    /** Look now (the menu's Check now). The answer lands in prefs.updateStatus. */
    fun checkNow(context: Context, done: (() -> Unit)? = null) {
        val app = RailSpottingApp.of(context)
        bg.execute {
            app.prefs.lastUpdateCheck = System.currentTimeMillis()
            app.prefs.updateStatus = try {
                check(app)
            } catch (e: IOException) {
                Log.i(TAG, "update check failed: $e")
                "Could not reach GitHub: ${e.message ?: e.javaClass.simpleName}"
            }
            done?.invoke()
        }
    }

    private fun check(app: RailSpottingApp): String {
        val release = Release.parse(fetch(Release.LATEST)) ?: return "The latest release could not be read"
        if (!release.newerThan(BuildConfig.VERSION_CODE.toLong())) return "Up to date (${BuildConfig.VERSION_NAME})"
        if (BuildConfig.DEBUG) return "Version ${release.versionName} is out; test builds don't update themselves"
        val apk = File(File(app.cacheDir, "updates").apply { mkdirs() }, "railspotting-${release.versionCode}.apk")
        if (!apk.exists() || sha256(apk) != release.sha256) {
            download(release.apkUrl, apk)
            if (sha256(apk) != release.sha256) {
                apk.delete()
                return "The download of ${release.versionName} did not match its checksum; not installed"
            }
        }
        problem(app, apk, release)?.let {
            apk.delete()
            return it
        }
        app.prefs.pendingUpdate = apk.absolutePath
        Log.i(TAG, "update ${release.versionName} downloaded")
        return if (installPending(app)) "Installing ${release.versionName}"
        else "Version ${release.versionName} installs when listening stops"
    }

    /** Why this APK must not be installed, or null when it may be. */
    private fun problem(context: Context, apk: File, release: Release): String? {
        val pm = context.packageManager
        val theirs = archiveInfo(pm, apk) ?: return "The download is not an app"
        if (theirs.packageName != context.packageName) return "The download is another app"
        if (theirs.longVersionCode != release.versionCode) return "The download is not version ${release.versionName}"
        val mine = installedInfo(pm, context.packageName)
        if (signers(theirs).isEmpty() || signers(theirs) != signers(mine)) {
            return "Version ${release.versionName} is signed with another key; not installed"
        }
        return null
    }

    /**
     * Install the downloaded update unless listening. True when the install started. Called
     * after a check, and when listening stops.
     */
    fun installPending(context: Context): Boolean {
        val app = RailSpottingApp.of(context)
        val path = app.prefs.pendingUpdate ?: return false
        val apk = File(path)
        if (!apk.exists()) {
            app.prefs.pendingUpdate = null
            return false
        }
        if (ListenService.running) return false
        return try {
            install(context, apk)
            app.prefs.pendingUpdate = null
            true
        } catch (e: IOException) {
            Log.w(TAG, "update install failed: $e")
            app.prefs.updateStatus = "Install failed: ${e.message}"
            false
        }
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // once the app installed itself, later updates need no confirmation (Android 12+)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = Intent(ACTION_RESULT).setPackage(context.packageName)
            val pi = PendingIntent.getBroadcast(context, id, result,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi.intentSender)
        }
    }

    /** Android wants the owner to confirm: a notification that opens its prompt. */
    fun askToConfirm(context: Context, confirm: Intent) {
        val pi = PendingIntent.getActivity(context, NOTE_ID, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, RailSpottingApp.CHANNEL_ACCOUNT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("RailSpotting update ready")
            .setContentText("Tap to install the new version.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTE_ID, n)
    }

    private fun fetch(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, to: File) {
        val c = open(url)
        try {
            val tmp = File(to.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(to)) throw IOException("could not save the download")
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true // GitHub sends release files from its download servers
        c.setRequestProperty("User-Agent", "RailSpotting-Android/${BuildConfig.VERSION_NAME}")
        if (c.responseCode != 200) {
            c.disconnect()
            throw IOException("HTTP ${c.responseCode}")
        }
        return c
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, name: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    /** The SHA-256 of each signing certificate. */
    private fun signers(p: PackageInfo): Set<String> {
        val s = p.signingInfo ?: return emptySet()
        val certs = if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        return certs.orEmpty().map { c ->
            MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}

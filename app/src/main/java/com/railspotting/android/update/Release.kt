package com.railspotting.android.update

import org.json.JSONException
import org.json.JSONObject

/**
 * A published release, as `latest.json` on the app's GitHub releases describes it:
 *
 * ```json
 * {"version_code": 5, "version_name": "0.2.0", "sha256": "…64 hex…",
 *  "apk": "https://github.com/railspotting/Railspotting-Android/releases/download/v0.2.0/railspotting-0.2.0.apk",
 *  "notes": "What changed"}
 * ```
 */
data class Release(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
) {
    companion object {
        const val REPO = "https://github.com/railspotting/Railspotting-Android"
        /** GitHub serves the newest release's files here. */
        const val LATEST = "$REPO/releases/latest/download/latest.json"
        /** APKs are taken only from this repository's own releases. */
        const val APK_PREFIX = "$REPO/releases/download/"
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** The release, or null when the file is not one we can trust or use. */
        fun parse(json: String): Release? {
            val o = try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
            val code = o.optLong("version_code", -1)
            val name = o.optString("version_name")
            val apk = o.optString("apk")
            val sha = o.optString("sha256").lowercase()
            if (code <= 0 || name.isBlank() || !apk.startsWith(APK_PREFIX) || apk.contains("..") || !SHA256.matches(sha)) {
                return null
            }
            return Release(code, name, apk, sha, o.optString("notes"))
        }
    }

    fun newerThan(installedCode: Long) = versionCode > installedCode
}

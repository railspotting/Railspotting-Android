package com.railspotting.android.core

import org.json.JSONObject

/** One event the phone heard, as the status lists it (newest first). */
data class Heard(
    val eventId: String,
    val freq: Double,
    val label: String,
    val kind: String,
    /** Still on the air. */
    val open: Boolean,
    val startTs: Double,
    val endTs: Double?,
    val peakRssi: Int,
    /** A decoded end-of-train packet, in words ("EOT unit 41234, 88 psi, moving"). */
    val decoded: String,
    val unitId: Long?,
    val brakePipePsi: Long?,
    val moving: Boolean?,
    /** Opened together with many channels: interference across the band, not a call. */
    val burst: Boolean = false,
) {
    val telemetry: Boolean get() = kind == "telemetry"
}

/** A channel the dongle watches. */
data class Watched(val freq: Double, val label: String, val kind: String, val receiving: Boolean)

/** The voice call live listen is playing. */
data class Playing(val eventId: String, val freq: Double, val label: String, val since: Double)

/** The dongle, as the core reports it. */
data class RadioStatus(
    val mode: String = "eot",
    val attached: Boolean = false,
    val connected: Boolean = false,
    val tuner: String = "",
    val error: String = "",
    val live: Boolean = false,
    val playing: Playing? = null,
    val channels: List<Watched> = listOf(),
)

/** The channel plan in use: the headend's name for it when it sends one (plans by location). */
data class PlanInfo(val name: String = "", val region: String = "", val source: String = "", val channels: Int = 0,
                    val byLocation: Boolean = false) {
    /** "NS Charlotte, 9 channels" or "9 channels" */
    val text: String get() = (if (name.isNotEmpty()) "$name, " else "") + "$channels channel" + if (channels == 1) "" else "s"

    companion object {
        fun parse(p: JSONObject?): PlanInfo {
            if (p == null) return PlanInfo()
            fun str(k: String) = if (p.isNull(k)) "" else p.optString(k)
            return PlanInfo(str("name"), str("region"), str("source"), p.optInt("channels"), p.optBoolean("by_location"))
        }
    }
}

/** The core's status (Native.status), for the screens and the notification. */
data class Status(
    val running: Boolean = false,
    val online: Boolean = false,
    val listening: Boolean = false,
    val radio: RadioStatus = RadioStatus(),
    val plan: PlanInfo = PlanInfo(),
    val share: Boolean = true,
    val fixAgeS: Double? = null,
    val fixesSent: Long = 0,
    val outboxDepth: Long = 0,
    val eventsUploaded: Long = 0,
    val signedOut: String? = null,
    val lastError: String = "",
    val heard: List<Heard> = listOf(),
) {
    companion object {
        fun parse(text: String?): Status {
            val o = try {
                JSONObject(text ?: return Status())
            } catch (_: org.json.JSONException) {
                return Status()
            }
            val recent = o.optJSONArray("recent_events")
            val heard = (0 until (recent?.length() ?: 0)).mapNotNull { i ->
                recent?.optJSONObject(i)?.let { heard(it) }
            }
            return Status(
                running = o.optBoolean("running"),
                online = o.optBoolean("online"),
                listening = o.optBoolean("listening"),
                radio = radio(o.optJSONObject("radio") ?: JSONObject()),
                plan = PlanInfo.parse(o.optJSONObject("plan")),
                share = o.optBoolean("share", true),
                fixAgeS = o.optNumber("fix_age_s"),
                fixesSent = o.optLong("fixes_sent"),
                outboxDepth = o.optLong("outbox_depth"),
                eventsUploaded = o.optLong("events_uploaded"),
                signedOut = o.optStringOrEmpty("signed_out").ifEmpty { null },
                lastError = o.optStringOrEmpty("last_error"),
                heard = heard,
            )
        }

        private fun heard(e: JSONObject): Heard {
            val tel = e.optJSONObject("telemetry")
            return Heard(
                eventId = e.optStringOrEmpty("event_id"),
                freq = e.optDouble("freq", 0.0),
                label = e.optStringOrEmpty("label"),
                kind = e.optStringOrEmpty("kind"),
                open = e.optString("state") == "open",
                startTs = e.optDouble("start_ts", 0.0),
                endTs = e.optNumber("end_ts"),
                peakRssi = e.optInt("peak_rssi"),
                decoded = e.optStringOrEmpty("decoded"),
                unitId = tel?.optNumber("unit_id")?.toLong(),
                brakePipePsi = tel?.optNumber("brake_pipe_psi")?.toLong(),
                moving = if (tel == null || tel.isNull("motion") || !tel.has("motion")) null else tel.optBoolean("motion"),
                burst = e.optBoolean("burst"),
            )
        }

        private fun radio(r: JSONObject): RadioStatus {
            val chans = r.optJSONArray("channels")
            val p = r.optJSONObject("now_playing")
            return RadioStatus(
                mode = r.optString("mode", "eot"),
                attached = r.optBoolean("attached"),
                connected = r.optBoolean("connected"),
                tuner = r.optStringOrEmpty("tuner"),
                error = r.optStringOrEmpty("error"),
                live = r.optBoolean("live"),
                playing = p?.let {
                    Playing(it.optStringOrEmpty("event_id"), it.optDouble("freq", 0.0), it.optStringOrEmpty("label"),
                        it.optDouble("since", 0.0))
                },
                channels = (0 until (chans?.length() ?: 0)).mapNotNull { i ->
                    chans?.optJSONObject(i)?.let {
                        Watched(it.optDouble("freq", 0.0), it.optStringOrEmpty("label"), it.optStringOrEmpty("kind"),
                            it.optBoolean("receiving"))
                    }
                },
            )
        }

        /** A JSON null reads as "" here, not as the text "null". */
        private fun JSONObject.optStringOrEmpty(k: String) = if (isNull(k)) "" else optString(k)

        /** A number, or null when absent or JSON null. */
        private fun JSONObject.optNumber(k: String): Double? = if (isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

        /** "12 s ago", "3 min ago", "2 h ago"; "none yet" without a fix. */
        fun age(seconds: Double?): String = when {
            seconds == null -> "none yet"
            seconds < 90 -> "${seconds.toLong()} s ago"
            seconds < 90 * 60 -> "${(seconds / 60).toLong()} min ago"
            else -> "${(seconds / 3600).toLong()} h ago"
        }

        /** "0:12", "3:05": how long a call has been on the air. */
        fun clock(seconds: Double): String {
            val s = seconds.coerceAtLeast(0.0).toLong()
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}

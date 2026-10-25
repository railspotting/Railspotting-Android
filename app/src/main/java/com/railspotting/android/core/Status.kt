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

/** A wayside unit (signal or crossing) as the ITC dongle last heard it. */
data class ItcUnit(
    val wiu: String,
    val codes: List<Int>,
    /** Each code's aspect name where public material gives one ("Clear"), else null. */
    val aspects: List<String?>,
    val previous: List<Int>?,
    val changedS: Double,
    val heardS: Double,
    val channel: Int,
) {
    /** "123456 0ff0": the area's group, then the unit. */
    val name: String get() = if (wiu.length == 10) wiu.substring(0, 6) + " " + wiu.substring(6) else wiu
    val state: String get() = Itc.state(codes, aspects)
}

/** One wayside unit's change of state. */
data class ItcChange(val wiu: String, val codes: List<Int>, val aspects: List<String?>, val previous: List<Int>, val agoS: Double) {
    val name: String get() = if (wiu.length == 10) wiu.substring(0, 6) + " " + wiu.substring(6) else wiu
}

/** A base station whose beacon the ITC dongle hears. */
data class ItcBase(val radio: String, val localChannel: Int, val levelDb: Double, val heardS: Double)

/** The ITC dongle: what it watches and what it hears. Kept on the phone; never sent. */
data class ItcStatus(
    val channels: List<Int> = listOf(),
    val messages: Long = 0,
    val lastMessageS: Double? = null,
    val bases: List<ItcBase> = listOf(),
    val units: List<ItcUnit> = listOf(),
    val changes: List<ItcChange> = listOf(),
    val error: String = "",
)

object Itc {
    /** "Approach, code 15": the aspect where it is known, the device code otherwise. */
    fun state(codes: List<Int>, aspects: List<String?>): String =
        codes.mapIndexed { i, c -> aspects.getOrNull(i) ?: "code $c" }.joinToString(", ").ifEmpty { "no devices" }
}

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
    /** In ITC mode. */
    val itc: ItcStatus? = null,
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
                itc = r.optJSONObject("itc")?.let { itc(it) },
            )
        }

        private fun ints(a: org.json.JSONArray?): List<Int> = (0 until (a?.length() ?: 0)).map { a!!.optInt(it) }

        private fun names(a: org.json.JSONArray?): List<String?> =
            (0 until (a?.length() ?: 0)).map { if (a!!.isNull(it)) null else a.optString(it) }

        private fun objects(a: org.json.JSONArray?): List<JSONObject> =
            (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }

        private fun itc(o: JSONObject) = ItcStatus(
            channels = ints(o.optJSONArray("channels")),
            messages = o.optLong("messages"),
            lastMessageS = o.optNumber("last_message_s"),
            bases = objects(o.optJSONArray("bases")).map {
                ItcBase(it.optStringOrEmpty("radio"), it.optInt("local_channel"), it.optDouble("level_db", 0.0), it.optDouble("heard_s", 0.0))
            },
            units = objects(o.optJSONArray("units")).map {
                ItcUnit(it.optStringOrEmpty("wiu"), ints(it.optJSONArray("codes")), names(it.optJSONArray("aspects")),
                    it.optJSONArray("previous_codes")?.let { p -> ints(p) }, it.optDouble("changed_s", 0.0),
                    it.optDouble("heard_s", 0.0), it.optInt("channel"))
            },
            changes = objects(o.optJSONArray("changes")).map {
                ItcChange(it.optStringOrEmpty("wiu"), ints(it.optJSONArray("codes")), names(it.optJSONArray("aspects")),
                    ints(it.optJSONArray("previous_codes")), it.optDouble("ago_s", 0.0))
            },
            error = o.optStringOrEmpty("error"),
        )

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

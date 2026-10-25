package com.railspotting.android.core

/**
 * The receiver core (Rust, `core/` in this repository). Calls that answer with data return JSON
 * text, so there are no models to keep in step on this side.
 *
 * The seed passed in is the receiver's private key: callers wipe their copy after the call.
 */
object Native {
    init {
        System.loadLibrary("railspotting_mobile")
    }

    /**
     * For calls from the screens that reopen the dongle (mode, gain): they wait for its threads
     * to stop, so they run here, in order, never on the main thread.
     */
    val reopen: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Set the log up: Android's log, the in-app list, and files in `logDir` (kept across restarts). */
    @JvmStatic external fun init(debug: Boolean, logDir: String)

    /** A new Ed25519 key: its 32-byte seed. */
    @JvmStatic external fun newSeed(): ByteArray

    /** The public key for a seed: base64url, 43 characters. */
    @JvmStatic external fun publicKey(seed: ByteArray): String

    /** GET /mobile/hello: `{"ok": true, "data": {...}}` or `{"ok": false, "error": ...}`. */
    @JvmStatic external fun hello(server: String, seed: ByteArray): String

    /** POST /mobile/link: `{"ok": true, "status": 201, "data": {...}}` or an error object. */
    @JvmStatic external fun link(
        server: String, seed: ByteArray, idToken: String, nonce: String, name: String,
        model: String, androidVersion: String, appVersion: String,
        integrityToken: String, integrityError: String,
    ): String

    /** Start the receiver. Returns "" when it started, else what went wrong. */
    @JvmStatic external fun start(
        server: String, stateDir: String, seed: ByteArray, deviceId: String, appVersion: String, share: Boolean,
    ): String

    /** Stop the receiver. It blocks while queued events get a last chance to go: call it off the main thread. */
    @JvmStatic external fun stop()

    @JvmStatic external fun status(): String
    @JvmStatic external fun log(): String

    /** `{"share", "foreground", "battery_pct", "charging"}` for the heartbeat. */
    @JvmStatic external fun setPhone(state: String)

    /** One fix as the contract's LocationFix. */
    @JvmStatic external fun addFix(fix: String)

    /** Stop and remove the receiver's folder (signed out). */
    @JvmStatic external fun forget(stateDir: String)

    /**
     * New EOT alerts from other receivers within `radiusKm` of the phone, from the public live
     * map's feed: `{"ok": true, "alerts": [...]}`. Blocks for the fetches: off the main thread.
     */
    @JvmStatic external fun nearbyEot(lat: Double, lon: Double, radiusKm: Double): String

    /** The crossings near the phone for the car screen: `{"ok": true, "crossings": [...]}`. Blocks. */
    @JvmStatic external fun nearbyCrossings(lat: Double, lon: Double, radiusKm: Double): String

    /**
     * Refresh the channel list for where the phone is (`fix`: a LocationFix, or "{}"):
     * `{"ok", "sent_fix", "moved", "plan": {...}}`. Blocks for the headend calls.
     */
    @JvmStatic external fun refreshPlan(fix: String): String

    // The RTL-SDR dongle: this app opens it (USB permission) and the core drives it.

    /** The dongle is open; `fd` is its USB file descriptor, which stays open here. */
    @JvmStatic external fun dongleAttached(fd: Int)

    /** Call before closing the dongle's connection. */
    @JvmStatic external fun dongleDetached()

    /** "voice" (road channels, live listen), "eot" (end-of-train, decoded) or "itc" (220 MHz PTC). */
    @JvmStatic external fun setMode(mode: String)

    /** The dongle's tuner gain: "auto" or dB ("40.2"). The dongle reopens with it. */
    @JvmStatic external fun setGain(gain: String)

    /** The headend takes train reports (its cached /config, in `stateDir`). Proposed v1.13. */
    @JvmStatic external fun trainReports(stateDir: String): Boolean

    /** Fetch the headend's settings now: `{"ok": true, "train_reports": bool}` or an error. Blocks; no listening needed. */
    @JvmStatic external fun refreshConfig(server: String, seed: ByteArray, deviceId: String, stateDir: String): String

    /** The headend takes a chosen crossing and track on a report (cached /config). Proposed v1.14. */
    @JvmStatic external fun trainReportPlace(stateDir: String): Boolean

    /** Lines and crossings within half a mile, from the public live map: `{"ok": true, "lines": [...]}`. Blocks. */
    @JvmStatic external fun reportPlaces(lat: Double, lon: Double): String

    /** Trains near the phone: `{"ok": true, "trains": [...]}` or an error. Blocks; works without listening. */
    @JvmStatic external fun nearbyTrains(server: String, seed: ByteArray, deviceId: String, lat: Double, lon: Double): String

    /**
     * Report a train: `input` is `{"train_id"?, "locomotives", "cars", "body", "link"}`, `fix` a
     * LocationFix. `{"ok": true, "url"?: ...}` or `{"ok": false, "message": ...}`. Blocks.
     */
    @JvmStatic external fun reportTrain(server: String, seed: ByteArray, deviceId: String, input: String, fix: String): String

    /** Live listen on or off. */
    @JvmStatic external fun setLive(on: Boolean)

    /** The live listen audio rate in Hz. */
    @JvmStatic external fun audioRate(): Int

    /** Live listen audio: mono floats, waiting up to `timeoutMs` for some; empty between calls. */
    @JvmStatic external fun audio(max: Int, timeoutMs: Int): FloatArray?
}

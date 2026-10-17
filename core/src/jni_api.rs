//! The JNI surface for `com.railspotting.android.core.Native`. Each call is small and passes JSON
//! strings, so the Kotlin side has no models to keep in step. A panic never crosses into the JVM:
//! each call catches it and answers as for an error.

use crate::agent;
use crate::link::{self, Device};
use crate::radio::{self, Mode};
use crate::{logbuf, trains, StartArgs, CORE};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jfloatArray, jint, jstring, JNI_FALSE};
use jni::JNIEnv;
use railspotting_proto::api::ApiClient;
use serde_json::{json, Value};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use std::time::Duration;

fn text(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).map(Into::into).unwrap_or_default()
}

fn bytes(env: &JNIEnv, a: &JByteArray) -> Vec<u8> {
    if a.is_null() {
        return vec![];
    }
    env.convert_byte_array(a).unwrap_or_default()
}

fn out(env: &mut JNIEnv, s: String) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn out_bytes(env: &mut JNIEnv, b: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(b).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

fn guard<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| {
        log::error!("internal error in the receiver core");
        fallback
    })
}

fn wipe(seed: &mut [u8]) {
    seed.iter_mut().for_each(|b| *b = 0);
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_init(mut env: JNIEnv, _c: JClass, debug: jboolean,
                                                                     log_dir: JString) {
    let dir = text(&mut env, &log_dir);
    guard((), || {
        logbuf::init(debug != JNI_FALSE);
        if !dir.is_empty() {
            logbuf::to_file(std::path::Path::new(&dir));
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_newSeed(mut env: JNIEnv, _c: JClass) -> jbyteArray {
    let mut seed = guard([0u8; 32], link::new_seed);
    let r = out_bytes(&mut env, &seed);
    wipe(&mut seed);
    r
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_publicKey(mut env: JNIEnv, _c: JClass, seed: JByteArray)
                                                                          -> jstring {
    let mut s = bytes(&env, &seed);
    let pk = guard(String::new(), || link::public_key(&s).unwrap_or_default());
    wipe(&mut s);
    out(&mut env, pk)
}

/// GET /mobile/hello. `{"ok": true, "data": {...}}` or an error object.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_hello(mut env: JNIEnv, _c: JClass, server: JString,
                                                                      seed: JByteArray) -> jstring {
    let server = text(&mut env, &server);
    let mut s = bytes(&env, &seed);
    let r = guard(json!({"ok": false, "error": "internal"}), || {
        let key = match link::key_from_seed(&s) {
            Ok(k) => k,
            Err(e) => return json!({"ok": false, "error": "bad_key", "message": e}),
        };
        let api = match link::client(&server, &key, None, CORE.skew.clock()) {
            Ok(a) => a,
            Err(e) => return link::error_json(&e),
        };
        let r = link::hello(&api);
        CORE.skew.absorb(&api);
        match r {
            Ok(data) => json!({"ok": true, "data": data}),
            Err(e) => link::error_json(&e),
        }
    });
    wipe(&mut s);
    out(&mut env, r.to_string())
}

/// POST /mobile/link. `{"ok": true, "status": 201|200, "data": {...}}` or an error object.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_railspotting_android_core_Native_link(
    mut env: JNIEnv, _c: JClass, server: JString, seed: JByteArray, id_token: JString, nonce: JString, name: JString,
    model: JString, android_version: JString, app_version: JString, integrity_token: JString,
    integrity_error: JString) -> jstring {
    let server = text(&mut env, &server);
    let id_token = text(&mut env, &id_token);
    let nonce = text(&mut env, &nonce);
    let name = text(&mut env, &name);
    let integrity = link::Integrity { token: text(&mut env, &integrity_token), error: text(&mut env, &integrity_error) };
    let device = Device {
        model: text(&mut env, &model),
        android_version: text(&mut env, &android_version),
        app_version: text(&mut env, &app_version),
    };
    let mut s = bytes(&env, &seed);
    let r = guard(json!({"ok": false, "error": "internal"}), || {
        let key = match link::key_from_seed(&s) {
            Ok(k) => k,
            Err(e) => return json!({"ok": false, "error": "bad_key", "message": e}),
        };
        let api = match link::client(&server, &key, None, CORE.skew.clock()) {
            Ok(a) => a,
            Err(e) => return link::error_json(&e),
        };
        let pk = railspotting_proto::keys::public_b64(&key);
        let body = link::link_body(&id_token, &nonce, &pk, Some(&name), &device, &integrity);
        let r = link::link(&api, &body);
        CORE.skew.absorb(&api);
        match r {
            Ok((status, data)) => json!({"ok": true, "status": status, "data": data}),
            Err(e) => link::error_json(&e),
        }
    });
    wipe(&mut s);
    out(&mut env, r.to_string())
}

// train reports (proposed v1.13; trains.rs) --------------------------------------------------

/// A signed client for the phone's own device, without the receiver running.
fn device_client(env: &mut JNIEnv, server: &JString, seed: &JByteArray, device_id: &JString) -> Result<ApiClient, Value> {
    let server = text(env, server);
    let device_id = text(env, device_id);
    let mut s = bytes(env, seed);
    let key = link::key_from_seed(&s);
    wipe(&mut s);
    let key = key.map_err(|e| json!({"ok": false, "error": "bad_key", "message": e}))?;
    link::client(&server, &key, Some(device_id), CORE.skew.clock()).map_err(|e| link::error_json(&e))
}

/// Whether the headend takes train reports, from the cached /config in `state_dir`.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_trainReports(mut env: JNIEnv, _c: JClass, state_dir: JString) -> jboolean {
    let dir = text(&mut env, &state_dir);
    let on = guard(false, || trains::enabled(&railspotting_common::load_cached_config(std::path::Path::new(&dir))));
    on as jboolean
}

/// Fetch the headend's settings now (train reports work without listening):
/// `{"ok": true, "train_reports": bool}` or an error.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_refreshConfig(
    mut env: JNIEnv, _c: JClass, server: JString, seed: JByteArray, device_id: JString, state_dir: JString) -> jstring {
    let dir = PathBuf::from(text(&mut env, &state_dir));
    let r = match device_client(&mut env, &server, &seed, &device_id) {
        Ok(api) => guard(json!({"ok": false, "error": "internal"}), || {
            let r = trains::refresh_config(&api, &dir);
            CORE.skew.absorb(&api);
            r
        }),
        Err(e) => e,
    };
    out(&mut env, r.to_string())
}

/// Trains the headend knows near the phone: `{"ok": true, "trains": [...]}` or an error.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_nearbyTrains(
    mut env: JNIEnv, _c: JClass, server: JString, seed: JByteArray, device_id: JString, lat: jdouble, lon: jdouble) -> jstring {
    let r = match device_client(&mut env, &server, &seed, &device_id) {
        Ok(api) => guard(json!({"ok": false, "error": "internal"}), || {
            let r = trains::nearby(&api, lat, lon);
            CORE.skew.absorb(&api);
            r
        }),
        Err(e) => e,
    };
    out(&mut env, r.to_string())
}

/// Report a train: `input` is what the owner entered, `fix` the phone's position.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_reportTrain(
    mut env: JNIEnv, _c: JClass, server: JString, seed: JByteArray, device_id: JString, input: JString, fix: JString) -> jstring {
    let input: Value = serde_json::from_str(&text(&mut env, &input)).unwrap_or(Value::Null);
    let fix: Value = serde_json::from_str(&text(&mut env, &fix)).unwrap_or(Value::Null);
    let r = match device_client(&mut env, &server, &seed, &device_id) {
        Ok(api) => guard(json!({"ok": false, "error": "internal"}), || {
            match trains::sighting_body(&input, &fix, (CORE.skew.clock())()) {
                Ok(body) => {
                    let r = trains::report(&api, &body);
                    CORE.skew.absorb(&api);
                    r
                }
                Err(message) => json!({"ok": false, "error": "invalid", "message": message}),
            }
        }),
        Err(e) => e,
    };
    out(&mut env, r.to_string())
}

/// Start the receiver. Returns "" when it started, else what went wrong.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_start(
    mut env: JNIEnv, _c: JClass, server: JString, state_dir: JString, seed: JByteArray, device_id: JString,
    app_version: JString, share: jboolean) -> jstring {
    let args = StartArgs {
        server_url: text(&mut env, &server),
        state_dir: PathBuf::from(text(&mut env, &state_dir)),
        seed: bytes(&env, &seed),
        device_id: text(&mut env, &device_id),
        app_version: text(&mut env, &app_version),
        share: share != JNI_FALSE,
    };
    let r = guard(Err("internal error".to_string()), || CORE.start(args));
    out(&mut env, r.err().unwrap_or_default())
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_stop(_env: JNIEnv, _c: JClass) {
    guard((), || CORE.stop());
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_status(mut env: JNIEnv, _c: JClass) -> jstring {
    let s = guard(json!({"running": false}), || CORE.status());
    out(&mut env, s.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_log(mut env: JNIEnv, _c: JClass) -> jstring {
    let s = guard(String::new(), || logbuf::lines().join("\n"));
    out(&mut env, s)
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_setPhone(mut env: JNIEnv, _c: JClass, state: JString) {
    let s = text(&mut env, &state);
    guard((), || CORE.set_phone(&serde_json::from_str::<Value>(&s).unwrap_or(Value::Null)));
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_addFix(mut env: JNIEnv, _c: JClass, fix: JString) {
    let s = text(&mut env, &fix);
    guard((), || CORE.add_fix(&serde_json::from_str::<Value>(&s).unwrap_or(Value::Null)));
}

/// Remove the receiver's folder (signed out or revoked). Stops the receiver first.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_forget(mut env: JNIEnv, _c: JClass, state_dir: JString) {
    let dir = PathBuf::from(text(&mut env, &state_dir));
    guard((), || {
        CORE.stop();
        agent::forget(&dir);
    });
}

/// New EOT alerts from other receivers within `radius_km` of the phone: `{"ok", "alerts": [...]}`.
/// Blocks for the fetches.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_nearbyEot(mut env: JNIEnv, _c: JClass, lat: jdouble,
                                                                          lon: jdouble, radius_km: jdouble) -> jstring {
    let r = guard(json!({"ok": false, "error": "internal"}), || CORE.nearby_eot(lat, lon, radius_km));
    out(&mut env, r.to_string())
}

/// The crossings near the phone for the car screen: `{"ok", "crossings": [...]}`. Blocks.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_nearbyCrossings(mut env: JNIEnv, _c: JClass, lat: jdouble,
                                                                                lon: jdouble, radius_km: jdouble) -> jstring {
    let r = guard(json!({"ok": false, "error": "internal"}), || CORE.nearby_crossings(lat, lon, radius_km));
    out(&mut env, r.to_string())
}

/// Refresh the channel list for where the phone is; `fix` is a LocationFix or "{}". Blocks.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_refreshPlan(mut env: JNIEnv, _c: JClass, fix: JString) -> jstring {
    let s = text(&mut env, &fix);
    let r = guard(json!({"ok": false, "error": "internal"}), || {
        CORE.refresh_plan(&serde_json::from_str::<Value>(&s).unwrap_or(Value::Null))
    });
    out(&mut env, r.to_string())
}

// the dongle -----------------------------------------------------------------------------------

/// The app opened the dongle; `fd` is its USB file descriptor (it stays open in the app).
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_dongleAttached(_env: JNIEnv, _c: JClass, fd: jint) {
    guard((), || CORE.dongle_attached(fd as i64));
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_dongleDetached(_env: JNIEnv, _c: JClass) {
    guard((), || CORE.dongle_detached());
}

/// "voice" or "eot".
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_setMode(mut env: JNIEnv, _c: JClass, mode: JString) {
    let m = text(&mut env, &mode);
    guard((), || match Mode::parse(&m) {
        Some(mode) => CORE.set_mode(mode),
        None => log::warn!("unknown radio mode {m}"),
    });
}

/// "auto", or the gain in dB ("40.2").
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_setGain(mut env: JNIEnv, _c: JClass, gain: JString) {
    let g = text(&mut env, &gain);
    guard((), || CORE.set_gain(radio::parse_gain(&g)));
}

#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_setLive(_env: JNIEnv, _c: JClass, on: jboolean) {
    guard((), || CORE.set_live(on != JNI_FALSE));
}

/// The live listen audio rate in Hz (16000 at the usual sample rate).
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_audioRate(_env: JNIEnv, _c: JClass) -> jint {
    guard(16_000, || CORE.audio_rate().round() as jint)
}

/// Live listen audio, mono floats at audioRate(); empty when nothing is on the air.
#[no_mangle]
pub extern "system" fn Java_com_railspotting_android_core_Native_audio(env: JNIEnv, _c: JClass, max: jint, timeout_ms: jint)
                                                                      -> jfloatArray {
    let d = Duration::from_millis(timeout_ms.max(0) as u64);
    let samples = guard(vec![], || CORE.audio(max.max(0) as usize, d));
    match env.new_float_array(samples.len() as i32) {
        Ok(arr) => {
            let _ = env.set_float_array_region(&arr, 0, &samples);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}
